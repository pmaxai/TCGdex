"""Price history: a daily archive of every card's prices, backfilled, and its compact form for the app.

Archive (pipeline cache, kept durable as monthly tar files in the 'price-history' release):
  days/YYYY-MM-DD.npz   ids (card ids) + vals (int32 cents per FIELD, -1 = missing) + source
  ecb.csv               the ECB's euro reference rates (USD per EUR) per day

Sources:
- every run: today's Cardmarket (EUR) and TCGplayer (USD) prices from the TCGdex catalog
- backfill, once: TCGplayer prices from TCGCSV's daily archives (since 2024-02-08), found by
  the TCGplayer product ids TCGdex lists per card; weekly for a year, daily for 90 days.
  Each archive is downloaded once and never again (TCGCSV's rules).

App (catalog table 'history'): one shared calendar (weekly for a year, daily for the last
90 days), per card and field a zlib-compressed float16 series (NaN = no price), plus the
euro-dollar rate on each calendar day.
"""
from __future__ import annotations

import csv
import io
import json
import logging
import shutil
import sqlite3
import subprocess
import tarfile
import time
import warnings
import zipfile
import zlib
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import requests

log = logging.getLogger(__name__)

# Cardmarket (EUR): the standard price (normal, or the only printing) and the holo one (reverse holo);
# TCGplayer (USD) market price per printing
FIELDS = ["cm_trend", "cm_avg30", "cm_low", "cm_trend_h", "cm_avg30_h", "cm_low_h",
          "tp_normal", "tp_holo", "tp_reverse", "tp_first"]
FI = {f: i for i, f in enumerate(FIELDS)}
# the fields the app gets a history of
APP_FIELDS = ["cm_trend", "cm_trend_h", "tp_normal", "tp_holo", "tp_reverse", "tp_first"]
TCGCSV_START = date(2024, 2, 8)
TCGCSV_URL = "https://tcgcsv.com/archive/tcgplayer/prices-{d}.ppmd.7z"
TCGCSV_CATEGORIES = ("3", "85")      # Pokémon, Pokémon Japan
ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.zip"
UA = "card-scanner-pipeline/1.1 (price history for a private card scanner app)"
DAILY_DAYS = 90
YEAR_DAYS = 365


def tp_field(sub_type: str | None) -> str | None:
    """TCGplayer printing (TCGdex key or TCGCSV subTypeName) -> our field."""
    s = (sub_type or "").lower().replace(" ", "-")
    if s.startswith("1st-edition"):
        return "tp_first"
    if "reverse" in s:
        return "tp_reverse"
    if "holo" in s:
        return "tp_holo"
    if s in ("normal", "unlimited", "unlimited-normal"):
        return "tp_normal"
    return None


def _cents(v) -> int:
    try:
        f = float(v)
    except (TypeError, ValueError):
        return -1
    return int(round(f * 100)) if f > 0 else -1


def calendar(today: date) -> list[date]:
    """Weekly points (Mondays) for the past year, then every day of the last 90 days (oldest
    first). The weekly points stay put from one day to the next, so a backfilled day keeps
    being used and the backfill has nothing new to fetch once the year is filled."""
    daily_from = today - timedelta(days=DAILY_DAYS)
    monday = daily_from - timedelta(days=daily_from.weekday() or 7)       # the last Monday before
    weekly = [monday - timedelta(days=7 * k) for k in range(0, 60)]
    weekly = [d for d in weekly if (today - d).days <= YEAR_DAYS]
    return sorted(weekly) + [daily_from + timedelta(days=k) for k in range(DAILY_DAYS + 1)]


