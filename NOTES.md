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
   - The follower's `while (true) { withFrameNanos { … } }` and the infinite glow transition mean
     the app **never idles**; the Choreographer stays awake at 60 Hz forever.
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
  Auto-repeat key-downs were the subtle part.
- **`NoFocusScrollSpec`** must stay while the follower owns vertical position — removing it lets
  lateral D-pad moves bounce the whole screen.
- **Never prune a row's package on doubt.** `AppRepository.isInstalled()` treats an unknown
  failure as "still installed"; a transient discovery failure used to permanently delete
  leanback-only apps from saved rows.
- **DataStore flows have `.catch`** guards — without them a corrupt prefs file kills the
  collector and settings silently stop updating for the process lifetime.
- **ProGuard rules for kotlinx.serialization** are required and now present. They were a P1
  placeholder promising to add them "in P2"; P2 shipped, they didn't. Only release builds
  minify, so the gap was invisible in debug — and this app is the Home launcher, where a crash
  on boot is awkward to back out of.
- **Artwork bitmap work belongs in `AppRepository`, on a background thread.** Never in
  composition. See §4.
