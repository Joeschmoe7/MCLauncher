package com.wmc.mediacenter.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.wmc.mediacenter.screensaver.DayGroup
import com.wmc.mediacenter.screensaver.DefaultPhotos
import com.wmc.mediacenter.screensaver.ScreensaverPhoto
import com.wmc.mediacenter.screensaver.ScreensaverPhotoRepository
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * S35 — WMC-style photo wall screensaver content, hosted by
 * PhotoWallDreamService. See that file for the DreamService/window-hosting
 * story; this file is pure rendering + scheduling.
 *
 * ARCHITECTURE: a virtual "wall" of [GridCellCount] photo cells laid out at
 * their natural (untransformed) size, wrapped in ONE graphicsLayer that
 * scales+translates the whole thing — the Deep-Zoom-style technique the
 * corrected spec calls for. Content is never re-decoded or re-laid-out to
 * pan/zoom, only the transform changes, so panning/zooming is compositor-only
 * work (see LAUNCHER_PLAYBOOK.md's motion-performance section). transformOrigin
 * is pinned to (0,0) — the wall's own top-left — which makes the camera math
 * a plain `screenCenter = scale * wallLocalPoint + translation`, solved for
 * translation each frame.
 *
 * S35 CORRECTION (against a real captured reference video, not just the
 * text spec): the wall is a much DENSER, REPEATING grid — the day-group's
 * photo pool cycles to fill every cell, so the same handful of photos tile
 * across the whole canvas (frame-extracted from the reference: a 7-photo
 * pool visibly repeating across a 7+x5+ cell grid). This is also what makes
 * "moves in every direction, not just left-right" true almost for free —
 * a genuinely large 2D grid, not a small enclosed one.
 */
@Composable
fun PhotoWallScreensaver(folderPath: String) {
    var scanState by remember { mutableStateOf<ScanState>(ScanState.Loading) }

    val context = LocalContext.current

    LaunchedEffect(folderPath) {
        val groups = withContext(Dispatchers.IO) {
            val repository = ScreensaverPhotoRepository()
            // S37 — fall back to the bundled public-domain set rather than
            // showing "no photos found" on a box nobody has copied anything
            // onto. The user's own folder always wins when it has anything in
            // it, and the bundled photos are only unpacked if this branch is
            // actually reached, so they cost no disk for anyone using their
            // own. See DefaultPhotos.
            repository.scanFolder(File(folderPath)).ifEmpty {
                repository.scanFolder(DefaultPhotos.ensureExtracted(context))
            }
        }
        scanState = if (groups.isEmpty()) ScanState.Empty else ScanState.Loaded(groups)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val state = scanState) {
            ScanState.Loading -> Unit // brief, no flash of "no photos" while the first scan is in flight
            ScanState.Empty -> NoPhotosMessage(folderPath)
            is ScanState.Loaded -> PhotoWall(state.groups)
        }
    }
}

private sealed interface ScanState {
    data object Loading : ScanState
    data object Empty : ScanState
    data class Loaded(val groups: List<DayGroup>) : ScanState
}

