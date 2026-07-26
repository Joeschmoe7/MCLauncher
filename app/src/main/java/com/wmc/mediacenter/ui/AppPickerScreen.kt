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
 * Full-app grid with checkboxes for "+ Add apps": checked = already in
 * this row. OK toggles membership immediately (no separate save step) —
 * add via ViewModel.addToRow, remove via ViewModel.removeFromRow.
 */
@Composable
fun AppPickerScreen(
    rowName: String,
    apps: List<AppInfo>,
    selectedPackages: Set<String>,
    showLabels: Boolean,
    glassTiles: Boolean,
    preferIconTiles: Boolean,
    onToggle: (AppInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .wmcBackground()
    ) {
        Text(
            text = "Add apps to “$rowName”",
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
                    isSelected = app.packageName in selectedPackages,
                    showLabel = showLabels,
                    glassTiles = glassTiles,
                    preferIcons = preferIconTiles,
                    onClick = { onToggle(app) }
                )
            }
        }
    }
}
