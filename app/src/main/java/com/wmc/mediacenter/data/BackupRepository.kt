package com.wmc.mediacenter.data

import android.os.Build
import android.os.Environment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T2 — everything worth keeping across an uninstall, in one JSON file.
 *
 * WHY THIS EXISTS: rows/settings live in DataStore, which dies with the
 * package. The first release-signed build will REQUIRE an uninstall (debug
 * and release keys can't upgrade over each other), so without an external
 * backup the whole setup is lost the first time a real release is cut.
 *
 * [schemaVersion] gates import: a file written by a NEWER schema than this
 * build understands is refused outright rather than half-parsed. Older
 * files are fine — kotlinx ignoreUnknownKeys plus defaulted fields make
 * every v1+ file readable for as long as fields are only ever added.
 */
@Serializable
data class LauncherBackup(
    val schemaVersion: Int,
    /** Informational only — never gates import. */
    val appVersion: String? = null,
    val exportedAt: String? = null,
    val config: LauncherConfig,
    val settings: SettingsBackup
)

/**
 * [AppSettings] mirrored into an explicitly @Serializable shape. Kept as a
 * separate class (not @Serializable on AppSettings itself) so the on-disk
 * format is a deliberate contract — an internal rename or reorder in
 * AppSettings can't silently change what a backup file means.
 */
@Serializable
data class SettingsBackup(
    val use24HourClock: Boolean = false,
    val showAppNames: Boolean = true,
    val hiddenPackages: Set<String> = emptySet(),
    val showHiddenApps: Boolean = false,
    val showNonTvApps: Boolean = true,
    val startupPackage: String? = null,
    val showRecentRow: Boolean = false,
    val recentPackages: List<String> = emptyList(),
    val glassTiles: Boolean = false,
    val classicStrips: Boolean = true,
    val fadedTiles: Boolean = true,
    val preferIconTiles: Boolean = false
) {
    fun toAppSettings() = AppSettings(
        use24HourClock = use24HourClock,
        showAppNames = showAppNames,
        hiddenPackages = hiddenPackages,
        showHiddenApps = showHiddenApps,
        showNonTvApps = showNonTvApps,
        startupPackage = startupPackage,
        showRecentRow = showRecentRow,
        recentPackages = recentPackages,
        glassTiles = glassTiles,
        classicStrips = classicStrips,
        fadedTiles = fadedTiles,
        preferIconTiles = preferIconTiles
    )

    companion object {
        fun from(s: AppSettings) = SettingsBackup(
            use24HourClock = s.use24HourClock,
            showAppNames = s.showAppNames,
            hiddenPackages = s.hiddenPackages,
            showHiddenApps = s.showHiddenApps,
            showNonTvApps = s.showNonTvApps,
            startupPackage = s.startupPackage,
            showRecentRow = s.showRecentRow,
            recentPackages = s.recentPackages,
            glassTiles = s.glassTiles,
            classicStrips = s.classicStrips,
            fadedTiles = s.fadedTiles,
            preferIconTiles = s.preferIconTiles
        )
    }
}

sealed class BackupResult<out T> {
    data class Ok<T>(val value: T) : BackupResult<T>()
    data class Error(val message: String) : BackupResult<Nothing>()
}

/**
 * Reads/writes the backup file at a fixed, adb-visible path:
 * `/sdcard/MCLauncher/mclauncher-backup.json`.
 *
 * DELIBERATELY NOT in getExternalFilesDir() — `/sdcard/Android/data/<pkg>/`
 * is wiped on uninstall, and surviving uninstall is this feature's entire
 * reason to exist (see [LauncherBackup]).
 *
 * A fixed public path needs "All files access" on Android 11+. Like
 * SYSTEM_ALERT_WINDOW (S32), it's declared in the manifest but must be
 * granted once per device over adb — there is no Google TV settings UI for
 * it:
 *
 *     adb shell appops set com.wmc.mediacenter MANAGE_EXTERNAL_STORAGE allow
 *
 * Survives reboots. Does NOT survive an uninstall — after reinstalling,
 * re-grant it BEFORE restoring. [storageReady] is checked before every
 * touch of the file so a missing grant degrades to a clear on-screen
 * message instead of a silent empty failure.
 *
 * All methods do blocking I/O — call from Dispatchers.IO.
 */
class BackupRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true // hand-editable over adb is a feature here
    }

    val backupPath: String
        get() = backupFile().absolutePath

    fun storageReady(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // pre-scoped-storage: WRITE_EXTERNAL_STORAGE in the manifest suffices
        }

    /** Writes [backup]; returns the path written. Write-then-rename so a mid-write failure can't destroy the previous good backup. */
    fun export(backup: LauncherBackup): BackupResult<String> {
        if (!storageReady()) return BackupResult.Error(GRANT_HINT)
        return try {
            val file = backupFile()
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(LauncherBackup.serializer(), backup))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) return BackupResult.Error("Couldn't move backup into place at ${file.absolutePath}")
            BackupResult.Ok(file.absolutePath)
        } catch (e: Exception) {
            BackupResult.Error("Backup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun import(): BackupResult<LauncherBackup> {
        if (!storageReady()) return BackupResult.Error(GRANT_HINT)
        val file = backupFile()
        if (!file.exists()) return BackupResult.Error("No backup found at ${file.absolutePath}")
        return try {
            val parsed = json.decodeFromString(LauncherBackup.serializer(), file.readText())
            if (parsed.schemaVersion > SCHEMA_VERSION) {
                BackupResult.Error(
                    "Backup is schema v${parsed.schemaVersion}, this build only understands up to v$SCHEMA_VERSION — update the app first"
                )
            } else {
                BackupResult.Ok(parsed)
            }
        } catch (e: Exception) {
            BackupResult.Error("Couldn't read backup: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun backupFile(): File =
        File(Environment.getExternalStorageDirectory(), "$DIR_NAME/$FILE_NAME")

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DIR_NAME = "MCLauncher"
        private const val FILE_NAME = "mclauncher-backup.json"
        private const val GRANT_HINT =
            "Storage access not granted — run: adb shell appops set com.wmc.mediacenter MANAGE_EXTERNAL_STORAGE allow"

        fun timestamp(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }
}
