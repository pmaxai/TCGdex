"""PSA 10 / 9 / 8 prices from PokemonPriceTracker (eBay + Fanatics Collect sales, USD).

    POKEMONPRICETRACKER_API_KEY=... python -m tcgscan graded --budget 18000

Needs an API key (the $9.99 'API' plan has 20,000 credits/day; a card with graded
data costs 2 credits). Whole sets are fetched at once, cached on disk, and the
stalest sets are refreshed first until the credit budget for this run is used up,
so the full catalog is covered over a few runs and then kept fresh.

The result is written into the catalog's `graded` table, matched to TCGdex card
ids by set name and card number.
"""
from __future__ import annotations

import json
import logging
import os
import re
import sqlite3
import time
import unicodedata
from pathlib import Path

import requests

log = logging.getLogger(__name__)
API = "https://www.pokemonpricetracker.com/api/v2"
GRADES = ("psa10", "psa9", "psa8")

# one row per card and printing ('normal', 'holo', 'reverse', '1st'; '' = not stated)
SCHEMA = """
CREATE TABLE IF NOT EXISTS graded(
  id TEXT, printing TEXT, currency TEXT, updated TEXT,
  psa10 REAL, psa10_n INTEGER, psa9 REAL, psa9_n INTEGER, psa8 REAL, psa8_n INTEGER, source TEXT,
  PRIMARY KEY(id, printing));
"""


def printing_kind(name) -> str:
    """PokemonPriceTracker / TCGplayer printing name -> our printing code."""
    n = str(name or "").lower()
    if not n:
        return ""
    if "1st" in n or "first" in n:
        return "1st"
    if "reverse" in n:
        return "reverse"
    if "holo" in n:
        return "holo"
    return "normal"          # 'Normal', 'Unlimited'



