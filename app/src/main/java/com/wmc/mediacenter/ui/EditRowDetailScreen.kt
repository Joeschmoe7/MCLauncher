package com.wmc.mediacenter.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wmc.mediacenter.RowUiState
import com.wmc.mediacenter.apps.AppInfo
import com.wmc.mediacenter.data.ShortcutConfig
import com.wmc.mediacenter.ui.components.AppTile
import com.wmc.mediacenter.ui.theme.WmcAccentCyan
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import com.wmc.mediacenter.ui.theme.WmcTileSurface
import kotlin.math.max

/**
 * One row's apps: reorder/remove via the same long-press context menu
 * used on the Home screen, plus a trailing "+ Add apps" tile that opens
 * the full-app picker for this row.
 */
@Composable
fun EditRowDetailScreen(
    row: RowUiState?,
    showLabels: Boolean,
    glassTiles: Boolean,
    preferIconTiles: Boolean,
    shortcutsById: Map<String, ShortcutConfig>,
    onLongPressTile: (index: Int, app: AppInfo) -> Unit,
    onAddApps: () -> Unit,
    onAddShortcut: () -> Unit,
    onAppLaunched: (String) -> Unit = {}
) {
    val apps = row?.apps.orEmpty()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .wmcBackground()
            .padding(top = 56.dp)
    ) {
        Text(
            text = row?.name ?: "Row",
            modifier = Modifier.padding(start = 48.dp, bottom = 16.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                val shortcut = shortcutsById[app.packageName]
                AppTile(
                    app = app,
                    showLabel = showLabels,
                    glassTiles = glassTiles,
                    preferIcons = preferIconTiles,
                    // A shortcut card fires its stored deep link instead of
                    // AppTile's default launch-by-package behavior (its
                    // "package name" is a synthetic sentinel id, not real).
                    onClick = shortcut?.let { { launchShortcut(context, it) } },
                    onLongClick = { onLongPressTile(index, app) },
                    onAppLaunched = onAppLaunched
                )
            }
            item {
                AddAppsTile(label = "+ Add apps", glassTiles = glassTiles, onClick = onAddApps)
            }
            item {
                AddAppsTile(label = "+ Add shortcut", glassTiles = glassTiles, onClick = onAddShortcut)
            }
        }
    }
}

// 1b — matches AppTile's S7 glass-chiclet treatment (glass card + top-left
// glow) instead of the old opaque WmcTileSurface fill, so the utility tile
// reads as part of the same tile family.
private val AddAppsGlassRest = Brush.verticalGradient(
    0f to Color(0x5A4A7FB5), 0.5f to Color(0x4A2E5F96), 1f to Color(0x5A1E4470)
)
private val AddAppsGlassFocused = Brush.verticalGradient(
    0f to Color(0xE64A7FB5), 0.5f to Color(0xD92E5F96), 1f to Color(0xE61E4470)
)

@Composable
private fun AddAppsTile(label: String, glassTiles: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusFraction by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "addAppsFocusFraction"
    )

    Box(
        modifier = Modifier
            .width(220.dp)
            .height(130.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (glassTiles) {
                    Modifier
                        .background(AddAppsGlassRest)
                        .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                } else {
                    // Off (default): plain flat tile, matching the app's
                    // original utility-tile look.
                    Modifier
                        .background(WmcTileSurface)
                        .then(
                            if (isFocused) Modifier.border(2.dp, WmcAccentCyan, RoundedCornerShape(8.dp)) else Modifier
                        )
                }
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (glassTiles) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = focusFraction }
                    .background(AddAppsGlassFocused)
                    .border(2.dp, WmcAccentCyan, RoundedCornerShape(8.dp))
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val glow = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFE2F0FC).copy(alpha = 0.30f + 0.30f * focusFraction),
                                Color(0xFFD2E8FA).copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.18f, size.height * 0.18f),
                            radius = max(size.width, size.height) * 0.75f
                        )
                        onDrawBehind { drawRect(glow) }
                    }
            )
        }
        Text(
            text = label,
            color = if (isFocused) WmcAccentCyan else WmcTextPrimary,
            fontSize = 15.sp
        )
    }
}
