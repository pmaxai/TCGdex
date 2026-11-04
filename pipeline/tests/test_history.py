"""Price history: archive, TCGCSV parsing, calendar, monthly files, the app's table (no network)."""
import json
import sqlite3
import zlib
from datetime import date, timedelta

import numpy as np

from tcgscan import history as h
from tcgscan.catalog import SCHEMA


def test_fields_and_calendar():
    assert h.tp_field("normal") == "tp_normal" and h.tp_field("Normal") == "tp_normal"
    assert h.tp_field("reverse-holofoil") == "tp_reverse" and h.tp_field("Reverse Holofoil") == "tp_reverse"
    assert h.tp_field("holofoil") == "tp_holo" and h.tp_field("Unlimited Holofoil") == "tp_holo"
    assert h.tp_field("1st Edition Holofoil") == "tp_first" and h.tp_field("1st-edition-normal") == "tp_first"
    today = date(2026, 11, 4)
    cal = h.calendar(today)
    assert cal[-1] == today and cal == sorted(cal)
    daily = [d for d in cal if d >= today - timedelta(days=90)]
    assert len(daily) == 91
    weekly = cal[:len(cal) - 91]
    assert all((b - a).days == 7 for a, b in zip(weekly, weekly[1:]))
    assert (today - cal[0]).days <= 365


def _catalog(tmp_path):
    db = tmp_path / "catalog.sqlite"
    con = sqlite3.connect(db)
    con.executescript(SCHEMA)
    con.execute("INSERT INTO prices_cardmarket(id, trend, avg30, low, trend_holo, avg30_holo, low_holo) VALUES ('sv06-034', 0.08, 0.1, 0.02, 0.22, 0.2, 0.1)")
    con.execute("INSERT INTO prices_tcgplayer VALUES ('sv06-034', 'x', 'USD', ?)",
                (json.dumps({"normal": {"marketPrice": 0.12}, "reverse-holofoil": {"marketPrice": 0.31, "lowPrice": 0.2}}),))
    con.execute("INSERT INTO card_products VALUES ('sv06-034', 'normal', NULL, 777, 5551)")
    con.commit()
    con.close()
    return db


def test_record_merge_and_app_table(tmp_path):
    db, root = _catalog(tmp_path), tmp_path / "hist"
    today = date(2026, 11, 4)
    assert h.record(db, root, today) == 1
    arc = h.Archive(root)
    ids, vals, src = arc.load(today)
    assert ids.tolist() == ["sv06-034"] and src == "tcgdex"
    row = vals[0]
    assert row[h.FI["cm_trend"]] == 8 and row[h.FI["cm_trend_h"]] == 22
    assert row[h.FI["tp_normal"]] == 12 and row[h.FI["tp_reverse"]] == 31 and row[h.FI["tp_holo"]] == -1
    # a backfilled day merges: new values win where they have one, others stay
    other = np.full(len(h.FIELDS), -1, dtype=np.int32)
    other[h.FI["tp_normal"]] = 15
    arc.save(today, ["sv06-034", "sv06-035"], np.stack([other, other]), "tcgcsv")
    ids, vals, src = arc.load(today)
    assert ids.tolist() == ["sv06-034", "sv06-035"] and src == "tcgdex+tcgcsv"
    assert vals[0][h.FI["tp_normal"]] == 15 and vals[0][h.FI["cm_trend"]] == 8
    # a second day, then the app table
    h.record(db, root, today - timedelta(days=1))
    (root / "ecb.csv").write_text("date,usd_per_eur\n2026-11-03,1.1500\n2026-11-04,1.1600\n")
    pack = sqlite3.connect(tmp_path / "pack.sqlite")
    pack.executescript("CREATE TABLE cards(id TEXT, lang TEXT); CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);")
    pack.execute("INSERT INTO cards VALUES ('sv06-034', 'en')")
    st = h.write_app_history(pack, root, today)
    assert st["cards"] == 1
    dates = pack.execute("SELECT value FROM meta WHERE key = 'history_dates'").fetchone()[0].split(",")
    assert dates[-1] == "2026-11-04"
    blob = pack.execute("SELECT data FROM history WHERE id = 'sv06-034' AND field = 'cm_trend'").fetchone()[0]
    a = np.frombuffer(zlib.decompress(blob), dtype="<f2").astype(np.float32)
    assert len(a) == len(dates)
    assert abs(a[-1] - 0.08) < 1e-3 and abs(a[-2] - 0.08) < 1e-3 and np.isnan(a[0])
    rates = pack.execute("SELECT value FROM meta WHERE key = 'history_rates'").fetchone()[0].split(",")
    assert rates[-1] == "1.1600" and rates[-2] == "1.1500"
    # a price beyond float16's range keeps its series in float32
    arc = h.Archive(root)
    for d in (today, today - timedelta(days=1)):
        ids_d, vals_d, _ = arc.load(d)
        vals_d[ids_d.tolist().index("sv06-034"), h.FI["tp_first"]] = 12_050_050   # $120,500.50
        arc.save(d, ids_d.tolist(), vals_d, "test")
    h.write_app_history(pack, root, today)
    blob = pack.execute("SELECT data FROM history WHERE id = 'sv06-034' AND field = 'tp_first'").fetchone()[0]
    raw = zlib.decompress(blob)
    assert len(raw) == 4 * len(dates)
    assert np.frombuffer(raw, dtype="<f4")[-1] == np.float32(120500.5)
    # sv06-035 is not a card of the pack: not exported
    assert pack.execute("SELECT COUNT(*) FROM history WHERE id = 'sv06-035'").fetchone()[0] == 0


