"""Turn a clean reference card image into a realistic 'phone photo' of it.

Used for the synthetic accuracy test: we know which card is in the photo and
where its corners are, so we can measure detection and matching separately.
Effects: random backgrounds and clutter, other cards nearby, rotation,
perspective, sleeves, glare, shadows, white balance, blur, noise, JPEG.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np
from PIL import Image

from .detect import CARD_ASPECT, rectify

CANVAS_W, CANVAS_H = 720, 960


def load_rgba(path) -> np.ndarray:
    with Image.open(path) as im:
        return np.asarray(im.convert("RGBA"))


def flatten(rgba: np.ndarray, bg: tuple[int, int, int] = (128, 128, 128)) -> np.ndarray:
    """RGBA -> RGB over a neutral background (card corners are transparent)."""
    a = rgba[..., 3:4].astype(np.float32) / 255
    rgb = rgba[..., :3].astype(np.float32) * a + np.array(bg, np.float32) * (1 - a)
    return rgb.astype(np.uint8)


# ----------------------------------------------------------------- backgrounds
def _rand_color(rng) -> np.ndarray:
    return rng.integers(0, 256, 3).astype(np.float32)


def random_background(rng: np.random.Generator, h: int = CANVAS_H, w: int = CANVAS_W) -> np.ndarray:
    kind = rng.choice(["solid", "gradient", "clutter", "wood", "fabric"], p=[0.15, 0.2, 0.3, 0.2, 0.15])
    if kind == "solid":
        bg = np.ones((h, w, 3), np.float32) * _rand_color(rng)
    elif kind == "gradient":
        t = np.linspace(0, 1, h if rng.random() < 0.5 else w, dtype=np.float32)
        t = t[:, None] if len(t) == h else t[None, :]
        t = np.broadcast_to(t, (h, w))[..., None]
        bg = _rand_color(rng) * (1 - t) + _rand_color(rng) * t
    elif kind == "wood":
        base = np.array([rng.uniform(60, 160), rng.uniform(40, 110), rng.uniform(20, 70)], np.float32)
        y, x = np.mgrid[0:h, 0:w].astype(np.float32)
        f = rng.uniform(0.02, 0.08)
        stripes = np.sin((x if rng.random() < 0.5 else y) * f + np.sin(y * 0.01) * 3) * 0.5 + 0.5
        bg = base * (0.75 + 0.35 * stripes[..., None])
    elif kind == "fabric":
        n = rng.normal(0, 1, (h // 4, w // 4, 3)).astype(np.float32)
        n = cv2.resize(cv2.GaussianBlur(n, (0, 0), 1.5), (w, h))
        bg = _rand_color(rng) + n * rng.uniform(10, 35)
    else:  # clutter: shapes, lines, paper-like rectangles
        bg = np.ones((h, w, 3), np.float32) * _rand_color(rng)
        for _ in range(int(rng.integers(8, 35))):
            col = tuple(float(c) for c in _rand_color(rng))
            p1 = (int(rng.integers(0, w)), int(rng.integers(0, h)))
            shape = rng.integers(0, 3)
            if shape == 0:
                p2 = (int(rng.integers(0, w)), int(rng.integers(0, h)))
                cv2.rectangle(bg, p1, p2, col, -1)
            elif shape == 1:
                cv2.circle(bg, p1, int(rng.integers(10, 150)), col, -1)
            else:
                p2 = (int(rng.integers(0, w)), int(rng.integers(0, h)))
                cv2.line(bg, p1, p2, col, int(rng.integers(1, 12)))
        bg = cv2.GaussianBlur(bg, (0, 0), rng.uniform(0.5, 3))
    bg = bg + rng.normal(0, rng.uniform(1, 6), bg.shape).astype(np.float32)
    return np.clip(bg, 0, 255)


# ----------------------------------------------------------------- geometry
def _card_quad(rng, ch: float, rot_deg: float, persp: float) -> np.ndarray:
    cw = ch * CARD_ASPECT
    q = np.array([[-cw / 2, -ch / 2], [cw / 2, -ch / 2], [cw / 2, ch / 2], [-cw / 2, ch / 2]], np.float32)
    t = np.deg2rad(rot_deg)
    r = np.array([[np.cos(t), -np.sin(t)], [np.sin(t), np.cos(t)]], np.float32)
    q = q @ r.T
    q += rng.normal(0, persp * ch, q.shape).astype(np.float32)
    return q


def _paste(canvas: np.ndarray, rgba: np.ndarray, quad: np.ndarray, alpha_scale: float = 1.0) -> np.ndarray:
    h, w = rgba.shape[:2]
    src = np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], np.float32)
    m = cv2.getPerspectiveTransform(src, quad.astype(np.float32))
    H, W = canvas.shape[:2]
    warped = cv2.warpPerspective(rgba.astype(np.float32), m, (W, H), flags=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0, 0))
    a = warped[..., 3:4] / 255 * alpha_scale
    return canvas * (1 - a) + warped[..., :3] * a


def _quad_mask(quad: np.ndarray, h: int, w: int) -> np.ndarray:
    m = np.zeros((h, w), np.float32)
    cv2.fillConvexPoly(m, quad.astype(np.int32), 1.0)
    return m


# ----------------------------------------------------------------- the generator
@dataclass
class SynthMeta:
    rot180: bool = False
    sideways: bool = False
    sleeve: bool = False
    glare: bool = False
    shadow: bool = False
    blur: bool = False
    distractors: int = 0
    card_frac: float = 0.0
    guided: bool = False
    tags: list[str] = field(default_factory=list)


GUIDE = (0.5, 0.5, 0.7)        # app guide frame: centre x, centre y, card height (fractions)


def synth_photo(card_rgba: np.ndarray, rng: np.random.Generator,
                distractors: list[np.ndarray] | tuple = (), guided: bool = False
                ) -> tuple[np.ndarray, np.ndarray, SynthMeta]:
    """Returns (photo RGB uint8, ground-truth quad tl,tr,br,bl of the upright card, meta).

    guided=True mimics the app: the user lines the card up with an on-screen
    frame, so it is roughly centred, upright and of the expected size."""
    H, W = CANVAS_H, CANVAS_W
    meta = SynthMeta()
    canvas = random_background(rng, H, W)

    # upscale the low-res reference so warping does not look blocky
    card = cv2.resize(card_rgba, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)

    # other cards around the target (flea-market boxes/binders)
    for d in distractors:
        dq = _card_quad(rng, rng.uniform(0.35, 0.7) * H, rng.uniform(-40, 40), 0.03)
        dq += np.array([rng.uniform(-0.1, 1.1) * W, rng.uniform(-0.1, 1.1) * H], np.float32)
        canvas = _paste(canvas, cv2.resize(d, None, fx=2, fy=2), dq)
        meta.distractors += 1

    # target card pose
    if guided:
        rot = float(np.clip(rng.normal(0, 4), -12, 12))
        if rng.random() < 0.05:
            rot += 180; meta.rot180 = True
        ch = GUIDE[2] * rng.uniform(0.8, 1.2) * H
    else:
        rot = float(np.clip(rng.normal(0, 8), -25, 25))
        if rng.random() < 0.10:
            rot += 180; meta.rot180 = True
        elif rng.random() < 0.08:
            rot += 90; meta.sideways = True
        ch = rng.uniform(0.45, 0.85) * H
    q = _card_quad(rng, ch, rot, rng.uniform(0, 0.05))
    # fit inside the canvas with a margin, then place randomly
    span = q.max(0) - q.min(0)
    fit = min(1.0, 0.96 * W / span[0], 0.96 * H / span[1])
    q *= fit
    lo = -q.min(0) + 0.02 * np.array([W, H])
    hi = np.array([W, H]) - q.max(0) - 0.02 * np.array([W, H])
    if guided:   # near the guide centre, still fully inside the photo
        want = np.array([GUIDE[0] * W, GUIDE[1] * H]) + rng.normal(0, 0.05, 2) * np.array([W, H])
        q += np.clip(want, lo, np.maximum(lo, hi)).astype(np.float32)
    else:
        q += np.array([rng.uniform(lo[0], max(lo[0], hi[0])), rng.uniform(lo[1], max(lo[1], hi[1]))], np.float32)
    meta.card_frac = float(cv2.contourArea(q) / (W * H))
    canvas = _paste(canvas, card, q)
    card_mask = _quad_mask(q, H, W)
    card_h = float(np.linalg.norm(q[3] - q[0]))

    # sleeve: slightly larger, faintly visible plastic with a sheen
    if rng.random() < 0.3:
        meta.sleeve = True
        c = q.mean(0)
        sq = (q - c) * rng.uniform(1.03, 1.06) + c
        sm = _quad_mask(sq, H, W)[..., None]
        canvas = canvas * (1 - sm * 0.08) + 255 * sm * 0.08
        canvas = cv2.polylines(canvas, [sq.astype(np.int32).reshape(-1, 1, 2)], True,
                               (235, 235, 235), 2)

    # lighting
    canvas *= rng.uniform(0.7, 1.2)
    canvas *= np.array([rng.uniform(0.88, 1.12), 1.0, rng.uniform(0.88, 1.12)], np.float32)
    if rng.random() < 0.35:
        meta.shadow = True
        y, x = np.mgrid[0:H, 0:W].astype(np.float32)
        t = np.deg2rad(rng.uniform(0, 360))
        g = (x * np.cos(t) + y * np.sin(t))
        g = (g - g.min()) / (np.ptp(g) + 1e-6)
        canvas *= (1 - rng.uniform(0.15, 0.5) * g)[..., None]
    if rng.random() < 0.5:
        meta.glare = True
        y, x = np.mgrid[0:H, 0:W].astype(np.float32)
        for _ in range(1 if rng.random() < 0.8 else 2):
            # glare centre somewhere on the card
            a, b = rng.uniform(0.15, 0.85, 2)
            p = q[0] + a * (q[1] - q[0]) + b * (q[3] - q[0])
            sx, sy = rng.uniform(0.05, 0.22, 2) * card_h
            blob = np.exp(-(((x - p[0]) / sx) ** 2 + ((y - p[1]) / sy) ** 2) / 2)
            canvas += (rng.uniform(0.35, 1.0) * 255 * blob * (0.4 + 0.6 * card_mask))[..., None]
    gamma = rng.uniform(0.8, 1.25)
    canvas = 255 * (np.clip(canvas, 0, 255) / 255) ** gamma

    # camera
    if rng.random() < 0.5:
        meta.blur = True
        if rng.random() < 0.7:
            canvas = cv2.GaussianBlur(canvas, (0, 0), rng.uniform(0.6, 2.2))
        else:
            k = int(rng.integers(5, 14))
            kern = np.zeros((k, k), np.float32)
            kern[k // 2, :] = 1
            rm = cv2.getRotationMatrix2D((k / 2, k / 2), rng.uniform(0, 180), 1)
            kern = cv2.warpAffine(kern, rm, (k, k))
            canvas = cv2.filter2D(canvas, -1, kern / max(kern.sum(), 1e-6))
    if rng.random() < 0.6:
        canvas += rng.normal(0, rng.uniform(2, 8), canvas.shape).astype(np.float32)
    photo = np.clip(canvas, 0, 255).astype(np.uint8)
    if rng.random() < 0.9:
        ok, buf = cv2.imencode(".jpg", photo[..., ::-1], [cv2.IMWRITE_JPEG_QUALITY, int(rng.integers(35, 91))])
        photo = cv2.imdecode(buf, cv2.IMREAD_COLOR)[..., ::-1].copy()

    meta.guided = guided
    meta.tags = [k for k in ("rot180", "sideways", "sleeve", "glare", "shadow", "blur") if getattr(meta, k)]
    if meta.distractors:
        meta.tags.append("other_cards")
    return photo, q, meta


def oracle_crop(photo: np.ndarray, quad: np.ndarray, rng: np.random.Generator, jitter: float = 0.012) -> np.ndarray:
    """Straighten using the true corners plus a little noise (a 'good' detector)."""
    card_h = float(np.linalg.norm(quad[3] - quad[0]))
    q = quad + rng.normal(0, jitter * card_h, quad.shape).astype(np.float32)
    return rectify(photo, q)
