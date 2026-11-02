"""Card 'fingerprint' models. Every model maps a straightened card image to a
unit-length vector, so matching is always: highest cosine similarity wins.

Models (each on the whole card, or with `_art` on the artwork area only):
- phash   : perceptual + difference hash (no ML, what jslok/card-scanner uses)
- mnv3    : MobileNetV3-Large, ImageNet features (tiny, phone-friendly)
- dinov2s : DINOv2 ViT-S/14 self-supervised features. One pass yields three
            'views': cls (summary token), avg (mean of patch tokens, keeps more
            detail) and cat (both).

The artwork is what makes a card unique and it is the same in every language;
the frame and text layout are shared by thousands of cards.
The ML models are used off the shelf here; fine-tuning comes later if needed.
"""
from __future__ import annotations

import os
import time
from typing import Callable

import cv2
import numpy as np

INPUT = 224                    # model input: the region squashed to 224x224
PREP_VERSION = 2               # bump when image preparation changes (invalidates cached fingerprints)
CARD_W, CARD_H = 252, 352      # straightened cards are stored at this size (63:88)
REGIONS = {
    "full": (0.0, 0.0, 1.0, 1.0),
    # artwork window of standard cards (x0, y0, x1, y1 as fractions); below the
    # name bar, above the attack text. Full-art cards are art everywhere anyway.
    "art": (0.07, 0.09, 0.93, 0.50),
}


def to_card(rgb: np.ndarray) -> np.ndarray:
    return cv2.resize(rgb, (CARD_W, CARD_H), interpolation=cv2.INTER_AREA)


def crop_region(cards: np.ndarray, region: str) -> np.ndarray:
    x0, y0, x1, y1 = REGIONS[region]
    h, w = cards.shape[1:3]
    sub = cards[:, int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)]
    return np.stack([cv2.resize(c, (INPUT, INPUT), interpolation=cv2.INTER_AREA) for c in sub]) if len(sub) else \
        np.zeros((0, INPUT, INPUT, 3), np.uint8)


def view(emb: np.ndarray, sl: slice | None) -> np.ndarray:
    """Take one part of a combined fingerprint and make it unit length again."""
    if sl is None:
        return emb
    v = emb[:, sl]
    return v / np.maximum(np.linalg.norm(v, axis=1, keepdims=True), 1e-8)


class Embedder:
    name = "base"
    dim = 0
    region = "full"
    views: dict[str, slice | None] = {"": None}

    def embed(self, cards: np.ndarray) -> np.ndarray:
        """(N, CARD_H, CARD_W, 3) uint8 straightened cards -> (N, dim) float32 unit rows."""
        return self._embed(crop_region(cards, self.region))

    def _embed(self, imgs: np.ndarray) -> np.ndarray:
        raise NotImplementedError


# ------------------------------------------------------------------ hashing
class HashEmbedder(Embedder):
    """256-bit pHash + 256-bit dHash. Bits become +-1 so cosine = 1 - 2*hamming/512."""
    dim = 512

    def __init__(self, name: str = "phash", region: str = "full", hash_size: int = 16):
        self.name, self.region, self.n = name, region, hash_size

    def _bits(self, img: np.ndarray) -> np.ndarray:
        gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY).astype(np.float32)
        n = self.n
        small = cv2.resize(gray, (n * 4, n * 4), interpolation=cv2.INTER_AREA)
        dct = cv2.dct(small)[:n, :n]
        ph = (dct > np.median(dct)).ravel()
        d = cv2.resize(gray, (n + 1, n), interpolation=cv2.INTER_AREA)
        dh = (d[:, 1:] > d[:, :-1]).ravel()
        return np.concatenate([ph, dh])

    def _embed(self, imgs: np.ndarray) -> np.ndarray:
        if not len(imgs):
            return np.zeros((0, self.dim), np.float32)
        bits = np.stack([self._bits(im) for im in imgs]).astype(np.float32)
        return (bits * 2 - 1) / np.sqrt(self.dim)


# ------------------------------------------------------------------ timm models
class TimmEmbedder(Embedder):
    def __init__(self, name: str, timm_name: str, region: str = "full", batch: int = 64,
                 vit_views: bool = False, **create_kwargs):
        import torch
        import timm

        torch.set_num_threads(max(1, os.cpu_count() or 1))
        self.torch = torch
        self.name, self.timm_name, self.region, self.batch = name, timm_name, region, batch
        self.vit_views = vit_views
        self.model = timm.create_model(timm_name, pretrained=True, num_classes=0, **create_kwargs).eval()
        cfg = self.model.pretrained_cfg
        self.mean = torch.tensor(cfg.get("mean", (0.485, 0.456, 0.406))).view(1, 3, 1, 1)
        self.std = torch.tensor(cfg.get("std", (0.229, 0.224, 0.225))).view(1, 3, 1, 1)
        with torch.inference_mode():
            d = int(self.model(torch.zeros(1, 3, INPUT, INPUT)).shape[1])
        if vit_views:
            self.dim = 2 * d
            self.views = {"cls": slice(0, d), "avg": slice(d, 2 * d), "cat": None}
        else:
            self.dim = d
            self.views = {"": None}

    def _forward(self, x):
        F = self.torch.nn.functional
        if not self.vit_views:
            return F.normalize(self.model(x), dim=1)
        tokens = self.model.forward_features(x)                  # (B, prefix + patches, C), normed
        cls = F.normalize(tokens[:, 0], dim=1)
        avg = F.normalize(tokens[:, self.model.num_prefix_tokens:].mean(1), dim=1)
        return self.torch.cat([cls, avg], 1) / np.sqrt(2)

    def _embed(self, imgs: np.ndarray) -> np.ndarray:
        torch = self.torch
        out = []
        with torch.inference_mode():
            for i in range(0, len(imgs), self.batch):
                x = torch.from_numpy(np.ascontiguousarray(imgs[i:i + self.batch])).permute(0, 3, 1, 2).float() / 255
                out.append(self._forward((x - self.mean) / self.std).numpy())
        return np.concatenate(out).astype(np.float32) if out else np.zeros((0, self.dim), np.float32)


_DINO = "vit_small_patch14_dinov2.lvd142m"
_MNV3 = "mobilenetv3_large_100.ra_in1k"
MODELS: dict[str, Callable[[], Embedder]] = {
    "phash": lambda: HashEmbedder("phash"),
    "phash_art": lambda: HashEmbedder("phash_art", "art"),
    "mnv3": lambda: TimmEmbedder("mnv3", _MNV3),
    "mnv3_art": lambda: TimmEmbedder("mnv3_art", _MNV3, "art"),
    "dinov2s": lambda: TimmEmbedder("dinov2s", _DINO, batch=32, vit_views=True, img_size=INPUT),
    "dinov2s_art": lambda: TimmEmbedder("dinov2s_art", _DINO, "art", batch=32, vit_views=True, img_size=INPUT),
}


def get_embedder(name: str) -> Embedder:
    if name not in MODELS:
        raise SystemExit(f"unknown model {name!r}; choose from {', '.join(MODELS)}")
    return MODELS[name]()


def timed_embed(model: Embedder, cards: np.ndarray) -> tuple[np.ndarray, float]:
    """Embed and return (vectors, milliseconds per image)."""
    t = time.perf_counter()
    e = model.embed(cards)
    return e, (time.perf_counter() - t) * 1000 / max(1, len(cards))