# ------------------------------------------------------------------ archive files
class Archive:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.days = self.root / "days"
        self.days.mkdir(parents=True, exist_ok=True)
        self.touched: set[str] = set()

    def path(self, d: date) -> Path:
        return self.days / f"{d.isoformat()}.npz"

    def have(self) -> set[date]:
        return {date.fromisoformat(p.stem) for p in self.days.glob("*.npz")}

    def load(self, d: date) -> tuple[np.ndarray, np.ndarray, str] | None:
        p = self.path(d)
        if not p.exists():
            return None
        z = np.load(p, allow_pickle=False)
        return z["ids"], z["vals"], str(z["source"]) if "source" in z.files else "tcgdex"

    def save(self, d: date, ids: list[str], vals: np.ndarray, source: str, prefer_existing: bool = False) -> None:
        """Writes a day; an existing day is merged: the new values win where they have one
        (prefer_existing: they only fill gaps, for backfills under the day's own snapshot)."""
        old = self.load(d)
        if old is not None:
            oid, ov, osrc = old
            index = {k: i for i, k in enumerate(oid.tolist())}
            merged_ids = list(oid.tolist())
            merged = ov.copy()
            extra_ids, extra_rows = [], []
            for i, cid in enumerate(ids):
                j = index.get(cid)
                if j is None:
                    extra_ids.append(cid)
                    extra_rows.append(vals[i])
                else:
                    m = vals[i] >= 0
                    if prefer_existing:
                        m &= merged[j] < 0
                    merged[j][m] = vals[i][m]
            if extra_ids:
                merged = np.vstack([merged, np.array(extra_rows, dtype=np.int32)])
                merged_ids += extra_ids
            ids, vals = merged_ids, merged
            source = osrc if source in osrc.split("+") else f"{osrc}+{source}"
        self.write(d, ids, vals, source)

    def write(self, d: date, ids: list[str], vals: np.ndarray, source: str) -> None:
        """Writes a day as given (no merge)."""
        tmp = self.path(d).with_suffix(".tmp.npz")
        np.savez_compressed(tmp, ids=np.array(ids), vals=np.asarray(vals, dtype=np.int32), source=np.array(source))
        tmp.replace(self.path(d))
        self.touched.add(d.isoformat()[:7])
        (self.root / "touched.txt").write_text("\n".join(sorted(self.touched)) + "\n")


# ------------------------------------------------------------------ today's prices
def record(db: Path, root: Path, today: date | None = None) -> int:
    """Today's Cardmarket and TCGplayer prices from the fetched catalog into the archive."""
    today = today or date.today()
    con = sqlite3.connect(db)
    rows: dict[str, np.ndarray] = {}

    def row(cid: str) -> np.ndarray:
        r = rows.get(cid)
        if r is None:
            r = rows[cid] = np.full(len(FIELDS), -1, dtype=np.int32)
        return r

    for cid, trend, avg30, low, th, a30h, lowh in con.execute(
            "SELECT id, trend, avg30, low, trend_holo, avg30_holo, low_holo FROM prices_cardmarket"):
        r = row(cid)
        for f, v in (("cm_trend", trend), ("cm_avg30", avg30), ("cm_low", low),
                     ("cm_trend_h", th), ("cm_avg30_h", a30h), ("cm_low_h", lowh)):
            r[FI[f]] = _cents(v)
    for cid, data in con.execute("SELECT id, data FROM prices_tcgplayer"):
        try:
            o = json.loads(data)
        except (TypeError, ValueError):
            continue
        for k, v in o.items():
            f = tp_field(k)
            if f and isinstance(v, dict):
                row(cid)[FI[f]] = _cents(v.get("marketPrice") or v.get("midPrice") or v.get("lowPrice"))
    con.close()
    ids = sorted(rows)
    keep = [i for i in ids if (rows[i] >= 0).any()]
    Archive(root).save(today, keep, np.stack([rows[i] for i in keep]) if keep else np.zeros((0, len(FIELDS)), np.int32), "tcgdex")
    log.info("price history: %s recorded, %d cards with a price", today, len(keep))
    return len(keep)


# ------------------------------------------------------------------ backfill
def products(db: Path) -> dict[int, str]:
    """TCGplayer product id -> our card id (from TCGdex's variants_detailed)."""
    con = sqlite3.connect(db)
    try:
        out = {int(t): cid for cid, t in con.execute("SELECT id, tcgplayer FROM card_products WHERE tcgplayer IS NOT NULL")}
    except sqlite3.OperationalError:
        out = {}
    con.close()
    return out