@Composable
private fun NoPhotosMessage(folderPath: String) {
    Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No photos found in $folderPath\n\nAdd some, or change the folder in MCLauncher Settings.",
            color = WmcTextPrimary.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

// --- Tunables — starting points; the handoff itself flags exact timing and
// zoom values as an unresolved research gap, same as this app's other
// hand-tuned motion constants. Grid density and the caption layout below
// ARE calibrated against a real reference video frame-extraction, not
// guessed. -----------------------------------------------------------

// S35 correction — a small grid (originally 3x2, then 4x3) only allows a
// couple of row-steps of vertical travel, so random cell-to-cell motion
// reads as "mostly horizontal" no matter how cells are picked, and doesn't
// match the dense, repeating, many-directions canvas seen in the reference
// footage. Cells are cheap (the pool is cycled/repeated to fill them, and
// bitmaps are deduplicated by file — see runShotLoop), so the grid can be
// large without decoding more unique images.
private const val GridColumns = 8
private const val GridRows = 6
private const val GridCellCount = GridColumns * GridRows
private val CellWidth = 420.dp
private val CellAspectRatio = 3f / 2f // landscape photos
private val ImageAreaHeight = CellWidth / CellAspectRatio
// S35 correction — reference frames show the date printed in the mat BELOW
// the photo (Polaroid-style caption), not overlaid on the image. This strip
// is part of each cell's total footprint, used for both the grid spacing
// math and the outer cell size — the image area shrinks to make room for
// it, the cell's outer width/height (and therefore grid spacing) does not
// change shape as a result.
private val CaptionHeight = 40.dp
private val CellHeight = ImageAreaHeight + CaptionHeight
private val CellSpacing = 32.dp
private val WallMargin = 32.dp
private val PhotoBorderWidth = 6.dp
// Warm off-white mat — reference frames show a cream/ivory border, not pure white.
private val PhotoMatColor = Color(0xFFEDE8DF)
private val PhotoCaptionTextColor = Color(0xFF4A4A46)

/** Fraction of the screen's limiting dimension a focused cell fills — leaves a little breathing room around it. */
private const val FocusedZoomFillFactor = 0.86f

/**
 * Floor for the camera scale. Only ever binds on a degenerate (zero-sized)
 * measurement; its job is purely to keep ln()/exp() finite — see baseScale.
 */
private const val MinCameraScale = 0.0001f

// S35 correction #2 (on-device feedback round 1: "moves much too fast,
// should be gentle" + "focused pictures aren't visibly fading in, they're
// already in color"). Slowed the pan and lengthened the fade — necessary
// but not sufficient; see correction #3 below for the actual bug.
private const val HoldDurationMs = 6000
private const val OverviewHoldDurationMs = 5000
// S35 correction #3 (round 2: "still starting in color" + "slow the
// scrolling 10% more" + "some pictures stay a long time, others not very
// long" + "sometimes it changes in place without scrolling"). Two real
// bugs, not just pacing:
//   1. The pan and the color fade ran in PARALLEL, both ~5s — so the exact
//      moment the camera visibly settles is also the exact moment color
//      finishes resolving. There is no separate, legible "it arrived, NOW
//      watch it bloom into color" beat; the two events finishing together
//      just reads as "it was already in color when it got here." Fixed by
//      making the fade start only once the pan has mostly finished
//      (tween's delayMillis) — arrive first, THEN visibly color up.
//   2. Cross-fade-in-place was too frequent (1-in-4 shots) and too quick
//      (900ms), and since the camera never moves for it, a run of them in
//      a row makes one area stay "in focus" for several stacked hold
//      cycles while a single pan-shot elsewhere only gets one — which is
//      exactly the "long on some, short on others" unevenness. Made it
//      rarer and slower, and gave it the same sequential arrive-then-color
//      structure as a full pan.
// S35 correction #4 (round 3: "pixelated during scrolling" + "scrolling far
// across the grid is too fast and jarring" + "fade to color still too
// fast, slow by ~40%"):
//   1. Pixelation — not actually a code bug. The demo photo set was
//      downloaded at a capped 1600px wide to keep the initial fetch small
//      and gentle on Wikimedia's rate limiter. At FocusedZoomFillFactor
//      filling most of the screen, 1600px source detail isn't enough —
//      no amount of decode-cap tuning on our side can recover resolution
//      that was never downloaded. Fixed by re-fetching the set at a wider
//      thumbnail width (see the photo re-fetch, not this file). Bumped
//      DecodeSizeMultiplier too, as a genuine (if secondary) correctness
//      fix and safety margin for whatever Lou's own photos turn out to be.
//   2. "Far across the grid is jarring" — PanZoomDurationMs was a FLAT
//      duration regardless of hop distance. A short adjacent-cell hop and
//      a long diagonal-across-the-wall hop both got the same ~5.5s, which
//      means the long hop has to move at a much higher instantaneous
//      velocity to cover more ground in the same time — reads as a sudden
//      lurch. Replaced with a duration proportional to actual travel
//      distance (computed at runtime from the real wall size, not
//      guessed), clamped so short hops still can't feel like a snap and
//      long hops don't take forever. See panDurationForDistance().
// S35 correction #12 (round 11: "the motion blur is still bad" — after
// round 10's mipmap/decode fix measurably helped the stutter but did NOT
// touch the blur, which disproves the aliasing theory as the blur's cause).
//
// The blur is EYE-TRACKED SAMPLE-AND-HOLD SMEAR, and it is a property of
// content velocity on a 60Hz panel, not of sampling, resolution, or frame
// pacing. When the eye smoothly tracks something moving across a
// sample-and-hold display, each frame is held static for one refresh
// (16.7ms) while the eye keeps moving, smearing that frame across
// (velocity * 16.7ms) of retina. Nothing about how sharply the frame was
// rendered changes this — which is why every sharpness-side fix tried so
// far (FilterQuality, decode size, mipmaps) left it untouched, and why
// captured single frames always looked fine.
//
// The arithmetic on this wall, before this change:
//   - every cell is the same size, so focusScale is a CONSTANT (~1.45) —
//     a hop between two already-focused cells is a PURE LATERAL PAN at
//     1.45x, no zoom at all.
//   - mean hop between two uniformly-random cells on the 8x6 grid is
//     ~2900 wall px => 2900 * 1.45 = ~4200 px of SCREEN travel
//   - panDurationForDistance gave that ~5.2s => ~810 px/s average, and
//     GentleEasing peaks ~1.55x that => ~1250 px/s
//   - 1250 px/s / 60Hz = ~21 px of smear per frame. Corner-to-corner hops
//     reach ~37 px. That is blatant, and it is exactly "trailing/smearing
//     of a single coherent (if fuzzy) picture" — coherent, because unlike
//     aliasing this is a clean directional smear.
//
// The root bug: panDurationForDistance measured distance in WALL
// coordinates while the thing that has to be held slow is SCREEN
// velocity, and one wall px is 0.25 screen px at overview but 1.45 at
// focus — a 5.8x error in exactly the direction that under-times the
// focused pans. See panDurationForScreenTravel(), which times a move by
// how far it actually travels ON SCREEN.
//
// Corroboration that this was always the real complaint: Lou has asked
// for slower motion twice before ("moves much too fast, should be
// gentle"; "slow the scrolling 10% more") and separately reported
// blur-during-movement. Those were never two complaints — they are one.
private const val MinPanDurationMs = 4000
// Raised with TargetPanScreenVelocityPxPerSec (round 12) so the clamp stops
// being what actually sets the speed on medium hops — a clamp that binds is
// a clamp that silently restores the old, too-fast velocity.
private const val MaxPanDurationMs = 13000
/**
 * Target average screen-space travel speed for any camera move. Peak
 * instantaneous velocity under [GentleEasing] is ~1.55x this, so 260 px/s
 * peaks near 400 px/s => ~7px of per-frame eye-tracking smear at 60Hz,
 * down from ~21px. This is THE knob for the blur: smear scales linearly
 * with it. Lower is sharper in motion and slower; there is no way to have
 * both on a 60Hz sample-and-hold panel.
 */
// Round 12: Lou confirms the blur "is not gone but it's better when it's
// slow" — which is the velocity mechanism verified, so this just needs more
// of the same lever. 260 -> 160 alongside a tighter NearbyCandidatePoolSize
// puts peak instantaneous velocity near 250 px/s => ~4px of smear, down
// from ~21px originally and ~7px last round.
private const val TargetPanScreenVelocityPxPerSec = 160f
/**
 * Pan targets are drawn from the N cells nearest the camera rather than
 * uniformly from all 48. Uniform picking made the mean hop ~2900 wall px;
 * since smear is proportional to distance/duration and duration is clamped
 * by [MaxPanDurationMs], long hops could not be slowed enough to be clean.
 * Shortening the hops is the other half of getting velocity down without
 * making every move take forever. Overview shots still reset the camera to
 * the middle, so this wanders rather than getting stuck in one corner.
 */
private const val NearbyCandidatePoolSize = 8
/** Below these, a "move" is a no-op and gets a zero duration — see panDurationForScreenTravel. */
private const val NegligibleScreenTravelPx = 1f
private const val NegligibleLogScaleDelta = 0.01f // ~1% scale change
// S35 correction #5 (round 4: "start the color change after the scrolling
// stops" — was 0.8, i.e. color started blooming while the camera was still
// gliding the last 20% of its travel). 1.0 means the delay equals the
// pan's own full duration, so saturation only starts once camera motion
// has completely finished — no overlap between "still moving" and "now
// coloring up."
private const val SaturationArriveDelayFraction = 1.0f
// Was 2200, then 3080 (+40%, round 3); now +50% more per Lou's explicit
// request (round 4): 3080 * 1.5 = 4620.
private const val SaturationDurationMs = 4620
private const val CrossfadeSwapHalfDurationMs = 1800 // was 900 — matches the wall's new gentle character instead of snapping

/**
 * A gentle, symmetric ease-in-ease-out (slow to start AND slow to finish) —
 * deliberately NOT HomeScreen.kt's LinearOutSlowInEasing, which is tuned for
 * snappy UI navigation (full speed immediately). Ambient screensaver motion
 * wants the opposite character throughout.
 */
private val GentleEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

private const val ProbOverview = 0.10f
// S35 correction — was 0.25 (1-in-4 shots); combined with the camera never
// moving for this shot type, a run of them in a row made one area of the
// wall stay "in focus" for several stacked hold cycles while other shots
// only got one. Halved so it reads as an occasional variety beat, not a
// recurring pattern.
// S35 correction #13 (round 12) — DISABLED. Lou has now reported this
// behaviour as unwanted twice: round 2 ("sometimes it changes in place
// without scrolling") and round 12 ("once in a while the photo in focus
// changes, without the screen scrolling to another photo"). Round 3's
// response was to halve the rate rather than remove it, and the complaint
// came straight back — so it isn't a frequency problem, the shot type
// itself reads as a malfunction rather than as the variety beat it was
// designed to be. The branch in runShotLoop is deliberately left in place
// (it becomes unreachable at 0f): restoring this is a one-constant change
// if Lou ever wants it back.
private const val ProbCrossfadeInPlace = 0f
// Remainder (0.90) is pan-to-a-new-cell.

private const val DayGroupDwellShots = 5

/**
 * S35 correction #12 (round 11: "one big stutter after it zooms all the way
 * out to grid and starts to zoom in"). Every day-group transition ends at
 * the overview and is immediately followed by the new group's first shot,
 * which is a zoom-in 78% of the time — so this describes the group
 * transition specifically, not overview shots in general.
 *
 * Round 9's preload fix reasoned that decoding before any camera movement
 * meant decoding "while still zoomed on a single cell (cheap)". Two holes
 * in that: (a) when the previous group's last shot was an overview, nothing
 * is focused and the preload burst happens with all 48 cells visible; and
 * (b) more importantly, moving the DECODE earlier does not move the GPU
 * TEXTURE UPLOAD, which happens on the first frame that actually draws each
 * new bitmap. With 48 cells re-pointed at 16 brand-new bitmaps, that upload
 * burst lands on the opening frames of the zoom-in — plus, since round 10,
 * mipmap generation for all 16 on top of it.
 *
 * This holds the wall still at the overview after the swap, so those
 * uploads and mip chains are paid while nothing is moving. It doubles as
 * the "reveal a fresh wall" beat correction #7 wanted anyway.
 *
 * S35 correction #13 (round 12) — shortened, because the staggered dissolve
 * below now provides most of the still-at-overview window this was creating.
 */
private const val GroupRevealSettleMs = 900

/**
 * S35 correction #13 (round 12: "the screen is still jumping — more than a
 * stutter — right after it zooms out and starts to zoom in").
 *
 * Not a performance problem at all: the day-group transition reassigned all
 * 48 cells' photos in one statement, so the ENTIRE wall changed contents in
 * a single frame. Correction #7 believed it had fixed this by pulling back
 * to the overview before swapping, but that only moved the hard cut — at
 * overview all 48 cells cut at once, which is more visually violent than
 * the single focused cell it was trying to protect, not less.
 *
 * Dissolved instead, reusing the per-cell previousPhoto/crossfadeProgress
 * machinery correction #7 already built for the in-place swap. Staggered
 * across the wall so it reads as a deliberate wave rather than a global
 * flash, and so only a handful of cells are re-drawing on any given frame
 * instead of all 48. The dissolve also gives the incoming textures a
 * static, camera-still window in which to upload — the same job
 * GroupRevealSettleMs was doing, now with something to look at.
 */
private const val GroupRevealDissolveMs = 1400
private const val GroupRevealStaggerMs = 22

// S35 correction #5 (round 4: "stuttering a lot on the scrolling"). The
// round-3 pixelation fix replaced the photo set with much higher native-
// resolution originals (2000-13000px wide, up from a 1600px cap) — but
// DecodeSizeMultiplier was ALSO bumped to 3x screen resolution that same
// round, as a defensive margin that turned out to be unnecessary once the
// source photos themselves stopped being the bottleneck. Combined, decode
// targets got large enough that computeInSampleSize barely downsamples the
// biggest source files at all — e.g. a 12960px-wide original decodes to
// something like 100+MB as a single ARGB_8888 bitmap, and up to ~16 of
// those can be resident at once per day-group. That's GC/memory pressure
// severe enough on this box's hardware to visibly stutter the pan
// animation, even though decode itself runs on Dispatchers.IO. Dialed back
// to a modest headroom over actual display resolution — plenty for the
// camera's zoom-in now that source detail is no longer the constraint.
//
// S35 correction #8 (round 7 — measured, not guessed, per
// LAUNCHER_PLAYBOOK.md §5's "measure first" lesson: `dumpsys gfxinfo
// com.wmc.mediacenter` showed 26.86% legacy-janky frames and GPU texture
// memory at 360MB against a 398MB budget — 90% of the ceiling, with
// "Slow bitmap uploads" flagged in the same dump. 1.5x screen resolution
// was still large enough to keep the GPU texture cache right up against
// its limit, causing eviction/re-upload churn on every newly-focused
// cell. Dropped to 1.0x: FocusedZoomFillFactor is already 0.86, so a
// decode matching plain screen resolution still has ~16% headroom before
// any upsampling would be visible, without living at the memory ceiling.
//
// S35 correction #12 (round 11) — this is now a multiple of the photo's
// ACTUAL FOCUSED ON-SCREEN SIZE, not of screen resolution. Those are not
// the same thing and never were: a photo never fills the screen, it fills
// one cell's image area at focusScale, which on this box is ~1184x795 —
// not 1920x1080. Sizing to screen resolution decoded ~1.9x more pixels
// than can ever be displayed, in every single bitmap.
//
// At 1.0f a focused photo now lands at mip level 0 (displayed 1:1, as
// sharp as this pipeline can be) while the overview draws it from a
// properly filtered lower mip, and residency per day-group drops from
// ~112MB to ~60MB — which also halves the upload burst that
// GroupRevealSettleMs has to absorb.
private const val DecodeSizeMultiplier = 1.0f

// S35 correction — reference frames show a short numeric date ("5/8/2025"),
// not a spelled-out month.
private val DateLabelFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d/yyyy")

/**
 * S6-roadmap fix (NOTES.md §6), applied here rather than to Home's locked
 * motion (out of scope per this project's own rule): this box's
 * animator_duration_scale can reset to 0 after a reboot, which would reduce
 * the ENTIRE screensaver to instant hard cuts — directly defeating the
 * point of building it. Wrapping the shot-scheduling coroutine in this
 * context makes Compose's animateTo calls immune to that system setting.
 */
private object FixedMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

private class CellState {
    val saturation = Animatable(0f)
    // S35 correction #7 (round 6) — 1 = fully showing `photo`; ramped from
    // 0 during an in-place swap to dissolve directly between `previousPhoto`
    // and `photo`. Replaces the old alpha-of-the-whole-cell approach, which
    // faded the mat/border to near-transparent too and exposed the black
    // wall background behind it — see PhotoCell.
    val crossfadeProgress = Animatable(1f)
    // S35 correction #14 (round 13: "when it zoomed all the way out, all of
    // the photos flipped to color, then it looked like the pictures all
    // changed but it was hard to tell"). PhotoCell used to draw the outgoing
    // photo at a hardcoded saturation = 1f. That was true for correction
    // #7's in-place swap — the only thing that ever dissolved was the
    // FOCUSED cell, which is fully colored by definition — but correction
    // #13 reused the same machinery for the whole-wall group dissolve, where
    // all 48 cells are DESATURATED at the overview. The hardcoded 1f made
    // every cell snap to full color the instant the dissolve began, then
    // fade back to the incoming grayscale photo: a wall-wide color flash
    // that also swamped the photo change it was supposed to be showing.
    // The incoming photo's own `saturation` Animatable gets reset to 0 for
    // its bloom, so the outgoing value has to be captured separately.
    var previousSaturation by mutableStateOf(1f)
    var photo by mutableStateOf<ScreensaverPhoto?>(null)
    var previousPhoto by mutableStateOf<ScreensaverPhoto?>(null)
}

@Composable
private fun PhotoWall(dayGroups: List<DayGroup>) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        val cellWidthPx = with(density) { CellWidth.toPx() }
        val cellHeightPx = with(density) { CellHeight.toPx() }
        val spacingPx = with(density) { CellSpacing.toPx() }
        val marginPx = with(density) { WallMargin.toPx() }

        val wallWidthPx = marginPx * 2 + GridColumns * cellWidthPx + (GridColumns - 1) * spacingPx
        val wallHeightPx = marginPx * 2 + GridRows * cellHeightPx + (GridRows - 1) * spacingPx

        // Cell rects in wall-local px — computed once per size, reused for
        // every shot regardless of which day-group is showing.
        val cellRects = remember(wallWidthPx, wallHeightPx) {
            List(GridCellCount) { i ->
                val col = i % GridColumns
                val row = i / GridColumns
                val left = marginPx + col * (cellWidthPx + spacingPx)
                val top = marginPx + row * (cellHeightPx + spacingPx)
                Rect(left, top, left + cellWidthPx, top + cellHeightPx)
            }
        }

        // S36 review fix — coerced away from zero because the camera is
        // animated in LOG space: ln(0f) is -Infinity, exp() of that is 0, and
        // animateTo across an infinite endpoint yields NaN, so a single
        // zero-sized measurement (plausible in a DreamService, which has no
        // window at all until onAttachedToWindow) would render nothing at
        // all. Paired with keying the camera Animatables below, so a later
        // correct measurement actually repairs it instead of being ignored.
        val baseScale = min(screenWidthPx / wallWidthPx, screenHeightPx / wallHeightPx)
            .coerceAtLeast(MinCameraScale)
        // Every cell is the same size, so this is a constant for the whole
        // wall — which is also why a hop between two already-focused cells
        // involves no zoom at all, just lateral travel (see correction #12).
        val focusScale =
            min(screenWidthPx / cellWidthPx, screenHeightPx / cellHeightPx) * FocusedZoomFillFactor

        // S35 correction #11 (round 10) — the camera's zoom is animated in
        // LOG space, not linear scale. Perceived zoom rate is d(ln s)/dt, so
        // a linear ramp across this wall's 0.25 -> 1.45 range (a 5.76x
        // span) is perceived as ~5.76x FASTER at the pulled-back end than at
        // the focused end. On the pull-back to overview that means the
        // motion visibly ACCELERATES into its finish, fighting GentleEasing
        // instead of being shaped by it — which is what "much worse when
        // zooming in and out of the grid" was describing. It also feeds the
        // blur complaint: fast screen-space motion on a 60Hz sample-and-hold
        // panel IS perceived motion blur, independently of texture sampling.
        // Interpolating ln(scale) makes a constant-rate zoom actually look
        // constant-rate, so GentleEasing's ease-in-ease-out is what the eye
        // sees. Everything downstream reads exp() of this — see the wall's
        // graphicsLayer below.
        //
        // S36 review fix — keyed on the geometry they are derived from. These
        // were unkeyed while the sibling `cellRects` above IS keyed, so a
        // changed measurement would rebuild the cell layout while the camera
        // kept values computed for the old one. `runShotLoop`'s LaunchedEffect
        // keys on these instances so it restarts against the new ones rather
        // than driving orphaned Animatables.
        val camLogScale = remember(wallWidthPx, wallHeightPx, screenWidthPx, screenHeightPx) {
            Animatable(ln(baseScale))
        }
        val camX = remember(wallWidthPx) { Animatable(wallWidthPx / 2f) }
        val camY = remember(wallHeightPx) { Animatable(wallHeightPx / 2f) }

        val cellStates = remember { List(GridCellCount) { CellState() } }
        val bitmaps = remember { mutableStateMapOf<File, ImageBitmap?>() }

        // S35 correction #12 — size the decode to the photo's largest ACTUAL
        // on-screen footprint (a focused cell's image area, which is the cell
        // minus its mat border and caption strip, magnified by focusScale),
        // not to screen resolution. See DecodeSizeMultiplier.
        val borderPx = with(density) { PhotoBorderWidth.toPx() }
        val captionPx = with(density) { CaptionHeight.toPx() }
        val imageAreaWidthPx = cellWidthPx - borderPx * 2
        val imageAreaHeightPx = cellHeightPx - borderPx - captionPx
        val maxDecodeWidth = (imageAreaWidthPx * focusScale * DecodeSizeMultiplier).toInt()
        val maxDecodeHeight = (imageAreaHeightPx * focusScale * DecodeSizeMultiplier).toInt()

        LaunchedEffect(dayGroups, cellRects, camLogScale, camX, camY) {
            withContext(FixedMotionDurationScale) {
                runShotLoop(
                    dayGroups = dayGroups,
                    cellRects = cellRects,
                    cellStates = cellStates,
                    bitmaps = bitmaps,
                    camLogScale = camLogScale,
                    camX = camX,
                    camY = camY,
                    baseScale = baseScale,
                    focusScale = focusScale,
                    wallWidthPx = wallWidthPx,
                    wallHeightPx = wallHeightPx,
                    maxDecodeWidth = maxDecodeWidth,
                    maxDecodeHeight = maxDecodeHeight
                )
            }
        }

        Box(
            modifier = Modifier
                .size(with(density) { wallWidthPx.toDp() }, with(density) { wallHeightPx.toDp() })
                .graphicsLayer {
                    val scale = exp(camLogScale.value)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = screenWidthPx / 2f - scale * camX.value
                    translationY = screenHeightPx / 2f - scale * camY.value
                }
        ) {
            cellRects.forEachIndexed { index, rect ->
                val cell = cellStates[index]
                val photo = cell.photo ?: return@forEachIndexed
                PhotoCell(
                    photo = photo,
                    bitmap = bitmaps[photo.file],
                    previousBitmap = cell.previousPhoto?.let { bitmaps[it.file] },
                    cell = cell,
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = rect.left
                            translationY = rect.top
                        }
                        .size(CellWidth, CellHeight)
                )
            }
        }
    }
}

