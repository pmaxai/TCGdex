"""Accuracy test: synthetic phone photos + your own real photos.

For every model:
  1. fingerprint all reference images (cached between runs)
  2. generate N synthetic photos of random cards, find the card with the
     detector ("full pipeline") and with the true corners ("perfect detection")
  3. match both against the index, trying the card upright and upside down
  4. score top-1 / top-5, classify mistakes, find confidence thresholds
Real photos in testdata/real/ are matched the same way.
"""
from __future__ import annotations

import csv
import json
import logging
import os
import shutil
import sqlite3
import time
import unicodedata
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps

from .augment import flatten, guide_prior, load_rgba, oracle_crop, synth_photo
from .catalog import Entry, load_entries
from .detect import RECT_H, RECT_W, as_artwork, detect_cards, draw_quad, quad_iou, rectify
from .embedders import CARD_H, CARD_W, PREP_VERSION, REGIONS, Embedder, get_embedder, timed_embed, to_card, view
from .labels import LabelResolver
from .report import has_tag, write_report

log = logging.getLogger(__name__)
IMG_EXT = {".jpg", ".jpeg", ".png", ".webp"}
WORKERS = max(1, os.cpu_count() or 1)


# =================================================================== references
def _entry_key(e: Entry) -> str:
    return f"{e.lang}/{e.card_id}/{e.path.stat().st_size}"


def _ref_input(e: Entry) -> np.ndarray:
    try:
        return to_card(flatten(load_rgba(e.path)))
    except Exception as ex:  # corrupt download: neutral image, never matches well
        log.warning("cannot read %s: %s", e.path, ex)
        return np.full((CARD_H, CARD_W, 3), 128, np.uint8)


def ref_embeddings(model: Embedder, entries: list[Entry], emb_cache: Path) -> np.ndarray:
    """Fingerprints of all reference images; only new/changed images are computed."""
    keys = [_entry_key(e) for e in entries]
    f = Path(emb_cache) / f"{model.name}_v{PREP_VERSION}_refs.npz"
    cached: dict[str, np.ndarray] = {}
    if f.exists():
        z = np.load(f, allow_pickle=False)
        if z["emb"].shape[1:] == (model.dim,):
            cached = dict(zip(z["keys"].tolist(), z["emb"]))
    missing = [i for i, k in enumerate(keys) if k not in cached]
    log.info("[%s] reference fingerprints: %d cached, %d to compute", model.name, len(keys) - len(missing), len(missing))
    if missing:
        t0 = time.time()
        with ThreadPoolExecutor(WORKERS) as ex:
            for s in range(0, len(missing), 512):
                idx = missing[s:s + 512]
                imgs = np.stack(list(ex.map(lambda i: _ref_input(entries[i]), idx)))
                for i, v in zip(idx, model.embed(imgs).astype(np.float16)):
                    cached[keys[i]] = v
                done = s + len(idx)
                rate = done / max(1e-6, time.time() - t0)
                log.info("[%s] %d/%d (%.0f img/s, ~%.0f min left)", model.name, done, len(missing), rate,
                         (len(missing) - done) / rate / 60)
        f.parent.mkdir(parents=True, exist_ok=True)
        np.savez(f, keys=np.array(keys), emb=np.stack([cached[k] for k in keys]))
    return np.stack([cached[k] for k in keys]).astype(np.float32)


# =================================================================== search
READINGS = ("card", "card upside down", "artwork", "artwork upside down")


def search(ref: np.ndarray, card_ids: list[str], qs: list[np.ndarray],
           k: int = 5, pool: int = 40) -> list[list[tuple[str, float, int, int]]]:
    """Top-(k+1) distinct cards per query as (card_id, score, entry index, reading).

    qs holds one fingerprint array per reading of the photo (card upright,
    card upside down, and optionally the two artwork-box readings); for every
    reference the best reading counts. Each card can have several reference
    images (languages); the best one counts.
    """
    out = []
    n = ref.shape[0]
    pool = min(pool, n - 1)
    for s in range(0, len(qs[0]), 128):
        stack = np.stack([q[s:s + 128] @ ref.T for q in qs])
        which = stack.argmax(0)
        sim = stack.max(0)
        top = np.argpartition(-sim, pool, axis=1)[:, :pool + 1] if pool > 0 else np.zeros((len(sim), 1), int)
        for r in range(len(sim)):
            cand = top[r][np.argsort(-sim[r, top[r]])]
            seen, res = set(), []
            for j in cand:
                cid = card_ids[j]
                if cid in seen:
                    continue
                seen.add(cid)
                res.append((cid, float(sim[r, j]), int(j), int(which[r, j])))
                if len(res) > k:
                    break
            out.append(res)
    return out