def parse_tcgcsv(folder: Path, product_to_card: dict[int, str]) -> tuple[list[str], np.ndarray]:
    """An extracted TCGCSV archive (date/category/group/prices JSON) -> our cards' TCGplayer prices."""
    rows: dict[str, np.ndarray] = {}
    for cat in TCGCSV_CATEGORIES:
        for f in folder.glob(f"*/{cat}/*/prices"):
            try:
                results = json.loads(f.read_text(encoding="utf-8")).get("results") or []
            except (OSError, ValueError):
                continue
            for p in results:
                cid = product_to_card.get(p.get("productId"))
                field = tp_field(p.get("subTypeName"))
                if not cid or not field:
                    continue
                v = _cents(p.get("marketPrice") or p.get("midPrice"))
                if v < 0:
                    continue
                r = rows.get(cid)
                if r is None:
                    r = rows[cid] = np.full(len(FIELDS), -1, dtype=np.int32)
                r[FI[field]] = v
    ids = sorted(rows)
    return ids, (np.stack([rows[i] for i in ids]) if ids else np.zeros((0, len(FIELDS)), np.int32))


TP_ONLY_TCGCSV = "tcgplayer-only-from-tcgcsv"     # marker in tcgcsv-done.txt: the clean-up below ran


def only_tcgcsv_for_tcgplayer(arc: Archive, done: set[str]) -> int:
    """TCGplayer history comes from one source, TCGCSV (TCGplayer's own market price):
    PokemonPriceTracker's TCGplayer figures differ for some cards (Base Set Charizard: $600
    against $2,200), and a line mixing both jumps. Clears the TCGplayer prices of the days
    PokemonPriceTracker wrote (not a day's own TCGdex snapshot) and lets TCGCSV read them again."""
    tp = [FI[f] for f in FIELDS if f.startswith("tp_")]
    n = 0
    for d in sorted(arc.have()):
        ids, vals, src = arc.load(d)
        parts = src.split("+")
        if "ppt" not in parts or "tcgdex" in parts:
            continue
        vals = vals.copy()
        vals[:, tp] = -1
        arc.write(d, ids.tolist(), vals, src)
        done.discard(d.isoformat())
        n += 1
    return n


def backfill_tcgcsv(db: Path, root: Path, today: date | None = None, max_files: int = 160, pause: float = 2.0) -> int:
    """TCGplayer prices of the calendar's past days that the archive has no TCGplayer prices for.
    Each TCGCSV archive is downloaded once: a day it was taken from is marked in tcgcsv-done.txt."""
    today = today or date.today()
    arc = Archive(root)
    done_file = Path(root) / "tcgcsv-done.txt"
    done = set(done_file.read_text().split()) if done_file.exists() else set()
    if TP_ONLY_TCGCSV not in done:
        n = only_tcgcsv_for_tcgplayer(arc, done)
        done.add(TP_ONLY_TCGCSV)
        done_file.write_text("\n".join(sorted(done)) + "\n")
        log.info("price history: TCGplayer prices from PokemonPriceTracker removed from %d days; TCGCSV reads them again", n)
    want = [d for d in calendar(today) if TCGCSV_START <= d < today and d.isoformat() not in done]
    if not want:
        log.info("price history: TCGplayer backfill complete")
        return 0
    prod = products(db)
    if not prod:
        log.warning("price history: no TCGplayer product ids in the catalog; backfill skipped")
        return 0
    if not shutil.which("7z"):
        log.warning("price history: 7z is not installed; backfill skipped")
        return 0
    s = requests.Session()
    s.headers["User-Agent"] = UA
    n = 0
    work = Path(root) / "tmp"
    for d in sorted(want, reverse=True)[:max_files]:       # newest first: the 90 daily days matter most
        shutil.rmtree(work, ignore_errors=True)
        work.mkdir(parents=True)
        url = TCGCSV_URL.format(d=d.isoformat())
        try:
            r = s.get(url, timeout=120)
        except requests.RequestException as ex:
            log.warning("TCGCSV %s: %s", d, ex)
            continue
        if r.status_code == 404:
            done.add(d.isoformat())          # no archive for that day: don't ask again
            continue
        if r.status_code != 200:
            log.warning("TCGCSV %s: HTTP %d; stopping for today", d, r.status_code)
            break
        f = work / "a.7z"
        f.write_bytes(r.content)
        res = subprocess.run(["7z", "x", "-y", f"-o{work}", str(f)] + [f"{d.isoformat()}/{c}/*" for c in TCGCSV_CATEGORIES] + ["-r"],
                             capture_output=True, text=True)
        if res.returncode not in (0, 1):
            log.warning("TCGCSV %s: 7z failed: %s", d, res.stderr[-300:])
            continue
        ids, vals = parse_tcgcsv(work, prod)
        if ids:
            arc.save(d, ids, vals, "tcgcsv", prefer_existing=True)
        done.add(d.isoformat())
        done_file.write_text("\n".join(sorted(done)) + "\n")
        n += 1
        log.info("TCGCSV %s: %d cards", d, len(ids))
        time.sleep(pause)
    shutil.rmtree(work, ignore_errors=True)
    log.info("price history: %d TCGCSV archives read, %d days still to fill", n, max(0, len(want) - n))
    return n


