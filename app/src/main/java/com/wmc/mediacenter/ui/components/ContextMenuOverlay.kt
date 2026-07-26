package com.wmc.mediacenter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wmc.mediacenter.ui.theme.WmcAccentCyan
import com.wmc.mediacenter.ui.theme.WmcNavyMid
import com.wmc.mediacenter.ui.theme.WmcTextPrimary

/**
 * Generic dimmed full-screen overlay with a centered, D-pad-navigable
 * vertical menu. Used for both the Home row-tile context menu
 * (Move left/right, Remove from row, App info) and the All Apps
 * "Add to row" submenu — same visual language, different option lists.
 */
@Composable
fun ContextMenuOverlay(
    title: String,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val firstOptionFocusRequester = remember { FocusRequester() }

    // The long-press that opened this menu is still physically held down when
    // the overlay first appears and grabs focus. Android auto-repeats a held
    // DPAD-center/Enter key — repeated key-DOWNs land on whatever now has
    // focus (the first option), not just the eventual key-UP — so a plain
    // "ignore one unpaired release" guard still let the option's own
    // clickable see a repeat-down and treat the real release as its matching
    // up, firing the option instantly. Fix: block every confirm-key event
    // (repeats included) from ever reaching the menu's children until the
    // physical button is actually released (its first key-up), then open the
    // gate for good — later, genuinely new presses work normally.
    var armed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .onPreviewKeyEvent { event ->
                val isConfirmKey = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (armed || !isConfirmKey) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) armed = true
                true // swallow every confirm-key event until that first release
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WmcNavyMid)
                // Absorb clicks so tapping inside the panel doesn't fall through
                // to the scrim's dismiss handler behind it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(vertical = 16.dp)
        ) {
            Text(
                text = title,
                color = WmcTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            options.forEachIndexed { index, (label, action) ->
                MenuOptionRow(
                    label = label,
                    onClick = action,
                    modifier = if (index == 0) Modifier.focusRequester(firstOptionFocusRequester) else Modifier
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { firstOptionFocusRequester.requestFocus() }
    }
}

@Composable
private fun MenuOptionRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isFocused) WmcAccentCyan.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            color = if (isFocused) WmcAccentCyan else WmcTextPrimary,
            fontSize = 15.sp
        )
    }
}
