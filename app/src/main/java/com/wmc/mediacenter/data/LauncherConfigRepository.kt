package com.wmc.mediacenter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

// internal (not private): SettingsRepository shares this same DataStore instance.
internal val Context.launcherDataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_config")

private val CONFIG_KEY = stringPreferencesKey("launcher_config_json")

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Persists [LauncherConfig] as JSON in DataStore.
 *
 * `null` from [configFlow] specifically means "nothing saved yet" (first
 * run) — the caller is responsible for building and saving a seed config
 * in that case. A corrupt/unreadable stored value is treated the same way
 * (falls back to null) rather than crashing.
 */
class LauncherConfigRepository(private val context: Context) {

    val configFlow: Flow<LauncherConfig?> = context.launcherDataStore.data
        // A corrupt prefs file must never kill the collector permanently —
        // fall back to defaults (treated the same as "nothing saved yet")
        // instead of throwing and stopping row/settings updates for good.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[CONFIG_KEY]?.let { raw ->
                runCatching { json.decodeFromString(LauncherConfig.serializer(), raw) }.getOrNull()
            }
        }

    suspend fun currentOrNull(): LauncherConfig? = configFlow.first()

    suspend fun save(config: LauncherConfig) {
        val raw = json.encodeToString(LauncherConfig.serializer(), config)
        context.launcherDataStore.edit { prefs ->
            prefs[CONFIG_KEY] = raw
        }
    }

    /**
     * Read-modify-write as ONE DataStore transaction. DataStore serializes
     * `edit` blocks, so two quick edits (e.g. Move left pressed twice) each
     * see the other's result — a separate [currentOrNull] + [save] let both
     * read the same starting config and the second silently undid the first.
     * No-op when nothing is saved yet or [transform] returns the same config.
     * Returns true if a new config was written.
     */
    suspend fun update(transform: (LauncherConfig) -> LauncherConfig): Boolean {
        var changed = false
        context.launcherDataStore.edit { prefs ->
            val current = prefs[CONFIG_KEY]
                ?.let { raw -> runCatching { json.decodeFromString(LauncherConfig.serializer(), raw) }.getOrNull() }
                ?: return@edit
            val updated = transform(current)
            if (updated != current) {
                prefs[CONFIG_KEY] = json.encodeToString(LauncherConfig.serializer(), updated)
                changed = true
            }
        }
        return changed
    }
}