/**
 * S35 correction #6 (round 5 — LAUNCHER_PLAYBOOK.md's §4/§5 lessons applied
 * here: the exact "color-matrix bake running inside composition" jank
 * pattern that was the worst bug in the main launcher). Previously
 * saturation/cellAlpha were read as plain Float PARAMETERS one level up, in
 * PhotoWall's forEachIndexed loop — a snapshot-state read in a composable
 * body invalidates that whole composable scope, so every animation frame
 * recomposed the ENTIRE 48-cell loop, not just the one animating cell. With
 * round-3/4's slower, longer pan+saturation durations (a single shot's
 * animating window can now span ~13s end to end), that full-grid
 * recomposition was running continuously for most of the hold cycle —
 * exactly the kind of jank the playbook's fix targets. Fixed by taking the
 * CellState itself (a stable reference) and reading .value only inside
 * deferred phases scoped to just this cell: alpha inside graphicsLayer
 * (relayout only, no recomposition at all), saturation inside a Canvas
 * draw lambda (redraw only). Neither triggers composition — matches
 * HomeScreen.kt's row-alpha technique the playbook calls out directly.
 *
 * The date caption is always rendered (not gated on focus) — a real printed
 * photo carries its own caption regardless of whether anyone's looking at
 * it; matches the reference video, where the grid's tiny thumbnails also
 * carry (illegible at that scale, but present) captions.
 *
 * S35 correction #9 (round 8, ATTEMPTED then REVERTED: "much worse when
 * zooming in and out of the grid"). Tried per-cell viewport culling via
 * drawWithContent reading camScale/camX/camY to skip off-screen cells —
 * measured WORSE (73% legacy-janky over a window that included overview
 * shots, vs. 3.66% baseline): making every cell's OWN draw phase depend on
 * the camera state, which changes every frame during ANY pan (not just
 * overview), forced all 48 cells to redraw every frame of every camera
 * animation — previously only the single outer graphicsLayer read that
 * state, and children's cached layers were just repositioned by the
 * compositor for free. Net effect: no benefit during overview (all 48 ARE
 * genuinely visible there, nothing to cull) and a regression during every
 * normal pan (only 1-2 cells are visible, and those were already cheap via
 * plain GPU clipping — this fix redrew them AND the invisible ones anyway).
 * Reverted; see NOTES.md for what's still worth trying for the overview
 * case specifically (real per-frame compositing cost of 48 simultaneous
 * layers, not a draw-skipping problem).
 */
