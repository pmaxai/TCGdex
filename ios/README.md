# Overpriced Cardboard for iPhone

The native iPhone app (Swift and SwiftUI), the counterpart of the Android app in `android/`.
App ID `com.overpricedcardboard.app`, iOS 17 or later, iPhone only. The subscription is sold in the
App Store.

## What it does (first release)

- **Scanner:** the live camera with the guide frame (green when sure), double tap to take a card by
  hand (with the list of the 10 most likely cards), pinch to zoom, the light; in the camera's
  corners the cart (top right) and a photo from the library (bottom right). The printed text
  (Apple's Vision) gives the language and the number, so the right set and language are shown. The
  result shows the printings, the default price with a table of every source (Cardmarket trend,
  30-day average, average sell price, lowest offer; TCGplayer market, mid and low, also in €), the
  price check's notes, the price history, PSA (an add-on) and gem rate, other versions and
  languages, and the alternatives. Holding a printing (Holo, Reverse holo, …) for a second keeps it
  for the next cards, in every mode (🔁 on its button; held again, it's let go): each new card comes
  in that printing when it has it. A card without a picture in its language shows another print's,
  marked "Symbolic picture – English print".
- **Carts and deals (Add to cart):** a popup prices the card for its condition against the vendor's
  price with a verdict (I get or I give); carts per booth, trades, shows with a budget, the deal
  history with CSV.
- **Search:** any card by name (any language), number or set; a card's page has the prices of every
  source, the chart, PSA, + Collection, ☆ Missing and Add to cart.
- **Collection and Missing cards:** + Collection and ☆ Missing on every card, the missing cards
  list. Changes wait on the phone while it is offline.
- **Settings:** the account (Sign in with Apple, an emailed code, or a code from an existing
  account; the plans with the App Store's prices, buying, plan changes, extra devices, Restore
  purchases, Manage subscription, creator codes, Delete account), the default price and price alert,
  price data and add-ons (PSA prices, its own App Store subscription), scanner, carts and deals, about.
- **Nightly prices:** the data pack downloads from the shop (only the changed files), also in the
  background, and is used from the next start.

- **YouTube Mode** (hold Scanner in the bottom bar → YouTube Mode; Creator plan): full screen, a price
  reveal for every card, a HIT at the alert price, the pack's value, ● REC with a log of every card,
  saved in Photos or uploaded to the account's storage.
- **Webshop Mode** (hold Scanner → Webshop Mode; appears once a webshop is connected to the account):
  as on Android. **Stock in**: scan or search a card, see its prices and what the shop holds of it
  (one line per product with price, stock per box and status), pick language (the card switches to
  its version in that language – name, set, picture – when the data has one; other versions and
  languages as in the Scanner), type (N / H / RH / 1st; held for a second: kept for the next cards),
  box (1–999, the last one is remembered), selling price (the shop's, else the market value for the
  condition, never below the minimum selling price) and condition (sky blue: in stock), then
  **Add to stock** / **Create in the shop**, with Undo. A card without a picture in the product's
  language gets the English card's, with a red band "Symbolic picture – English print", or
  **Create Product Image** (a still, cut to the card's edge and straightened) for a photo of its own. **Sell**: each
  card the customer wants goes into the order (🧾, kept on the phone); **Complete sale** takes it out
  of stock in one batch, with Undo. **Settings › Webshop**: host (filled in from the account), key id
  and secret (in the Keychain, never shown again), Save & test, the minimum selling price, the − / +
  step (shared with Carts & deals) and Show Status after Submit (the line after a booking). Every
  request is signed (HMAC-SHA256) and every write keeps its Idempotency-Key on retries, so nothing
  is booked or sold twice.

## How it is built

- **CardCore** (`CardCore/`): the card detector, the four readings of a card and the matcher, in
  C++ on OpenCV (core and imgproc). It is a line-by-line port of the Android app's
  `CardDetector.kt`, `Readings.kt`, `Matcher.kt` and `Recognizer.kt`, so both apps see the same.
- **Bridge** (`Bridge/`): the Objective-C++ layer through which Swift calls CardCore.
- **App** (`App/`): Swift. The fingerprint model runs on ONNX Runtime: Microsoft's iOS
  framework (`Vendor/onnxruntime.xcframework`), called through its C++ API in `Bridge/OnnxModel.mm`.
  Microsoft's Swift package for it disappeared from GitHub on 17 Nov 2026, so CI downloads the
  framework from Microsoft's own download server instead. The catalog is read with SQLite, and the data pack is the
  same one the Android app ships (`pack/`, downloaded from the release `data-pack`).
- **Tests** (`Tests/`), on the iPhone simulator:
  - The same comparison with the Python pipeline as Android's `ParityTest`: the detector, the
    recognition, the printed language and number, the card search, the price data.
  - Sign-in, buying (StoreKitTest with `StoreKit/OverpricedCardboard.storekit`), Restore,
    creator codes, Delete account and the offline queue. These run against `StandIn`, a stand-in
    of the shop's API.
  - Trading mode's numbers.
  - Webshop Mode: the shop API's signing vectors, the client against `FakeShop` (retries with the
    same key, errors, paging, photos, batches), Stock in and Sell, and a round trip on the staging
    shop when the secrets `POKESHOP_STAGING_KEY_ID` / `_SECRET` are there (skipped otherwise).
  - Screenshots of the main screens.

The Xcode project is generated from `project.yml` by [XcodeGen](https://github.com/yonaskolb/XcodeGen),
and it isn't stored in Git.

## CI

`.github/workflows/ios.yml` runs on GitHub's macOS runner. It does these steps:

1. It builds OpenCV 4.12 for iOS as `Vendor/opencv2.xcframework` (arm64 device and simulator),
   and caches it. It downloads ONNX Runtime for iOS (`pod-archive-onnxruntime-c-<version>.zip` from
   download.onnxruntime.ai, checked against its SHA-256) into `Vendor/onnxruntime.xcframework` and
   `Vendor/onnxruntime-headers`, and caches that too.
2. It downloads the data pack into `pack/` and the test photos into `Tests/Fixtures/`.
3. It generates the project, then builds the app for iPhone without signing.
4. It runs the tests on the iPhone simulator. The log shows the lines starting with `ParityTest:`
   and `StoreTest:`. Screenshots appear as base64 between `SHOT-BEGIN` and `SHOT-END`.

Started by hand (Run workflow) with **Upload a signed build to TestFlight**, a second job archives
the app, signs it and uploads it to App Store Connect.

## On a Mac

```sh
brew install xcodegen
cd ios
# OpenCV (once, ~15 minutes): see the step "Build OpenCV" in .github/workflows/ios.yml
# ONNX Runtime: see the step "Download ONNX Runtime for iOS" there
# the data pack
gh release download data-pack -D pack -p model.onnx -p index.f16 -p catalog.sqlite -p pack.json -p texture.u8
xcodegen generate
open OverpricedCardboard.xcodeproj
```

In Xcode, purchases use the stand-in products of `StoreKit/OverpricedCardboard.storekit`. The scheme
sets this.

## Once the Apple developer account is ready

1. **Apple Developer (Certificates, Identifiers & Profiles):** register the App ID
   `com.overpricedcardboard.app`, with the Sign in with Apple capability. Also create a Sign in
   with Apple key for the shop's server, so the shop can revoke tokens when an account is deleted.
2. **App Store Connect:**
   - Create the app, and the subscriptions: the group "Overpriced Cardboard" with Collector and
     Creator, each monthly and yearly. Creator ranks above Collector.
   - Hand the product IDs to the shop team (it enters them in its admin), and put them into
     `StoreKit/OverpricedCardboard.storekit`.
   - Fill in the App Store Server Notifications URLs (the shop doc lists them).
   - Create the shop's in-app purchase key.
3. **For CI uploads:** create an App Store Connect API key with the Admin role (with App Manager, Xcode can't
   use the cloud-managed distribution certificate). Put it into
   GitHub:
   - The secrets `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID` and
     `APP_STORE_CONNECT_KEY_P8` (the .p8 file, base64).
   - The variable `APPLE_TEAM_ID`.

   Then run the workflow with "Upload a signed build to TestFlight". No certificate is stored:
   Xcode signs automatically with the key.
4. **Review:** the review account (`review@overpricedcardboard.com`, see the shop doc) goes into the
   review notes. The privacy policy URL is `https://overpricedcardboard.com/app/privacy`.
