package com.wmc.mediacenter.ui

import com.wmc.mediacenter.apps.AppInfo

sealed interface Screen {
    data object Home : Screen
    data object AllApps : Screen
    data object EditRows : Screen
    data class EditRowDetail(val rowId: String) : Screen
    data class AppPicker(val rowId: String) : Screen
    data object Settings : Screen
}

sealed interface ContextMenuState {
    /** Long-press on an app tile (Home row or Edit Row detail): Move left/right, Remove from row, App info. */
    data class RowTileMenu(
        val rowId: String,
        val app: AppInfo,
        val index: Int,
        val rowSize: Int
    ) : ContextMenuState

    /** Long-press on an All Apps tile: pick which row to add it to. */
    data class AddToRowMenu(val app: AppInfo) : ContextMenuState

    /** Long-press on a row entry in Edit Rows: Rename, Move up/down, Delete. */
    data class RowListMenu(
        val rowId: String,
        val rowName: String,
        val index: Int,
        val rowCount: Int
    ) : ContextMenuState

    /** Confirm before actually deleting a row. */
    data class ConfirmDeleteRow(val rowId: String, val rowName: String) : ContextMenuState

    /** Confirm before wiping the row layout back to the first-run seed. */
    data object ConfirmResetSetup : ContextMenuState

    /** Confirm before switching to the box's other Home app — the one deliberate "leave WMC" action, gated so a stray click can't trigger it. */
    data object ConfirmSwitchLauncher : ContextMenuState

    /** F3 — Settings' "Launch on startup" picker: None, then one entry per installed app. */
    data object StartupAppMenu : ContextMenuState

    /** "+ Add shortcut" step 1: pick which installed app the new deep-link card should open. */
    data class PickShortcutTargetApp(val rowId: String) : ContextMenuState
}

/** Text-input overlays — kept separate from [ContextMenuState] since these need a keyboard, not a list of options. */
sealed interface DialogState {
    data class RenameRow(val rowId: String, val currentName: String) : DialogState
    data object AddRow : DialogState

    /** "+ Add shortcut" step 2: name the card (e.g. "Movies"). */
    data class EnterShortcutLabel(val rowId: String, val targetPackage: String) : DialogState

    /** "+ Add shortcut" step 3: the deep-link URI to fire at the target app (e.g. channels://navigate/Movies). */
    data class EnterShortcutUri(val rowId: String, val targetPackage: String, val label: String) : DialogState
}
