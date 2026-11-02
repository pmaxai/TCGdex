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
               E("SV1-3", "zh-tw", "小火龍"),                                   # ... and its Chinese print (same id)
               E("s4-2", "en", "Different"),                                  # different art
               E("SV9-1", "ja", "ゼンゼンチガウ")]                               # Japanese-only card
    emb = np.stack([near(a), near(a), near(a), near(a), near(a), near(a, 0.01), c,
                    near(b)]).astype(np.float32)
    groups, stats = artwork_groups(entries, emb)
    # the Chinese print sharing the Japanese id must not stop the link to the English card
    assert groups[0] == groups[1] == groups[2] == groups[4] == groups[5] == "s1-1"
    assert groups[3] == "s3-9" and groups[6] == "s4-2" and groups[7] == "SV9-1"
    assert stats["merged_asian"] == 1 and stats["merged_reprints"] == 1 and stats["asian_without_twin"] == 1


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


def test_graded_set_matching_handles_era_codes_and_dates():
    from tcgscan import graded
    ours = [("sv06", "Twilight Masquerade", "2024-05-24", 226), ("sv01", "Scarlet & Violet", "2023-03-31", 258),
            ("sv03.5", "151", "2023-09-22", 207), ("swsh1", "Sword & Shield", "2020-02-07", 216),
            ("base1", "Base Set", "1999-01-09", 102), ("sv08", "Surging Sparks", "2024-11-08", 252)]
    theirs = [{"name": "SV06: Twilight Masquerade", "releaseDate": "2024-05-24T00:00:00Z", "cardCount": 226},
              {"name": "SV01: Scarlet & Violet Base Set", "releaseDate": "2023-03-31", "cardCount": 258},
              {"name": "SV: Scarlet & Violet 151", "releaseDate": "2023-09-22", "cardCount": 207},
              {"name": "SWSH01: Sword & Shield Base Set", "releaseDate": "2020-02-07", "cardCount": 216},
              {"name": "Base Set", "releaseDate": "1999-01-09", "cardCount": 102},
              {"name": "Surging Sparks Sleeved Booster Promos", "releaseDate": "2024-11-08", "cardCount": 3},
              {"name": "SV08: Surging Sparks", "releaseDate": "2024-11-08", "cardCount": 252}]
    m = graded.match_sets(ours, theirs)
    assert {k: v["name"] for k, v in m.items()} == {
        "sv06": "SV06: Twilight Masquerade", "sv01": "SV01: Scarlet & Violet Base Set",
        "sv03.5": "SV: Scarlet & Violet 151", "swsh1": "SWSH01: Sword & Shield Base Set",
        "base1": "Base Set", "sv08": "SV08: Surging Sparks"}


def test_foil_scores_normal_vs_reverse():
    import cv2
    from tcgscan import foil
    rng = np.random.default_rng(3)
    # a card: textured artwork, smooth text area with a few text lines
    card = np.full((352, 252, 3), 200, np.uint8)
    art = cv2.resize(rng.integers(0, 255, (26, 41, 3), dtype=np.uint8), (205, 130), interpolation=cv2.INTER_NEAREST)
    card[40:170, 25:230] = art
    for y in range(200, 250, 10):           # rules text in the upper half of the text area, blank below
        card[y:y + 4, 30:220] = 30
    ref = foil.texture_grids(card)
    assert abs(foil.scores(foil.texture_grids(card), ref)[0]) < 0.02
    # same card, slightly blurred and noisy (a photo): still normal
    photo = cv2.GaussianBlur(card, (0, 0), 0.8).astype(int) + rng.normal(0, 2, card.shape).astype(int)
    photo = np.clip(photo, 0, 255).astype(np.uint8)
    assert foil.scores(foil.texture_grids(photo), ref)[0] < foil.REVERSE_THRESHOLD
    # reverse holo: a foil pattern of blobs over the text area
    rev = photo.copy().astype(int)
    blobs = cv2.resize((rng.random((14, 22)) > 0.5).astype(np.float32), (222, 140), interpolation=cv2.INTER_NEAREST)
    rev[180:320, 15:237] -= (blobs * 40).astype(int)[:, :, None]
    rev = np.clip(rev, 0, 255).astype(np.uint8)
    normal_score = foil.scores(foil.texture_grids(photo), ref)[0]
    assert foil.scores(foil.texture_grids(rev), ref)[0] > normal_score + 0.1
    assert foil.printing_label("Darumaka 34-167__reverse.jpg") == "reverse"
    assert foil.printing_label("Altaria 2-102__holo_sleeve.jpg") == "holo"
    assert foil.printing_label("Glurak 4-102.jpg") is None


def test_foil_lab_runs_on_fake_catalog(tmp_path):
    from tcgscan import foil
    from tcgscan.catalog import fetch_catalog, load_entries
    from tcgscan.export import foil_lab
    cache, db = tmp_path / "cache", tmp_path / "catalog.sqlite"
    fetch_catalog(FakeTCGdex(cache, n_sets=2, cards_per_set=10, workers=4), db, ["en"])
    entries = load_entries(db, cache)
    tex = foil.reference_grids(entries, tmp_path / "emb" / "foil_grids.npz")
    assert tex.shape == (len(entries), foil.N_CELLS)
    again = foil.reference_grids(entries, tmp_path / "emb" / "foil_grids.npz")     # from cache
    assert (again == tex).all()
    real = [{"file": "x__reverse.jpg", "foil": {"score": 0.9, "art": 1.0, "body": 20.0, "label": "reverse"}},
            {"file": "z.jpg", "foil": {"score": None, "art": 1.0, "body": 1.0, "label": None}},
            {"file": "y__normal.jpg", "foil": {"score": 0.1, "art": 1.0, "body": 1.0, "label": "normal"}}]
    thr = foil_lab(entries, tex, real, db, tmp_path / "foil-lab.md", n_synthetic=20)
    md = (tmp_path / "foil-lab.md").read_text()
    assert foil.REVERSE_THRESHOLD <= thr < 0.9 and "x__reverse.jpg" in md and "synthetic normal" in md
