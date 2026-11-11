# Card scanner (flea-market edition)

A phone app that points at Pokémon cards and shows their Cardmarket price, offline,
for flea markets.

- `pipeline/`: card database, fingerprints, accuracy tests and the app's data pack (Python)
- `android/`: the app (Kotlin). GitHub Actions builds it into an APK.

## Install the app

The current release is **Version 1.1.6**: **github.com/pmaxai/TCGdex/releases/tag/v1.1.6**
(releases stay as they are; v1.0 and v1.1 are still there). **app-latest** is always the
newest build, with whatever is being worked on since. The version is shown in ⚙ → **About**.

**Version numbers:** builds count on the current version line: 1.2.1, 1.2.2, 1.2.3, …
A release moves to the next line, so after releasing 1.2.9 the builds are 1.3.1, 1.3.2, …
(the line is in `android/version.properties`; Android's hidden version code keeps
counting all builds, so every build installs over the one before).

1. On your phone, open **github.com/pmaxai/TCGdex/releases/tag/v1.1** (or `app-latest`)
   and tap **CardScanner.apk**.
2. Open the downloaded file. Android asks once to allow installing from your browser:
   allow it.
3. Start **Card Scanner** and allow the camera. The first start takes a few seconds
   while it unpacks the card database (about 80 MB).

New builds install over the old one (same signing key), so updating is the same two taps.
Every data pipeline run with fresh prices also builds a new APK.

**Updating:** when a newer build is out, the app shows a bar at the top: "New version
1.1.57 · prices of 5 Nov (yours: 3 Nov)" with **Update**. One tap downloads it and opens
Android's installer; confirm there (the first time, Android asks you to allow Card Scanner
to install apps: allow it, come back, and it goes on). Your carts and settings stay.
**Later** hides the bar until the next start; ⚙ → **About** → **Check for updates** looks again.

