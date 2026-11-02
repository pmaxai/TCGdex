"""Find trading cards in a photo and straighten them (classic OpenCV, no ML).

This is the "first version" detector from the plan: edges -> contours ->
four-cornered shapes with card proportions (63 x 88 mm). The same logic will
be ported to the Android app, so it is kept simple and dependency-free.
"""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

CARD_ASPECT = 63 / 88          # width / height of a standard card
RECT_W, RECT_H = 448, 626      # size of a straightened card crop (63:88)
SUPPORT_MIN = 0.15             # every side must run along an edge for >= this fraction
SUPPORT_POW = 2
PREFER_OUTER = False           # tested: +-1%, and it can swap a card for card+clutter; the matcher reads artwork boxes instead
OUTER_MIN_RATIO = 1.6          # prefer an enclosing outline (card vs. its artwork box)...
OUTER_SUPPORT = 0.0            # ...if its sides are visible at least this well
OUTER_SCORE_FRAC = 0.25
REFINE = True                  # snap each side to the edge pixels along it
EXPAND = True                  # grow an inner-panel outline to the card's outer border
USE_HULL = True                # also build quads from convex hulls (repairs ragged outlines)
USE_LINES = False              # also assemble quads from straight edge lines (tested: +1%, 4x slower)
WORK_SIZES = (300, 450, 600)   # detection runs on the photo scaled to these SHORT sides


@dataclass
class Detection:
    quad: np.ndarray   # (4, 2) float32, tl,tr,br,bl in original image coords, portrait order
    score: float


def order_quad(pts: np.ndarray) -> np.ndarray:
    """Order 4 points tl,tr,br,bl (clockwise, starting top-left) and make it portrait."""
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    c = pts.mean(0)
    ang = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    pts = pts[np.argsort(ang)]                   # clockwise in image coords (y down)
    start = int(np.argmin(pts.sum(1)))           # top-left-most point first
    pts = np.roll(pts, -start, axis=0)
    w = (np.linalg.norm(pts[1] - pts[0]) + np.linalg.norm(pts[2] - pts[3])) / 2
    h = (np.linalg.norm(pts[3] - pts[0]) + np.linalg.norm(pts[2] - pts[1])) / 2
    if w > h:                                    # landscape -> rotate order so long side is vertical
        pts = np.roll(pts, -1, axis=0)
    return pts


def _quad_score(quad: np.ndarray, img_area: float) -> float:
    q = order_quad(quad)
    sides = np.linalg.norm(q - np.roll(q, -1, axis=0), axis=1)   # top, right, bottom, left
    if sides.min() < 10:
        return 0.0
    w, h = (sides[0] + sides[2]) / 2, (sides[1] + sides[3]) / 2
    aspect = w / h
    if not 0.5 < aspect < 0.95:
        return 0.0
    area = cv2.contourArea(q)
    frac = area / img_area
    if frac > 0.98:                               # the photo frame itself
        return 0.0
    # interior angles close to 90 degrees (perspective allows some slack)
    v1 = np.roll(q, 1, axis=0) - q
    v2 = np.roll(q, -1, axis=0) - q
    cos = np.abs((v1 * v2).sum(1) / (np.linalg.norm(v1, axis=1) * np.linalg.norm(v2, axis=1) + 1e-6))
    if cos.max() > 0.5:                           # an angle outside ~60..120 degrees
        return 0.0
    aspect_score = np.exp(-((aspect - CARD_ASPECT) / 0.07) ** 2)
    angle_score = 1.0 - cos.mean()
    return float(aspect_score * angle_score * np.sqrt(frac))


def _auto_canny(ch: np.ndarray, lo_f: float = 0.66, hi_f: float = 1.33) -> np.ndarray:
    med = float(np.median(ch))
    lo = int(max(5, lo_f * med))
    hi = int(min(255, max(hi_f * med, lo + 25)))
    return cv2.Canny(ch, lo, hi)


