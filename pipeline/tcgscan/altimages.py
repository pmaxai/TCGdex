"""Second picture source for cards TCGdex has no image for.

TCGdex lists every card with its prices, but some sets come without pictures (Crown Zenith
Galarian Gallery, the Trainer Galleries, Dragon Majesty, Shining Legends, promos ...).
Without a picture a card can't be recognised at all. pokemontcg.io's open card data
(github.com/PokemonTCG/pokemon-tcg-data, pictures on images.pokemontcg.io) has many of
them. This module maps their sets and cards to ours, downloads the pictures into the
pipeline's cache (never into the repo) and points the catalog at them:

- cards.image_file: the downloaded picture, so the card gets a fingerprint (English row)
- cards.image_alt / image_alt_large: picture URLs for the app's display (every language
  of that card: the artwork is the same)
"""
from __future__ import annotations

import json
import logging
import re
import sqlite3
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

import requests

from .tcgdex import safe_name

log = logging.getLogger(__name__)

SOURCE = "pokemontcg.io"
# our set id -> theirs, where neither the name nor the id spelling finds it
SET_ALIASES = {
    "svp": "svp",            # "SVP Black Star Promos" / "Scarlet & Violet Black Star Promos"
    "swshp": "swshp",
    "smp": "smp",
    "xyp": "xyp",
    "bwp": "bwp",
    "hgssp": "hsp",
    "dpp": "dpp",
    "np": "np",
    "basep": "basep",
    "cel25cc": "cel25c",
}
# two sets are the same when this share of the smaller one's card names agrees
MIN_NAME_OVERLAP = 0.7


def norm(s: str | None) -> str:
    """Lower case letters and digits only, accents dropped: 'Whimsicott-GX' == 'Whimsicott GX'."""
    s = unicodedata.normalize("NFKD", s or "")
    return re.sub(r"[^a-z0-9]", "", s.encode("ascii", "ignore").decode().lower())


def norm_number(s: str | None) -> str:
    """'019' == '19', 'GG01' == 'GG1', 'TG05' == 'TG5'; letters upper case."""
    s = (s or "").strip().upper()
    return re.sub(r"(^|[A-Z])0+(\d)", r"\1\2", s)


def id_spelling(set_id: str) -> str:
    """TCGdex 'sv04.5' -> pokemontcg.io 'sv4pt5', 'swsh12.5gg' -> 'swsh12pt5gg', 'sv06' -> 'sv6'."""
    return re.sub(r"([a-z])0+(\d)", r"\1\2", set_id.lower().replace(".", "pt"))


def same_name(ours: str, theirs: str) -> bool:
    a, b = norm(ours), norm(theirs)
    if not a or not b:
        return False
    return a == b or (len(a) >= 6 and len(b) >= 6 and (a.startswith(b[:6]) or b.startswith(a[:6])))


@dataclass
class TheirSet:
    id: str
    name: str
    date: str
    cards: list[dict] = field(default_factory=list)


def load_data(data_dir: Path) -> dict[str, TheirSet]:
    """pokemon-tcg-data checkout: sets/en.json + cards/en/<set>.json"""
    data_dir = Path(data_dir)
    out = {}
    for s in json.loads((data_dir / "sets" / "en.json").read_text(encoding="utf-8")):
        f = data_dir / "cards" / "en" / f"{s['id']}.json"
        cards = json.loads(f.read_text(encoding="utf-8")) if f.exists() else []
        out[s["id"]] = TheirSet(s["id"], s.get("name", ""), (s.get("releaseDate") or "").replace("/", "-"), cards)
    return out


def map_sets(ours: dict[str, tuple[str, str, set[str]]], theirs: dict[str, TheirSet]) -> dict[str, str]:
    """Our set id -> their set id. ours: id -> (name, release date, card names).

    Alias, then the same name, then the same id spelling, then the same release date; a
    match must share most card names (MIN_NAME_OVERLAP of the smaller set): MEP promos and
    Mega Evolution came out the same day and must not be mixed up."""
    by_name = {norm(t.name): t for t in theirs.values()}
    by_date: dict[str, list[TheirSet]] = {}
    for t in theirs.values():
        by_date.setdefault(t.date, []).append(t)
    out = {}
    for sid, (name, date, names) in ours.items():
        tried = [theirs.get(SET_ALIASES.get(sid, "")), by_name.get(norm(name)), theirs.get(id_spelling(sid))]
        tried += by_date.get(date or "", [])
        for t in tried:
            if t is None or not t.cards:
                continue
            their_names = {norm(c.get("name")) for c in t.cards}
            ours_n = {norm(n) for n in names if n}
            if ours_n and their_names and len(ours_n & their_names) / min(len(ours_n), len(their_names)) >= MIN_NAME_OVERLAP:
                out[sid] = t.id
                break
    return out


