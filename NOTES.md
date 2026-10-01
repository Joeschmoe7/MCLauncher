# MCLauncher — Engineering Notes

_Consolidated 2026-07-26 from five overlapping documents (`SONNET_GUIDE.md`,
`ICON_OPACITY_BUG_INVESTIGATION.md`, `SCROLL_SMOOTHNESS_INVESTIGATION.md`,
`SCROLL_FIX_PLAN.md`, `wmc-launcher-build-spec.md`), which are now deleted. Where those files
disagreed with each other, this one records what turned out to be **true**, not what was
believed at the time — several of their conclusions were wrong and are corrected below._

**Read §5 before changing tile rendering or scroll behaviour.** It exists because the same
wrong turns were taken repeatedly.

---

## 1. Target and constraints

- **Device:** Walmart onn 4K Google TV box. ~2 GB RAM, weak tile GPU, slow CPU.
  **Renders its UI at 1920×1080 with density 2.0** (so 1 dp = 2 px; a 220 dp tile is 440 px).
- Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material` 1.1.0), Compose BOM 2026.06.00.
- `minSdk 26`, `targetSdk 34`, `compileSdk 36`. Single module, single Activity, no Nav library.
- No network, no analytics, minimal dependencies — the box is slow and the APK should stay small.
- Package: `com.wmc.mediacenter` (release) / `com.wmc.mediacenter.debug` (debug).
  **Using the wrong one with `adb` fails silently.** This cost several confusing rounds.

> **Naming:** the app was renamed **MediaCenter → MCLauncher** on 2026-07-26, but only the
> *display* name, Gradle project, theme and Compose symbols changed. The **`applicationId` and
> Kotlin package deliberately stay `com.wmc.mediacenter`** — changing them makes Android treat
> it as a brand-new app, which wipes the saved rows and settings (DataStore is per-package),
> leaves the old copy installed, and drops the preferred-Home record. So the on-screen name and
> the package name differ on purpose; every `adb` command still uses `com.wmc.mediacenter`.

### Device quirks

- **`animator_duration_scale` defaults to unset on this box, which behaves as 0** — every
  Compose animation *snaps*. If motion suddenly looks like instant jumps, check this first:
  ```
  adb shell settings put global animator_duration_scale 1.0
  adb shell am force-stop com.wmc.mediacenter
  ```
  It does **not** survive a reboot. A permanent in-app fix exists but is not implemented — see
  §6, "MotionDurationScale".
- `/sdcard/Android/data/<pkg>/` is reachable by `adb pull`, but only under the **real** package
  name, and `run-as` only works on debuggable builds.

### ⚠️ Re-run `set-home-activity` after EVERY install

**This is the one to remember.** Installing a new build clears the preferred-Home record, and
the launcher silently stops being Home — it looks intermittent and random, but it isn't.

Google TV Home (`com.google.android.apps.tv.launcherx`) declares its HOME intent filter with
**`priority=2`**; ours is the standard priority 0. **Verified 2026-07-28: priority wins even
against a preferred-activity record** — with our record present (`mAlways=true`, selected over
launcherx), home resolution STILL returned launcherx. `set-home-activity` can therefore never
fix home resolution on this box; whenever the system starts a home app itself (cold boot, or
wake-from-sleep after our process died), launcherx appears. S31 (BootReceiver) covers boot;
S33 (HomeWatchdogService, accessibility) covers everything else — see §2.

**S34 — the "home task" is a separate concept from "which activity is on screen," and BACK
resolves against it.** Android keeps exactly one home-task slot per display (`rootTaskId=1`
here). Whichever app last started via an intent actually carrying `ACTION_MAIN` +
`CATEGORY_HOME` owns that slot, and pressing BACK with no parent activity reveals THAT slot's
occupant — not "whichever app the user tapped from." S31/S33 originally relaunched
`MainActivity` via a bare component intent (`Intent(context, MainActivity::class.java)`, no
category). That renders us on screen looking fully in charge, but registers only an ordinary
background task — launcherx silently keeps owning the home slot from whenever it was last
resolved that way. Verified on-device: the identical intent produces `type=standard` without
the category and `type=home, rootTaskId=1` with it; only the latter makes backing out of a
launched app (Play Store, Settings, ...) correctly return to us. Fix: both receivers now build
`Intent(ACTION_MAIN).addCategory(CATEGORY_HOME)` targeted at `MainActivity`, exactly mirroring
what a genuine Home-button press sends.

**Caveat found while testing S34:** `am force-stop` is not equivalent to the low-memory kill
that happens during sleep — force-stop is a deliberate OS action that also disables
accessibility services (and notification listeners, etc.) as a security measure, wiping
`enabled_accessibility_services` for that component. A real background/OOM kill does not do
this. If you ever force-stop MCLauncher from Settings, re-enable the watchdog afterward (§1).

```bash
# after every install that matters:
adb shell cmd package set-home-activity com.wmc.mediacenter/.MainActivity
adb shell appops set com.wmc.mediacenter SYSTEM_ALERT_WINDOW allow      # S32 cold-boot self-start
adb shell appops set com.wmc.mediacenter MANAGE_EXTERNAL_STORAGE allow  # T2 backup/restore
# S33 wake-from-sleep watchdog (accessibility service; also not survived by uninstall):
adb shell settings put secure enabled_accessibility_services com.wmc.mediacenter/com.wmc.mediacenter.HomeWatchdogService
adb shell settings put secure accessibility_enabled 1

# verify — you want com.wmc.mediacenter.MainActivity, NOT launcherx:
adb shell cmd package resolve-activity --user 0 \
    -c android.intent.category.HOME -a android.intent.action.MAIN | grep name=