**Fresh prices every night:** TCGdex updates its prices daily at about 23:50 CET. The data
pipeline starts at 00:40 CET and the new APK (with that day's prices) is out at about
02:30 CET, well before flea markets (6:00) and shows (10:00). Install it before you leave.

## Licence

The app asks for a one-off licence (bought in the shop; the license service runs next to
the shop: `apps/license` in the pokeshop repository, see its `docs/license-service.md`).
It is **on** since build 1.2.25; `cardscanner.licenceRequired` in `android/gradle.properties`
switches it (the service's address is `cardscanner.licenceUrl`).

- **First start:** scan the QR code from the licence e-mail, or type the key
  (`CS-XXXXX-XXXXX-XXXXX-XXXXX`; case, spaces and dashes don't matter). Activating needs
  the internet once.
- **Offline is fine:** the app asks the service at every start and at most once an hour after
  that; without a connection it simply goes on.
- **One device at a time:** ⚙ → **Licence** → **Show the licence QR code** (Switch to new
  device); scan it with the new device. The old one stops the next time it is online, says
  where the licence went, and forgets the key: it comes back only by scanning a QR code again.
- **Revoked** (refund): the app stops at its next check.
- **Is the service up?** **Actions → License service health → Run workflow** asks the
  production and the staging service and shows their answers in the log.

## Restore points

Go to **Actions → Restore point → Run workflow**. Leave the name empty to name it after
the current build (`v1.2.9`), or give one. Tick *latest* for a release: it becomes the
Latest release and the next builds start the next version line (1.3.1, …); without it,
it's only a restore point and the numbering goes on. That freezes the code (a git tag),
the current APK and the current data pack as a release that later builds never touch. They're listed under **Releases**. To go back, either
install the APK from that release (uninstall the app first, since Android blocks
downgrades), or restore the code from the tag.

## Using it

- **Settings (⚙)** open as a screen of their own, with a sidebar of pages: **Mode**,
  **Camera** (wait time, frame size, zoom, fill, energy cards, orientation), **Prices**
  (alert, PSA, where prices come from, the step of − / +), **Trading**, **Webshop** (shop
  connection, minimum selling price), **YouTube**, **Advanced** (details), **Licence**
  (where it is active, switch to new device) and **About** (version, price date, updates). Upright, the sidebar is a slim column of
  icons; turned sideways it lists the full names. ⚙ opens the page that fits where you
  are (Trading Mode: Trading; YouTube: YouTube; otherwise where you left it), the Alert
  chip opens Prices. Back (← or the phone's back) returns to the camera.
- **Scan:** hold one card inside the frame. When the frame turns green the app is sure;
  it takes the card once the frame has stayed green for the time set in ⚙ (*Wait before
  taking the card*: instant, 0.3 s … 2 s; a green bar under the frame fills up while
  you hold still). The result then stays put until another card is taken, so it doesn't
  flicker. The language and set number are read from the card a moment later and
  refine the result.
- **The exact version:** the artwork finds the card; the printed text then decides
  which one it is:
  - **language:** the card is shown in the language it is printed in (English, German,
    French, Spanish, Italian, Portuguese, Japanese, Chinese). For non-English cards, the
    English name and set are shown under it. Asian cards are told by their script: kana
    is Japanese, Hangul Korean, Chinese characters without kana Chinese (traditional or
    simplified by the characters). Most Asian prints have no picture in the card data; they
    are found by their printed number (e.g. `005/063`) and the name in their own script
    (the picture shown is the western card's; *Create Product Image* takes one for the
    shop). Korean prints aren't in the card data yet: the western card is shown with a
    "Printed in Korean" note, and the Webshop takes KO as the language. ⚙ → Details shows
    what the script check saw.
  - **set:** the card number (e.g. `059/191`) picks the right set when the same artwork
    was printed in several sets.
  - **Other language?** chips let you correct it with one tap.
- **Printings:** normal, holo, reverse holo and 1st edition each have their own price
  chip (in YouTube mode too); a card starts as *Normal* whenever it has a normal printing;
  tap the one in front of you. If a more valuable printing
  exists, a ⚠ line says so, so you don't miss a reverse holo or 1st edition in a box
  of commons.
- **Foil check (experimental):** while you tilt the card a little, the app watches for
  foil shimmer and may pick *Reverse holo* or *Holo* (marked ✨), but only for cards
  without a normal printing: those always stay *Normal* until you tap another. A second check that
  compares a still frame with the card's scan is measured (see **Details**) but does not
  choose the printing yet: on the first real photos it couldn't tell blurry normal
  cards from reverse holos. Labelled photos of your own cards (see `testdata/real/`)
  are what it needs.
- **Prices:** Cardmarket trend, 30-day average and lowest price (EUR) for the chosen
  printing, plus TCGplayer (USD) where Cardmarket has none.
- **Price history:** under the price, a 90-day line with the change (Cardmarket, or
  TCGplayer in euros where Cardmarket's history is still short). Tap it for the full chart: 1 month to 1
  year, Cardmarket trend and TCGplayer (converted at each day's ECB rate), per printing,
  with the high and the low. The history comes with the app (install the latest before a
  show). Where it comes from: the data pipeline saves every card's prices every day (kept
  as monthly files in the `price-history` release). The past was filled in from two
  sources, one per marketplace: TCGplayer's past year from TCGCSV's daily archives
  (TCGplayer's own market price), Cardmarket's last six months from PokemonPriceTracker
  (about 2,500 cards a day within the plan's daily credits, most valuable cards first). A
  single reading far off its neighbours (a glitch) is left out; a day without a reading
  takes the nearest one within 3 days.
- **Price check:** the card database (TCGdex) links some cards to the wrong Cardmarket
  product, so they carry another card's price (a common Plinfa 54/236 showed the secret
  rare Plinfa 239/236's €39.74). The data pipeline finds cards that share the same
  Cardmarket figures (all of them, or all but two when TCGdex refreshed the two cards on
  different days, as with Magnetilo 68/236 and 242/236) and keeps the price only for the
  card whose TCGplayer price fits it;
  the others show TCGplayer converted to euros with "No reliable Cardmarket price". Where it
  can't tell, the price stays with a warning, and any card worth €3 or more whose Cardmarket
  and TCGplayer prices are over 4x apart gets "check before you pay". Trading Mode gives
  no deal verdict on such prices ("Check price").
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
  the one shown. While the list is open nothing else is taken. A card taken by hand (or
  picked from a list) stays while it is in front of the camera: the scanner keeps reading
  every outline like the double tap did, and only a card it is sure is a different one
  replaces it.
- **Release year:** the year the card's set came out (in its language) is shown next to
  the set and number, also in the version lists, and goes into the pack CSV.
- **Camera and card, half and half:** the camera picture takes one half of the screen and
  the result the other, in portrait (camera on top) and landscape (camera left).
- **Screen orientation** (⚙): *Auto* turns with the phone, *Portrait* and *Landscape* stay
  put. Works in both modes.
- **Zoom:** pinch the camera picture, or pick 1× … 4× in ⚙. The zoom is kept.
- **Capture frame size** (⚙, 50–90 %): how tall the frame is in the camera picture.
  Bigger means holding the card closer (more detail), smaller suits a tripod further away.
- **Search cards** (🔍 at the top, or ⚙ → *Search a card…* in YouTube mode): look up any
  card without having it in front of the camera: name in any language, number and set,
  e.g. `whimsicott 019`, `elfun`, `charizard base`, `GG01`, `darumaka 034/167`. The camera
  pauses and shows the card (tap the picture to scan again). It also finds cards the
  camera can't recognise because no picture of them exists (marked *no picture*).
  *Not this card? → 🔍 Search…* (and the window after a double tap) puts the card you pick
  in place of the one shown, also in the pack.
- **Card picture:** the card's image, the same card in another language, or another print
  of the artwork. While it loads the frame stays empty; your card as the camera saw it
  only shows when there is no picture or no internet, marked *your card*. **Hold the picture for a second** to see it full screen (✕
  closes it); *Card image* / *Your card* switches between the database image and your
  card, e.g. to check its condition.
- **Other versions:** the same picture printed in other sets or languages is listed
  under the card (*Other version or language?*); tap one to switch, prices follow.
- **Not this card?** The other candidates are listed under it; tap the right one. It
  then stays pinned (📌) until you tap **Next card**.
- **Alert:** set a price (e.g. 5 €). The phone vibrates when a card worth at least
  that appears (any printing), so you can sweep through a box and only stop when it
  buzzes.
- **Number pad** for prices (Trading and Webshop): big keys, the price in large digits.
  **Clear** empties it and stays open for the new price; **OK** on an empty price removes
  it. Held sideways, the keys sit to the right of the price, so OK is always on the screen.
- **Trading Mode** (⚙ → Mode → Trading), for card shows: judge a vendor's price, negotiate,
  and keep track of what you buy, booth by booth.
  - **Market value**: the Cardmarket trend of the printing (or the 30-day average or the
    lowest price, ⚙), times the card's condition: NM 100 · EX 85 · GD 75 · LP 65 · PL 50 ·
    PO 30 %. Tap the condition under the name; the camera can't judge it. An arrow shows
    the trend against the 30-day average. Warnings appear for few sales, a price taken
    from the English version, and prices older than 3 days (install the latest app
    before a show).
  - **Asking price**: tap *Type the asking price* and enter it on the big number pad
    (quick buttons: = market, −10 %, −20 %, offer), or nudge it with **−** / **+** left and
    right of the price (each tap by the step from ⚙ → Prices, e.g. 0.50 €; the same beside
    the cart line's prices and Webshop's selling price). The verdict: up to 80 % of the market
    value *great deal*, 95 % *good price*, 110 % *fair*, above that *expensive*. Below it:
    an opening offer (85 %, rounded down) and the walk-away price (100 %).
  - **+ Cart** puts the card with its printing, condition and asking price into the cart
    of the current booth. The same card again adds to its quantity.
  - **Carts per booth**: the cart chip at the top opens it. Each line keeps market value,
    asking price and negotiated price; tap a line to change condition, quantity, prices
    or status (considering, agreed, dropped), swipe it left to drop it. Totals: market
    value, asking, what you pay, saved against market and asking. *Next booth* starts a
    new cart; ▾ switches between open carts, renames, saves as CSV or deletes one.
  - **Bundle…** in the cart: tap the cards of a bundle, see their market value, asking
    total and a suggested offer, and agree one price for them. **Agree total** does the
    same for the whole cart. The price is split over the cards by market value, to the
    cent, and they are marked agreed.
  - **Trades and selling**: the pill at the top of the camera switches what you scan
    between *I get* (the vendor's cards) and *I give* (yours; + I give). For your own
    cards the panel rates the vendor's offer: under 75 % of the market value *low*, up to
    90 % *usual*, above *good*. In the cart, *Trade* opens the I give side: your cards
    count at 80 % of their market value, the vendor's at 100 % (tap to change), unless a
    card has its own price or offer. The balance says who pays how much; *Agreed cash*
    stores what you settle on (⇄ flips who pays), and *At market value* shows what the
    trade is worth to you without the rates. A cart with only I give cards is a sale.
  - **Show & budget** (⚙): name the show and set a budget. A bar under the top bar fills
    with what you spent (paid deals) and agreed (not paid yet), orange from 90 %.
  - **Paid** (or **Done** for a trade; cash, card, other) moves the cart into the **deal
    history** (⚙): deals by show (or by day) with a summary (spent, received, market
    value in and out, cards, savings, budget used); reopen one to fix a mistake, save one,
    a show or all as CSV.
  - **Last scans**: the tray under the price brings back any of the last 20 cards.
  - Carts and deals are kept on the phone (nothing leaves it unless you save a CSV).
- **Webshop Mode** (⚙ → Mode → Webshop), for your Pokeshop (pokemonkarten.at): scan or
  search a card and see its prices (market value for the condition, Cardmarket trend,
  TCGplayer, PSA) and what the shop holds of it: one line per product (language ·
  condition · finish) with price, stock per box and status. Pick language (from the scan,
  any of the shop's 15 can be chosen), type (**N** normal, **H** holo, **RH** reverse holo,
  **1st** when the card has a 1st edition; also one the price data doesn't list, then
  without a market price), storage box (1–999, the last one is remembered),
  selling price (suggested: the shop's price, else the market value for the condition, but
  never below your **minimum selling price** from ⚙ → Webshop, for cards worth only a few
  cents; the number pad shows "Market price: x") and condition (M … PO; **sky blue**: the
  shop has copies of this card in stock in that condition, same language and type); a new product can
  get a comment (shown on its product page). Each booking adds one copy. The line above the
  button shows the card and its stock: "Pikachu 58/102 DE - 2 in stock in boxes 014, 021",
  or for a new product "Glurak-ex (DE · EX · Holo · 2023 · 199/165) is new". When a card
  is detected, the panel scrolls down so this button is in view. A product goes live only
  with a price and a picture. When the shop's card catalogue has no picture of the card
  (it takes them from TCGdex, which lacks some, e.g. Trainer Gallery cards), the booking
  sends the picture the app shows, if it is the card in its own language. When there is
  none in the card's language (many Japanese and Chinese cards, some German ones),
  **Create Product Image** appears at the bottom of the camera side: it takes a photo
  (the camera's full resolution where the phone allows), finds the card's outer edge,
  straightens it and cuts it out at the card's shape (without an edge: the guide frame);
  the picture is shown small on the button (tap to take it again) and goes up with the
  next booking. A product that stayed a draft only for its picture goes live then. A
  booking's warnings are cleared with the next card. The shop's address at the
  top of the panel opens ⚙ → Webshop.
  **Scanning books nothing**: the stock changes only with **Add to stock** (the product
  exists) or **Create in the shop** (it doesn't). **Undo** beside it takes the copy out again.
  While you fill in, the camera keeps the card. A card the shop's card list doesn't know
  is created with its German and English names (shown for you to check).
  - **Sell** (the *Stock in | Sell* switch at the top): a customer at your table. Scan or
    search each card they want; the app picks the matching product (scanned language and
    printing first; tap another line to change it) and the box with the most free copies.
    Pick the box and copies and press **Add to the order**: nothing leaves the stock yet.
    The **🧾** button shows the order (cards and total, kept if the app is closed): change
    copies, remove lines, then **Complete sale** takes everything out of stock in one go
    (one `/batch` request, reason "sold in person (scanner app)"). A line the shop refuses
    (e.g. the box holds fewer copies by now) stays in the order with the reason; the rest is
    taken out. The sale's summary stays in the 🧾 order with **Undo**, and a small **Undo
    sale** sits beside *Add to the order*: it puts the sold copies back into their boxes.
    The switch remembers Stock in or Sell. Copies held by an online
    checkout are marked: selling them shows the product as sold out and the shop tells the
    admin. The shop's API has no orders, so a sale appears in the shop as stock changes with
    that reason (not as an order with a customer or a payment).
  - **Connect** in ⚙ → Webshop: host (e.g. `pokemonkarten.at`, or `staging.pokemonkarten.at`
    for the test shop), key id and secret from the shop's admin (Admin → API-Schlüssel; one
    key per phone, no IP allowlist). The secret is stored encrypted with a key of the
    Android Keystore and is never shown again, never in the APK or a log. *Save & test*
    checks key, secret and clock.
  - Every request is signed (HMAC-SHA256); every booking and sale has an idempotency key
    that stays the same when it's sent again, so a retry after a dropped connection never
    books or sells twice. The phone's clock is corrected from the shop's.
- **YouTube mode** (⚙ → Mode → YouTube): a clean screen for screen-recorded opening and
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
  - a session bar with the pack's value
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
  - **Recording** (**● REC** next to +1 and ⚙, or ⚙ → *Start recording*): the app records
    its own screen and uploads it with a log of every card, for the video pipeline. Until
    the upload is set up, ● REC opens ⚙ → Recording & upload. Before it starts: **Continue
    pack** or **New pack**, the microphone (off by default; the pipeline can add a
    voiceover) and the space it needs (a warning below an hour). Android then asks to share
    the screen; on Android 14 and later choose *A single app* → Card Scanner.
    While recording, ● REC leaves the bar (nothing covers the pack value): a red dot and the
    time sit in the black border beside the 16:9 area, in a window the screen recording
    leaves out, so they are not in the video (tap them to stop; ⚙ → *Stop recording* and
    the notification's Stop work too, also without Fit to 16:9, where there is no border
    and so no timer on screen). The screen orientation is locked. There is no length
    limit. The recording stops by itself when the app goes into the background or the
    phone has less than 300 MB left. If the app crashes, at most the last 10 seconds are
    lost: the next start repairs the file and marks the recording *recovered*.
    - **Files** (one folder per recording): `video.mp4` (the screen, H.264, a constant
      30 fps whatever the display's refresh rate, 1080p or 720p, 6/8/12 Mbit/s, a keyframe
      every second; sound only with the mic on), `camera.mp4` (the camera picture without
      anything drawn on it, 4:3 like the scanner's, so it sees as much as the scanner does:
      1440×1080 or the largest size the phone records next to scanning; phones that can't
      record the camera next to scanning record only the screen, and the log says why),
      `events.json` (every card shown, changed, added, removed, with time stamps to the
      frame – a reveal's time is when its first frame is drawn – card data, prices, hit and
      the card's four outer corners in the camera picture, also when the scanner found only
      the artwork box) and `pack.csv` (the same as *Save pack*).
    - **Fit to 16:9** (⚙ → Recording & upload, on by default): YouTube mode is laid out in
      a 16:9 area (9:16 upright) with black around it; the log says where that area is
      (`content_rect`), so the pipeline crops it exactly.
    - **Upload** (⚙ → Recording & upload): an S3 bucket. Provider **AWS** (region, default
      eu-central-1), **Exoscale** (zone, e.g. `at-vie-1`; the endpoint
      `https://sos-<zone>.exo.io` is filled in) or **Other** (any S3-compatible endpoint,
      path-style addressing). Bucket, folder prefix, access key ID and secret. The secret is
      stored encrypted with a key of the Android Keystore and is never shown again, never in
      the APK, a log or an uploaded file. *Save & test* writes a small test file and says
      what is wrong (unknown key, wrong secret, no permission for the bucket or prefix,
      bucket not found, phone clock off, host not reachable). The key needs
      `s3:PutObject`, `s3:AbortMultipartUpload` and `s3:ListMultipartUploadParts` on the
      prefix (and `s3:DeleteObject` to remove the test file).
    - After Stop the app asks: **Submit** (the recording goes into the upload queue) or
      **Delete** (it is thrown away). A recording recovered after a crash asks the same at
      the next start. Submitted recordings go up only over Wi-Fi unless you switch that off, into
      `<prefix>recordings/<id>/` (id: UTC start time + 4 characters, e.g.
      `20261109T140322Z-7f3a`): `events.json`, `pack.csv`, `video.mp4`, `camera.mp4` and
      last `_complete.json` (sizes and SHA-256 of each file), so the pipeline only takes
      complete folders. Big files go in 16 MB parts, three at a time; a dropped connection
      or a closed app resumes where it stopped. Temporary errors are retried (5 s up to every
      10 minutes); a wrong key or missing permission stops it as *Failed* with the reason.
      With *Delete after upload* (on by default) the two videos are removed from the phone
      once everything is up.
    - **⚙ → Recordings…**: every recording with length, cards, hits, size and status
      (waiting for Wi-Fi, uploading 62 %, uploaded, failed: reason). **Retry**, **Upload
      now** (also over mobile data, this one time), **Share** and **Delete** (stops its
      upload; files already in the bucket stay). If the phone's battery saver may stop
      uploads (Xiaomi HyperOS/MIUI, Samsung), it shows a hint once with buttons to the
      battery settings (and Xiaomi's Autostart): allow Card Scanner to run unrestricted.
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
- **Automatically:** every night (see below), and whenever photos are added to `testdata/real/`.
- **Every night:** the **Nightly start** workflow starts the pipeline at 23:40 UTC, right
  after TCGdex's daily price update. GitHub starts scheduled workflows late (hours, at busy
  times), so Nightly start is scheduled three times in the evening (18:17, 20:17, 22:17
  UTC): the first copy that runs waits until 23:40 and starts the pipeline directly (no
  queue); a copy that only runs after midnight starts it at once; the others see tonight's
  run and stop. The pipeline publishes the data pack and starts the Android build at once;
  the 50-minute Cardmarket history backfill runs after that, so it doesn't hold up the APK.
  To start a night run by hand: **Actions → Nightly start → Run workflow** with `now` ticked.

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

- Card images and data come from TCGdex. Cards TCGdex has no picture for (galleries,
  promos, ...) get theirs from pokemontcg.io's open card data where it has one. Card images
  belong to The Pokémon Company.
  They are only cached inside GitHub Actions and are never committed here.
- Prices from TCGdex can be missing or occasionally wrong for some cards (their own
  FAQ says so). Cardmarket's own price-guide download can be added later as a second
  source.
