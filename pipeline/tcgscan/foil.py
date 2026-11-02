"""Reverse holo / holo check from a single photo.

A reverse holo is the normal card with a foil layer everywhere except the artwork. In
a photo that foil shows up as extra texture in the text area (patterns, sparkle,
rainbow edges) that the printed card - and so the TCGdex scan of it - doesn't have.

Each reference card gets a tiny texture fingerprint: how much fine detail each cell of
an 8x5 grid over the artwork and an 8x4 grid over the text area has. The scan tells
which text-area cells are blank (no print) and which hold text. In a photo:

    score = detail(blank cells) / detail(text cells)  -  the same ratio in the scan

Blank and text cells sit side by side under the same light, blur and glare, so the
ratio cancels those. A normal card scores about 0 (only noise in the blank cells); a
reverse holo clearly above (its foil gives the blank cells texture). Cards whose text
area has no clear blank/text contrast (full arts) get no score. The artwork cells are
only used to line the photo up with the scan.

The Kotlin twin is android/.../vision/FoilCheck.kt and must stay identical.
"""
from __future__ import annotations

import cv2
import numpy as np

FOIL_W, FOIL_H = 126, 176
ART_BOX = (0.10, 0.12, 0.90, 0.47)
BODY_BOX = (0.08, 0.56, 0.92, 0.86)
ART_GRID = (8, 5)        # columns, rows
BODY_GRID = (8, 4)
N_ART = ART_GRID[0] * ART_GRID[1]
N_CELLS = N_ART + BODY_GRID[0] * BODY_GRID[1]
SCALE = 400.0
# starting point only: the foil lab sets the pack's threshold from synthetic and real photos
REVERSE_THRESHOLD = 0.2


def texture_map(card_rgb: np.ndarray) -> np.ndarray:
    """Fine detail relative to local brightness, on a 126x176 grey version of the upright card."""
    small = cv2.resize(card_rgb, (FOIL_W, FOIL_H), interpolation=cv2.INTER_AREA)
    g = cv2.cvtColor(small, cv2.COLOR_RGB2GRAY).astype(np.float32)
    hp = np.abs(g - cv2.GaussianBlur(g, (0, 0), 2.0))
    base = cv2.GaussianBlur(g, (0, 0), 8.0) + 16.0
    return hp / base


def _cells(t: np.ndarray, box, grid) -> np.ndarray:
    x0, y0 = int(round(box[0] * FOIL_W)), int(round(box[1] * FOIL_H))
    x1, y1 = int(round(box[2] * FOIL_W)), int(round(box[3] * FOIL_H))
    return cv2.resize(np.ascontiguousarray(t[y0:y1, x0:x1]), grid, interpolation=cv2.INTER_AREA).ravel()


def texture_grids(card_rgb: np.ndarray) -> np.ndarray:
    """72 values 0..255: 40 artwork cells, then 32 text-area cells (row by row)."""
    t = texture_map(card_rgb)
    v = np.concatenate([_cells(t, ART_BOX, ART_GRID), _cells(t, BODY_BOX, BODY_GRID)])
    return np.clip(np.round(v * SCALE), 0, 255).astype(np.uint8)


def scores(photo: np.ndarray, ref: np.ndarray) -> tuple[float, float, float]:
    """(foil score or nan, photo detail in blank cells, photo detail in text cells)."""
    pb = photo[N_ART:].astype(np.float32)
    rb = ref[N_ART:].astype(np.float32)
    lo, hi = float(np.percentile(rb, 40)), float(np.percentile(rb, 70))
    flat, text = rb <= lo, rb >= hi
    if flat.sum() < 6 or text.sum() < 6:
        return float("nan"), 0.0, 0.0
    r_flat, r_text = float(rb[flat].mean()), float(rb[text].mean())
    p_flat, p_text = float(np.median(pb[flat])), float(np.median(pb[text]))
    if r_text < 2 * r_flat + 6:            # no clear blank vs text cells
        return float("nan"), p_flat, p_text
    return p_flat / max(p_text, 6.0) - r_flat / r_text, p_flat, p_text


