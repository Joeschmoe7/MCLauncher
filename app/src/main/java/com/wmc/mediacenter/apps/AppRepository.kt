package com.wmc.mediacenter.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

private const val CATEGORY_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"

/**
 * Discovers launchable apps for the home rows.
 *
 * Primary source is CATEGORY_LEANBACK_LAUNCHER (proper TV apps). We also
 * query the standard CATEGORY_LAUNCHER as a fallback so sideloaded
 * non-TV apps still show up instead of silently disappearing.
 *
 * Icons/banners are decoded and converted to [ImageBitmap] here — off the
 * main thread, since callers run [loadInstalledApps] under
 * Dispatchers.IO — and cached in memory (LruCache), since PackageManager
 * drawable lookups are relatively expensive and rows redraw often. Doing
 * the Drawable→ImageBitmap conversion once here (rather than lazily
 * during Compose composition) avoids re-decoding on every recomposition.
 *
 * S22 — the faded (unfocused-tile) silhouettes are baked here too, for the
 * same reason: doing it in AppTile put a bitmap allocation and a software
 * colour-matrix pass on the main thread every time a tile changed focus or
 * scrolled in. See FadedArtwork.kt.
 *
 * [displayDensity] is `resources.displayMetrics.density`; artwork is capped
 * at roughly the pixel size a tile actually draws at (see [maxArtWidth]).
 */
