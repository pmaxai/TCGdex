"""Artwork groups: the same picture across languages and reprints counts as one card
for recognition; which exact version it is gets decided from the printed text."""
from __future__ import annotations

import logging

import numpy as np

log = logging.getLogger(__name__)


# languages with their own set ids and scripts: their cards are linked to the
# western print by picture alone (names differ)
ASIAN = frozenset({"ja", "ko", "zh-tw", "zh-cn", "th"})
# an Asian print and its western twin: same artwork, but often a different layout or
# crop (full arts, promos), so their similarity is lower than en <-> de of one card.
# Checked by hand on samples: 0.70-0.90 pairs are the same card in other languages.
CROSS_THRESHOLD = 0.70


def _norm_name(s: str, lang: str = "en") -> str:
    import re
    import unicodedata
    if lang in ASIAN:
        return lang + ":" + re.sub(r"\s+", "", unicodedata.normalize("NFKC", s or ""))
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "", s)


def artwork_groups(entries, emb: np.ndarray, chunk: int = 1024,
                   cross_threshold: float = CROSS_THRESHOLD) -> tuple[list[str], dict]:
    """One group per artwork: every language and reprint of the same picture.

    The threshold comes from the data: English and German images of the same card
    have identical artwork, so the 5th percentile of their similarity is what
    'same picture' looks like. Reprints in the same script must also share the name.
    Asian prints (Japanese, Chinese, Korean, Thai) are linked to the single most
    similar western card, once per card id, so one Asian card can never bridge two
    different western cards.
    """
    n = len(entries)
    parent = list(range(n))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    first: dict[str, int] = {}
    for i, e in enumerate(entries):
        if e.card_id in first:
            union(i, first[e.card_id])
        else:
            first[e.card_id] = i
    # calibrate on same card, different western language
    asian = np.array([e.lang in ASIAN for e in entries])
    by_id: dict[str, list[int]] = {}
    for i, e in enumerate(entries):
        if not asian[i]:
            by_id.setdefault(e.card_id, []).append(i)
    pair_sims = [float(emb[a] @ emb[b]) for ids in by_id.values() if len(ids) > 1 for a, b in [(ids[0], ids[1])]]
    thr = float(np.percentile(pair_sims, 5)) if pair_sims else 0.92
    thr = min(max(thr, 0.85), 0.97)
    names = [_norm_name(e.name, e.lang) for e in entries]
    merged_same = merged_cross = 0
    examples = []
    western = np.nonzero(~asian)[0]
    # per Asian card id: its best western match over all its language entries
    best_for_id: dict[str, tuple[float, int, int]] = {}

    def note(a, b, v):
        if len(examples) < 12:
            examples.append(f"{entries[a].card_id}/{entries[a].lang} {entries[a].name} = "
                            f"{entries[b].card_id}/{entries[b].lang} {entries[b].name} ({v:.3f})")

    for s in range(0, n, chunk):
        sim = emb[s:s + chunk] @ emb.T
        if len(western):
            for r in np.nonzero(asian[s:s + chunk])[0]:
                j = int(western[int(np.argmax(sim[r, western]))])
                v = float(sim[r, j])
                cid = entries[s + r].card_id
                if cid not in best_for_id or v > best_for_id[cid][0]:
                    best_for_id[cid] = (v, s + r, j)
        ii, jj = np.nonzero(sim >= thr)
        for a, b in zip(ii + s, jj):
            if b <= a or entries[a].card_id == entries[b].card_id:
                continue
            if asian[a] != asian[b] or names[a] != names[b]:     # reprints: same script and same name
                continue
            if find(a) != find(b):
                union(a, b)
                merged_same += 1
                note(a, b, sim[a - s, b])
    unlinked = 0
    for cid, (v, a, b) in best_for_id.items():
        if v >= cross_threshold:
            if find(a) != find(b):
                union(a, b)
                merged_cross += 1
                note(a, b, v)
        else:
            unlinked += 1
    # group name: an English card id if the group has one (the 'English version'), else the smallest id
    members: dict[int, list[int]] = {}
    for i in range(n):
        members.setdefault(find(i), []).append(i)
    name_of = {}
    for root, idx in members.items():
        en = sorted(entries[i].card_id for i in idx if entries[i].lang == "en")
        name_of[root] = en[0] if en else sorted(entries[i].card_id for i in idx)[0]
    groups = [name_of[find(i)] for i in range(n)]
    sizes = np.bincount(np.unique(groups, return_inverse=True)[1])
    stats = {"threshold": round(thr, 4), "same_card_pairs": len(pair_sims),
             "merged_reprints": merged_same, "merged_asian": merged_cross, "asian_without_twin": unlinked,
             "cross_threshold": cross_threshold,
             "groups": int(len(sizes)), "largest_group": int(sizes.max()) if len(sizes) else 0}
    log.info("artwork groups: %s", stats)
    for ex in examples:
        log.info("  same artwork: %s", ex)
    return groups, stats


