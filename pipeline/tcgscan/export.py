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
def write_catalog(src_db: Path, dst_db: Path, entries, meta: dict, groups: list[str] | None = None) -> None:
    if dst_db.exists():
        dst_db.unlink()
    con = sqlite3.connect(dst_db)
    con.execute(f"ATTACH DATABASE '{src_db}' AS src")
    con.executescript("""
        CREATE TABLE entries(idx INTEGER PRIMARY KEY, card_id TEXT NOT NULL, lang TEXT NOT NULL, grp TEXT NOT NULL);
        CREATE TABLE cards(id TEXT, lang TEXT, set_id TEXT, local_id TEXT, name TEXT, image_url TEXT,
                           rarity TEXT, variants TEXT, PRIMARY KEY(id, lang));
        CREATE TABLE sets(id TEXT, lang TEXT, name TEXT, serie_id TEXT, serie_name TEXT, official INTEGER,
                          total INTEGER, release_date TEXT, PRIMARY KEY(id, lang));
        CREATE TABLE prices(id TEXT PRIMARY KEY, updated TEXT, trend REAL, avg REAL, low REAL, avg1 REAL,
                            avg7 REAL, avg30 REAL, trend_holo REAL, avg_holo REAL, low_holo REAL, avg30_holo REAL);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        INSERT INTO cards SELECT c.id, c.lang, c.set_id, c.local_id, c.name, c.image_url, d.rarity, d.variants
            FROM src.cards c LEFT JOIN src.card_details d ON d.id = c.id;
        CREATE TABLE tcgplayer(id TEXT PRIMARY KEY, updated TEXT, data TEXT);
        INSERT INTO tcgplayer SELECT id, updated, data FROM src.prices_tcgplayer;
        CREATE TABLE graded(id TEXT PRIMARY KEY, currency TEXT, updated TEXT, psa10 REAL, psa10_n INTEGER,
                            psa9 REAL, psa9_n INTEGER, psa8 REAL, psa8_n INTEGER, source TEXT);
        INSERT INTO sets(id, lang, name, serie_id, serie_name, official, total, release_date)
            SELECT id, lang, name, serie_id, serie_name, official, total, release_date FROM src.sets;
        INSERT INTO prices SELECT id, updated, trend, avg, low, avg1, avg7, avg30, trend_holo, avg_holo, low_holo,
            avg30_holo FROM src.prices_cardmarket;
        CREATE INDEX cards_id ON cards(id);
    """)
    if con.execute("SELECT 1 FROM src.sqlite_master WHERE name = 'graded'").fetchone():
        con.execute("INSERT INTO graded SELECT * FROM src.graded")
    groups = groups or [e.card_id for e in entries]
    con.executemany("INSERT INTO entries VALUES (?,?,?,?)",
                    [(i, e.card_id, e.lang, g) for i, (e, g) in enumerate(zip(entries, groups))])
    con.execute("CREATE INDEX entries_grp ON entries(grp)")
    con.executemany("INSERT INTO meta VALUES (?,?)", [(k, str(v)) for k, v in meta.items()])
    con.commit()
    con.execute("DETACH DATABASE src")
    con.execute("VACUUM")
    con.close()


# ---------------------------------------------------------------- artwork groups
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


# ---------------------------------------------------------------- parity fixtures
def app_recognise(img: np.ndarray, fp: OnnxFingerprints, index: np.ndarray, ids: list[str]) -> dict:
    """ids: the artwork group of every index row (the app ranks artworks, then picks the version)."""
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
    groups, group_stats = artwork_groups(entries, index.astype(np.float32))
    ax, ay, aw, ah = art_rect()
    meta = {
        "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "model": model_name,
        "timm_name": model.timm_name, "dim": pca_dim, "entries": len(entries), "prep_version": PREP_VERSION,
        "card_w": CARD_W, "card_h": CARD_H, "rect_w": RECT_W, "rect_h": RECT_H,
        "art_x": ax, "art_y": ay, "art_w": aw, "art_h": ah, "input": INPUT,
        "art_box": ",".join(str(v) for v in REGIONS["art"]),
        "readings": ",".join(READINGS), **thresholds,
        "langs": ",".join(sorted({r[0] for r in sqlite3.connect(db).execute("SELECT DISTINCT lang FROM cards")})),
        "group_threshold": group_stats["threshold"],
    }
    write_catalog(db, out_dir / "catalog.sqlite", entries, meta, groups)

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
                rec = app_recognise(load_photo(p), fp, index32, groups)
                truth = resolver.resolve(text).ids or None
                rec.update(file=fname, truth=truth,
                           truth_groups=sorted({groups[i] for i, e in enumerate(entries) if truth and e.card_id in truth}) or None)
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
