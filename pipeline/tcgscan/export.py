"""Build the data pack the Android app ships with.

    python -m tcgscan export --model mnv3_art --out out/app-pack

Files:
  model.onnx      fingerprint model with all preprocessing and PCA baked in:
                  input  'image'       (N, 224, 224, 3) float32, RGB 0..255, the artwork
                                       region of a straightened card, resized to 224x224
                  output 'fingerprint' (N, 256) float32 unit vectors
  index.f16       fingerprint of every reference image: N x 256 float16, little endian,
                  row i = entries.idx i in catalog.sqlite
  catalog.sqlite  entries, cards, sets, prices (Cardmarket, EUR), meta (all app settings)
  parity.zip      parity.json + test photos: what this Python pipeline detects and
                  matches, so the app's self-test can check it behaves identically
  pack.json       version and file checksums
"""
from __future__ import annotations

import hashlib
import io
import json
import logging
import shutil
import sqlite3
import time
import zipfile
from pathlib import Path

import cv2
import numpy as np

from .augment import load_rgba, synth_photo
from .catalog import load_entries
from .detect import RECT_H, RECT_W, as_artwork, detect_cards, rectify
from .embedders import CARD_H, CARD_W, INPUT, PREP_VERSION, REGIONS, crop_region, get_embedder, to_card
from .evaluate import IMG_EXT, READINGS, _labels, _rot180, fit_pca, load_photo, project, ref_embeddings, search
from .labels import LabelResolver

log = logging.getLogger(__name__)


def art_rect() -> tuple[int, int, int, int]:
    """Artwork region in a CARD_W x CARD_H card, exactly as crop_region slices it (x, y, w, h)."""
    x0, y0, x1, y1 = REGIONS["art"]
    x, y = int(x0 * CARD_W), int(y0 * CARD_H)
    return x, y, int(x1 * CARD_W) - x, int(y1 * CARD_H) - y


# ---------------------------------------------------------------- ONNX model
def export_onnx(model, pca, path: Path) -> None:
    import torch
    import torch.nn.functional as F

    class AppModel(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.base = model.model
            self.register_buffer("mean", model.mean.clone())
            self.register_buffer("std", model.std.clone())
            self.register_buffer("pca_mu", torch.from_numpy(pca[0]))
            self.register_buffer("pca_w", torch.from_numpy(pca[1]))

        def forward(self, image):                       # (N, 224, 224, 3) RGB 0..255
            x = image.permute(0, 3, 1, 2) / 255.0
            x = (x - self.mean) / self.std
            f = F.normalize(self.base(x), dim=1)
            f = (f - self.pca_mu) @ self.pca_w
            return F.normalize(f, dim=1)

    m = AppModel().eval()
    dummy = torch.zeros(1, INPUT, INPUT, 3)
    kw = dict(input_names=["image"], output_names=["fingerprint"],
              dynamic_axes={"image": {0: "n"}, "fingerprint": {0: "n"}}, opset_version=17)
    with torch.inference_mode():
        try:
            torch.onnx.export(m, (dummy,), str(path), dynamo=False, **kw)
        except TypeError:   # older torch without the dynamo switch
            torch.onnx.export(m, (dummy,), str(path), **kw)


class OnnxFingerprints:
    """What the app does: region crop -> ONNX model."""

    def __init__(self, path: Path):
        import onnxruntime as ort
        self.sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])

    def __call__(self, cards: np.ndarray) -> np.ndarray:
        x = crop_region(cards, "art").astype(np.float32)
        return self.sess.run(["fingerprint"], {"image": x})[0]


