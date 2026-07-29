# Building an Android TV Launcher That Actually Works

_A playbook distilled from building MCLauncher (2026). Everything here was learned the hard
way on a real device — a Walmart onn 4K Google TV box (~2 GB RAM, weak GPU) — and most of it
was learned by first doing it wrong. Aesthetics are out of scope; being the home screen,
staying the home screen, and moving smoothly are in._

---

## 1. The manifest plumbing (table stakes)

The activity that will be Home needs all of this; each item exists for a reason:

```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"      <!-- Home must never stack copies -->
    android:stateNotNeeded="true"        <!-- system may kill it freely; it must rebuild from scratch -->
    android:excludeFromRecents="true"    <!-- a launcher in the Recents list is nonsense -->
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <!-- SEPARATE filter for the TV app row -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
    </intent-filter>
</activity>
```

Plus, at the manifest root:

```xml
<uses-feature android:name="android.software.leanback" android:required="true" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />

<!-- Android 11+ package visibility: WITHOUT this, queryIntentActivities()
     returns an empty list and your launcher shows nothing. -->
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
    </intent>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
    </intent>
</queries>
```

---

## 2. Becoming Home: what the system actually does (the hard part)

This is the section that cost the most. **On Google TV, the stock launcher
(`com.google.android.apps.tv.launcherx`) declares its HOME intent filter with `priority=2`.
Android clamps third-party `android:priority` to 0. Priority is compared BEFORE
preferred-activity records are consulted, so you cannot win home resolution — ever.**
All of the following were verified true simultaneously on-device, and the stock launcher
still won every fresh resolution:

- `cmd role get-role-holders android.app.role.HOME` → our package
- A preferred-activity record for HOME with `mAlways=true`, "selected from" a set including
  the stock launcher
- Correct manifest (HOME + DEFAULT, exported, singleTask)

Consequences, and the three-layer defense that works:

### Layer 1 — `set-home-activity` (helps the Home button, nothing else)

```
adb shell cmd package set-home-activity --user 0 <pkg>/.MainActivity
```

- It can print `Success` **while changing nothing**. Never trust the return value; verify
  with `dumpsys package preferred-activities` and by pressing the actual Home button.
- **Every install clears the record.** Re-run it after every install, always with `--user 0`.
- Even when the record sticks, it only matters in paths that honor preferences. Fresh
  resolutions (boot, wake with your process dead) ignore it. Do it anyway; it's free.

### Layer 2 — self-start at cold boot (`BOOT_COMPLETED` + BAL exemption)

At cold boot the system starts `FallbackHome` (while locked), then the stock launcher.
Your process is never started at all. Fix: start yourself.

- Manifest receiver for `android.intent.action.BOOT_COMPLETED` with
  `RECEIVE_BOOT_COMPLETED` permission. It **must be `exported="true"`** — protected system
  broadcasts are never delivered to unexported manifest receivers.
- A receiver starting an activity is a **background activity launch (BAL)** and Android 10+
  blocks it — *silently*: no exception, just an `ActivityTaskManager` log line
  (`Background activity launch blocked`). Holding the HOME role does NOT exempt you.
- The practical exemption: declare `SYSTEM_ALERT_WINDOW` and grant its appop once per
  device: `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`. The app never has to
  draw an overlay; holding the grant is what exempts it. There is usually no Google TV
  settings UI for this — adb is the only way.
- Expect a 1–2 second flash of the stock launcher before yours appears. Unavoidable.

### Layer 3 — wake-from-sleep watchdog (accessibility service)

The failure Layer 2 can't cover: box sleeps → low-RAM system kills your process → wake
needs a Home app → fresh resolution → stock launcher wins → user never sees you again
(until they press Home... which may also resolve against you). A `SCREEN_ON` receiver
cannot fix this: it must be runtime-registered, and a dead process is a deaf receiver.

The fix (same technique commercial TV launchers use): an **accessibility service** that
watches for the stock launcher's home window and immediately starts your activity over it.
Why it works where everything else fails:

