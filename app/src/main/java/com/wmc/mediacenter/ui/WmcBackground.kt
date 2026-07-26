package com.wmc.mediacenter.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.LayoutDirection
import com.wmc.mediacenter.ui.theme.WmcGlowBlue
import com.wmc.mediacenter.ui.theme.WmcNavyDark
import com.wmc.mediacenter.ui.theme.WmcNavyMid
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * WMC-style full-screen blue gradient with a soft, diffuse highlight —
 * tuned against a real WMC screenshot rather than guessed: it's blue
 * throughout (no teal/cyan shift), darkest in the corners, with the
 * lightest area upper-right-of-center fading outward. Static version,
 * shared by every full-screen surface (All Apps, Settings, ...) so they
 * all look like the same app.
 */
fun Modifier.wmcBackground(driftPhase: Float = 0f): Modifier = this.drawWithCache {
    val diagonal = diagonalBrush()
    val highlight = highlightBrush(driftPhase)
    onDrawBehind {
        drawRect(brush = diagonal)
        drawRect(brush = highlight)
    }
}

/**
 * Home background.
 *
 * S16 — NOW STATIC. This used to drift the glow over ~24s, which forced a
 * fullscreen radial-gradient REDRAW every single frame, forever — even when
 * idle. On the low-end box that constant fill-rate tax was fixed overhead on
 * top of everything a row transition already does (scroll relayout, tile
 * crossfades, row expansion); when a transition spiked past the 16ms frame
 * budget, frames dropped and the scroll lurched ("moves partway, pauses,
 * continues" — confirmed by frame analysis: identical transitions rendered
 * smoothly when the box kept up and steppy when it didn't).
 *
 * The drift was a ±4% wander that's imperceptible in motion, so trading it
 * for a static background is nearly invisible.
 *
 * S17 — and BAKED TO A BITMAP. Even static, drawing two fullscreen gradients
 * (a linear + a RADIAL) per frame re-runs their shaders every frame the
 * screen invalidates — which is every frame while scrolling. gfxinfo showed
 * ~19ms GPU/frame on the box. Here the two gradients are rendered ONCE into
 * an offscreen ImageBitmap (rebuilt only when the viewport size changes),
 * and each frame just blits that texture — a fraction of the fill cost of
 * re-shading a fullscreen radial gradient every frame.
 */
@Composable
fun Modifier.wmcBackgroundAnimated(): Modifier = this.drawWithCache {
    val w = size.width.roundToInt().coerceAtLeast(1)
    val h = size.height.roundToInt().coerceAtLeast(1)
    val baked = ImageBitmap(w, h)
    val diagonal = diagonalBrush()
    val highlight = highlightBrush(0f)
    // Render the gradients into the bitmap a single time (this whole block
    // re-runs only when size changes).
    CanvasDrawScope().draw(this, LayoutDirection.Ltr, Canvas(baked), size) {
        drawRect(brush = diagonal)
        drawRect(brush = highlight)
    }
    onDrawBehind { drawImage(baked) }
}

private fun androidx.compose.ui.draw.CacheDrawScope.diagonalBrush(): Brush =
    Brush.linearGradient(
        colors = listOf(WmcNavyDark, WmcNavyMid, WmcGlowBlue),
        start = Offset(0f, size.height),
        end = Offset(size.width, 0f)
    )

/**
 * Broad, bright center glow with a faint white-hot core — per a real WMC
 * screenshot this glow is what makes ghosted (translucent) icons read as
 * glassy: they sit directly on it with no card behind them.
 */
private fun androidx.compose.ui.draw.CacheDrawScope.highlightBrush(driftPhase: Float): Brush {
    val cx = size.width * (0.62f + 0.04f * sin(driftPhase))
    val cy = size.height * (0.40f + 0.03f * cos(driftPhase * 0.7f))
    return Brush.radialGradient(
        colors = listOf(
            Color(0xFF9DC4E8).copy(alpha = 0.30f),
            WmcGlowBlue.copy(alpha = 0.25f),
            Color.Transparent
        ),
        center = Offset(cx, cy),
        radius = max(size.width, size.height) * 0.75f
    )
}
