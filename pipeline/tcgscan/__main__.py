"""Command line: python -m tcgscan {fetch,evaluate} ..."""
from __future__ import annotations

import argparse
import logging
from pathlib import Path


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(prog="tcgscan", description="Card catalog + scanner accuracy pipeline")
    sub = ap.add_subparsers(dest="cmd", required=True)

    f = sub.add_parser("fetch", help="download the TCGdex catalog, prices and images")
    f.add_argument("--cache", type=Path, default=Path(".cache/tcgdex"))
    f.add_argument("--db", type=Path, default=Path("out/catalog.sqlite"))
    f.add_argument("--langs", default="en,de,fr,es,it,pt,ja", help="comma separated TCGdex language codes")
    f.add_argument("--image-langs", default="en,de,ja", help="languages whose card images are downloaded")
    f.add_argument("--price-lang", default="en")
    f.add_argument("--no-prices", action="store_true", help="skip per-card details (prices, rarity)")
    f.add_argument("--no-images", action="store_true")
    f.add_argument("--max-sets", type=int, default=0, help="0 = all sets; N = evenly spread subset for quick tests")
    f.add_argument("--workers", type=int, default=8)
    f.add_argument("--exclude-series", default="tcgp", help="TCGdex series ids to skip (tcgp = TCG Pocket, digital only)")

    e = sub.add_parser("evaluate", help="accuracy test on synthetic + real photos")
    e.add_argument("--cache", type=Path, default=Path(".cache/tcgdex"))
    e.add_argument("--db", type=Path, default=Path("out/catalog.sqlite"))
    e.add_argument("--emb-cache", type=Path, default=Path(".cache/emb"))
    e.add_argument("--models", default="phash,mnv3_art,dinov2s_art")
    e.add_argument("--queries", type=int, default=1500)
    e.add_argument("--real", type=Path, default=None, help="folder with your own labelled photos")
    e.add_argument("--out", type=Path, default=Path("out"))
    e.add_argument("--seed", type=int, default=1234)

    d = sub.add_parser("detector-lab", help="compare card-detector variants (no AI models)")
    d.add_argument("--cache", type=Path, default=Path(".cache/tcgdex"))
    d.add_argument("--db", type=Path, default=Path("out/catalog.sqlite"))
    d.add_argument("--photos", type=int, default=400)
    d.add_argument("--out", type=Path, default=Path("out/detector-lab.md"))

    x = sub.add_parser("export", help="build the Android app's data pack")
    x.add_argument("--cache", type=Path, default=Path(".cache/tcgdex"))
    x.add_argument("--db", type=Path, default=Path("out/catalog.sqlite"))
    x.add_argument("--emb-cache", type=Path, default=Path(".cache/emb"))
    x.add_argument("--model", default="mnv3_art")
    x.add_argument("--pca", type=int, default=512)
    x.add_argument("--real", type=Path, default=None)
    x.add_argument("--results", type=Path, default=Path("out/results.json"))
    x.add_argument("--out", type=Path, default=Path("out/app-pack"))
    x.add_argument("--history", type=Path, default=None, help="price history archive (tcgscan history)")

    h = sub.add_parser("history", help="price history: record today, backfill, ECB rates, monthly files")
    h.add_argument("action", choices=["record", "backfill", "backfill-ppt", "ecb", "pack-months", "unpack-months"])
    h.add_argument("--db", type=Path, default=Path("out/catalog.sqlite"))
    h.add_argument("--dir", type=Path, default=Path(".cache/history"))
    h.add_argument("--months", type=Path, default=None, help="folder of history-YYYY-MM.tar files")
    h.add_argument("--max-files", type=int, default=160, help="backfill: TCGCSV archives to read at most")
    h.add_argument("--budget", type=int, default=12000, help="backfill-ppt: PokemonPriceTracker credits to spend at most")
    h.add_argument("--minutes", type=float, default=25, help="backfill-ppt: time limit")

    p = sub.add_parser("altimages", help="pictures from pokemontcg.io for cards TCGdex has none for")
    p.add_argument("--db", type=Path, default=Path("out/catalog.sqlite"))
    p.add_argument("--cache", type=Path, default=Path(".cache/tcgdex"))
    p.add_argument("--data", type=Path, required=True, help="checkout of github.com/PokemonTCG/pokemon-tcg-data")

    g = sub.add_parser("graded", help="PSA 10/9/8 prices from PokemonPriceTracker (needs POKEMONPRICETRACKER_API_KEY)")
    g.add_argument("--db", type=Path, default=Path("out/catalog.sqlite"))
    g.add_argument("--cache", type=Path, default=Path(".cache/graded"))
    g.add_argument("--budget", type=int, default=18000, help="max credits to spend in this run")
    g.add_argument("--max-age-days", type=float, default=7)
    g.add_argument("--inspect", action="store_true", help="only print the cached data's structure")
    g.add_argument("--probe-population", action="store_true", help="one call: does the plan include gem rates?")
    g.add_argument("--probe-history", action="store_true", help="two calls: price history for one card, and its credit cost")

    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")

    if a.cmd == "fetch":
        from .catalog import fetch_catalog
        from .tcgdex import TCGdexClient
        client = TCGdexClient(a.cache, workers=a.workers)
        fetch_catalog(client, a.db, [x.strip() for x in a.langs.split(",") if x.strip()], a.price_lang,
                      with_details=not a.no_prices, download_images=not a.no_images, max_sets=a.max_sets,
                      exclude_series=tuple(x.strip() for x in a.exclude_series.split(",") if x.strip()),
                      image_langs=[x.strip() for x in a.image_langs.split(",") if x.strip()])
    elif a.cmd == "altimages":
        from .altimages import fill
        fill(a.db, a.cache, a.data)
    elif a.cmd == "graded":
        from .graded import fetch as graded_fetch, inspect as graded_inspect, probe_history, probe_population
        if a.probe_history:
            probe_history()
        elif a.probe_population:
            probe_population()
        elif a.inspect:
            graded_inspect(a.cache)
        else:
            graded_fetch(a.db, a.cache, a.budget, a.max_age_days)
    elif a.cmd == "export":
        from .export import run as export_run
        export_run(a.db, a.cache, a.emb_cache, a.model, a.out, a.real, a.results, a.pca, history_dir=a.history)
    elif a.cmd == "history":
        from . import history as hist
        if a.action == "record":
            hist.record(a.db, a.dir)
        elif a.action == "backfill":
            hist.backfill_tcgcsv(a.db, a.dir, max_files=a.max_files)
        elif a.action == "backfill-ppt":
            hist.backfill_ppt(a.db, a.dir, budget=a.budget, minutes=a.minutes)
        elif a.action == "ecb":
            hist.fetch_ecb(a.dir)
        elif a.action == "pack-months":
            for p in hist.pack_months(a.dir, a.months or a.dir / "months"):
                print(p)
        else:
            hist.unpack_months(a.dir, a.months or a.dir / "months")
    elif a.cmd == "detector-lab":
        from .lab import run as lab_run
        text = lab_run(a.db, a.cache, a.photos)
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(text, encoding="utf-8")
        print(text)
    else:
        from .evaluate import run
        run(a.db, a.cache, a.emb_cache, [m.strip() for m in a.models.split(",") if m.strip()], a.queries,
            a.real, a.out, a.seed)


if __name__ == "__main__":
    main()