# what the system actually has on record:
adb shell dumpsys package preferred-activities | grep -i -A3 HOME
```

Caveats found the hard way:

- `set-home-activity` can print **`Success` while changing nothing** — always verify, don't
  trust the return. If it reports success but `resolve-activity` still shows `launcherx`, add
  `--user 0` to both commands, and check `dumpsys package preferred-activities`.
- The ground truth is simply **pressing Home on the remote**. Trust that over any adb output.
- A **firmware OTA** can re-assert Google TV Home. Nothing the app can do about it.
- There is no code-level fix. Android deliberately requires the user to choose the Home app;
  no manifest flag or permission can claim it.
- Other installed launchers compete for the same slot. This box also has **Projectivy Launcher**
  (`com.spocky.projengmenu`), which declares HOME too and includes a launcher-manager screen
  that can set the default without adb — a useful fallback when `set-home-activity` won't stick.
- Debug and release are **separate packages** (`com.wmc.mediacenter.debug` vs
  `com.wmc.mediacenter`) and both declare HOME. Keeping only one installed removes a whole class
  of confusion.

---

## 2. Architecture

```
MainActivity ──> MCLauncherApp (in-memory screen switch, context menus, confirm dialogs)
                      │
                      ├── HomeScreen        rows of tiles; owns the scroll follower
                      ├── AllAppsScreen     5-column grid
                      ├── EditRowsScreen    ──> EditRowDetailScreen ──> AppPickerScreen
                      └── SettingsScreen
                      
MainViewModel ─ the ONLY place state changes. UI composables are stateless.
   ├── AppRepository          discovery + artwork decode + faded bake (Dispatchers.IO)
   │     └── CustomArtwork    user art from /sdcard/MCLauncher/Artwork, decoded during discovery
   ├── LauncherConfigRepository   rows, JSON in DataStore
   ├── SettingsRepository         prefs in DataStore
   └── BackupRepository       T2 — rows+settings ⇄ /sdcard/MCLauncher/mclauncher-backup.json
                              (survives uninstall; needs the MANAGE_EXTERNAL_STORAGE appop, see §1)
```

**Data model:** `LauncherConfig(rows: List<RowConfig>)`, `RowConfig(id, name, packages)`,
serialized to JSON in DataStore Preferences. `AppInfo` carries pre-decoded `ImageBitmap`s.

**Launcher plumbing that must not be broken:** `ACTION_MAIN` + `CATEGORY_HOME` +
`CATEGORY_DEFAULT`, a separate `LEANBACK_LAUNCHER` filter, `launchMode="singleTask"`,
`stateNotNeeded`, `excludeFromRecents`, and the `<queries>` block for
`CATEGORY_LEANBACK_LAUNCHER` (without it the app list is empty on Android 11+).

**Discovery:** `queryIntentActivities` for `CATEGORY_LEANBACK_LAUNCHER`, falling back to
`CATEGORY_LAUNCHER` for sideloaded non-TV apps. A runtime `BroadcastReceiver` on
`PACKAGE_ADDED/REMOVED/REPLACED` refreshes and invalidates artwork.

### Settings (all in `AppSettings` → `SettingsRepository` → DataStore)

`use24HourClock`, `showAppNames`, `classicStrips`, `glassTiles`, `fadedTiles`,
`preferIconTiles`, `hiddenPackages`, `showHiddenApps`, `showNonTvApps`, `startupPackage`,
`showRecentRow`, `recentPackages`.

Adding one means touching, in order: `AppSettings` → `SettingsRepository` (key + read + setter)
→ `MainViewModel` → `SettingsScreen` row → wire the lambda at the `MCLauncherApp` call site.

---

## 3. Home screen motion (the hard part)

Classic-strips mode (default): only the focused row shows tiles; the rest collapse to titles.
The focused row's **top edge** is pulled to a fixed line at 30 % of viewport height, and the
row opens downward beneath it.

Two independent mechanisms, currently:

1. **Scroll follower** (`HomeScreen.kt`) — a long-lived `LaunchedEffect` running a
   `withFrameNanos` loop. Each frame it reads the focused row's live top from
   `listState.layoutInfo`, computes `error = top - anchorY`, drives a damped spring, and scrolls
   via `dispatchRawDelta`. Has an overshoot clamp (never crosses the anchor in one frame, so it
   is stable at any stiffness) and a settle dead-band.
   Knobs: `FocusedRowAnchorFraction = 0.30`, `FollowerStiffness = 550`,
   `FollowerDampingRatio = 1.25`.
2. **Row expansion** — `animateFloatAsState(rowMotionSpring())`, `RowMotionStiffness = 650`.
   Obeys the OS animator scale; the follower does not.

`NoFocusScrollSpec` disables Compose's own vertical bring-into-view so the follower is the only
thing that moves the list. A per-row `anchorSpec` (`BringIntoViewSpec`) anchors the focused tile
to the strip's left content edge horizontally.

**Current state: Lou reports scrolling as "perfect"** after the artwork fix in §5.1. That fix,
not any motion tuning, is what solved it.

---

## 4. Performance: what was actually wrong

The long-running "scroll is janky" investigation concluded the wall was uniform hardware cost
(~22 ms/frame everywhere) and that the next move was flattening `LazyRow` → `Row`. **That was
wrong**, and acting on it would have been a large, risky refactor for no benefit.

Frame-by-frame analysis of a 59 fps screen capture showed the jank was **not uniform**. Each
transition looked like this (per-frame mean luminance delta; `0.00` = the box repainted an
identical frame):

```
21.89  0.00  0.00  0.00  13.15  0.00  0.00  10.42  0.00  0.00  0.00  9.09
 ^new   dup   dup   dup   ^new   dup   dup   ^new   dup   dup   dup   ^new
