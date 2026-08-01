package com.wmc.mediacenter.screensaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** One photo on disk plus the calendar date it was taken (or, failing that, last modified). */
data class ScreensaverPhoto(val file: File, val dateTaken: LocalDate)

/** A cluster of photos from the same day — the unit the screensaver dwells on before rotating. */
data class DayGroup(val date: LocalDate, val photos: List<ScreensaverPhoto>)

/**
 * S35 — folder-based photo source for the screensaver (deliberately NOT
 * MediaStore: this is a streaming box, not a phone with a camera roll — see
 * the AskUserQuestion decision in the S35 plan). Mirrors AppRepository's
 * established discipline for this hardware: decode off the caller's thread,
 * downsample aggressively, never hold more than the current day-group's
 * bitmaps in memory at once. No LRU cache needed at this scale — callers are
 * expected to decode only the active DayGroup's ~10 photos and let the
 * previous group's bitmaps be garbage collected when they advance.
 */
class ScreensaverPhotoRepository {

    /**
     * Scans [folder] for images, groups them by capture date, and caps each
     * group at [MaxPhotosPerDayGroup]. Groups are sorted most-recent-first;
     * empty/unreadable folders return an empty list rather than throwing —
     * callers show a graceful "no photos found" message instead of a crash.
     * A single unreadable file is skipped, never fails the whole scan (same
     * discipline as AppRepository.toAppInfoOrNull).
     *
     * MUST be called off the main thread.
     */
    fun scanFolder(folder: File): List<DayGroup> {
        val files = try {
            folder.listFiles { f -> f.isFile && f.extension.lowercase(Locale.US) in IMAGE_EXTENSIONS }
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val photos = files.mapNotNull { file ->
            try {
                ScreensaverPhoto(file, dateTakenOf(file))
            } catch (e: Exception) {
                null
            }
        }

        return photos
            .groupBy { it.dateTaken }
            .map { (date, photosOnDate) -> DayGroup(date, photosOnDate.take(MaxPhotosPerDayGroup)) }
            .sortedByDescending { it.date }
    }

    /**
     * EXIF capture date first (what a real photo's "date" means to a
     * person), falling back to file-modified time for screenshots/exports/
     * anything EXIF-stripped. Never throws — an unreadable EXIF block falls
     * straight through to the mtime fallback.
     */
    private fun dateTakenOf(file: File): LocalDate {
        val exifDate = try {
            ExifInterface(file.absolutePath)
                .getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.let { raw -> exifDateFormat.get()!!.parse(raw) }
        } catch (e: Exception) {
            null
        }
        val date = exifDate ?: Date(file.lastModified())
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    }

    /**
     * Two-pass downsampled decode (bounds first to compute inSampleSize,
     * then the real decode) capped at roughly [maxWidth]x[maxHeight] — sized
     * by the caller with headroom for the camera's zoom-in. Deliberately not
     * true Deep-Zoom tiling: generous downsampling is the pragmatic trade on
     * this hardware (see LAUNCHER_PLAYBOOK.md's artwork-pipeline lessons).
     *
     * MUST be called off the main thread.
     */
    fun decodeDownsampled(file: File, maxWidth: Int, maxHeight: Int): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    private fun computeInSampleSize(rawWidth: Int, rawHeight: Int, maxWidth: Int, maxHeight: Int): Int {
        if (rawWidth <= 0 || rawHeight <= 0) return 1
        var sampleSize = 1
        while (rawWidth / (sampleSize * 2) >= maxWidth && rawHeight / (sampleSize * 2) >= maxHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    companion object {
        /**
         * Used whenever [com.wmc.mediacenter.data.AppSettings.screensaverFolderPath]
         * is null — consistent with the `/sdcard/MCLauncher/` convention
         * BackupRepository already established. Not app-private storage:
         * this needs to be a place Lou can actually drop files into over
         * adb/a file manager, same reasoning as the backup path.
         */
        const val DEFAULT_FOLDER_PATH = "/sdcard/MCLauncher/Screensaver"

        private const val MaxPhotosPerDayGroup = 10
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        // SimpleDateFormat is NOT thread-safe; discovery may run on any IO
        // thread, so hand out one per thread rather than sharing an instance
        // (same pattern as AppRepository's fadePaint ThreadLocal).
        private val exifDateFormat = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        }
    }
}
