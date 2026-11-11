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
from .detect import RECT_H, RECT_W, as_artwork, detect_cards, detect_hypotheses, quad_iou, rectify
from .embedders import CARD_H, CARD_W, INPUT, PREP_VERSION, REGIONS, crop_region, get_embedder, to_card
from .evaluate import IMG_EXT, READINGS, _labels, _rot180, fit_pca, load_photo, project, ref_embeddings, search
from . import foil
from .groups import ASIAN as groups_mod_asian, artwork_groups
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
ENERGY_WORDS = {"energy", "energie", "énergie", "energia", "energía", "エネルギー", "能量", "에너지", "พลังงาน"}


def card_category(category) -> str | None:
    """TCGdex's category in any language -> 'Pokemon', 'Trainer' or 'Energy'."""
    c = str(category or "").strip()
    if not c:
        return None
    low = c.lower()
    if low in ENERGY_WORDS or "energ" in low:
        return "Energy"
    if low.startswith(("pok", "ポケモン", "寶可夢", "宝可梦")):
        return "Pokemon"
    return "Trainer" if low.startswith(("trainer", "dresseur", "entrenador", "allenatore", "treinador", "トレーナー", "訓練家", "训练家")) else c


def write_catalog(src_db: Path, dst_db: Path, entries, meta: dict, groups: list[str] | None = None) -> None:
    if dst_db.exists():
        dst_db.unlink()
    con = sqlite3.connect(dst_db)
    con.execute(f"ATTACH DATABASE '{src_db}' AS src")
    con.executescript("""
        CREATE TABLE entries(idx INTEGER PRIMARY KEY, card_id TEXT NOT NULL, lang TEXT NOT NULL, grp TEXT NOT NULL);
        CREATE TABLE cards(id TEXT, lang TEXT, set_id TEXT, local_id TEXT, name TEXT, image_url TEXT,
                           image_alt TEXT, image_alt_large TEXT, PRIMARY KEY(id, lang));
        CREATE TABLE card_info(id TEXT PRIMARY KEY, rarity TEXT, variants TEXT, category TEXT, dex TEXT);
        CREATE TABLE sets(id TEXT, lang TEXT, name TEXT, serie_id TEXT, serie_name TEXT, official INTEGER,
                          total INTEGER, release_date TEXT, PRIMARY KEY(id, lang));
        CREATE TABLE prices(id TEXT PRIMARY KEY, updated TEXT, trend REAL, avg REAL, low REAL, avg1 REAL,
                            avg7 REAL, avg30 REAL, trend_holo REAL, avg_holo REAL, low_holo REAL, avg30_holo REAL);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE tcgplayer(id TEXT PRIMARY KEY, updated TEXT, data TEXT);
        CREATE TABLE graded(id TEXT, printing TEXT, currency TEXT, updated TEXT, psa10 REAL, psa10_n INTEGER,
                            psa9 REAL, psa9_n INTEGER, psa8 REAL, psa8_n INTEGER, source TEXT, PRIMARY KEY(id, printing));
        INSERT INTO sets(id, lang, name, serie_id, serie_name, official, total, release_date)
            SELECT id, lang, name, serie_id, serie_name, official, total, release_date FROM src.sets;
        INSERT INTO prices SELECT id, updated, trend, avg, low, avg1, avg7, avg30, trend_holo, avg_holo, low_holo,
            avg30_holo FROM src.prices_cardmarket;
        CREATE INDEX cards_id ON cards(id);
        CREATE TABLE price_check(id TEXT PRIMARY KEY, status TEXT NOT NULL, cm_trend REAL, tp_lo REAL, tp_hi REAL, others TEXT);
    """)
    # the price check's verdicts (pricecheck.py): Cardmarket prices dropped, uncertain or far from TCGplayer
    if con.execute("SELECT 1 FROM src.sqlite_master WHERE name = 'price_check'").fetchone():
        con.execute("INSERT INTO price_check SELECT id, status, cm_trend, tp_lo, tp_hi, others FROM src.price_check")
    # pictures from the second source (altimages) for cards TCGdex has none for, if that step ran
    src_cols = {r[1] for r in con.execute("PRAGMA src.table_info(cards)")}
    alt = "c.image_alt, c.image_alt_large" if "image_alt" in src_cols else "NULL, NULL"
    con.execute(f"INSERT INTO cards SELECT c.id, c.lang, c.set_id, c.local_id, c.name, c.image_url, {alt} FROM src.cards c")
    # printings as compact flags; TCGplayer reduced to the market price per printing
    info_rows = []
    # Pokédex numbers (catalogs built before they were kept have none)
    has_dex = "dex" in {r[1] for r in con.execute("PRAGMA src.table_info(card_details)")}
    dex_of = dict(con.execute("SELECT id, dex FROM src.card_details").fetchall()) if has_dex else {}
    for cid, rarity, variants, category in con.execute("SELECT id, rarity, variants, category FROM src.card_details").fetchall():
        v = json.loads(variants) if variants else {}
        if isinstance(v, list):    # older catalogs stored the detailed list
            kinds = {x.get("type") for x in v if isinstance(x, dict)}
            v = {"normal": "normal" in kinds, "holo": "holo" in kinds, "reverse": "reverse" in kinds,
                 "firstEdition": any("1st" in str(x.get("subtype", "")) or "first" in str(x.get("subtype", "")).lower()
                                     for x in v if isinstance(x, dict))}
        flags = {k: True for k in ("normal", "holo", "reverse", "firstEdition") if v.get(k)}
        info_rows.append((cid, rarity, json.dumps(flags, separators=(",", ":")), card_category(category), dex_of.get(cid)))
    con.executemany("INSERT OR REPLACE INTO card_info VALUES (?,?,?,?,?)", info_rows)
    cats: dict[str, int] = {}
    raw: dict[str, int] = {}
    for r, (_, _, _, raw_cat) in zip(info_rows, con.execute("SELECT id, rarity, variants, category FROM src.card_details")):
        cats[str(r[3])] = cats.get(str(r[3]), 0) + 1
        if r[3] not in ("Pokemon", "Trainer", "Energy"):
            raw[str(raw_cat)] = raw.get(str(raw_cat), 0) + 1
    log.info("card categories: %s; unrecognised: %s", cats, dict(sorted(raw.items(), key=lambda t: -t[1])[:10]))
    tp_rows = []
    for cid, updated, data in con.execute("SELECT id, updated, data FROM src.prices_tcgplayer").fetchall():
        d = json.loads(data) if data else {}
        m = {}
        for k, v in d.items():
            if isinstance(v, dict):
                price = next((v[f] for f in ("marketPrice", "midPrice", "lowPrice") if isinstance(v.get(f), (int, float)) and v[f] > 0), None)
                if price is not None:
                    m[k] = round(float(price), 2)
        if m:
            tp_rows.append((cid, updated, json.dumps(m, separators=(",", ":"))))
    con.executemany("INSERT OR REPLACE INTO tcgplayer VALUES (?,?,?)", tp_rows)
    con.execute("""CREATE TABLE population(id TEXT, printing TEXT, updated TEXT, psa_total INTEGER, psa10 INTEGER,
                   psa9 INTEGER, psa8 INTEGER, gem_rate REAL, PRIMARY KEY(id, printing))""")
    if con.execute("SELECT 1 FROM src.sqlite_master WHERE name = 'population'").fetchone():
        con.execute("INSERT INTO population SELECT id, printing, updated, psa_total, psa10, psa9, psa8, gem_rate FROM src.population")
    if con.execute("SELECT 1 FROM src.sqlite_master WHERE name = 'graded'").fetchone():
        cols = [r[1] for r in con.execute("PRAGMA src.table_info(graded)")]
        if "printing" in cols:
            con.execute("INSERT INTO graded SELECT id, printing, currency, updated, psa10, psa10_n, psa9, psa9_n, "
                        "psa8, psa8_n, source FROM src.graded")
        else:   # older catalog: printing not known
            con.execute("INSERT INTO graded SELECT id, '', currency, updated, psa10, psa10_n, psa9, psa9_n, "
                        "psa8, psa8_n, source FROM src.graded")
    groups = groups or [e.card_id for e in entries]
    con.executemany("INSERT INTO entries VALUES (?,?,?,?)",
                    [(i, e.card_id, e.lang, g) for i, (e, g) in enumerate(zip(entries, groups))])
    con.execute("CREATE INDEX entries_grp ON entries(grp)")
    con.executemany("INSERT INTO meta VALUES (?,?)", [(k, str(v)) for k, v in meta.items()])
    con.commit()
    con.execute("DETACH DATABASE src")
    con.execute("VACUUM")
    con.close()


