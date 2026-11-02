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