def _rot180(imgs: np.ndarray) -> np.ndarray:
    return np.ascontiguousarray(imgs[:, ::-1, ::-1])


# =================================================================== synthetic queries
@dataclass
class Query:
    qi: int
    entry: int
    tags: list[str]
    card_frac: float
    det_found: bool
    det_iou: float
    det_crop: np.ndarray | None
    det_art: tuple | None            # artwork-box readings of the same quad
    oracle: np.ndarray


def _generate(entries: list[Entry], qi: int, ei: int, seed: int):
    """Deterministic synthetic photo for query qi (re-run to recreate debug images)."""
    r = np.random.default_rng([seed, qi])
    card = load_rgba(entries[ei].path)
    distract = []
    if r.random() < 0.35:
        for _ in range(int(r.integers(1, 3))):
            j = int(r.integers(len(entries)))
            if entries[j].card_id != entries[ei].card_id:
                distract.append(load_rgba(entries[j].path))
    guided = qi % 2 == 1          # half the photos: card lined up with the app's guide frame
    photo, quad, meta = synth_photo(card, r, distract, guided=guided)
    dets = detect_cards(photo, max_cards=1, prior=guide_prior(photo.shape) if guided else None)
    if guided:
        meta.tags.append("guided")
    return r, photo, quad, meta, dets


def make_queries(entries: list[Entry], n: int, seed: int, debug_dir: Path | None, n_examples: int = 12) -> list[Query]:
    rng = np.random.default_rng(seed)
    picks = rng.choice(len(entries), size=min(n, len(entries)), replace=False)

    def one(args):
        qi, ei = args
        r, photo, quad, meta, dets = _generate(entries, qi, int(ei), seed)
        det_crop, det_art, iou = None, None, 0.0
        used = None
        if dets:
            iou = quad_iou(dets[0].quad, quad)
            used = dets[0].quad
        elif "guided" in meta.tags:   # the app matches what is inside its guide frame
            used = guide_quad(photo)
        if used is not None:
            det_crop = to_card(rectify(photo, used))
            det_art = as_artwork(photo, used, (CARD_W, CARD_H), REGIONS["art"])
        orc = to_card(oracle_crop(photo, quad, r))
        if debug_dir is not None and qi < n_examples:
            vis = draw_quad(photo, quad, (0, 120, 255), 3)
            if dets:
                vis = draw_quad(vis, dets[0].quad, (0, 255, 0), 3)
            _save(debug_dir / "synthetic_examples" / f"{qi:03d}_{entries[ei].card_id}.jpg", vis)
        return Query(qi, int(ei), meta.tags, meta.card_frac, bool(dets), iou, det_crop, det_art, orc)

    t0 = time.time()
    with ThreadPoolExecutor(WORKERS) as ex:
        qs = list(ex.map(one, enumerate(picks)))
    log.info("generated %d synthetic photos in %.0fs", len(qs), time.time() - t0)
    return qs


# =================================================================== scoring
def _classify(truth: Entry, pred_entry: Entry, top_ids: list[str], phash_ref: np.ndarray, ti: int, pj: int,
              names: dict[tuple[str, str], str]) -> str:
    if float(phash_ref[ti] @ phash_ref[pj]) >= 0.8:
        return "near_identical_reprint"
    truth_name = names.get((truth.card_id, pred_entry.lang), truth.name)
    if pred_entry.name.strip().lower() == truth_name.strip().lower():
        return "same_name_other_art"
    return "in_top5" if truth.card_id in top_ids else "different_card"


