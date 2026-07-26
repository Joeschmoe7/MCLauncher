package com.wmc.mediacenter.data

import com.wmc.mediacenter.apps.SystemActions
import java.util.UUID

/**
 * Known packages for the apps named in the first-run seed. Several apps
 * ship under different package names on TV vs. mobile builds, so each
 * entry lists every candidate — whichever one is actually installed wins.
 */
private val TV_ROW_CANDIDATES: List<List<String>> = listOf(
    listOf("com.getchannels.dvr.app"),                                    // Channels
    listOf("com.google.android.youtube.tv", "com.google.android.youtube"), // YouTube
    listOf("com.netflix.mediaclient", "com.netflix.ninja")                // Netflix
)

private val MOVIES_ROW_CANDIDATES: List<List<String>> = listOf(
    listOf("com.plexapp.android"),               // Plex
    listOf("com.amazon.avod.thirdpartyclient"),  // Prime Video
    listOf("com.disney.disneyplus")              // Disney+
)

/**
 * First-run seed: three example rows — "TV", "Movies", "Apps" — populated
 * with whichever of Channels / Plex / Netflix / YouTube / Prime Video /
 * Disney+ are actually installed. "Apps" starts empty; it's just a spare
 * row for the user to fill in once Edit Rows (P4) exists. Anything not
 * matched here still shows up in All Apps (P3).
 *
 * A trailing "Settings" row replaces the old fixed bottom bar: it's seeded
 * with the built-in [SystemActions] cards (All Apps / Edit Rows / Settings /
 * Google TV Home) but is a perfectly ordinary, editable row — the user can
 * rename it, move it, delete it, or drop other apps into it.
 */
fun buildSeedConfig(installedPackages: Set<String>): LauncherConfig {
    fun matchInstalled(candidateGroups: List<List<String>>): List<String> =
        candidateGroups.mapNotNull { candidates -> candidates.firstOrNull { it in installedPackages } }

    return LauncherConfig(
        rows = listOf(
            RowConfig(id = UUID.randomUUID().toString(), name = "TV", packages = matchInstalled(TV_ROW_CANDIDATES)),
            RowConfig(id = UUID.randomUUID().toString(), name = "Movies", packages = matchInstalled(MOVIES_ROW_CANDIDATES)),
            RowConfig(id = UUID.randomUUID().toString(), name = "Apps", packages = emptyList()),
            RowConfig(id = UUID.randomUUID().toString(), name = "Settings", packages = SystemActions.DEFAULT_SETTINGS_ROW)
        )
    )
}