=> 68ms, 51ms, 68ms between real frames  ≈ 15-20 fps at the ONSET
```

…followed by a clean, smoothly decaying tail. A steady hardware ceiling produces evenly spaced
slow frames. This was a **burst of one-off work at the start of each transition** — and the
median frame time that the whole investigation was steered by averaged it away completely.

**Root cause:** `fadeBitmap()` was baking the unfocused-tile silhouette **on the main thread,
inside composition**, via `remember(src, faded)`. The S18-era comment claimed it ran "once per
source"; it did not — `remember` is scoped to a composable *instance* and was keyed on the focus
state, so every focus change re-baked, and every tile entering composition baked its own private
copy. One D-pad press = 7–9 full 512×320 ARGB allocations plus software colour-matrix passes in
a single frame.

**Fix:** bake once per package at discovery time on `Dispatchers.IO`; carry the results on
`AppInfo` as `fadedIcon`/`fadedBanner`. Rendering a faded tile is now exactly as cheap as an
unfaded one and a focus change is a pure draw swap.

Also fixed at the same time: the artwork `LruCache` was bounded by **entry count** (128), not
bytes — up to ~80 MB on a 2 GB box. It is now byte-bounded at 24 MB via `sizeOf`, and the
decode cap is derived from the real display density instead of a fixed 512 px.

### Historical numbers (50th percentile frame time while scrolling, debug build)

| state | frame |
|---|---|
| baseline | 42 ms (GPU 19 ms, 46 offscreen RenderTargets) |
| after removing offscreen compositing + baking the background | 38 ms → 22 ms |
| faded tiles OFF (A/B) | 22 ms ← misread as "the fade *shader* costs 16 ms" |
| after moving the bake off the main thread | onset spike gone |

The A/B test that turned faded tiles off was correctly identifying that *something* about faded
tiles was expensive. The wrong inference was **which** something — it was the bake, not the
shader, and S18 moved the bake rather than eliminating it.

---

## 5. Dead ends and root causes — READ BEFORE CHANGING TILES OR MOTION

### 5.1 The YouTube banner: `Canvas.drawBitmap` is density-scaled ⚠️ the big one

**Symptom:** YouTube TV's banner rendered as a ~2× magnified, top-left-anchored crop — the play
button jammed at the bottom of the tile, "Yo" running off the right. Every other app was fine.

**Four wrong theories, each plausible, each disproved:** a white-background/colour-matrix
problem (S19–S21 tuned the matrix four times); a full-bleed vs inset layout problem; a
gravity-bearing wrapper drawable defeating `toBitmap(w, h)`; an oversized source bitmap.

**Actual cause:** `Canvas.drawBitmap(bitmap, left, top, paint)` **multiplies by
`canvasDensity / bitmapDensity`.** `Bitmap.createBitmap()` stamps the *default device* density
on the destination, but a banner arriving as a `BitmapDrawable` is the *resource's* bitmap,
carrying the resource's density. A 2× mismatch magnifies the source 2× from the origin, so only
the top-left quadrant lands in the destination — a silent crop.

**Why only that app:** Hulu's banner is a `LayerDrawable`, so `toBitmap` renders it into a
freshly created bitmap already carrying the default density — no mismatch. The corruption
tracked the **drawable type**, not anything about the artwork. That is exactly why it looked
like a white-background problem for four rounds.

**Fix** (`FadedArtwork.kt`): set `out.density = srcBmp.density`, set
`canvas.density = Bitmap.DENSITY_NONE`, and use the explicit src/dst `Rect` overload, which maps
rect-to-rect and ignores density entirely. **Do not revert to the `(left, top)` overload.**

**The generalisable lesson:** a colour matrix cannot move or resize anything. When artwork is
the wrong *size or position*, the fade code is exonerated by definition — no matter how
suspicious it looks. And: if a bug is wrong in both focused and unfocused states, it is upstream
of anything focus-dependent.

### 5.2 Icon opacity — feature removed, do not reintroduce

A Settings "Icon opacity" preset cycler went through three rendering approaches (whole-tile
alpha; artwork alpha; a frost/gloss/vignette overlay stack). The plumbing was verified correct
end-to-end by dex inspection and on-device screenshots — the *mechanics* were never broken. The
aesthetics never worked, and Lou pulled the feature entirely on 2026-07-20 across nine files.

**Lesson that survived it:** pure alpha over this app's dark background reads as "dim grey," not
glass. Per-tile decoration (frost washes, specular bands, hairline borders) reads as "tinted,"
not WMC.

### 5.3 Tile rendering guardrail

**Never fade artwork *into* an opaque fill.** Three separate attempts died on this: a full-bleed
banner blended into a dark card just looks like a dimmer rectangle.

The pattern that works: a *translucent* glass card plus an additive top-left specular glow, with
the artwork sitting **on top at full opacity**. Light goes behind or around the art, never
through it.

### 5.4 Scroll motion — seven approaches that failed

Do not re-try these:

1. `collectLatest` + `animateScrollBy(tween)` per height emission → cancelled and restarted a
   zero-velocity tween every frame; stop-start chatter.
2. Frame-locked eased "chase" (fixed 320 ms) → made down as clunky as up; fought the expansion's
   separate curve.
3. Centre-based retargeting spring → the expanding strip moved its own centre toward the target,
   starving the scroll into a slow tail (two-phase "expand then drift").
4. Top-edge anchor computed once per keypress with analytic `shrinkAbove` compensation → landed
   rows at inconsistent heights, because the guess differs up vs down.
5. Top anchor re-derived from live layout every frame → the spring chased its own scroll output;
   asymptotic crawl.
6. Proportional (exponential) follower → immediate, but first-frame jerk at low time constants.
7. Critically-damped spring follower ← **current**; stable once the overshoot clamp was added.

**Correction to the old notes:** they concluded the up-vs-down asymmetry was "partly inherent."
It is not — it is an artifact of anchoring to a *live measurement* inside a feedback loop, while
a second, differently-tuned spring moves the thing being measured. See §6 for the design that
removes it by construction, if it ever needs removing.

### 5.5 The focused tile's frame must draw ON TOP of the artwork

In a `Box`, earlier children paint underneath later ones. The lit frame was the first child and
the artwork came later, so a full-bleed banner painted straight over the 2 dp white border.
Shrinking the artwork would have been the wrong fix — WMC's highlight frame sits in front of the
content. The frame and glow are now drawn after `TileArtwork`.

### 5.6 The reflection was removed

S5's mirrored reflection under the focused tile never lined up: the tile scales 1.12× via a
`graphicsLayer` (a *draw-time* transform, so its layout box stays 130 dp), while the reflection
slot below it was laid out unscaled. It read as a detached smudge. Removing it also dropped a
second `TileArtwork` composition and a `BlendMode.DstIn` pass from the busiest tile.

If it returns: it must share the tile's `scale` and be pinned to the **scaled** bottom edge, not
laid out as an independent fixed-height slot.

Its removal is also why `TileLabelTopGap` exists — the 14 dp reflection slot used to absorb the
focused tile's 7.8 dp overhang (`130 dp × (1.12 − 1) / 2`), and without it the tile landed on
the label.

---

## 6. Roadmap / not done

Ranked. Nothing here is required — the launcher is a working daily driver.

1. **`MotionDurationScale` override.** Makes animations immune to the box's
   `animator_duration_scale` quirk (§1) and retires a manual `adb` step that silently
   invalidates any test where it was forgotten:
   ```kotlin
   object FixedMotionDurationScale : MotionDurationScale {
       override val scaleFactor: Float get() = 1f
   }
   // then: LaunchedEffect(...) { withContext(FixedMotionDurationScale) { animatable.animateTo(...) } }
   ```
2. **Cheap GPU wins**, if frame time ever matters again:
   - `wmcBackgroundAnimated` bakes the gradient into a **full-resolution** `ImageBitmap` —
     1920×1080 ARGB = 8.3 MB blitted every frame. It's a smooth gradient; baking at 1/6 scale
     (320×180 = 230 KB) and letting the GPU upscale is visually identical and ~35× less
     bandwidth.
   - `AppTile`'s breathing glow allocates **two `Brush.verticalGradient` objects per frame** —
     the pulse is read inside `onDrawBehind`, so `drawWithCache` caches nothing. Hoist the
     brushes and apply the pulse as `graphicsLayer { alpha = … }`.
   - ~~The app never idles~~ — **DONE 2026-10-01.** Measured first: idle on Home the app drew
     ~63 fps and used ~35% of a core. The glow now settles after 20 s with no key press
     (`rememberGlowPulse` / `LocalGlowPulseActive`, any key wakes it) and the follower suspends
     on a `snapshotFlow` once settled instead of polling `withFrameNanos`. Measured after:
     0 frames, 0% CPU while idle.
3. **Replace the follower with one deterministic transition.** Only worth doing if up-vs-down
   asymmetry resurfaces. Animate a single fractional focused-index `f` with one `Animatable`,
   and derive everything from it as a pure function:
   ```
   expansion(i)  = (1 - |i - f|).coerceIn(0, 1)
   height(i)     = titleHeight(i) + expansion(i) * stripHeight
   stackOffset   = lerp(y(floor f), y(ceil f), frac f)      // index f lands at anchorY
   ```
   Read `f` in the **placement** block of a custom `Layout`, not in composition — Compose then
   invalidates only placement, the cheapest phase, and the nested `LazyRow`s never remeasure
   (which also removes the `dispatchRawDelta` relayout cost without the risky
   `LazyRow` → `Row` flattening the old notes recommended). Symmetry is structural because the
   rule depends only on `|i - f|`; interruption is free via `Animatable`'s velocity continuity.
   Main risk: focus traversal, currently scaffolded by `LazyColumn`.
4. **Don't compose tiles for rows that can't be seen.** Rows with `expansion == 0` draw nothing
   but are still fully composed and measured. Requires making the row itself the focus target
   (`focusable()` + `focusRestorer()`) — closer to real WMC, but a genuine focus rework.

### Known open items

- `TileLabelTopGap = 12.dp` was added late and is **unconfirmed on-device**.
- `SettingsScreen` is a `verticalScroll` — it outgrew the viewport. Newer options are
  unreachable without it.
- Returning Home from another screen re-runs initial focus to the first tile.
- **Not done, on purpose:** Channels "preset" shortcuts — the app's manifest only declares
  `channels://com.getchannels.dvr.app/app` and `/play`; section links like `navigate/Movies`
  are parsed inside the app and can't be enumerated, so presets would be guesses. A Jellyfin
  library picker would need network access, which this app deliberately doesn't have.
  Jump-by-letter in long lists: the remote has no letter keys, menus now scroll, and All Apps
  is a handful of rows.