def _edge_maps(small_rgb: np.ndarray, gray: np.ndarray):
    k3 = np.ones((3, 3), np.uint8)
    yield cv2.dilate(_auto_canny(gray), k3)
    yield cv2.dilate(_auto_canny(gray, 0.33, 0.8), k3)
    # colour edges: a yellow card border on a beige table is clear in colour, not in grey
    lab = cv2.GaussianBlur(cv2.cvtColor(small_rgb, cv2.COLOR_RGB2LAB), (5, 5), 0)
    e = np.zeros_like(gray)
    for i in range(3):
        e |= cv2.Canny(lab[..., i], 20, 50) if i else _auto_canny(lab[..., 0])
    yield cv2.morphologyEx(e, cv2.MORPH_CLOSE, k3, iterations=2)
    # adaptive threshold catches low-contrast borders (e.g. card on a similar background)
    th = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY, 31, 5)
    yield cv2.morphologyEx(255 - th, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))


def _line_intersection(p1, p2, p3, p4):
    d = (p1[0] - p2[0]) * (p3[1] - p4[1]) - (p1[1] - p2[1]) * (p3[0] - p4[0])
    if abs(d) < 1e-9:
        return None
    a = p1[0] * p2[1] - p1[1] * p2[0]
    b = p3[0] * p4[1] - p3[1] * p4[0]
    return np.array([(a * (p3[0] - p4[0]) - (p1[0] - p2[0]) * b) / d,
                     (a * (p3[1] - p4[1]) - (p1[1] - p2[1]) * b) / d], np.float32)


def _hull_to_quad(hull: np.ndarray) -> np.ndarray | None:
    """Shrink a convex polygon to 4 corners by repeatedly dropping the edge whose
    neighbours, extended until they meet, add the least area. Recovers sharp
    corners from rounded/clipped ones."""
    pts = [p.astype(np.float64) for p in hull.reshape(-1, 2)]
    if len(pts) < 4:
        return None
    while len(pts) > 4:
        n = len(pts)
        best, best_i, best_p = np.inf, -1, None
        for i in range(n):
            a, b, c, d = pts[i - 1], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
            p = _line_intersection(a, b, c, d)
            if p is None:
                continue
            # p must lie beyond b (on the a->b ray) and before c (on the d->c ray)
            if np.dot(p - b, b - a) < 0 or np.dot(p - c, c - d) < 0:
                continue
            added = abs((c[0] - b[0]) * (p[1] - b[1]) - (p[0] - b[0]) * (c[1] - b[1])) / 2
            if added < best:
                best, best_i, best_p = added, i, p
        if best_p is None:
            return None
        i = best_i
        pts[i] = best_p
        del pts[(i + 1) % len(pts)]
    return np.array(pts, np.float32)


def _edge_support(quad: np.ndarray, edges: np.ndarray, n: int = 40) -> tuple[float, float]:
    """Fraction of points along each side that lie on an edge -> (mean, weakest side).

    A real card outline is visible along all four sides; a shape invented by
    joining a card with background clutter is not."""
    h, w = edges.shape
    q = order_quad(quad)
    t = np.linspace(0.12, 0.88, n)[:, None]          # skip rounded corners
    fr = []
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        p = np.rint(a + (b - a) * t).astype(int)
        ok = (p[:, 0] >= 0) & (p[:, 0] < w) & (p[:, 1] >= 0) & (p[:, 1] < h)
        hits = np.zeros(n, bool)
        hits[ok] = edges[p[ok, 1], p[ok, 0]] > 0
        fr.append(hits.mean())
    return float(np.mean(fr)), float(np.min(fr))