def embed_queries(model: Embedder, queries: list[Query]) -> tuple[dict, float]:
    """Fingerprints of every reading of every query, per mode.

    perfect detection: the card upright and upside down
    full pipeline:     the same, plus the two artwork-box readings of the detected quad
    """
    out: dict[str, tuple] = {}
    ms = 0.0
    for mode in ("detector", "oracle"):
        sel = [i for i, q in enumerate(queries) if mode == "oracle" or q.det_crop is not None]
        if sel:
            imgs = np.stack([queries[i].oracle if mode == "oracle" else queries[i].det_crop for i in sel])
            e0, t = timed_embed(model, imgs)
            if mode == "detector":
                ms = t
            readings = [e0, model.embed(_rot180(imgs))]
            if mode == "detector":
                readings.append(model.embed(np.stack([queries[i].det_art[0] for i in sel])))
                readings.append(model.embed(np.stack([queries[i].det_art[1] for i in sel])))
        else:
            readings = [np.zeros((0, model.dim), np.float32)] * 2
        out[mode] = (sel, readings)
    return out, ms


PCA_DIMS = (256, 512)   # compressed fingerprint sizes tested for the phone app


def fit_pca(x: np.ndarray, k: int) -> tuple[np.ndarray, np.ndarray]:
    """Mean and projection matrix (D, k) of the top-k principal components."""
    mu = x.mean(0)
    _, _, vt = np.linalg.svd(x - mu, full_matrices=False)
    return mu.astype(np.float32), vt[:k].T.astype(np.float32)


def project(x: np.ndarray, pca) -> np.ndarray:
    if pca is None:
        return x
    mu, w = pca
    y = (x - mu) @ w
    return y / np.maximum(np.linalg.norm(y, axis=1, keepdims=True), 1e-8)


def score_view(ref_t: np.ndarray, qemb: dict, tf, entries: list[Entry], queries: list[Query],
               phash_ref: np.ndarray, names: dict) -> dict:
    """Score one fingerprint variant. tf turns raw model output into this variant."""
    ids = [e.card_id for e in entries]
    results: dict[str, list[dict]] = {}
    for mode, (sel, readings) in qemb.items():
        found = search(ref_t, ids, [tf(e) for e in readings]) if sel else []
        by_q = dict(zip(sel, found))
        rows: list[dict] = []
        for i, q in enumerate(queries):
            truth = entries[q.entry]
            row = {"q": i, "truth": truth.card_id, "lang": truth.lang, "serie": truth.serie_id, "tags": q.tags}
            res = by_q.get(i)
            if not res:
                row.update(pred=None, score=0.0, margin=0.0, top1=False, top5=False, err="no_detection")
            else:
                top_ids = [c for c, _, _, _ in res[:5]]
                pred, score, pj, reading = res[0]
                margin = score - (res[1][1] if len(res) > 1 else 0.0)
                ok = pred == truth.card_id
                row.update(pred=pred, pred_entry=pj, reading=READINGS[reading], score=round(score, 4),
                           margin=round(margin, 4), top1=ok,
                           top5=truth.card_id in top_ids,
                           err=None if ok else _classify(truth, entries[pj], top_ids, phash_ref, q.entry, pj, names))
            rows.append(row)
        results[mode] = rows
    return results


def detection_stats(queries: list[Query]) -> dict:
    """How well the detector outlines the card (overlap with the true outline)."""
    iou = np.array([q.det_iou if q.det_found else -1.0 for q in queries])
    buckets = [("nothing found", iou < 0), ("another card/object (overlap < 0.6)", (iou >= 0) & (iou < 0.6)),
               ("rough outline (0.6-0.8)", (iou >= 0.6) & (iou < 0.8)),
               ("slightly off, e.g. inner panel only (0.8-0.9)", (iou >= 0.8) & (iou < 0.9)),
               ("good (0.9-0.95)", (iou >= 0.9) & (iou < 0.95)), ("precise (>= 0.95)", iou >= 0.95)]
    by_tag = {}
    for tag in ("guided", "free", "clean", "glare", "blur", "sleeve", "fingers", "shadow", "rot180", "sideways",
                "other_cards"):
        sel = np.array([has_tag(q.tags, tag) for q in queries])
        if sel.any():
            by_tag[tag] = {"n": int(sel.sum()), "right": float((iou[sel] >= 0.6).mean()),
                           "good": float((iou[sel] >= 0.9).mean())}
    return {"found": float((iou >= 0).mean()), "found_right_card": float((iou >= 0.6).mean()),
            "good_outline": float((iou >= 0.9).mean()),
            "overlap": [(name, float(m.mean())) for name, m in buckets], "by_tag": by_tag}