- `AppRepository.render()`'s intrinsic-size decode was written to fix the YouTube banner and
  did **not** — the cause was §5.1. It is kept because it is still correct for gravity-bearing
  wrapper drawables and costs nothing.

---

## 7. How to measure this app

The single most expensive mistake in this project's history was **guessing instead of
measuring**, repeatedly, over multiple sessions. Every wrong theory in §5.1 was plausible and
survived only because nobody looked at the actual bitmap.

1. **Always measure on `release`.** Debug Compose is materially slower. `installRelease` works
   with no keystore setup.
2. **`framestats`, not percentiles.**
   ```
   adb shell dumpsys gfxinfo com.wmc.mediacenter reset
   # ... exactly 8 down-presses and 8 up-presses at ~1/sec ...
   adb shell dumpsys gfxinfo com.wmc.mediacenter framestats > after.txt
   ```
   The per-frame columns separate *traversal* (composition/measure) from *GPU* (fill). Median
   frame time cannot tell those apart — which is how this project spent sessions optimising the
   GPU while the real cost was on the UI thread.
3. **Screen-record and diff the frames.** This is what proved the jank was an onset spike:
   ```
   adb shell screenrecord --time-limit 10 /sdcard/x.mp4
   ```
   Extract at the native rate (`ffmpeg -vf fps=59`), then compute frame-to-frame mean luminance
   delta. **Duplicate frames (`0.00`) are dropped frames.** Pass/fail signals: duplicate frames
   at transition onset, and variance in transition length between up-moves and down-moves.