def test_parse_tcgcsv_and_months(tmp_path):
    folder = tmp_path / "x" / "2025-06-01" / "3" / "23381"
    folder.mkdir(parents=True)
    (folder / "prices").write_text(json.dumps({"success": True, "results": [
        {"productId": 5551, "subTypeName": "Normal", "marketPrice": 0.11, "midPrice": 0.2},
        {"productId": 5551, "subTypeName": "Reverse Holofoil", "marketPrice": None, "midPrice": 0.4},
        {"productId": 999, "subTypeName": "Normal", "marketPrice": 3.0}]}))
    ids, vals = h.parse_tcgcsv(tmp_path / "x", {5551: "sv06-034"})
    assert ids == ["sv06-034"]
    assert vals[0][h.FI["tp_normal"]] == 11 and vals[0][h.FI["tp_reverse"]] == 40
    root = tmp_path / "hist"
    arc = h.Archive(root)
    arc.save(date(2025, 6, 1), ids, vals, "tcgcsv")
    made = h.pack_months(root, tmp_path / "months")
    assert [p.name for p in made] == ["history-2025-06.tar"]
    fresh = tmp_path / "fresh"
    assert h.unpack_months(fresh, tmp_path / "months") == 1
    assert h.Archive(fresh).load(date(2025, 6, 1))[0].tolist() == ["sv06-034"]


def test_ppt_history_points():
    card = {
        "cardmarketPrices": {"variants": {
            "Normal": {"history": [{"date": "2026-05-09T00:00:00.000Z", "trendEur": 0.31, "avg30Eur": 1.17, "lowEur": 0.02}]},
            "Reverse Holo": {"history": [{"date": "2026-05-09T00:00:00.000Z", "trendEur": 0.9, "avg30Eur": None, "lowEur": 0.5}]}}},
        "priceHistory": {"variants": {
            "Unlimited": {"Near Mint": {"history": [{"date": "2026-05-10T00:00:00.000Z", "market": 0.28}]},
                          "Damaged": {"history": [{"date": "2026-05-10T00:00:00.000Z", "market": 0.13}]}},
            "1st Edition": {"Near Mint": {"history": [{"date": "2026-05-10T00:00:00.000Z", "market": 4.92}]}}}},
    }
    pts = h._ppt_points(card)
    d1, d2 = date(2026, 5, 9), date(2026, 5, 10)
    assert pts[d1] == {"cm_trend": 31, "cm_avg30": 117, "cm_low": 2, "cm_trend_h": 90, "cm_low_h": 50}
    assert pts[d2] == {"tp_normal": 28, "tp_first": 492}
    # a holo rare alone on Cardmarket: its price is the standard one
    only = {"cardmarketPrices": {"variants": {"Holofoil": {"history": [{"date": "2026-06-01", "trendEur": 12.5}]}}}}
    assert h._ppt_points(only) == {date(2026, 6, 1): {"cm_trend": 1250}}
