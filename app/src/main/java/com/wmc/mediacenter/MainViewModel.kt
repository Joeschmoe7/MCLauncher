package com.wmc.mediacenter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wmc.mediacenter.apps.AppInfo
import com.wmc.mediacenter.apps.AppRepository
import com.wmc.mediacenter.apps.SystemActions
import com.wmc.mediacenter.data.AppSettings
import com.wmc.mediacenter.data.LauncherConfig
import com.wmc.mediacenter.data.LauncherConfigRepository
import com.wmc.mediacenter.data.RowConfig
import com.wmc.mediacenter.data.SettingsRepository
import com.wmc.mediacenter.data.ShortcutConfig
import com.wmc.mediacenter.data.buildSeedConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One user-named row, resolved to the actual [AppInfo] for each package still installed. */
data class RowUiState(
    val id: String,
    val name: String,
    val apps: List<AppInfo>
)

data class HomeUiState(
    val rows: List<RowUiState> = emptyList(),
    /** Custom deep-link cards, keyed by their sentinel id — for the click-to-launch lookup on Home/Edit Row. */
    val shortcutsById: Map<String, ShortcutConfig> = emptyMap(),
    val isLoading: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appRepository = AppRepository(
        packageManager = application.packageManager,
        // S22 — lets the repository cap decoded artwork at the pixel size a
        // tile actually draws at on THIS panel, instead of a fixed 512px that
        // was ~2x oversampled at density 1.0.
        displayDensity = application.resources.displayMetrics.density
    )
    private val configRepository = LauncherConfigRepository(application)
    private val settingsRepository = SettingsRepository(application)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // F3 — one-shot "launch on startup" signal. Process-scoped (only ever
    // set once per cold start via [startupHandled]), so returning Home from
    // the launched app never re-fires it.
    private val _startupLaunch = MutableStateFlow<String?>(null)
    val startupLaunch: StateFlow<String?> = _startupLaunch.asStateFlow()
    private var startupHandled = false

    private var hasSeeded = false

    // Runtime-registered (not manifest-registered) receiver: implicit
    // package broadcasts are restricted for manifest receivers on API 26+,
    // so this has to be registered in code to actually fire.
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Drop any cached artwork for the changed package first so a
            // REPLACED update doesn't keep showing the old icon/banner.
            intent.data?.schemeSpecificPart?.let { appRepository.invalidate(it) }
            refreshApps()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Package broadcasts are system broadcasts (exempt from the
        // targetSdk-34 export-flag requirement), but be explicit anyway.
        ContextCompat.registerReceiver(
            application,
            packageChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        viewModelScope.launch {
            combine(_apps, configRepository.configFlow) { apps, config -> apps to config }
                .collect { (apps, config) ->
                    if (config == null) {
                        seedIfNeeded()
                    } else if (migrateSettingsRowIfNeeded(config)) {
                        // Saved an updated config; the new one flows back through
                        // configFlow and re-enters this collector.
                        return@collect
                    } else {
                        _uiState.value = buildUiState(apps, config)
                        pruneMissingPackages(apps, config)
                    }
                }
        }

        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { _settings.value = it }
        }

        // F3 — fire at most once per process (i.e. once per boot / cold
        // start). MediaCenter is the Home app so it's already foreground at
        // this point; no RECEIVE_BOOT_COMPLETED receiver needed (and one
        // would hit Android 10+ background-activity-start limits anyway).
        viewModelScope.launch {
            if (startupHandled) return@launch
            startupHandled = true
            val pkg = settingsRepository.settingsFlow.first().startupPackage
            if (!pkg.isNullOrEmpty()) _startupLaunch.value = pkg
        }

        refreshApps()
    }

    fun clearStartupLaunch() {
        _startupLaunch.value = null
    }

    fun refreshApps() {
        viewModelScope.launch {
            val discovered = withContext(Dispatchers.IO) { appRepository.loadInstalledApps() }
            _apps.value = discovered
        }
    }

    private suspend fun seedIfNeeded() {
        if (hasSeeded) return
        hasSeeded = true

        val discovered = withContext(Dispatchers.IO) { appRepository.loadInstalledApps() }
        _apps.value = discovered

        val seed = buildSeedConfig(discovered.map { it.packageName }.toSet())
        configRepository.save(seed) // flows back through configFlow above
    }

    private fun buildUiState(apps: List<AppInfo>, config: LauncherConfig): HomeUiState {
        val byPackage = apps.associateBy { it.packageName }
        val shortcutsById = config.shortcuts.associateBy { it.id }
        return HomeUiState(
            rows = config.rows.map { row ->
                RowUiState(
                    id = row.id,
                    name = row.name,
                    // Resolve each package to its installed app, a built-in
                    // system-action card, or a user-defined shortcut card
                    // (synthetic AppInfo borrowing its target app's artwork).
                    // Anything that's none of those (an uninstalled app) is
                    // skipped.
                    apps = row.packages.mapNotNull { pkg ->
                        byPackage[pkg]
                            ?: SystemActions.appInfoFor(pkg)
                            ?: shortcutsById[pkg]?.let { shortcut -> shortcutAppInfo(shortcut, byPackage) }
                    }
                )
            },
            shortcutsById = shortcutsById,
            isLoading = false
        )
    }

    /** Synthetic tile for a shortcut card — borrows its target app's icon/banner so it reads as branded (e.g. Channels-styled "Movies" tile). */
    private fun shortcutAppInfo(shortcut: ShortcutConfig, byPackage: Map<String, AppInfo>): AppInfo {
        val targetApp = byPackage[shortcut.targetPackage]
        return AppInfo(
            packageName = shortcut.id,
            label = shortcut.label,
            icon = targetApp?.icon,
            banner = targetApp?.banner,
            // S22 — borrow the target's pre-baked faded copies too, otherwise
            // shortcut cards would be the only tiles still rendering at full
            // colour when unfocused.
            fadedIcon = targetApp?.fadedIcon,
            fadedBanner = targetApp?.fadedBanner
        )
    }

    /**
     * One-time migration for configs saved before the default "Settings" row
     * existed: if no row anywhere already holds a system-action card, append a
     * fresh "Settings" row seeded with them. Runs at most once (guarded by a
     * persisted flag) so it won't reappear if the user later deletes it.
     * Returns true if it saved a new config (caller should wait for the re-emit).
     */
    private suspend fun migrateSettingsRowIfNeeded(config: LauncherConfig): Boolean {
        if (settingsRepository.isSettingsRowMigrated()) return false
        settingsRepository.markSettingsRowMigrated()

        val alreadyHasSystemActions =
            config.rows.any { row -> row.packages.any { SystemActions.isSystemAction(it) } }
        if (alreadyHasSystemActions) return false

        val settingsRow = RowConfig(
            id = UUID.randomUUID().toString(),
            name = "Settings",
            packages = SystemActions.DEFAULT_SETTINGS_ROW
        )
        configRepository.save(config.copy(rows = config.rows + settingsRow))
        return true
    }

    /**
     * Drops packages from saved rows once they're confirmed no longer
     * installed, and persists the cleanup. A package missing from the
     * latest discovery pass is only a *candidate* — [AppRepository.queryFor]
     * swallows any exception into an empty list, so if just the LEANBACK
     * query fails transiently (boot race, PM hiccup), a leanback-only app
     * would otherwise look uninstalled and get silently, permanently
     * pruned. Each candidate is re-checked directly via PackageManager
     * (off the main thread) before it's actually dropped.
     */
    private fun pruneMissingPackages(apps: List<AppInfo>, config: LauncherConfig) {
        if (apps.isEmpty()) return
        val installed = apps.map { it.packageName }.toSet()
        val candidates = config.rows.asSequence()
            .flatMap { it.packages }
            .filter { it !in installed && !SystemActions.isSystemAction(it) && !ShortcutConfig.isShortcutId(it) }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return

        viewModelScope.launch {
            val confirmedGone = withContext(Dispatchers.IO) {
                candidates.filterNot { appRepository.isInstalled(it) }.toSet()
            }
            if (confirmedGone.isEmpty()) return@launch
            val current = configRepository.currentOrNull() ?: return@launch
            val cleanedRows = current.rows.map { row ->
                row.copy(packages = row.packages.filter { it !in confirmedGone })
            }
            if (cleanedRows != current.rows) {
                configRepository.save(current.copy(rows = cleanedRows))
            }
        }
    }

    /** Moves [packageName] within [rowId] by [offset] positions (e.g. -1 = left, +1 = right). */
    fun moveWithinRow(rowId: String, packageName: String, offset: Int) {
        viewModelScope.launch {
            val current = configRepository.currentOrNull() ?: return@launch
            val updatedRows = current.rows.map { row ->
                if (row.id != rowId) return@map row
                val index = row.packages.indexOf(packageName)
                if (index == -1) return@map row
                val newIndex = (index + offset).coerceIn(0, row.packages.lastIndex)
                if (newIndex == index) return@map row
                val reordered = row.packages.toMutableList()
                reordered.removeAt(index)
                reordered.add(newIndex, packageName)
                row.copy(packages = reordered)
            }
            configRepository.save(current.copy(rows = updatedRows))
        }
    }

    /**
     * Removes [packageName] from [rowId]. If it's a shortcut card, this also
     * deletes the underlying [ShortcutConfig] entirely (rather than just
     * unlinking it) — shortcuts only ever live in one row and there's no UI
     * to re-add an orphaned one, so leaving it around would just be cruft.
     */
    fun removeFromRow(rowId: String, packageName: String) {
        viewModelScope.launch {
            val current = configRepository.currentOrNull() ?: return@launch
            val updatedRows = current.rows.map { row ->
                if (row.id == rowId) row.copy(packages = row.packages.filter { it != packageName }) else row
            }
            val updatedShortcuts = if (ShortcutConfig.isShortcutId(packageName)) {
                current.shortcuts.filter { it.id != packageName }
            } else {
                current.shortcuts
            }
            configRepository.save(current.copy(rows = updatedRows, shortcuts = updatedShortcuts))
        }
    }

    fun addToRow(rowId: String, packageName: String) {
        viewModelScope.launch {
            val current = configRepository.currentOrNull() ?: return@launch
            val updatedRows = current.rows.map { row ->
                if (row.id == rowId && packageName !in row.packages) {
                    row.copy(packages = row.packages + packageName)
                } else {
                    row
                }
            }
            configRepository.save(current.copy(rows = updatedRows))
        }
    }

    /** Renames a row. Blank/whitespace-only names are ignored (keeps the old name). */
    fun renameRow(rowId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val current = configRepository.currentOrNull() ?: return@launch
            val updated = current.rows.map { if (it.id == rowId) it.copy(name = trimmed) else it }
            configRepository.save(current.copy(rows = updated))
        }
    }

    /** Reorders the row itself (not its apps) by [offset] positions — e.g. -1 = up, +1 = down. */
    fun moveRow(rowId: String, offset: Int) {
        viewModelScope.launch {
            val current = configRepository.currentOrNull() ?: return@launch
            val index = current.rows.indexOfFirst { it.id == rowId }
            if (index == -1) return@launch
            val newIndex = (index + offset).coerceIn(0, current.rows.lastIndex)
            if (newIndex == index) return@launch
            val reordered = current.rows.toMutableList()
            val row = reordered.removeAt(index)
            reordered.add(newIndex, row)
            configRepository.save(current.copy(rows = reordered))
        }
    }

    fun deleteRow(rowId: String) {
        viewModelScope.launch {
            val current = configRepository.currentOrNull() ?: return@launch
            configRepository.save(current.copy(rows = current.rows.filter { it.id != rowId }))
        }
    }

    /** Adds a new, empty row named [name] (falls back to "New Row" if blank) at the end. */
    fun addRow(name: String) {
        val trimmed = name.trim().ifEmpty { "New Row" }
        viewModelScope.launch {
            val current = configRepository.currentOrNull() ?: return@launch
            val newRow = RowConfig(id = UUID.randomUUID().toString(), name = trimmed, packages = emptyList())
            configRepository.save(current.copy(rows = current.rows + newRow))
        }
    }

    fun setUse24HourClock(value: Boolean) {
        viewModelScope.launch { settingsRepository.setUse24HourClock(value) }
    }

    fun setShowAppNames(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowAppNames(value) }
    }

    // --- F1: Hide apps ---------------------------------------------------

    fun hideApp(packageName: String) {
        viewModelScope.launch {
            settingsRepository.setHiddenPackages(settings.value.hiddenPackages + packageName)
        }
    }

    fun unhideApp(packageName: String) {
        viewModelScope.launch {
            settingsRepository.setHiddenPackages(settings.value.hiddenPackages - packageName)
        }
    }

    fun setShowHiddenApps(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowHiddenApps(value) }
    }

    // --- T1: Non-TV (sideloaded) apps -------------------------------------

    fun setShowNonTvApps(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowNonTvApps(value) }
    }

    // --- F3: Launch on startup --------------------------------------------

    fun setStartupPackage(packageName: String?) {
        viewModelScope.launch { settingsRepository.setStartupPackage(packageName) }
    }

    // --- F4: Recent apps row ----------------------------------------------

    fun setShowRecentRow(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowRecentRow(value) }
    }

    /** Records a real app launch (not a picker toggle / system-action click) into the Recent row's MRU list, capped at 12. */
    fun recordLaunch(packageName: String) {
        viewModelScope.launch {
            val updated = (listOf(packageName) + settings.value.recentPackages).distinct().take(12)
            settingsRepository.setRecentPackages(updated)
        }
    }

    fun removeRecent(packageName: String) {
        viewModelScope.launch {
            settingsRepository.setRecentPackages(settings.value.recentPackages.filter { it != packageName })
        }
    }

    fun setGlassTiles(value: Boolean) {
        viewModelScope.launch { settingsRepository.setGlassTiles(value) }
    }

    fun setClassicStrips(value: Boolean) {
        viewModelScope.launch { settingsRepository.setClassicStrips(value) }
    }

    fun setFadedTiles(value: Boolean) {
        viewModelScope.launch { settingsRepository.setFadedTiles(value) }
    }

    fun setPreferIconTiles(value: Boolean) {
        viewModelScope.launch { settingsRepository.setPreferIconTiles(value) }
    }

    // --- Deep-link shortcut cards ------------------------------------------

    /** Creates a shortcut card (e.g. "Movies" → Channels DVR's Movies section) and appends it to [rowId]. */
    fun addShortcut(rowId: String, label: String, targetPackage: String, uri: String) {
        val trimmedLabel = label.trim().ifEmpty { "Shortcut" }
        val trimmedUri = uri.trim()
        if (trimmedUri.isEmpty()) return
        viewModelScope.launch {
            val current = configRepository.currentOrNull() ?: return@launch
            val shortcut = ShortcutConfig(
                id = ShortcutConfig.newId(),
                label = trimmedLabel,
                targetPackage = targetPackage,
                uri = trimmedUri
            )
            val updatedRows = current.rows.map { row ->
                if (row.id == rowId) row.copy(packages = row.packages + shortcut.id) else row
            }
            configRepository.save(current.copy(rows = updatedRows, shortcuts = current.shortcuts + shortcut))
        }
    }

    /**
     * "Re-run first-time setup": rebuilds the three seed rows (TV/Movies/Apps)
     * from whatever's currently installed, discarding the existing row
     * layout entirely. Destructive by design — the Settings screen confirms
     * before calling this.
     */
    fun resetToFirstRunSeed() {
        viewModelScope.launch {
            val discovered = withContext(Dispatchers.IO) { appRepository.loadInstalledApps() }
            _apps.value = discovered
            val seed = buildSeedConfig(discovered.map { it.packageName }.toSet())
            configRepository.save(seed)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(packageChangeReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered — fine.
        }
    }
}