4. **Settings toggles are free A/B perf tests.** Turning "Fade unhighlighted tiles" off and
   re-measuring is what first localised the artwork cost.
5. **When artwork looks wrong, dump the actual bitmap.** Temporary scaffolding in
   `AppRepository` — log the drawable's concrete class and every size involved, and write the
   decoded PNG to `getExternalFilesDir()` for `adb pull`. Seeing the real bitmap ended a
   four-theory guessing streak in one look. Removed after use; re-add freely.

**A change that doesn't move the measured signal did not help, regardless of what the median
says.**

---

## 8. Do not regress

- **Selawik font** files live in `app/src/main/assets/fonts/` and are loaded at runtime in
  `Type.kt`. Absent, it silently falls back to sans-serif and the app stops looking like WMC.
- **`ContextMenuOverlay` long-press gate:** it swallows confirm-key events until the first
  key-*up*, so the long-press that opened the menu doesn't instantly select an option.
  Auto-repeat key-downs were the subtle part. A *fresh* key-down (`repeatCount == 0`) arms it
  immediately — otherwise menus opened by a short click (Restore, Delete row, ...) ate the
  user's first OK. The overlay also handles Back itself: left to `BackHandler`, the first Back
  only moved focus out of the scrolling option list.
- **`focusRestorer()` on every screen's main list** (Home's LazyColumn and each LazyRow, All
  Apps, Edit Rows, Edit Row, Settings). Closing a menu removes the focused option; without it
  focus fell to the first visible item, so the next key acted on the wrong card.
- **`NoFocusScrollSpec`** must stay while the follower owns vertical position — removing it lets
  lateral D-pad moves bounce the whole screen.
- **Never prune rows at all.** Rows keep entries for apps that aren't installed;
  `buildUiState` just skips them. Pruning (even "only when confirmed gone") deleted slots
  permanently after a restore onto a box whose apps weren't reinstalled yet, and lost an app's
  place on uninstall/reinstall. `moveWithinRow` steps over the invisible entries.
- **Config edits go through `LauncherConfigRepository.update`** (one DataStore transaction).
  A separate read + save let two quick edits read the same state and lose one.
- **The last Edit Rows / Settings card can't be removed** (`SystemActions.ESSENTIAL`), and a
  config missing one is healed on load — they are the only way back into setup.
- **Auto-backup only follows user edits**, never seeding/heal/restore/reset — backing up a
  fresh install's seed config would overwrite the real backup before it could be restored.
- **Custom artwork is decoded only during discovery, on IO** (`CustomArtwork.rescan`, called
  from `loadInstalledApps`). Lookups (`forName`) are a map read, so `buildUiState` can give
  shortcut cards their art from the main thread. `MainActivity.onResume` compares a cheap
  folder fingerprint and re-runs discovery only when it changed — never decode on resume.
- **Settings cards open with `NEW_TASK | CLEAR_TASK`.** TV Settings keeps a single task; without
  CLEAR_TASK the page lands on top of whatever Settings screen was left open, and Back walks
  into that instead of returning here. Each card has a fallback list ending in
  `ACTION_SETTINGS` (`SystemActions.settingsIntentsFor`); on the onn, `DISPLAY_SETTINGS` and
  `BLUETOOTH_SETTINGS` resolve to nothing, `SOUND_SETTINGS` hits a chooser unless pinned to
  `com.android.tv.settings`, and "Remotes & Accessories" has no activity of its own (it's a
  slice) — `CONNECT_INPUT` (pair accessory) is the closest.
- **"Home pressed while visible" is `onNewIntent` with no `onStop` since the last `onResume`.**
  Android always pauses before delivering a new intent, so checking for RESUMED never fires.
  Returning from another app (or the watchdog bounce) goes through `onStop`, so it keeps the
  user's place; only a Home press on Home resets to the top (`key(homeResetCount)` rebuilds
  HomeScreen, which re-runs initial focus with every row scrolled to its start).
- **DataStore flows have `.catch`** guards — without them a corrupt prefs file kills the
  collector and settings silently stop updating for the process lifetime.
- **ProGuard rules for kotlinx.serialization** are required and now present. They were a P1
  placeholder promising to add them "in P2"; P2 shipped, they didn't. Only release builds
  minify, so the gap was invisible in debug — and this app is the Home launcher, where a crash
  on boot is awkward to back out of.
- **Artwork bitmap work belongs in `AppRepository`, on a background thread.** Never in
  composition. See §4.

---

## 9. Photo wall screensaver (S35)

WMC-style: black background, thick white borders, a virtual "wall" of photos the camera pans
and zooms across, focused photo in color while the rest is desaturated, grouped by capture
date, occasional in-place cross-fade instead of a pan, date label on the focused photo.

**Architecture:** a real Android TV screensaver (`android.service.dreams.DreamService`), not an
in-app overlay — chosen deliberately so it fires system-wide after idle time from anywhere, the
way WMC's own screensaver did. `PhotoWallDreamService` hosts a `ComposeView` by hand-rolling its
own `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner` (this app had never hosted
Compose outside `ComponentActivity.setContent{}` before this) — the documented, still-current
pattern for Compose in a non-Activity host; there is no AndroidX helper that does this
automatically. `isInteractive = false` + `isFullscreen = true` makes any input dismiss the dream
via the framework's own handling, no custom key code needed.

