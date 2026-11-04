"""Second picture source: mapping pokemontcg.io sets and cards to ours (no network)."""
import json
import sqlite3

from tcgscan.altimages import fill, id_spelling, norm_number, same_name
from tcgscan.catalog import SCHEMA


def test_spellings():
    assert norm_number("019") == norm_number("19") == "19"
    assert norm_number("GG01") == "GG1" and norm_number("TG05") == "TG5" and norm_number("SWSH050") == "SWSH50"
    assert norm_number("100") == "100" and norm_number("TG10") == "TG10"
    assert id_spelling("sv04.5") == "sv4pt5" and id_spelling("swsh12.5gg") == "swsh12pt5gg" and id_spelling("sv06") == "sv6"
    assert same_name("Whimsicott GX", "Whimsicott-GX") and not same_name("Pikachu", "Raichu")


def _data(tmp_path):
    d = tmp_path / "data"
    (d / "sets").mkdir(parents=True)
    (d / "cards" / "en").mkdir(parents=True)
    sets = [{"id": "swsh12pt5gg", "name": "Crown Zenith Galarian Gallery", "releaseDate": "2023/01/20"},
            {"id": "me1", "name": "Mega Evolution", "releaseDate": "2025/09/26"}]
    (d / "sets" / "en.json").write_text(json.dumps(sets))
    gg = [{"id": f"swsh12pt5gg-GG0{i}", "name": n, "number": f"GG0{i}",
           "images": {"small": f"https://img/gg{i}.png", "large": f"https://img/gg{i}_hires.png"}}
          for i, n in [(1, "Hisuian Lilligant"), (2, "Simisear"), (3, "Ice Rider Calyrex")]]
    me1 = [{"id": f"me1-{i}", "name": n, "number": str(i), "images": {"small": f"https://img/me{i}.png"}}
           for i, n in [(1, "Bulbasaur"), (19, "Gardevoir"), (2, "Ivysaur")]]
    (d / "cards" / "en" / "swsh12pt5gg.json").write_text(json.dumps(gg))
    (d / "cards" / "en" / "me1.json").write_text(json.dumps(me1))
    return d


def test_fill_maps_sets_and_cards(tmp_path):
    db = tmp_path / "catalog.sqlite"
    con = sqlite3.connect(db)
    con.executescript(SCHEMA)
    con.executemany("INSERT INTO sets VALUES (?,?,?,?,?,?,?,?)", [
        ("en", "swsh12.5gg", "Crown Zenith Galarian Gallery", "swsh", "SwSh", "2023-01-20", 70, 70),
        ("en", "mep", "MEP Black Star Promos", "me", "ME", "2025-09-26", 0, 89),
        ("en", "sv06", "Twilight Masquerade", "sv", "SV", "2024-05-24", 167, 226)])
    con.executemany("INSERT INTO cards VALUES (?,?,?,?,?,?,?)", [
        ("en", "swsh12.5gg-GG01", "swsh12.5gg", "GG01", "Hisuian Lilligant", None, None),
        ("de", "swsh12.5gg-GG01", "swsh12.5gg", "GG01", "Hisui-Dressella", None, None),
        ("en", "swsh12.5gg-GG02", "swsh12.5gg", "GG02", "Simisear", None, None),
        ("en", "swsh12.5gg-GG09", "swsh12.5gg", "GG09", "Ice Rider Calyrex", None, None),  # no such number there
        ("en", "mep-019", "mep", "019", "Whimsicott", None, None),
        ("en", "mep-001", "mep", "001", "Bulbasaur", None, None),      # same date as me1, but another set
        ("en", "sv06-034", "sv06", "034", "Darumaka", "https://tcgdex/sv06/034", None)])
    con.commit()
    con.close()
    st = fill(db, tmp_path / "cache", _data(tmp_path), download=False)
    con = sqlite3.connect(db)
    alt = dict(con.execute("SELECT id || '/' || lang, image_alt FROM cards WHERE image_alt IS NOT NULL").fetchall())
    assert alt == {"swsh12.5gg-GG01/en": "https://img/gg1.png", "swsh12.5gg-GG01/de": "https://img/gg1.png",
                   "swsh12.5gg-GG02/en": "https://img/gg2.png"}
    assert con.execute("SELECT image_alt_large FROM cards WHERE id = 'swsh12.5gg-GG01' AND lang = 'en'").fetchone()[0] \
        == "https://img/gg1_hires.png"
    # cards that have a TCGdex picture are left alone; promos are not mixed up with a main set
    assert st["sets_mapped"] == 1 and st["found"] == 2 and st["without_picture"] == 5
    assert st["still_missing_by_set"] == {"mep": 2, "swsh12.5gg": 1}
