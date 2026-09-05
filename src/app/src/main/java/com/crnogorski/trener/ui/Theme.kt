package com.crnogorski.trener.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0E0E10)
val Surface1 = Color(0xFF17171B)
val Surface2 = Color(0xFF212127)
val Paper = Color(0xFFEDE9E1)
val Muted = Color(0xFF8E8B85)
val Gold = Color(0xFFD8B25F)
val Crimson = Color(0xFFC2384F)

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    secondary = Paper,
    background = Ink,
    onBackground = Paper,
    surface = Surface1,
    onSurface = Paper,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    error = Crimson,
    outline = Color(0xFF3A3A42)
)

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp
    )
)

@Composable
fun CrnogorskiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
