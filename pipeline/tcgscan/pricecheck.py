"""Checks TCGdex's Cardmarket prices before anything uses them.

TCGdex finds a card's Cardmarket prices through a link to a Cardmarket product. Some links
are wrong: the common Plinfa 54/236 points at the product of the secret rare Plinfa 239/236
(and so shows 39.74 EUR instead of 0.10), the e-Card holo and non-holo versions point at one
product, whole sets point at another set's products (Team Magma vs Team Aqua -> Sandstorm).
Two different cards with exactly the same Cardmarket figures can only come from such a link.

What is done:
- shared: cards with identical Cardmarket figures form a group. The price stays with the card
  whose TCGplayer price fits it (within 4x, and clearly the best fit); the other cards lose it
  ('dropped': the app shows TCGplayer instead). If no card can be told apart that way, a card
  whose TCGplayer price doesn't fit loses it too, the others keep it marked 'uncertain'.
  Groups where the price and every card are under 3 EUR are left alone (chance, and bulk).
- disagree: any other card worth 3 EUR or more whose Cardmarket and TCGplayer prices are more
  than 4x apart is marked 'disagree' (the app warns).

The result is the table price_check(id, status, cm_trend, tp_lo, tp_hi, others); dropped rows
move from prices_cardmarket to prices_cardmarket_dropped.
"""
from __future__ import annotations

import json
import logging
import math
import sqlite3
from collections import defaultdict

log = logging.getLogger(__name__)

# the Cardmarket figures compared (two cards with all of these equal share one product)
FIELDS = ["avg", "low", "trend", "avg1", "avg7", "avg30", "avg_holo", "low_holo", "trend_holo",
          "avg1_holo", "avg7_holo", "avg30_holo"]
MIN_VALUES = 3            # figures that must be there (and not 0) for a match to mean anything
FIT = math.log(4.0)       # Cardmarket within 4x of TCGplayer: the price fits the card
CLEARLY = math.log(1.5)   # the best fit must beat the next one by this much
MIN_EUR = 3.0             # a disagreement matters from 3 EUR on (bulk differs by nature)
USD_PER_EUR = 1.10        # rough is enough for a 4x test

CHECK_SCHEMA = """
CREATE TABLE IF NOT EXISTS price_check(id TEXT PRIMARY KEY, status TEXT NOT NULL, cm_trend REAL,
                                       tp_lo REAL, tp_hi REAL, others TEXT);
CREATE TABLE IF NOT EXISTS prices_cardmarket_dropped AS SELECT * FROM prices_cardmarket WHERE 0;
"""


def tp_range(data: str | None, rate: float = USD_PER_EUR) -> tuple[float, float] | None:
    """Lowest and highest TCGplayer market price (in EUR) of a card's printings other than
    reverse holo (Cardmarket's main price is not a reverse holo); all printings if that's all."""
    try:
        d = json.loads(data) if data else {}
    except ValueError:
        return None
    main, rev = [], []
    for k, v in d.items():
        if not isinstance(v, dict):
            continue
        p = next((v[f] for f in ("marketPrice", "midPrice", "lowPrice") if isinstance(v.get(f), (int, float)) and v[f] > 0), None)
        if p is not None:
            (rev if "reverse" in k.lower() else main).append(p / rate)
    vals = main or rev
    return (min(vals), max(vals)) if vals else None


def distance(cm: float, rng: tuple[float, float]) -> float:
    """How far a Cardmarket price is from a TCGplayer range, as a log ratio (0 = inside)."""
    lo, hi = rng
    if cm <= 0:
        return math.inf
    if cm < lo:
        return math.log(lo / cm)
    if cm > hi:
        return math.log(cm / hi)
    return 0.0


def decide(cm: float | None, members: list[str], tp: dict[str, tuple[float, float]]) -> tuple[str | None, list[str]]:
    """The card a shared price belongs to (None: can't tell) and the cards that lose it."""
    if cm is None or cm <= 0:
        return None, []
    fits = sorted((distance(cm, tp[i]), i) for i in members if i in tp)
    fits = [(d, i) for d, i in fits if d <= FIT]
    if not fits:
        return None, []
    if len(fits) > 1 and fits[1][0] - fits[0][0] < CLEARLY:
        return None, []
    owner = fits[0][1]
    return owner, [i for i in members if i != owner]