**Rendering (`PhotoWallScreensaver.kt`):** the wall's photo cells are laid out once at natural
size; the "camera" is one `graphicsLayer` transform (scale + translation, `transformOrigin`
pinned to the wall's top-left so the math is a plain `screenCenter = scale·point + translation`)
wrapping the whole thing — panning/zooming is compositor-only work, nothing is ever re-decoded
or re-laid-out. Desaturation is Compose-native (`ColorFilter.colorMatrix`), no bitmap baking
needed unlike `FadedArtwork.kt`'s tile approach — there are only ever a handful of cells, not
dozens of recycled tiles, so the "bake once, cache forever" discipline that mattered for Home
doesn't apply here.

**`MotionDurationScale` fix, finally implemented (see §6's old roadmap item) — but scoped only
to the screensaver**, not retrofitted onto Home's locked follower (out of scope per this
project's own rule). Without it, this box's `animator_duration_scale` resetting to 0 after
reboot would reduce the whole screensaver to instant hard cuts, defeating the point of building
it. `androidx.compose.ui.MotionDurationScale` is the correct interface — **not**
`androidx.compose.animation.core` (that package has no such type; the compiler error is
"Unresolved reference" if you guess wrong, which is what happened writing this). There is also
an `androidx.compose.foundation.FixedMotionDurationScale`, but it's `internal` (Kotlin's own
`basicMarquee` implementation detail) and unreachable from application code — don't chase it.

**Photo source:** a folder Lou points at (default `/sdcard/MCLauncher/Screensaver`,
overridable in Settings), not MediaStore — this is a streaming box, not a phone with a camera
roll. Capture date comes from EXIF `DateTimeOriginal` first, falling back to file
`lastModified()` for anything EXIF-stripped (screenshots, downloads). No new storage permission
needed — `MANAGE_EXTERNAL_STORAGE` was already granted for backup/restore (T2) and covers this
folder too.

**⚠️ This Google TV build's own screensaver picker does not list third-party dreams.**
Google has replaced the generic AOSP "Screen saver" settings with its own "Ambient Screensaver"
(Backdrop) UI at **Settings → System → Ambient mode**, whose "Source" list only ever shows
Google's own options (Google Photos, Art Gallery, Custom AI art) — MCLauncher's dream never
appears there, on this box, no matter how correctly it's registered. This was the biggest
surprise of building this feature and cost real time to discover. The underlying mechanism
still works, though: the classic `Settings.Secure.screensaver_components` value is what
`DreamManagerService` actually reads, `Ambient mode`'s own "Start now" button reads that same
value, and setting it via adb is confirmed to correctly launch our dream even though the
"Source" picker can't see it:
```bash
adb shell settings put secure screensaver_components com.wmc.mediacenter/com.wmc.mediacenter.screensaver.PhotoWallDreamService
adb shell settings put secure screensaver_enabled 1
```
`android.settings.DREAM_SETTINGS` and `android.settings.DISPLAY_SETTINGS` both fail to resolve
on this box's Settings app too (confirmed via `adb shell am start -a ...`); only the bare
`android.settings.SETTINGS` (main Settings screen) reliably resolves, which is what the in-app
"Set as screen saver" row falls back to, with a toast pointing at System → Ambient mode.

**Verified on-device:** date grouping (including the mtime-fallback path — no photo used in
testing had EXIF, all came from Windows screenshots with genuinely distinct file dates), white
borders, black background, date label, pan/zoom framing between multiple different day-groups,
desaturation of unfocused cells (visually confirmed: an unfocused peripheral cell peeking into
frame renders visibly grayscale next to the full-color focused one), day-group rotation,
dismiss-on-any-input, and the missing-folder graceful message (no crash). The
`animator_duration_scale = 0` case was verified by code review + the loop continuing to
function correctly under it, not by catching a mid-transition screenshot — timing that
precisely proved awkward to capture manually, and wasn't worth the time given the mechanism is
confirmed correct by type-checking against the real interface.

---

## 10. Screensaver motion, memory and opt-in (S36)

Follow-on to §9, from a session spent chasing two complaints: "it stutters when scrolling" and
"the pictures look blurry when scrolling". They had different causes, and the blurry one was
misdiagnosed twice before the arithmetic was actually done. Read this before touching
`PhotoWallScreensaver.kt`'s motion constants.

### The blur was never a rendering problem

**It is eye-tracked sample-and-hold smear, and it is a function of content velocity.** When the
eye smoothly tracks something moving across a 60Hz sample-and-hold panel, each frame is held
static for one refresh (16.7ms) while the eye keeps moving, smearing that frame across
`velocity × 16.7ms` of retina. Nothing about how sharply the frame was rendered changes this.

The arithmetic that should have been done on day one, for this wall at density 2.0 on a
1920x1080 surface:

- every cell is the same size, so `focusScale` is a **constant** (~1.45) — a hop between two
  already-focused cells is a pure lateral pan with no zoom at all
- mean hop between two uniformly-random cells on the 8x6 grid is ~2900 wall px, and
  2900 × 1.45 = **~4200 px of screen travel**
- the old duration model gave that ~5.2s, so ~810 px/s mean and ~1250 px/s peak under the easing
- 1250 px/s ÷ 60Hz = **~21 px of smear per frame**; corner-to-corner hops reached ~37 px

The root bug: `panDurationForDistance` measured travel in **wall** coordinates while the thing
that has to stay slow is travel across the **screen**, and the conversion between them is the
camera scale — 0.25 at overview, 1.45 at focus, a 5.8x swing that systematically under-timed
exactly the moves happening at high zoom. Replaced by `panDurationForScreenTravel`, which times a
move by its real screen-space distance against a target velocity.

**`TargetPanScreenVelocityPxPerSec` is the knob.** Smear scales linearly with it. There is no
setting that gives both fast pans and sharp ones on a sample-and-hold panel. The only escape from
that trade is a dolly-out-pan-dolly-in arc (zoom out while traversing, so long hops cross the wall
at low scale), which is not implemented.

Corroboration that this was always the real complaint: slower motion had been asked for twice
before, under a separate heading. "Moves too fast" and "blurry during movement" were one bug.

The camera's zoom is interpolated in **log space** for the same family of reasons. Perceived zoom
rate is `d(ln s)/dt`, so a linear ramp across a 0.25 → 1.45 range is perceived as ~5.8x faster at
the pulled-back end; the pull-back to overview visibly accelerated into its finish, fighting the
easing instead of being shaped by it.

### Dead ends — do not repeat these

1. **`FilterQuality.High` on the `drawImage` calls.** Cannot possibly do anything. On Android,
   Compose's `FilterQuality` collapses to a single boolean — `AndroidPaint.android.kt` reads
   `this.isFilterBitmap = value != FilterQuality.None`, with AOSP's own comment above it: "Framework
   only supports bilinear filtering which maps to FilterQuality.low". Low, Medium and High are the
   same value. There is **no mipmap path through `DrawScope.drawImage` on Android**;
   `Bitmap.setHasMipMap(true)` at decode time is the only lever.
2. **Supersampling, or "the layer is cached at low res and stretched".** It isn't.
   `CompositingStrategy.Auto` only allocates an offscreen buffer when `alpha < 1f` or a
   `RenderEffect` is set; neither `graphicsLayer` here does either, so the wall is re-rasterised at
   final resolution every frame. Verified against the Compose 1.11.3 sources, not assumed.
3. **Per-cell viewport culling** via `drawWithContent` reading camera state. Measured *worse* (73%
   legacy-janky vs a 3.66% baseline) — it makes every cell's draw phase depend on state that changes
   every frame during *any* pan, so all 48 redraw constantly, for a benefit that only ever existed
   during overview, where nothing needs culling anyway.
4. **Chasing frame pacing.** Aggregate numbers were already fine (50th %ile 10ms, 90th 20ms, 1.75%
   legacy-janky). Single captured mid-pan frames look sharp — which is evidence *for* velocity smear
   and *against* a rendering fault, since an aliasing or pacing problem would show in the still.

### Memory: the decode target is the on-screen size, not the screen

`DecodeSizeMultiplier` is a multiple of a photo's **actual focused on-screen footprint** (a cell's
image area × `focusScale`, about 1184x795 here), not of screen resolution. Those are not the same
thing — a photo never fills the screen — and sizing to 1920x1080 decoded ~1.9x more pixels than can
ever be displayed, in every bitmap.

