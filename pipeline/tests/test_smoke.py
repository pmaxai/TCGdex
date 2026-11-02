"""End-to-end smoke test on fake data (no network, no torch needed).

    cd pipeline && python -m pytest -q tests
"""
from __future__ import annotations

import json
import sqlite3

import numpy as np

from tcgscan.augment import synth_photo
from tcgscan.catalog import fetch_catalog, load_entries
from tcgscan.detect import detect_cards, quad_iou
from tcgscan.evaluate import run

from fake_tcgdex import FakeTCGdex, fake_card


def test_fetch_is_cached_and_complete(tmp_path):
    cache, db = tmp_path / "cache", tmp_path / "catalog.sqlite"
    client = FakeTCGdex(cache, n_sets=3, cards_per_set=10, workers=4)
    stats = fetch_catalog(client, db, ["en", "de"])
    first = client.http_requests
    assert [s.cards for s in stats] == [30, 30]
    con = sqlite3.connect(db)
    assert con.execute("SELECT COUNT(*) FROM cards").fetchone()[0] == 60
    assert con.execute("SELECT COUNT(*) FROM prices_cardmarket WHERE trend = 0.4").fetchone()[0] == 30
    assert con.execute("SELECT avg_holo FROM prices_cardmarket LIMIT 1").fetchone()[0] == 1.2
    # the odd '?' card id survives as a file name
    assert con.execute("SELECT COUNT(*) FROM cards WHERE id LIKE '%-?' AND image_file IS NOT NULL").fetchone()[0] >= 1
    con.close()
    # second run: everything served from cache
    client2 = FakeTCGdex(cache, n_sets=3, cards_per_set=10, workers=4)
    fetch_catalog(client2, db, ["en", "de"])
    assert client2.http_requests == 0 < first
    assert len(load_entries(db, cache)) == 60 - len(client.missing_de_image & {c for s in client.sets.values() for c, _, _ in s["cards"]})


def test_detector_finds_synthetic_cards():
    rng = np.random.default_rng(0)
    hits = 0
    n = 40
    for i in range(n):
        card = fake_card(i, i + 1, i + 2)
        photo, quad, meta = synth_photo(card, rng)
        dets = detect_cards(photo)
        hits += bool(dets) and quad_iou(dets[0].quad, quad) > 0.6
    print(f"detector: {hits}/{n}")
    assert hits / n > 0.6


def test_full_evaluation(tmp_path):
    cache, db, out = tmp_path / "cache", tmp_path / "catalog.sqlite", tmp_path / "out"
    client = FakeTCGdex(cache, n_sets=6, cards_per_set=40, workers=4)
    fetch_catalog(client, db, ["en", "de"])
    real = tmp_path / "real"
    real.mkdir()
    # a 'real' photo: synthetic photo saved as jpg with the card id in the file name
    from PIL import Image
    from tcgscan.augment import load_rgba
    e = load_entries(db, cache)[5]
    photo, _, _ = synth_photo(load_rgba(e.path), np.random.default_rng(3))
    Image.fromarray(photo).save(real / f"{e.card_id}__test.jpg")
    Image.fromarray(photo).save(real / "unknown__mystery.jpg")

    s = run(db, cache, tmp_path / "emb", ["phash"], 120, real, out)
    m = s["models"]["phash"]
    print(json.dumps({k: m[k]["top1"] for k in ("detector", "oracle")}))
    assert m["oracle"]["top1"] > 0.5
    assert (out / "report.md").exists() and (out / "results.json").exists()
    assert (out / "pack" / "phash_embeddings.f16.npy").exists()
    assert len(s["real"]) == 2 and any(r["truth"] == [e.card_id] for r in s["real"])
    assert list((out / "debug" / "synthetic_examples").glob("*.jpg"))
    # cached fingerprints are reused on the second run
    s2 = run(db, cache, tmp_path / "emb", ["phash"], 120, real, out)
    assert s2["models"]["phash"]["oracle"]["top1"] == m["oracle"]["top1"]
