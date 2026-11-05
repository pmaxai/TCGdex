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
    assert d2 not in pts                       # its TCGplayer history is not used (TCGCSV has it)
    # a holo rare alone on Cardmarket: its price is the standard one
    only = {"cardmarketPrices": {"variants": {"Holofoil": {"history": [{"date": "2026-06-01", "trendEur": 12.5}]}}}}
    assert h._ppt_points(only) == {date(2026, 6, 1): {"cm_trend": 1250}}


def test_app_series_glitches_and_gaps(tmp_path):
    """A one-day glitch is dropped; a card priced every second day still has a line every day."""
    root, today = tmp_path / "hist", date(2026, 11, 4)
    arc = h.Archive(root)
    for k in range(20):
        d = today - timedelta(days=k)
        rows, ids = [], []
        a = np.full(len(h.FIELDS), -1, dtype=np.int32)
        a[h.FI["tp_normal"]] = 2 if k == 5 else 500           # $5.00, one day $0.02
        rows.append(a); ids.append("base1-4")
        if k % 2 == 0:                                       # Cardmarket every second day
            b = np.full(len(h.FIELDS), -1, dtype=np.int32)
            b[h.FI["cm_trend"]] = 1000 + k
            rows.append(b); ids.append("sv06-034")
        arc.save(d, ids, np.stack(rows), "test")
    cal, series, _ = h.app_series(root, today, {"base1-4", "sv06-034"})
    tp = series[("base1-4", "tp_normal")]
    last20 = tp[-20:]
    assert np.all(np.isfinite(last20)) and np.allclose(last20, 5.0)      # glitch gone, filled by a neighbour
    cm = series[("sv06-034", "cm_trend")][-20:]
    assert np.all(np.isfinite(cm))                                       # odd days take a neighbour's price
    assert abs(cm[-1] - 10.0) < 1e-4 and abs(cm[-2] - 10.02) < 1e-4      # earlier neighbour first... or the same day
    # a jump that stays is kept
    assert not np.any(h._despike(np.array([1, 1, 1, 1, 9, 9, 9, 9], dtype=np.float32)) == False)
    assert h._despike(np.array([1, 1, 1, 0.01, 1, 1, 1], dtype=np.float32)).tolist() == [True, True, True, False, True, True, True]
    # the weekly points are Mondays and don't move from one day to the next
    weekly = [d for d in h.calendar(today) if d < today - timedelta(days=h.DAILY_DAYS)]
    assert all(d.weekday() == 0 for d in weekly)
    nxt = [d for d in h.calendar(today + timedelta(days=1)) if d < today - timedelta(days=h.DAILY_DAYS)]
    assert set(nxt) <= set(weekly) | {today - timedelta(days=h.DAILY_DAYS)}


def test_tcgplayer_only_from_tcgcsv(tmp_path):
    """TCGplayer prices PokemonPriceTracker wrote are cleared once and TCGCSV reads those days again;
    a day's own TCGdex snapshot is kept. One reading spread over a few days is not a history."""
    root, today = tmp_path / "hist", date(2026, 11, 4)
    arc = h.Archive(root)
    row = np.full(len(h.FIELDS), -1, dtype=np.int32)
    row[h.FI["tp_holo"]], row[h.FI["cm_trend"]] = 60000, 50000
    d_ppt, d_both, d_own = date(2026, 6, 1), date(2026, 10, 1), date(2026, 11, 3)
    arc.save(d_ppt, ["base1-4"], row[None], "ppt")
    arc.save(d_both, ["base1-4"], row[None], "ppt")
    arc.save(d_both, ["base1-4"], row[None], "tcgcsv", prefer_existing=True)
    arc.save(d_own, ["base1-4"], row[None], "tcgdex")
    arc.save(d_own, ["base1-4"], row[None], "ppt", prefer_existing=True)
    assert arc.load(d_both)[2] == "ppt+tcgcsv"
    arc.save(d_both, ["base1-4"], row[None], "ppt")
    assert arc.load(d_both)[2] == "ppt+tcgcsv"                 # no repeats
    done = {d_ppt.isoformat(), d_both.isoformat(), "2026-09-01"}
    assert h.only_tcgcsv_for_tcgplayer(arc, done) == 2
    assert done == {"2026-09-01"}
    for d in (d_ppt, d_both):
        v = arc.load(d)[1][0]
        assert v[h.FI["tp_holo"]] == -1 and v[h.FI["cm_trend"]] == 50000
    assert arc.load(d_own)[1][0][h.FI["tp_holo"]] == 60000
    # a single Cardmarket reading: no series for the app
    one = np.full(len(h.FIELDS), -1, dtype=np.int32)
    one[h.FI["cm_trend"]] = 6
    arc.save(today, ["sv06-034"], one[None], "tcgdex")
    _, series, _ = h.app_series(root, today, {"sv06-034", "base1-4"})
    assert ("sv06-034", "cm_trend") not in series
    assert ("base1-4", "cm_trend") in series
