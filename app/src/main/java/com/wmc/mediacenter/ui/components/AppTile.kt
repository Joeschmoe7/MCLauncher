package com.wmc.mediacenter.ui.components

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wmc.mediacenter.R
import com.wmc.mediacenter.apps.AppInfo
import com.wmc.mediacenter.apps.SystemActions
import com.wmc.mediacenter.apps.TileFadedTint
import com.wmc.mediacenter.ui.theme.WmcAccentCyan
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import kotlin.math.max

// S24 — INTERNAL, not private: HomeScreen derives the strip's cursor-slot
// position (RowCursorSlotStart) from this width. Duplicating the number there
// would silently break the WMC lead-in slot the first time a tile is resized.
internal val TileWidth = 220.dp
private val TileHeight = 130.dp
private val TileCornerRadius = 8.dp
private const val TileFocusScale = 1.12f

/**
 * Gap between the tile and its label.
 *
 * S23 — must clear the focused tile's OVERHANG. The 1.12x focus scale is a
 * graphicsLayer transform, i.e. draw-time only: the tile's layout box stays
 * TileHeight, but it paints TileHeight * (1.12 - 1) / 2 = 7.8dp past its own
 * bottom edge. The old 14dp reflection slot used to swallow that; once the
 * reflection was removed the focused tile landed on top of the label.
 *
 * Reserved for EVERY tile, focused or not, so row height never changes as
 * focus moves. Anything above ~8dp works; the remainder is the visible gap.
 */
private val TileLabelTopGap = 12.dp

// S7 — WMC glass-chiclet: every tile (rest AND focused) sits on a translucent
// glass card with a soft top-left specular highlight (Aero's light source);
// the artwork (banner, icon fallback) is inset smaller than the card and
// drawn on top at full opacity — never faded into the card. Focused tiles
// get a brighter glass surface plus the lit cyan/white frame.
private val TileGlassRest = Brush.verticalGradient(
    0f to Color(0x5A4A7FB5),
    0.5f to Color(0x4A2E5F96),
    1f to Color(0x5A1E4470)
)
private val TileGlassFocused = Brush.verticalGradient(
    0f to Color(0xE64A7FB5),
    0.5f to Color(0xD92E5F96),
    1f to Color(0xE61E4470)
)

// S11 — WMC-authentic faded tiles: everything but the highlighted card is a
// pale washed-blue silhouette of its artwork, with light pixels (a banner's
// white background) dissolved away so only the logo/text reads.
//
// S22 — THE BAKE NO LONGER LIVES HERE. It used to, as
// `remember(src, faded) { fadeBitmap(src) }`, which re-ran a full bitmap
// allocation + software colour-matrix pass ON THE MAIN THREAD, INSIDE
// COMPOSITION, every time a tile changed focus and for every tile entering
// composition as a strip expanded — 7-9 of them in a single frame per D-pad
// press. That was the 50-70ms first frame of every row transition (the
// scroll stutter), NOT a uniform hardware ceiling.
//
// Faded copies are now baked once per package at discovery time on
// Dispatchers.IO and carried on AppInfo (see AppRepository / FadedArtwork.kt).
// Drawing a faded tile is now exactly as cheap as drawing an unfaded one, and
// a focus change is a pure draw swap. Do not reintroduce any bitmap work in
// this file.

