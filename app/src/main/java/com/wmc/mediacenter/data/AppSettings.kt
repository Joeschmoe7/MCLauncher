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
    val preferIconTiles: Boolean = false
)