def norm(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    s = s.replace("&", "and")
    return re.sub(r"[^a-z0-9]+", "", s)


ERA_PREFIX = re.compile(r"^\s*(?:sv|swsh|sm|xy|bw|me|hgss|dp|pop|ex)\s*\d*[a-z]?\s*[:\-]\s*", re.I)


def set_keys(name: str) -> set[str]:
    """Name variants for matching: TCGplayer-style names carry an era code
    ('SV06: Twilight Masquerade', 'SWSH01: Sword & Shield Base Set')."""
    keys = {norm(name)}
    stripped = ERA_PREFIX.sub("", name or "")
    keys.add(norm(stripped))
    for k in list(keys):
        if k.endswith("baseset") and len(k) > len("baseset"):
            keys.add(k[:-len("baseset")])
        for era in ("scarletandviolet", "swordandshield", "sunandmoon"):
            if k.startswith(era) and len(k) > len(era) + 1 and not k.endswith("baseset"):
                keys.add(k[len(era):])          # 'scarletandviolet151' -> '151'
    for k in list(keys):
        if k in BASE_ALIASES:
            keys.add(BASE_ALIASES[k])
    return {k for k in keys if k}


# TCGplayer calls some era base sets by their code
BASE_ALIASES = {"smbaseset": "sunandmoon", "xybaseset": "xy", "bwbaseset": "blackandwhite",
                "dpbaseset": "diamondandpearl", "hgssbaseset": "heartgoldandsoulsilver",
                "swshbaseset": "swordandshield", "svbaseset": "scarletandviolet"}


def _date(s) -> float | None:
    try:
        return time.mktime(time.strptime(str(s)[:10], "%Y-%m-%d"))
    except (TypeError, ValueError):
        return None


def match_sets(ours: list[tuple], ppt_sets: list[dict]) -> dict[str, dict]:
    """our set id -> PokemonPriceTracker set. By name first, then by release date
    (and card count) for sets whose names differ too much."""
    by_key: dict[str, list[dict]] = {}
    for ps in ppt_sets:
        for k in set_keys(_first(ps, "name", "setName", default="")):
            by_key.setdefault(k, []).append(ps)
    out: dict[str, dict] = {}
    used: set[int] = set()
    for sid, name, rel, n in ours:
        cands = {id(ps): ps for k in set_keys(name) for ps in by_key.get(k, [])}
        if len(cands) == 1:
            ps = next(iter(cands.values()))
            out[sid] = ps
            used.add(id(ps))
    for sid, name, rel, n in ours:
        if sid in out:
            continue
        d0 = _date(rel)
        if d0 is None:
            continue
        near = [ps for ps in ppt_sets if id(ps) not in used and (d := _date(ps.get("releaseDate"))) is not None
                and abs(d - d0) <= 4 * 86400]
        if len(near) > 1:
            same_count = [ps for ps in near if ps.get("cardCount") and n and abs(int(ps["cardCount"]) - n) <= 2]
            near = same_count or near
        if len(near) > 1:
            ours_words = set(re.findall(r"[a-z0-9]+", (name or "").lower()))
            def overlap(ps):
                w = set(re.findall(r"[a-z0-9]+", ERA_PREFIX.sub("", _first(ps, "name", default="")).lower()))
                return len(w & ours_words) / max(1, len(w | ours_words))
            near.sort(key=overlap, reverse=True)
            near = near[:1] if overlap(near[0]) >= 0.34 else []
        if len(near) == 1:
            out[sid] = near[0]
            used.add(id(near[0]))
    return out


class RateLimited(RuntimeError):
    pass


class NotInPlan(RateLimited):
    """The API key's plan doesn't include this endpoint (e.g. population needs the Business plan)."""


def credits_spent_today(cache_dir: Path) -> int:
    """Credits used since midnight UTC, from the cached downloads (2 credits per card)."""
    today = time.strftime("%Y-%m-%d", time.gmtime())
    spent = 0
    for f in Path(cache_dir).glob("*.json") if Path(cache_dir).exists() else []:
        try:
            d = json.loads(f.read_text())
        except (OSError, ValueError):
            continue
        if time.strftime("%Y-%m-%d", time.gmtime(d.get("fetched", 0))) == today:
            spent += int(d.get("credits") or 2 * max(1, len(d.get("cards", []))))
    return spent


def norm_number(s) -> str:
    """'037/167' -> '37', 'TG05' -> 'TG5', 'SWSH001' -> 'SWSH1'."""
    s = str(s or "").split("/")[0].strip()
    m = re.match(r"^([A-Za-z]*)0*(\d+)([a-zA-Z]?)$", s)
    return (m.group(1).upper() + m.group(2) + m.group(3).lower()) if m else s.upper()


def _first(d: dict, *keys, default=None):
    for k in keys:
        if isinstance(d, dict) and d.get(k) not in (None, ""):
            return d[k]
    return default


def grade_price(g: dict | None) -> tuple[float | None, int | None]:
    """Best single number for a grade: the 'smart' market price, else the median, else the average."""
    if not isinstance(g, dict):
        return None, None
    smart = g.get("smartMarketPrice")
    price = smart.get("price") if isinstance(smart, dict) else smart
    for v in (price, g.get("medianPrice"), g.get("averagePrice"), g.get("avg")):
        try:
            if v is not None and float(v) > 0:
                n = g.get("count")
                return float(v), int(n) if isinstance(n, (int, float)) else None
        except (TypeError, ValueError):
            continue
    return None, None


class Client:
    def __init__(self, key: str, cache_dir: Path):
        self.s = requests.Session()
        self.s.headers.update({"Authorization": f"Bearer {key}", "User-Agent": "card-scanner-pipeline/0.2"})
        self.cache = Path(cache_dir)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.credits_used = 0
        self.first = True
        self.started = time.time()
        self.daily_remaining: int | None = None

    def get(self, path: str, params: dict) -> dict:
        for attempt in range(5):
            r = self.s.get(f"{API}/{path}", params=params, timeout=90)
            rem = r.headers.get("x-ratelimit-daily-remaining")
            if rem and rem.isdigit():
                self.daily_remaining = int(rem)
            if self.first:
                self.first = False
                log.info("PokemonPriceTracker rate-limit headers: %s",
                         {k: v for k, v in r.headers.items() if "limit" in k.lower() or "credit" in k.lower() or "usage" in k.lower()})
            if r.status_code == 429 or r.status_code >= 500:
                try:
                    wait = float(r.headers.get("Retry-After", 0) or 0)
                except ValueError:
                    wait = 0.0
                limits = {k: v for k, v in r.headers.items() if "limit" in k.lower() or "credit" in k.lower()}
                if wait > 120:      # the daily allowance is used up: try again tomorrow
                    raise RateLimited(f"HTTP {r.status_code}, retry after {wait:.0f}s {limits} {r.text[:150]}")
                wait = wait or 15 * (attempt + 1)
                log.warning("PokemonPriceTracker %s -> HTTP %d, waiting %.0fs %s", path, r.status_code, wait, limits)
                time.sleep(wait)
                continue
            if r.status_code == 403:
                raise NotInPlan(f"PokemonPriceTracker: {path} is not included in this plan (HTTP 403): {r.text[:200]}")
            if r.status_code in (401, 402):
                raise RateLimited(f"PokemonPriceTracker refused the request (HTTP {r.status_code}): {r.text[:200]}")
            r.raise_for_status()
            used = r.headers.get("X-API-Calls-Consumed") or r.headers.get("x-credits-used")
            if used and str(used).isdigit():
                self.credits_used += int(used)
            return r.json()
        raise RateLimited(f"PokemonPriceTracker {path} kept failing (rate limit or server error)")


def _items(resp) -> list[dict]:
    if isinstance(resp, list):
        return resp
    if isinstance(resp, dict):
        for k in ("data", "cards", "sets", "results"):
            if isinstance(resp.get(k), list):
                return resp[k]
    return []


def fetch(db: Path, cache_dir: Path, budget: int = 18000, max_age_days: float = 7, key: str | None = None,
          daily_limit: int = 19500) -> None:
    """Never fails the pipeline: on any API trouble the cached prices are used."""
    key = key or os.environ.get("POKEMONPRICETRACKER_API_KEY", "").strip()
    cache_dir = Path(cache_dir)
    if not key:
        log.info("no POKEMONPRICETRACKER_API_KEY: skipping PSA price download (cached data is still used)")
    else:
        try:
            _download(db, cache_dir, budget, max_age_days, key, daily_limit)
        except Exception as ex:   # noqa: BLE001 - PSA prices are optional
            log.warning("PSA price download stopped: %s (cached prices are still used)", ex)
    build_table(db, cache_dir)


def _download(db: Path, cache_dir: Path, budget: int, max_age_days: float, key: str, daily_limit: int,
              max_minutes: float = 25) -> None:
    spent_today = credits_spent_today(cache_dir)
    budget = min(budget, daily_limit - spent_today)
    if budget <= 0:
        log.info("PSA prices: today's credits are used up (%d spent since midnight UTC); using the cache", spent_today)
        return
    client = Client(key, cache_dir)
    sets_resp = client.get("sets", {"limit": 500})
    if client.daily_remaining is not None:      # the API says what is left today: trust it over our count
        budget = min(budget, client.daily_remaining - 300)
    ppt_sets = _items(sets_resp)
    if ppt_sets:
        log.info("PokemonPriceTracker: %d sets; first set fields: %s", len(ppt_sets), sorted(ppt_sets[0]))

    con = sqlite3.connect(db)
    ours = con.execute("""SELECT s.id, s.name, s.release_date, COUNT(c.id) FROM sets s
                          JOIN cards c ON c.set_id = s.id AND c.lang = 'en'
                          WHERE s.lang = 'en' GROUP BY s.id ORDER BY s.release_date DESC""").fetchall()
    con.close()
    matched = match_sets(ours, ppt_sets)
    plan = []
    for sid, name, rel, n in ours:
        ps = matched.get(sid)
        if not ps:
            continue
        f = cache_dir / f"{sid}.json"
        age = (time.time() - f.stat().st_mtime) / 86400 if f.exists() else 1e9
        if age > max_age_days:
            plan.append((age, sid, name, ps, n))
    plan.sort(key=lambda t: -t[0])     # never fetched / stalest first (newest sets win ties)
    unmatched = [f"{name} ({rel})" for sid, name, rel, _ in ours if sid not in matched]
    used = {id(ps) for ps in matched.values()}
    theirs = [f"{_first(ps, 'name', default='?')} ({str(ps.get('releaseDate', ''))[:10]})" for ps in ppt_sets if id(ps) not in used]
    log.info("PSA prices: %d of our sets matched (%d not), %d due for refresh, budget %d credits (%d spent today)",
             len(matched), len(unmatched), len(plan), budget, spent_today)
    log.info("PSA prices: our sets without a match: %s", "; ".join(unmatched[:60]))
    log.info("PSA prices: their sets without a match: %s", "; ".join(theirs[:60]))
    spent = 0
    probe = True
    for _, sid, name, ps, n in plan:
        cost = 2 * max(n, 1)
        if spent + cost > budget:
            continue
        if time.time() - client.started > max_minutes * 60:
            log.info("PSA prices: %d min time limit reached, the rest follows in the next run", max_minutes)
            break
        set_param = _first(ps, "name", "setName")
        try:
            resp = client.get("cards", {"set": set_param, "fetchAllInSet": "true", "includeEbay": "true"})
        except RateLimited as ex:
            log.warning("PSA prices: stopping for today: %s", ex)
            break
        cards = _items(resp)
        if probe and cards:
            c0 = cards[0]
            log.info("probe %s: card fields %s; ebay fields %s; grades %s", name, sorted(c0),
                     sorted((c0.get("ebay") or {})), sorted(((c0.get("ebay") or {}).get("salesByGrade") or {}))[:12])
            probe = False
        (cache_dir / f"{sid}.json").write_text(json.dumps({"set": name, "fetched": time.time(), "cards": cards,
                                                          "credits": 2 * max(1, len(cards))}))
        spent += cost
        log.info("PSA prices: %s (%d cards) fetched, ~%d credits used so far", name, len(cards), spent)
        time.sleep(max(1.0, len(cards) / 10 * 1.1))       # stay under 60 calls/minute
    left = budget - spent
    if time.time() - client.started < max_minutes * 60 and left > 200:
        _download_population(client, cache_dir, left, deadline=client.started + max_minutes * 60)


def _matched_cards(con, sid: str, cards: list[dict]) -> list[tuple[str, dict]]:
    """(our card id, PokemonPriceTracker card) for one set, matched by number (and name if needed)."""
    ours = con.execute("SELECT id, local_id, name FROM cards WHERE set_id = ? AND lang = 'en'", (sid,)).fetchall()
    by_num: dict[str, list[tuple[str, str]]] = {}
    for cid, local, name in ours:
        by_num.setdefault(norm_number(local), []).append((cid, norm(name)))
    out = []
    for c in cards:
        num = norm_number(_first(c, "cardNumber", "number", "localId", default=""))
        cands = by_num.get(num, [])
        if len(cands) > 1:   # same number twice (rare): use the name
            cname = norm(_first(c, "name", "cleanName", default=""))
            cands = [x for x in cands if x[1] and (x[1] in cname or cname in x[1])] or cands[:1]
        if cands:
            out.append((cands[0][0], c))
    return out


# ---------------------------------------------------------------- population (gem rate)
POP_SCHEMA = """
CREATE TABLE IF NOT EXISTS population(
  id TEXT, printing TEXT, updated TEXT, psa_total INTEGER, psa10 INTEGER, psa9 INTEGER, psa8 INTEGER,
  gem_rate REAL, PRIMARY KEY(id, printing));
"""


def _download_population(client: Client, cache_dir: Path, budget: int, deadline: float, max_age_days: float = 14) -> None:
    """PSA population per card from PokemonPriceTracker's /population (Business plan; 2 credits per
    card). On a plan without it the API answers 403: logged once, nothing else happens."""
    pop_dir = Path(cache_dir) / "population"
    pop_dir.mkdir(parents=True, exist_ok=True)
    todo = []
    for f in sorted(Path(cache_dir).glob("*.json")):
        pf = pop_dir / f.name
        age = (time.time() - pf.stat().st_mtime) / 86400 if pf.exists() else 1e9
        if age > max_age_days:
            todo.append((age, f))
    todo.sort(key=lambda t: -t[0])
    spent = 0
    for _, f in todo:
        cards = json.loads(f.read_text()).get("cards", [])
        ids = [str(c["tcgPlayerId"]) for c in cards if c.get("tcgPlayerId")]
        if not ids:
            continue
        if spent + 2 * len(ids) > budget or time.time() > deadline:
            break
        items = []
        try:
            for i in range(0, len(ids), 50):
                chunk = ids[i:i + 50]
                resp = client.get("population", {"tcgPlayerIds": ",".join(chunk), "limit": 100})
                got = _items(resp) or ([resp["data"]] if isinstance(resp, dict) and isinstance(resp.get("data"), dict) else [])
                if not items and got:
                    log.info("PSA population probe: fields %s; PSA block %s", sorted(got[0]),
                             json.dumps(_skeleton(((got[0].get("populationByGrader") or {}).get("PSA"))))[:400])
                items += got
                spent += 2 * len(chunk)
                time.sleep(1.1)
        except NotInPlan as ex:
            log.info("Gem rates: PSA population is not part of this PokemonPriceTracker plan (it needs the "
                     "Business plan) - skipped. %s", str(ex)[:120])
            return
        except RateLimited as ex:
            log.warning("PSA population: stopping for today: %s", ex)
            break
        (pop_dir / f.name).write_text(json.dumps({"fetched": time.time(), "items": items}))
        log.info("PSA population: %s (%d cards) fetched, ~%d credits", f.stem, len(items), spent)


def _grade(d: dict, n: int):
    for k in (f"g{n}", str(n), f"psa{n}", f"grade{n}", f"psa_{n}"):
        v = d.get(k) if isinstance(d, dict) else None
        if isinstance(v, (int, float)):
            return int(v)
        if isinstance(v, dict) and isinstance(v.get("count"), (int, float)):
            return int(v["count"])
    return None


def population_values(block: dict) -> tuple | None:
    """(psa total, psa10, psa9, psa8, gem rate 0..1) from a population block (a card, or one printing)."""
    if not isinstance(block, dict):
        return None
    psa = (block.get("populationByGrader") or {}).get("PSA") or block.get("PSA") or block.get("psa")
    if not isinstance(psa, dict):
        return None
    g10, g9, g8 = _grade(psa, 10), _grade(psa, 9), _grade(psa, 8)
    total = psa.get("totalPopulation") or psa.get("total")
    if not isinstance(total, (int, float)) or total <= 0:
        grades = [v for k, v in psa.items() if isinstance(v, (int, float)) and re.fullmatch(r"g\d+(_5)?", k)]
        total = sum(grades) if grades else 0
    if not total:
        return None
    gem = (g10 or 0) / total
    return int(total), g10, g9, g8, round(gem, 4)


def build_population(con, cache_dir: Path) -> int:
    con.executescript(POP_SCHEMA)
    con.execute("DELETE FROM population")
    pop_dir = Path(cache_dir) / "population"
    rows = []
    for pf in sorted(pop_dir.glob("*.json")) if pop_dir.exists() else []:
        price_file = Path(cache_dir) / pf.name
        if not price_file.exists():
            continue
        cards = json.loads(price_file.read_text()).get("cards", [])
        ours = {str(c.get("tcgPlayerId")): (cid, c) for cid, c in _matched_cards(con, pf.stem, cards)}
        data = json.loads(pf.read_text())
        updated = time.strftime("%Y-%m-%d", time.gmtime(data.get("fetched", time.time())))
        for item in data.get("items", []):
            hit = ours.get(str(item.get("tcgPlayerId")))
            if not hit:
                continue
            cid, c = hit
            blocks = [(printing_kind((c.get("prices") or {}).get("primaryPrinting")), item)]
            for name, b in (item.get("populationByPrinting") or {}).items():
                blocks.append((printing_kind(name), b))
            seen = set()
            for printing, b in blocks:
                v = population_values(b)
                if v and printing not in seen:
                    seen.add(printing)
                    rows.append((cid, printing, updated, *v))
    con.executemany("INSERT OR REPLACE INTO population VALUES (?,?,?,?,?,?,?,?)", rows)
    if rows:
        log.info("Gem rates: %d cards with PSA population", len({r[0] for r in rows}))
    return len(rows)


def probe_population(key: str | None = None) -> None:
    """One /population call to see whether the plan includes it (free when it doesn't)."""
    key = key or os.environ.get("POKEMONPRICETRACKER_API_KEY", "").strip()
    if not key:
        log.info("no API key")
        return
    client = Client(key, Path(".cache/graded"))
    try:
        resp = client.get("population", {"tcgPlayerId": "45167"})
        log.info("population: available. Response: %s", json.dumps(_skeleton(resp), ensure_ascii=False)[:3000])
    except NotInPlan as ex:
        log.info("population: NOT in this plan. %s", ex)
    except Exception as ex:   # noqa: BLE001
        log.info("population: failed: %s", ex)


def build_table(db: Path, cache_dir: Path) -> None:
    """Match cached PokemonPriceTracker cards to our card ids and fill the graded table."""
    con = sqlite3.connect(db)
    con.executescript(SCHEMA)
    con.execute("DELETE FROM graded")
    rows = []
    for f in sorted(Path(cache_dir).glob("*.json")) if Path(cache_dir).exists() else []:
        sid = f.stem
        data = json.loads(f.read_text())
        updated = time.strftime("%Y-%m-%d", time.gmtime(data.get("fetched", time.time())))
        for cid, c in _matched_cards(con, sid, data.get("cards", [])):
            ebay = c.get("ebay") or {}
            # salesByGrade: the card's primary printing; salesByPrinting: every other printing
            per_printing = [(printing_kind((c.get("prices") or {}).get("primaryPrinting")),
                             ebay.get("salesByGrade") or c.get("gradedPrices") or {})]
            for name, block in (ebay.get("salesByPrinting") or {}).items():
                if isinstance(block, dict):
                    per_printing.append((printing_kind(name), block.get("salesByGrade") or {}))
            seen = set()
            for printing, grades in per_printing:
                vals = [grade_price(grades.get(g)) for g in GRADES]
                if all(v[0] is None for v in vals) or printing in seen:
                    continue
                seen.add(printing)
                rows.append((cid, printing, "USD", updated, vals[0][0], vals[0][1], vals[1][0], vals[1][1],
                             vals[2][0], vals[2][1], "PokemonPriceTracker (eBay sold)"))
    con.executemany("INSERT OR REPLACE INTO graded VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
    con.commit()
    by_printing: dict[str, int] = {}
    for r in rows:
        by_printing[r[1] or "?"] = by_printing.get(r[1] or "?", 0) + 1
    log.info("PSA prices: %d cards with graded prices in the catalog (%d rows by printing: %s)",
             len({r[0] for r in rows}), len(rows), by_printing)
    build_population(con, cache_dir)
    con.commit()
    con.close()


def _skeleton(v, depth: int = 0, max_items: int = 4):
    """Structure of a JSON value with short sample values (for looking at the API's format)."""
    if isinstance(v, dict):
        if depth > 5:
            return "{...}"
        return {k: _skeleton(x, depth + 1) for k, x in list(v.items())[:40]}
    if isinstance(v, list):
        return [_skeleton(x, depth + 1) for x in v[:max_items]] + (["..."] if len(v) > max_items else [])
    if isinstance(v, str):
        return v[:60]
    return v


def inspect(cache_dir: Path, n: int = 4) -> None:
    """Print how cached PokemonPriceTracker cards describe graded sales per printing."""
    files = sorted(Path(cache_dir).glob("*.json"))
    log.info("PSA cache: %d sets", len(files))
    shown = 0
    stats = {"cards": 0, "with_salesByGrade": 0, "with_salesByPrinting": 0}
    printings: dict[str, int] = {}
    for f in files:
        d = json.loads(f.read_text())
        for c in d.get("cards", []):
            stats["cards"] += 1
            eb = c.get("ebay") or {}
            if eb.get("salesByGrade"):
                stats["with_salesByGrade"] += 1
            sbp = eb.get("salesByPrinting")
            if sbp:
                stats["with_salesByPrinting"] += 1
                for k in (sbp if isinstance(sbp, dict) else [x.get("printing", "?") for x in sbp if isinstance(x, dict)]):
                    printings[str(k)] = printings.get(str(k), 0) + 1
                modern = f.stem.startswith(("sv", "swsh", "sm"))
                if shown < n and (modern or shown < 1):
                    shown += 1
                    keep = ("count", "medianPrice", "averagePrice", "smartMarketPrice", "lastSaleDate")
                    def slim(x):
                        if isinstance(x, dict):
                            return {k: (slim(v) if k not in keep else v) for k, v in x.items()
                                    if k in keep or k.startswith(("psa", "printing", "grade")) or isinstance(v, (dict, list))
                                    and k not in ("labels", "salesBySource")}
                        if isinstance(x, list):
                            return [slim(v) for v in x[:3]]
                        return x
                    log.info("PSA card %s %s %s: primaryPrinting=%s ebay keys=%s salesByPrinting=%s", f.stem, c.get("name"),
                             c.get("cardNumber"), (c.get("prices") or {}).get("primaryPrinting"), sorted(eb),
                             json.dumps(slim(sbp), ensure_ascii=False)[:2500])
    log.info("PSA cache stats: %s", stats)
    log.info("PSA printings in salesByPrinting: %s", dict(sorted(printings.items(), key=lambda t: -t[1])[:30]))