def confidence_table(rows: list[dict]) -> list[dict]:
    """How many photos can be answered at a given accuracy if we only trust large margins."""
    m = np.array([r["margin"] for r in rows], np.float32)
    c = np.array([r["top1"] for r in rows], bool)
    order = np.argsort(-m, kind="stable")
    prec = np.cumsum(c[order]) / np.arange(1, len(c) + 1)
    out = []
    for target in (0.99, 0.98, 0.95, 0.90):
        ok = np.where(prec >= target)[0]
        if len(ok):
            k = int(ok.max())
            out.append({"precision": target, "min_margin": float(m[order][k]), "coverage": (k + 1) / len(c)})
        else:
            out.append({"precision": target, "min_margin": None, "coverage": 0.0})
    return out


# =================================================================== real photos
def _labels(real_dir: Path) -> dict[str, str | None]:
    lab: dict[str, str | None] = {}
    csv_path = real_dir / "labels.csv"
    if csv_path.exists():
        with csv_path.open(newline="", encoding="utf-8") as fh:
            for row in csv.reader(fh):
                if len(row) >= 2 and row[0].strip() and not row[0].startswith("#") and row[0] != "file":
                    lab[row[0].strip()] = row[1].strip() or None
    for p in sorted(real_dir.iterdir()):
        if p.suffix.lower() in IMG_EXT and p.name not in lab:
            text = p.stem.split("__")[0].strip()
            lab[p.name] = None if text.lower() in ("", "unknown", "x") else text
    return lab


def load_photo(path: Path) -> np.ndarray:
    with Image.open(path) as im:
        return np.asarray(ImageOps.exif_transpose(im).convert("RGB"))


def guide_quad(img: np.ndarray) -> np.ndarray:
    """The app's on-screen guide frame as a quad (used when no card is found)."""
    h, w = img.shape[:2]
    cx, cy, gh = guide_prior(img.shape)
    ch = gh * h
    cw = ch * 63 / 88
    if cw > 0.9 * w:
        cw = 0.9 * w; ch = cw * 88 / 63
    x0, y0 = cx * w - cw / 2, cy * h - ch / 2
    return np.array([[x0, y0], [x0 + cw, y0], [x0 + cw, y0 + ch], [x0, y0 + ch]], np.float32)


# =================================================================== debug images
def _save(path: Path, rgb: np.ndarray, max_h: int = 700) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if rgb.shape[0] > max_h:
        rgb = cv2.resize(rgb, (int(rgb.shape[1] * max_h / rgb.shape[0]), max_h), interpolation=cv2.INTER_AREA)
    cv2.imwrite(str(path), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])


def _panel(rgb: np.ndarray, label: str, h: int = 420) -> np.ndarray:
    label = unicodedata.normalize("NFKD", label).encode("ascii", "replace").decode()
    im = cv2.resize(rgb, (max(1, int(rgb.shape[1] * h / rgb.shape[0])), h), interpolation=cv2.INTER_AREA)
    bar = np.full((44, im.shape[1], 3), 255, np.uint8)
    for k, line in enumerate([label[i:i + 34] for i in range(0, min(len(label), 68), 34)]):
        cv2.putText(bar, line, (4, 18 + 20 * k), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 1, cv2.LINE_AA)
    return np.vstack([bar, im])


def _strip(panels: list[np.ndarray]) -> np.ndarray:
    gap = np.full((panels[0].shape[0], 8, 3), 255, np.uint8)
    out = [panels[0]]
    for p in panels[1:]:
        out += [gap, p]
    return np.hstack(out)


