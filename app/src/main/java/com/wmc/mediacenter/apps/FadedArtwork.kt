package com.wmc.mediacenter.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * S11 — the pale washed-blue every unfocused ("faded") tile is tinted to.
 * Also used directly by AppTile for the vector system-action glyphs and the
 * text fallback, which have no bitmap to bake.
 */
val TileFadedTint = Color(0xFFAEC8E8)

/**
 * S22 — WHERE THIS LIVES AND WHY.
 *
 * The faded silhouette used to be baked inside AppTile via
 * `remember(src, faded) { fadeBitmap(src) }`. The S18 comment claimed that
 * ran "at most once per source"; it did not. `remember` is scoped to a
 * single composable INSTANCE and was keyed on `faded`, so:
 *
 *   - every time a tile lost focus (`faded` false -> true) the key changed
 *     and the bitmap was baked AGAIN, on the main thread, inside composition;
 *   - every tile entering composition (a strip expanding, a LazyRow
 *     recycling an item back in) baked its own private copy, shared with
 *     nothing.
 *
 * One D-pad press therefore ran ~7-9 full-bitmap allocations plus software
 * colour-matrix passes inside a SINGLE frame. Frame analysis of a screen
 * capture showed exactly that: the first frame of every transition took
 * 50-70ms (the box repainted the same frame 3-4 times), after which the
 * motion settled smoothly. That onset spike — not a uniform hardware
 * ceiling — was the scroll stutter.
 *
 * So the bake now happens ONCE PER PACKAGE, at discovery time, on
 * Dispatchers.IO (see AppRepository), and the result is cached alongside the
 * normal artwork. Rendering a faded tile is now the same cost as rendering
 * an unfaded one: a plain bitmap blit. Nothing here may ever be called from
 * composition.
 */

/**
 * Output RGB is the constant pale-blue tint; new ALPHA = 0.8*A - 0.45*lum.
 * The luminance term knocks light pixels (a banner's harsh white background)
 * down, but the 0.8*A floor keeps them faintly visible instead of fully
 * erased — so a mostly-white banner (e.g. YouTube) reads as a soft panel +
 * logo like every other tile rather than vanishing.
 *
 * S21 history: the earlier coefficients (0.8*(A-lum)) drove white to exactly
 * 0 alpha, which made white-heavy banners disappear entirely while darker
 * ones survived — an inconsistent, broken-looking result.
 *
 * Tuning: bigger luminance coefficients = more white removal (risks
 * vanishing); smaller = more visible panel.
 */
private val FadeMatrix = floatArrayOf(
    0f, 0f, 0f, 0f, TileFadedTint.red * 255f,
    0f, 0f, 0f, 0f, TileFadedTint.green * 255f,
    0f, 0f, 0f, 0f, TileFadedTint.blue * 255f,
    -0.1346f, -0.2642f, -0.0513f, 0.8f, 0f
)

/**
 * android.graphics.Paint is NOT thread-safe, and discovery may run on any
 * IO thread (and could be parallelised later), so hand out one per thread
 * rather than sharing a single instance.
 */
private val fadePaint = ThreadLocal.withInitial {
    Paint().apply {
        isFilterBitmap = true
        colorFilter = ColorMatrixColorFilter(ColorMatrix(FadeMatrix))
    }
}

/**
 * Bakes the faded silhouette of [src] into a new bitmap, or null if the
 * source can't be read as a software bitmap.
 *
 * S20 — baked via android.graphics (software Canvas + ColorMatrixColorFilter)
 * rather than Compose's CanvasDrawScope, which is the canonical way to bake a
 * colour matrix into an ARGB_8888 bitmap. Source bitmaps come from
 * drawable.toBitmap() and are therefore software, so asAndroidBitmap() is
 * safe.
 *
 * S23 — CORRECTION to the original S20 note, which claimed the Compose path
 * "mis-rendered some banners (e.g. YouTube)". It did not. The YouTube banner
 * was oversized and cropped in EVERY mode — focused and unfocused, faded and
 * unfaded — so no fade code path could have caused it. The real cause was in
 * AppRepository's Drawable->Bitmap decode (see `render()` there). That
 * misattribution is what kept S19-S22 tuning colour matrices against a
 * geometry bug. Nothing in this file has ever been implicated; if a specific
 * app's artwork looks wrong again, check whether it also looks wrong when
 * FOCUSED before touching anything here.
 *
 * MUST be called off the main thread.
 */
fun fadeBitmap(src: ImageBitmap): ImageBitmap? = try {
    val srcBmp = src.asAndroidBitmap()
    val out = Bitmap.createBitmap(srcBmp.width, srcBmp.height, Bitmap.Config.ARGB_8888)
    // Keep the copy's density identical to the source so nothing downstream
    // ever sizes the faded variant differently from the full-colour one.
    out.density = srcBmp.density

    val canvas = Canvas(out)
    // S23 — DENSITY. This is the bug that made YouTube's banner render as a
    // 2x-magnified, top-left-anchored crop while every other app was fine.
    //
    // Canvas.drawBitmap(bitmap, left, top, paint) is DENSITY-SCALED: it
    // multiplies by canvasDensity / bitmapDensity. Bitmap.createBitmap()
    // stamps the DEFAULT device density on the destination, but a banner that
    // arrives as a BitmapDrawable is the resource's own bitmap, carrying the
    // RESOURCE's density. When those differ by 2x, drawBitmap magnifies the
    // source 2x from the origin and only the top-left quadrant lands in the
    // destination — a perfect, silent crop.
    //
    // Why only some apps: Hulu's banner is a LayerDrawable, so toBitmap()
    // renders it into a freshly created bitmap that already carries the
    // default density — no mismatch, no crop. Only the BitmapDrawable path
    // preserves a foreign density, so the corruption tracked drawable TYPE,
    // not anything about the artwork. That is why four rounds of colour-matrix
    // tuning (S19-S22) never touched it: a colour matrix cannot move or resize
    // anything, and this was always a geometry bug.
    //
    // Fixed two ways, deliberately belt-and-braces:
    //   1. Neutralise the canvas density so no implicit scaling can apply.
    //   2. Use the explicit src/dst Rect overload, which maps rect-to-rect and
    //      ignores density entirely.
    // Do NOT revert to the (left, top) overload.
    canvas.density = Bitmap.DENSITY_NONE
    val bounds = Rect(0, 0, srcBmp.width, srcBmp.height)
    canvas.drawBitmap(srcBmp, bounds, bounds, fadePaint.get())

    out.asImageBitmap()
} catch (e: Exception) {
    null
}