def art_correlation(photo: np.ndarray, ref: np.ndarray) -> float:
    a = photo[:N_ART].astype(np.float32)
    b = ref[:N_ART].astype(np.float32)
    a, b = a - a.mean(), b - b.mean()
    d = float(np.sqrt((a * a).sum() * (b * b).sum()))
    return float((a * b).sum() / d) if d > 0 else 0.0


GROW = (1.0, 1.035, 1.07)


def photo_grids(img_rgb: np.ndarray, card_quad: np.ndarray, ref: np.ndarray, rect_size=(448, 626)) -> np.ndarray:
    """Grids of the card in a photo. The detected outline is sometimes the inner panel,
    so the outline is also tried slightly grown; the version whose artwork texture lines
    up best with the reference wins."""
    from .detect import rectify
    best, best_c = None, -2.0
    c = card_quad.mean(0)
    for k in GROW:
        g = texture_grids(rectify(img_rgb, (card_quad - c) * k + c, rect_size))
        corr = art_correlation(g, ref)
        if corr > best_c:
            best, best_c = g, corr
    return best


def card_quad_for_reading(quad: np.ndarray, reading: int, card_size=(252, 352),
                          art_box=(0.07, 0.09, 0.93, 0.50), margin: float = 0.0) -> np.ndarray:
    """Outline of the upright card for one reading of a detection (as the app does):
    0 card, 1 card upside down, 2/3 the detection is the artwork box (upright / upside down)."""
    from .detect import order_quad
    q = order_quad(np.asarray(quad, np.float32))
    if reading == 0:
        return q
    if reading == 1:
        return np.roll(q, 2, axis=0)
    land = np.roll(q, 1, axis=0)
    src = np.roll(land, 2, axis=0) if reading == 3 else land
    cw, ch = card_size
    x0, y0, x1, y1 = art_box
    dst = np.array([[x0 * cw, y0 * ch], [x1 * cw - 1, y0 * ch], [x1 * cw - 1, y1 * ch - 1], [x0 * cw, y1 * ch - 1]], np.float32)
    h = cv2.getPerspectiveTransform(dst, src.astype(np.float32))
    m = margin
    corners = np.array([[[-m * cw, -m * ch]], [[(1 + m) * cw, -m * ch]], [[(1 + m) * cw, (1 + m) * ch]],
                        [[-m * cw, (1 + m) * ch]]], np.float32)
    return cv2.perspectiveTransform(corners, h).reshape(4, 2)


def printing_label(file_name: str) -> str | None:
    """'Darumaka 34-167__reverse.jpg' -> 'reverse' (normal, holo, reverse, 1st)."""
    tail = file_name.rsplit(".", 1)[0].split("__", 1)[1].lower() if "__" in file_name else ""
    for word, label in (("reverse", "reverse"), ("rev", "reverse"), ("holo", "holo"), ("normal", "normal"),
                        ("1st", "1st"), ("first", "1st")):
        if word in tail:
            return label
    return None


def reference_grids(entries, cache_file) -> np.ndarray:
    """Texture grids of every reference image (cached by image key)."""
    from pathlib import Path
    from concurrent.futures import ThreadPoolExecutor
    from .augment import flatten, load_rgba
    keys = [f"{e.lang}/{e.card_id}/{e.path.stat().st_size}" for e in entries]
    cached: dict[str, np.ndarray] = {}
    cache_file = Path(cache_file)
    if cache_file.exists():
        z = np.load(cache_file, allow_pickle=False)
        if str(z["version"]) == VERSION:
            cached = dict(zip(z["keys"].tolist(), z["grids"]))

    def one(e):
        try:
            return texture_grids(flatten(load_rgba(e.path)))
        except Exception:
            return np.zeros(N_CELLS, np.uint8)
    todo = [i for i, k in enumerate(keys) if k not in cached]
    with ThreadPoolExecutor(8) as ex:
        for i, g in zip(todo, ex.map(lambda i: one(entries[i]), todo)):
            cached[keys[i]] = g
    out = np.stack([cached[k] for k in keys]).astype(np.uint8)
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    np.savez(cache_file, version=VERSION, keys=np.array(keys), grids=out)
    return out


VERSION = "1"
