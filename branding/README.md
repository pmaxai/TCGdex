# Overpriced Cardboard – app icon

- `opcb-app-logo.png`: the logo as delivered (white card with a dollar sign on #21211F).
- `play-icon-512.png`: the Google Play listing icon (512 × 512, no transparency; Play rounds the corners itself).
- `app-icon-1024.png`: 1024 × 1024 without transparency, for the App Store later.

The Android launcher icon is a vector traced from the logo:
`android/app/src/main/res/drawable/ic_launcher_foreground.xml` (inside the adaptive icon's
safe zone) on `ic_launcher_background.xml` (#21211F), with the same card as the themed icon.
