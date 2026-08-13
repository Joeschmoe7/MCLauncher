package com.wmc.mediacenter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wmc.mediacenter.ui.theme.WmcNavyMid
import com.wmc.mediacenter.ui.theme.WmcTextPrimary

/**
 * S36 — read-only counterpart to [TextInputDialog]: a title, a body of
 * explanatory text, and two buttons. Added for the "you still have to pick
 * it in Android's own Settings" instructions the screensaver toggle shows,
 * which is more than a Toast can carry — it's a multi-step path the user
 * has to follow in another app, so it needs to stay on screen while they
 * read it.
 *
 * Focus lands on the confirm button so D-pad Enter does the useful thing
 * immediately; Back dismisses, same as [TextInputDialog].
 */
@Composable
fun MessageDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(WmcNavyMid)
                .padding(24.dp)
        ) {
            Text(
                text = title,
                color = WmcTextPrimary,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            Text(
                text = message,
                // No secondary text token in the theme — a dimmed primary is
                // what PhotoWallScreensaver's own body copy already uses.
                color = WmcTextPrimary.copy(alpha = 0.8f),
                fontSize = 14.sp,
                lineHeight = 22.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.End
            ) {
                DialogButton(label = dismissLabel, onClick = onDismiss)
                DialogButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .focusRequester(confirmFocusRequester)
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { confirmFocusRequester.requestFocus() }
    }
}