- The framework **keeps the service bound and re-binds it after your process is killed** —
  it revives you in exactly the failure case.
- The binding raises your process priority substantially (`am kill` refuses), so the
  overnight kill mostly stops happening at all.
- Scope it tight so it isn't creepy and can't be flagged as reading the screen:
  `accessibilityEventTypes="typeWindowStateChanged"`, `packageNames="<stock launcher pkg>"`,
  `canRetrieveWindowContent="false"`, and additionally class-match the home activity only —
  the stock launcher's other surfaces (search results, detail pages, profile chooser) must
  not be hijacked.
- Give users a deliberate way out: if you offer a "go to stock launcher" tile, suppress the
  bounce for a time-boxed window (e.g. 15 min) via a shared in-process flag. Time-boxed,
  not open-ended — a stale flag must not disable the watchdog forever.
- Enable once per device:
  ```
  adb shell settings put secure enabled_accessibility_services <pkg>/<pkg>.YourWatchdogService
  adb shell settings put secure accessibility_enabled 1
  ```
- Caveats: `am force-stop` leaves the app in stopped state and the service will NOT rebind
  until something launches the app again (real memory kills are fine). And **Google Play
  Protect occasionally disables accessibility services of sideloaded apps** — if the
  launcher stops coming back after sleep, check the accessibility toggle first.

### The home-task trap: BACK can reveal the wrong launcher even while yours is on screen

A distinct bug from resolution itself, easy to miss because your launcher looks completely
fine at the moment you cause it: **Android keeps exactly one "home task" slot per display.**
Whichever app last started via an intent actually carrying `ACTION_MAIN` + `CATEGORY_HOME`
owns that slot — and when the user backs out of some other app with nothing left in its own
back stack, the system reveals **that slot's occupant**, not "whichever app the user happened
to tap the other app from."

If your Layer 2/3 recovery code (§2) relaunches your main activity via a bare component intent
— `Intent(context, MainActivity::class.java)`, no category — you render on screen and look
fully in charge, but you've only created an ordinary background task. The stock launcher
silently keeps owning the home slot from whenever it was last resolved that way. The
symptom: everything seems fine, then the user backs out of Play Store, Settings, or any other
app, and lands on the *stock* launcher instead of yours — even though yours was clearly
foreground a minute earlier.

**Fix:** every self-relaunch must carry the same intent shape a genuine Home-button press
would send:

```kotlin
Intent(Intent.ACTION_MAIN).apply {
    setClass(context, MainActivity::class.java)
    addCategory(Intent.CATEGORY_HOME)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
```

Verify with `dumpsys activity activities | grep rootTaskId=1` — your task should show
`type=home` and `visible=true`, the other launcher's `type=home` entry should show
`visible=false`. Confirm the actual fix by opening some other app fresh and backing out of
it; if it returns to you, you're done.

**Testing note:** don't use `am force-stop` to simulate the sleep/OOM kill this defends
against. Force-stop is a deliberate OS action that ALSO disables accessibility services (and
notification listeners) as a security measure — it silently clears the "enabled" record for
any accessibility-based recovery service you built for §2, which then looks like the watchdog
"stopped working" when really the test method broke it. A real low-memory kill during sleep
does not do this. If you need to force-stop for other testing, remember to re-enable any
accessibility service afterward.

### The install ritual

Every install invalidates per-install state. Script this; forgetting one step produces
"intermittent" bugs that aren't intermittent at all:

```
adb shell cmd package set-home-activity --user 0 <pkg>/.MainActivity
adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services <pkg>/<pkg>.YourWatchdogService
adb shell settings put secure accessibility_enabled 1
```

Appops and secure settings survive reboots but **not uninstalls**. A release-signed build
over a debug-signed install requires an uninstall, which also wipes your DataStore — see §6.

### Testing boot behavior

- **`adb reboot` is not a cold boot.** Several behaviors only fail on a true power pull.
  The definitive test is: pull the plug, wait five seconds, plug back in.
