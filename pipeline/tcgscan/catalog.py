"""Fetch the TCGdex catalog (sets, cards, prices, images) and store it in SQLite.

The SQLite file is what the Android app will later ship/download (minus the
`image_file` column, which only points into the pipeline's local cache).
"""
from __future__ import annotations

import json
import logging
import math
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .tcgdex import TCGdexClient, safe_name

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE sets(
  lang TEXT, id TEXT, name TEXT, serie_id TEXT, serie_name TEXT,
  release_date TEXT, official INTEGER, total INTEGER,
  PRIMARY KEY(lang, id));
CREATE TABLE cards(
  lang TEXT, id TEXT, set_id TEXT, local_id TEXT, name TEXT,
  image_url TEXT, image_file TEXT,
  PRIMARY KEY(lang, id));
CREATE TABLE card_details(
  id TEXT PRIMARY KEY, rarity TEXT, category TEXT, illustrator TEXT,
  hp INTEGER, regulation_mark TEXT, variants TEXT, updated TEXT, dex TEXT);
CREATE TABLE prices_cardmarket(
  id TEXT PRIMARY KEY, updated TEXT, unit TEXT,
  avg REAL, low REAL, trend REAL, avg1 REAL, avg7 REAL, avg30 REAL,
  avg_holo REAL, low_holo REAL, trend_holo REAL,
  avg1_holo REAL, avg7_holo REAL, avg30_holo REAL);
