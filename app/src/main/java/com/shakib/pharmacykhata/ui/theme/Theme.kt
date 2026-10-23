package com.shakib.pharmacykhata.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PharmacyGreen = Color(0xFF0B6E4F)
val PharmacyGreenDark = Color(0xFF084F39)
val AccentGold = Color(0xFFF2B705)

// System font family: Android devices sold in Bangladesh ship with full
// Bengali glyph coverage, so UI text renders correctly without bundling a
// font binary. (A bundled Anek Bangla TTF can be re-added for release
// builds pushed via a full git client.)
val AnekBangla = FontFamily.Default

private fun appTypography() = Typography(
    headlineMedium = TextStyle(fontFamily = AnekBangla, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = AnekBangla, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = AnekBangla, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = AnekBangla, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = AnekBangla, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = AnekBangla, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

private val LightColors = lightColorScheme(
    primary = PharmacyGreen, onPrimary = Color.White,
    secondary = AccentGold, background = Color(0xFFF7F9F7), surface = Color.White
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DBE8B), onPrimary = Color(0xFF04231A),
    secondary = AccentGold, background = Color(0xFF101512), surface = Color(0xFF1A201C)
)

@Composable
fun PharmacyKhataProTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = appTypography(),
        content = content
    )
}
