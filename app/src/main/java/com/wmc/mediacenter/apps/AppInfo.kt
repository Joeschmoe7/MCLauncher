package com.wmc.mediacenter.apps

import androidx.compose.ui.graphics.ImageBitmap

/**
 * One launchable app as discovered by [AppRepository].
 *
 * [banner] is the TV banner (320x180) if the app declares one via
 * android:banner; most non-TV apps won't have one, so tiles fall back
 * to [icon] and finally to a plain text label.
 *
 * [icon]/[banner] are pre-converted to [ImageBitmap] at discovery time
 * (see [AppRepository]) instead of being converted from [android.graphics.drawable.Drawable]
 * during Compose composition — that conversion is expensive and was
 * previously re-run on the main thread every time a tile scrolled back
 * into view.
 *
 * S22 — [fadedIcon]/[fadedBanner] are the pale-blue silhouettes used by
 * unfocused tiles when the "Fade unhighlighted tiles" setting is on. They
 * are baked ONCE per package at discovery time, on the same background
 * thread, for exactly the same reason: baking them lazily inside AppTile's
 * composition put a full bitmap allocation + software colour-matrix pass on
 * the main thread on every focus change and every tile that scrolled in,
 * which was the stutter at the start of every row transition. See
 * FadedArtwork.kt for the full history.
 *
 * Null when the corresponding source artwork is null, or if the bake failed
 * — call sites must fall back to the unfaded artwork rather than drawing
 * nothing.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val banner: ImageBitmap?,
    val fadedIcon: ImageBitmap? = null,
    val fadedBanner: ImageBitmap? = null
)
