package com.wmc.mediacenter.apps

import android.content.Intent
import android.provider.Settings

/**
 * The launcher's built-in actions — the items that used to live in the fixed
 * bottom bar (SystemRow). They aren't real installed apps, so they can't be
 * addressed by a real package name. Instead each one gets a sentinel id that
 * the rest of the app recognizes: [buildUiState] resolves a sentinel to a
 * synthetic [AppInfo] so it renders as a normal tile, and Home dispatches a
 * click on one to the matching navigation action instead of launching a
 * package.
 *
 * Modeling them this way lets them sit inside an ordinary, fully-editable
 * [com.wmc.mediacenter.data.RowConfig] (the default "Settings" row) — the user
 * can rename/reorder/delete that row and reorder or remove the cards, exactly
 * like any other row.
 *
 * "Google Play Store" is intentionally NOT here: it's a real installed app, so
 * it already shows up as a normal card via All Apps / any row.
 */
object SystemActions {
    const val ALL_APPS = "wmc.system.all_apps"
    const val EDIT_ROWS = "wmc.system.edit_rows"
    const val SETTINGS = "wmc.system.settings"
    const val GOOGLE_TV_HOME = "wmc.system.google_tv_home"

    // Shortcuts into Android's own TV Settings. Not seeded into any row — add
    // them from "+ Add apps" in Edit Rows, where every built-in card is listed.
    const val TV_SETTINGS = "wmc.system.tv_settings"
    const val NETWORK = "wmc.system.network"
    const val DISPLAY_SOUND = "wmc.system.display_sound"
    const val PAIR_ACCESSORY = "wmc.system.pair_accessory"

    /** Default set + order for the seeded "Settings" row. */
    val DEFAULT_SETTINGS_ROW: List<String> = listOf(ALL_APPS, EDIT_ROWS, SETTINGS, GOOGLE_TV_HOME)

    /**
     * Cards that must always exist in some row. They're the only way into
     * Edit Rows and Settings, and the one place a built-in card can be added
     * back (Edit Rows' "+ Add apps") is itself behind the Edit Rows card — so
     * losing the last copy locked the user out of their own setup until app
     * data was cleared. See MainViewModel.withEssentialCards.
     */
    val ESSENTIAL: List<String> = listOf(EDIT_ROWS, SETTINGS)

    private val LABELS: Map<String, String> = linkedMapOf(
        ALL_APPS to "All Apps",
        EDIT_ROWS to "Edit Rows",
        SETTINGS to "Settings",
        GOOGLE_TV_HOME to "Google TV Home",
        TV_SETTINGS to "TV Settings",
        NETWORK to "Network",
        DISPLAY_SOUND to "Display & Sound",
        PAIR_ACCESSORY to "Pair Accessory"
    )

    /** Every built-in card, in picker order — so a removed one can always be added back. */
    val ALL: List<String> get() = LABELS.keys.toList()

    private const val TV_SETTINGS_PACKAGE = "com.android.tv.settings"

    /**
     * For the Android-settings cards: intents to try in order, most specific
     * first. Verified on the onn 4K (Google TV): WIFI_SETTINGS opens Network &
     * Internet; SOUND_SETTINGS opens Display & Sound but ALSO matches another
     * app, so it's pinned to TV Settings to skip a chooser; CONNECT_INPUT is
     * the "Pair remote or accessory" screen. DISPLAY_SETTINGS and
     * BLUETOOTH_SETTINGS resolve to nothing there, and "Remotes &
     * Accessories" is a panel inside TV Settings with no activity of its own.
     * Each list ends in the main Settings screen so a box that lacks the
     * specific page still lands somewhere useful. Empty for other cards.
     */
    fun settingsIntentsFor(id: String): List<Intent> = when (id) {
        TV_SETTINGS -> listOf(Intent(Settings.ACTION_SETTINGS))
        NETWORK -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        DISPLAY_SOUND -> listOf(
            Intent(Settings.ACTION_SOUND_SETTINGS).setPackage(TV_SETTINGS_PACKAGE),
            Intent(Settings.ACTION_SETTINGS)
        )
        PAIR_ACCESSORY -> listOf(
            Intent("com.google.android.intent.action.CONNECT_INPUT"),
            Intent(Settings.ACTION_SETTINGS)
        )
        else -> emptyList()
    }

    fun isSystemAction(packageName: String): Boolean = packageName in LABELS

    fun labelFor(packageName: String): String? = LABELS[packageName]

    /**
     * A synthetic [AppInfo] for a sentinel id, or null if [packageName] isn't a
     * system action. No icon/banner — the tile falls back to its text label,
     * same as any installed app that ships without artwork.
     */
    fun appInfoFor(packageName: String): AppInfo? =
        LABELS[packageName]?.let { label ->
            AppInfo(packageName = packageName, label = label, icon = null, banner = null)
        }
}
