package com.wmc.mediacenter.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LauncherConfig(
    val rows: List<RowConfig>,
    val shortcuts: List<ShortcutConfig> = emptyList()
)

@Serializable
data class RowConfig(
    val id: String,             // UUID
    val name: String,           // user-editable: "Movies", "TV", "Streaming Services"...
    val packages: List<String>  // ordered app package names (or system-action / shortcut ids)
)

/**
 * A user-defined "deep link" card: instead of just launching [targetPackage]
 * normally, it fires an explicit ACTION_VIEW intent at [uri] targeted at
 * that app — e.g. a "Movies" card that opens Channels DVR straight to its
 * Movies section (`channels://navigate/Movies`), or a Plex library section.
 *
 * Referenced from [RowConfig.packages] by [id], same as how a built-in
 * system-action card is referenced by its sentinel id (see SystemActions).
 * [id] is always prefixed so it can never collide with a real Android
 * package name (which can't contain a dash).
 */
@Serializable
data class ShortcutConfig(
    val id: String,
    val label: String,
    val targetPackage: String,
    val uri: String
) {
    companion object {
        private const val ID_PREFIX = "wmc.shortcut."

        fun newId(): String = ID_PREFIX + UUID.randomUUID()

        fun isShortcutId(id: String): Boolean = id.startsWith(ID_PREFIX)
    }
}
