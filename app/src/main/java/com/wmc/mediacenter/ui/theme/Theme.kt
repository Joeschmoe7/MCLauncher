package com.wmc.mediacenter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val WmcColorScheme = darkColorScheme(
    primary = WmcAccentCyan,
    background = WmcNavyDark,
    surface = WmcNavyMid,
    onBackground = WmcTextPrimary,
    onSurface = WmcTextPrimary
)

@Composable
fun MediaCenterTheme(content: @Composable () -> Unit) {
    // S9 — Selawik (Segoe UI stand-in) from assets when present, platform
    // sans-serif otherwise. Resolved once; see Type.kt for install steps.
    val context = LocalContext.current
    val typography = remember {
        wmcTypography(selawikFontFamilyOrNull(context.assets) ?: FontFamily.SansSerif)
    }
    MaterialTheme(
        colorScheme = WmcColorScheme,
        typography = typography
    ) {
        // Without this, any Text() that doesn't set an explicit color falls
        // back to LocalContentColor's default (black) outside a Surface —
        // which is exactly why row/screen titles were rendering black
        // instead of the WMC off-white against the navy background.
        CompositionLocalProvider(LocalContentColor provides WmcTextPrimary) {
            content()
        }
    }
}