@Composable
private fun PhotoCell(
    photo: ScreensaverPhoto,
    bitmap: ImageBitmap?,
    previousBitmap: ImageBitmap?,
    cell: CellState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(PhotoMatColor)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = PhotoBorderWidth, top = PhotoBorderWidth, end = PhotoBorderWidth)
                .background(Color.Black)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Outgoing photo (already full color) fades out as the
                // incoming one fades in on top — a true dissolve, so the
                // opaque mat/border never has to hide anything and the
                // black backdrop behind the cell is never exposed.
                if (previousBitmap != null) {
                    drawPhotoFitted(
                        previousBitmap,
                        saturation = cell.previousSaturation,
                        alpha = 1f - cell.crossfadeProgress.value
                    )
                }
                if (bitmap != null) {
                    drawPhotoFitted(bitmap, saturation = cell.saturation.value, alpha = cell.crossfadeProgress.value)
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(CaptionHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = photo.dateTaken.format(DateLabelFormatter),
                color = PhotoCaptionTextColor,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Fit-scales [bitmap] into the current draw bounds (matches Image()'s
 * default ContentScale.Fit), applying [saturation] and [alpha].
 *
 * S35 correction #10 (round 9, ATTEMPTED then REVERTED: "drastically out
 * of focus during movement"). Tried FilterQuality.High, theorizing the
 * default Low was under-sampling during the camera's scale animation —
 * Lou confirmed zero visible difference, and closer questioning revealed
 * the artifact reads as actual motion blur/trailing (not static softness),
 * confirmed screensaver-specific (not a TV motion-smoothing setting).
 * Inspecting mid-transition frames directly (adb screencap burst during an
 * active pan) showed individual frames are reasonably sharp on their own —
 * consistent with this being a frame-pacing/consistency issue during the
 * animation rather than a sampling-resolution one, which FilterQuality
 * can't address. Reverted to the default (no measured or perceived
 * benefit, and it's not free — mipmap sampling costs a little extra GPU
 * time on every animating frame for nothing in return).
 */
private fun DrawScope.drawPhotoFitted(bitmap: ImageBitmap, saturation: Float, alpha: Float) {
    val fitScale = min(size.width / bitmap.width, size.height / bitmap.height)
    val drawWidth = bitmap.width * fitScale
    val drawHeight = bitmap.height * fitScale
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(
            ((size.width - drawWidth) / 2f).roundToInt(),
            ((size.height - drawHeight) / 2f).roundToInt()
        ),
        dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
        alpha = alpha,
        colorFilter = saturationFilterFor(saturation)
    )
}