class AppRepository(
    private val packageManager: PackageManager,
    displayDensity: Float = 1f
) {

    // Tiles draw at 220x130dp and scale to 1.12x on focus, so ~246x146dp is
    // the largest they are ever sampled at. Capping in PIXELS derived from
    // the real display density keeps artwork sharp on a 320dpi TV panel
    // (density 2.0 -> ~492px) without hoarding 4x the pixels needed on a
    // density-1.0 panel. A fixed 512px cap was ~2x oversampled on the latter
    // and cost real memory and upload bandwidth for nothing.
    private val maxArtWidth = (246f * displayDensity).toInt().coerceIn(220, 512)
    private val maxArtHeight = (146f * displayDensity).toInt().coerceIn(130, 320)

    // Bounded by BYTES, not entry count. The old LruCache(128) counted
    // entries, so with large artwork it could hold ~80MB on a 2GB box — and
    // S22 doubles the entries by caching a faded copy per source. sizeOf
    // makes the ceiling real.
    private val artworkCache = object : LruCache<String, ImageBitmap>(ARTWORK_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    fun loadInstalledApps(): List<AppInfo> {
        val byPackage = LinkedHashMap<String, ResolveInfo>()

        // Leanback first, so an app declaring both categories keeps its TV
        // entry activity (and TV banner) rather than its phone entry.
        val leanback = queryFor(Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_LEANBACK_LAUNCHER))
        leanback.forEach { byPackage.putIfAbsent(it.activityInfo.packageName, it) }
        val tvPackages = leanback.mapTo(HashSet()) { it.activityInfo.packageName }

        queryFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .forEach { byPackage.putIfAbsent(it.activityInfo.packageName, it) }

        return byPackage.values
            .mapNotNull { toAppInfoOrNull(it, isTvApp = it.activityInfo.packageName in tvPackages) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Confirms whether [packageName] is actually still installed — used to
     * gate row pruning so a transient discovery failure (e.g. the LEANBACK
     * query failing on a boot race) can never be mistaken for an uninstall.
     * An unknown failure is treated as "still installed" (never prune on
     * doubt); only a definitive NameNotFoundException means it's gone.
     */
    fun isInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        true
    }

    /** Drops any cached artwork for [packageName], e.g. after ACTION_PACKAGE_REPLACED, so the next discovery pass re-reads fresh icon/banner instead of showing stale artwork indefinitely. Must also drop the baked faded copies, or an updated app keeps its old silhouette. */
    fun invalidate(packageName: String) {
        listOf("icon:$packageName", "banner:$packageName").forEach { key ->
            artworkCache.remove(key)
            artworkCache.remove("faded:$key")
        }
    }

    /** Drops all cached artwork. */
    fun clearCache() {
        artworkCache.evictAll()
    }

    private fun queryFor(intent: Intent): List<ResolveInfo> =
        try {
            // Flag 0, NOT MATCH_DEFAULT_ONLY: launcher entry activities don't
            // declare CATEGORY_DEFAULT, so MATCH_DEFAULT_ONLY hides nearly all apps.
            packageManager.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * A single malformed/half-uninstalled package should never take down
     * discovery for everything else — skip it instead.
     */
    private fun toAppInfoOrNull(resolveInfo: ResolveInfo, isTvApp: Boolean): AppInfo? {
        val packageName = resolveInfo.activityInfo?.packageName ?: return null
        return try {
            val iconKey = "icon:$packageName"
            val bannerKey = "banner:$packageName"
            val icon = loadArtwork(iconKey) { resolveInfo.loadIcon(packageManager) }
            val banner = loadArtwork(bannerKey) { loadBanner(resolveInfo) }
            AppInfo(
                packageName = packageName,
                label = resolveInfo.loadLabel(packageManager)?.toString() ?: packageName,
                icon = icon,
                banner = banner,
                fadedIcon = loadFaded(iconKey, icon),
                fadedBanner = loadFaded(bannerKey, banner),
                isTvApp = isTvApp
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun loadBanner(resolveInfo: ResolveInfo): Drawable? {
        val activityInfo = resolveInfo.activityInfo ?: return null
        val bannerRes = activityInfo.banner.takeIf { it != 0 }
            ?: activityInfo.applicationInfo?.banner?.takeIf { it != 0 }
            ?: return null
        return packageManager.getDrawable(activityInfo.packageName, bannerRes, activityInfo.applicationInfo)
    }

    /**
     * Loads via [loader], converts to [ImageBitmap] once, and caches under
     * [cacheKey]. S10 — capped at tile-ish resolution: some apps ship huge
     * banners/adaptive icons, and scaling oversized textures every frame
     * during row scrolling is real GPU bandwidth on the box. The cap is
     * derived from the display density (see [maxArtWidth]).
     */
    private inline fun loadArtwork(cacheKey: String, loader: () -> Drawable?): ImageBitmap? {
        artworkCache.get(cacheKey)?.let { return it }
        val bitmap = try {
            loader()?.let { render(it)?.asImageBitmap() }
        } catch (e: Exception) {
            null
        }
        if (bitmap != null) {
            artworkCache.put(cacheKey, bitmap)
        }
        return bitmap
    }

    /**
     * RENDER AT INTRINSIC SIZE, THEN SCALE. Do not "optimise" this back into a
     * single `toBitmap(scaledWidth, scaledHeight)` call.
     *
     * `Drawable.toBitmap(w, h)` has a fast path only for a plain
     * BitmapDrawable. Anything else — a <layer-list>, an inset/wrapper
     * drawable, or a <bitmap> declaring android:gravity — takes the slow path
     * (`setBounds(0,0,w,h)` then `draw()`), and a gravity-bearing bitmap
     * IGNORES those bounds: it draws at its natural size, centred, which
     * silently yields a centred crop.
     *
     * Drawing at the drawable's OWN intrinsic size first makes gravity a
     * no-op (natural size == bounds), so every drawable type renders in full.
     * Only then is the finished bitmap scaled down — pure pixel arithmetic
     * that no drawable can subvert.
     */
    private fun render(drawable: Drawable): Bitmap? {
        val iw = drawable.intrinsicWidth.takeIf { it in 1..MAX_INTRINSIC_PX } ?: maxArtWidth
        val ih = drawable.intrinsicHeight.takeIf { it in 1..MAX_INTRINSIC_PX } ?: maxArtHeight

        // toBitmap() short-circuits to the source bitmap for a plain
        // BitmapDrawable, so the common case allocates nothing extra here.
        val full = drawable.toBitmap(width = iw, height = ih)

        val scale = minOf(maxArtWidth / iw.toFloat(), maxArtHeight / ih.toFloat(), 1f)
        if (scale >= 1f) return full
        return Bitmap.createScaledBitmap(
            full,
            (iw * scale).toInt().coerceAtLeast(1),
            (ih * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * S22 — the pale-blue faded silhouette for [src], baked once and cached
     * under "faded:[cacheKey]". Runs on whatever thread discovery is on
     * (Dispatchers.IO), never on the main thread. Returns null when there is
     * no source artwork or the bake failed; callers fall back to the unfaded
     * bitmap rather than drawing nothing.
     */
    private fun loadFaded(cacheKey: String, src: ImageBitmap?): ImageBitmap? {
        if (src == null) return null
        val fadedKey = "faded:$cacheKey"
        artworkCache.get(fadedKey)?.let { return it }
        val faded = fadeBitmap(src) ?: return null
        artworkCache.put(fadedKey, faded)
        return faded
    }

    private companion object {
        // ~24MB of decoded artwork, faded copies included. Comfortable on a
        // 2GB box; large enough to hold every tile on Home plus the All Apps
        // grid without thrashing.
        const val ARTWORK_CACHE_BYTES = 24 * 1024 * 1024

        // Guard on the intrinsic-size render in [render]: a pathological
        // drawable claiming e.g. 8000px would otherwise allocate a ~256MB
        // temp bitmap. Above this we fall back to the tile-sized canvas —
        // such a drawable may crop, but it won't OOM the launcher.
        const val MAX_INTRINSIC_PX = 4096
    }
}
