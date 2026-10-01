# MCLauncher — Installation

A Windows Media Center–style home screen for Android TV / Google TV.

---

## Two ways to use this

**As a normal app — no computer needed.** Install it, open it from your apps
list, and use it. Everything works: your rows, your tiles, launching apps.
Pressing the Home button still takes you to Google TV.

**As your actual home screen — needs a computer, once.** Google does not let
an ordinary app make itself the home screen on Google TV, so this part takes
two typed commands from a computer. You only ever do it once.

Start with Part 1. Do Part 2 later, or never — the app is fully functional
without it.

> **In a hurry, and comfortable with a terminal?** `scripts/setup-device.sh`
> (or `.ps1` on Windows) does the whole of Part 2 in one go — installs, grants
> everything, sets the home screen, enables the watchdog, and creates the
> photo folder. The rest of this page is the same thing done by hand, with an
> explanation of each step. Read on if you'd rather know what you're changing.

---

# Part 1 — Install the app

No computer required.

### 1. Allow app installs

**Settings → System → Apps → Special app access → Install unknown apps**

Turn this on for whichever app you'll use to download the APK (see below).
Exact menu wording varies by device.

### 2. Get the APK onto the device

Any of these work — pick whichever you already have:

- **Downloader** (free, on the Play Store) — enter the release URL directly
- **Send Files to TV** (free) — push the APK from your phone
- A USB stick and any file manager

### 3. Install it

Open the downloaded `MCLauncher.apk` and confirm the install.

### 4. Open it

It appears in your apps list like any other app. That's it — you're done
unless you want it as your home screen.

---

# Part 2 — Make it your home screen (optional)

This part needs a computer on the same network, about 10 minutes, and a
willingness to type two commands. Nothing here is permanent; uninstalling
reverses all of it.

### What the two commands do

| Command | What it changes |
|---|---|
| `set-home-activity` | The **Home button** opens MCLauncher instead of Google TV |
| `appops … SYSTEM_ALERT_WINDOW` | MCLauncher **appears on its own after a power cycle** |
| `appops … MANAGE_EXTERNAL_STORAGE` | **Backup & restore** can read/write its backup file, and **custom artwork** can be read (optional) |

You can do the first without the second. You'll just have to press Home once
after unplugging and replugging the TV.

### 1. Turn on Developer options

On the TV device:

1. **Settings → System → About**
2. Scroll to **Build** (or "Android TV OS build") and **press it 7 times**
3. A message confirms you're now a developer

### 2. Turn on network debugging

