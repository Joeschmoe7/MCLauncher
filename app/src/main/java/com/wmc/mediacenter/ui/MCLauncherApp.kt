package com.wmc.mediacenter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wmc.mediacenter.BuildConfig
import com.wmc.mediacenter.HomeHandoff
import com.wmc.mediacenter.MainViewModel
import com.wmc.mediacenter.RowUiState
import com.wmc.mediacenter.apps.AppInfo
import com.wmc.mediacenter.apps.SystemActions
import com.wmc.mediacenter.data.ShortcutConfig
import com.wmc.mediacenter.ui.components.ContextMenuOverlay
import com.wmc.mediacenter.ui.components.TextInputDialog

/**
 * Top-level composable: owns which screen is showing, which (if any)
 * context menu or text-input dialog is open, and centralizes Back-button
 * behavior across all of it. Still a single Activity per the spec — this
 * is in-memory navigation state, not a Fragment/Nav-graph switch.
 * Screen switches use WMC's zoom-through motion (S9): the outgoing screen
 * grows slightly toward the viewer as it fades, the incoming one settles
 * back from slightly small — Media Center's section-entry feel.
 */
@Composable
fun MCLauncherApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val allApps by viewModel.apps.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val startupTarget by viewModel.startupLaunch.collectAsState()
    val context = LocalContext.current

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var contextMenu by remember { mutableStateOf<ContextMenuState?>(null) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    // F1 — All Apps / Add-apps only ever show hidden packages when the
    // "Show hidden apps" toggle is on. T1 — same for non-TV (sideloaded)
    // apps when "Show non-TV apps" is off. Rows are left on the full
    // resolution path below (neither filter ever touches rows).
    val visibleApps = allApps
        .filter { settings.showHiddenApps || it.packageName !in settings.hiddenPackages }
        .filter { settings.showNonTvApps || it.isTvApp }

    // F4 — resolved Recent-row apps, newest first, excluding hidden/uninstalled.
    val recentApps = if (!settings.showRecentRow) {
        emptyList()
    } else {
        settings.recentPackages.mapNotNull { pkg ->
            allApps.find { it.packageName == pkg }?.takeIf { it.packageName !in settings.hiddenPackages }
        }
    }

    val startupAppLabel = settings.startupPackage
        ?.let { pkg -> allApps.find { it.packageName == pkg }?.label }
        ?: "None"

    // F3 — fires once per cold start when a startup package is configured;
    // the ViewModel resets startupTarget to null right after so re-entering
    // Home never relaunches it.
    LaunchedEffect(startupTarget) {
        val pkg = startupTarget ?: return@LaunchedEffect
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            runCatching { context.startActivity(it) }
        }
        viewModel.clearStartupLaunch()
    }

    // Priority: close a text-input dialog first, then a context menu, then
    // fall back to each screen's own "up" target, then — on Home with
    // nothing open — do nothing, since this is Home.
    BackHandler(enabled = true) {
        val currentScreen = screen
        when {
            dialog != null -> dialog = null
            contextMenu != null -> contextMenu = null
            currentScreen is Screen.AppPicker -> screen = Screen.EditRowDetail(currentScreen.rowId)
            currentScreen is Screen.EditRowDetail -> screen = Screen.EditRows
            currentScreen == Screen.EditRows -> screen = Screen.Home
            currentScreen == Screen.AllApps -> screen = Screen.Home
            currentScreen == Screen.Settings -> screen = Screen.Home
            else -> Unit // Home, nothing open.
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                    .togetherWith(fadeOut(tween(180)) + scaleOut(targetScale = 1.05f, animationSpec = tween(180)))
            },
            label = "screenZoom"
        ) { targetScreen ->
            when (targetScreen) {
                Screen.Home -> HomeScreen(
                    uiState = uiState,
                    settings = settings,
                    recentApps = recentApps,
                    onOpenAllApps = { screen = Screen.AllApps },
                    onOpenEditRows = { screen = Screen.EditRows },
                    onOpenSettings = { screen = Screen.Settings },
                    onGoogleTvHome = { contextMenu = ContextMenuState.ConfirmSwitchLauncher },
                    onLongPressRowTile = { rowId, app, index, rowSize ->
                        contextMenu = ContextMenuState.RowTileMenu(rowId, app, index, rowSize)
                    },
                    onAppLaunched = viewModel::recordLaunch,
                    onRemoveRecent = viewModel::removeRecent
                )

                Screen.AllApps -> AllAppsScreen(
                    apps = visibleApps,
                    showLabels = settings.showAppNames,
                    glassTiles = settings.glassTiles,
                    preferIconTiles = settings.preferIconTiles,
                    onLongPressApp = { app -> contextMenu = ContextMenuState.AddToRowMenu(app) },
                    onAppLaunched = viewModel::recordLaunch
                )

                Screen.EditRows -> EditRowsScreen(
                    rows = uiState.rows,
                    onOpenRow = { rowId -> screen = Screen.EditRowDetail(rowId) },
                    onLongPressRow = { rowId, rowName, index, rowCount ->
                        contextMenu = ContextMenuState.RowListMenu(rowId, rowName, index, rowCount)
                    },
                    onAddRow = { dialog = DialogState.AddRow }
                )

                is Screen.EditRowDetail -> {
                    val row = uiState.rows.find { it.id == targetScreen.rowId }
                    EditRowDetailScreen(
                        row = row,
                        showLabels = settings.showAppNames,
                        glassTiles = settings.glassTiles,
                        preferIconTiles = settings.preferIconTiles,
                        shortcutsById = uiState.shortcutsById,
                        onLongPressTile = { index, app ->
                            contextMenu = ContextMenuState.RowTileMenu(
                                targetScreen.rowId,
                                app,
                                index,
                                row?.apps?.size ?: 0
                            )
                        },
                        onAddApps = { screen = Screen.AppPicker(targetScreen.rowId) },
                        onAddShortcut = { contextMenu = ContextMenuState.PickShortcutTargetApp(targetScreen.rowId) },
                        onAppLaunched = viewModel::recordLaunch
                    )
                }

                is Screen.AppPicker -> {
                    val row = uiState.rows.find { it.id == targetScreen.rowId }
                    val selected = row?.apps?.map { it.packageName }?.toSet().orEmpty()
                    AppPickerScreen(
                        rowName = row?.name ?: "",
                        apps = visibleApps,
                        selectedPackages = selected,
                        showLabels = settings.showAppNames,
                        glassTiles = settings.glassTiles,
                        preferIconTiles = settings.preferIconTiles,
                        onToggle = { app ->
                            if (app.packageName in selected) {
                                viewModel.removeFromRow(targetScreen.rowId, app.packageName)
                            } else {
                                viewModel.addToRow(targetScreen.rowId, app.packageName)
                            }
                        }
                    )
                }

                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    versionName = BuildConfig.VERSION_NAME,
                    startupAppLabel = startupAppLabel,
                    onSetUse24HourClock = viewModel::setUse24HourClock,
                    onSetShowAppNames = viewModel::setShowAppNames,
                    onSetShowHiddenApps = viewModel::setShowHiddenApps,
                    onSetShowNonTvApps = viewModel::setShowNonTvApps,
                    onSetShowRecentRow = viewModel::setShowRecentRow,
                    onSetGlassTiles = viewModel::setGlassTiles,
                    onSetClassicStrips = viewModel::setClassicStrips,
                    onSetFadedTiles = viewModel::setFadedTiles,
                    onSetPreferIconTiles = viewModel::setPreferIconTiles,
                    onPickStartupApp = { contextMenu = ContextMenuState.StartupAppMenu },
                    onResetSetup = { contextMenu = ContextMenuState.ConfirmResetSetup },
                    // T2 — backup is non-destructive, runs immediately;
                    // restore overwrites live config, so it confirms first.
                    onBackup = {
                        viewModel.exportBackup { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    onRestore = { contextMenu = ContextMenuState.ConfirmRestoreBackup }
                )
            }
        }

        contextMenu?.let { menu ->
            when (menu) {
                is ContextMenuState.RowTileMenu -> ContextMenuOverlay(
                    title = menu.app.label,
                    options = rowTileMenuOptions(menu, context, viewModel) { contextMenu = null },
                    onDismiss = { contextMenu = null }
                )

                is ContextMenuState.AddToRowMenu -> ContextMenuOverlay(
                    title = "Add \"${menu.app.label}\" to…",
                    options = addToRowMenuOptions(
                        menu = menu,
                        rows = uiState.rows,
                        isHidden = menu.app.packageName in settings.hiddenPackages,
                        context = context,
                        viewModel = viewModel,
                        dismiss = { contextMenu = null }
                    ),
                    onDismiss = { contextMenu = null }
                )

                is ContextMenuState.RowListMenu -> ContextMenuOverlay(
                    title = menu.rowName,
                    options = rowListMenuOptions(
                        menu = menu,
                        viewModel = viewModel,
                        dismiss = { contextMenu = null },
                        openRenameDialog = { rowId, currentName ->
                            dialog = DialogState.RenameRow(rowId, currentName)
                        },
                        openDeleteConfirm = { rowId, rowName ->
                            contextMenu = ContextMenuState.ConfirmDeleteRow(rowId, rowName)
                        }
                    ),
                    onDismiss = { contextMenu = null }
                )

                is ContextMenuState.ConfirmDeleteRow -> ContextMenuOverlay(
                    title = "Delete \"${menu.rowName}\"?",
                    options = listOf(
                        "Cancel" to { contextMenu = null },
                        "Delete" to {
                            viewModel.deleteRow(menu.rowId)
                            contextMenu = null
                        }
                    ),
                    onDismiss = { contextMenu = null }
                )

                ContextMenuState.ConfirmRestoreBackup -> ContextMenuOverlay(
                    title = "Replace current rows and settings with the backup?",
                    options = listOf(
                        "Cancel" to { contextMenu = null },
                        "Restore" to {
                            contextMenu = null
                            viewModel.importBackup { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    ),
                    onDismiss = { contextMenu = null }
                )

                ContextMenuState.ConfirmResetSetup -> ContextMenuOverlay(
                    title = "Reset rows to the default setup?",
                    options = listOf(
                        "Cancel" to { contextMenu = null },
                        "Reset" to {
                            viewModel.resetToFirstRunSeed()
                            contextMenu = null
                            screen = Screen.Home
                        }
                    ),
                    onDismiss = { contextMenu = null }
                )

                ContextMenuState.StartupAppMenu -> ContextMenuOverlay(
                    title = "Launch on startup",
                    options = startupAppMenuOptions(allApps, viewModel) { contextMenu = null },
                    onDismiss = { contextMenu = null }
                )

                is ContextMenuState.PickShortcutTargetApp -> ContextMenuOverlay(
                    title = "Shortcut opens which app?",
                    options = allApps.map { app ->
                        app.label to {
                            contextMenu = null
                            dialog = DialogState.EnterShortcutLabel(menu.rowId, app.packageName)
                        }
                    },
                    onDismiss = { contextMenu = null }
                )

                ContextMenuState.ConfirmSwitchLauncher -> ContextMenuOverlay(
                    title = "Switch to your other Home app?",
                    options = listOf(
                        "Cancel" to { contextMenu = null },
                        "Switch" to {
                            contextMenu = null
                            launchOtherHome(context)
                        }
                    ),
                    onDismiss = { contextMenu = null }
                )
            }
        }

        dialog?.let { current ->
            when (current) {
                is DialogState.RenameRow -> TextInputDialog(
                    title = "Rename row",
                    initialValue = current.currentName,
                    confirmLabel = "Save",
                    onConfirm = { newName ->
                        viewModel.renameRow(current.rowId, newName)
                        dialog = null
                    },
                    onDismiss = { dialog = null }
                )

                DialogState.AddRow -> TextInputDialog(
                    title = "New row name",
                    initialValue = "",
                    confirmLabel = "Add",
                    onConfirm = { name ->
                        viewModel.addRow(name)
                        dialog = null
                    },
                    onDismiss = { dialog = null }
                )

                is DialogState.EnterShortcutLabel -> TextInputDialog(
                    title = "Card name (e.g. Movies)",
                    initialValue = "",
                    confirmLabel = "Next",
                    onConfirm = { label ->
                        if (label.isNotBlank()) {
                            dialog = DialogState.EnterShortcutUri(current.rowId, current.targetPackage, label.trim())
                        }
                    },
                    onDismiss = { dialog = null }
                )

                is DialogState.EnterShortcutUri -> TextInputDialog(
                    title = "Deep link URI (e.g. channels://navigate/Movies)",
                    initialValue = "",
                    confirmLabel = "Add",
                    onConfirm = { uri ->
                        if (uri.isNotBlank()) {
                            viewModel.addShortcut(current.rowId, current.label, current.targetPackage, uri.trim())
                        }
                        dialog = null
                    },
                    onDismiss = { dialog = null }
                )
            }
        }
    }
}

private fun rowTileMenuOptions(
    menu: ContextMenuState.RowTileMenu,
    context: Context,
    viewModel: MainViewModel,
    dismiss: () -> Unit
): List<Pair<String, () -> Unit>> {
    val options = mutableListOf<Pair<String, () -> Unit>>()

    if (menu.index > 0) {
        options += "Move left" to {
            viewModel.moveWithinRow(menu.rowId, menu.app.packageName, -1)
            dismiss()
        }
    }
    if (menu.index < menu.rowSize - 1) {
        options += "Move right" to {
            viewModel.moveWithinRow(menu.rowId, menu.app.packageName, 1)
            dismiss()
        }
    }
    options += "Remove from row" to {
        // For a shortcut card this also deletes the underlying ShortcutConfig
        // (see MainViewModel.removeFromRow) — there's no separate "delete" step.
        viewModel.removeFromRow(menu.rowId, menu.app.packageName)
        dismiss()
    }
    // "App info"/"Uninstall" only make sense for real installed apps — a
    // built-in system-action card or shortcut card has no real package to
    // open/uninstall.
    if (!SystemActions.isSystemAction(menu.app.packageName) && !ShortcutConfig.isShortcutId(menu.app.packageName)) {
        options += "App info" to {
            openAppInfo(context, menu.app.packageName)
            dismiss()
        }
        options += "Uninstall" to {
            uninstallApp(context, menu.app.packageName)
            dismiss()
        }
    }
    return options
}

private fun addToRowMenuOptions(
    menu: ContextMenuState.AddToRowMenu,
    rows: List<RowUiState>,
    isHidden: Boolean,
    context: Context,
    viewModel: MainViewModel,
    dismiss: () -> Unit
): List<Pair<String, () -> Unit>> {
    val options: MutableList<Pair<String, () -> Unit>> = rows.map { row ->
        row.name to {
            viewModel.addToRow(row.id, menu.app.packageName)
            dismiss()
        }
    }.toMutableList()

    // F1 — hide/unhide (only reachable to unhide when Show-hidden is on,
    // since a hidden app otherwise wouldn't be in this list at all).
    options += if (isHidden) {
        "Unhide app" to {
            viewModel.unhideApp(menu.app.packageName)
            dismiss()
        }
    } else {
        "Hide app" to {
            viewModel.hideApp(menu.app.packageName)
            dismiss()
        }
    }

    if (!SystemActions.isSystemAction(menu.app.packageName)) {
        options += "Uninstall" to {
            uninstallApp(context, menu.app.packageName)
            dismiss()
        }
    }

    return options
}

private fun startupAppMenuOptions(
    allApps: List<AppInfo>,
    viewModel: MainViewModel,
    dismiss: () -> Unit
): List<Pair<String, () -> Unit>> {
    val options = mutableListOf<Pair<String, () -> Unit>>()
    options += "None" to {
        viewModel.setStartupPackage(null)
        dismiss()
    }
    allApps.forEach { app ->
        options += app.label to {
            viewModel.setStartupPackage(app.packageName)
            dismiss()
        }
    }
    return options
}

private fun rowListMenuOptions(
    menu: ContextMenuState.RowListMenu,
    viewModel: MainViewModel,
    dismiss: () -> Unit,
    openRenameDialog: (rowId: String, currentName: String) -> Unit,
    openDeleteConfirm: (rowId: String, rowName: String) -> Unit
): List<Pair<String, () -> Unit>> {
    val options = mutableListOf<Pair<String, () -> Unit>>()

    options += "Rename" to {
        dismiss()
        openRenameDialog(menu.rowId, menu.rowName)
    }
    if (menu.index > 0) {
        options += "Move up" to {
            viewModel.moveRow(menu.rowId, -1)
            dismiss()
        }
    }
    if (menu.index < menu.rowCount - 1) {
        options += "Move down" to {
            viewModel.moveRow(menu.rowId, 1)
            dismiss()
        }
    }
    options += "Delete" to {
        dismiss()
        openDeleteConfirm(menu.rowId, menu.rowName)
    }
    return options
}

private fun openAppInfo(context: Context, packageName: String) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't open app info", Toast.LENGTH_SHORT).show()
    }
}

private fun uninstallApp(context: Context, packageName: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't start uninstall", Toast.LENGTH_SHORT).show()
    }
}

/**
 * "Google TV Home" — fires the standard HOME intent, which on a box with
 * more than one Home app (e.g. the onn 4K's stock launcher alongside WMC)
 * prompts the system app-picker rather than jumping straight to a specific
 * app. Only reached after the user confirms via ConfirmSwitchLauncher, so a
 * stray remote press on this card can no longer leave WMC by accident.
 */
private fun launchOtherHome(context: Context) {
    // S33 — tell the watchdog this is a deliberate visit so it doesn't
    // immediately bounce the user back here.
    HomeHandoff.beginDeliberateVisit()
    try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't find another Home app", Toast.LENGTH_SHORT).show()
    }
}