# ---------------------------------------------------------------- parity fixtures
HYPOTHESES = 2      # outlines read per photo at most (Recognizer.HYPOTHESES)


def app_recognise(img: np.ndarray, fp: OnnxFingerprints, index: np.ndarray, ids: list[str],
                  sure: float = 0.085) -> dict:
    """The app's recognition, step by step, in Python (Recognizer.recognise).
    ids: the artwork group of every index row (the app ranks artworks, then picks the version).
    The detector's outline is read first; if its match is not sure (margin < sure), the
    detector's second guess is read too and the better match (top score) wins."""
    dets = detect_hypotheses(img, k=HYPOTHESES)
    if not dets:
        return {"detected": False}
    best = None
    for hi, d in enumerate(dets):
        quad = d.quad
        card = to_card(rectify(img, quad))
        art_a, art_b = as_artwork(img, quad, (CARD_W, CARD_H), REGIONS["art"])
        readings = [fp(card[None]), fp(_rot180(card[None])), fp(art_a[None]), fp(art_b[None])]
        res = search(index, ids, readings)[0]
        margin = res[0][1] - (res[1][1] if len(res) > 1 else 0.0)
        if best is None or res[0][1] > best[1][0][1]:
            best = (quad, res, margin, hi)
        if margin >= sure:
            break
    quad, res, margin, hi = best
    return {"detected": True, "quad": quad.round(2).tolist(), "hypothesis": hi,
            "top": [{"id": c, "score": round(s, 5), "reading": READINGS[r], "entry": j} for c, s, j, r in res[:5]],
            "margin": round(margin, 5)}