def _ppt_points(card: dict) -> dict[date, dict[str, int]]:
    """PokemonPriceTracker's history of one card -> {date: {field: cents}}: Cardmarket (EUR) per
    printing. (Its TCGplayer history is not used: TCGCSV has TCGplayer's own prices.)"""
    out: dict[date, dict[str, int]] = {}

    def put(d: str | None, field: str, v) -> None:
        c = _cents(v)
        if not d or c < 0:
            return
        try:
            day = date.fromisoformat(str(d)[:10])
        except ValueError:
            return
        out.setdefault(day, {})[field] = c

    cm = ((card.get("cardmarketPrices") or {}).get("variants") or {})
    names = list(cm)
    has_normal = any("normal" in x.lower() for x in names)
    for name in names:
        hist = (cm.get(name) or {}).get("history") or []
        n = name.lower()
        # one printing: the standard price; with two, the reverse holo (or the holo next to a
        # normal printing) is Cardmarket's second, "holo" price
        holo = len(names) > 1 and ("reverse" in n or ("holo" in n and has_normal))
        suffix = "_h" if holo else ""
        for pnt in hist:
            put(pnt.get("date"), "cm_trend" + suffix, pnt.get("trendEur"))
            put(pnt.get("date"), "cm_avg30" + suffix, pnt.get("avg30Eur"))
            put(pnt.get("date"), "cm_low" + suffix, pnt.get("lowEur"))
    return out