# ---------------------------------------------------------------- catalog
def write_catalog(src_db: Path, dst_db: Path, entries, meta: dict) -> None:
    if dst_db.exists():
        dst_db.unlink()
    con = sqlite3.connect(dst_db)
    con.execute(f"ATTACH DATABASE '{src_db}' AS src")
    con.executescript("""
        CREATE TABLE entries(idx INTEGER PRIMARY KEY, card_id TEXT NOT NULL, lang TEXT NOT NULL);
        CREATE TABLE cards(id TEXT, lang TEXT, set_id TEXT, local_id TEXT, name TEXT, image_url TEXT,
                           rarity TEXT, PRIMARY KEY(id, lang));
        CREATE TABLE sets(id TEXT, lang TEXT, name TEXT, serie_id TEXT, serie_name TEXT, official INTEGER,
                          total INTEGER, release_date TEXT, PRIMARY KEY(id, lang));
        CREATE TABLE prices(id TEXT PRIMARY KEY, updated TEXT, trend REAL, avg REAL, low REAL, avg1 REAL,
                            avg7 REAL, avg30 REAL, trend_holo REAL, avg_holo REAL, low_holo REAL, avg30_holo REAL);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        INSERT INTO cards SELECT c.id, c.lang, c.set_id, c.local_id, c.name, c.image_url, d.rarity
            FROM src.cards c LEFT JOIN src.card_details d ON d.id = c.id;
        INSERT INTO sets(id, lang, name, serie_id, serie_name, official, total, release_date)
            SELECT id, lang, name, serie_id, serie_name, official, total, release_date FROM src.sets;
        INSERT INTO prices SELECT id, updated, trend, avg, low, avg1, avg7, avg30, trend_holo, avg_holo, low_holo,
            avg30_holo FROM src.prices_cardmarket;
        CREATE INDEX cards_id ON cards(id);
    """)
    con.executemany("INSERT INTO entries VALUES (?,?,?)", [(i, e.card_id, e.lang) for i, e in enumerate(entries)])
    con.executemany("INSERT INTO meta VALUES (?,?)", [(k, str(v)) for k, v in meta.items()])
    con.commit()
    con.execute("DETACH DATABASE src")
    con.execute("VACUUM")
    con.close()


# ---------------------------------------------------------------- parity fixtures
def app_recognise(img: np.ndarray, fp: OnnxFingerprints, index: np.ndarray, ids: list[str]) -> dict:
    """The app's recognition, step by step, in Python."""
    dets = detect_cards(img, max_cards=1)
    if not dets:
        return {"detected": False}
    quad = dets[0].quad
    card = to_card(rectify(img, quad))
    art_a, art_b = as_artwork(img, quad, (CARD_W, CARD_H), REGIONS["art"])
    readings = [fp(card[None]), fp(_rot180(card[None])), fp(art_a[None]), fp(art_b[None])]
    res = search(index, ids, readings)[0]
    return {"detected": True, "quad": quad.round(2).tolist(),
            "top": [{"id": c, "score": round(s, 5), "reading": READINGS[r]} for c, s, _, r in res[:5]],
            "margin": round(res[0][1] - (res[1][1] if len(res) > 1 else 0.0), 5)}


def synthetic_detection_photos(n: int, seed: int = 4242) -> list[tuple[str, np.ndarray]]:
    """Photos of invented cards (abstract art) for checking the app's detector."""
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tests"))
    from fake_tcgdex import fake_card
    out = []
    for i in range(n):
        r = np.random.default_rng([seed, i])
        photo, _, _ = synth_photo(fake_card(9000 + i, 9100 + i, 9200 + i), r, guided=bool(i % 2))
        out.append((f"synthetic_{i:02d}.png", photo))
    return out


