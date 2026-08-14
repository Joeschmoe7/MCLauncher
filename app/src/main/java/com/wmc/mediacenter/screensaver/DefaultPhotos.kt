package com.wmc.mediacenter.screensaver

import android.content.Context
import android.util.Log
import java.io.File

/**
 * S37 — the photos the screensaver falls back to when the configured folder is
 * empty, so it does something worth looking at on a box nobody has copied
 * anything onto yet.
 *
 * Bundled in `assets/screensaver/`, extracted on first use into app-private
 * storage. App-private is deliberate: it needs no permission at all, so the
 * default set works even on an install where MANAGE_EXTERNAL_STORAGE was never
 * granted — which is exactly the situation where somebody has not set anything
 * up and most needs a sensible default.
 *
 * Extraction is lazy (only when the configured folder turns up empty) and
 * idempotent, so the ~4.7MB is not duplicated on disk for anyone who does use
 * their own photos.
 *
 * Every bundled photo is public domain or CC0 — see `assets/screensaver/
 * CREDITS.txt`, which lists title, author, licence and source URL for each.
 * Attribution is not legally required for any of them; it is recorded anyway.
 */
object DefaultPhotos {

    private const val ASSET_DIR = "screensaver"
    private const val DEST_DIR = "screensaver-default"
    private const val DATES_FILE = "dates.txt"
    private const val TAG = "MCLauncherScreensaver"

    /**
     * Returns the directory holding the bundled photos, extracting them first
     * if needed. Returns the directory even on partial failure — a folder with
     * some photos in it still beats a "no photos" screen.
     *
     * MUST be called off the main thread.
     */
    fun ensureExtracted(context: Context): File {
        val dest = File(context.filesDir, DEST_DIR)
        if (!dest.isDirectory && !dest.mkdirs()) {
            Log.w(TAG, "Could not create $dest for the bundled photos")
            return dest
        }
        val assets = context.assets
        val names = try {
            assets.list(ASSET_DIR).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list bundled photos", e)
            return dest
        }

        val dates = readDates(context)
        for (name in names) {
            if (name.substringAfterLast('.', "").lowercase() !in
                ScreensaverPhotoRepository.IMAGE_EXTENSIONS
            ) {
                continue    // CREDITS.txt, dates.txt
            }
            val target = File(dest, name)
            try {
                // Length check doubles as the "already extracted" test and as
                // a repair for a copy interrupted half-way.
                val expected = assets.openFd("$ASSET_DIR/$name").use { it.length }
                if (target.isFile && target.length() == expected) continue
                assets.open("$ASSET_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                // openFd fails for entries the build compressed; fall back to
                // an unconditional copy rather than skipping the photo.
                try {
                    if (target.isFile && target.length() > 0) continue
                    assets.open("$ASSET_DIR/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Could not extract bundled photo $name", e2)
                    runCatching { target.delete() }
                    continue
                }
            }
            // Real capture dates, so the wall groups these by when they were
            // actually taken and the printed caption means something. Without
            // this every bundled photo would carry the install date, because
            // resizing them for bundling stripped their EXIF.
            dates[name]?.let { target.setLastModified(it) }
        }
        return dest
    }

    /** `filename<TAB>epochSeconds` per line, `#` comments. Missing/garbled lines are simply skipped. */
    private fun readDates(context: Context): Map<String, Long> = try {
        context.assets.open("$ASSET_DIR/$DATES_FILE").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                if (line.startsWith("#") || line.isBlank()) return@mapNotNull null
                val parts = line.split('\t')
                val seconds = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: return@mapNotNull null
                parts[0].trim() to seconds * 1000L
            }.toMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
