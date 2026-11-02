"""Offline stand-in for the TCGdex API, used by the smoke test.

Serves invented sets and cards whose images are abstract random shapes
(no real card art). Some cards are 'reprints' that reuse another card's
artwork, and every card exists in 'en' and 'de' with different text blocks,
which mirrors the hard cases of the real catalog.
"""
from __future__ import annotations

import io
import re
import zlib
from urllib.parse import quote, unquote

import cv2
import numpy as np
from PIL import Image

from tcgscan.tcgdex import API_BASE, TCGdexClient


def h(x) -> int:
    """Stable hash (Python's hash() changes between runs)."""
    return zlib.crc32(repr(x).encode())

ASSETS = "https://assets.example.invalid"


def fake_card(art_seed: int, text_seed: int, frame_seed: int) -> np.ndarray:
    W, H = 245, 337
    ra, rt, rf = (np.random.default_rng(s) for s in (art_seed, text_seed, frame_seed))
    img = np.zeros((H, W, 4), np.uint8)
    border = np.array([rf.integers(180, 255), rf.integers(150, 230), rf.integers(0, 90)], np.uint8)
    img[..., :3] = border
    img[..., 3] = 255
    panel = rf.integers(150, 255, 3)
    cv2.rectangle(img, (10, 10), (W - 11, H - 11), (*map(int, panel), 255), -1)
    # artwork window: random gradient + shapes (abstract only)
    x0, y0, x1, y1 = 20, 38, W - 21, 170
    g = np.linspace(0, 1, x1 - x0)[None, :, None]
    c1, c2 = ra.integers(0, 256, 3), ra.integers(0, 256, 3)
    img[y0:y1, x0:x1, :3] = (c1 * (1 - g) + c2 * g).astype(np.uint8)
    art = img[y0:y1, x0:x1].copy()
    for _ in range(int(ra.integers(4, 10))):
        col = (*map(int, ra.integers(0, 256, 3)), 255)
        p = (int(ra.integers(0, x1 - x0)), int(ra.integers(0, y1 - y0)))
        if ra.random() < 0.5:
            cv2.circle(art, p, int(ra.integers(6, 45)), col, -1)
        else:
            pts = ra.integers(0, [x1 - x0, y1 - y0], (int(ra.integers(3, 6)), 2)).astype(np.int32)
            cv2.fillPoly(art, [pts], col)
    img[y0:y1, x0:x1] = art
    # 'text': dark bars of random length (name, attacks, number) - language dependent
    def bars(y, n, h=7):
        for k in range(n):
            ln = int(rt.integers(30, W - 60))
            cv2.rectangle(img, (22, y + k * (h + 6)), (22 + ln, y + k * (h + 6) + h), (30, 30, 30, 255), -1)
    bars(18, 1, 10)
    bars(185, 6)
    bars(H - 30, 1, 6)
    # rounded, transparent corners like the real webp scans
    mask = np.zeros((H, W), np.uint8)
    r = 12
    cv2.rectangle(mask, (r, 0), (W - r, H), 255, -1)
    cv2.rectangle(mask, (0, r), (W, H - r), 255, -1)
    for cx, cy in ((r, r), (W - r, r), (r, H - r), (W - r, H - r)):
        cv2.circle(mask, (cx, cy), r, 255, -1)
    img[..., 3] = np.minimum(img[..., 3], mask)
    return img


class FakeResponse:
    def __init__(self, status: int, data=None, content: bytes = b"", ctype: str = "application/json"):
        self.status_code = status
        self._data = data
        self.content = content
        self.headers = {"Content-Type": ctype}

    def json(self):
        return self._data

    def raise_for_status(self):
        raise RuntimeError(f"HTTP {self.status_code}")


class FakeTCGdex(TCGdexClient):
    """Real client logic (cache, retries, threads) with a fake network."""

    def __init__(self, cache_dir, n_sets=6, cards_per_set=40, reprint_frac=0.1, seed=7, **kw):
        super().__init__(cache_dir, **kw)
        rng = np.random.default_rng(seed)
        self.sets = {}
        self.art_of: dict[str, int] = {}
        all_ids: list[str] = []
        for si in range(n_sets):
            sid = f"fs{si + 1}" if si % 3 else f"fs{si + 1}.5"   # ids with dots like sv03.5
            serie = f"era{si // 2 + 1}"
            cards = []
            for ci in range(cards_per_set):
                local = str(ci + 1) if ci != 7 else "?"            # odd ids exist (Unown '?')
                cid = f"{sid}-{local}"
                art = h(cid)
                if all_ids and rng.random() < reprint_frac:      # reprint: reuse artwork
                    art = self.art_of[str(rng.choice(all_ids))]
                cards.append((cid, local, art))
                all_ids.append(cid)
                self.art_of[cid] = art
            self.sets[sid] = {"serie": serie, "cards": cards, "release": f"20{10 + si}-01-01"}
        self.missing_de_image = set(all_ids[::17])

    def _http_get(self, url: str):
        with self._count_lock:
            self.http_requests += 1
        if url.startswith(ASSETS):
            m = re.match(rf"{re.escape(ASSETS)}/(\w+)/([^/]+)/([^/]+)/low\.webp$", url)
            lang, sid, local = m.groups()
            cid = f"{unquote(sid)}-{unquote(local)}"
            art = self.art_of[cid]
            text_seed = h((cid, lang))
            frame_seed = h(sid)
            buf = io.BytesIO()
            Image.fromarray(fake_card(art, text_seed, frame_seed)).save(buf, "WEBP", quality=85)
            return FakeResponse(200, content=buf.getvalue(), ctype="image/webp")
        path = url[len(API_BASE) + 1:].split("/")
        path = [unquote(p) for p in path]
        lang = path[0]
        if path[1:] == ["sets"]:
            return FakeResponse(200, [{"id": s, "name": f"Set {s}", "cardCount": {"total": len(v["cards"])}}
                                      for s, v in self.sets.items()])
        if path[1] == "sets" and len(path) == 3:
            s = self.sets.get(path[2])
            if not s:
                return None
            cards = []
            for cid, local, _ in s["cards"]:
                c = {"id": cid, "localId": local, "name": f"Monster {h(cid) % 97}" + (" DE" if lang == "de" else "")}
                if not (lang == "de" and cid in self.missing_de_image):
                    c["image"] = f"{ASSETS}/{lang}/{quote(path[2], safe='')}/{quote(local, safe='')}"
                cards.append(c)
            return FakeResponse(200, {"id": path[2], "name": f"Set {path[2]}", "serie": {"id": s["serie"], "name": s["serie"]},
                                      "releaseDate": s["release"], "cardCount": {"official": len(cards), "total": len(cards)},
                                      "cards": cards})
        if path[1] == "cards":
            cid = path[2]
            return FakeResponse(200, {"id": cid, "rarity": "Common", "category": "Pokemon", "hp": 60,
                                      "variants": {"normal": True, "reverse": True},
                                      "pricing": {"cardmarket": {"updated": "2026-11-01", "unit": "EUR", "avg": 0.5,
                                                                 "trend": 0.4, "low": 0.02, "avg-holo": 1.2},
                                                  "tcgplayer": {"updated": "2026-11-01", "unit": "USD",
                                                                "normal": {"marketPrice": 0.3}}}})
        return None
