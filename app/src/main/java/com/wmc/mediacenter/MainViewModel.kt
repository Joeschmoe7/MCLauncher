package com.wmc.mediacenter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wmc.mediacenter.apps.AppInfo
import com.wmc.mediacenter.apps.AppRepository
import com.wmc.mediacenter.apps.SystemActions
import com.wmc.mediacenter.data.AppSettings
import com.wmc.mediacenter.data.BackupRepository
import com.wmc.mediacenter.data.BackupResult
import com.wmc.mediacenter.data.LauncherBackup
import com.wmc.mediacenter.data.LauncherConfig
import com.wmc.mediacenter.data.LauncherConfigRepository
import com.wmc.mediacenter.data.RowConfig
import com.wmc.mediacenter.data.SettingsBackup
import com.wmc.mediacenter.data.SettingsRepository
import com.wmc.mediacenter.data.ShortcutConfig
import com.wmc.mediacenter.data.buildSeedConfig
import com.wmc.mediacenter.screensaver.ScreensaverAvailability
import com.wmc.mediacenter.screensaver.ScreensaverSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        displayDensity = application.resources.displayMetrics.density,
        ownPackage = application.packageName
    )
    private val configRepository = LauncherConfigRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val backupRepository = BackupRepository()

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

        // S36 — component enabled-state is NOT part of DataStore and does not
        // survive an uninstall/reinstall, and a restore-from-backup only
        // rewrites the preference. Reconcile the two once per start so the
        // stored preference is always what's actually in effect.
        viewModelScope.launch {
            val wanted = settingsRepository.settingsFlow.first().screensaverEnabled
            val actual = withContext(Dispatchers.IO) { ScreensaverAvailability.isEnabled(application) }
            if (wanted != actual) applyScreensaverEnabled(wanted, persist = false)
        }

        viewModelScope.launch {
            combine(_apps, configRepository.configFlow) { apps, config -> apps to config }
                .collect { (apps, config) ->
                    if (config == null) {
                        seedIfNeeded()
                    } else if (migrateSettingsRowIfNeeded(config)) {
                        // Saved an updated config; the new one flows back through
                        // configFlow and re-enters this collector.
                        return@collect
                    } else if (withEssentialCards(config) != config) {
                        // Self-heal (e.g. a restored backup or a pre-guard
                        // config with no Settings card) — same re-entry as above.
                        configRepository.update(::withEssentialCards)
                        return@collect
                    } else {
                        // Uninstalled apps are deliberately NOT pruned from
                        // rows: buildUiState already skips them, and keeping
                        // the entry means a reinstall (or apps installed after
                        // a restore) comes back in its old slot.
                        _uiState.value = buildUiState(apps, config)
                    }
                }
        }

        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { _settings.value = it }
        }

        // F3 — fire at most once per BOOT. Once per process isn't enough: this
        // 2GB box kills our process during sleep and the S33 watchdog starts a
        // fresh one on wake, which used to reopen the startup app every time
        // the TV woke. The boot count is persisted, so a new process in the
        // same boot sees it was already handled.
        viewModelScope.launch {
            if (startupHandled) return@launch
            startupHandled = true
            // Claim the boot even with no startup app set, so choosing one
            // later doesn't make it fire on the next restart in this same boot.
            val bootCount = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
            // -1: no boot count on this build — fall back to once per process.
            val firstStartThisBoot = bootCount == -1 || settingsRepository.claimStartupLaunch(bootCount)
            val pkg = settingsRepository.settingsFlow.first().startupPackage
            if (firstStartThisBoot && !pkg.isNullOrEmpty()) _startupLaunch.value = pkg
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
     * Applies a user edit to the saved config as one atomic DataStore
     * transaction (see [LauncherConfigRepository.update]) and, if anything
     * changed, schedules an automatic backup.
     */
    private fun editConfig(transform: (LauncherConfig) -> LauncherConfig) {
        viewModelScope.launch {
            if (configRepository.update(transform)) scheduleAutoBackup()
        }
    }

    /** Applies a user settings change, then schedules an automatic backup. */
    private fun editSettings(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch {
            settingsRepository.block()
            scheduleAutoBackup()
        }
    }

    /**
     * True if removing [packageName] from [rowId] would leave no copy of an
     * essential card anywhere (see [SystemActions.ESSENTIAL]). The row-tile
     * menu uses this to withhold "Remove from row".
     */
    fun isLastEssentialCard(rowId: String, packageName: String): Boolean {
        if (packageName !in SystemActions.ESSENTIAL) return false
        val rows = _uiState.value.rows
        return rows.none { row -> row.id != rowId && row.apps.any { it.packageName == packageName } }
    }

    /**
     * Moves [packageName] within [rowId] by one visible position in the
     * direction of [offset]. Rows keep entries for uninstalled apps (they're
     * hidden, not pruned), so a raw index step could land on an invisible
     * entry and the move would look like it did nothing — step past those to
     * the next tile actually on screen instead.
     */
    fun moveWithinRow(rowId: String, packageName: String, offset: Int) {
        if (offset == 0) return
        val installed = _apps.value.mapTo(HashSet()) { it.packageName }
        editConfig { current ->
            val shortcutIds = current.shortcuts.mapTo(HashSet()) { it.id }
            fun isVisible(id: String) =
                id in installed || SystemActions.isSystemAction(id) || id in shortcutIds
            current.copy(rows = current.rows.map { row ->
                if (row.id != rowId) return@map row
                val index = row.packages.indexOf(packageName)
                if (index == -1) return@map row
                val step = if (offset < 0) -1 else 1
                var target = index + step
                while (target in row.packages.indices && !isVisible(row.packages[target])) target += step
                if (target !in row.packages.indices) return@map row
                val reordered = row.packages.toMutableList()
                reordered.removeAt(index)
                reordered.add(target, packageName)
                row.copy(packages = reordered)
            })
        }
    }

    /**
     * Removes [packageName] from [rowId]. If it's a shortcut card, this also
     * deletes the underlying [ShortcutConfig] entirely (rather than just
     * unlinking it) — shortcuts only ever live in one row and there's no UI
     * to re-add an orphaned one, so leaving it around would just be cruft.
     * Refuses to remove the last copy of an essential card.
     */
    fun removeFromRow(rowId: String, packageName: String) {
        editConfig { current ->
            val updatedRows = current.rows.map { row ->
                if (row.id == rowId) row.copy(packages = row.packages.filter { it != packageName }) else row
            }
            if (packageName in SystemActions.ESSENTIAL && updatedRows.none { packageName in it.packages }) {
                return@editConfig current
            }
            current.copy(
                rows = updatedRows,
                shortcuts = current.shortcuts.filter { it.id != packageName }
            )
        }
    }

    fun addToRow(rowId: String, packageName: String) {
        editConfig { current ->
            current.copy(rows = current.rows.map { row ->
                if (row.id == rowId && packageName !in row.packages) {
                    row.copy(packages = row.packages + packageName)
                } else {
                    row
                }
            })
        }
    }

    /** Renames a row. Blank/whitespace-only names are ignored (keeps the old name). */
    fun renameRow(rowId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        editConfig { current ->
            current.copy(rows = current.rows.map { if (it.id == rowId) it.copy(name = trimmed) else it })
        }
    }

    /** Reorders the row itself (not its apps) by [offset] positions — e.g. -1 = up, +1 = down. */
    fun moveRow(rowId: String, offset: Int) {
        editConfig { current ->
            val index = current.rows.indexOfFirst { it.id == rowId }
            if (index == -1) return@editConfig current
            val newIndex = (index + offset).coerceIn(0, current.rows.lastIndex)
            val reordered = current.rows.toMutableList()
            reordered.add(newIndex, reordered.removeAt(index))
            current.copy(rows = reordered)
        }
    }

    /**
     * Deletes a row along with any shortcut cards it held. If it held the
     * last Edit Rows / Settings card, those are moved to a "Settings" row
     * (see [withEssentialCards]) and [onEssentialCardsMoved] gets that row's
     * name so the UI can say where they went.
     */
    fun deleteRow(rowId: String, onEssentialCardsMoved: (String) -> Unit = {}) {
        viewModelScope.launch {
            var movedTo: String? = null
            val changed = configRepository.update { current ->
                val deleted = current.rows.find { it.id == rowId } ?: return@update current
                val remaining = current.copy(
                    rows = current.rows.filter { it.id != rowId },
                    shortcuts = current.shortcuts.filter { it.id !in deleted.packages }
                )
                val healed = withEssentialCards(remaining)
                if (healed != remaining) {
                    movedTo = healed.rows.first { row -> SystemActions.ESSENTIAL.any { it in row.packages } }.name
                }
                healed
            }
            if (changed) scheduleAutoBackup()
            movedTo?.let(onEssentialCardsMoved)
        }
    }

    /** Adds a new, empty row named [name] (falls back to "New Row" if blank) at the end. */
    fun addRow(name: String) {
        val trimmed = name.trim().ifEmpty { "New Row" }
        editConfig { current ->
            current.copy(rows = current.rows + RowConfig(id = UUID.randomUUID().toString(), name = trimmed, packages = emptyList()))
        }
    }

    fun setUse24HourClock(value: Boolean) = editSettings { setUse24HourClock(value) }

    fun setShowAppNames(value: Boolean) = editSettings { setShowAppNames(value) }

    // --- F1: Hide apps ---------------------------------------------------

    fun hideApp(packageName: String) = editSettings { updateHiddenPackages { it + packageName } }

    fun unhideApp(packageName: String) = editSettings { updateHiddenPackages { it - packageName } }

    fun setShowHiddenApps(value: Boolean) = editSettings { setShowHiddenApps(value) }

    // --- T1: Non-TV (sideloaded) apps -------------------------------------

    fun setShowNonTvApps(value: Boolean) = editSettings { setShowNonTvApps(value) }

    // --- F3: Launch on startup --------------------------------------------

    fun setStartupPackage(packageName: String?) = editSettings { setStartupPackage(packageName) }

    // --- F4: Recent apps row ----------------------------------------------

    fun setShowRecentRow(value: Boolean) = editSettings { setShowRecentRow(value) }

    /**
     * Records a real app launch (not a picker toggle / system-action click)
     * into the Recent row's MRU list, capped at 12. Not auto-backed-up: it
     * changes on every launch and isn't worth an sdcard write each time.
     */
    fun recordLaunch(packageName: String) {
        viewModelScope.launch {
            settingsRepository.updateRecentPackages { (listOf(packageName) + it).distinct().take(12) }
        }
    }

    fun removeRecent(packageName: String) {
        viewModelScope.launch {
            settingsRepository.updateRecentPackages { recent -> recent.filter { it != packageName } }
        }
    }

    fun setGlassTiles(value: Boolean) = editSettings { setGlassTiles(value) }

    fun setClassicStrips(value: Boolean) = editSettings { setClassicStrips(value) }

    fun setFadedTiles(value: Boolean) = editSettings { setFadedTiles(value) }

    fun setPreferIconTiles(value: Boolean) = editSettings { setPreferIconTiles(value) }

    fun setScreensaverFolderPath(value: String?) = editSettings { setScreensaverFolderPath(value) }

    /**
     * S36 — persists the preference AND applies it to the DreamService
     * component, which is what actually adds/removes the photo wall from the
     * system's screensaver picker. See ScreensaverAvailability.
     */
    /**
     * [onEnabled] fires only when switching ON, reporting whether MCLauncher
     * was also able to SELECT the dream itself (true) or whether the user
     * still has to finish the job by hand (false) — see ScreensaverSelection.
     */
    fun setScreensaverEnabled(value: Boolean, onEnabled: (autoSelected: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val autoSelected = applyScreensaverEnabled(value, persist = true)
            scheduleAutoBackup()
            if (value) onEnabled(autoSelected)
        }
    }

    /**
     * S36 — "Set as screen saver": selects the dream directly when
     * WRITE_SECURE_SETTINGS has been granted. Reports false when it couldn't,
     * so the caller can fall back to opening Android's own Settings. Exists
     * separately from the toggle because the grant is often run AFTER the
     * screensaver was first switched on, and nothing would otherwise re-select
     * it.
     */
    fun selectScreensaverNow(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ScreensaverSelection.select(getApplication())
            }
            if (result.selected) {
                settingsRepository.setScreensaverPreviousDream(result.previousComponents)
            }
            onResult(result.selected)
        }
    }

    /**
     * The single place the preference and the component's enabled state are
     * brought into agreement. Every writer of screensaverEnabled must go
     * through here — the preference alone changes nothing the system can see,
     * and the component alone is not persisted (it does not survive a
     * reinstall).
     *
     * S36 review fix — setComponentEnabledSetting is a synchronous binder
     * round-trip into PackageManagerService, so it belongs off the main
     * thread: on the UI thread it drops frames on the Settings screen at the
     * exact moment the user pressed the toggle, and adds a jank spike to cold
     * start via the reconcile in init.
     */
    private suspend fun applyScreensaverEnabled(value: Boolean, persist: Boolean): Boolean {
        if (persist) settingsRepository.setScreensaverEnabled(value)
        val app = getApplication<Application>()
        return withContext(Dispatchers.IO) {
            if (value) {
                // Enable the component BEFORE selecting it — pointing the
                // system at a disabled dream is a state worth never creating.
                ScreensaverAvailability.setEnabled(app, true)
                val result = ScreensaverSelection.select(app)
                if (result.selected) {
                    settingsRepository.setScreensaverPreviousDream(result.previousComponents)
                }
                result.selected
            } else {
                // Release the slot first, while we can still recognise
                // ourselves as the current selection, then withdraw the dream.
                ScreensaverSelection.deselect(app, settings.value.screensaverPreviousDream)
                ScreensaverAvailability.setEnabled(app, false)
                false
            }
        }
    }

    // --- Deep-link shortcut cards ------------------------------------------

    /**
     * Creates a shortcut card and appends it to [rowId]. Either [uri] (apps
     * with their own scheme, e.g. Channels DVR's `channels://navigate/Movies`)
     * or [stringExtras]/[booleanExtras] (apps that read plain Intent extras
     * instead, e.g. Jellyfin's `ItemId` + `ItemIsUserView`) should be set —
     * see [com.wmc.mediacenter.ui.launchShortcut].
     */
    fun addShortcut(
        rowId: String,
        label: String,
        targetPackage: String,
        uri: String? = null,
        stringExtras: Map<String, String> = emptyMap(),
        booleanExtras: Map<String, Boolean> = emptyMap()
    ) {
        val trimmedLabel = label.trim().ifEmpty { "Shortcut" }
        val trimmedUri = uri?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedUri == null && stringExtras.isEmpty() && booleanExtras.isEmpty()) return
        val shortcut = ShortcutConfig(
            id = ShortcutConfig.newId(),
            label = trimmedLabel,
            targetPackage = targetPackage,
            uri = trimmedUri,
            stringExtras = stringExtras,
            booleanExtras = booleanExtras
        )
        editConfig { current ->
            if (current.rows.none { it.id == rowId }) return@editConfig current
            current.copy(
                rows = current.rows.map { row ->
                    if (row.id == rowId) row.copy(packages = row.packages + shortcut.id) else row
                },
                shortcuts = current.shortcuts + shortcut
            )
        }
    }

    // --- Auto-backup --------------------------------------------------------

    private var autoBackupJob: Job? = null

    /**
     * Writes the backup file [AUTO_BACKUP_DELAY_MS] after the last user edit,
     * so a burst of edits (reordering a row, flipping several settings) costs
     * one write. Silent: a missing storage grant just means no backup, same
     * as before this existed — the manual "Back up" row still reports it.
     *
     * Only user edits call this — never seeding, the essential-card heal, a
     * restore or a reset. That matters: a fresh install seeds a default
     * config on first start, and backing THAT up would overwrite the user's
     * real backup before they had a chance to restore it.
     */
    private fun scheduleAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch {
            delay(AUTO_BACKUP_DELAY_MS)
            val backup = buildBackup() ?: return@launch
            withContext(Dispatchers.IO) {
                if (backupRepository.storageReady()) backupRepository.export(backup)
            }
        }
    }

    private suspend fun buildBackup(): LauncherBackup? {
        val config = configRepository.currentOrNull() ?: return null
        return LauncherBackup(
            schemaVersion = BackupRepository.SCHEMA_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = BackupRepository.timestamp(),
            config = config,
            settings = SettingsBackup.from(settingsRepository.settingsFlow.first())
        )
    }

    // --- T2: Backup & restore ----------------------------------------------

    /**
     * Writes rows + shortcuts + settings to the fixed backup path (see
     * BackupRepository). [onResult] gets a user-facing message either way;
     * always invoked on the main thread.
     */
    fun exportBackup(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val backup = buildBackup()
            if (backup == null) {
                onResult("Nothing to back up yet")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { backupRepository.export(backup) }
            onResult(
                when (result) {
                    is BackupResult.Ok -> "Backed up to ${result.value}"
                    is BackupResult.Error -> result.message
                }
            )
        }
    }

    /**
     * Overwrites live rows/shortcuts/settings with the backup file's
     * contents. Destructive — the Settings screen confirms before calling
     * this, same pattern as [resetToFirstRunSeed].
     */
    fun importBackup(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { backupRepository.import() }
            when (result) {
                is BackupResult.Error -> onResult(result.message)
                is BackupResult.Ok -> {
                    val backup = result.value
                    configRepository.save(backup.config)
                    val restored = backup.settings.toAppSettings()
                    settingsRepository.replaceAll(restored)
                    // S36 review fix — replaceAll only rewrites the stored
                    // preference. The DreamService component's enabled state
                    // is separate OS-level state that the init-time reconcile
                    // already passed over (it runs at process start, long
                    // before this), so without this the Settings row would
                    // read "On" while the photo wall stayed absent from the
                    // system's screensaver list until the launcher was
                    // restarted — and vice versa.
                    applyScreensaverEnabled(restored.screensaverEnabled, persist = false)
                    val exported = backup.exportedAt?.let { " (from $it)" } ?: ""
                    onResult("Restored rows and settings$exported")
                }
            }
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

    /**
     * [config] with every [SystemActions.ESSENTIAL] card present somewhere.
     * Missing ones are appended to the first row that already holds a system
     * card (normally "Settings"), or to a new "Settings" row if none does.
     */
    private fun withEssentialCards(config: LauncherConfig): LauncherConfig {
        val missing = SystemActions.ESSENTIAL.filter { card -> config.rows.none { card in it.packages } }
        if (missing.isEmpty()) return config
        val hostIndex = config.rows.indexOfFirst { row -> row.packages.any(SystemActions::isSystemAction) }
        return if (hostIndex == -1) {
            config.copy(rows = config.rows + RowConfig(id = UUID.randomUUID().toString(), name = "Settings", packages = missing))
        } else {
            config.copy(rows = config.rows.mapIndexed { i, row ->
                if (i == hostIndex) row.copy(packages = row.packages + missing) else row
            })
        }
    }

    private companion object {
        const val AUTO_BACKUP_DELAY_MS = 5_000L
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