# ---------------------------------------------------------------- main
def run(db: Path, cache_dir: Path, emb_cache: Path, model_name: str, out_dir: Path,
        real_dir: Path | None, results: Path | None, pca_dim: int = 256, n_synthetic: int = 8) -> None:
    t0 = time.time()
    out_dir = Path(out_dir)
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    entries = load_entries(db, cache_dir)
    ids = [e.card_id for e in entries]
    model = get_embedder(model_name)
    if model.region != "art" or list(model.views) != [""]:
        raise SystemExit("export supports single-view artwork models (e.g. mnv3_art)")
    ref = ref_embeddings(model, entries, emb_cache)
    pca = fit_pca(ref, pca_dim)
    index = project(ref, pca).astype(np.float16)
    (out_dir / "index.f16").write_bytes(index.astype("<f2").tobytes())
    log.info("index: %d x %d", *index.shape)

    export_onnx(model, pca, out_dir / "model.onnx")
    fp = OnnxFingerprints(out_dir / "model.onnx")
    # the exported model must reproduce the pipeline's fingerprints
    rng = np.random.default_rng(0)
    sample = [entries[int(i)] for i in rng.choice(len(entries), size=min(64, len(entries)), replace=False)]
    from .augment import flatten
    cards = np.stack([to_card(flatten(load_rgba(e.path))) for e in sample])
    want = project(model.embed(cards), pca)
    got = fp(cards)
    cos = (want * got).sum(1)
    log.info("ONNX vs pipeline fingerprints: min cosine %.6f", cos.min())
    if cos.min() < 0.999:
        raise SystemExit(f"exported model differs from the pipeline (min cosine {cos.min():.4f})")

    # confidence threshold from the latest accuracy test, for the compressed fingerprint
    thresholds = {}
    if results and Path(results).exists():
        summ = json.loads(Path(results).read_text())["summary"]["models"].get(f"{model_name}_pca{pca_dim}")
        if summ:
            for c in summ["confidence"]:
                if c["min_margin"] is not None:
                    thresholds[f"margin_{int(c['precision'] * 100)}"] = round(c["min_margin"], 4)
    ax, ay, aw, ah = art_rect()
    meta = {
        "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "model": model_name,
        "timm_name": model.timm_name, "dim": pca_dim, "entries": len(entries), "prep_version": PREP_VERSION,
        "card_w": CARD_W, "card_h": CARD_H, "rect_w": RECT_W, "rect_h": RECT_H,
        "art_x": ax, "art_y": ay, "art_w": aw, "art_h": ah, "input": INPUT,
        "readings": ",".join(READINGS), **thresholds,
    }
    write_catalog(db, out_dir / "catalog.sqlite", entries, meta)

    # parity fixtures
    index32 = index.astype(np.float32)
    fixtures = {"real": [], "synthetic": []}
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        if real_dir and Path(real_dir).is_dir():
            resolver = LabelResolver(entries)
            for fname, text in _labels(Path(real_dir)).items():
                p = Path(real_dir) / fname
                if not p.exists() or p.suffix.lower() not in IMG_EXT:
                    continue
                rec = app_recognise(load_photo(p), fp, index32, ids)
                rec.update(file=fname, truth=resolver.resolve(text).ids or None)
                fixtures["real"].append(rec)
                log.info("parity %s: %s", fname, rec.get("top", [{}])[0])
        for name, photo in synthetic_detection_photos(n_synthetic):
            ok, png = cv2.imencode(".png", cv2.cvtColor(photo, cv2.COLOR_RGB2BGR))
            z.writestr(f"photos/{name}", png.tobytes())
            d = detect_cards(photo, max_cards=1)
            fixtures["synthetic"].append({"file": name, "detected": bool(d),
                                          "quad": d[0].quad.round(2).tolist() if d else None})
        z.writestr("parity.json", json.dumps(fixtures, indent=1))
    (out_dir / "parity.zip").write_bytes(buf.getvalue())

    files = {}
    for f in sorted(out_dir.iterdir()):
        files[f.name] = {"bytes": f.stat().st_size, "sha256": hashlib.sha256(f.read_bytes()).hexdigest()}
    (out_dir / "pack.json").write_text(json.dumps({"version": meta["created"], "model": model_name, "dim": pca_dim,
                                                   "entries": len(entries), "files": files}, indent=1))
    log.info("app pack in %s (%.0fs): %s", out_dir, time.time() - t0,
             ", ".join(f"{k} {v['bytes'] / 1e6:.1f} MB" for k, v in files.items()))