- Ground truth for "am I Home?" is pressing the Home button on the remote — with your
  process dead first (`am force-stop` + Home press reproduces the resolution path).
  A Home press while your launcher is already foreground proves nothing: Home-to-current-
  home-task is a no-op and never re-resolves.
- Read the evidence quickly; logcat rotates fast. Useful greps: your boot-receiver tag,
  `Background activity`, `am_proc_start` in the events buffer.
- Firmware OTAs can re-assert the stock launcher and re-enable what you disabled. Nothing
  to do but document it.
- Multi-user: TV boxes can have secondary users (kids profiles). `--user 0` everywhere,
  and know that your app may not exist at all for other users.

---

## 3. Discovering apps

- Query **both** categories and merge: `LEANBACK_LAUNCHER` first (proper TV apps), then
  plain `LAUNCHER` (sideloaded phone apps — Downloader, browsers; missing these is a top
  reason people abandon TV launchers). Deduplicate by package, keeping the leanback entry
  so you get the TV banner. Track which query found each app if you want a "show non-TV
  apps" toggle.
- Use flag `0`, **not** `MATCH_DEFAULT_ONLY` — launcher entry activities don't declare
  `CATEGORY_DEFAULT`, so `MATCH_DEFAULT_ONLY` hides nearly everything.
- Register a runtime `BroadcastReceiver` for `PACKAGE_ADDED/REMOVED/REPLACED` (with the
  `package` data scheme). Manifest registration doesn't work — implicit package broadcasts
  aren't delivered to manifest receivers since API 26. On REPLACED, invalidate that
  package's cached artwork.
- **Never prune saved rows on doubt.** A transient query failure (boot race, PM hiccup)
  looks identical to an uninstall. Before removing a package from saved config, re-confirm
  directly with `getPackageInfo` and only trust a definitive `NameNotFoundException`;
  treat any other failure as "still installed."
- Wrap per-app resolution in a try/catch that skips the app: one malformed, half-uninstalled
  package must not take down discovery for everything else.

## 4. Artwork pipeline (this is also a smoothness section)

- **Do every bitmap operation at discovery time, on a background thread, never in
  composition.** Decode, convert to the UI framework's bitmap type, and pre-bake any
  filtered/tinted variants once per package. The single worst jank bug in this project was
  a color-matrix bake running inside composition on every focus change — one D-pad press
  triggered 7–9 full-size ARGB allocations on the main thread. `remember {}` is scoped to
  a composable instance and is NOT "once per app" — don't let a comment claim otherwise.
- Cache decoded artwork in an `LruCache` **bounded by bytes** (`sizeOf` override), not by
  entry count. 128 entries of large artwork ≈ 80 MB on a 2 GB box.
- Cap decode size at what a tile actually draws at, derived from the real display density —
  many TV boxes render UI at 1080p on a 4K panel. A fixed 512 px cap is 2× oversampled at
  density 1.0 and costs memory and GPU upload bandwidth for nothing.
- **The density-scaling trap** (a four-theory, multi-session bug): `Canvas.drawBitmap(bmp,
  left, top, paint)` multiplies by `canvasDensity / bitmapDensity`. A resource-sourced
  `BitmapDrawable` carries the resource's density; a `createBitmap()` destination carries
  device density. Mismatch = silently magnified, cropped artwork — and only for apps whose
  artwork happens to arrive as a plain `BitmapDrawable`, which makes it look app-specific.
  Fix: match densities explicitly (or `canvas.density = DENSITY_NONE`) and use the explicit
  src/dst `Rect` overload, which ignores density entirely.
- **Render drawables at intrinsic size, then scale the finished bitmap.**
  `Drawable.toBitmap(w, h)` fast-paths only plain `BitmapDrawable`; wrapper drawables and
  gravity-bearing `<bitmap>`s draw at natural size and silently crop when forced into
  foreign bounds. Guard the intrinsic render with a max-pixels cap so a pathological
  drawable claiming 8000 px can't OOM you.
- Fallback chain per tile: banner → icon → text label. Non-TV apps have no banner; the
  chain is what makes them render acceptably.

