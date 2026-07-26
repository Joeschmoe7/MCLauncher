package com.wmc.mediacenter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.wmc.mediacenter.apps.AppInfo
import com.wmc.mediacenter.ui.components.AppTile

/**
 * Every installed launchable app, alphabetical, 5 columns, same tile
 * styling as the home rows — "search" is just scrolling, per spec.
 * Reached from the system row; Back returns Home (handled one level up,
 * in MCLauncherApp). Long-press OK on a tile opens "Add to row →".
 */
@Composable
fun AllAppsScreen(
    apps: List<AppInfo>,
    showLabels: Boolean,
    glassTiles: Boolean,
    preferIconTiles: Boolean,
    onLongPressApp: (AppInfo) -> Unit,
    onAppLaunched: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .wmcBackground()
    ) {
        Text(
            text = "All Apps",
            modifier = Modifier.padding(start = 48.dp, top = 40.dp, bottom = 16.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(apps, key = { it.packageName }) { app ->
                AppTile(
                    app = app,
                    showLabel = showLabels,
                    glassTiles = glassTiles,
                    preferIcons = preferIconTiles,
                    onLongClick = { onLongPressApp(app) },
                    onAppLaunched = onAppLaunched
                )
            }
        }
    }
}
