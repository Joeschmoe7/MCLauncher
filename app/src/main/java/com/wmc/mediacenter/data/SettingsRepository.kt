package com.wmc.mediacenter.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val USE_24_HOUR_CLOCK_KEY = booleanPreferencesKey("use_24_hour_clock")
private val SHOW_APP_NAMES_KEY = booleanPreferencesKey("show_app_names")
private val HIDDEN_PACKAGES_KEY = stringSetPreferencesKey("hidden_packages")
private val SHOW_HIDDEN_APPS_KEY = booleanPreferencesKey("show_hidden_apps")
private val SHOW_NON_TV_APPS_KEY = booleanPreferencesKey("show_non_tv_apps")
private val STARTUP_PACKAGE_KEY = stringPreferencesKey("startup_package")
private val SHOW_RECENT_ROW_KEY = booleanPreferencesKey("show_recent_row")
private val RECENT_PACKAGES_KEY = stringPreferencesKey("recent_packages")
private val GLASS_TILES_KEY = booleanPreferencesKey("glass_tiles")
private val CLASSIC_STRIPS_KEY = booleanPreferencesKey("classic_strips")
private val FADED_TILES_KEY = booleanPreferencesKey("faded_tiles")
private val PREFER_ICON_TILES_KEY = booleanPreferencesKey("prefer_icon_tiles")

// One-time flag: whether the default "Settings" row has been added to configs
// that predate it. Guards the migration in MainViewModel so it runs at most
// once and never fights a user who deliberately deleted the row afterward.
private val SETTINGS_ROW_MIGRATED_KEY = booleanPreferencesKey("settings_row_migrated_v1")

/**
 * Plain Preferences keys are enough here, no need for the JSON-blob
 * approach [LauncherConfigRepository] uses for rows. Reuses the same
 * DataStore instance (defined in LauncherConfigRepository.kt) since both
 * live under one small "launcher_config" preferences file.
 */
class SettingsRepository(private val context: Context) {

    val settingsFlow: Flow<AppSettings> = context.launcherDataStore.data
        // A corrupt prefs file must never kill the collector permanently —
        // fall back to defaults for that emission instead of throwing.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                use24HourClock = prefs[USE_24_HOUR_CLOCK_KEY] ?: false,
                showAppNames = prefs[SHOW_APP_NAMES_KEY] ?: true,
                hiddenPackages = prefs[HIDDEN_PACKAGES_KEY] ?: emptySet(),
                showHiddenApps = prefs[SHOW_HIDDEN_APPS_KEY] ?: false,
                showNonTvApps = prefs[SHOW_NON_TV_APPS_KEY] ?: true,
                startupPackage = prefs[STARTUP_PACKAGE_KEY],
                showRecentRow = prefs[SHOW_RECENT_ROW_KEY] ?: false,
                recentPackages = prefs[RECENT_PACKAGES_KEY]
                    ?.split("\n")
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                glassTiles = prefs[GLASS_TILES_KEY] ?: false,
                classicStrips = prefs[CLASSIC_STRIPS_KEY] ?: true,
                fadedTiles = prefs[FADED_TILES_KEY] ?: true,
                preferIconTiles = prefs[PREFER_ICON_TILES_KEY] ?: false
            )
        }

    suspend fun setUse24HourClock(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[USE_24_HOUR_CLOCK_KEY] = value }
    }

    suspend fun setShowAppNames(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[SHOW_APP_NAMES_KEY] = value }
    }

    suspend fun setHiddenPackages(value: Set<String>) {
        context.launcherDataStore.edit { prefs -> prefs[HIDDEN_PACKAGES_KEY] = value }
    }

    suspend fun setShowHiddenApps(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[SHOW_HIDDEN_APPS_KEY] = value }
    }

    suspend fun setShowNonTvApps(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[SHOW_NON_TV_APPS_KEY] = value }
    }

    /** Null clears the startup package (means "None"). */
    suspend fun setStartupPackage(value: String?) {
        context.launcherDataStore.edit { prefs ->
            if (value == null) prefs.remove(STARTUP_PACKAGE_KEY) else prefs[STARTUP_PACKAGE_KEY] = value
        }
    }

    suspend fun setShowRecentRow(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[SHOW_RECENT_ROW_KEY] = value }
    }

    suspend fun setRecentPackages(value: List<String>) {
        context.launcherDataStore.edit { prefs -> prefs[RECENT_PACKAGES_KEY] = value.joinToString("\n") }
    }

    suspend fun setGlassTiles(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[GLASS_TILES_KEY] = value }
    }

    suspend fun setClassicStrips(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[CLASSIC_STRIPS_KEY] = value }
    }

    suspend fun setFadedTiles(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[FADED_TILES_KEY] = value }
    }

    suspend fun setPreferIconTiles(value: Boolean) {
        context.launcherDataStore.edit { prefs -> prefs[PREFER_ICON_TILES_KEY] = value }
    }

    /**
     * T2 — restore: replaces every setting in ONE DataStore edit, so the
     * settingsFlow collector sees a single consistent emission rather than
     * twelve partially-restored intermediate states.
     */
    suspend fun replaceAll(settings: AppSettings) {
        context.launcherDataStore.edit { prefs ->
            prefs[USE_24_HOUR_CLOCK_KEY] = settings.use24HourClock
            prefs[SHOW_APP_NAMES_KEY] = settings.showAppNames
            prefs[HIDDEN_PACKAGES_KEY] = settings.hiddenPackages
            prefs[SHOW_HIDDEN_APPS_KEY] = settings.showHiddenApps
            prefs[SHOW_NON_TV_APPS_KEY] = settings.showNonTvApps
            if (settings.startupPackage == null) {
                prefs.remove(STARTUP_PACKAGE_KEY)
            } else {
                prefs[STARTUP_PACKAGE_KEY] = settings.startupPackage
            }
            prefs[SHOW_RECENT_ROW_KEY] = settings.showRecentRow
            prefs[RECENT_PACKAGES_KEY] = settings.recentPackages.joinToString("\n")
            prefs[GLASS_TILES_KEY] = settings.glassTiles
            prefs[CLASSIC_STRIPS_KEY] = settings.classicStrips
            prefs[FADED_TILES_KEY] = settings.fadedTiles
            prefs[PREFER_ICON_TILES_KEY] = settings.preferIconTiles
        }
    }

    suspend fun isSettingsRowMigrated(): Boolean =
        context.launcherDataStore.data.first()[SETTINGS_ROW_MIGRATED_KEY] ?: false

    suspend fun markSettingsRowMigrated() {
        context.launcherDataStore.edit { prefs -> prefs[SETTINGS_ROW_MIGRATED_KEY] = true }
    }
}
