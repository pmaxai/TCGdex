"""Markdown report (also shown on the GitHub Actions run page)."""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

ERR_TEXT = {
    "no_detection": "no card found in the photo",
    "near_identical_reprint": "picked a reprint with (nearly) the same image - card number OCR fixes this",
    "same_name_other_art": "right Pokémon/name, different artwork or set",
    "in_top5": "wrong card, but the right one was in the top 5",
    "different_card": "a completely different card",
}
TAG_TEXT = {
    "clean": "no glare/blur/sleeve/shadow", "glare": "glare", "blur": "blur", "sleeve": "in a sleeve",
    "shadow": "shadow", "rot180": "upside down", "sideways": "sideways", "other_cards": "other cards in view",
}


def _pct(x: float | None) -> str:
    return "–" if x is None else f"{100 * x:.1f}%"


def _acc(rows: list[dict]) -> float | None:
    return sum(r["top1"] for r in rows) / len(rows) if rows else None


def write_report(path: Path, s: dict, per_query: dict, entries) -> None:
    models = list(s["models"])
    best = s["best_model"]
    meta = s.get("catalog_meta", {})
    L: list[str] = []
    w = L.append

    w("# Card scanner accuracy report\n")
    w(f"Catalog from {meta.get('source', 'TCGdex')}, created {meta.get('created', '?')}. "
      f"Languages: {meta.get('langs', '?')}" + (f" (quick run: {meta.get('max_sets')} sets)" if meta.get("max_sets") not in (None, "0") else "") + ".  ")
    w(f"**{s['n_cards']:,} cards**, {s['n_refs']:,} reference images "
      f"({', '.join(f'{k}: {v:,}' for k, v in s['refs_by_lang'].items())}). "
      f"Cardmarket prices for {s['prices']['cardmarket']:,} cards, TCGplayer for {s['prices']['tcgplayer']:,}.  ")
    w(f"Test: {s['n_queries']:,} synthetic phone photos" + (f" + {len(s['real'])} real photos" if s["real"] else "")
      + f". Runtime {s['seconds'] // 60} min.\n")

    w("## Synthetic photos\n")
    w("*Full pipeline* = card found by the detector, then matched. *Perfect detection* = matched using the true "
      "corners, so it measures the fingerprint model alone. Speed is on the GitHub machine's CPU, not a phone.\n")
    w("| model | top-1 full pipeline | top-5 | top-1 perfect detection | top-5 | ms / image | sure answers at ≥99% accuracy |")
    w("|---|---|---|---|---|---|---|")
    for m in models:
        d = s["models"][m]
        c99 = next((c for c in d["confidence"] if c["precision"] == 0.99), None)
        sure = f"{_pct(c99['coverage'])} of photos (margin ≥ {c99['min_margin']:.3f})" if c99 and c99["min_margin"] is not None else "–"
        star = " ⭐" if m == best else ""
        w(f"| **{m}**{star} | {_pct(d['detector']['top1'])} | {_pct(d['detector']['top5'])} | "
          f"{_pct(d['oracle']['top1'])} | {_pct(d['oracle']['top5'])} | {d['ms_per_img']:.1f} | {sure} |")
    det = s["detection"]
    w(f"\n### Card detection\n")
    w(f"Found the right card in **{_pct(det['found_right_card'])}** of photos, with a good outline "
      f"(overlap ≥ 0.9) in **{_pct(det.get('good_outline'))}**.\n")
    if det.get("overlap"):
        w("| outline vs. the true card | share of photos |")
        w("|---|---|")
        for name, frac in det["overlap"]:
            w(f"| {name} | {_pct(frac)} |")
        w("")
    if det.get("by_tag"):
        w("| condition | photos | right card found | good outline |")
        w("|---|---|---|---|")
        for tag, d in det["by_tag"].items():
            w(f"| {TAG_TEXT.get(tag, tag)} | {d['n']} | {_pct(d['right'])} | {_pct(d['good'])} |")
        w("")

    w(f"### What goes wrong ({best}, full pipeline)\n")
    errs = s["models"][best]["detector"]["errors"]
    n = s["n_queries"]
    w("| mistake | share of all photos |")
    w("|---|---|")
    for k, v in errs.items():
        w(f"| {ERR_TEXT.get(k, k)} | {_pct(v / n)} ({v}) |")

    w("\n### Confidence threshold (full pipeline)\n")
    w("If the app only shows a result when the score gap between the best and second-best card (the *margin*) is "
      "large enough, how many photos get an answer?\n")
    w("| model | " + " | ".join(f"≥{int(p * 100)}% accurate" for p in (0.99, 0.98, 0.95, 0.90)) + " |")
    w("|---|---|---|---|---|")
    for m in models:
        cells = [f"{_pct(c['coverage'])} answered" + (f" (margin ≥ {c['min_margin']:.3f})" if c["min_margin"] is not None else "")
                 for c in s["models"][m]["confidence"]]
        w(f"| {m} | " + " | ".join(cells) + " |")

    w("\n### Accuracy by condition (full pipeline, top-1)\n")
    w("| condition | photos | " + " | ".join(models) + " |")
    w("|---|---|" + "---|" * len(models))
    first = per_query[models[0]]["detector"]
    for tag in TAG_TEXT:
        sel = [i for i, r in enumerate(first) if (not r["tags"] if tag == "clean" else tag in r["tags"])]
        if not sel:
            continue
        cells = [_pct(_acc([per_query[m]["detector"][i] for i in sel])) for m in models]
        w(f"| {TAG_TEXT[tag]} | {len(sel)} | " + " | ".join(cells) + " |")

    w("\n### By language and era (perfect detection, top-1)\n")
    groups: dict[str, list[int]] = defaultdict(list)
    for i, r in enumerate(per_query[models[0]]["oracle"]):
        groups[f"lang: {r['lang']}"].append(i)
    serie_rows: dict[str, list[int]] = defaultdict(list)
    for i, r in enumerate(per_query[models[0]]["oracle"]):
        serie_rows[r["serie"]].append(i)
    for k, v in sorted(serie_rows.items(), key=lambda t: -len(t[1]))[:14]:
        groups[f"series: {k}"] = v
    w("| group | photos | " + " | ".join(models) + " |")
    w("|---|---|" + "---|" * len(models))
    for g, sel in groups.items():
        cells = [_pct(_acc([per_query[m]["oracle"][i] for i in sel])) for m in models]
        w(f"| {g} | {len(sel)} | " + " | ".join(cells) + " |")

    w("\n## Real photos\n")
    if not s["real"]:
        w("No photos in `testdata/real/` yet. Synthetic photos are made from the very image the index uses, so "
          "the numbers above are optimistic - real photos are the true test. See `testdata/real/README.md`.\n")
    else:
        labelled = [r for r in s["real"] if r["truth"]]
        if labelled:
            w("| model | top-1 | top-5 |")
            w("|---|---|---|")
            for m in models:
                t1 = sum(bool(r["preds"][m]["top1"]) for r in labelled) / len(labelled)
                t5 = sum(bool(r["preds"][m]["in_top5"]) for r in labelled) / len(labelled)
                w(f"| {m} | {_pct(t1)} | {_pct(t5)} |")
            w("")
        w("| photo | label → card | card found | " + " | ".join(models) + " |")
        w("|---|---|---|" + "---|" * len(models))
        for r in s["real"]:
            cells = []
            for m in models:
                p = r["preds"][m]
                top = p["top5"][0]
                mark = "" if p["top1"] is None else ("✅ " if p["top1"] else ("🟡 " if p["in_top5"] else "❌ "))
                cells.append(f"{mark}{top['name']} `{top['id']}` {top['score']:.2f}")
            if r["truth"]:
                truth = ", ".join(f"`{t}`" for t in r["truth"][:3]) + (" …" if len(r["truth"]) > 3 else "")
            else:
                truth = "–"
            if r.get("label_problem"):
                truth += f" ⚠️ {r['label_problem']}"
            w(f"| {r['file']} | {truth} | {'yes' if r['detected'] else '**no**'} | " + " | ".join(cells) + " |")
        w("\n✅ right · 🟡 right card in top 5 · ❌ wrong · no mark = photo has no usable label. "
          "Side-by-side images are in the `report` download (debug/real).\n")

    w("## Files in the downloads\n")
    w("- **report**: this report, `results.json` (every test photo), `debug/` (example synthetic photos, "
      "failures side by side, real photo results)")
    w("- **data-pack**: `catalog.sqlite` (cards, sets, Cardmarket + TCGplayer prices) and the fingerprint index per model")
    Path(path).write_text("\n".join(L) + "\n", encoding="utf-8")
