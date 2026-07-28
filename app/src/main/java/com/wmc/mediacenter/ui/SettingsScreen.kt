package com.wmc.mediacenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wmc.mediacenter.data.AppSettings
import com.wmc.mediacenter.ui.theme.WmcAccentCyan
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import com.wmc.mediacenter.ui.theme.WmcTileSurface

/**
 * Minimal Settings, per spec: clock format, app-name labels, re-run
 * first-time setup, version info. Toggles persist immediately.
 * "Re-run first-time setup" is destructive (wipes custom rows), so its
 * confirmation is handled one level up in MCLauncherApp, same pattern
 * as deleting a row.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    versionName: String,
    startupAppLabel: String,
    onSetUse24HourClock: (Boolean) -> Unit,
    onSetShowAppNames: (Boolean) -> Unit,
    onSetShowHiddenApps: (Boolean) -> Unit,
    onSetShowNonTvApps: (Boolean) -> Unit,
    onSetShowRecentRow: (Boolean) -> Unit,
    onSetGlassTiles: (Boolean) -> Unit,
    onSetClassicStrips: (Boolean) -> Unit,
    onSetFadedTiles: (Boolean) -> Unit,
    onSetPreferIconTiles: (Boolean) -> Unit,
    onPickStartupApp: () -> Unit,
    onResetSetup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .wmcBackground()
            // The option list has outgrown a TV screen — scroll, with D-pad
            // focus dragging the viewport along via bring-into-view.
            .verticalScroll(rememberScrollState())
            .padding(top = 56.dp, bottom = 32.dp, start = 48.dp, end = 48.dp)
    ) {
        Text(text = "Settings", modifier = Modifier.padding(bottom = 24.dp))

        SettingsRow(
            label = "Clock format",
            valueLabel = if (settings.use24HourClock) "24-hour" else "12-hour",
            onClick = { onSetUse24HourClock(!settings.use24HourClock) }
        )
        SettingsRow(
            label = "App names under tiles",
            valueLabel = if (settings.showAppNames) "Shown" else "Hidden",
            onClick = { onSetShowAppNames(!settings.showAppNames) }
        )
        SettingsRow(
            label = "Show hidden apps",
            valueLabel = if (settings.showHiddenApps) "Shown" else "Hidden",
            onClick = { onSetShowHiddenApps(!settings.showHiddenApps) }
        )
        SettingsRow(
            // T1 — sideloaded phone/tablet apps (no leanback entry) in
            // All Apps / the Add-apps picker.
            label = "Show non-TV apps",
            valueLabel = if (settings.showNonTvApps) "Shown" else "Hidden",
            onClick = { onSetShowNonTvApps(!settings.showNonTvApps) }
        )
        SettingsRow(
            label = "Launch on startup",
            valueLabel = startupAppLabel,
            onClick = onPickStartupApp
        )
        SettingsRow(
            label = "Recent apps row",
            valueLabel = if (settings.showRecentRow) "Shown" else "Hidden",
            onClick = { onSetShowRecentRow(!settings.showRecentRow) }
        )
        SettingsRow(
            label = "Glassy tiles",
            valueLabel = if (settings.glassTiles) "On" else "Off",
            onClick = { onSetGlassTiles(!settings.glassTiles) }
        )
        SettingsRow(
            // S9 — authentic WMC start-menu behavior: tiles only on the
            // highlighted row, everything else collapses to its title.
            label = "Tiles on highlighted row only",
            valueLabel = if (settings.classicStrips) "On" else "Off",
            onClick = { onSetClassicStrips(!settings.classicStrips) }
        )
        SettingsRow(
            // S11 — authentic WMC: unhighlighted tiles render as pale faded-
            // blue silhouettes (artwork backgrounds dissolved); only the
            // highlighted tile is full color.
            label = "Fade unhighlighted tiles",
            valueLabel = if (settings.fadedTiles) "On" else "Off",
            onClick = { onSetFadedTiles(!settings.fadedTiles) }
        )
        SettingsRow(
            // S11 — banner (TV artwork, default) vs centered app icon.
            label = "Tile artwork",
            valueLabel = if (settings.preferIconTiles) "Icon" else "Banner",
            onClick = { onSetPreferIconTiles(!settings.preferIconTiles) }
        )
        SettingsRow(
            label = "Re-run first-time setup",
            valueLabel = null,
            onClick = onResetSetup
        )

        Text(
            text = "MediaCenter $versionName",
            color = WmcTextPrimary.copy(alpha = 0.6f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 32.dp)
        )
    }
}

@Composable
private fun SettingsRow(label: String, valueLabel: String?, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) WmcTileSurface else Color.Transparent)
            .then(
                if (isFocused) Modifier.border(2.dp, WmcAccentCyan, RoundedCornerShape(8.dp)) else Modifier
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = if (isFocused) WmcAccentCyan else WmcTextPrimary,
                fontSize = 16.sp
            )
            if (valueLabel != null) {
                Text(
                    text = valueLabel,
                    color = if (isFocused) WmcAccentCyan else WmcTextPrimary.copy(alpha = 0.75f),
                    fontSize = 16.sp
                )
            }
        }
    }
}