def foil_lab(entries, tex: np.ndarray, real: list[dict], db: Path, out_md: Path, n_synthetic: int = 300) -> float:
    """How the single-photo foil check scores: synthetic photos of normal cards (made from
    the scans, so never foil) and your labelled real photos (`Name 12-100__reverse.jpg`).
    Returns the reverse-holo threshold for the app: above every normal card seen, with room."""
    rng = np.random.default_rng(77)
    con = sqlite3.connect(db)
    rev_ids = {cid for cid, v in con.execute("SELECT id, variants FROM card_details") if v and '"reverse": true' in v}
    con.close()
    pool = [i for i, e in enumerate(entries) if e.card_id in rev_ids] or list(range(len(entries)))
    synth = []
    for i in rng.choice(pool, size=min(n_synthetic, len(pool)), replace=False):
        e = entries[int(i)]
        try:
            photo, quad, _ = synth_photo(load_rgba(e.path), rng, guided=True)
        except Exception:
            continue
        d = detect_cards(photo, max_cards=1)
        # the app only checks a card it recognised, with that card's outline; a detection
        # of something else would not get here
        found = d[0].quad if d and quad_iou(d[0].quad, quad) >= 0.8 else None
        for name, q in (("detected", found), ("true outline", quad)):
            if q is None:
                continue
            g = foil.photo_grids(photo, np.asarray(q, np.float32), tex[int(i)])
            sc = foil.scores(g, tex[int(i)])[0]
            if np.isfinite(sc):
                synth.append((name, sc))
    det = np.array([sc for n, sc in synth if n == "detected"])
    scored = [r for r in real if r.get("foil") and r["foil"]["score"] is not None]
    real_normal = [r["foil"]["score"] for r in scored if r["foil"]["label"] == "normal"]
    real_rev = [r["foil"]["score"] for r in scored if r["foil"]["label"] == "reverse"]
    thr = max(foil.REVERSE_THRESHOLD, 1.25 * float(np.percentile(det, 99)) if len(det) else 0.0,
              (max(real_normal) + 0.05) if real_normal else 0.0)
    lines = ["## Foil check (single photo)", "",
             "Score = detail in the blank parts of the text area relative to the printed text next to it, "
             "minus the same for the card's scan (foil adds detail where the scan has none). "
             f"Normal cards here reach **{thr:.2f}**. The app shows the score under Details but does not pick "
             "the printing from it yet: that needs labelled photos of reverse holos and normal cards.", ""]
    if real_rev and min(real_rev) < thr:
        lines += [f"⚠️ {sum(1 for v in real_rev if v < thr)} of {len(real_rev)} labelled reverse holos score below that.", ""]
    lines += ["| synthetic normal cards (outline) | photos | median | 90% | 95% | 99% | above reverse threshold |", "|---|---|---|---|---|---|---|"]
    for name in ("detected", "true outline"):
        v = np.array([s for n, s in synth if n == name])
        if len(v):
            lines.append(f"| {name} | {len(v)} | {np.median(v):.2f} | {np.percentile(v, 90):.2f} | {np.percentile(v, 95):.2f} | "
                         f"{np.percentile(v, 99):.2f} | {np.mean(v >= foil.REVERSE_THRESHOLD) * 100:.1f}% |")
    lines += ["", "| your photo | label | score | detail blank | detail text | says |", "|---|---|---|---|---|---|"]
    for r in real:
        f = r.get("foil")
        if not f:
            continue
        if f["score"] is None:
            lines.append(f"| {r['file']} | {f['label'] or '?'} | – | {f['art']:.1f} | {f['body']:.1f} | no blank/text contrast |")
            continue
        says = "above normal cards" if f["score"] >= thr else "like normal cards"
        lines.append(f"| {r['file']} | {f['label'] or '?'} | {f['score']:.2f} | {f['art']:.1f} | {f['body']:.1f} | {says} |")
    out_md.write_text("\n".join(lines) + "\n")
    for l in lines:
        log.info("foil lab %s", l)
    return thr


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
        real_dir: Path | None, results: Path | None, pca_dim: int = 256, n_synthetic: int = 8,
        history_dir: Path | None = None) -> None:
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
        "foil_version": foil.VERSION,
    }
    write_catalog(db, out_dir / "catalog.sqlite", entries, meta, groups)
    # price history (weekly for a year, daily for 90 days), when the archive is there
    if history_dir is not None and Path(history_dir).exists():
        from .history import write_app_history
        hcon = sqlite3.connect(out_dir / "catalog.sqlite")
        try:
            write_app_history(hcon, Path(history_dir))
            hcon.commit()
        except Exception as ex:   # noqa: BLE001  the pack never fails for want of history
            log.warning("price history not exported: %s", ex)
        finally:
            hcon.close()
    tex = foil.reference_grids(entries, Path(emb_cache) / "foil_grids.npz")
    (out_dir / "texture.u8").write_bytes(tex.tobytes())
    log.info("foil texture grids: %d x %d", *tex.shape)

    # parity fixtures
    index32 = index.astype(np.float32)
    western_entry: dict[str, int] = {}          # card id -> its English (else first western) entry
    for i, e in enumerate(entries):
        if e.lang in groups_mod_asian:
            continue
        if e.card_id not in western_entry or e.lang == "en":
            western_entry[e.card_id] = i
    fixtures = {"real": [], "synthetic": []}
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        if real_dir and Path(real_dir).is_dir():
            resolver = LabelResolver(entries)
            for fname, text in _labels(Path(real_dir)).items():
                p = Path(real_dir) / fname
                if not p.exists() or p.suffix.lower() not in IMG_EXT:
                    continue
                img = load_photo(p)
                rec = app_recognise(img, fp, index32, groups, sure=thresholds.get("margin_99", 0.085))
                truth = resolver.resolve(text).ids or None
                rec.update(file=fname, truth=truth,
                           truth_groups=sorted({groups[i] for i, e in enumerate(entries) if truth and e.card_id in truth}) or None)
                if rec.get("detected"):
                    t = rec["top"][0]
                    rq = foil.card_quad_for_reading(np.array(rec["quad"], np.float32), READINGS.index(t["reading"]))
                    # compare with the scan of the version shown (western layout), not an Asian scan
                    ref_e = western_entry.get(t["id"], t["entry"])
                    g = foil.photo_grids(img, rq, tex[ref_e])
                    sc, art, body = foil.scores(g, tex[ref_e])
                    rec["foil"] = {"entry": ref_e, "card_quad": rq.round(2).tolist(), "grids": g.tolist(),
                                   "score": round(sc, 4) if np.isfinite(sc) else None,
                                   "art": round(art, 4), "body": round(body, 4), "label": foil.printing_label(fname)}
                fixtures["real"].append(rec)
                log.info("parity %s: %s foil %s", fname, rec.get("top", [{}])[0],
                         {k: v for k, v in rec.get("foil", {}).items() if k in ("score", "art", "body", "label")})
        for name, photo in synthetic_detection_photos(n_synthetic):
            ok, png = cv2.imencode(".png", cv2.cvtColor(photo, cv2.COLOR_RGB2BGR))
            z.writestr(f"photos/{name}", png.tobytes())
            d = detect_cards(photo, max_cards=1)
            fixtures["synthetic"].append({"file": name, "detected": bool(d),
                                          "quad": d[0].quad.round(2).tolist() if d else None})
        z.writestr("parity.json", json.dumps(fixtures, indent=1))
    (out_dir / "parity.zip").write_bytes(buf.getvalue())
    thr = foil_lab(entries, tex, fixtures["real"], db, out_dir.parent / "foil-lab.md")
    con = sqlite3.connect(out_dir / "catalog.sqlite")
    con.execute("INSERT OR REPLACE INTO meta VALUES ('foil_reverse', ?)", (str(round(thr, 4)),))
    con.commit()
    con.close()

    files = {}
    for f in sorted(out_dir.iterdir()):
        files[f.name] = {"bytes": f.stat().st_size, "sha256": hashlib.sha256(f.read_bytes()).hexdigest()}
    (out_dir / "pack.json").write_text(json.dumps({"version": meta["created"], "model": model_name, "dim": pca_dim,
                                                   "entries": len(entries), "files": files}, indent=1))
    log.info("app pack in %s (%.0fs): %s", out_dir, time.time() - t0,
             ", ".join(f"{k} {v['bytes'] / 1e6:.1f} MB" for k, v in files.items()))
