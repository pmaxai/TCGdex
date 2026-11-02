"""Card 'fingerprint' models. Every model maps a straightened card image to a
unit-length vector, so matching is always: highest cosine similarity wins.

- phash   : perceptual + difference hash (no ML, what jslok/card-scanner uses)
- mnv3    : MobileNetV3-Large, ImageNet features (tiny, phone-friendly)
- dinov2s : DINOv2 ViT-S/14 self-supervised features (strong image retrieval)

The ML models are used off the shelf here; fine-tuning comes later if needed.
"""
from __future__ import annotations

import os
import time
from typing import Callable

import cv2
import numpy as np

INPUT = 224   # every model sees the card squashed to 224x224


def to_input(crop_rgb: np.ndarray) -> np.ndarray:
    return cv2.resize(crop_rgb, (INPUT, INPUT), interpolation=cv2.INTER_AREA)


class Embedder:
    name = "base"
    dim = 0

    def embed(self, imgs: np.ndarray) -> np.ndarray:  # (N,224,224,3) uint8 -> (N,D) float32 unit rows
        raise NotImplementedError


# ------------------------------------------------------------------ hashing
class HashEmbedder(Embedder):
    """256-bit pHash + 256-bit dHash. Bits become +-1 so cosine = 1 - 2*hamming/512."""
    name = "phash"
    dim = 512

    def __init__(self, hash_size: int = 16):
        self.n = hash_size

    def _bits(self, img: np.ndarray) -> np.ndarray:
        gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY).astype(np.float32)
        n = self.n
        small = cv2.resize(gray, (n * 4, n * 4), interpolation=cv2.INTER_AREA)
        dct = cv2.dct(small)[:n, :n]
        ph = (dct > np.median(dct)).ravel()
        d = cv2.resize(gray, (n + 1, n), interpolation=cv2.INTER_AREA)
        dh = (d[:, 1:] > d[:, :-1]).ravel()
        return np.concatenate([ph, dh])

    def embed(self, imgs: np.ndarray) -> np.ndarray:
        bits = np.stack([self._bits(im) for im in imgs]).astype(np.float32)
        return (bits * 2 - 1) / np.sqrt(self.dim)


# ------------------------------------------------------------------ timm models
class TimmEmbedder(Embedder):
    def __init__(self, name: str, timm_name: str, batch: int = 64, **create_kwargs):
        import torch
        import timm

        torch.set_num_threads(max(1, os.cpu_count() or 1))
        self.torch = torch
        self.name = name
        self.timm_name = timm_name
        self.batch = batch
        self.model = timm.create_model(timm_name, pretrained=True, num_classes=0, **create_kwargs).eval()
        cfg = self.model.pretrained_cfg
        self.mean = torch.tensor(cfg.get("mean", (0.485, 0.456, 0.406))).view(1, 3, 1, 1)
        self.std = torch.tensor(cfg.get("std", (0.229, 0.224, 0.225))).view(1, 3, 1, 1)
        with torch.inference_mode():
            self.dim = int(self.model(torch.zeros(1, 3, INPUT, INPUT)).shape[1])

    def embed(self, imgs: np.ndarray) -> np.ndarray:
        torch = self.torch
        out = []
        with torch.inference_mode():
            for i in range(0, len(imgs), self.batch):
                x = torch.from_numpy(np.ascontiguousarray(imgs[i:i + self.batch])).permute(0, 3, 1, 2).float() / 255
                x = (x - self.mean) / self.std
                f = self.model(x)
                out.append(torch.nn.functional.normalize(f, dim=1).numpy())
        return np.concatenate(out).astype(np.float32) if out else np.zeros((0, self.dim), np.float32)


MODELS: dict[str, Callable[[], Embedder]] = {
    "phash": HashEmbedder,
    "mnv3": lambda: TimmEmbedder("mnv3", "mobilenetv3_large_100.ra_in1k"),
    "dinov2s": lambda: TimmEmbedder("dinov2s", "vit_small_patch14_dinov2.lvd142m", batch=32, img_size=INPUT),
}


def get_embedder(name: str) -> Embedder:
    if name not in MODELS:
        raise SystemExit(f"unknown model {name!r}; choose from {', '.join(MODELS)}")
    return MODELS[name]()


def timed_embed(model: Embedder, imgs: np.ndarray) -> tuple[np.ndarray, float]:
    """Embed and return (vectors, milliseconds per image)."""
    t = time.perf_counter()
    e = model.embed(imgs)
    return e, (time.perf_counter() - t) * 1000 / max(1, len(imgs))
