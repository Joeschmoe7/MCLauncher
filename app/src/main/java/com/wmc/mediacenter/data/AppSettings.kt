package com.wmc.mediacenter.data

data class AppSettings(
    val use24HourClock: Boolean = false,
    val showAppNames: Boolean = true,
    /** F1 — packages hidden from All Apps / the Add-apps picker (rows are unaffected). */
    val hiddenPackages: Set<String> = emptySet(),
    /** F1 — reveal hidden apps in All Apps / Add-apps so they can be unhidden. */
    val showHiddenApps: Boolean = false,
    /**
     * T1 — ON by default: All Apps / the Add-apps picker include sideloaded
     * non-TV apps (CATEGORY_LAUNCHER only, no leanback entry — Downloader,
     * browsers, utilities). Off restores the old TV-apps-only listing.
     * Rows are unaffected either way, same as [hiddenPackages].
     */
    val showNonTvApps: Boolean = true,
    /** F3 — package launched automatically once per cold start, or null for none. */
    val startupPackage: String? = null,
    /** F4 — off by default; controls whether the Recent row renders on Home. */
    val showRecentRow: Boolean = false,
    /** F4 — most-recently-launched packages, newest first, capped at 12. */
    val recentPackages: List<String> = emptyList(),
    /** S7 — off by default: tiles show the full banner/icon with no inset or glass card. On: WMC glass-chiclet look (inset art, glass surface, top-left glow). */
    val glassTiles: Boolean = false,
    /** S9 — ON by default (authentic WMC): only the highlighted row shows its tiles; other rows collapse to just their title. Off: every row always shows tiles. */
    val classicStrips: Boolean = true,
    /**
     * S11 — ON by default (authentic WMC): only the highlighted tile shows its
     * full-color artwork; other tiles in the strip render as a pale faded-blue
     * silhouette with the artwork's background (e.g. a banner's white fill)
     * dissolved away. Off: every tile always shows full-color art.
     */
    val fadedTiles: Boolean = true,
    /**
     * S11 — OFF by default: tiles prefer the app's TV banner (icon fallback).
     * On: tiles show the centered app icon instead — icons are usually
     * transparent-background logos, so this also reads better with fadedTiles.
     */
    val preferIconTiles: Boolean = false,
    /**
     * S35 — folder the photo wall screensaver reads from. Null means "use
     * the default" (`ScreensaverPhotoRepository.DEFAULT_FOLDER_PATH`,
     * `/sdcard/MCLauncher/Screensaver`), consistent with the
     * `/sdcard/MCLauncher/` convention BackupRepository already established.
     */
    val screensaverFolderPath: String? = null,
    /**
     * S36 — OFF by default, deliberately: installing a launcher is not
     * consent to replace whatever screensaver someone already uses, and
     * this one is opinionated enough (a photo wall wanting a folder of
     * photos) that it should be opted into. Off keeps the DreamService
     * component disabled, so the photo wall never appears in the system's
     * screensaver picker and the user's existing choice is untouched. On
     * enables the component and MCLauncher then explains that Android still
     * needs them to select it — see
     * [com.wmc.mediacenter.screensaver.ScreensaverAvailability] for why this
     * is a component toggle and not something the dream just reads.
     */
    val screensaverEnabled: Boolean = false,
    /**
     * S36 — whatever `Settings.Secure.screensaver_components` pointed at
     * before MCLauncher took the slot, so switching the screensaver back off
     * can hand it back rather than just clearing it. Device-local bookkeeping,
     * never shown in the UI and deliberately excluded from backup/restore — a
     * component name from one box is meaningless on another.
     */
    val screensaverPreviousDream: String? = null
)