def find_card(t: TheirSet, local_id: str, name: str) -> dict | None:
    n = norm_number(local_id)
    for c in t.cards:
        if norm_number(c.get("number")) == n and same_name(name, c.get("name", "")):
            return c
    return None


def _download(url: str, dest: Path, session: requests.Session) -> bool:
    if dest.exists() and dest.stat().st_size > 1000:
        return True
    for attempt in range(4):
        try:
            r = session.get(url, timeout=30)
            if r.status_code == 200 and len(r.content) > 1000:
                dest.parent.mkdir(parents=True, exist_ok=True)
                tmp = dest.with_suffix(".part")
                tmp.write_bytes(r.content)
                tmp.replace(dest)
                return True
            if r.status_code == 404:
                return False
        except requests.RequestException:
            pass
        time.sleep(1.5 * (attempt + 1))
    return False


def fill(db: Path, cache_dir: Path, data_dir: Path, workers: int = 8, download: bool = True) -> dict:
    """Give cards without any TCGdex picture one from pokemontcg.io. Returns counts."""
    con = sqlite3.connect(db)
    cols = {r[1] for r in con.execute("PRAGMA table_info(cards)")}
    for c in ("image_alt", "image_alt_large"):
        if c not in cols:
            con.execute(f"ALTER TABLE cards ADD COLUMN {c} TEXT")
    theirs = load_data(data_dir)

    # card ids without a picture in any language (western ids: Asian sets have their own ids)
    rows = con.execute("""
        SELECT c.id, c.set_id, c.local_id, c.name FROM cards c
        WHERE c.lang = 'en' AND NOT EXISTS (
            SELECT 1 FROM cards o WHERE o.id = c.id AND (o.image_url IS NOT NULL OR o.image_file IS NOT NULL))
    """).fetchall()
    set_ids = sorted({r[1] for r in rows})
    ours = {}
    for sid in set_ids:
        meta = con.execute("SELECT name, release_date FROM sets WHERE id = ? AND lang = 'en'", (sid,)).fetchone()
        names = {n for (n,) in con.execute("SELECT name FROM cards WHERE set_id = ? AND lang = 'en'", (sid,))}
        ours[sid] = ((meta or ("", ""))[0] or "", ((meta or ("", ""))[1] or "")[:10], names)
    mapping = map_sets(ours, theirs)

    found = []
    for cid, sid, local_id, name in rows:
        t = theirs.get(mapping.get(sid, ""))
        c = find_card(t, local_id, name) if t else None
        img = (c or {}).get("images") or {}
        if img.get("small"):
            found.append((cid, sid, img["small"], img.get("large") or img["small"]))

    ok: dict[str, bool] = {}
    if download and found:
        s = requests.Session()
        s.headers["User-Agent"] = "tcgscan-pipeline"
        def dl(f):
            cid, sid, small, _ = f
            return cid, _download(small, _path(cache_dir, sid, cid), s)
        with ThreadPoolExecutor(workers) as ex:
            ok = dict(ex.map(dl, found))
    for cid, sid, small, large in found:
        con.execute("UPDATE cards SET image_alt = ?, image_alt_large = ? WHERE id = ?", (small, large, cid))
        if ok.get(cid):
            rel = str(_path(cache_dir, sid, cid).relative_to(cache_dir))
            con.execute("UPDATE cards SET image_file = ? WHERE id = ? AND lang = 'en'", (rel, cid))
    con.execute("INSERT OR REPLACE INTO meta VALUES ('image_alt_source', ?)", (SOURCE,))
    con.commit()

    unmatched: dict[str, int] = {}
    got = {f[0] for f in found}
    for cid, sid, _, _ in rows:
        if cid not in got:
            unmatched[sid] = unmatched.get(sid, 0) + 1
    stats = {"without_picture": len(rows), "found": len(found), "downloaded": sum(ok.values()),
             "sets_mapped": len(mapping), "sets": len(set_ids),
             "still_missing_by_set": dict(sorted(unmatched.items(), key=lambda x: -x[1])[:15])}
    con.close()
    log.info("pictures from %s: %d of %d cards without a TCGdex picture found (%d downloaded), %d of %d sets mapped",
             SOURCE, stats["found"], stats["without_picture"], stats["downloaded"], stats["sets_mapped"], stats["sets"])
    log.info("still without a picture: %s", stats["still_missing_by_set"])
    return stats


def _path(cache_dir: Path, set_id: str, card_id: str) -> Path:
    return Path(cache_dir) / "img-alt" / safe_name(set_id) / (safe_name(card_id) + ".png")