## 5. Smooth transitions and motion

### Measure first — the meta-lesson

The single most expensive mistake across this whole project was **guessing instead of
measuring**. Every plausible wrong theory survived only until someone looked at the actual
data. Concretely:

- **Measure release builds.** Debug Compose is materially slower and will send you
  optimizing ghosts.
- **`dumpsys gfxinfo <pkg> framestats`**, not median frame times. Framestats separates
  UI-thread cost (measure/layout/composition) from GPU cost; a median can't, which is how
  you end up optimizing the GPU while the real cost is on the UI thread.
- **Screen-record and diff consecutive frames** (extract with ffmpeg at the native rate,
  compute mean per-frame delta; identical frames = dropped frames). This is what
  distinguishes *"uniformly slow"* (a hardware ceiling) from *"a burst of one-off work at
  transition onset"* (a bug you can fix). Medians average the burst away completely.
  Caveat: the encoder shares the GPU, so captures look worse than the panel — use video
  for layout truth and frame-timing analysis, never to eyeball smoothness.
- Settings toggles double as free A/B perf tests — "turn feature X off and re-measure"
  localized the most expensive bug here.
- A change that doesn't move the measured signal did not help, no matter how good the
  theory was.

### The animator-scale trap

Some boxes ship with `animator_duration_scale` unset/0 — **every animation snaps
instantly and it looks exactly like broken code**. It resets on reboot.

- Dev workaround: `adb shell settings put global animator_duration_scale 1.0`
- Permanent in-app fix: run your animations in a context that overrides
  `MotionDurationScale` with `scaleFactor = 1f`, making them immune to the system setting.
  Do this from day one and the whole class of "why did motion break after reboot"
  disappears — and no A/B test is silently invalidated by a forgotten adb step.

### Focus-driven scrolling that feels right

For a rows-of-tiles launcher where D-pad focus drives scrolling (hard-won; each item below
is a failure mode that was actually shipped and reverted):

- **One owner for each axis of motion.** Disable the framework's own bring-into-view
  (a no-op `BringIntoViewSpec`) if you drive vertical position yourself, or lateral D-pad
  moves will bounce the whole screen; give horizontal anchoring an explicit spec so the
  focused tile lands where you want it, not wherever the default scroll puts it.
- **Don't restart a tween per input/measurement emission** — cancelling and restarting a
  zero-velocity animation every frame produces stop-start chatter.