def run(con: sqlite3.Connection, rate: float = USD_PER_EUR) -> dict:
    """Checks prices_cardmarket against itself and TCGplayer; writes price_check (see above)."""
    con.executescript(CHECK_SCHEMA)
    con.execute("DELETE FROM price_check")
    # a second run starts from TCGdex's prices again
    con.execute("INSERT OR IGNORE INTO prices_cardmarket SELECT * FROM prices_cardmarket_dropped")
    con.execute("DELETE FROM prices_cardmarket_dropped")
    cols = [r[1] for r in con.execute("PRAGMA table_info(prices_cardmarket)")]
    have = [f for f in FIELDS if f in cols]
    rows = con.execute(f"SELECT id, {', '.join(have)} FROM prices_cardmarket").fetchall()
    trend_i = have.index("trend") + 1
    avg30_i = have.index("avg30") + 1 if "avg30" in have else None

    def headline(r) -> float | None:
        return r[trend_i] or (r[avg30_i] if avg30_i else None)

    tp = {}
    for cid, data in con.execute("SELECT id, data FROM prices_tcgplayer"):
        rng = tp_range(data, rate)
        if rng:
            tp[cid] = rng
    groups: dict[tuple, list] = defaultdict(list)
    for r in rows:
        key = tuple(r[1:])
        if sum(1 for v in key if v not in (None, 0)) >= MIN_VALUES:
            groups[key].append(r)

    out: dict[str, tuple] = {}
    dropped: list[str] = []
    for members in groups.values():
        if len(members) < 2:
            continue
        ids = [r[0] for r in members]
        cm = headline(members[0])
        if cm is None or cm <= 0:
            continue
        # two bulk cards with the same few cents can be chance, and don't matter either
        if cm < MIN_EUR and not any(tp[i][1] >= MIN_EUR for i in ids if i in tp):
            continue
        owner, _ = decide(cm, ids, tp)
        for cid in ids:
            if cid == owner:
                continue
            rng = tp.get(cid)
            misfit = rng is not None and distance(cm, rng) > FIT
            if owner is not None:
                status, others = "dropped", owner            # the price is another card's
            elif misfit:
                status, others = "dropped", ",".join(i for i in ids if i != cid)   # it fits no card, surely not this one
            elif cm >= MIN_EUR:
                status, others = "uncertain", ",".join(i for i in ids if i != cid)
            else:
                continue                                     # a bulk price on a bulk (or unpriced) card
            out[cid] = (cid, status, cm, *(rng or (None, None)), others)
            if status == "dropped":
                dropped.append(cid)
    # Cardmarket and TCGplayer far apart (cards not settled above)
    for r in rows:
        cid = r[0]
        if cid in out or cid not in tp:
            continue
        cm = headline(r)
        if cm is None or cm <= 0:
            continue
        lo, hi = tp[cid]
        if max(cm, hi) >= MIN_EUR and distance(cm, (lo, hi)) > FIT:
            out[cid] = (cid, "disagree", cm, lo, hi, None)
    con.executemany("INSERT OR REPLACE INTO price_check VALUES (?,?,?,?,?,?)", list(out.values()))
    if dropped:
        marks = ",".join("?" * len(dropped))
        con.execute(f"INSERT INTO prices_cardmarket_dropped SELECT * FROM prices_cardmarket WHERE id IN ({marks})", dropped)
        con.execute(f"DELETE FROM prices_cardmarket WHERE id IN ({marks})", dropped)
    con.commit()
    stats = {s: sum(1 for v in out.values() if v[1] == s) for s in ("dropped", "uncertain", "disagree")}
    stats["shared_groups"] = sum(1 for g in groups.values() if len(g) > 1)
    log.info("price check: %s", stats)
    for cid in sorted(dropped, key=lambda i: -(out[i][2] or 0))[:8]:
        v = out[cid]
        log.info("  dropped %s: Cardmarket %.2f EUR belongs to %s (TCGplayer here %s EUR)", cid, v[2] or 0, v[5],
                 "-" if v[3] is None else f"{v[3]:.2f}-{v[4]:.2f}")
    return stats