`computeInSampleSize` derives its power-of-two step from the same fit factor `scaleToFit` uses. The
stock AOSP snippet it started as halves only while *both* dimensions stay above their caps, which
stops far too early on extreme aspect ratios: a 12000x1500 panorama failed `750 >= 795` on the first
iteration and decoded at full size — a single **72MB** ARGB_8888 allocation — purely to hand
`scaleToFit` something it immediately shrank to 1184x148.

**`OutOfMemoryError` is an `Error`, not an `Exception`.** Decoding is the one place in this app that
can genuinely exhaust the heap, so `decodeDownsampled` catches it explicitly; a bare
`catch (e: Exception)` there lets one unlucky photo take down the whole DreamService.

Bitmap residency is bounded to the group actually on screen, but **both** the outgoing and incoming
group are briefly live during the reveal dissolve, so peak is about double the steady state. The
eviction (`retainAll`) must therefore run *after* the dissolve completes, not before — evicting
earlier blanks the cells mid-fade.

### Turning it on and off (S36)

The dream is declared `android:enabled="false"` in the manifest and opted into from MCLauncher
Settings. Installing a launcher is not consent to replace someone's screensaver.

**Why a component toggle and not a preference the dream reads:** an app cannot choose which
screensaver Android runs by default, but it can always enable or disable its own components, and
a disabled `DreamService` drops out of the system's list entirely. A
preference the dream merely read would be strictly worse — it would still be listed and selectable,
and "off" would mean it starts and then instantly dismisses itself, which looks like a crash.
`PackageManager.DONT_KILL_APP` is not optional: without it the framework kills the launcher process
to apply the change, at the exact moment the user pressed the toggle.

Two pieces of state must be kept in agreement — the DataStore preference (persisted, backed up,
shown in the UI) and the component's enabled state (real, but not persisted across a reinstall).
Every writer goes through `MainViewModel.applyScreensaverEnabled`. There is a reconcile in `init`
for reinstalls, and **restore-from-backup has to call it too**, since `replaceAll` only rewrites the
preference — otherwise the Settings row reads "On" while the dream stays withdrawn until the
launcher process restarts.

`setComponentEnabledSetting` is a synchronous binder call into PackageManagerService — keep it off
the main thread.

### Selecting it automatically — WRITE_SECURE_SETTINGS

Enabling the component is necessary but not sufficient, and on Google TV it is a dead end on its
own: that build's Ambient mode source list still will not show the dream (§9), so there is no UI
anywhere that can select it.

`WRITE_SECURE_SETTINGS` closes that gap. Its protectionLevel is
`signature|privileged|**development**`, and it is that last flag that lets `adb shell pm grant`
hand it to an ordinary sideloaded app — the same mechanism Tasker and friends rely on. **Verified
working on this box:** `pm grant` succeeds silently and `dumpsys package` then reports
`WRITE_SECURE_SETTINGS: granted=true` for user 0. (A secondary `userId=10` shows `granted=false`;
that is a separate profile and irrelevant.)