**Settings → System → Developer options** → turn on **USB debugging** and
**Network debugging** (sometimes called "Wireless debugging" or "ADB over
network").

If you can't find Developer options, Step 1 didn't finish — go back and keep
pressing Build until the confirmation appears.

### 3. Find the device's IP address

**Settings → Network & Internet** → select your connected network.

It looks like `192.168.1.42`. **Everywhere below that you see `DEVICE_IP`,
type this number instead.**

### 4. Install adb on your computer

`adb` is Google's official tool for talking to Android devices.

1. Download **SDK Platform Tools**:
   https://developer.android.com/tools/releases/platform-tools
2. Unzip it somewhere memorable, e.g. `C:\platform-tools`
3. Open a terminal **in that folder**:
   - **Windows:** open the folder, click the address bar, type `cmd`, Enter
   - **Mac/Linux:** `cd` to the folder

### 5. Connect

```
adb connect DEVICE_IP:5555
```

The first time, a dialog appears on the TV asking whether to allow debugging
from your computer. Tick **Always allow**, choose **OK**. If you miss it, run
the command again.

You should see `connected to DEVICE_IP:5555`.

### 6. Run the two commands

One at a time.

```
adb -s DEVICE_IP:5555 shell cmd package set-home-activity com.wmc.mediacenter/.MainActivity
```

```
adb -s DEVICE_IP:5555 shell appops set com.wmc.mediacenter SYSTEM_ALERT_WINDOW allow
```

**About that second one.** It reads as "allow this app to draw over other
apps," which is worth being cautious about — so here's exactly why it's here.
Android blocks apps from opening themselves in the background, which is what
starting up after a power cycle requires. Holding this permission is the
documented exemption. **MCLauncher draws no overlays and uses it for nothing
else.** Skip it and the app still works; it just won't appear by itself after
you unplug the TV.

Check it took:

```
adb -s DEVICE_IP:5555 shell appops get com.wmc.mediacenter SYSTEM_ALERT_WINDOW
```

Should print `allow`. Any `rejectTime` shown next to it is a record of a past
event and means nothing here.

### 6b. Optional third command — backup & restore

MCLauncher keeps a copy of your setup at
`/sdcard/MCLauncher/mclauncher-backup.json` — written automatically a few
seconds after every change, and on demand from **Back up rows & settings** —
so it survives an uninstall (updates that change the signing key require
one). **Restore from backup** in Settings reads it back. The same folder holds
**custom tile artwork** (`/sdcard/MCLauncher/Artwork/`, see the README).
Using that shared location needs one more grant:

```
adb -s DEVICE_IP:5555 shell appops set com.wmc.mediacenter MANAGE_EXTERNAL_STORAGE allow
```

Without it, both buttons show a message with this command instead of working,
no automatic backups are written, and custom artwork is ignored.
Like the others, it survives reboots but not uninstalls — after reinstalling,
re-run it **before** using Restore.

### 6c. Recommended fourth command — stay the home screen after sleep

Google TV's own launcher outranks any third-party app in the system's home
selection (its priority can't be matched by a normal app). In practice that
means: if the box wakes from sleep and MCLauncher's process happened to be
killed while asleep, **Google TV Home appears instead**. The fix is a tiny
watchdog inside MCLauncher that notices Google TV Home appearing and
immediately returns to MCLauncher. It runs as an accessibility service — it
watches for exactly one thing (the Google TV home window appearing), reads
no screen content, and captures nothing. Enable it with:

```
adb -s DEVICE_IP:5555 shell settings put secure enabled_accessibility_services com.wmc.mediacenter/com.wmc.mediacenter.HomeWatchdogService
```

```
adb -s DEVICE_IP:5555 shell settings put secure accessibility_enabled 1
```

You can also toggle it on the TV under **Settings → System → Accessibility →
MCLauncher home watchdog**. The "Google TV Home" tile inside MCLauncher
still works — the watchdog stands down for 15 minutes when you use it.

Survives reboots, not uninstalls — same as the others. If it ever stops
working (Google Play Protect occasionally switches off accessibility
services for sideloaded apps), just re-run the two commands or re-enable it
in Settings.

### 6d. Optional — the photo wall screensaver

A Windows Media Center–style screensaver: a wall of your photos with white
borders, panning and zooming slowly, the focused photo in color while the rest
stay black and white.

**It ships switched off and does not touch the screensaver you already use.**
Installing a launcher is not a reason to replace one, so you have to ask for
it. There are two steps, and skipping the first is the usual reason it appears
to do nothing.

**1. Put some photos on the box.**

```
adb -s DEVICE_IP:5555 push "C:\my photos\*.jpg" /sdcard/MCLauncher/Screensaver/
```

JPEG, PNG or WebP. A file manager or USB stick works just as well. You can
point it somewhere else under MCLauncher's **Settings → Screensaver photos
folder**. This needs the storage grant from step 6b — without it the
screensaver reports "no photos found" however many photos are in the folder.

**2. Switch it on: MCLauncher → Settings → Photo wall screensaver → On.**

What happens next depends on your TV:

- **If you ran the optional grant below**, that's it. MCLauncher selects
  itself and tells you so. Nothing else to do.
- **On plain Android TV**, finish up in Settings → Device Preferences →
  Screen saver → MCLauncher.
- **On Google TV, there is no way to do it from the TV at all.** That build
  hides third-party screensavers from its own picker — Settings → System →
  Ambient mode only ever offers Google's own options, no matter how correctly
  the app registers. You need one of the adb routes below.

**The optional grant (recommended — do this once and forget it):**

```
adb -s DEVICE_IP:5555 shell pm grant com.wmc.mediacenter android.permission.WRITE_SECURE_SETTINGS
```

This lets MCLauncher select and unselect its own screensaver, so the Settings
toggle does the whole job — including on Google TV. It can only ever be given
deliberately over adb; installing the app can't acquire it. After granting,
open MCLauncher → Settings → **Set as screen saver**.

**Or set it directly, without the grant:**

```
adb -s DEVICE_IP:5555 shell settings put secure screensaver_components com.wmc.mediacenter/com.wmc.mediacenter.screensaver.PhotoWallDreamService
```

```
adb -s DEVICE_IP:5555 shell settings put secure screensaver_enabled 1
```

⚠️ Do step 2 **first**. These commands point the system at a screensaver that
is still switched off inside MCLauncher, and the result is no screensaver at
all — with nothing on screen to explain why.

Switching the toggle back Off hands the screensaver back to whatever you had
before. Survives reboots, not uninstalls, same as the others.

### 7. Test it properly

**Unplug the device's power, wait five seconds, plug it back in.**

Restarting from the menu is not the same test — the behaviour this fixes only
shows on a full power cycle.

You'll see Google TV for a second or two, then MCLauncher takes over. That
brief flash is normal and can't be avoided.

---

## Troubleshooting

**`adb` is not recognized as a command**
Your terminal isn't in the platform-tools folder. Redo Part 2, Step 4.

**`failed to connect` / `unable to connect`**
Check the IP, confirm both devices are on the same network, and make sure
network debugging is still on — some devices switch it off after a reboot.

**Home button works, but a power cycle still lands on Google TV**
The `appops` command. Run the `appops get` check; if it doesn't say `allow`,
run the `set` command again.

**It worked, then stopped after an update**
The `appops` grant survives reboots but **not** an uninstall. If an update
required uninstalling first, re-run both commands from Step 6.

**I want to see what happened at boot**

```
adb -s DEVICE_IP:5555 shell "logcat -d | grep -i MCLauncherBoot"
adb -s DEVICE_IP:5555 shell "logcat -d | grep -i 'background activity'"
```

`allowed because SYSTEM_ALERT_WINDOW permission is granted` — working.
`Background activity launch blocked` — the second command didn't take.

---

## Going back to Google TV

**For a moment:** MCLauncher has a Google TV tile that hands off to the stock
launcher.

**For good:**

```
adb -s DEVICE_IP:5555 uninstall com.wmc.mediacenter
```

or just uninstall it from **Settings → Apps** like any other app. Google TV
becomes your home screen again immediately.

---

## What this does and doesn't touch

MCLauncher is a normal, unprivileged app. It does not root your device,
replace system files, or disable anything. The two optional commands change
two settings Android already supports, and uninstalling reverses everything.
