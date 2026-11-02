"""Export pieces that don't need torch: catalog, art rectangle, app recognition, fixtures."""
import sqlite3

import numpy as np

from tcgscan.catalog import fetch_catalog, load_entries
from tcgscan.embedders import CARD_H, CARD_W, HashEmbedder, crop_region
from tcgscan.evaluate import project
from tcgscan.export import app_recognise, art_rect, synthetic_detection_photos, write_catalog
from tcgscan.augment import flatten, load_rgba, synth_photo
from tcgscan.embedders import to_card

from fake_tcgdex import FakeTCGdex


def test_art_rect_matches_crop_region():
    x, y, w, h = art_rect()
    cards = np.random.default_rng(0).integers(0, 255, (1, CARD_H, CARD_W, 3), dtype=np.uint8)
    import cv2
    want = crop_region(cards, "art")[0]
    got = cv2.resize(cards[0, y:y + h, x:x + w], (224, 224), interpolation=cv2.INTER_AREA)
    assert np.array_equal(want, got)


def test_catalog_and_app_recognition(tmp_path):
    cache, db = tmp_path / "cache", tmp_path / "catalog.sqlite"
    fetch_catalog(FakeTCGdex(cache, n_sets=3, cards_per_set=20, workers=4), db, ["en", "de"])
    entries = load_entries(db, cache)
    write_catalog(db, tmp_path / "app.sqlite", entries, {"dim": 512})
    con = sqlite3.connect(tmp_path / "app.sqlite")
    assert con.execute("SELECT COUNT(*) FROM entries").fetchone()[0] == len(entries)
    assert con.execute("SELECT COUNT(*) FROM prices WHERE trend = 0.4").fetchone()[0] == 60
    assert con.execute("SELECT variants FROM card_info LIMIT 1").fetchone()[0] == '{"normal":true,"reverse":true}'
    assert con.execute("SELECT data FROM tcgplayer LIMIT 1").fetchone()[0] == '{"normal":0.3}'
    sid, lang = con.execute("SELECT id, lang FROM sets LIMIT 1").fetchone()
    assert sid.startswith("fs") and lang in ("en", "de")
    row = con.execute("""SELECT c.name, s.name, p.trend FROM entries e JOIN cards c ON c.id = e.card_id AND c.lang = e.lang
                         JOIN sets s ON s.id = c.set_id AND s.lang = c.lang LEFT JOIN prices p ON p.id = c.id
                         WHERE e.idx = 3""").fetchone()
    assert row[0] and row[1] and row[2] == 0.4

    h = HashEmbedder("t", "art")
    fp = lambda cards: project(h._embed(crop_region(cards, "art")), None)
    index = np.concatenate([fp(to_card(flatten(load_rgba(e.path)))[None]) for e in entries])
    photo, _, _ = synth_photo(load_rgba(entries[7].path), np.random.default_rng(1), guided=True)
    rec = app_recognise(photo, fp, index, [e.card_id for e in entries])
    assert rec["detected"] and len(rec["top"]) >= 2 and rec["top"][0]["reading"]


def test_synthetic_detection_photos():
    ph = synthetic_detection_photos(2)
    assert len(ph) == 2 and ph[0][1].ndim == 3


def test_artwork_groups_merge_languages_and_named_reprints():
    from pathlib import Path
    from tcgscan.catalog import Entry
    from tcgscan.export import artwork_groups
    rng = np.random.default_rng(0)
    a, b, c = (v / np.linalg.norm(v) for v in rng.normal(size=(3, 64)))
    def near(v, s=0.05):
        w = v + rng.normal(scale=s, size=v.shape)
        return w / np.linalg.norm(w)
    E = lambda cid, lang, name: Entry(cid, lang, cid.split("-")[0], name, "s", Path("x"))
    entries = [E("s1-1", "en", "Glumanda"), E("s1-1", "de", "Glumanda"),     # same card, two languages
               E("s2-5", "en", "Glumanda"),                                   # reprint, same art + name
               E("s3-9", "en", "Other"),                                      # same art, other name: keep apart
               E("SV1-3", "ja", "ヒトカゲ"),                                    # Japanese print of the same art
               E("s4-2", "en", "Different")]                                  # different art
    emb = np.stack([near(a), near(a), near(a), near(a), near(a), c]).astype(np.float32)
    groups, stats = artwork_groups(entries, emb)
    assert groups[0] == groups[1] == groups[2] == groups[4] == "s1-1"
    assert groups[3] == "s3-9" and groups[5] == "s4-2"
    assert stats["merged_japanese"] == 1 and stats["merged_reprints"] == 1


def test_graded_table_matches_by_number(tmp_path):
    import json, sqlite3
    from tcgscan import graded
    db = tmp_path / "c.sqlite"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE cards(lang TEXT, id TEXT, set_id TEXT, local_id TEXT, name TEXT, image_url TEXT, image_file TEXT)")
    con.executemany("INSERT INTO cards VALUES (?,?,?,?,?,?,?)", [
        ("en", "sv06-037", "sv06", "037", "Lampent", None, None),
        ("en", "sv06-130", "sv06", "130", "Dragapult ex", None, None)])
    con.commit(); con.close()
    cache = tmp_path / "graded"; cache.mkdir()
    (cache / "sv06.json").write_text(json.dumps({"set": "Twilight Masquerade", "fetched": 0, "cards": [
        {"name": "Dragapult ex", "cardNumber": "130/167", "ebay": {"salesByGrade": {
            "psa10": {"count": 84, "medianPrice": 186.75, "smartMarketPrice": {"price": 362.5, "confidence": "low"}},
            "psa9": {"count": 60, "medianPrice": 29.97}}}},
        {"name": "Lampent", "cardNumber": "37", "ebay": {"salesByGrade": {}}}]}))
    graded.build_table(db, cache)
    rows = sqlite3.connect(db).execute("SELECT id, psa10, psa10_n, psa9, psa8 FROM graded").fetchall()
    assert rows == [("sv06-130", 362.5, 84, 29.97, None)]
    assert graded.norm_number("037/167") == "37" and graded.norm_number("TG05") == "TG5"
