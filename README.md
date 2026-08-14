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
- An optional WMC-style **photo wall screensaver** — off by default, see below.

## Requirements

- Android TV / Google TV, **API 26+** (`minSdk 26`, `targetSdk 34`)
- arm64-v8a or armeabi-v7a

---

## Install

> **Just want to install it on a TV?** [`INSTALL.md`](INSTALL.md) is the click-by-click guide,
> written for someone who has never used adb — including how to turn on developer options, find
> the box's IP, and what each permission actually changes. This section is the short version for
> people already comfortable with a terminal.

Several of MCLauncher's features need permissions that **Android TV provides no settings UI for** —
they can only be granted over adb. The app installs and runs without them, but it can't become
your Home screen, backup/restore fails, and the screensaver finds no photos.

### Recommended: one script, does everything

With [adb](https://developer.android.com/tools/releases/platform-tools) installed and the box
reachable (enable *Developer options → USB/network debugging* on the TV first):

```bash
./scripts/setup-device.sh 192.168.1.50
```

```powershell
.\scripts\setup-device.ps1 192.168.1.50
```

Leave the IP off if the box is already attached over USB or `adb connect`. It is safe to re-run —
and you **will** need to re-run it after any uninstall, since none of this survives one.

The script installs the APK if it finds one at `app/build/outputs/apk/release/app-release.apk`
(override with `APK=…` / `-Apk …`), then:

| Step | Needed for | Without it |
|---|---|---|
| `set-home-activity` | The Home button opening MCLauncher | It stays an ordinary app in the apps list |
| `SYSTEM_ALERT_WINDOW` | Coming back by itself after a power cycle | Box boots to the stock launcher |
| `MANAGE_EXTERNAL_STORAGE` | Backup/restore, screensaver photos | Backup fails; screensaver shows "no photos found" |
| `WRITE_SECURE_SETTINGS` | MCLauncher selecting its own screensaver (**optional**) | You select it by hand — impossible on Google TV, see below |
| Home watchdog | Staying Home after a wake-from-sleep | Google TV Home reappears |

None of these can be acquired by installing the app; every one needs a deliberate adb command.
The watchdog is *appended* to the device's accessibility services, never written over the top —
that setting is a single shared list, and overwriting it would switch off anything else the user
depends on, TalkBack included.

### Without adb

Sideload the APK any way you like — **Send Files to TV** (install on phone + TV from the Play
Store) is the easiest — and open it on the TV to install. It runs fine as an ordinary app from
the apps list.

Google TV does not let an ordinary app make itself the Home screen, so **the Home button will
still open Google TV** and backup, the screensaver, and surviving a reboot won't be available.
[`INSTALL.md`](INSTALL.md) explains the trade-off in full.

> **Escape hatch:** the launcher's own "Google TV Home" tile switches back, and
> *Settings → Apps → MCLauncher → clear defaults* always works.

---

## Photo wall screensaver (optional, off by default)

A recreation of WMC's screensaver: a large virtual wall of your photos in cream mats with printed
date captions, which a slow camera pans and zooms across, blooming the focused photo into colour
while the rest of the wall stays desaturated. Photos are grouped by capture date, so a run of shots
from one day is explored together before the wall dissolves to the next.

It is a real Android TV screensaver (`DreamService`), not an in-app overlay, so it takes over
system-wide after idle time the way WMC's did.

**It ships disabled and does not touch your existing screensaver.** Installing a launcher is not
consent to replace one.

### Turning it on

If you ran `setup-device` above, it's one step: **MCLauncher → Settings → Photo wall screensaver
→ On.** That grant lets MCLauncher select itself, so there's nothing else to do.

Without `WRITE_SECURE_SETTINGS`, MCLauncher can only make the screensaver *available* — Android
decides which one runs, and you have to finish the job:

- *Plain Android TV:* Settings → Device Preferences → Screen saver → MCLauncher.
- *Google TV:* **the Ambient mode source list only ever shows Google's own options and will not
  offer MCLauncher**, however correctly the screensaver is registered. There is no way to select
  it from the UI at all. Either grant the permission —
  ```bash
  adb shell pm grant com.wmc.mediacenter android.permission.WRITE_SECURE_SETTINGS
  ```
  and then use *Settings → Set as screen saver* in MCLauncher, or set it directly:
  ```bash
  adb shell settings put secure screensaver_components com.wmc.mediacenter/.screensaver.PhotoWallDreamService
  adb shell settings put secure screensaver_enabled 1
  ```
  ⚠️ Switch the toggle On *first*. These commands point the system at a screensaver that is
  still disabled inside MCLauncher, and the result is no screensaver at all, with nothing on
  screen to explain why.

Switching it back Off in MCLauncher withdraws the screensaver and hands the slot back to whatever
you had before.

### Photos

**It works out of the box.** 17 landscape photos ship with the app — ten US national parks and
seven from NASA (Earth from orbit, Mars, the Carina Nebula) — and are used automatically whenever
your own photo folder is empty. Every one is public domain or CC0, so redistributing this app
carries no attribution obligation; `app/src/main/assets/screensaver/CREDITS.txt` records the
title, author, licence and source URL for each anyway. They're only unpacked to disk if you
actually have no photos of your own, so they cost nothing if you do.

Your own photos always win. They're read from a plain folder — `/sdcard/MCLauncher/Screensaver`
by default, changeable in Settings. There is no MediaStore scan, no indexing and no cloud account:

```bash
adb push ~/photos/*.jpg /sdcard/MCLauncher/Screensaver/
```

JPEG, PNG and WebP. Capture date comes from EXIF `DateTimeOriginal`, falling back to the file's
modified time for anything EXIF-stripped — that date is what the wall groups by and prints on
each mat.

> Reading this folder needs `MANAGE_EXTERNAL_STORAGE` (see Install). Without it your own photos
> are invisible to the screensaver and it falls back to the bundled set — which lives in
> app-private storage and needs no permission at all.

### Using a USB drive

**Settings → Screensaver photos folder** opens a browser rather than a text box, listing internal
storage and any plugged-in drive, and showing how many photos are directly inside each folder as
you arrow through them. (That count matters: the scan is not recursive, so a drive whose photos
sit in `DCIM/2024/` needs that folder picked, not the drive root.)

Inside a folder on a USB drive you get two choices:

- **Copy these photos to this box** — then you can unplug the drive. Merges rather than replaces,
  so copying twice is harmless and a second drive adds to the first one's photos.
- **Use this folder** — reads straight off the drive, which has to stay plugged in.

> Motion is deliberately slow. On a 60Hz TV, panning quickly across a photo produces visible
> eye-tracking smear no matter how sharply it is rendered — `TargetPanScreenVelocityPxPerSec` in
> `PhotoWallScreensaver.kt` is the trade-off dial if you want it faster and blurrier, or slower and
> crisper. See [`NOTES.md`](NOTES.md) §10 before changing the motion constants.

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
