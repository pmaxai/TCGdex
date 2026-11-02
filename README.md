# Card scanner (flea-market edition)

A phone app that points at Pokémon cards and shows their Cardmarket price. This
repository is built in stages:

1. **Data pipeline + accuracy test** (this stage, `pipeline/`)
2. Android app, built into an APK by GitHub Actions (next)

## What the pipeline does

```
TCGdex (free card database)                      your photos (testdata/real/)
  sets, cards (en + de), prices, images                     │
        │                                                   │
        ▼                                                   ▼
  catalog.sqlite ──► fingerprint every card ──► match synthetic + real photos ──► report
                     (pHash, MobileNetV3, DINOv2)
```

- **Catalog:** every set and card in the chosen languages, plus Cardmarket (EUR) and
  TCGplayer (USD) prices from [TCGdex](https://tcgdex.dev). Everything is cached, so
  repeat runs barely touch their servers.
- **Fingerprints:** each card image becomes a vector. Matching a photo means finding
  the closest vector. Three methods are compared:
  - `phash`: image hashing, no AI
  - `mnv3`: MobileNetV3, tiny and fast on phones
  - `dinov2s`: DINOv2-small, a strong image-retrieval model that still runs on phones
- **Accuracy test:**
  - Thousands of synthetic phone photos (random tables, other cards nearby, sleeves,
    glare, blur, shadows, tilt, upside down) are scanned the way the app would scan them.
    Each one is checked twice: with the card detector ("full pipeline") and with the
    true corners ("perfect detection").
  - Your own photos in `testdata/real/` are scanned too.

## Running it

Everything runs on GitHub. Nothing needs installing.

- **Manually:** go to **Actions → Data pipeline → Run workflow**. For a quick first look,
  set `max_sets` to `15` (about 10 min); `0` runs the full catalog.
- **Automatically:** whenever photos are added to `testdata/real/`.

**Where the results are:** open the run. Its **Summary** page shows the report.
Further down, *Artifacts* has two downloads:
- `report`: debug images that show failures side by side
- `data-pack`: the catalog and fingerprint index the app will use

**Timing:** the first full run takes about 1–1.5 h, mostly fingerprinting about 35,000
card images with DINOv2. Fingerprints are cached, so later runs take about 10–20 min.

## Locally (optional)

```bash
cd pipeline
pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt pytest
python -m pytest -q tests                                   # offline self-test
python -m tcgscan fetch --langs en,de --max-sets 10          # catalog -> out/catalog.sqlite
python -m tcgscan evaluate --models phash,mnv3,dinov2s --real ../testdata/real
```

## Notes

- Card images and data come from TCGdex. Card images belong to The Pokémon Company.
  They are only cached inside GitHub Actions and are never committed here.
- Prices from TCGdex can be missing or occasionally wrong for some cards (their own
  FAQ says so). Cardmarket's own price-guide download can be added later as a second
  source.