def backfill_ppt(db: Path, root: Path, budget: int = 12000, minutes: float = 25, days: int = 180,
                 key: str | None = None, reserve: int = 1500) -> int:
    """Cardmarket (EUR) history of the last [days] from PokemonPriceTracker,
    one card per call (6 credits for 180 days), most valuable cards first, a share of the day's
    credits per run. Cards done are listed in ppt-done.txt and never asked again."""
    import os
    from .graded import Client, RateLimited
    key = key or os.environ.get("POKEMONPRICETRACKER_API_KEY", "").strip()
    if not key:
        log.info("price history: no PokemonPriceTracker key, EUR backfill skipped")
        return 0
    root = Path(root)
    done_file = root / "ppt-done.txt"
    done = set(done_file.read_text().split()) if done_file.exists() else set()
    con = sqlite3.connect(db)
    try:
        todo = con.execute("""
            SELECT p.id, MIN(p.tcgplayer), MAX(COALESCE(c.trend, 0), COALESCE(c.trend_holo, 0)) AS v
            FROM card_products p LEFT JOIN prices_cardmarket c ON c.id = p.id
            WHERE p.tcgplayer IS NOT NULL GROUP BY p.id ORDER BY v DESC""").fetchall()
    except sqlite3.OperationalError:
        todo = []
    con.close()
    todo = [(cid, tp) for cid, tp, _ in todo if cid not in done]
    if not todo:
        log.info("price history: EUR backfill complete")
        return 0
    client = Client(key, root / "ppt-cache")
    today = date.today()
    per_day: dict[date, dict[str, np.ndarray]] = {}
    spent, n, t0 = 0, 0, time.time()
    try:
        for cid, tp in todo:
            if spent + 6 > budget or time.time() - t0 > minutes * 60:
                break
            if client.daily_remaining is not None and client.daily_remaining < reserve:
                log.info("price history: keeping %d credits for tomorrow", client.daily_remaining)
                break
            before = client.daily_remaining
            resp = client.get("cards", {"tcgPlayerId": str(tp), "includeHistory": "true", "includeCardmarket": "true",
                                        "days": str(days)})
            used = (before - client.daily_remaining) if (before is not None and client.daily_remaining is not None) else 6
            spent += used if 0 <= used <= 60 else 6          # the day's allowance resets at midnight UTC
            card = resp.get("data") if isinstance(resp, dict) else None
            if isinstance(card, list):
                card = card[0] if card else None
            for d, fields in _ppt_points(card or {}).items():
                if d >= today:
                    continue
                r = per_day.setdefault(d, {}).get(cid)
                if r is None:
                    r = per_day[d][cid] = np.full(len(FIELDS), -1, dtype=np.int32)
                for f, c in fields.items():
                    r[FI[f]] = c
            done.add(cid)
            n += 1
            time.sleep(1.0)          # 60 requests a minute
    except RateLimited as ex:
        log.info("price history: PokemonPriceTracker stopped us: %s", ex)
    arc = Archive(root)
    for d, rows in per_day.items():
        ids = sorted(rows)
        arc.save(d, ids, np.stack([rows[i] for i in ids]), "ppt", prefer_existing=True)
    done_file.write_text("\n".join(sorted(done)) + "\n")
    log.info("price history: Cardmarket history of %d cards from PokemonPriceTracker (%d credits, %d days written), %d cards left",
             n, spent, len(per_day), len(todo) - n)
    return n


def fetch_ecb(root: Path) -> int:
    """The ECB's daily euro reference rates (USD per EUR) since 1999, as date,usd rows."""
    try:
        r = requests.get(ECB_URL, timeout=60, headers={"User-Agent": UA})
        r.raise_for_status()
        with zipfile.ZipFile(io.BytesIO(r.content)) as z:
            text = z.read(z.namelist()[0]).decode("utf-8")
    except Exception as ex:   # noqa: BLE001
        log.warning("ECB rates: %s", ex)
        return 0
    out = []
    for row in csv.DictReader(io.StringIO(text)):
        v = (row.get("USD") or "").strip()
        if v and v != "N/A":
            out.append(f"{row['Date']},{v}")
    (Path(root) / "ecb.csv").write_text("date,usd_per_eur\n" + "\n".join(sorted(out)) + "\n")
    log.info("ECB rates: %d days, latest %s", len(out), sorted(out)[-1] if out else None)
    return len(out)


def ecb_rates(root: Path) -> dict[date, float]:
    f = Path(root) / "ecb.csv"
    if not f.exists():
        return {}
    out = {}
    for line in f.read_text().splitlines()[1:]:
        d, v = line.split(",")
        out[date.fromisoformat(d)] = float(v)
    return out


def rate_on(rates: dict[date, float], d: date) -> float | None:
    """The last ECB rate on or before d (no rates on weekends and holidays)."""
    for k in range(10):
        v = rates.get(d - timedelta(days=k))
        if v:
            return v
    return None


