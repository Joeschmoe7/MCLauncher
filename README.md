# MCLauncher — a Windows Media Center launcher for Android TV

A custom Android TV home launcher styled after **Windows Media Center** (Windows 7 era):
a vertical stack of horizontal app "strips," where the focused strip glides to a fixed line
and opens downward while the others collapse to just their titles.

Built for a Walmart **onn 4K Google TV** box (a deliberately low-end target — ~2 GB RAM, weak
tile GPU). It is an app-tile launcher only: no video playback, no metadata scraping, no network
access, no analytics.

![status](https://img.shields.io/badge/status-working%20daily%20driver-brightgreen)

---

## What it does

- Organise your installed apps into named, reorderable rows.
- WMC-style presentation: navy/teal gradient background, Selawik (Segoe UI Light equivalent)
  typography, glass tiles, focus glow.
- Full D-pad navigation — there is no touchscreen on a TV box.
- All Apps grid, Edit Rows screen, per-app hide, uninstall from the launcher, launch-an-app-on-
  startup, and an optional Recent row.
- Everything persists to DataStore; no account, no cloud, no permissions beyond package queries.

## Requirements

- Android TV / Google TV, **API 26+** (`minSdk 26`, `targetSdk 34`)
- arm64-v8a or armeabi-v7a

---

## Install (no development setup)

1. Grab `app-release.apk` from a release build (see below) or from whoever shared it with you.
2. Get it onto the box — **Send Files to TV** (Play Store, install on phone + TV) is the easiest
   route, or `adb install app-release.apk`.
3. Open the file on the TV to install it.
4. Press **Home** → Android asks which launcher to use → pick **MCLauncher → Always**.
   - If no chooser appears (some firmware), sideload **Launcher Manager** and set it there.
5. **Escape hatch:** the launcher's own "Google TV Home" tile switches back, and
   *Settings → Apps → MCLauncher → clear defaults* always works.

---

## Build from source

You need [Android Studio](https://developer.android.com/studio) — it bundles the JDK and SDK.

```bash
git clone https://github.com/Joeschmoe7/MCLauncher.git
cd MCLauncher
./gradlew :app:assembleRelease        # Windows: gradlew.bat :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. The release build is signed with the debug
key on purpose, so `installRelease` works with no keystore setup — fine for a personal
sideloaded app, **not** suitable for Play Store distribution.

### Running on a device

```bash
adb connect 192.168.x.x:5555          # accept the prompt on the TV
./gradlew :app:installRelease
```

Or open the project in Android Studio, pick the box in the device dropdown, and hit **Run**.

> **Benchmark on `release`, never `debug`.** A debuggable Compose build is materially slower —
> no R8, and ART disables optimisations for debuggable processes. Every performance number
> should come from a release build. Note the package differs: `com.wmc.mediacenter` for release
> vs `com.wmc.mediacenter.debug` for debug, which matters for every `adb` command.

### Emulator

Device Manager → Create Device → category **TV** → *Television (1080p)* → a Google TV image at
API 26+. Arrow keys = D-pad, Enter = OK, Esc = Back. A fresh AVD only has stock apps, so seeded
rows will look sparse — use the real box for anything about performance or real artwork.

---

## Project layout

> The Kotlin package is `com.wmc.mediacenter` rather than `mclauncher` — the app was renamed
> after release, and changing the `applicationId` would make Android treat it as a new app,
> wiping saved rows and settings on every existing install. Cosmetic mismatch, deliberate.

```
app/src/main/java/com/wmc/mediacenter/
├── MainActivity.kt          launcher plumbing (CATEGORY_HOME, singleTask)
├── MainViewModel.kt         the single ViewModel; all state changes go through it
├── apps/                    app discovery, artwork decode + faded-silhouette bake
├── data/                    DataStore persistence, config + settings models
└── ui/                      Compose screens; HomeScreen.kt is the interesting one
```

Single module, single Activity, in-memory navigation (no Nav library). Kotlin + Jetpack Compose
for TV (`androidx.tv:tv-material`).

---

## Contributing / modifying

**Read [`NOTES.md`](NOTES.md) first.** It is the engineering log: architecture, the invariants
that must not regress, and — most importantly — a record of the dead ends, several of which cost
multiple sessions and are easy to walk back into. It also documents how to actually measure this
app rather than guessing at it.

## Licence

Personal project. Bundled [Selawik](https://github.com/microsoft/Selawik) font is SIL OFL
(see `app/src/main/assets/fonts/SELAWIK-LICENSE.txt`).
