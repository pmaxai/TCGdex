"""The Cardmarket price check: shared prices go to the card TCGplayer confirms (no network)."""
import json
import sqlite3

from tcgscan import pricecheck as pc
from tcgscan.catalog import SCHEMA


def _db(tmp_path):
    con = sqlite3.connect(tmp_path / "catalog.sqlite")
    con.executescript(SCHEMA)
    return con


def _cm(con, cid, trend, avg30, low=1.0, holo=None):
    con.execute("INSERT INTO prices_cardmarket(id, updated, trend, avg, low, avg1, avg7, avg30, trend_holo) VALUES (?,?,?,?,?,?,?,?,?)",
                (cid, "2026-11-03", trend, trend * 1.02, low, trend * 1.1, trend * 0.99, avg30, holo))


def _tp(con, cid, **prices):
    con.execute("INSERT INTO prices_tcgplayer VALUES (?,?,?,?)",
                (cid, "x", "USD", json.dumps({k.replace("_", "-"): {"marketPrice": v} for k, v in prices.items()})))


def test_shared_price_goes_to_the_card_that_fits(tmp_path):
    con = _db(tmp_path)
    # Plinfa 54/236 (common) carries the secret rare 239/236's price
    _cm(con, "sm12-54", 39.74, 41.15, low=10.0, holo=11.29)
    _cm(con, "sm12-239", 39.74, 41.15, low=10.0, holo=11.29)
    _tp(con, "sm12-54", normal=0.33, reverse_holofoil=0.98)
    _tp(con, "sm12-239", holofoil=62.97)
    # a whole set linked to another: ex4-6 has no TCGplayer price, ex2-6 fits
    _cm(con, "ex2-6", 139.53, 120.0)
    _cm(con, "ex4-6", 139.53, 120.0)
    _tp(con, "ex2-6", holofoil=150.0)
    # two promos with one price, no TCGplayer at all: can't tell -> uncertain
    _cm(con, "xyp-XY20", 850.0, 800.0)
    _cm(con, "xyp-XY24", 850.0, 800.0)
    # two bulk commons with the same few cents: left alone
    _cm(con, "swsh5-78", 0.03, 0.03, low=0.02)
    _cm(con, "sv08-093", 0.03, 0.03, low=0.02)
    _tp(con, "swsh5-78", normal=0.2)
    # a valuable card carrying a common's price (the other way round), no clear owner
    _cm(con, "neo2-39", 0.5, 0.45, low=0.1)
    _cm(con, "neo2-24", 0.5, 0.45, low=0.1)
    _tp(con, "neo2-39", normal=0.6)
    _tp(con, "neo2-24", normal=0.55, **{"1st_edition": 44.0})
    # not shared, but Cardmarket and TCGplayer far apart
    _cm(con, "ecard3-10", 8704.81, 8000.0)
    _tp(con, "ecard3-10", holofoil=1064.84)
    # not shared, fine
    _cm(con, "sv03.5-199", 319.5, 330.0)
    _tp(con, "sv03.5-199", holofoil=330.0)
    con.commit()
    st = pc.run(con)
    check = {r[0]: r[1:] for r in con.execute("SELECT id, status, others FROM price_check")}
    assert check["sm12-54"] == ("dropped", "sm12-239")
    assert "sm12-239" not in check
    assert check["ex4-6"] == ("dropped", "ex2-6") and "ex2-6" not in check
    assert check["xyp-XY20"][0] == "uncertain" and check["xyp-XY24"][0] == "uncertain"
    assert "swsh5-78" not in check and "sv08-093" not in check
    # neo2-24 (1st edition up to $44) and neo2-39 both fit 0.50 within 4x... 24's range is 0.5-40 EUR: it fits too
    assert check.get("neo2-39") is None or check["neo2-39"][0] != "dropped"
    assert check["ecard3-10"][0] == "disagree"
    assert "sv03.5-199" not in check
    # dropped prices left the table (kept aside), the others are still there
    left = {r[0] for r in con.execute("SELECT id FROM prices_cardmarket")}
    assert "sm12-54" not in left and "ex4-6" not in left and "sm12-239" in left and "xyp-XY20" in left
    assert {r[0] for r in con.execute("SELECT id FROM prices_cardmarket_dropped")} == {"sm12-54", "ex4-6"}
    assert st["dropped"] == 2 and st["uncertain"] == 2 and st["disagree"] == 1
    # running again gives the same result
    assert pc.run(con) == st
    assert {r[0] for r in con.execute("SELECT id FROM prices_cardmarket_dropped")} == {"sm12-54", "ex4-6"}