- **Don't anchor a spring to a live measurement of a thing another animation is moving.**
  The spring chases its own output (asymptotic crawl) or starves (two-phase "expand then
  drift"), and up-vs-down asymmetry appears that isn't inherent — it's the feedback loop.
- What worked: a critically-to-over-damped **spring follower** driven per frame
  (`withFrameNanos`), computing error against a fixed anchor line and applying raw scroll
  deltas, with an **overshoot clamp** (never cross the anchor within one frame). The clamp
  is what makes it stable at any stiffness.
- The cleaner design (if starting fresh): animate a single fractional focused-index `f`
  with one `Animatable` and derive every row's height/offset as a pure function of
  `|i − f|`. Symmetry is structural, interruption gets velocity continuity for free, and if
  you read `f` during the layout *placement* phase only, the content never re-measures.
- Fixed-duration row scrolls (~160 ms with a decelerating ease) read as "responsive" for
  horizontal tile steps; springs are for the big vertical moves.
- **Draw focus chrome once, not per tile.** A selection frame drawn by each tile means
  every focus change recomposes/redraws two tiles and any effect on them. A single
  stationary highlight drawn by the row (with tiles gliding beneath it) is both the
  classic-launcher look and dramatically cheaper. Draw the frame *after* (on top of) the
  artwork — full-bleed art will happily paint over a border drawn beneath it.
- **Screen-to-screen transitions:** a short zoom-through (outgoing scales up slightly ~1.05
  and fades ~180 ms, incoming settles from ~0.96 over ~220 ms) reads as polished and is
  cheap. Keep durations under ~250 ms; TV users press buttons fast.

### GPU/composition hygiene on weak hardware

- Offscreen render targets (compositing layers) are poison on weak tile GPUs. Count them
  (`gfxinfo` reports them); every "harmless" `graphicsLayer`/alpha layer adds up.
- Never blit a full-resolution baked gradient every frame — bake backgrounds at ~1/6 scale
  and let the GPU upscale; visually identical for smooth gradients, ~35× less bandwidth.
- Don't allocate brushes/objects inside per-frame draw lambdas; anything read inside the
  draw block defeats `drawWithCache`-style caching. Hoist, and apply per-frame pulse via
  layer alpha.
- Infinite animations plus a per-frame follower loop mean the app never idles and the
  Choreographer runs at 60 Hz forever. Acceptable for a plugged-in launcher, but know
  you're doing it — gate them when not visible if you can.
- Compose everything you can't see? No — *don't compose it at all* if possible: rows that
  are fully collapsed still pay composition and measure. Making the row itself the focus
  target (instead of tiles inside a Lazy list) is the structural fix, at the cost of a
  focus rework.

### Perceived quality details

- If unfocused tiles get a treatment (dim/fade/silhouette), pre-bake it per app at
  discovery (§4). A focus change must be a pure draw swap.
- Pure alpha over a dark background reads as "dim grey," not glass; blending artwork into
  an opaque fill reads as "a dimmer rectangle." Light goes behind or around artwork, never
  through it. (Kept here because it's really a performance note: the cheap approaches that
  don't work tempt you into expensive layered ones that also don't work.)
- A focus-scale transform (`graphicsLayer`) changes draw size, not layout size — anything
  positioned relative to the tile (labels, reflections) must account for the scaled edge or
  it visually collides/detaches.

## 6. Config and data

- DataStore Preferences is fine for both settings (plain keys) and rows (a JSON blob via
  kotlinx.serialization). Simple beats clever at this scale.
- **Every DataStore flow needs a `.catch`** that falls back to defaults on `IOException` —
  a corrupt prefs file must never permanently kill the collector, or settings silently
  stop updating for the process lifetime.
- **ProGuard/R8 rules for kotlinx.serialization are mandatory and easy to forget**, because
  only release builds minify and everything works in debug. For a launcher, the failure
  mode is losing the user's rows on boot. Add the canonical rule set the day you add the
  dependency, not "in phase 2."
- **Backup/restore to a fixed public path** (e.g. `/sdcard/<AppName>/backup.json`) with a
  `schemaVersion` field, refusing files newer than the app understands. Rationale: DataStore
  dies with the package, and your first release-signed build *forces* an uninstall. The
  app-private external dir is wiped on uninstall — useless for this. A fixed public path
  needs "All files access" on Android 11+ (`appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`
  — same adb-only story as the other grants). Make restore a confirmed, destructive-marked
  action; apply restored settings in a single write so collectors see one consistent state.
- Changing `applicationId` (or the signing key) makes Android treat the app as brand-new:
  DataStore gone, preferred-Home record dropped, old copy still installed. Renaming the
  *display* name is free; renaming the package is not — don't.

## 7. Odds and ends that bit anyway

- Debug and release are separate packages if you use an `applicationId` suffix — both
  declare HOME, both compete, and adb against the wrong one "fails" silently. Keep only one
  installed on the test device.
- Sign release with the debug key during development (`signingConfig = debug`) so
  `installRelease` just works — you must measure on release builds (§5), so make them
  one-click.
- Long-press handling on D-pad confirm keys: if a long-press opens a context menu, swallow
  confirm-key events until the first key-*up*, or the press that opened the menu instantly
  activates the first option (auto-repeat key-downs are the subtle part).
- An in-app "switch to the stock launcher" action should fire the generic HOME intent (and
  confirm first) — never hardcode the stock launcher's component.
- `screenrecord`, `input keyevent`, `screencap` + `adb pull` make the whole UI drivable and
  verifiable from a PC — but each round-trip is slow; batch inputs and verify end states,
  not every step.