# =================================================================== main
def run(db: Path, cache_dir: Path, emb_cache: Path, models: list[str], n_queries: int, real_dir: Path | None,
        out_dir: Path, seed: int = 1234, n_debug_failures: int = 24) -> dict:
    t_start = time.time()
    out_dir = Path(out_dir)
    debug_dir = out_dir / "debug"
    if debug_dir.exists():
        shutil.rmtree(debug_dir)
    entries = load_entries(db, cache_dir)
    if len(entries) < 2:
        raise SystemExit("catalog has fewer than 2 cards with images - run `fetch` first")
    ids = [e.card_id for e in entries]
    names = {(e.card_id, e.lang): e.name for e in entries}
    by_lang: dict[str, int] = defaultdict(int)
    for e in entries:
        by_lang[e.lang] += 1
    log.info("%d reference images (%s), %d distinct cards", len(entries), dict(by_lang), len(set(ids)))

    # pHash fingerprints are always computed: cheap, and used to spot identical-art reprints
    phash = get_embedder("phash")
    phash_ref = ref_embeddings(phash, entries, emb_cache)

    queries = make_queries(entries, n_queries, seed, debug_dir)

    real = []
    if real_dir and Path(real_dir).is_dir():
        resolver = LabelResolver(entries)
        for fname, text in _labels(Path(real_dir)).items():
            p = Path(real_dir) / fname
            if not p.exists():
                log.warning("labels.csv lists missing file %s", fname)
                continue
            img = load_photo(p)
            dets = detect_cards(img, max_cards=1)
            quad = dets[0].quad if dets else guide_quad(img)
            crop = rectify(img, quad)
            art = as_artwork(img, quad, (CARD_W, CARD_H), REGIONS["art"])
            lab = resolver.resolve(text)
            if text and lab.problem:
                log.warning("label of %s (%r): %s", fname, text, lab.problem)
            real.append({"file": fname, "label": text, "truth": lab.ids or None, "label_problem": lab.problem if text else None,
                         "detected": bool(dets), "img": img, "quad": quad,
                         "crop": crop, "input": to_card(crop), "art": art, "preds": {}})
        log.info("%d real photos", len(real))

    summary = {"models": {}, "n_queries": len(queries), "n_refs": len(entries), "n_cards": len(set(ids)),
               "refs_by_lang": dict(by_lang),
               "detection": detection_stats(queries)}
    per_query: dict[str, dict] = {}
    pack_dir = out_dir / "pack"
    pack_dir.mkdir(parents=True, exist_ok=True)
    with (pack_dir / "entries.tsv").open("w", encoding="utf-8") as fh:
        for e in entries:
            fh.write(f"{e.card_id}\t{e.lang}\n")

    for name in models:
        model = phash if name == "phash" else get_embedder(name)
        ref = phash_ref if name == "phash" else ref_embeddings(model, entries, emb_cache)
        qemb, ms = embed_queries(model, queries)
        if real:
            x = np.stack([r["input"] for r in real])
            real_readings = [model.embed(x), model.embed(_rot180(x)),
                             model.embed(np.stack([r["art"][0] for r in real])),
                             model.embed(np.stack([r["art"][1] for r in real]))]
        variants = []
        for vname, sl in model.views.items():
            base = f"{name}_{vname}" if vname else name
            variants.append((base, sl, None))
            for k in PCA_DIMS:
                if name != "phash" and view(ref[:1], sl).shape[1] > k:
                    variants.append((f"{base}_pca{k}", sl, fit_pca(view(ref, sl), k)))
        for label, sl, pca in variants:
            tf = (lambda e, sl=sl, pca=pca: project(view(e, sl), pca))
            ref_t = tf(ref)
            res = score_view(ref_t, qemb, tf, entries, queries, phash_ref, names)
            per_query[label] = res
            s = {"ms_per_img": ms, "dim": int(ref_t.shape[1])}
            for mode, rows in res.items():
                s[mode] = {"top1": float(np.mean([r["top1"] for r in rows])),
                           "top5": float(np.mean([r["top5"] for r in rows])),
                           "errors": _count([r["err"] for r in rows if r["err"]])}
            s["confidence"] = confidence_table(res["detector"])
            art = [r for r in res["detector"] if r.get("reading", "").startswith("artwork")]
            s["artwork_reading"] = {"share": len(art) / max(1, len(res["detector"])),
                                    "right": float(np.mean([r["top1"] for r in art])) if art else None}
            summary["models"][label] = s
            log.info("[%s] top-1 full pipeline %.1f%%, perfect detection %.1f%%", label,
                     100 * s["detector"]["top1"], 100 * s["oracle"]["top1"])
            if pca is None:
                np.save(pack_dir / f"{label}_embeddings.f16.npy", ref_t.astype(np.float16))
            if real:
                found = search(ref_t, ids, [tf(e) for e in real_readings])
                for r, f in zip(real, found):
                    r["preds"][label] = [{"id": c, "name": entries[j].name, "lang": entries[j].lang,
                                          "score": round(sc, 4), "entry": j, "reading": READINGS[rd]}
                                         for c, sc, j, rd in f[:5]]
        del model

    # ---------------------------------------------------------- debug images
    best = max(summary["models"], key=lambda m: summary["models"][m]["detector"]["top1"])
    summary["best_model"] = best
    fails = [r for r in per_query[best]["detector"] if not r["top1"]][:n_debug_failures]
    for r in fails:
        q = queries[r["q"]]
        _, photo, quad, _, dets = _generate(entries, q.qi, q.entry, seed)
        vis = draw_quad(photo, quad, (0, 120, 255), 3)
        if dets:
            vis = draw_quad(vis, dets[0].quad, (0, 255, 0), 3)
        panels = [_panel(vis, f"photo ({', '.join(q.tags) or 'clean'})")]
        if q.det_crop is not None:
            panels.append(_panel(q.det_crop, "what the detector cut out"))
        if r["pred"] is not None:
            pe = entries[r["pred_entry"]]
            panels.append(_panel(flatten(load_rgba(pe.path)), f"matched: {pe.name} {pe.card_id}"))
        te = entries[q.entry]
        panels.append(_panel(flatten(load_rgba(te.path)), f"truth: {te.name} {te.card_id}"))
        _save(debug_dir / "failures" / f"{r['q']:04d}_{r['err']}.jpg", _strip(panels), max_h=480)

    real_rows = []
    for r in real:
        row = {"file": r["file"], "label": r["label"], "truth": r["truth"], "label_problem": r["label_problem"],
               "detected": r["detected"], "preds": {}}
        panels = [_panel(draw_quad(r["img"], r["quad"], (0, 255, 0), max(3, r["img"].shape[0] // 200)),
                         f"{r['file']} ({'card found' if r['detected'] else 'NO card found - centre crop'})"),
                  _panel(r["crop"], "straightened")]
        for name, preds in r["preds"].items():
            top = preds[0]
            row["preds"][name] = {"top5": preds, "top1": top["id"] in r["truth"] if r["truth"] else None,
                                  "in_top5": any(p["id"] in r["truth"] for p in preds) if r["truth"] else None}
            if name == best:
                panels.append(_panel(flatten(load_rgba(entries[top["entry"]].path)),
                                     f"{name}: {top['name']} {top['id']} ({top['score']:.2f})"))
        real_rows.append(row)
        _save(debug_dir / "real" / f"{Path(r['file']).stem}.jpg", _strip(panels), max_h=480)
    summary["real"] = real_rows
    summary["seconds"] = round(time.time() - t_start)

    con = sqlite3.connect(db)
    summary["catalog_meta"] = dict(con.execute("SELECT key, value FROM meta").fetchall())
    summary["prices"] = {
        "cardmarket": con.execute("SELECT COUNT(*) FROM prices_cardmarket WHERE trend IS NOT NULL OR avg IS NOT NULL").fetchone()[0],
        "tcgplayer": con.execute("SELECT COUNT(*) FROM prices_tcgplayer").fetchone()[0],
        "cards": len(set(ids)),
    }
    con.close()
    shutil.copy(db, pack_dir / "catalog.sqlite")

    lite = {m: {mode: [{k: v for k, v in r.items() if k != "pred_entry"} for r in rows] for mode, rows in res.items()}
            for m, res in per_query.items()}
    (out_dir / "results.json").write_text(json.dumps({"summary": summary, "queries": lite}, indent=1, ensure_ascii=False))
    write_report(out_dir / "report.md", summary, per_query, entries)
    log.info("done in %.0fs - report at %s", time.time() - t_start, out_dir / "report.md")
    return summary


def _count(items: list[str]) -> dict[str, int]:
    d: dict[str, int] = defaultdict(int)
    for i in items:
        d[i] += 1
    return dict(sorted(d.items(), key=lambda t: -t[1]))