CREATE TABLE prices_tcgplayer(id TEXT PRIMARY KEY, updated TEXT, unit TEXT, data TEXT);
CREATE TABLE card_products(id TEXT, type TEXT, subtype TEXT, cardmarket INTEGER, tcgplayer INTEGER);
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE INDEX cards_by_id ON cards(id);
"""

# TCGdex JSON key -> our column
CARDMARKET_FIELDS = {
    "avg": "avg", "low": "low", "trend": "trend",
    "avg1": "avg1", "avg7": "avg7", "avg30": "avg30",
    "avg-holo": "avg_holo", "low-holo": "low_holo", "trend-holo": "trend_holo",
    "avg1-holo": "avg1_holo", "avg7-holo": "avg7_holo", "avg30-holo": "avg30_holo",
}


@dataclass
class FetchStats:
    lang: str
    sets: int = 0
    cards: int = 0
    with_image_url: int = 0
    images_ok: int = 0


def _num(v: Any) -> float | None:
    try:
        f = float(v)
        return f if math.isfinite(f) else None
    except (TypeError, ValueError):
        return None


def image_path(cache_dir: Path, lang: str, set_id: str, card_id: str) -> Path:
    return Path(cache_dir) / "img" / lang / safe_name(set_id) / (safe_name(card_id) + ".webp")


def hours_since_utc(hhmm: str, now: "datetime | None" = None) -> float:
    """Hours since the latest occurrence of a UTC time of day ("23:15")."""
    from datetime import datetime, timedelta, timezone
    now = now or datetime.now(timezone.utc)
    h, m = (int(x) for x in hhmm.split(":"))
    last = now.replace(hour=h, minute=m, second=0, microsecond=0)
    if last > now:
        last -= timedelta(days=1)
    return (now - last).total_seconds() / 3600


def pick_sets(set_ids: list[str], max_sets: int) -> list[str]:
    """Evenly spaced subset (old and new eras) for quick test runs."""
    if not max_sets or max_sets >= len(set_ids):
        return set_ids
    step = len(set_ids) / max_sets
    return [set_ids[int(i * step)] for i in range(max_sets)]


def fetch_catalog(client: TCGdexClient, out_db: Path, langs: list[str], price_lang: str = "en",
                  with_details: bool = True, download_images: bool = True, max_sets: int = 0,
                  list_max_age_h: float = 24, details_max_age_h: float = 20,
                  exclude_series: tuple[str, ...] = ("tcgp",),
                  image_langs: list[str] | None = None) -> list[FetchStats]:
    """exclude_series: 'tcgp' = Pokémon TCG Pocket, a phone game without physical cards.
    image_langs: languages whose card images are downloaded (default: all). Western
    languages share card ids and artwork, so names alone are enough for most of them;
    Japanese sets have their own ids and need images to be recognised."""
    t0 = time.time()
    stats: list[FetchStats] = []
    sets_rows: list[tuple] = []
    cards_rows: list[tuple] = []
    chosen: list[str] | None = None

    for lang in langs:
        st = FetchStats(lang)
        set_list = client.get_json(lang, "sets", max_age_h=list_max_age_h) or []
        ids = [s["id"] for s in set_list if s.get("id")]
        if chosen is None:
            chosen = pick_sets(ids, max_sets)
        if max_sets:
            keep = set(chosen)
            same = [i for i in ids if i in keep]
            # languages with their own set ids (Japanese) get their own spread of sets
            ids = same if same else pick_sets(ids, max_sets)
        details = client.map(lambda sid: client.get_json(lang, "sets", sid, max_age_h=list_max_age_h),
                             ids, f"[{lang}] set details")
        for sid in ids:
            s = details.get(sid)
            if not s:
                continue
            serie = s.get("serie") or {}
            if serie.get("id") in exclude_series:
                continue
            st.sets += 1
            cc = s.get("cardCount") or {}
            sets_rows.append((lang, sid, s.get("name"), serie.get("id"), serie.get("name"),
                              s.get("releaseDate"), cc.get("official"), cc.get("total")))
            for c in s.get("cards") or []:
                if not c.get("id"):
                    continue
                st.cards += 1
                img = c.get("image")
                if img:
                    st.with_image_url += 1
                cards_rows.append([lang, c["id"], sid, c.get("localId"), c.get("name"), img, None])
        stats.append(st)
        log.info("[%s] %d sets, %d cards (%d with image url)", lang, st.sets, st.cards, st.with_image_url)

    # ---------------------------------------------------------------- details/prices
    detail_rows, cm_rows, tp_rows, product_rows = [], [], [], []
    if with_details:
        # prices are per card id (language independent): fetch each id once,
        # preferring price_lang, falling back to the first language that has it
        src_lang: dict[str, str] = {}
        for row in cards_rows:
            lang, cid = row[0], row[1]
            if cid not in src_lang or lang == price_lang:
                src_lang[cid] = lang
        got = client.map(lambda cid: client.get_json(src_lang[cid], "cards", cid, max_age_h=details_max_age_h),
                         list(src_lang), "card details/prices")
        for cid, d in got.items():
            if not d:
                continue
            hp = d.get("hp")
            # National Pokédex numbers ("154"; "6,150" for tag teams): links an Asian print to its Pokémon
            dex = ",".join(str(int(x)) for x in (d.get("dexId") or []) if isinstance(x, (int, float)) or str(x).isdigit()) or None
            detail_rows.append((cid, d.get("rarity"), d.get("category"), d.get("illustrator"),
                                int(hp) if isinstance(hp, (int, float)) else None, d.get("regulationMark"),
                                json.dumps(d.get("variants") or {}, ensure_ascii=False),
                                d.get("updated"), dex))
            pricing = d.get("pricing") or {}
            cm = pricing.get("cardmarket")
            if isinstance(cm, dict):
                cm_rows.append((cid, cm.get("updated"), cm.get("unit"),
                                *[_num(cm.get(k)) for k in CARDMARKET_FIELDS]))
            tp = pricing.get("tcgplayer")
            if isinstance(tp, dict):
                tp_rows.append((cid, tp.get("updated"), tp.get("unit"), json.dumps(tp)))
            # marketplace product ids per printing (price history from other sources is found by them)
            for v in d.get("variants_detailed") or []:
                if not isinstance(v, dict):
                    continue
                tp_ids = v.get("thirdParty") or {}
                cm_id, tp_id = tp_ids.get("cardmarket"), tp_ids.get("tcgplayer")
                if cm_id or tp_id:
                    product_rows.append((cid, v.get("type"), v.get("subtype"),
                                         int(cm_id) if str(cm_id).isdigit() else None,
                                         int(tp_id) if str(tp_id).isdigit() else None))
        log.info("details for %d cards, cardmarket prices for %d, tcgplayer for %d",
                 len(detail_rows), len(cm_rows), len(tp_rows))
        sample = next((d for d in got.values() if d and d.get("pricing")), None)
        if sample:
            log.info("sample card %s: keys=%s variants=%s variants_detailed=%s pricing=%s", sample.get("id"),
                     sorted(sample), sample.get("variants"), json.dumps(sample.get("variants_detailed"))[:400],
                     {k: sorted(v) if isinstance(v, dict) else v for k, v in (sample.get("pricing") or {}).items()})

    # ---------------------------------------------------------------- images
    if download_images:
        todo = [i for i, r in enumerate(cards_rows) if r[5] and (image_langs is None or r[0] in image_langs)]

        def dl(i: int) -> bool:
            lang, cid, sid, _, _, img, _ = cards_rows[i]
            return client.get_image(img, image_path(client.cache_dir, lang, sid, cid))

        ok = client.map(dl, todo, "images", max_fail_frac=0.2)
        for i, success in ok.items():
            if success:
                r = cards_rows[i]
                r[6] = str(image_path(client.cache_dir, r[0], r[2], r[1]).relative_to(client.cache_dir))
        for st in stats:
            st.images_ok = sum(1 for r in cards_rows if r[0] == st.lang and r[6])
            log.info("[%s] images available: %d", st.lang, st.images_ok)

    # ---------------------------------------------------------------- sqlite
    out_db = Path(out_db)
    out_db.parent.mkdir(parents=True, exist_ok=True)
    if out_db.exists():
        out_db.unlink()
    con = sqlite3.connect(out_db)
    con.executescript(SCHEMA)
    con.executemany("INSERT OR REPLACE INTO sets VALUES (?,?,?,?,?,?,?,?)", sets_rows)
    con.executemany("INSERT OR REPLACE INTO cards VALUES (?,?,?,?,?,?,?)", [tuple(r) for r in cards_rows])
    con.executemany("INSERT OR REPLACE INTO card_details VALUES (?,?,?,?,?,?,?,?,?)", detail_rows)
    con.executemany(f"INSERT OR REPLACE INTO prices_cardmarket VALUES ({','.join('?' * 15)})", cm_rows)
    con.executemany("INSERT OR REPLACE INTO prices_tcgplayer VALUES (?,?,?,?)", tp_rows)
    con.executemany("INSERT INTO card_products VALUES (?,?,?,?,?)", product_rows)
    log.info("marketplace product ids: %d (TCGplayer for %d cards)", len(product_rows),
             len({r[0] for r in product_rows if r[4]}))
    meta = {
        "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "langs": ",".join(langs), "price_lang": price_lang, "max_sets": str(max_sets),
        "excluded_series": ",".join(exclude_series),
        "source": "TCGdex (https://tcgdex.dev)",
    }
    con.executemany("INSERT INTO meta VALUES (?,?)", meta.items())
    con.commit()
    # Cardmarket prices TCGdex linked to the wrong card: out before anything uses them
    if cm_rows:
        from . import pricecheck
        pricecheck.run(con)
    con.close()
    log.info("catalog written to %s in %.0fs (%d HTTP requests)", out_db, time.time() - t0, client.http_requests)
    return stats


@dataclass
class Entry:
    """One reference image: a card id in one language."""
    card_id: str
    lang: str
    set_id: str
    name: str
    serie_id: str
    path: Path
    local_id: str = ""
    official: int | None = None


def load_entries(db: Path, cache_dir: Path) -> list[Entry]:
    con = sqlite3.connect(db)
    rows = con.execute(
        """SELECT c.id, c.lang, c.set_id, c.name, s.serie_id, c.image_file, c.local_id, s.official
           FROM cards c LEFT JOIN sets s ON s.id = c.set_id AND s.lang = c.lang
           WHERE c.image_file IS NOT NULL ORDER BY c.id, c.lang""").fetchall()
    con.close()
    out = []
    for cid, lang, sid, name, serie, f, local, official in rows:
        p = Path(cache_dir) / f
        if p.exists():
            out.append(Entry(cid, lang, sid, name or "", serie or "?", p, local or "", official))
    return out