# ------------------------------------------------------------------ months (durable copy)
def pack_months(root: Path, out: Path, months: list[str] | None = None) -> list[Path]:
    """One tar per month of day files; by default the months touched in this run."""
    root, out = Path(root), Path(out)
    out.mkdir(parents=True, exist_ok=True)
    if months is None:
        t = root / "touched.txt"
        months = t.read_text().split() if t.exists() else []
    made = []
    for m in sorted(set(months)):
        files = sorted((root / "days").glob(f"{m}-*.npz"))
        if not files:
            continue
        p = out / f"history-{m}.tar"
        with tarfile.open(p, "w") as tar:
            for f in files:
                tar.add(f, arcname=f.name)
        made.append(p)
    for extra in ("ecb.csv", "tcgcsv-done.txt", "ppt-done.txt"):
        if (root / extra).exists():
            shutil.copy(root / extra, out / extra)
    return made


def unpack_months(root: Path, months_dir: Path) -> int:
    """Day files from the monthly tars that the cache doesn't have."""
    arc = Archive(root)
    n = 0
    for p in sorted(Path(months_dir).glob("history-*.tar")):
        with tarfile.open(p) as tar:
            for m in tar.getmembers():
                if m.isfile() and m.name.endswith(".npz") and "/" not in m.name and not (arc.days / m.name).exists():
                    tar.extract(m, arc.days, filter="data")
                    n += 1
    for extra in ("ecb.csv", "tcgcsv-done.txt", "ppt-done.txt"):
        src = Path(months_dir) / extra
        if src.exists() and not (Path(root) / extra).exists():
            shutil.copy(src, Path(root) / extra)
    log.info("price history: %d day files restored, %d in the archive", n, len(list(arc.days.glob("*.npz"))))
    return n


# ------------------------------------------------------------------ for the app
SPIKE = 4.0        # one reading 4x off its neighbours is a glitch (Cardmarket's trend has a few)
NEAREST = 3        # a calendar day without a price of a card takes its nearest price within 3 days


def _despike(v: np.ndarray) -> np.ndarray:
    """Which of a card's readings (in date order) to keep: not one that is 4x above (or below)
    both the median of the 3 readings before it and that of the 3 after. A real jump matches
    one side, so it stays."""
    if len(v) < 5:
        return np.ones(len(v), dtype=bool)
    pad = np.concatenate([np.full(3, np.nan, np.float32), v, np.full(3, np.nan, np.float32)])
    w = np.lib.stride_tricks.sliding_window_view(pad, 7)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)          # no readings on one side
        before, after = np.nanmedian(w[:, :3], axis=1), np.nanmedian(w[:, 4:], axis=1)
    low = (np.isnan(before) | (v * SPIKE < before)) & (np.isnan(after) | (v * SPIKE < after))
    high = (np.isnan(before) | (v > before * SPIKE)) & (np.isnan(after) | (v > after * SPIKE))
    return ~(low | high)


