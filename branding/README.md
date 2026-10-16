# Branding

**Placeholders.** The template's mark is a blank page on the platform's navy, so a new app is recognisably unfinished until it has its own. Replace every file below, keeping the file names and sizes.

| Colour | Hex | Used for |
|---|---|---|
| Navy | `#1F3A5F` | Icon background, launch screen, top bars (the platform family's colour) |
| White | `#FFFFFF` | The page |
| Fold | `#C9D3E0` | The folded corner |
| Lines | `#9AA9BD` | The text lines |

The app's font is **Atkinson Hyperlegible** (bundled in `mobile/composeApp/src/commonMain/composeResources/font/`, licence in `licenses/`); use it for a wordmark too.

## Masters (this folder)

| File | What | Notes |
|---|---|---|
| `mark.svg` | The mark alone, transparent, 108 × 108 | Artwork inside the central 66 × 66 (Android's adaptive-icon safe zone) |
| `app-icon.svg` | The app icon: the mark on navy | Export PNGs from it for the stores: Google Play 512 × 512, App Store 1024 × 1024 without transparency |

## In the app (replace these to change what the app shows)

| File | What it is | How to replace |
|---|---|---|
| `mobile/androidApp/src/main/res/drawable/ic_launcher_foreground.xml` | Launcher icon foreground **and** the launch-screen icon | Android vector drawable, 108 × 108 dp, artwork inside the central 66 dp. Android Studio: File → New → Vector Asset (from your SVG), or Image Asset for a full adaptive icon |
| `mobile/androidApp/src/main/res/drawable/ic_launcher_monochrome.xml` | Themed icon on Android 13+ (tinted by the system) | One-colour vector of the mark, same size rules |
| `mobile/androidApp/src/main/res/values/colors.xml` | `ic_launcher_background` (icon background) and `splash_background` (launch screen) | Colours |
| `mobile/androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` | Adaptive icon definitions that combine the three above | Usually unchanged |
| `mobile/androidApp/src/main/res/values/themes.xml` | The launch screen (AndroidX SplashScreen) | Change the icon or background; an optional `windowSplashScreenBrandingImage` adds a wordmark at the bottom (Android 12+) |
| `mobile/composeApp/src/commonMain/composeResources/drawable/app_logo.xml` | The icon tile on the app's own screens (welcome screen) | Android vector drawable; a PNG with the same name also works (`app_logo.png`) |
