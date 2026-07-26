package com.wmc.mediacenter.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wmc.mediacenter.ui.theme.WmcAccentCyan
import com.wmc.mediacenter.ui.theme.WmcNavyDark
import com.wmc.mediacenter.ui.theme.WmcNavyMid
import com.wmc.mediacenter.ui.theme.WmcTextPrimary

/**
 * Full-screen dimmed overlay with a single text field — focusing it pops
 * up the system's on-screen keyboard, as required for Edit Rows renaming
 * and adding rows. Doesn't dismiss on outside tap (unlike
 * [ContextMenuOverlay]) so a stray click mid-typing can't lose the edit;
 * Back is the only way out besides Cancel/Save.
 */
@Composable
fun TextInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember {
        mutableStateOf(TextFieldValue(text = initialValue, selection = TextRange(initialValue.length)))
    }
    val fieldFocusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WmcNavyMid)
                .padding(20.dp)
        ) {
            Text(
                text = title,
                color = WmcTextPrimary,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = WmcTextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(WmcAccentCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocusRequester)
                    .clip(RoundedCornerShape(8.dp))
                    .background(WmcNavyDark)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.End
            ) {
                DialogButton(label = "Cancel", onClick = onDismiss)
                DialogButton(
                    label = confirmLabel,
                    onClick = { onConfirm(value.text) },
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { fieldFocusRequester.requestFocus() }
    }
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) WmcAccentCyan.copy(alpha = 0.25f) else Color.Transparent)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) WmcAccentCyan else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text = label, color = if (isFocused) WmcAccentCyan else WmcTextPrimary, fontSize = 14.sp)
    }
}