def app_series(root: Path, today: date | None = None, ids: set[str] | None = None) -> tuple[list[date], dict[tuple[str, str], np.ndarray], list[float | None]]:
    """The calendar, (card id, field) -> float32 prices (NaN = none), and the USD-per-EUR rate per calendar day.

    Every archived day of the year is read, per card: glitches are dropped, and a calendar day
    the card has no price on takes its nearest one within 3 days (the backfilled Cardmarket
    history has a reading every second day or so)."""
    arc = Archive(root)
    have = sorted(arc.have())
    today = today or (have[-1] if have else date.today())
    cal = calendar(today)
    rates = ecb_rates(root)
    rate_list = [rate_on(rates, d) for d in cal]
    first = cal[0] - timedelta(days=NEAREST)
    days = [d for d in have if first <= d <= today]
    # the cards: everything with a price on one of those days (and in the pack)
    index: dict[str, int] = {}
    for d in days:
        for cid in np.load(arc.path(d), allow_pickle=False)["ids"].tolist():
            if cid not in index and (ids is None or cid in ids):
                index[cid] = len(index)
    if not index:
        return cal, {}, rate_list
    n_days = (today - first).days + 1
    app_idx = [FI[f] for f in APP_FIELDS]
    m = np.full((len(index), len(APP_FIELDS), n_days), np.nan, dtype=np.float32)
    for d in days:
        day_ids, vals, _ = arc.load(d)
        rows = np.array([index.get(c, -1) for c in day_ids.tolist()])
        ok = rows >= 0
        sub = vals[ok][:, app_idx].astype(np.float32) / 100.0
        sub[vals[ok][:, app_idx] < 0] = np.nan
        m[rows[ok], :, (d - first).days] = sub
    # glitches
    counts = np.isfinite(m).sum(axis=2)
    for c, f in zip(*np.nonzero(counts >= 5)):
        x = m[c, f]
        obs = np.flatnonzero(np.isfinite(x))
        x[obs[~_despike(x[obs])]] = np.nan
    readings = np.isfinite(m).sum(axis=2)
    # the calendar: the day's price, else the nearest within 3 days (earlier first)
    j = np.array([(d - first).days for d in cal])
    out = np.full((len(index), len(APP_FIELDS), len(cal)), np.nan, dtype=np.float32)
    for off in [0] + [k for n in range(1, NEAREST + 1) for k in (-n, n)]:
        jo = j + off
        valid = (jo >= 0) & (jo < n_days)
        cand = m[:, :, np.clip(jo, 0, n_days - 1)]
        take = np.isnan(out) & np.isfinite(cand) & valid[None, None, :]
        out[take] = cand[take]
    series: dict[tuple[str, str], np.ndarray] = {}
    ok = (readings >= 2) & (np.isfinite(out).sum(axis=2) >= 2)     # one reading is not a history
    names = list(index)
    for c, f in zip(*np.nonzero(ok)):
        series[(names[c], APP_FIELDS[f])] = out[c, f].copy()
    return cal, series, rate_list


F16_MAX = 65000.0

HISTORY_SCHEMA = """
CREATE TABLE IF NOT EXISTS history(id TEXT NOT NULL, field TEXT NOT NULL, data BLOB NOT NULL, PRIMARY KEY(id, field)) WITHOUT ROWID;
"""


def write_app_history(con: sqlite3.Connection, root: Path, today: date | None = None) -> dict:
    """The app's history table + meta (calendar, fields, rates) in the pack's catalog."""
    ids = {r[0] for r in con.execute("SELECT DISTINCT id FROM cards")}
    cal, series, rates = app_series(root, today, ids)
    con.executescript(HISTORY_SCHEMA)
    con.execute("DELETE FROM history")
    # cards whose Cardmarket price was another card's (pricecheck): their Cardmarket past is too
    no_cm = set()
    if con.execute("SELECT 1 FROM sqlite_master WHERE name = 'price_check'").fetchone():
        no_cm = {r[0] for r in con.execute("SELECT id FROM price_check WHERE status = 'dropped'")}
    rows = []
    for (cid, f), a in series.items():
        if np.isfinite(a).sum() < 2:          # one point is not a line
            continue
        if cid in no_cm and f.startswith("cm_"):
            continue
        # float16 halves the size; a price above its range (65,504) keeps the series in float32
        big = np.nanmax(a) > F16_MAX
        rows.append((cid, f, zlib.compress(a.astype("<f4" if big else "<f2").tobytes(), 6)))
    con.executemany("INSERT INTO history VALUES (?,?,?)", rows)
    meta = {
        "history_dates": ",".join(d.isoformat() for d in cal),
        "history_rates": ",".join("" if r is None else f"{r:.4f}" for r in rates),
        "usd_per_eur": next((f"{r:.4f}" for r in reversed(rates) if r), ""),
    }
    con.executemany("INSERT OR REPLACE INTO meta VALUES (?,?)", meta.items())
    size = sum(len(r[2]) for r in rows)
    stats = {"series": len(rows), "cards": len({r[0] for r in rows}), "points": len(cal), "bytes": size,
             "first": cal[0].isoformat() if cal else None,
             "days_with_data": sum(1 for d in cal if d in Archive(root).have())}
    log.info("price history for the app: %s", stats)
    return stats
