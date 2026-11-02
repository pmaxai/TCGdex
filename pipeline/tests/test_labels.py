from pathlib import Path

from tcgscan.catalog import Entry
from tcgscan.labels import LabelResolver, norm_number


def E(cid, name, local, official, lang="en"):
    return Entry(cid, lang, cid.rsplit("-", 1)[0], name, "s", Path("x"), local, official)


ENTRIES = [
    E("base1-4", "Charizard", "4", 102), E("base1-4", "Glurak", "4", 102, "de"),
    E("base2-4", "Clefable", "4", 64),
    E("sv03.5-006", "Charizard ex", "006", 165), E("sv03.5-006", "Glurak-ex", "006", 165, "de"),
    E("swsh12.5-GG05", "Arceus V", "GG05", 70),
    E("sv01-245", "Pikachu", "245", 198),
    E("base1-58", "Pikachu", "58", 102), E("base4-87", "Pikachu", "87", 130),
]
R = LabelResolver(ENTRIES)


def test_numbers():
    assert norm_number("006") == "6" and norm_number("GG05") == "GG5" and norm_number("tg5") == "TG5"


def test_resolve():
    assert R.resolve("base1-4").ids == ["base1-4"]                      # exact TCGdex id
    assert R.resolve("Glurak 4-102").ids == ["base1-4"]                 # German name + number/total
    assert R.resolve("charizard_004-102").ids == ["base1-4"]
    assert R.resolve("4-64").ids == ["base2-4"]                         # number + total only
    assert R.resolve("Glurak ex 6-165").ids == ["sv03.5-006"]
    assert R.resolve("Arceus V GG05").ids == ["swsh12.5-GG05"]          # alphanumeric numbers
    assert R.resolve("Pikachu 245-198").ids == ["sv01-245"]             # secret rare above total
    amb = R.resolve("4")
    assert set(amb.ids) == {"base1-4", "base2-4"} and amb.problem.startswith("ambiguous")
    assert R.resolve("Mewtwo 4-102").problem.startswith("no card named")
    assert R.resolve("just a name").problem == "no card number in label"
