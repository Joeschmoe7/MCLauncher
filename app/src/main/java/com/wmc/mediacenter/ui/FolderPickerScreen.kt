package com.wmc.mediacenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wmc.mediacenter.screensaver.ScreensaverPhotoRepository
import com.wmc.mediacenter.screensaver.StorageVolumes
import com.wmc.mediacenter.ui.theme.WmcAccentCyan
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import com.wmc.mediacenter.ui.theme.WmcTileSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * S37 — D-pad folder browser for the screensaver's photo folder, replacing the
 * free-text path box.
 *
 * The text box could not be used to reach a USB stick in practice: removable
 * volumes live at a path derived from the drive's volume serial
 * (`/storage/1A2B-3C4D`), nothing on the TV displays that string, and typing it
 * on an on-screen keyboard with a remote is punishing even once you know it.
 * See [StorageVolumes].
 *
 * It also fixes a quieter trap. [ScreensaverPhotoRepository.scanFolder] is NOT
 * recursive, so picking a drive whose photos sit in `DCIM/2024/` produced "no
 * photos found" with nothing on screen explaining why. Browsing makes the
 * structure visible, and each folder shows how many photos are directly inside
 * it — so the right one is obvious before you commit to it.
 */
@Composable
fun FolderPickerScreen(
    initialPath: String,
    onPick: (path: String, message: String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    // null = the roots list (internal storage + any removable volumes).
    var current by remember { mutableStateOf<File?>(File(initialPath).takeIf { it.isDirectory }) }
    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var roots by remember { mutableStateOf<List<RootEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var copying by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var currentPhotoCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(current) {
        loading = true
        val dir = current
        withContext(Dispatchers.IO) {
            // Roots are refreshed on EVERY navigation, not just at the device
            // list. They decide whether the current folder is on removable
            // storage (and so whether copying is worth offering), and the
            // screen usually opens straight into a saved path without ever
            // showing the device list — so loading them lazily meant the copy
            // option could never appear. Also picks up a stick plugged in
            // while the picker is open.
            roots = StorageVolumes.list(context).map {
                RootEntry(it.label, it.directory, countPhotos(it.directory), it.removable)
            }
            // Counted here rather than in the list body: it hits the disk, and
            // in composition that runs on the main thread on every recompose.
            currentPhotoCount = if (dir == null) 0 else countPhotos(dir)
            entries = if (dir == null) emptyList() else childFolders(dir)
        }
        loading = false
    }

    /**
     * Copy into the DEFAULT folder rather than wherever the setting currently
     * points — if the setting still points at the stick, copying to it would
     * be a no-op that silently changed nothing.
     */
    fun copyHere(source: File) {
        copying = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ScreensaverPhotoRepository().copyPhotos(
                    from = source,
                    to = File(ScreensaverPhotoRepository.DEFAULT_FOLDER_PATH)
                )
            }
            copying = false
            if (result.error != null || result.copied == 0 && result.skipped == 0) {
                message = result.summary()
            } else {
                onPick(ScreensaverPhotoRepository.DEFAULT_FOLDER_PATH, result.summary())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .wmcBackground()
            .padding(top = 48.dp, bottom = 24.dp, start = 48.dp, end = 48.dp)
    ) {
        Text(text = "Screensaver photos folder", modifier = Modifier.padding(bottom = 6.dp))
        Text(
            text = current?.absolutePath ?: "Choose a storage device",
            color = WmcTextPrimary.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = if (current == null) {
                "Plug a USB stick in and it appears here."
            } else {
                "Photos must be directly inside the folder you choose — subfolders aren't searched."
            },
            color = WmcTextPrimary.copy(alpha = 0.5f),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 18.dp)
        )

        if (loading || copying) {
            Text(
                text = if (copying) "Copying photos to this box…" else "Reading…",
                color = WmcTextPrimary.copy(alpha = 0.6f),
                fontSize = 15.sp
            )
            return@Column
        }
        message?.let {
            Text(
                text = it,
                color = WmcAccentCyan,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (current == null) {
                items(roots) { root ->
                    PickerRow(
                        label = root.label,
                        detail = photoCountLabel(root.photoCount),
                        onClick = { current = root.directory }
                    )
                }
                item {
                    PickerRow(
                        label = "Reset to the default folder",
                        detail = ScreensaverPhotoRepository.DEFAULT_FOLDER_PATH,
                        onClick = { onPick(ScreensaverPhotoRepository.DEFAULT_FOLDER_PATH, null) }
                    )
                }
            } else {
                val here = current!!
                val photoCount = currentPhotoCount
                // Offered only where it changes anything: copying a folder that
                // already lives in internal storage onto internal storage is
                // just duplication.
                val onRemovable = roots.any {
                    it.removable && here.absolutePath.startsWith(it.directory.absolutePath)
                }
                if (photoCount > 0 && onRemovable) {
                    item {
                        PickerRow(
                            label = "⬇  Copy these photos to this box",
                            detail = "then you can unplug the drive",
                            emphasis = true,
                            onClick = { copyHere(here) }
                        )
                    }
                }
                item {
                    PickerRow(
                        label = "✓  Use this folder",
                        detail = photoCountLabel(photoCount) +
                            if (onRemovable) " — drive must stay plugged in" else "",
                        emphasis = !onRemovable,
                        onClick = { onPick(here.absolutePath, null) }
                    )
                }
                item {
                    PickerRow(
                        label = "↰  Up",
                        detail = null,
                        onClick = {
                            val parent = current?.parentFile
                            // Stop at the volume root rather than wandering up
                            // into /storage or /, which are unreadable anyway.
                            current = if (parent != null && parent.canRead() &&
                                roots.none { it.directory.absolutePath == current?.absolutePath }
                            ) parent else null
                        }
                    )
                }
                items(entries) { entry ->
                    PickerRow(
                        label = "📁  ${entry.directory.name}",
                        detail = photoCountLabel(entry.photoCount),
                        onClick = { current = entry.directory }
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            text = "No subfolders here.",
                            color = WmcTextPrimary.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
            item {
                PickerRow(label = "Cancel", detail = null, onClick = onCancel)
            }
        }
    }
}

private data class FolderEntry(val directory: File, val photoCount: Int)
private data class RootEntry(
    val label: String,
    val directory: File,
    val photoCount: Int,
    val removable: Boolean
)

private fun photoCountLabel(count: Int): String = when (count) {
    0 -> "no photos"
    1 -> "1 photo"
    else -> "$count photos"
}

/** Subdirectories, alphabetical, skipping hidden ones. Never throws on an unreadable path. */
private fun childFolders(dir: File): List<FolderEntry> = try {
    dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        ?.sortedBy { it.name.lowercase() }
        ?.map { FolderEntry(it, countPhotos(it)) }
        ?: emptyList()
} catch (e: Exception) {
    emptyList()
}

/**
 * Counts image files DIRECTLY in [dir] — deliberately matching
 * [ScreensaverPhotoRepository.scanFolder]'s own non-recursive behaviour, so the
 * number shown is exactly what the screensaver would find.
 */
private fun countPhotos(dir: File): Int = try {
    dir.listFiles { f ->
        f.isFile && f.extension.lowercase() in ScreensaverPhotoRepository.IMAGE_EXTENSIONS
    }?.size ?: 0
} catch (e: Exception) {
    0
}

@Composable
private fun PickerRow(
    label: String,
    detail: String?,
    emphasis: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) WmcTileSurface else Color.Transparent)
            .then(
                if (isFocused) Modifier.border(2.dp, WmcAccentCyan, RoundedCornerShape(8.dp)) else Modifier
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                color = when {
                    isFocused -> WmcAccentCyan
                    emphasis -> WmcAccentCyan.copy(alpha = 0.85f)
                    else -> WmcTextPrimary
                },
                fontSize = 16.sp
            )
            if (detail != null) {
                Text(
                    text = detail,
                    color = if (isFocused) WmcAccentCyan else WmcTextPrimary.copy(alpha = 0.6f),
                    fontSize = 15.sp
                )
            }
        }
    }
}