/**
 * S36 review fix — [drawPhotoFitted] runs inside a draw lambda, so building
 * `ColorMatrix().apply { setToSaturation(..) }` and `ColorFilter.colorMatrix`
 * there allocated a FloatArray(20), a Compose ColorFilter AND a native
 * ColorMatrixColorFilter on every single redraw: ~240 native objects/second
 * during an ordinary saturation animation, and a burst of ~96 per frame
 * during the 48-cell group-reveal dissolve — exactly the moment the rest of
 * this file is working hardest to keep frames cheap.
 *
 * Saturation is a continuously animating float, so there is no single
 * instance to hoist; instead it is quantised to [SaturationFilterSteps]
 * buckets (a ~1.5% step, far below anything visible in a slow colour bloom)
 * and the filters are built once and reused. Fully-saturated draws return
 * null, which skips the colour-matrix shader entirely and lets Skia take its
 * fast opaque-image path.
 *
 * Only ever touched from Compose's draw phase on the main thread, so the
 * lazy fill needs no synchronisation.
 */
private const val SaturationFilterSteps = 64
private val saturationFilters = arrayOfNulls<ColorFilter>(SaturationFilterSteps + 1)

private fun saturationFilterFor(saturation: Float): ColorFilter? {
    if (saturation >= 1f) return null
    val step = (saturation.coerceIn(0f, 1f) * SaturationFilterSteps).roundToInt()
    saturationFilters[step]?.let { return it }
    val filter = ColorFilter.colorMatrix(
        ColorMatrix().apply { setToSaturation(step.toFloat() / SaturationFilterSteps) }
    )
    saturationFilters[step] = filter
    return filter
}