def test_a_day_apart_is_still_one_product(tmp_path):
    con = _db(tmp_path)
    # Magnetilo 68/236 (common, refreshed a day earlier) carries the secret rare 242/236's price:
    # every figure equal but the trend (20.40 vs 20.09)
    row = dict(avg=22.88, low=6.5, avg1=14.95, avg7=18.46, avg30=23.09, trend_holo=3.94, avg30_holo=4.11)
    for cid, trend, day in (("sm12-68", 20.40, "2026-11-05"), ("sm12-242", 20.09, "2026-11-06")):
        con.execute("INSERT INTO prices_cardmarket(id, updated, trend, avg, low, avg1, avg7, avg30, trend_holo, avg30_holo) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?)", (cid, day, trend, *row.values()))
    _tp(con, "sm12-68", normal=0.23, reverse_holofoil=0.36)
    _tp(con, "sm12-242", holofoil=26.5)
    # two bulk commons agreeing in all but two figures: chance, left alone
    _cm(con, "swsh5-78", 0.05, 0.05, low=0.02)
    _cm(con, "sv08-093", 0.06, 0.05, low=0.02)
    _tp(con, "swsh5-78", normal=0.2)
    # two valuable cards with different prices: not linked
    _cm(con, "sv03.5-199", 319.5, 330.0)
    _cm(con, "sv03.5-200", 120.0, 330.0)
    _tp(con, "sv03.5-199", holofoil=330.0)
    _tp(con, "sv03.5-200", holofoil=140.0)
    con.commit()
    pc.run(con)
    check = {r[0]: r[1:] for r in con.execute("SELECT id, status, cm_trend, others FROM price_check")}
    assert check["sm12-68"] == ("dropped", 20.40, "sm12-242")
    assert "sm12-242" not in check
    assert not {"swsh5-78", "sv08-093", "sv03.5-199", "sv03.5-200"} & set(check)
    left = {r[0] for r in con.execute("SELECT id FROM prices_cardmarket")}
    assert "sm12-68" not in left and "sm12-242" in left


def test_shared_groups_only_link_near_matches_with_a_real_price():
    rows = [("a", 10.0, 11.0, 2.0, 12.0, 10.5, 11.5, 3.1, 3.3), ("b", 10.4, 11.0, 2.0, 12.0, 10.5, 11.0, 3.1, 3.3),  # two differ
            ("c", 10.4, 11.9, 2.0, 12.0, 10.9, 11.0, 3.1, 3.3),                                  # two from b, three from a
            ("f", 10.4, 11.9, 2.0, 12.5, 10.0, 11.0, 3.0, 3.3),                                  # three from c: not linked
            ("d", 0.1, 0.1, 0.02, 0.1, 0.1, 0.2, 0.1, None), ("e", 0.1, 0.1, 0.02, 0.1, 0.12, 0.3, 0.1, None)]  # bulk
    groups = sorted(sorted(r[0] for r in g) for g in pc.shared_groups(rows))
    # c is linked through b (two figures from b), the bulk pair isn't linked
    assert groups == [["a", "b", "c"], ["d"], ["e"], ["f"]]


def test_fit_and_decide():
    assert pc.distance(10.0, (8.0, 12.0)) == 0
    assert pc.distance(39.74, (0.3, 0.3)) > pc.FIT
    tp = {"a": (0.3, 0.3), "b": (55.0, 55.0)}
    assert pc.decide(39.74, ["a", "b"], tp) == ("b", ["a"])
    # two cards fit about as well: no owner
    assert pc.decide(10.0, ["a", "b"], {"a": (9.0, 9.0), "b": (11.0, 11.0)}) == (None, [])
    assert pc.tp_range(json.dumps({"normal": {"marketPrice": 1.1}, "reverse-holofoil": {"marketPrice": 3.3}}), 1.1) == (1.0, 1.0)
    assert pc.tp_range(json.dumps({"reverse-holofoil": {"marketPrice": 2.2}}), 1.1) == (2.0, 2.0)