def refine_quad(quad: np.ndarray, edges: np.ndarray, band_frac: float = 0.015) -> np.ndarray:
    """Fit a straight line to the edge pixels along each side, then intersect
    neighbouring lines to get precise corners. Keeps the input if unsure."""
    q = order_quad(quad).astype(np.float32)
    card_h = float(np.linalg.norm(q[3] - q[0]))
    band = max(3.0, band_frac * card_h)
    ys, xs = np.nonzero(edges)
    if len(xs) < 50:
        return q
    pts = np.stack([xs, ys], 1).astype(np.float32)
    lines = []
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        d = b - a
        L = float(np.linalg.norm(d))
        u = d / L
        nrm = np.array([-u[1], u[0]], np.float32)
        rel = pts - a
        t = rel @ u
        dist = rel @ nrm
        sel = (np.abs(dist) < band) & (t > 0.1 * L) & (t < 0.9 * L)
        if sel.sum() < 0.3 * 0.8 * L:          # too little evidence for this side
            return q
        vx, vy, x0, y0 = cv2.fitLine(pts[sel], cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        lines.append((np.array([x0, y0], np.float32), np.array([x0 + vx, y0 + vy], np.float32)))
    out = []
    for i in range(4):
        p = _line_intersection(*lines[i - 1], *lines[i])
        if p is None:
            return q
        out.append(p)
    out = np.array(out, np.float32)
    if np.abs(out - q).max() > 0.05 * card_h:     # moved too far: probably locked onto something else
        return q
    return out


def _shift_side(a, b, nrm, off):
    return a + nrm * off, b + nrm * off


def expand_to_border(quad: np.ndarray, edges: np.ndarray, img_rgb: np.ndarray,
                     lo: float = 0.015, hi: float = 0.065) -> np.ndarray:
    """If the quad is the card's inner panel, grow it to the outer edge of the border.

    Looks for an edge parallel to every side, a small and similar distance
    outside, with a uniformly coloured band (the card border) in between.
    Sleeves and binder pockets give uneven margins and are left alone."""
    q = order_quad(quad).astype(np.float32)
    card_h = float(np.linalg.norm(q[3] - q[0]))
    c = q.mean(0)
    offs = np.arange(lo * card_h, hi * card_h, max(1.0, 0.004 * card_h))
    if len(offs) < 3:
        return q
    h, w = edges.shape
    t = np.linspace(0.15, 0.85, 40)[:, None]
    lines, found = [], []
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        u = (b - a) / max(1e-6, float(np.linalg.norm(b - a)))
        nrm = np.array([-u[1], u[0]], np.float32)
        if np.dot((a + b) / 2 - c, nrm) < 0:
            nrm = -nrm                                  # point outwards
        best_off, best_sup = None, 0.0
        for off in offs:
            pa, pb = _shift_side(a, b, nrm, off)
            p = np.rint(pa + (pb - pa) * t).astype(int)
            ok = (p[:, 0] >= 0) & (p[:, 0] < w) & (p[:, 1] >= 0) & (p[:, 1] < h)
            if ok.mean() < 0.9:
                break
            sup = float((edges[p[ok, 1], p[ok, 0]] > 0).mean())
            if sup > best_sup + 0.05:                   # first strong peak going outwards
                best_off, best_sup = off, sup
            elif best_sup >= 0.6 and sup < best_sup - 0.2:
                break
        if best_off is None or best_sup < 0.6:
            return q
        found.append(best_off)
        lines.append(_shift_side(a, b, nrm, best_off))
    found = np.array(found)
    if found.max() / found.min() > 1.7:                # uneven margins: sleeve/pocket, not a border
        return q
    out = []
    for i in range(4):
        p = _line_intersection(*lines[i - 1], *lines[i])
        if p is None:
            return q
        out.append(p)
    out = np.array(out, np.float32)
    # the band between old and new outline must be one colour (a card border)
    mask = np.zeros((h, w), np.uint8)
    cv2.fillConvexPoly(mask, out.astype(np.int32), 1)
    cv2.fillConvexPoly(mask, q.astype(np.int32), 0)
    ring = img_rgb[mask > 0].astype(np.int16)
    if len(ring) < 50:
        return q
    med = np.median(ring, axis=0)
    if (np.abs(ring - med).sum(1) < 60).mean() < 0.6:
        return q
    return out


def _prior_weight(quad: np.ndarray, prior: tuple[float, float, float], shape) -> float:
    """1.0 for a card where the guide frame is, falling off with distance and size mismatch."""
    hh, ww = shape[:2]
    cx, cy, ch = prior
    q = order_quad(quad)
    c = q.mean(0) / np.array([ww, hh])
    size = (np.linalg.norm(q[3] - q[0]) + np.linalg.norm(q[2] - q[1])) / 2 / hh
    d = np.hypot(c[0] - cx, c[1] - cy)
    return float(np.exp(-(d / 0.18) ** 2) * np.exp(-(np.log(max(size, 1e-3) / ch) / 0.35) ** 2) + 0.05)


def _inside(pt: np.ndarray, quad: np.ndarray) -> bool:
    return cv2.pointPolygonTest(quad.astype(np.float32).reshape(-1, 1, 2), (float(pt[0]), float(pt[1])), False) >= 0


def _line_quads(edges: np.ndarray, max_lines: int = 18) -> list[np.ndarray]:
    """Card-shaped quads assembled from straight edge lines.

    Works where contours fail: when a card touches neighbouring cards its
    outline merges with theirs, but its four straight sides are still there."""
    h, w = edges.shape
    md = min(h, w)
    segs = cv2.HoughLinesP(edges, 1, np.pi / 180, threshold=40, minLineLength=0.12 * md, maxLineGap=0.02 * max(h, w))
    if segs is None:
        return []
    segs = np.asarray(segs).reshape(-1, 4).astype(np.float32)   # OpenCV 4: (N,1,4), OpenCV 5: (N,4)
    x1, y1, x2, y2 = segs.T
    length = np.hypot(x2 - x1, y2 - y1)
    phi = (np.arctan2(y2 - y1, x2 - x1) + np.pi / 2) % np.pi          # normal angle in [0, pi)
    rho = x1 * np.cos(phi) + y1 * np.sin(phi)
    # merge collinear segments into lines, strongest first
    lines: list[list[float]] = []                                    # [phi, rho, support]
    for i in np.argsort(-length):
        for ln in lines:
            dphi = abs(phi[i] - ln[0])
            r = rho[i]
            if dphi > np.pi / 2:                                     # wrap-around: same line, flipped normal
                dphi, r = np.pi - dphi, -r
            if dphi < np.deg2rad(3) and abs(r - ln[1]) < 0.015 * md:
                ln[2] += length[i]
                break
        else:
            lines.append([float(phi[i]), float(rho[i]), float(length[i])])
    lines = sorted(lines, key=lambda t: -t[2])[:max_lines]

    def angdiff(a, b):
        d = abs(a - b) % np.pi
        return min(d, np.pi - d)

    def same_dir_rho(a, b):   # rho of b expressed with a's normal direction
        return b[1] if abs(a[0] - b[0]) < np.pi / 2 else -b[1]

    pairs = []
    for i in range(len(lines)):
        for j in range(i + 1, len(lines)):
            a, b = lines[i], lines[j]
            if angdiff(a[0], b[0]) < np.deg2rad(12):
                d = abs(a[1] - same_dir_rho(a, b))
                if d > 0.12 * md:
                    pairs.append((a, b, d))
    out = []
    for p in range(len(pairs)):
        for q in range(p + 1, len(pairs)):
            a, b, d1 = pairs[p]
            c, e, d2 = pairs[q]
            if not np.deg2rad(65) < angdiff(a[0], c[0]) < np.deg2rad(115):
                continue
            if not 0.6 < min(d1, d2) / max(d1, d2) < 0.85:
                continue
            pts = []
            for l1, l2 in ((a, c), (c, b), (b, e), (e, a)):
                m = np.array([[np.cos(l1[0]), np.sin(l1[0])], [np.cos(l2[0]), np.sin(l2[0])]])
                if abs(np.linalg.det(m)) < 1e-6:
                    break
                pts.append(np.linalg.solve(m, [l1[1], l2[1]]))
            if len(pts) < 4:
                continue
            quad = np.array(pts, np.float32)
            if (quad[:, 0] < -0.02 * w).any() or (quad[:, 0] > 1.02 * w).any() or \
                    (quad[:, 1] < -0.02 * h).any() or (quad[:, 1] > 1.02 * h).any():
                continue
            if cv2.isContourConvex(order_quad(quad).reshape(-1, 1, 2)):
                out.append(quad)
    return out


def _scale_candidates(img_rgb: np.ndarray, work_size: int, min_area_frac: float, prior, debug, si: int):
    """Card-shaped candidates at one working resolution, quads in original image coords.

    work_size is the short side: phone photos are tall (9:20), and a card fills
    roughly half the width however tall the photo is."""
    h, w = img_rgb.shape[:2]
    scale = min(1.0, work_size / min(h, w))
    small = cv2.resize(img_rgb, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1 else img_rgb
    gray = cv2.GaussianBlur(cv2.cvtColor(small, cv2.COLOR_RGB2GRAY), (5, 5), 0)
    img_area = float(small.shape[0] * small.shape[1])
    maps = list(_edge_maps(small, gray))
    k = max(3, int(round(0.004 * max(small.shape[:2]))) | 1)   # edge tolerance grows with the image
    union = cv2.dilate(np.bitwise_or.reduce(maps), np.ones((k, k), np.uint8))
    cands = []

    def consider(quad: np.ndarray) -> None:
        s = _quad_score(quad, img_area)
        if s <= 0:
            if debug is not None:
                debug.append((quad / scale, "shape"))
            return
        sup_mean, sup_min = _edge_support(quad, union)
        if sup_min < SUPPORT_MIN:
            if debug is not None:
                debug.append((quad / scale, "support"))
            return
        if prior is not None:
            s *= _prior_weight(quad, prior, small.shape)
        if debug is not None:
            debug.append((quad / scale, "candidate"))
        cands.append((s * sup_mean ** SUPPORT_POW, quad / scale, sup_mean, si))

    if USE_LINES:
        for quad in _line_quads(union):
            consider(quad)
    for edges in maps:
        contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for c in contours:
            area = cv2.contourArea(c)
            if area < min_area_frac * img_area:
                continue
            peri = cv2.arcLength(c, True)
            quads = []
            for eps in (0.015, 0.025, 0.04):
                approx = cv2.approxPolyDP(c, eps * peri, True)
                if len(approx) == 4 and cv2.isContourConvex(approx):
                    quads.append(approx.reshape(4, 2).astype(np.float32))
                    break
            if USE_HULL:
                hull = cv2.convexHull(c)
                hull_area = cv2.contourArea(hull)
                if hull_area > 0 and area / hull_area > 0.8:     # mostly convex blob
                    hq = _hull_to_quad(cv2.approxPolyDP(hull, 0.005 * peri, True))
                    if hq is not None and area / max(cv2.contourArea(hq), 1) > 0.8:
                        quads.append(hq)
            for quad in quads:
                consider(quad)
    return cands, {"scale": scale, "small": small, "gray": gray, "union": union}


def detect_cards(img_rgb: np.ndarray, max_cards: int = 1, min_area_frac: float = 0.03,
                 work_sizes: tuple[int, ...] | None = None, prior: tuple[float, float, float] | None = None,
                 debug: list | None = None) -> list[Detection]:
    """Return up to max_cards card quads, best first.

    work_sizes: detection runs on the photo scaled to each of these short
    sides; candidates from all scales compete (coarse scales see a card's
    outline as one clean shape, fine scales place the corners more precisely).
    prior: (cx, cy, height) of where the card is expected, as fractions of the
    image (the app's on-screen guide frame). Candidates far from it score lower.
    debug: if a list is given, every quad considered is appended as
    (quad in image coords, stage) with stage 'shape', 'support' or 'candidate'.
    """
    cands, ctx = [], []
    for si, ws in enumerate(work_sizes or WORK_SIZES):
        c, x = _scale_candidates(img_rgb, ws, min_area_frac, prior, debug, si)
        cands += c
        ctx.append(x)

    cands.sort(key=lambda t: -t[0])
    kept: list[list] = []          # [group best score, quad, score of quad, scale index]
    for s, q, sup, si in cands:
        clash = [i for i, k in enumerate(kept) if _inside(q.mean(0), k[1]) or _inside(k[1].mean(0), q)]
        if not clash:
            kept.append([s, q, s, si])
            continue
        # q overlaps an already kept shape. Prefer the outer one (card rather than
        # its inner panel or artwork box) if its outline is clearly visible too.
        if not PREFER_OUTER or len(clash) != 1:
            continue
        g = kept[clash[0]]
        ratio = cv2.contourArea(q) / max(cv2.contourArea(g[1]), 1)
        if OUTER_MIN_RATIO < ratio < 4.5 and _inside(g[1].mean(0), q) and sup >= OUTER_SUPPORT \
                and s > OUTER_SCORE_FRAC * g[0]:
            g[1], g[2], g[3] = q, s, si
    kept = kept[:max_cards]
    if REFINE and kept:
        raws: dict[int, np.ndarray] = {}
        for g in kept:
            x = ctx[g[3]]
            if g[3] not in raws:
                raws[g[3]] = np.bitwise_or.reduce([_auto_canny(x["gray"]), _auto_canny(x["gray"], 0.33, 0.8)])
            raw, sc = raws[g[3]], x["scale"]
            q = refine_quad(g[1] * sc, raw)
            if EXPAND:
                q = refine_quad(expand_to_border(q, x["union"], x["small"]), raw)
            g[1] = q / sc
    return [Detection(order_quad(g[1]), g[2]) for g in kept]


def as_artwork(img_rgb: np.ndarray, quad: np.ndarray, card_size: tuple[int, int],
               art_box: tuple[float, float, float, float]) -> tuple[np.ndarray, np.ndarray]:
    """Second reading of a detection: the quad is the card's ARTWORK box, not the card.

    The artwork box is often the crispest rectangle on a card (dark frame line),
    while the card's own edge may be faint or covered by fingers. Returns two
    card-sized images (both 180-degree orientations) with the quad's content
    placed where the artwork sits on a card; the rest is neutral grey."""
    cw, ch = card_size
    x0, y0, x1, y1 = art_box
    q = order_quad(quad)
    land = np.roll(q, 1, axis=0)      # artwork is landscape: make the long side horizontal (tl,tr,br,bl)
    dst = np.array([[x0 * cw, y0 * ch], [x1 * cw - 1, y0 * ch], [x1 * cw - 1, y1 * ch - 1], [x0 * cw, y1 * ch - 1]],
                   np.float32)
    out = []
    for src in (land, np.roll(land, 2, axis=0)):
        m = cv2.getPerspectiveTransform(src.astype(np.float32), dst)
        canvas = cv2.warpPerspective(img_rgb, m, (cw, ch), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_CONSTANT,
                                     borderValue=(128, 128, 128))
        mask = np.zeros((ch, cw), np.uint8)
        cv2.rectangle(mask, (int(x0 * cw), int(y0 * ch)), (int(x1 * cw) - 1, int(y1 * ch) - 1), 1, -1)
        canvas[mask == 0] = 128
        out.append(canvas)
    return out[0], out[1]


def rectify(img_rgb: np.ndarray, quad: np.ndarray, size: tuple[int, int] = (RECT_W, RECT_H)) -> np.ndarray:
    """Warp the quad (tl,tr,br,bl) to an upright card image of `size` (w, h)."""
    w, h = size
    dst = np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], np.float32)
    m = cv2.getPerspectiveTransform(np.asarray(quad, np.float32), dst)
    return cv2.warpPerspective(img_rgb, m, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def quad_iou(a: np.ndarray, b: np.ndarray) -> float:
    a = order_quad(a).astype(np.float32)
    b = order_quad(b).astype(np.float32)
    inter, _ = cv2.intersectConvexConvex(a, b)
    union = cv2.contourArea(a) + cv2.contourArea(b) - inter
    return float(inter / union) if union > 0 else 0.0


def draw_quad(img_rgb: np.ndarray, quad: np.ndarray, color=(0, 255, 0), thickness: int = 4) -> np.ndarray:
    out = img_rgb.copy()
    q = np.asarray(quad, np.int32).reshape(-1, 1, 2)
    cv2.polylines(out, [q], True, color, thickness)
    cv2.circle(out, tuple(int(v) for v in quad[0]), thickness * 3, (255, 0, 0), -1)   # top-left marker
    return out
