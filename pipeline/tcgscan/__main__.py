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
    f.add_argument("--langs", default="en,de", help="comma separated TCGdex language codes")
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

    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")

    if a.cmd == "fetch":
        from .catalog import fetch_catalog
        from .tcgdex import TCGdexClient
        client = TCGdexClient(a.cache, workers=a.workers)
        fetch_catalog(client, a.db, [x.strip() for x in a.langs.split(",") if x.strip()], a.price_lang,
                      with_details=not a.no_prices, download_images=not a.no_images, max_sets=a.max_sets,
                      exclude_series=tuple(x.strip() for x in a.exclude_series.split(",") if x.strip()))
    else:
        from .evaluate import run
        run(a.db, a.cache, a.emb_cache, [m.strip() for m in a.models.split(",") if m.strip()], a.queries,
            a.real, a.out, a.seed)


if __name__ == "__main__":
    main()
