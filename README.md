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
                     (pHash, MobileNetV3, DINOv2 -
                      on the whole card or the artwork only)
```

- **Catalog:** every set and card in the chosen languages, plus Cardmarket (EUR) and
  TCGplayer (USD) prices from [TCGdex](https://tcgdex.dev). Everything is cached, so
  repeat runs barely touch their servers.
- **Fingerprints:** each card image becomes a vector. Matching a photo means finding
  the closest vector. Three methods are compared, each on the whole card or with
  `_art` on the artwork area only. The artwork is what makes a card unique, and it is
  the same in every language.
  - `phash`: image hashing, no AI
  - `mnv3`: MobileNetV3, tiny and fast on phones
  - `dinov2s`: DINOv2-small, a strong image-retrieval model that still runs on phones
- **Card detection** (`pipeline/tcgscan/detect.py`, plain OpenCV, to be ported to the app):
  - looks for card-shaped outlines at three resolutions
  - checks that each side runs along a real edge
  - grows an inner-panel outline to the outer border
  - snaps corners to the edges

  Every detection is also read as the card's **artwork box**. That box is often the
  crispest rectangle on a card when the card's own edge is faint or covered by
  fingers. The better match wins.
- **Accuracy test:**
  - Thousands of synthetic phone photos are scanned the way the app would scan them.
    They come in 3:4, 9:16 and 9:20 frames, with random tables, other cards nearby,
    fingers, sleeves, glare, blur, shadows, tilt and upside down cards. Half are
    "guided" (card lined up with the app's on-screen frame).
  - Each photo is checked twice: with the card detector ("full pipeline") and with
    the true corners ("perfect detection").
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

**Timing:** the first full run takes about 1.5 h, mostly fingerprinting about 33,000
card images with DINOv2. Fingerprints are cached, so later runs take about 25 min.

**Detector lab:** go to **Actions → Detector lab → Run workflow** (a few minutes, no AI). It
compares detector variants on synthetic photos of real cards. For each missed card it
says why: the outline was never found, a filter rejected it, or another shape scored
higher.

## Findings so far

Full catalog: 19,750 cards and 33,424 images (English and German).

- **Matching with perfect corners:** MobileNetV3 and DINOv2 on the artwork get about 92%.
  On the whole card they get only 82–89%. pHash scores 98%, but synthetic photos are made
  from the very image it compares against. Your real photos will tell whether that holds.
- **Detection is the weak spot**, mainly when a card touches other cards (binders, boxes).
  It finds the right card in about 72–75% of the harder synthetic photos.

## Locally (optional)

```bash
cd pipeline
pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt pytest
python -m pytest -q tests                                   # offline self-test
python -m tcgscan fetch --langs en,de --max-sets 10          # catalog -> out/catalog.sqlite
python -m tcgscan evaluate --models phash,mnv3_art,dinov2s_art --real ../testdata/real
python -m tcgscan detector-lab --photos 200
```

## Notes

- Card images and data come from TCGdex. Card images belong to The Pokémon Company.
  They are only cached inside GitHub Actions and are never committed here.
- Prices from TCGdex can be missing or occasionally wrong for some cards (their own
  FAQ says so). Cardmarket's own price-guide download can be added later as a second
  source.
