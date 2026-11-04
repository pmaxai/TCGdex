# Card scanner (flea-market edition)

A phone app that points at Pokémon cards and shows their Cardmarket price, offline,
for flea markets.

- `pipeline/`: card database, fingerprints, accuracy tests and the app's data pack (Python)
- `android/`: the app (Kotlin). GitHub Actions builds it into an APK.

## Install the app

**Version 1.0** is the first release: **github.com/pmaxai/TCGdex/releases/tag/v1.0**
(it stays as it is). **app-latest** is always the newest build, with whatever is being
worked on since. The version (1.0.*build*) is shown at the bottom of ⚙.

1. On your phone, open **github.com/pmaxai/TCGdex/releases/tag/v1.0** (or `app-latest`)
   and tap **CardScanner.apk**.
2. Open the downloaded file. Android asks once to allow installing from your browser:
   allow it.
3. Start **Card Scanner** and allow the camera. The first start takes a few seconds
   while it unpacks the card database (about 80 MB).

New builds install over the old one (same signing key), so updating is the same two taps.
Every data pipeline run with fresh prices also builds a new APK.

## Restore points

Go to **Actions → Restore point → Run workflow** and give it a name like `v0.2`
(tick *latest* for a shipped version such as `v1.0`: it becomes the Latest release). That
freezes the code (a git tag), the current APK and the current data pack as a release
that later builds never touch. They're listed under **Releases**. To go back, either
install the APK from that release (uninstall the app first, since Android blocks
downgrades), or restore the code from the tag.

## Using it

- **Scan:** hold one card inside the frame. When the frame turns green the app is sure;
  it takes the card once the frame has stayed green for the time set in ⚙ (*Wait before
  taking the card*: instant, 0.3 s … 2 s; a green bar under the frame fills up while
  you hold still). The result then stays put until another card is taken, so it doesn't
  flicker. The language and set number are read from the card a moment later and
  refine the result.
- **The exact version:** the artwork finds the card; the printed text then decides
  which one it is:
  - **language:** the card is shown in the language it is printed in (English, German,
    French, Spanish, Italian, Portuguese, Japanese). For non-English cards, the English
    name and set are shown under it.
  - **set:** the card number (e.g. `059/191`) picks the right set when the same artwork
    was printed in several sets.
  - **Other language?** chips let you correct it with one tap.
- **Printings:** normal, holo, reverse holo and 1st edition each have their own price
  chip (in YouTube mode too); tap the one in front of you. If a more valuable printing
  exists, a ⚠ line says so, so you don't miss a reverse holo or 1st edition in a box
  of commons.
- **Foil check (experimental):** while you tilt the card a little, the app watches for
  foil shimmer and may pick *Reverse holo* or *Holo* (marked ✨). A second check that
  compares a still frame with the card's scan is measured (see **Details**) but does not
  choose the printing yet: on the first real photos it couldn't tell blurry normal
  cards from reverse holos. Labelled photos of your own cards (see `testdata/real/`)
  are what it needs.
- **Prices:** Cardmarket trend, 30-day average and lowest price (EUR) for the chosen
  printing, plus TCGplayer (USD) where Cardmarket has none.
- **PSA 10 / 9 / 8:** recent eBay sold prices for graded copies (USD) of the printing
  you selected: a graded reverse holo has its own prices. If only another printing has
  sales, the app says so instead of showing the wrong price. Switch them on or off in ⚙.
- **Gem rate:** the share of PSA-graded copies that got a PSA 10, with the population
  ("PSA 10: 340 of 3,000 graded"), per printing. It needs PokemonPriceTracker's Business
  plan; with the API plan the line simply doesn't appear (the pipeline checks each day).
- **Energy cards** (⚙): *skip common* ignores the energy cards that come in every pack
  but still recognises gold and secret rare energies; *skip all* ignores every energy.
- **Card must fill the frame** (⚙, off / 30–85 %): a card only counts if it covers that
  much of the guide frame. Against "ghost" cards: a card lying further away, one at the
  edge, or the frame without any card outline in it. Details shows how much the current
  card fills.
- **Double tap** on the camera picture takes the card in the frame right away, for cards
  the scanner isn't sure about: it reads every outline it found plus the frame itself
  and shows the best match (a white flash confirms it). The frame-fill setting doesn't
  apply to it. A small window then lists the **10 most likely cards** (picture, set,
  number, year, price; a bar shows how close each came): tap the right one, or ✕ to keep
  the one shown.
