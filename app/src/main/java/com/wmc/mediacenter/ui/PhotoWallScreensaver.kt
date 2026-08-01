package com.wmc.mediacenter.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.wmc.mediacenter.screensaver.DayGroup
import com.wmc.mediacenter.screensaver.ScreensaverPhoto
import com.wmc.mediacenter.screensaver.ScreensaverPhotoRepository
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.math.min
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
 */
@Composable
fun PhotoWallScreensaver(folderPath: String) {
    var scanState by remember { mutableStateOf<ScanState>(ScanState.Loading) }

    LaunchedEffect(folderPath) {
        val groups = withContext(Dispatchers.IO) {
            ScreensaverPhotoRepository().scanFolder(File(folderPath))
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
// zoom values as an unresolved research gap (no video reference captured),
// same as this app's other hand-tuned motion constants. -----------------

private const val GridColumns = 3
private const val GridRows = 2
private const val GridCellCount = GridColumns * GridRows
private val CellWidth = 420.dp
private val CellAspectRatio = 3f / 2f // landscape photos
private val CellHeight = CellWidth / CellAspectRatio
private val CellSpacing = 32.dp
private val WallMargin = 32.dp
private val PhotoBorderWidth = 6.dp

/** Fraction of the screen's limiting dimension a focused cell fills — leaves a little breathing room around it. */
private const val FocusedZoomFillFactor = 0.86f

private const val HoldDurationMs = 4000
private const val OverviewHoldDurationMs = 3200
private const val PanZoomDurationMs = 1400
private const val CrossfadeSwapHalfDurationMs = 450
private const val SaturationDurationMs = 1000

private const val ProbOverview = 0.10f
private const val ProbCrossfadeInPlace = 0.25f
// Remainder (0.65) is pan-to-a-new-cell.

private const val DayGroupDwellShots = 5

/** Decode headroom over display resolution so the camera's zoom-in doesn't visibly upsample. */
private const val DecodeSizeMultiplier = 2f

private val DateLabelFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

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
    val alpha = Animatable(1f)
    var photo by mutableStateOf<ScreensaverPhoto?>(null)
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

        val baseScale = min(screenWidthPx / wallWidthPx, screenHeightPx / wallHeightPx)

        val camScale = remember { Animatable(baseScale) }
        val camX = remember { Animatable(wallWidthPx / 2f) }
        val camY = remember { Animatable(wallHeightPx / 2f) }
        var focusedCellIndex by remember { mutableStateOf<Int?>(null) }
        var focusedPhoto by remember { mutableStateOf<ScreensaverPhoto?>(null) }

        val cellStates = remember { List(GridCellCount) { CellState() } }
        val bitmaps = remember { mutableStateMapOf<File, ImageBitmap?>() }

        val maxDecodeWidth = (screenWidthPx * DecodeSizeMultiplier).toInt()
        val maxDecodeHeight = (screenHeightPx * DecodeSizeMultiplier).toInt()

        LaunchedEffect(dayGroups) {
            withContext(FixedMotionDurationScale) {
                runShotLoop(
                    dayGroups = dayGroups,
                    cellRects = cellRects,
                    cellStates = cellStates,
                    bitmaps = bitmaps,
                    camScale = camScale,
                    camX = camX,
                    camY = camY,
                    baseScale = baseScale,
                    wallWidthPx = wallWidthPx,
                    wallHeightPx = wallHeightPx,
                    screenWidthPx = screenWidthPx,
                    screenHeightPx = screenHeightPx,
                    maxDecodeWidth = maxDecodeWidth,
                    maxDecodeHeight = maxDecodeHeight,
                    onFocusChanged = { index, photo -> focusedCellIndex = index; focusedPhoto = photo }
                )
            }
        }

        Box(
            modifier = Modifier
                .size(with(density) { wallWidthPx.toDp() }, with(density) { wallHeightPx.toDp() })
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = camScale.value
                    scaleY = camScale.value
                    translationX = screenWidthPx / 2f - camScale.value * camX.value
                    translationY = screenHeightPx / 2f - camScale.value * camY.value
                }
        ) {
            cellRects.forEachIndexed { index, rect ->
                val cell = cellStates[index]
                val photo = cell.photo ?: return@forEachIndexed
                PhotoCell(
                    photo = photo,
                    bitmap = bitmaps[photo.file],
                    saturation = cell.saturation.value,
                    cellAlpha = cell.alpha.value,
                    showDateLabel = index == focusedCellIndex && photo == focusedPhoto,
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
 * saturation/cellAlpha are read as plain Float params (not inside a
 * graphicsLayer block), so an animating cell recomposes its PhotoCell call —
 * and, since the caller reads them in a forEachIndexed loop, the whole cell
 * list — every frame of a transition. Only ~1-2 of 6 cells are ever
 * animating at once, for ~1-1.4s every few seconds (not continuously, unlike
 * the tile rows' per-frame follower), so this is left as the simple version
 * for now. If on-device measurement (see NOTES.md §7) ever shows it's worth
 * it, move these reads into a graphicsLayer/drawWithCache scope on each cell
 * to isolate recomposition the way HomeScreen.kt's row alpha already does.
 */
@Composable
private fun PhotoCell(
    photo: ScreensaverPhoto,
    bitmap: ImageBitmap?,
    saturation: Float,
    cellAlpha: Float,
    showDateLabel: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .alpha(cellAlpha)
            .background(Color.White)
            .padding(PhotoBorderWidth)
            .background(Color.Black)
    ) {
        if (bitmap != null) {
            val matrix = remember(saturation) { ColorMatrix().apply { setToSaturation(saturation) } }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                colorFilter = ColorFilter.colorMatrix(matrix)
            )
        }

        if (showDateLabel) {
            val labelAlpha by animateFloatAsState(targetValue = 1f, animationSpec = tween(SaturationDurationMs), label = "dateLabel")
            Text(
                text = photo.dateTaken.format(DateLabelFormatter),
                color = Color.White.copy(alpha = labelAlpha),
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
    }
}

/**
 * The shot-scheduling loop: assigns each day-group's photos to grid cells,
 * decodes their bitmaps, then repeats hold-then-transition shots
 * ([DayGroupDwellShots] per group) before advancing to the next group,
 * looping back to the first once exhausted. Runs for the lifetime of the
 * composable (cancelled automatically when the DreamService tears down the
 * LaunchedEffect's CoroutineScope).
 */
private suspend fun runShotLoop(
    dayGroups: List<DayGroup>,
    cellRects: List<Rect>,
    cellStates: List<CellState>,
    bitmaps: SnapshotStateMap<File, ImageBitmap?>,
    camScale: Animatable<Float, AnimationVector1D>,
    camX: Animatable<Float, AnimationVector1D>,
    camY: Animatable<Float, AnimationVector1D>,
    baseScale: Float,
    wallWidthPx: Float,
    wallHeightPx: Float,
    screenWidthPx: Float,
    screenHeightPx: Float,
    maxDecodeWidth: Int,
    maxDecodeHeight: Int,
    onFocusChanged: (Int?, ScreensaverPhoto?) -> Unit
) {
    val repository = ScreensaverPhotoRepository()
    var groupIndex = 0
    var focusedCell: Int? = null

    // Start pulled back, showing the very first group's wall as it loads in.
    camScale.snapTo(baseScale)
    camX.snapTo(wallWidthPx / 2f)
    camY.snapTo(wallHeightPx / 2f)

    while (true) {
        val group = dayGroups[groupIndex % dayGroups.size]
        val activeCellCount = min(GridCellCount, group.photos.size)
        if (activeCellCount == 0) {
            groupIndex++
            continue
        }

        // Assign the first wave of cells directly from the group; anything
        // beyond activeCellCount stays photo=null (unrendered).
        var poolCursor = activeCellCount
        for (i in 0 until GridCellCount) {
            cellStates[i].photo = if (i < activeCellCount) group.photos[i] else null
            if (i < activeCellCount) {
                loadBitmapAsync(bitmaps, group.photos[i].file, repository, maxDecodeWidth, maxDecodeHeight)
            }
        }
        cellStates.forEach { it.saturation.snapTo(0f) }
        focusedCell = null
        onFocusChanged(null, null)

        repeat(DayGroupDwellShots) {
            val roll = Random.nextFloat()
            when {
                roll < ProbOverview -> {
                    val previous = focusedCell
                    focusedCell = null
                    onFocusChanged(null, null)
                    coroutineScope {
                        launch { camScale.animateTo(baseScale, tween(PanZoomDurationMs, easing = LinearOutSlowInEasing)) }
                        launch { camX.animateTo(wallWidthPx / 2f, tween(PanZoomDurationMs, easing = LinearOutSlowInEasing)) }
                        launch { camY.animateTo(wallHeightPx / 2f, tween(PanZoomDurationMs, easing = LinearOutSlowInEasing)) }
                        previous?.let { p -> launch { cellStates[p].saturation.animateTo(0f, tween(SaturationDurationMs)) } }
                    }
                    delay(OverviewHoldDurationMs.toLong())
                }

                roll < ProbOverview + ProbCrossfadeInPlace && focusedCell != null -> {
                    val cellIndex = focusedCell!!
                    val cell = cellStates[cellIndex]
                    val nextPhoto = if (poolCursor < group.photos.size) {
                        group.photos[poolCursor].also { poolCursor++ }
                    } else {
                        // Pool exhausted — reuse the group's photos from the top.
                        group.photos[poolCursor % group.photos.size].also { poolCursor++ }
                    }
                    cell.alpha.animateTo(0.05f, tween(CrossfadeSwapHalfDurationMs))
                    cell.photo = nextPhoto
                    loadBitmapAsync(bitmaps, nextPhoto.file, repository, maxDecodeWidth, maxDecodeHeight)
                    onFocusChanged(cellIndex, nextPhoto)
                    cell.alpha.animateTo(1f, tween(CrossfadeSwapHalfDurationMs))
                    delay(HoldDurationMs.toLong())
                }

                else -> {
                    val candidates = (0 until activeCellCount).filter { it != focusedCell }
                    if (candidates.isEmpty()) return@repeat
                    val newCell = candidates.random()
                    val previous = focusedCell
                    focusedCell = newCell
                    val rect = cellRects[newCell]
                    val cellCenterX = rect.left + rect.width / 2f
                    val cellCenterY = rect.top + rect.height / 2f
                    val focusScale = min(screenWidthPx / rect.width, screenHeightPx / rect.height) * FocusedZoomFillFactor
                    onFocusChanged(newCell, cellStates[newCell].photo)
                    coroutineScope {
                        launch { camScale.animateTo(focusScale, tween(PanZoomDurationMs, easing = LinearOutSlowInEasing)) }
                        launch { camX.animateTo(cellCenterX, tween(PanZoomDurationMs, easing = LinearOutSlowInEasing)) }
                        launch { camY.animateTo(cellCenterY, tween(PanZoomDurationMs, easing = LinearOutSlowInEasing)) }
                        launch { cellStates[newCell].saturation.animateTo(1f, tween(SaturationDurationMs)) }
                        previous?.let { p -> launch { cellStates[p].saturation.animateTo(0f, tween(SaturationDurationMs)) } }
                    }
                    delay(HoldDurationMs.toLong())
                }
            }
        }

        groupIndex++
    }
}

/** Fires [file]'s decode on IO and stores the result once ready — a no-op if already cached. */
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
    bitmaps[file] = decoded
}