With it, `ScreensaverSelection` writes `screensaver_components` and `screensaver_enabled` directly,
so the in-app toggle finishes the whole job. Without it every method there degrades to "no" and the
UI shows the manual commands instead. Installing the app can never acquire it, so this stays opt-in
by construction — which is the point.

Two traps worth knowing:

- **Never record ourselves as the "previous" dream.** `screensaver_components` can already point at
  us (a leftover enable, or someone setting it by hand). Saving that and later "restoring" it aims
  the system at a dream we just disabled — no screensaver at all, rather than the user's old one.
- **Order matters.** Enable the component *before* selecting it, and release the selection *before*
  disabling the component, so the system is never pointed at a disabled dream.

The remembered previous dream lives in DataStore but is deliberately **excluded from
backup/restore** — a component name from one box is meaningless on another, and `replaceAll` must
not clobber it.

### One-time device setup is scripted

`scripts/setup-device.sh` / `.ps1` install the APK and grant all three adb-only permissions
(`SYSTEM_ALERT_WINDOW`, `MANAGE_EXTERNAL_STORAGE`, `WRITE_SECURE_SETTINGS`), create the photo
folder, and are safe to re-run. This exists because the README used to document none of it: a
new user following the old install steps got an app where backup/restore failed, cold-boot
self-start failed, and the screensaver reported "no photos found" no matter what was in the
folder — all three because of ungranted appops with no settings UI to grant them from.

**Grants do not survive an uninstall.** Re-run the script after reinstalling.

### Testing the dream on-device

- **It cannot be force-started via adb on a production build.** `cmd dreams start-dreaming` fails
  with `SecurityException: Must be root`. The only route is to shrink `screen_off_timeout` (e.g. to
  15000) and genuinely wait, untouched, for real device idle. Restore it to `600000` afterwards.
- **Do not poll with `adb shell dumpsys` while waiting for it to trigger.** Repeated calls close
  together were observed resetting the idle timer, so the dream never activates and you get a false
  "the screensaver is broken" scare. Poll sparsely (20s+), or not at all.
- **`adb shell` cannot change this app's component state** (`SecurityException: Shell cannot change
  component state`), so the Settings toggle cannot be exercised from the host — it has to be driven
  through the UI. `pm query-services -a android.service.dreams.DreamService` is how to check whether
  the dream is currently advertised to the system.
- **adb path mangling under Git Bash / MSYS:** `adb shell` commands containing POSIX-looking remote
  paths (`/sdcard/...`) get rewritten into Windows paths unless prefixed with
  `export MSYS_NO_PATHCONV=1`. With that set, `adb push`/`pull`'s *host-side* path must then be given
  in native Windows form (`C:\Users\...`), not `/c/Users/...`. Mixing this up costs a round-trip
  every time.
- Grants (screensaver selection, storage, overlay, accessibility watchdog) all survive
  `adb install -r`; only a full uninstall requires re-applying them.

### Photo source: browsing, copying, and the bundled default set (S37)

Three related gaps, all stemming from the folder being a free-text path box.

**A USB drive was unreachable in practice.** Removable volumes mount at a path derived from the
drive's FAT volume serial (`/storage/1A2B-3C4D`). Nothing on an Android TV displays that string —
the system's Storage settings show a drive's *label*, never its path — so the only way to learn it
was `adb shell ls /storage/`, and then it had to be typed on an on-screen keyboard with a remote.
Replaced by `FolderPickerScreen`. Volume discovery is `Context.getExternalFilesDirs(null)` with the
`/Android/data/<pkg>/files` tail stripped off each entry: that returns one path per mounted volume,
works on every API level here, and needs no permission, unlike `StorageVolume.getDirectory()` which
is API 30+. `StorageManager` is consulted only for friendlier labels.

**`scanFolder` is not recursive**, so picking a drive whose photos live in `DCIM/2024/` gave "no
photos found" with nothing explaining why. The picker shows a photo count per folder, computed with
the same extension rule the scanner uses — a picker that disagrees with the scanner about what
counts as a photo would be worse than none. Count it on the IO dispatcher, not in the list body:
in composition it runs on the main thread on every recompose.

**The drive had to stay plugged in.** `copyPhotos` merges a folder's images into internal storage —
same name and length is treated as the same photo and skipped, so copying twice is harmless and a
second drive adds to the first's photos. Nothing is deleted on either side. Byte copy preserves
EXIF and the modified time is carried over explicitly, so both date sources survive.

**Bundled defaults.** 17 public-domain/CC0 landscapes (national parks via Commons, Earth-from-orbit
and planetary frames via NASA's image library) live in `assets/screensaver/`, extracted lazily into
**app-private** storage when the configured folder scans empty. App-private matters: it needs no
permission, so the default set still works on an install where `MANAGE_EXTERNAL_STORAGE` was never
granted — precisely the box that has nothing set up and most needs a fallback.

Two things worth knowing if the set is ever regenerated:

- **Filter on brightness, not just licence.** Two NASA frames that read beautifully at full size
  (an orbital sunrise, Saturn against black) have mean luminance of 3 and 27 out of 255 — on a wall
  of 48 desaturated cells they are black rectangles. Look at a contact sheet before accepting
  anything. The same pass caught an animal close-up, a scanned slide, and a shot straight up a tree
  trunk that a licence filter happily accepted.
- **Resizing strips EXIF**, so capture dates ship separately in `assets/screensaver/dates.txt` and
  are applied to each file's last-modified time on extraction. Without that every bundled photo
  would caption with the install date, and they would all land in one day-group.

Licences are recorded in `assets/screensaver/CREDITS.txt`. Attribution is not required for any of
them — that is the point of restricting to PD/CC0 — but it is recorded so each one can be verified.
