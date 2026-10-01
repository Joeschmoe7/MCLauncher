package com.wmc.mediacenter.apps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * User-supplied tile artwork. Drop an image into [FOLDER_PATH] named after
 * what it should replace:
 *
 *  - an app: its package name, e.g. `com.netflix.ninja.png`
 *  - a shortcut card: its label, e.g. `Movies.jpg` (case-insensitive)
 *
 * PNG, JPEG and WebP are read. The image replaces both the app's banner and
 * its icon, so it shows whichever "Tile artwork" setting is chosen (icon mode
 * falls back to the banner when there's no icon). Landscape 16:9-ish images
 * fit the tile best; anything else is letterboxed, never cropped.
 *
 * Reading the folder needs the same "All files access" grant as backup and
 * the screensaver (MANAGE_EXTERNAL_STORAGE). Without it the folder simply
 * reads as empty and every tile keeps its normal artwork.
 *
 * Decoding follows the AppRepository rules (NOTES.md §4/§8): it happens only
 * in [rescan], which callers run on Dispatchers.IO during discovery, and the
 * result is capped at the size a tile actually draws at. Lookups ([forName])
 * are a map read, safe from the main thread.
 */
class CustomArtwork(private val maxWidth: Int, private val maxHeight: Int) {

    class Art(val image: ImageBitmap, val faded: ImageBitmap?)

    @Volatile
    private var byName: Map<String, Art> = emptyMap()

    /** Decoded art by "path|lastModified|length", so an unchanged file is never decoded twice. */
    private val decoded = HashMap<String, Art>()

    /** Fingerprint of the folder's contents as of the last [rescan]. */
    @Volatile
    var lastStamp: String = ""
        private set

    fun forName(name: String): Art? = byName[name.lowercase()]

    /**
     * Fingerprint of what's in the folder right now (names, sizes, mtimes).
     * Cheap — a directory listing, no decoding — so it can be checked on every
     * return to Home to notice artwork added over adb or a file manager.
     * Blocking I/O: call from Dispatchers.IO.
     */
    fun currentStamp(): String =
        imageFiles().joinToString("\n") { "${it.name}|${it.lastModified()}|${it.length()}" }

    /**
     * Re-reads the folder, decoding only new or changed files, and bakes each
     * one's faded copy with [fade]. Blocking I/O: call from Dispatchers.IO.
     */
    @Synchronized
    fun rescan(fade: (ImageBitmap) -> ImageBitmap?) {
        val files = imageFiles()
        val fresh = HashMap<String, Art>()
        val seen = HashSet<String>()
        for (file in files) {
            val key = "${file.path}|${file.lastModified()}|${file.length()}"
            seen += key
            val art = decoded[key] ?: decode(file)?.let { bitmap ->
                val image = bitmap.asImageBitmap()
                Art(image, fade(image)).also { decoded[key] = it }
            } ?: continue
            fresh[file.nameWithoutExtension.lowercase()] = art
        }
        decoded.keys.retainAll(seen) // drop art for files since removed or replaced
        byName = fresh
        lastStamp = files.joinToString("\n") { "${it.name}|${it.lastModified()}|${it.length()}" }
    }

    private fun imageFiles(): List<File> = try {
        File(FOLDER_PATH).listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
    } catch (e: Exception) {
        emptyList() // no grant, no folder, unmounted storage — no custom art
    }

    /**
     * Decodes at most ~2x the tile size via inSampleSize (a 4K photo dropped
     * in by mistake must not allocate 33MB), then scales down to the cap.
     */
    private fun decode(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth && bounds.outHeight / (sample * 2) >= maxHeight) {
                sample *= 2
            }
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { full ->
                    val scale = minOf(maxWidth / full.width.toFloat(), maxHeight / full.height.toFloat(), 1f)
                    if (scale >= 1f) {
                        full
                    } else {
                        Bitmap.createScaledBitmap(
                            full,
                            (full.width * scale).toInt().coerceAtLeast(1),
                            (full.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                    }
                }
        }
    } catch (e: Exception) {
        null // unreadable or corrupt file — that tile keeps its normal artwork
    } catch (e: OutOfMemoryError) {
        null
    }

    companion object {
        val FOLDER_PATH: String =
            File(Environment.getExternalStorageDirectory(), "MCLauncher/Artwork").absolutePath
        private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
