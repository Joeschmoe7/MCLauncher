package com.wmc.mediacenter.screensaver

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * S37 — one entry per place the user could plausibly keep photos: internal
 * storage, plus any mounted USB stick or SD card.
 *
 * WHY THIS EXISTS. The screensaver's photo folder used to be a free-text box,
 * which made a USB drive effectively unusable: removable volumes mount under a
 * path derived from the drive's FAT volume serial — `/storage/1A2B-3C4D` — and
 * nothing on an Android TV surfaces that string. The system's own Storage
 * settings list a drive by its label ("SanDisk Cruzer"), never its path, so the
 * only way to learn it was `adb shell ls /storage/`. Asking someone to discover
 * that and then type it on an on-screen keyboard with a remote is not a
 * feature. [FolderPickerScreen] browses these instead.
 */
data class StorageRoot(
    /** Human label for the list — "Internal storage", "USB drive", a device name. */
    val label: String,
    val directory: File,
    val removable: Boolean
)

object StorageVolumes {

    /**
     * Internal storage first, then removable volumes.
     *
     * The path discovery leans on [Context.getExternalFilesDirs], which returns
     * this app's private directory ON EVERY MOUNTED VOLUME — e.g.
     * `/storage/1A2B-3C4D/Android/data/com.wmc.mediacenter/files`. Stripping the
     * `/Android/data/<pkg>/files` tail yields the volume root. That works on
     * every API level here and needs no permission, unlike
     * `StorageVolume.getDirectory()` which is API 30+. StorageManager is still
     * consulted, but only for nicer labels.
     */
    fun list(context: Context): List<StorageRoot> {
        // Keyed by path so the two discovery methods below can't produce the
        // same drive twice; insertion-ordered so internal storage stays first.
        val roots = LinkedHashMap<String, StorageRoot>()

        Environment.getExternalStorageDirectory()
            ?.takeIf { it.isDirectory }
            ?.let { roots[it.absolutePath] = StorageRoot("Internal storage", it, removable = false) }

        val labels = describeRemovable(context)

        // Method 1 — ask Android for this app's private directory on every
        // mounted volume and strip the tail. Needs no permission, works on
        // every API level here.
        for (dir in context.getExternalFilesDirs(null)) {
            addRemovable(roots, volumeRootOf(dir, context.packageName), labels)
        }

        // Method 2 — read /storage directly. Deliberately redundant with
        // method 1, because method 1 has a real failure mode on TV boxes:
        // Android may not create the per-app Android/data directory on a USB
        // stick until something writes to it, and getExternalFilesDirs then
        // omits that volume entirely — the drive would simply never appear in
        // the picker, with nothing to explain why. This needs
        // MANAGE_EXTERNAL_STORAGE, which the screensaver already requires to
        // read photos off a drive at all, so it costs nothing extra.
        for (dir in scanStorageDirectory()) {
            addRemovable(roots, dir, labels)
        }
        return roots.values.toList()
    }

    /** Adds [root] as a removable volume if it is real, readable and not already known. */
    private fun addRemovable(
        into: LinkedHashMap<String, StorageRoot>,
        root: File?,
        labels: Map<String, String>
    ) {
        if (root == null) return
        val path = root.absolutePath
        if (into.containsKey(path)) return
        // listFiles() returning null separates a genuinely mounted volume from
        // a leftover mount point for a drive that has been pulled out.
        if (!root.isDirectory || !root.canRead() || root.listFiles() == null) return
        val label = labels[root.name] ?: labels[path] ?: "USB or SD card"
        into[path] = StorageRoot("$label (${root.name})", root, removable = true)
    }

    /**
     * Mount points under /storage, minus the ones that are never a user's
     * photo drive: `emulated` is internal storage (already listed, and its
     * child `0` is what getExternalStorageDirectory returns), `self` is a
     * symlink directory the framework uses for per-process mounts.
     */
    private fun scanStorageDirectory(): List<File> = try {
        File("/storage").listFiles()
            ?.filter { it.name !in STORAGE_SKIP }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private val STORAGE_SKIP = setOf("emulated", "self", "sdcard0", "container", "knox-emulated")

    /**
     * `/storage/1A2B-3C4D/Android/data/<pkg>/files` -> `/storage/1A2B-3C4D`.
     * Returns null for anything not shaped like that, rather than guessing.
     */
    private fun volumeRootOf(filesDir: File?, packageName: String): File? {
        val suffix = "${File.separator}Android${File.separator}data${File.separator}" +
            "$packageName${File.separator}files"
        val path = filesDir?.absolutePath ?: return null
        if (!path.endsWith(suffix)) return null
        return File(path.removeSuffix(suffix)).takeIf { it.absolutePath.isNotEmpty() }
    }

    /** Friendly names keyed by both volume id and path — best effort, never throws. */
    private fun describeRemovable(context: Context): Map<String, String> = try {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        buildMap {
            for (volume in sm.storageVolumes) {
                if (!volume.isRemovable) continue
                val description = volume.getDescription(context) ?: continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory?.let { put(it.name, description); put(it.absolutePath, description) }
                }
                // API 26-29 has no public directory accessor; the uuid matches
                // the folder name for FAT volumes, which is the common case.
                volume.uuid?.let { put(it, description) }
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
