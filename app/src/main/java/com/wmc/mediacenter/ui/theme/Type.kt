package com.wmc.mediacenter.ui.theme

import android.content.res.AssetManager
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

/**
 * WMC-style type: large, light-weight sans-serif for headings — the
 * Segoe UI Light look Windows Media Center used everywhere.
 *
 * S9 — the theme now loads Microsoft's Selawik (the SIL-licensed Segoe UI
 * equivalent) from assets at runtime IF the files are present, falling back
 * to the platform light sans-serif otherwise. No code change needed to
 * enable it — just add the files and rebuild:
 *
 *   1. Download from https://github.com/microsoft/Selawik (Font Files
 *      folder, or the Releases page).
 *   2. Create the folder app/src/main/assets/fonts/
 *   3. Drop in, with exactly these names:
 *        selawik_light.ttf   (Selawik Light)
 *        selawik.ttf         (Selawik Regular)
 *        selawik_semibold.ttf (Selawik Semibold)
 */
@OptIn(ExperimentalTextApi::class)
fun selawikFontFamilyOrNull(assets: AssetManager): FontFamily? = runCatching {
    // Existence check up front — Font() resolves lazily and would otherwise
    // fail at first draw instead of falling back cleanly here.
    listOf("fonts/selawik_light.ttf", "fonts/selawik.ttf", "fonts/selawik_semibold.ttf")
        .forEach { path -> assets.open(path).close() }
    FontFamily(
        Font("fonts/selawik_light.ttf", assets, FontWeight.Light),
        Font("fonts/selawik.ttf", assets, FontWeight.Normal),
        Font("fonts/selawik_semibold.ttf", assets, FontWeight.SemiBold)
    )
}.getOrNull()

/** Builds the WMC typography scale on whichever family resolved above. */
fun wmcTypography(fontFamily: FontFamily): Typography = Typography(
    // S1 — WMC's large light headings carry slightly loose tracking; body/
    // label styles below are left at default.
    displayLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 40.sp,
        letterSpacing = 0.5.sp
    ),
    displayMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 32.sp,
        letterSpacing = 0.5.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 28.sp,
        letterSpacing = 0.5.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 22.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    )
)