/**
 * The shot-scheduling loop: assigns each day-group's photo pool to grid
 * cells (cycling/repeating to fill every cell — see class doc), then
 * repeats hold-then-transition shots ([DayGroupDwellShots] per group)
 * before advancing to the next group, looping back to the first once
 * exhausted. Runs for the lifetime of the composable (cancelled
 * automatically when the DreamService tears down the LaunchedEffect's
 * CoroutineScope).
 */
private suspend fun runShotLoop(
    dayGroups: List<DayGroup>,
    cellRects: List<Rect>,
    cellStates: List<CellState>,
    bitmaps: SnapshotStateMap<File, ImageBitmap?>,
    /** ln(scale), not scale — see the Animatable's declaration in PhotoWall. */
    camLogScale: Animatable<Float, AnimationVector1D>,
    camX: Animatable<Float, AnimationVector1D>,
    camY: Animatable<Float, AnimationVector1D>,
    baseScale: Float,
    focusScale: Float,
    wallWidthPx: Float,
    wallHeightPx: Float,
    maxDecodeWidth: Int,
    maxDecodeHeight: Int
) {
    val repository = ScreensaverPhotoRepository()
    var groupIndex = 0
    var focusedCell: Int? = null

    // Start pulled back, showing the very first group's wall as it loads in.
    camLogScale.snapTo(ln(baseScale))
    camX.snapTo(wallWidthPx / 2f)
    camY.snapTo(wallHeightPx / 2f)

    while (true) {
        val group = dayGroups[groupIndex % dayGroups.size]
        if (group.photos.isEmpty()) {
            groupIndex++
            // ScreensaverPhotoRepository.scanFolder never emits an empty
            // group today, so this is unreachable — but it is the only path
            // through the loop with no suspension point in it, which means a
            // future change there would turn this into a main-thread spin
            // (an ANR, not a glitch). yield() makes that failure mode
            // impossible for the price of one line.
            yield()
            continue
        }

        // S35 correction #9 (round 8: "much worse when zooming in and out
        // of the grid" — measured, not guessed: dumpsys gfxinfo showed
        // 19.66% legacy-janky frames over a window that included overview
        // shots, vs. 3.66% without). The incoming group's photos used to
        // decode/GPU-upload only AFTER the pull-back-to-overview finished
        // below — i.e. right as all 48 cells become simultaneously
        // visible, already the most expensive moment for the compositor.
        // Stacking a burst of fresh texture uploads on top of that is
        // exactly the "much worse" the pan+decode combination the user
        // flagged. Preload here, BEFORE any camera movement starts, while
        // still zoomed on a single cell (cheap — only ~1 cell visible).
        // Iterates group.photos (unique, up to MaxPhotosPerDayGroup) rather
        // than all GridCellCount slots — loadBitmapAsync already dedupes
        // by File, so this reaches the identical end state with fewer
        // redundant map lookups.
        for (photo in group.photos) {
            loadBitmapAsync(bitmaps, photo.file, repository, maxDecodeWidth, maxDecodeHeight)
        }

        // S35 correction #7 (round 6: "changes the photo in focus just
        // before scrolling" glitch). Every cell's photo used to get
        // hard-swapped the instant a new day-group started, including
        // whatever cell the camera was still zoomed in on from the
        // previous group's last shot — an unannounced hard cut right
        // before the next pan began. Pull back to the overview FIRST so
        // the swap only happens once nothing is focused and the wall is
        // small/desaturated — the "reveal a fresh wall" beat this was
        // always meant to be.
        if (focusedCell != null) {
            val previous = focusedCell
            focusedCell = null
            panToOverview(camLogScale, camX, camY, baseScale, wallWidthPx, wallHeightPx, cellStates, previous)
        }

        // Cycle the pool to fill every cell — bitmaps are already decoded
        // above, so this is just cheap state assignment now.
        //
        // S35 correction #13 — dissolve rather than hard-cut. Each cell keeps
        // its outgoing photo as previousPhoto and ramps crossfadeProgress
        // 0->1 on a staggered schedule; see GroupRevealDissolveMs. Cells with
        // nothing to dissolve FROM (the very first group, where photo is
        // still null) go straight to 1f so they simply appear.
        for (i in 0 until GridCellCount) {
            val cell = cellStates[i]
            // Captured BEFORE the snapTo below reclaims `saturation` for the
            // incoming photo — see CellState.previousSaturation.
            cell.previousSaturation = cell.saturation.value
            cell.previousPhoto = cell.photo
            cell.photo = group.photos[i % group.photos.size]
            cell.saturation.snapTo(0f)
            cell.crossfadeProgress.snapTo(if (cell.previousPhoto == null) 1f else 0f)
        }
        coroutineScope {
            cellStates.forEachIndexed { i, cell ->
                if (cell.previousPhoto == null) return@forEachIndexed
                launch {
                    cell.crossfadeProgress.animateTo(
                        1f,
                        tween(
                            GroupRevealDissolveMs,
                            delayMillis = i * GroupRevealStaggerMs,
                            easing = GentleEasing
                        )
                    )
                }
            }
        }
        cellStates.forEach { it.previousPhoto = null }
        // S35 correction #6 (round 5, LAUNCHER_PLAYBOOK.md §4: "cache
        // decoded artwork bounded by bytes, not by entry count"). The
        // original plan called for evicting a finished group's bitmaps on
        // advance, but that step never actually got wired up — bitmaps has
        // been growing for the app's whole runtime, every decoded photo
        // ever shown staying resident. At the current decode size each
        // bitmap is several MB, and this box has ~2GB RAM total, so that's
        // real steady-state memory pressure. Bound residency to just the
        // group that's actually on screen. Done AFTER the swap above (not
        // before) — the previous group's bitmaps must stay resident for the
        // pull-back animation AND, since correction #13, for the dissolve,
        // which draws the outgoing photo right up until crossfadeProgress
        // reaches 1. Evicting any earlier would blank the cells mid-fade.
        val keepFiles = group.photos.map { it.file }.toSet()
        bitmaps.keys.retainAll(keepFiles)

        // S35 correction #12 — hold still at the overview so the 16 fresh
        // bitmaps get uploaded to the GPU (and their mip chains built) while
        // nothing is moving, instead of on the opening frames of the first
        // zoom-in. See GroupRevealSettleMs.
        delay(GroupRevealSettleMs.toLong())

        repeat(DayGroupDwellShots) {
            val roll = Random.nextFloat()
            when {
                roll < ProbOverview -> {
                    val previous = focusedCell
                    focusedCell = null
                    panToOverview(camLogScale, camX, camY, baseScale, wallWidthPx, wallHeightPx, cellStates, previous)
                    delay(OverviewHoldDurationMs.toLong())
                }

                roll < ProbOverview + ProbCrossfadeInPlace && focusedCell != null -> {
                    // S35 correction #7 (round 6: "the photo in focus fades
                    // to black" — the old approach faded the WHOLE cell
                    // (mat, border, photo) down to near-zero alpha to hide
                    // the swap, which exposed the black wall background
                    // behind it since the focused cell fills most of the
                    // screen. Real WMC-style crossfades dissolve directly
                    // between the old and new photo instead — the mat/
                    // border never disappear. Keep the outgoing photo
                    // resident as `previousPhoto` and ramp crossfadeProgress
                    // 0->1 to dissolve; see PhotoCell/drawPhotoFitted.
                    // Non-null by the branch condition above; no !! needed.
                    val cellIndex = focusedCell
                    val cell = cellStates[cellIndex]
                    val nextPhoto = group.photos.filter { it != cell.photo }.randomOrNull() ?: group.photos.random()
                    loadBitmapAsync(bitmaps, nextPhoto.file, repository, maxDecodeWidth, maxDecodeHeight)
                    cell.previousSaturation = cell.saturation.value
                    cell.previousPhoto = cell.photo
                    cell.photo = nextPhoto
                    cell.saturation.snapTo(0f)
                    cell.crossfadeProgress.snapTo(0f)
                    cell.crossfadeProgress.animateTo(1f, tween(CrossfadeSwapHalfDurationMs * 2, easing = GentleEasing))
                    cell.previousPhoto = null
                    cell.saturation.animateTo(1f, tween(SaturationDurationMs))
                    delay(HoldDurationMs.toLong())
                }

                else -> {
                    // S35 correction #12 — draw from the nearest cells rather
                    // than uniformly across all 48, to keep screen-space
                    // travel (and therefore motion smear) down. See
                    // NearbyCandidatePoolSize.
                    val candidates = (0 until GridCellCount).filter { it != focusedCell }
                    if (candidates.isEmpty()) return@repeat
                    val newCell = candidates
                        .sortedBy { i ->
                            val r = cellRects[i]
                            kotlin.math.hypot(
                                r.left + r.width / 2f - camX.value,
                                r.top + r.height / 2f - camY.value
                            )
                        }
                        .take(NearbyCandidatePoolSize)
                        .random()
                    val previous = focusedCell
                    focusedCell = newCell
                    val rect = cellRects[newCell]
                    val cellCenterX = rect.left + rect.width / 2f
                    val cellCenterY = rect.top + rect.height / 2f
                    // S35 correction #12 — timed by how far this move travels
                    // ON SCREEN, not across the wall. See
                    // panDurationForScreenTravel and MinPanDurationMs's
                    // comment for why that distinction is the whole blur bug.
                    val panDuration = panDurationForScreenTravel(
                        kotlin.math.hypot(cellCenterX - camX.value, cellCenterY - camY.value),
                        camLogScale.value,
                        ln(focusScale)
                    )
                    // Delay is now a FRACTION of whatever this hop's actual
                    // duration turned out to be, not a flat ms value — keeps
                    // the "arrive, then bloom" beat proportionally placed
                    // regardless of hop length.
                    val saturationDelay = (panDuration * SaturationArriveDelayFraction).toInt()
                    coroutineScope {
                        launch { camLogScale.animateTo(ln(focusScale), tween(panDuration, easing = GentleEasing)) }
                        launch { camX.animateTo(cellCenterX, tween(panDuration, easing = GentleEasing)) }
                        launch { camY.animateTo(cellCenterY, tween(panDuration, easing = GentleEasing)) }
                        // S35 correction — delayMillis so color starts
                        // blooming only once the glide has mostly finished,
                        // not from the same instant the pan starts. Running
                        // both from t=0 meant they finished together, which
                        // read as "already in color" the moment the camera
                        // visibly settled — there was no separate beat left
                        // to watch. The outgoing cell fading TO gray isn't
                        // the "reveal" moment, so it stays parallel with the pan.
                        launch {
                            cellStates[newCell].saturation.animateTo(
                                1f,
                                tween(SaturationDurationMs, delayMillis = saturationDelay)
                            )
                        }
                        previous?.let { p -> launch { cellStates[p].saturation.animateTo(0f, tween(SaturationDurationMs)) } }
                    }
                    delay(HoldDurationMs.toLong())
                }
            }
        }

        groupIndex++
    }
}