/**
 * A single app tile: WMC-style rounded glass card with the app's TV banner
 * (falling back to icon, then plain label) inset inside it, a top-left
 * specular glow, focus scale/glow, and the label underneath.
 *
 * OK / click launches the app by default, or runs [onClick] instead if
 * provided (used by the app picker, where OK toggles selection rather
 * than launching). Long-press OK (if [onLongClick] is provided) opens a
 * context menu. [isSelected] draws a small checkmark badge, for the app
 * picker's "checked = in this row" state. [showLabel] toggles the label
 * under the tile — driven by Settings' "show app names" preference (the
 * fallback text drawn *inside* an icon-less tile is unaffected, since
 * that's the only thing identifying the app in that case).
 *
 * Confirmed from a real WMC screenshot: tiles are NOT full-bleed art — the
 * artwork is a smaller icon/banner inset inside a glassy card with a
 * specular highlight in the top-left corner. Both rest and focused tiles
 * get the glass card; focused is brighter with the lit cyan/white frame.
 * Never fade artwork *into* an opaque fill — earlier attempts at that read
 * as "a dimmer rectangle." Here the art stays crisp at full opacity on top;
 * only the glass/glow behind it is translucent.
 *
 * [glassTiles] is a Settings toggle (off by default): off shows the plain
 * full-bleed banner/icon with no card at rest — only the focused tile gets
 * chrome, same as this app's original look — since the glass treatment
 * didn't read well for Lou. On applies the full S7 glass-chiclet treatment
 * described above.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    app: AppInfo,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    showLabel: Boolean = true,
    glassTiles: Boolean = false,
    // S9 — classic-strips mode: the label renders only under the focused
    // tile (fading in with focus), like real WMC. Space stays reserved so
    // the row never shifts. Ignored when showLabel is false.
    labelOnlyWhenFocused: Boolean = false,
    // S11 — authentic WMC: while unfocused, the artwork renders as a pale
    // faded-blue silhouette (backgrounds dissolved, see TileFadedColorFilter)
    // and crossfades to full color as focus arrives.
    fadedWhenUnfocused: Boolean = false,
    // S11 — Settings "Tile artwork": true shows the centered app icon instead
    // of the TV banner (banner becomes the fallback).
    preferIcons: Boolean = false,
    // F4 — only the default launch path below (OK with no onClick override)
    // records a launch. Picker toggles and system-action cards always pass
    // their own onClick, so they never call this.
    onAppLaunched: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // S4 — WMC-style eased glide instead of a linear tween.
    val scale by animateFloatAsState(
        targetValue = if (isFocused) TileFocusScale else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tileScale"
    )
    // S7 — 0→1 focus fraction used to lerp the glass/glow lighting up
    // smoothly rather than popping between rest and focused states.
    val focusFraction by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "tileFocusFraction"
    )
    // S9 — WMC's focus glow "breathes": a slow pulse on the outer frame
    // while focused. Kept as a State and only read in the DRAW phase below
    // (S10) — reading it in composition recomposed the tile at 60fps.
    val glowPulse: State<Float> = if (isFocused) {
        rememberInfiniteTransition(label = "focusPulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1500), repeatMode = RepeatMode.Reverse),
            label = "focusPulseValue"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Column(
        modifier = modifier.width(TileWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(TileWidth)
                .height(TileHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(TileCornerRadius))
                .then(
                    if (glassTiles) {
                        Modifier
                            .background(TileGlassRest)
                            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(TileCornerRadius))
                    } else {
                        // Off (default): bare artwork at rest, no card — only
                        // the focused overlay below draws any chrome.
                        Modifier
                    }
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick ?: { launchApp(context, app.packageName, onAppLaunched) },
                    onLongClick = onLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            // Focused glass SURFACE — behind the artwork, so the art still
            // reads as lit rather than washed out. The lit FRAME that used to
            // be drawn here has moved below the artwork in this Box (see S23),
            // because a child drawn earlier is painted underneath.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = focusFraction }
                    .background(TileGlassFocused)
            )

            // Top-left specular highlight (Aero's light source), drawn under
            // the artwork so the icon/banner stays crisp on top. Only in
            // glassTiles mode — it's part of the glass look, not the plain one.
            if (glassTiles) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawWithCache {
                            val glow = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFE2F0FC).copy(alpha = 0.40f + 0.32f * focusFraction),
                                    Color(0xFFD2E8FA).copy(alpha = 0.10f),
                                    Color.Transparent
                                ),
                                center = Offset(size.width * 0.18f, size.height * 0.18f),
                                radius = max(size.width, size.height) * 0.75f
                            )
                            onDrawBehind { drawRect(glow) }
                        }
                )
            }

            // S19 — SNAP faded<->full on focus instead of crossfading. The
            // old crossfade stacked TWO artwork layers, each wrapped in a
            // graphicsLayer{alpha}, so during every focus transition the box
            // did two alpha-composited offscreen passes PER TILE — the
            // transition-time cost that kept faded-ON janky even after the
            // faded bitmap was baked (median was fine, but 90th/95th frame
            // times and jank% stayed high — the hitch you feel while
            // scrolling). Now a single artwork draws, faded when the tile is
            // unfocused, full when focused. The simultaneous 1.12x scale-up +
            // glow already animate the focus feedback, so the color switch
            // reads as part of that pop rather than a missing fade. Keys off
            // the boolean isFocused (flips once per focus change), so it costs
            // one cheap recomposition, not a per-frame layer.
            TileArtwork(
                app,
                inset = glassTiles,
                preferIcons = preferIcons,
                faded = fadedWhenUnfocused && !isFocused
            )

            // S23 — the lit frame + outer glow draw ON TOP of the artwork.
            //
            // They used to be part of the glass-surface Box above, i.e. the
            // FIRST child — and in a Box, earlier children are painted
            // underneath later ones. With glass tiles OFF (the default) the
            // banner draws edge-to-edge via fillMaxSize(), so it painted
            // straight over the 2dp white frame along the left and right
            // edges, where a 16:9 banner binds first. That read as "the
            // banner is slightly too large, covering the border" — the banner
            // was the right size; it was simply in front of the frame.
            //
            // Fixing it by shrinking the artwork would have been the wrong
            // move: WMC's highlight frame sits in front of the content. This
            // keeps the art full-bleed and puts the frame where it belongs.
            //
            // S9 — pale blue-white breathing glow per real WMC 7: the
            // highlight is NOT a uniform ring — it's brightest along the top
            // edge and fades down the sides, so both the soft glow and the
            // thin frame use a vertical gradient stroke. Drawn (not border
            // modifiers) so the pulse only invalidates the draw pass, never
            // recomposes (S10).
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = focusFraction }
                    .drawWithCache {
                        val cornerRadius = CornerRadius(TileCornerRadius.toPx())
                        val glowWidth = 6.dp.toPx()
                        val frameWidth = 2.dp.toPx()
                        val frameBrush = Brush.verticalGradient(
                            0f to Color.White,
                            1f to Color.White.copy(alpha = 0.35f)
                        )
                        onDrawBehind {
                            val pulse = 0.7f + 0.3f * glowPulse.value
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    0f to Color(0xFFEAF5FF).copy(alpha = 0.60f * pulse),
                                    0.45f to Color(0xFFCFE6FF).copy(alpha = 0.25f * pulse),
                                    1f to Color(0xFFCFE6FF).copy(alpha = (0.08f * pulse).coerceAtLeast(0f))
                                ),
                                topLeft = Offset(glowWidth / 2f, glowWidth / 2f),
                                size = Size(size.width - glowWidth, size.height - glowWidth),
                                cornerRadius = cornerRadius,
                                style = Stroke(glowWidth)
                            )
                            drawRoundRect(
                                brush = frameBrush,
                                topLeft = Offset(frameWidth / 2f, frameWidth / 2f),
                                size = Size(size.width - frameWidth, size.height - frameWidth),
                                cornerRadius = cornerRadius,
                                style = Stroke(frameWidth)
                            )
                        }
                    }
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(WmcAccentCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✓", color = Color.Black, fontSize = 13.sp)
                }
            }
        }

        // S23 — S5's mirrored reflection is REMOVED (Lou's call).
        //
        // It never lined up. The tile above scales to 1.12x through a
        // graphicsLayer, which is a DRAW-time transform: the tile's layout
        // box stays 220x130dp, so the reflection slot beneath it kept its
        // unscaled 220dp width while the focused tile drew 246dp wide and
        // overhung ~8dp into the slot from above. The result read as a small
        // detached smudge below the tile rather than a mirror of it.
        //
        // Removing it also drops a second full TileArtwork composition and a
        // BlendMode.DstIn pass on the focused tile — the one tile already
        // doing the most work per frame.
        //
        // If it ever comes back: it must scale with the tile (share the same
        // `scale` value) and be pinned flush to the SCALED bottom edge, not
        // laid out as an independent fixed-height slot.

        if (showLabel) {
            Text(
                text = app.label,
                color = if (isFocused) WmcAccentCyan else WmcTextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = TileLabelTopGap)
                    .width(TileWidth)
                    // S9 — in classic mode the label fades in with focus;
                    // layout space is always reserved either way.
                    .graphicsLayer { alpha = if (labelOnlyWhenFocused) focusFraction else 1f }
            )
        }
    }
}

@Composable
private fun TileArtwork(app: AppInfo, inset: Boolean, preferIcons: Boolean = false, faded: Boolean = false) {
    // S11 — faded mode: vector system icons and text fallbacks just tint to
    // the pale blue; bitmap artwork swaps to the copy baked at discovery time.
    val flatColor = if (faded) TileFadedTint.copy(alpha = 0.8f) else WmcTextPrimary

    // Built-in system-action cards have no app artwork — draw their mapped
    // vector icon (centered, tinted) instead of falling back to the label,
    // which would otherwise duplicate the label shown beneath the tile.
    val systemIconRes = systemActionIconRes(app.packageName)
    if (systemIconRes != null) {
        Image(
            painter = painterResource(systemIconRes),
            contentDescription = app.label,
            colorFilter = ColorFilter.tint(flatColor),
            modifier = Modifier.size(64.dp)
        )
        return
    }

    // S22 — pure selection, zero work: the faded silhouettes were baked at
    // discovery time. `?:` falls back to the full-colour bitmap if a bake
    // failed, so a tile can never render blank. If a bitmap is null here it
    // is because the app genuinely ships no artwork.
    val bannerArt = if (faded) (app.fadedBanner ?: app.banner) else app.banner
    val iconArt = if (faded) (app.fadedIcon ?: app.icon) else app.icon

    // S11 — icon mode swaps the preference order: centered icon first,
    // banner only as the fallback when the app has no icon.
    val banner = if (preferIcons) null else bannerArt
    val icon = iconArt
    val fallbackBanner = if (preferIcons) bannerArt else null

    if (inset) {
        // S7 (glassTiles on) — inset, don't fill: banner (primary) is drawn
        // smaller than the card with margin so the glass card + top-left
        // glow still show around it; icon fallback is centered at a fixed
        // size.
        when {
            banner != null -> Image(
                painter = BitmapPainter(banner),
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(14.dp).fillMaxSize()
            )
            icon != null -> Image(
                painter = BitmapPainter(icon),
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(64.dp)
            )
            fallbackBanner != null -> Image(
                painter = BitmapPainter(fallbackBanner),
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(14.dp).fillMaxSize()
            )
            else -> Text(
                text = app.label,
                color = flatColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
    } else {
        // glassTiles off (default) — full banner/icon, edge to edge, no card.
        // Icon mode instead centers the icon at a fixed size (WMC-glyph
        // style) rather than blowing it up to tile height.
        when {
            preferIcons && icon != null -> Image(
                painter = BitmapPainter(icon),
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(80.dp)
            )
            (banner ?: icon ?: fallbackBanner) != null -> Image(
                painter = BitmapPainter((banner ?: icon ?: fallbackBanner)!!),
                contentDescription = app.label,
                modifier = Modifier.fillMaxSize()
            )
            else -> Text(
                text = app.label,
                color = flatColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

private fun launchApp(context: Context, packageName: String, onLaunched: (String) -> Unit) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    try {
        context.startActivity(launchIntent)
        onLaunched(packageName)
    } catch (e: Exception) {
        // App may have just been uninstalled out from under us — ignore.
    }
}

/** Drawable for a built-in system-action card, or null for a normal app. */
private fun systemActionIconRes(packageName: String): Int? = when (packageName) {
    SystemActions.ALL_APPS -> R.drawable.ic_sys_all_apps
    SystemActions.EDIT_ROWS -> R.drawable.ic_sys_edit_rows
    SystemActions.SETTINGS -> R.drawable.ic_sys_settings
    SystemActions.GOOGLE_TV_HOME -> R.drawable.ic_sys_google_tv_home
    else -> null
}
