"""Detector lab: compare card-detector variants on synthetic photos of real cards.

    python -m tcgscan detector-lab --photos 400

No AI models involved, so it is fast. For every variant it reports how often
the right card is found and how precisely it is outlined, in two scenarios:
  free   - card anywhere in the photo (boxes, binders, tables)
  guided - card roughly centred, as when lining it up with the app's frame
For the current detector it also explains each miss: was the true card never
outlined as a four-cornered shape, rejected by a filter, or outscored?
"""
from __future__ import annotations

import logging
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

from . import detect
from .augment import guide_prior, load_rgba, synth_photo
from .catalog import load_entries
from .evaluate import WORKERS

log = logging.getLogger(__name__)

VARIANTS: dict[str, dict] = {
    "current": {},
    "short sides 400+600+800": {"WORK_SIZES": (400, 600, 800)},
    "short sides 300+450+600+900": {"WORK_SIZES": (300, 450, 600, 900)},
    "no edge check": {"SUPPORT_MIN": 0.0, "SUPPORT_POW": 0},
    "no border expansion": {"EXPAND": False},
    "+ line-based quads": {"USE_LINES": True},
}
DEFAULTS = {k: getattr(detect, k) for v in VARIANTS.values() for k in v}


def _photos(entries, n: int, seed: int, guided: bool):
    def one(i):
        r = np.random.default_rng([seed, int(guided), i])
        ei = int(r.integers(len(entries)))
        distract = []
        if r.random() < 0.35:
            for _ in range(int(r.integers(1, 3))):
                j = int(r.integers(len(entries)))
                if entries[j].card_id != entries[ei].card_id:
                    distract.append(load_rgba(entries[j].path))
        photo, quad, meta = synth_photo(load_rgba(entries[ei].path), r, distract, guided=guided)
        return photo, quad, meta.tags
    with ThreadPoolExecutor(WORKERS) as ex:
        return list(ex.map(one, range(n)))


def _iou_stats(ious: np.ndarray) -> dict:
    return {"right": float((ious >= 0.6).mean()), "good": float((ious >= 0.9).mean()),
            "precise": float((ious >= 0.95).mean()), "none": float((ious < 0).mean())}


def _run_variant(photos, prior) -> np.ndarray:
    def one(p):
        photo, quad, _ = p
        d = detect.detect_cards(photo, prior=guide_prior(photo.shape) if prior == "guide" else prior)
        return detect.quad_iou(d[0].quad, quad) if d else -1.0
    with ThreadPoolExecutor(WORKERS) as ex:
        return np.array(list(ex.map(one, photos)))


def _explain(photos, prior) -> Counter:
    """Why did the current detector miss? One reason per missed photo."""
    why: Counter = Counter()
    for photo, quad, tags in photos:
        dbg: list = []
        d = detect.detect_cards(photo, prior=prior, debug=dbg)
        if d and detect.quad_iou(d[0].quad, quad) >= 0.6:
            continue
        best_iou, best_stage = 0.0, None
        for q, stage in dbg:
            iou = detect.quad_iou(q, quad)
            if iou > best_iou:
                best_iou, best_stage = iou, stage
        if best_iou < 0.6:
            why["card outline never found as a 4-corner shape"] += 1
        elif best_stage == "shape":
            why["outline found, rejected by shape check (proportions/angles)"] += 1
        elif best_stage == "support":
            why["outline found, rejected by edge check"] += 1
        else:
            why["outline was a candidate, but another shape scored higher"] += 1
        if "other_cards" in tags:
            why["   ...of these, photos with other cards in view"] += 1
    return why


def ref_image_stats(entries, n: int = 400, seed: int = 5) -> list[str]:
    """Do the reference scans have margins around the actual card?"""
    r = np.random.default_rng(seed)
    rows = []
    for i in r.choice(len(entries), size=min(n, len(entries)), replace=False):
        im = load_rgba(entries[int(i)].path)
        a = im[..., 3]
        h, w = a.shape
        ys, xs = np.nonzero(a > 128)
        transparent = float((a < 128).mean())
        if len(xs):
            box = (xs.min() / w, ys.min() / h, (xs.max() + 1) / w, (ys.max() + 1) / h)
        else:
            box = (0, 0, 0, 0)
        # uniform-colour margin: how far in from each side do rows/cols match the outermost line?
        rgb = im[..., :3].astype(np.int16)
        def depth(lines):
            ref = lines[0]
            for k, ln in enumerate(lines):
                if np.abs(ln - ref).sum(-1).mean() > 40:
                    return k
            return len(lines)
        m = [depth([rgb[k] for k in range(h // 4)]) / h, depth([rgb[h - 1 - k] for k in range(h // 4)]) / h,
             depth([rgb[:, k] for k in range(w // 4)]) / w, depth([rgb[:, w - 1 - k] for k in range(w // 4)]) / w]
        rows.append((w, h, transparent, box, np.mean(m)))
    sizes = Counter((w, h) for w, h, *_ in rows)
    tr = np.array([t for _, _, t, _, _ in rows])
    boxes = np.array([b for *_, b, _ in rows])
    marg = np.array([m for *_, m in rows])
    out = ["\n### Reference images\n",
           f"Sizes: {', '.join(f'{w}x{h} ({c})' for (w, h), c in sizes.most_common(4))}.  ",
           f"Transparent pixels: median {np.median(tr):.1%}, 90th pct {np.percentile(tr, 90):.1%}.  ",
           f"Opaque area box (median x0,y0,x1,y1): {', '.join(f'{v:.3f}' for v in np.median(boxes, 0))}.  ",
           f"Uniform-colour band at the image edge (card border or scan margin): median {np.median(marg):.1%} "
           f"of the size, 90th pct {np.percentile(marg, 90):.1%}; images with a band > 6%: {(marg > 0.06).mean():.1%}.\n"]
    return out


def run(db: Path, cache_dir: Path, n: int, seed: int = 99) -> str:
    t0 = time.time()
    entries = load_entries(db, cache_dir)
    lines = ["# Detector lab\n", f"{n} synthetic photos per scenario, {len(entries):,} card images.\n",
             "| variant | scenario | right card | good outline (≥0.9) | precise (≥0.95) | nothing found |",
             "|---|---|---|---|---|---|"]
    explain_out = []
    for scenario in ("free", "guided"):
        photos = _photos(entries, n, seed, scenario == "guided")
        priors = {"": None} if scenario == "free" else {"": None, " + guide prior": "guide"}
        for vname, cfg in VARIANTS.items():
            for k, v in DEFAULTS.items():
                setattr(detect, k, v)
            for k, v in cfg.items():
                setattr(detect, k, v)
            for pname, prior in priors.items():
                if pname and vname != "current":
                    continue
                st = _iou_stats(_run_variant(photos, prior))
                lines.append(f"| {vname}{pname} | {scenario} | {st['right']:.1%} | {st['good']:.1%} | "
                             f"{st['precise']:.1%} | {st['none']:.1%} |")
                log.info("%s%s / %s: right %.1f%%", vname, pname, scenario, 100 * st["right"])
        for k, v in DEFAULTS.items():
            setattr(detect, k, v)
        why = _explain(photos, None)
        explain_out.append(f"\n### Why the current detector misses ({scenario})\n")
        explain_out.append("| reason | photos |")
        explain_out.append("|---|---|")
        for reason, c in why.most_common():
            explain_out.append(f"| {reason} | {c} ({c / n:.1%}) |")
    lines += explain_out
    lines += ref_image_stats(entries)
    lines.append(f"\nRuntime {time.time() - t0:.0f}s.")
    return "\n".join(lines) + "\n"