/**
 * S35 correction #12 — replaces correction #4's panDurationForDistance,
 * which had the right idea (time a move by how far it travels) applied to
 * the wrong quantity: it measured [wallDistancePx] in WALL coordinates,
 * but what has to be held slow is travel across the SCREEN, and the
 * conversion between the two is the camera scale — 0.25 at overview,
 * ~1.45 at focus, a 5.8x swing. That systematically under-timed exactly
 * the moves that happen at high zoom, which is what produced the motion
 * smear. See MinPanDurationMs's comment for the full arithmetic.
 *
 * Because the zoom itself is interpolated in log space (correction #11),
 * the scale at the move's midpoint is the geometric mean of its endpoint
 * scales — the right single-number stand-in for "how many screen px does
 * one wall px cover during this move". For a pure lateral pan (both
 * endpoints already focused, which is the common case) that reduces
 * exactly to the focus scale.
 */
private fun panDurationForScreenTravel(
    wallDistancePx: Float,
    startLogScale: Float,
    endLogScale: Float
): Int {
    val midScale = exp((startLogScale + endLogScale) / 2f)
    val screenDistancePx = wallDistancePx * midScale
    // S36 review fix — a move that neither travels nor zooms has nothing to
    // ease, and MinPanDurationMs applied to it just freezes the wall. That is
    // reachable: an overview shot rolled as the first shot after a group
    // reveal finds the camera ALREADY centred at baseScale, so it animated
    // for 4s to a position it was in, then held OverviewHoldDurationMs on top
    // — nine motionless seconds that read as the screensaver having hung.
    // The Min floor still applies to every real move, where it correctly
    // stops short hops from snapping.
    if (screenDistancePx < NegligibleScreenTravelPx &&
        abs(endLogScale - startLogScale) < NegligibleLogScaleDelta
    ) {
        return 0
    }
    return (screenDistancePx / TargetPanScreenVelocityPxPerSec * 1000f)
        .toInt()
        .coerceIn(MinPanDurationMs, MaxPanDurationMs)
}

