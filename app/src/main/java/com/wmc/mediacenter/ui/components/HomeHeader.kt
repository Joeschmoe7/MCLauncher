package com.wmc.mediacenter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * WMC-style header: just the live clock top-right (h:mm + day/date, 12/24h
 * per Settings). S9 — the focused row's name is no longer duplicated here;
 * real WMC has no separate header title — the strip titles in the stack ARE
 * the labels.
 */
@Composable
fun HomeHeader(use24HourClock: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        Clock(
            use24HourClock = use24HourClock,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
        )
    }
}

@Composable
private fun Clock(use24HourClock: Boolean, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            // Wake up right at the next minute boundary instead of polling every second.
            val millisIntoCurrentMinute = (now.second * 1_000L) + (now.nano / 1_000_000L)
            delay(60_000L - millisIntoCurrentMinute)
        }
    }

    val timePattern = if (use24HourClock) "HH:mm" else "h:mm a"
    val timeFormatter = remember(use24HourClock) { DateTimeFormatter.ofPattern(timePattern) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MMMM d") }
    val timeText = now.format(timeFormatter)
    val dateText = now.format(dateFormatter)

    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        Text(text = timeText, style = MaterialTheme.typography.headlineSmall, color = WmcTextPrimary)
        Text(text = dateText, style = MaterialTheme.typography.bodyMedium, color = WmcTextPrimary.copy(alpha = 0.75f))
    }
}
