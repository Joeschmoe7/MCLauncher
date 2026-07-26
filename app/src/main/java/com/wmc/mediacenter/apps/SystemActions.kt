package com.wmc.mediacenter.apps

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

    /** Default set + order for the seeded "Settings" row. */
    val DEFAULT_SETTINGS_ROW: List<String> = listOf(ALL_APPS, EDIT_ROWS, SETTINGS, GOOGLE_TV_HOME)

    private val LABELS: Map<String, String> = linkedMapOf(
        ALL_APPS to "All Apps",
        EDIT_ROWS to "Edit Rows",
        SETTINGS to "Settings",
        GOOGLE_TV_HOME to "Google TV Home"
    )

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