/**
 * Glides the camera back to the pulled-back overview (shared by the
 * overview shot and, since round 6, the day-group transition — see
 * runShotLoop's S35 correction #7 comment for why the latter needs it too).
 */
private suspend fun panToOverview(
    /** ln(scale), not scale — see the Animatable's declaration in PhotoWall. */
    camLogScale: Animatable<Float, AnimationVector1D>,
    camX: Animatable<Float, AnimationVector1D>,
    camY: Animatable<Float, AnimationVector1D>,
    baseScale: Float,
    wallWidthPx: Float,
    wallHeightPx: Float,
    cellStates: List<CellState>,
    previousFocused: Int?
) {
    val targetX = wallWidthPx / 2f
    val targetY = wallHeightPx / 2f
    val panDuration = panDurationForScreenTravel(
        kotlin.math.hypot(targetX - camX.value, targetY - camY.value),
        camLogScale.value,
        ln(baseScale)
    )
    coroutineScope {
        launch { camLogScale.animateTo(ln(baseScale), tween(panDuration, easing = GentleEasing)) }
        launch { camX.animateTo(targetX, tween(panDuration, easing = GentleEasing)) }
        launch { camY.animateTo(targetY, tween(panDuration, easing = GentleEasing)) }
        previousFocused?.let { p -> launch { cellStates[p].saturation.animateTo(0f, tween(SaturationDurationMs)) } }
    }
}

/**
 * Fires [file]'s decode on IO and stores the result once ready — a no-op if
 * already cached.
 *
 * The map's key set doubles as the "already handled" check, and a present-
 * but-null value marks a decode in flight (so the cell renders empty rather
 * than stale while it lands).
 *
 * S36 review fix — a FAILED decode used to be stored as that same null,
 * which `containsKey` then treated as "already loaded" forever: one corrupt
 * photo left its cell permanently blank, and when the folder produces only a
 * single day-group `retainAll` kept the poisoned entry alive for the whole
 * session, so it never got a second chance. Removing the key on failure
 * means the next group cycle simply tries again.
 */
private suspend fun loadBitmapAsync(
    bitmaps: SnapshotStateMap<File, ImageBitmap?>,
    file: File,
    repository: ScreensaverPhotoRepository,
    maxWidth: Int,
    maxHeight: Int
) {
    if (bitmaps.containsKey(file)) return
    bitmaps[file] = null
    val decoded = withContext(Dispatchers.IO) { repository.decodeDownsampled(file, maxWidth, maxHeight) }
    if (decoded == null) bitmaps.remove(file) else bitmaps[file] = decoded
}
