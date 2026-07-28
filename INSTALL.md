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
| `appops … MANAGE_EXTERNAL_STORAGE` | **Backup & restore** in Settings can read/write its backup file (optional) |

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

Settings has **Back up rows & settings** / **Restore from backup**, which
keep a copy of your setup at `/sdcard/MCLauncher/mclauncher-backup.json` so
it survives an uninstall (updates that change the signing key require one).
Writing to that shared location needs one more grant:

```
adb -s DEVICE_IP:5555 shell appops set com.wmc.mediacenter MANAGE_EXTERNAL_STORAGE allow
```

Without it, both buttons show a message with this command instead of working.
Like the others, it survives reboots but not uninstalls — after reinstalling,
re-run it **before** using Restore.

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
