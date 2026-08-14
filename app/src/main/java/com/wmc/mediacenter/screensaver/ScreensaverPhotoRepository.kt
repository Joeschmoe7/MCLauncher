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
import kotlin.math.min
import kotlin.math.roundToInt

/** One photo on disk plus the calendar date it was taken (or, failing that, last modified). */
data class ScreensaverPhoto(val file: File, val dateTaken: LocalDate)

/** A cluster of photos from the same day — the unit the screensaver dwells on before rotating. */
data class DayGroup(val date: LocalDate, val photos: List<ScreensaverPhoto>)

/**
 * S37 — outcome of [ScreensaverPhotoRepository.copyPhotos]. [error] is set only
 * when nothing could be attempted at all (unreadable source, uncreatable
 * destination); individual photo failures are counted in [failed] instead.
 */
data class CopyResult(
    val copied: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val error: String? = null
) {
    /** Message for the user — states what happened rather than just "done". */
    fun summary(): String = when {
        error != null -> error
        copied == 0 && skipped > 0 && failed == 0 -> "Those $skipped photos are already on this box"
        copied == 0 && failed == 0 -> "No photos found to copy"
        else -> buildString {
            append("Copied $copied photo${if (copied == 1) "" else "s"} to this box")
            if (skipped > 0) append(", $skipped already there")
            if (failed > 0) append(", $failed couldn't be read")
        }
    }
}

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
     * Strict per-calendar-day grouping was the original design, but a diverse
     * photo set (e.g. downloaded stock photos, each with its own distinct
     * real EXIF date) mostly produces one-photo groups — and the wall's
     * cell-fill logic cycles a group's pool with `i % pool.size`, which
     * degenerates to "the same single photo in every cell" when the pool
     * size is 1. Confirmed on-device: Lou reported the grid showing one
     * photo repeated across the whole wall for most groups, with variety
     * only in groups that happened to have multiple photos. Fix: merge
     * adjacent (by date) groups until each has at least [MinGroupSize]
     * photos, so no group can ever be a singleton unless the whole folder
     * has fewer photos than that.
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
        if (photos.isEmpty()) return emptyList()

        val byDateDescending = photos
            .groupBy { it.dateTaken }
            .entries
            .sortedByDescending { it.key }

        // Two-pass: accumulate UNCAPPED buckets first, folding any leftover
        // tail (smaller than MinGroupSize) into the previous bucket rather
        // than emitting it as its own small group — that leftover-as-its-
        // own-group bug was still producing an occasional 1-2-photo group
        // in rotation, even after the initial merge fix. Applying
        // MaxPhotosPerDayGroup only at the very end (not per-bucket during
        // accumulation) matters too: capping early would have silently
        // discarded the folded-in tail photos rather than actually merging
        // them.
        val buckets = mutableListOf<Pair<LocalDate, MutableList<ScreensaverPhoto>>>()
        var current = mutableListOf<ScreensaverPhoto>()
        var currentDate: LocalDate? = null
        for ((date, photosOnDate) in byDateDescending) {
            if (currentDate == null) currentDate = date
            current.addAll(photosOnDate)
            if (current.size >= MinGroupSize) {
                buckets.add(currentDate to current)
                current = mutableListOf()
                currentDate = null
            }
        }
        if (current.isNotEmpty()) {
            if (buckets.isNotEmpty()) {
                buckets.last().second.addAll(current)
            } else {
                // The whole folder has fewer than MinGroupSize photos —
                // this is simply all there is, nothing to fold into.
                buckets.add(currentDate!! to current)
            }
        }
        return buckets.map { (date, list) -> DayGroup(date, list.take(MaxPhotosPerDayGroup)) }
    }

    /**
     * S37 — copies the image files sitting directly in [from] into [to], so a
     * USB stick can be unplugged afterwards rather than living in the box
     * forever.
     *
     * Deliberately NON-RECURSIVE, matching [scanFolder]: what gets copied is
     * exactly what the screensaver would have read from that folder, so the
     * count shown in the picker is the count that ends up on the device.
     *
     * MERGES rather than replaces — a same-named file of the same length is
     * assumed to be the same photo and skipped, so copying twice is harmless
     * and a second stick can be added to the first one's photos. Nothing is
     * ever deleted from either side.
     *
     * A plain byte copy preserves EXIF, and the last-modified time is carried
     * across explicitly, so both date sources [dateTakenOf] uses survive the
     * trip. Per-file failures are counted rather than thrown — one unreadable
     * photo on a flaky stick should not abandon the other forty.
     *
     * MUST be called off the main thread.
     */
    fun copyPhotos(from: File, to: File): CopyResult {
        val sources = try {
            from.listFiles { f -> f.isFile && f.extension.lowercase(Locale.US) in IMAGE_EXTENSIONS }
        } catch (e: Exception) {
            null
        } ?: return CopyResult(error = "Couldn't read ${from.absolutePath}")

        if (!to.isDirectory && !to.mkdirs()) {
            return CopyResult(error = "Couldn't create ${to.absolutePath}")
        }

        var copied = 0
        var skipped = 0
        var failed = 0
        for (source in sources) {
            val target = File(to, source.name)
            if (target.isFile && target.length() == source.length()) {
                skipped++
                continue
            }
            try {
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setLastModified(source.lastModified())
                copied++
            } catch (e: Exception) {
                // Leave no half-written file behind to be decoded later.
                runCatching { target.delete() }
                failed++
            }
        }
        return CopyResult(copied = copied, skipped = skipped, failed = failed)
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
     * Three-pass downsampled decode (bounds → power-of-two sampled decode →
     * exact fine scale) producing a bitmap that FITS INSIDE
     * [maxWidth]x[maxHeight] with its aspect ratio preserved.
     *
     * S35 correction #11 (round 10, measured: the screensaver's "blurry while
     * scrolling" and residual stutter turned out to share this root cause).
     * Two separate bugs lived here:
     *
     *   1. inSampleSize is power-of-two ONLY, and [computeInSampleSize]'s
     *      (stock AOSP) semantics are "largest power of two that keeps BOTH
     *      dimensions >= the request" — i.e. it deliberately errs LARGE. A
     *      3456px-wide source halves to 1728 < 1920, so it did not downsample
     *      at ALL and stayed resident at 3456x2304 = 31.8MB. With
     *      MaxPhotosPerDayGroup = 16 that is 160-320MB of ARGB_8888 per day
     *      group on a 1.97GB box against a 398MB GPU texture budget — which
     *      is exactly the 360MB/398MB + "Slow bitmap uploads" that
     *      `dumpsys gfxinfo` reported. Tuning the caller's
     *      DecodeSizeMultiplier could never fix this: the power-of-two step,
     *      not the multiplier, was the binding constraint. Fixed by keeping
     *      inSampleSize as the cheap bulk reduction and adding an exact fine
     *      scale on top, so the result actually lands on the requested box.
     *   2. No mipmaps. See [scaleToFit]'s setHasMipMap call.
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
        BitmapFactory.decodeFile(file.absolutePath, options)
            ?.let { scaleToFit(it, maxWidth, maxHeight) }
            ?.asImageBitmap()
    } catch (e: OutOfMemoryError) {
        // NOT covered by the `Exception` branch below — OutOfMemoryError is an
        // Error. Decoding is the one thing in this app that can genuinely
        // exhaust the heap (a single large photo is tens of MB as ARGB_8888,
        // and this runs on 2GB TV boxes with no android:largeHeap), so
        // without this branch an unlucky photo takes the whole DreamService
        // down mid-screensaver instead of being skipped like every other
        // unreadable file. Callers already treat null as "skip this one".
        null
    } catch (e: Exception) {
        null
    }

    /**
     * Exact aspect-preserving fine scale down into [maxWidth]x[maxHeight],
     * finishing what the power-of-two [computeInSampleSize] pass can only
     * approximate. The intermediate is recycled immediately — it can be ~4x
     * the size of the result, and holding both is what pushed this box to its
     * texture-memory ceiling.
     *
     * S35 correction #11 — setHasMipMap is the OTHER half of this fix, and
     * the one that addresses "pictures look blurry when scrolling". At
     * overview the camera scale is ~0.25 and a photo occupies roughly
     * 206x137px on screen, so even a correctly-sized ~1620x1080 bitmap is
     * being minified ~8x. Compose's drawImage on Android has no mipmap path
     * at all — FilterQuality collapses to a single `isFilterBitmap` boolean
     * (see AndroidPaint.android.kt's own AOSP comment: "Framework only
     * supports bilinear filtering which maps to FilterQuality.low"), which is
     * why round 9's FilterQuality.High experiment was a literal no-op rather
     * than a disproof. A 2x2 bilinear tap against ~64 source texels per
     * output pixel aliases severely, and because camScale changes every
     * frame, WHICH texels get sampled changes every frame — the image boils.
     * A boiling image under camera motion is what reads to the eye as motion
     * blur/smearing, and it also explains why single captured frames looked
     * sharp (aliased frames ARE sharp, just wrong; the artifact is purely
     * temporal). setHasMipMap is a hint HWUI honours for minified bitmaps.
     */
    private fun scaleToFit(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap? {
        val fit = fitFactor(source.width, source.height, maxWidth, maxHeight)
        if (fit >= 1f) {
            // Already inside the box — never upsample, that only wastes memory.
            source.setHasMipMap(true)
            return source
        }
        val targetWidth = (source.width * fit).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * fit).roundToInt().coerceAtLeast(1)
        val scaled = try {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        } catch (e: OutOfMemoryError) {
            // Both bitmaps are briefly live here, so this is the single most
            // likely place in the app to run out of heap. Free the large one
            // immediately rather than waiting for GC — the caller is about to
            // decode the next photo and would otherwise hit the same wall.
            source.recycle()
            return null
        }
        if (scaled !== source) source.recycle()
        scaled.setHasMipMap(true)
        return scaled
    }

    /**
     * The bulk (cheap, decode-time) reduction only — the largest power of two
     * that still leaves BOTH dimensions at or above the fitted target, so
     * [scaleToFit] only ever scales down and never has to upsample.
     *
     * S36 review fix — this used to halve while both raw dimensions stayed
     * above maxWidth/maxHeight independently, which stops far too early when
     * a photo's aspect ratio is nothing like the target box's: a 12000x1500
     * panorama failed `750 >= 795` on the very first iteration and so was not
     * downsampled AT ALL, decoding as a single 72MB ARGB_8888 bitmap purely
     * to hand [scaleToFit] something it immediately shrank to 1184x148. That
     * was the main trigger for the OutOfMemoryError paths above. Deriving the
     * sample size from the same fit factor [scaleToFit] uses keeps the two in
     * agreement by construction — the same panorama now samples to 1500x187.
     */
    private fun computeInSampleSize(rawWidth: Int, rawHeight: Int, maxWidth: Int, maxHeight: Int): Int {
        val fit = fitFactor(rawWidth, rawHeight, maxWidth, maxHeight)
        if (fit >= 1f) return 1
        val maxSample = (1f / fit).toInt()
        var sampleSize = 1
        while (sampleSize * 2 <= maxSample) sampleSize *= 2
        return sampleSize
    }

    /**
     * How much [rawWidth]x[rawHeight] has to shrink to fit inside
     * [maxWidth]x[maxHeight] with its aspect ratio intact. Returns 1f (i.e.
     * "no scaling") for degenerate input rather than 0 or infinity — a zero
     * cap used to drive computeInSampleSize's loop until Int overflow made it
     * divide by zero.
     */
    private fun fitFactor(rawWidth: Int, rawHeight: Int, maxWidth: Int, maxHeight: Int): Float {
        if (rawWidth <= 0 || rawHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) return 1f
        return min(maxWidth / rawWidth.toFloat(), maxHeight / rawHeight.toFloat())
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

        // Upper bound on how many photos one day-group may hold resident as
        // decoded bitmaps at once — the memory ceiling, not an aesthetic
        // choice. At the current decode size (~1184x795, sized to a focused
        // cell's on-screen footprint) that is roughly 60MB per group, and
        // BOTH the outgoing and incoming group are briefly live during the
        // reveal dissolve, so the real peak is about double this.
        //
        // S36 review fix — the comment here used to describe a 12-cell (4x3)
        // wall and claim the pool is deliberately kept LARGER than the grid.
        // Both halves went stale when the grid grew to GridColumns 8 x
        // GridRows 6 = 48: the pool is now three times SMALLER than the grid,
        // and PhotoWallScreensaver tiles it with `i % pool.size` so each
        // photo appears in about three cells. Nothing depends on a surplus
        // any more (the cross-fade-in-place shot that did is disabled).
        private const val MaxPhotosPerDayGroup = 16
        // S35 correction — see scanFolder's doc comment: prevents the
        // single-photo-tiled-everywhere bug.
        private const val MinGroupSize = 8
        /**
         * Public so FolderPickerScreen can count photos with exactly the same
         * rule scanFolder uses — a picker that disagrees with the scanner about
         * what counts as a photo is worse than no picker.
         */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        // SimpleDateFormat is NOT thread-safe; discovery may run on any IO
        // thread, so hand out one per thread rather than sharing an instance
        // (same pattern as AppRepository's fadePaint ThreadLocal).
        private val exifDateFormat = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        }
    }
}
