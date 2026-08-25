package com.moji.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val MojiGreen = Color(0xFF1F6B4F)
val MojiLightGreen = Color(0xFFDCEDE5)
val MojiPaper = Color(0xFFF8F7F3)
val MojiInk = Color(0xFF1C211E)
val MojiMuted = Color(0xFF6D746F)

private val LightColors = lightColorScheme(
    primary = MojiGreen,
    onPrimary = Color.White,
    primaryContainer = MojiLightGreen,
    onPrimaryContainer = Color(0xFF153C2E),
    secondary = Color(0xFF52665D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6ECE8),
    onSecondaryContainer = Color(0xFF26342E),
    background = MojiPaper,
    onBackground = MojiInk,
    surface = Color(0xFFFFFEFA),
    onSurface = MojiInk,
    surfaceVariant = Color(0xFFEDEEE9),
    onSurfaceVariant = MojiMuted,
    outline = Color(0xFFB9BEB9),
    outlineVariant = Color(0xFFDFE2DD),
    error = Color(0xFFB33A32),
    errorContainer = Color(0xFFFFDAD5)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED6B0),
    onPrimary = Color(0xFF073824),
    primaryContainer = Color(0xFF174C37),
    onPrimaryContainer = Color(0xFFC6F1D9),
    secondary = Color(0xFFB7CCC0),
    onSecondary = Color(0xFF22362C),
    secondaryContainer = Color(0xFF354A40),
    onSecondaryContainer = Color(0xFFD2E8DC),
    background = Color(0xFF111512),
    onBackground = Color(0xFFE8ECE8),
    surface = Color(0xFF171B18),
    onSurface = Color(0xFFE8ECE8),
    surfaceVariant = Color(0xFF242A26),
    onSurfaceVariant = Color(0xFFB9C1BB),
    outline = Color(0xFF879089),
    outlineVariant = Color(0xFF343B36),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF8C1D18)
)

private val MojiTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

private val MojiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun MojiTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme ?: isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MojiTypography,
        shapes = MojiShapes,
        content = content
    )
}
