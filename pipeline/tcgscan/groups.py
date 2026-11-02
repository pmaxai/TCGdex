"""Artwork groups: the same picture across languages and reprints counts as one card
for recognition; which exact version it is gets decided from the printed text."""
from __future__ import annotations

import logging

import numpy as np

log = logging.getLogger(__name__)


def _norm_name(s: str) -> str:
    import re
    import unicodedata
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "", s)


def artwork_groups(entries, emb: np.ndarray, chunk: int = 1024) -> tuple[list[str], dict]:
    """One group per artwork: every language and reprint of the same picture.

    The threshold comes from the data: English and German images of the same card
    have identical artwork, so the 5th percentile of their similarity is what
    'same picture' looks like. Same-script reprints must also share the name;
    Japanese <-> other languages are matched by picture alone (names differ).
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
    # calibrate on same card, different language
    by_id: dict[str, list[int]] = {}
    for i, e in enumerate(entries):
        by_id.setdefault(e.card_id, []).append(i)
    pair_sims = [float(emb[a] @ emb[b]) for ids in by_id.values() if len(ids) > 1 for a, b in [(ids[0], ids[1])]]
    thr = float(np.percentile(pair_sims, 5)) if pair_sims else 0.92
    thr = min(max(thr, 0.85), 0.97)
    names = [_norm_name(e.name) for e in entries]
    is_ja = np.array([e.lang == "ja" for e in entries])
    merged_same = merged_cross = 0
    examples = []
    latin = np.nonzero(~is_ja)[0]
    best_latin = np.full(n, -1)
    best_sim = np.full(n, -1.0, np.float32)

    def note(a, b, v):
        if len(examples) < 12:
            examples.append(f"{entries[a].card_id}/{entries[a].lang} {entries[a].name} = "
                            f"{entries[b].card_id}/{entries[b].lang} {entries[b].name} ({v:.3f})")

    for s in range(0, n, chunk):
        sim = emb[s:s + chunk] @ emb.T
        # Japanese prints: remember the single best other-language match (merged below,
        # one link each, so a Japanese card can never bridge two different cards)
        for r in np.nonzero(is_ja[s:s + chunk])[0]:
            if len(latin):
                j = latin[int(np.argmax(sim[r, latin]))]
                best_latin[s + r], best_sim[s + r] = j, sim[r, j]
        ii, jj = np.nonzero(sim >= thr)
        for a, b in zip(ii + s, jj):
            if b <= a or entries[a].card_id == entries[b].card_id:
                continue
            if is_ja[a] != is_ja[b] or names[a] != names[b]:     # reprints: same script and same name
                continue
            if find(a) != find(b):
                union(a, b)
                merged_same += 1
                note(a, b, sim[a - s, b])
    for a in np.nonzero(is_ja)[0]:
        b = int(best_latin[a])
        if b >= 0 and best_sim[a] >= thr and find(a) != find(b):
            union(a, b)
            merged_cross += 1
            note(a, b, best_sim[a])
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
             "merged_reprints": merged_same, "merged_japanese": merged_cross,
             "groups": int(len(sizes)), "largest_group": int(sizes.max()) if len(sizes) else 0}
    log.info("artwork groups: %s", stats)
    for ex in examples:
        log.info("  same artwork: %s", ex)
    return groups, stats