- **Release year:** the year the card's set came out (in its language) is shown next to
  the set and number, also in the version lists, and goes into the pack CSV.
- **Camera and card, half and half:** the camera picture takes one half of the screen and
  the result the other, in portrait (camera on top) and landscape (camera left).
- **Screen orientation** (⚙): *Auto* turns with the phone, *Portrait* and *Landscape* stay
  put. Works in both modes.
- **Zoom:** pinch the camera picture, or pick 1× … 4× in ⚙. The zoom is kept.
- **Capture frame size** (⚙, 50–90 %): how tall the frame is in the camera picture.
  Bigger means holding the card closer (more detail), smaller suits a tripod further away.
- **Card picture:** always shown: the card's image, the same card in another language, or
  another print of the artwork; without internet or when there is no image at all, the
  card as the camera saw it. **Hold the picture for a second** to see it full screen (✕
  closes it); *Card image* / *Your card* switches between the database image and your
  card, e.g. to check its condition.
- **Other versions:** the same picture printed in other sets or languages is listed
  under the card (*Other version or language?*); tap one to switch, prices follow.
- **Not this card?** The other candidates are listed under it; tap the right one. It
  then stays pinned (📌) until you tap **Next card**.
- **Alert:** set a price (e.g. 5 €). The phone vibrates when a card worth at least
  that appears (any printing), so you can sweep through a box and only stop when it
  buzzes.
- **YouTube mode** (⚙ → YouTube mode): a clean screen for screen-recorded opening and
  price-check videos, in portrait or landscape (see *Screen orientation*). The card box
  keeps the same look in both: it is scaled as a whole instead of wrapping text, and long
  names or prices shrink to one line.
  - big card name and price with a count-up, and a "HIT" burst for cards above your
    alert price
  - the printings as chips under the price: tap the right one if the app got it wrong
  - **‹ Version 2/4 ›** under the set: step through other sets and languages of the
    same picture, or tap the middle for the list (with "Not this card?" candidates)
  - **double tap** the camera picture to take a card the scanner doesn't recognise
  - **swipe the card to the left** to take it out of the pack (wrong match, or a card
    you don't want to count); the same card isn't taken again until you show another
  - a session bar with card count and total value
  - **+1** (next to ⚙) puts the shown card into the pack once more: two of the same card
    in a row are otherwise counted once. Each copy is its own entry (own printing; a
    swipe removes only that copy), and the picture shows ×2, ×3 … when the pack has the
    card more than once. It also adds a card the scanner wasn't sure enough to count.
  - the **⚙ button** next to the pack value opens the menu: **save pack (CSV)**, new
    pack, remove last card, next card, light, settings, exit
  - *Save pack* writes one row per card (name, English name, set, number, language,
    printing, Cardmarket and TCGplayer prices, PSA 10/9/8 with sale counts, hit, all
    printings) plus the pack total. On a German phone it uses `;` and decimal commas,
    so Excel opens it directly.
  - optional title (e.g. your channel name) at the top; no vibration while recording
- **Light** turns on the torch. **Photo** recognises a picture from the gallery.
  **Details** (in ⚙) shows what the scanner saw: the straightened card, the top 5
  matches with scores, the text it read, and timings.
- **Offline:** recognition, text reading and prices work without a connection. The
  card thumbnail and the Cardmarket button need the internet.

## PSA prices

PSA prices come from [PokemonPriceTracker](https://www.pokemonpricetracker.com/api)
(eBay sold listings). The app never calls it; the daily data pipeline downloads the
prices into the data pack, refreshing each set about once a week.

1. Get an API key there (the $9.99/month plan; the free plan's 100 credits a day are
   too few).
2. In this repo: **Settings → Secrets and variables → Actions → New repository
   secret**, name `POKEMONPRICETRACKER_API_KEY`, value: your key.
3. Run **Actions → Data pipeline → Run workflow**. The next APK then has PSA prices.

The key stays inside GitHub Actions; it is never printed in logs or put into the app.
Without the key everything works, just without PSA prices.

## What the pipeline does

```
TCGdex (free card database)                      your photos (testdata/real/)
  sets, cards (7 languages), prices, images                     │
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

  When the first outline gives no sure match, the app reads the detector's **second
  guess** too (another outline, also built from straight edge lines: they find a card
  whose outline runs into fingers or a sleeve, or whose shiny border breaks the contour),
  and the better match wins.

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
