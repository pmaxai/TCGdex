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

SCHEMA = """
CREATE TABLE IF NOT EXISTS graded(
  id TEXT PRIMARY KEY, currency TEXT, updated TEXT,
  psa10 REAL, psa10_n INTEGER, psa9 REAL, psa9_n INTEGER, psa8 REAL, psa8_n INTEGER, source TEXT);
"""


def norm(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    s = s.replace("&", "and")
    return re.sub(r"[^a-z0-9]+", "", s)


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

    def get(self, path: str, params: dict) -> dict:
        for attempt in range(5):
            r = self.s.get(f"{API}/{path}", params=params, timeout=120)
            if r.status_code == 429 or r.status_code >= 500:
                wait = float(r.headers.get("Retry-After", 0) or 0) or 20 * (attempt + 1)
                log.warning("PokemonPriceTracker %s -> HTTP %d, waiting %.0fs", path, r.status_code, wait)
                time.sleep(wait)
                continue
            if r.status_code in (401, 403):
                raise SystemExit(f"PokemonPriceTracker refused the API key (HTTP {r.status_code}): {r.text[:200]}")
            r.raise_for_status()
            used = r.headers.get("X-API-Calls-Consumed") or r.headers.get("x-credits-used")
            if used and str(used).isdigit():
                self.credits_used += int(used)
            return r.json()
        raise RuntimeError(f"PokemonPriceTracker {path} kept failing")


def _items(resp) -> list[dict]:
    if isinstance(resp, list):
        return resp
    if isinstance(resp, dict):
        for k in ("data", "cards", "sets", "results"):
            if isinstance(resp.get(k), list):
                return resp[k]
    return []


def fetch(db: Path, cache_dir: Path, budget: int = 18000, max_age_days: float = 7, key: str | None = None) -> None:
    key = key or os.environ.get("POKEMONPRICETRACKER_API_KEY", "").strip()
    cache_dir = Path(cache_dir)
    if not key:
        log.info("no POKEMONPRICETRACKER_API_KEY: skipping PSA price download (cached data is still used)")
    else:
        client = Client(key, cache_dir)
        sets_resp = client.get("sets", {"limit": 500})
        ppt_sets = _items(sets_resp)
        if ppt_sets:
            log.info("PokemonPriceTracker: %d sets; first set fields: %s", len(ppt_sets), sorted(ppt_sets[0]))
        by_name = {norm(_first(s, "name", "setName", default="")): s for s in ppt_sets}

        con = sqlite3.connect(db)
        ours = con.execute("""SELECT s.id, s.name, s.release_date, COUNT(c.id) FROM sets s
                              JOIN cards c ON c.set_id = s.id AND c.lang = 'en'
                              WHERE s.lang = 'en' GROUP BY s.id ORDER BY s.release_date DESC""").fetchall()
        con.close()
        plan = []
        for sid, name, rel, n in ours:
            ps = by_name.get(norm(name))
            if not ps:
                continue
            f = cache_dir / f"{sid}.json"
            age = (time.time() - f.stat().st_mtime) / 86400 if f.exists() else 1e9
            if age > max_age_days:
                plan.append((age, sid, name, ps, n))
        plan.sort(key=lambda t: -t[0])     # never fetched / stalest first (newest sets win ties)
        unmatched = len(ours) - sum(1 for _, name, _, _ in ours if norm(name) in by_name)
        log.info("PSA prices: %d of our sets matched by name (%d not), %d due for refresh, budget %d credits",
                 len(ours) - unmatched, unmatched, len(plan), budget)
        spent = 0
        probe = True
        for _, sid, name, ps, n in plan:
            cost = 2 * max(n, 1)
            if spent + cost > budget:
                continue
            set_param = _first(ps, "name", "setName")
            resp = client.get("cards", {"set": set_param, "fetchAllInSet": "true", "includeEbay": "true"})
            cards = _items(resp)
            if probe and cards:
                c0 = cards[0]
                log.info("probe %s: card fields %s; ebay fields %s; grades %s", name, sorted(c0),
                         sorted((c0.get("ebay") or {})), sorted(((c0.get("ebay") or {}).get("salesByGrade") or {}))[:12])
                probe = False
            (cache_dir / f"{sid}.json").write_text(json.dumps({"set": name, "fetched": time.time(), "cards": cards}))
            spent += cost
            log.info("PSA prices: %s (%d cards) fetched, ~%d credits used so far", name, len(cards), spent)
            time.sleep(max(1.0, len(cards) / 10 * 1.1))       # stay under 60 calls/minute
    build_table(db, cache_dir)


def build_table(db: Path, cache_dir: Path) -> None:
    """Match cached PokemonPriceTracker cards to our card ids and fill the graded table."""
    con = sqlite3.connect(db)
    con.executescript(SCHEMA)
    con.execute("DELETE FROM graded")
    rows = []
    for f in sorted(Path(cache_dir).glob("*.json")) if Path(cache_dir).exists() else []:
        sid = f.stem
        data = json.loads(f.read_text())
        ours = con.execute("SELECT id, local_id, name FROM cards WHERE set_id = ? AND lang = 'en'", (sid,)).fetchall()
        by_num: dict[str, list[tuple[str, str]]] = {}
        for cid, local, name in ours:
            by_num.setdefault(norm_number(local), []).append((cid, norm(name)))
        updated = time.strftime("%Y-%m-%d", time.gmtime(data.get("fetched", time.time())))
        for c in data.get("cards", []):
            num = norm_number(_first(c, "cardNumber", "number", "localId", default=""))
            cands = by_num.get(num, [])
            if len(cands) > 1:   # same number twice (rare): use the name
                cname = norm(_first(c, "name", "cleanName", default=""))
                cands = [x for x in cands if x[1] and (x[1] in cname or cname in x[1])] or cands[:1]
            if not cands:
                continue
            grades = ((c.get("ebay") or {}).get("salesByGrade")) or c.get("gradedPrices") or {}
            vals = [grade_price(grades.get(g)) for g in GRADES]
            if all(v[0] is None for v in vals):
                continue
            rows.append((cands[0][0], "USD", updated, vals[0][0], vals[0][1], vals[1][0], vals[1][1],
                         vals[2][0], vals[2][1], "PokemonPriceTracker (eBay sold)"))
    con.executemany("INSERT OR REPLACE INTO graded VALUES (?,?,?,?,?,?,?,?,?,?)", rows)
    con.commit()
    log.info("PSA prices: %d cards with graded prices in the catalog", len(rows))
    con.close()
