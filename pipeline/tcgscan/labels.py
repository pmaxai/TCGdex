"""Turn a human label like 'Glurak 4-102' into TCGdex card ids.

You write what is printed on the card: its name (any language) and the number
at the bottom ('4/102' -> write 4-102, since '/' can't be in a file name).
Examples that work:
    Glurak 4-102.jpg          charizard_004-102.jpg      Pikachu 58.jpg
    base1-4.jpg (exact TCGdex id)   Mew ex 151-165.jpg   Arceus V TG05.jpg
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

from .catalog import Entry

NUM = re.compile(r"(?<![A-Za-z0-9])([A-Za-z]{0,4}\d{1,4}[a-z]?)(?:\s*[-_/]\s*([A-Za-z]{0,4}\d{1,4}))?(?![A-Za-z0-9])")


def norm_text(s: str) -> str:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "", s)


def norm_number(s: str) -> str:
    m = re.match(r"^([A-Za-z]*)0*(\d+)([a-z]?)$", s.strip())
    return (m.group(1).upper() + m.group(2) + m.group(3)) if m else s.strip().upper()


@dataclass
class Resolved:
    text: str
    ids: list[str] = field(default_factory=list)   # acceptable answers (several = ambiguous label)
    problem: str | None = None


class LabelResolver:
    def __init__(self, entries: list[Entry]):
        self.ids = {e.card_id for e in entries}
        self.by_num: dict[str, list[Entry]] = {}
        for e in entries:
            self.by_num.setdefault(norm_number(e.local_id), []).append(e)

    def resolve(self, text: str | None) -> Resolved:
        if not text:
            return Resolved("", problem="no label")
        raw = text.strip()
        if raw in self.ids:
            return Resolved(raw, [raw])
        nums = list(NUM.finditer(raw))
        if not nums:
            return Resolved(raw, problem="no card number in label")
        m = nums[-1]                               # the card number is normally last
        number, total = norm_number(m.group(1)), m.group(2)
        name = norm_text(raw[:m.start()] + " " + raw[m.end():])
        cands = self.by_num.get(number, [])
        if total and total.isdigit():
            with_total = [e for e in cands if e.official == int(total)]
            cands = with_total or cands             # secret rares: number > total, keep all
        if name:
            # (Asian names fold to nothing: they never match a written name)
            named = [e for e in cands if norm_text(e.name) and (name in norm_text(e.name) or norm_text(e.name) in name)]
            if not named:
                return Resolved(raw, problem=f"no card named like '{name}' with number {number}")
            cands = named
        ids = sorted({e.card_id for e in cands})
        if not ids:
            return Resolved(raw, problem=f"no card with number {number}")
        if len(ids) > 5:
            return Resolved(raw, ids, problem=f"ambiguous: {len(ids)} cards match - add the name or set total")
        return Resolved(raw, ids, problem="ambiguous: " + ", ".join(ids) if len(ids) > 1 else None)
