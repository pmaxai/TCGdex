# Overpriced Cardboard for iPhone

The native iPhone app (Swift and SwiftUI), the counterpart of the Android app in `android/`.
App ID `com.overpricedcardboard.app`, iOS 17 or later, iPhone only.

## How it is built

- **CardCore** (`CardCore/`): the card detector, the four readings of a card and the matcher, in
  C++ on OpenCV (core and imgproc). It is a line-by-line port of the Android app's
  `CardDetector.kt`, `Readings.kt`, `Matcher.kt` and `Recognizer.kt`, so both apps see the same.
- **Bridge** (`Bridge/`): the Objective-C++ layer through which Swift calls CardCore.
- **App** (`App/`): Swift. The fingerprint model runs on ONNX Runtime (Swift package
  `onnxruntime-swift-package-manager`). The catalog is read with SQLite, and the data pack is the
  same one the Android app ships (`pack/`, downloaded from the release `data-pack`).
- **Tests** (`Tests/`): the same comparison with the Python pipeline as Android's `ParityTest`
  (the detector on synthetic photos, recognition of the real test photos, the card search).

The Xcode project is generated from `project.yml` by [XcodeGen](https://github.com/yonaskolb/XcodeGen),
and it isn't stored in Git.

## CI

`.github/workflows/ios.yml` runs on GitHub's macOS runner. It does these steps:

1. It builds OpenCV 4.12 for iOS as `Vendor/opencv2.xcframework` (arm64 device and simulator),
   and caches it.
2. It downloads the data pack into `pack/` and the test photos into `Tests/Fixtures/`.
3. It generates the project, then builds the app for iPhone without signing.
4. It runs the tests on the iPhone simulator. The log shows the lines starting with `ParityTest:`.

## On a Mac

```sh
brew install xcodegen
cd ios
# OpenCV (once, ~10 minutes): see the step "Build OpenCV" in .github/workflows/ios.yml
# the data pack
gh release download data-pack -D pack -p model.onnx -p index.f16 -p catalog.sqlite -p pack.json -p texture.u8
xcodegen generate
open OverpricedCardboard.xcodeproj
```

## Once the Apple developer account is ready

- Set `DEVELOPMENT_TEAM` in `project.yml` to the team ID.
- Register the App ID `com.overpricedcardboard.app`, with the Sign in with Apple capability.
- Create the subscriptions in App Store Connect (the same products as on Google Play).
- Signing and TestFlight uploads from CI need an App Store Connect API key as GitHub secrets.
  They are never put into the repository.
