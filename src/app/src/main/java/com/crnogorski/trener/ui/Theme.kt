package com.crnogorski.trener.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Палитра ролями, а не значениями: `ink` — фон, `paper` — текст по нему,
 * `gold` — акцент. В светлой теме роли те же, светлота меняется местами,
 * поэтому экраны о теме не знают вообще ничего и остаются как были.
 */
@Immutable
data class Palette(
    val ink: Color,
    val surface1: Color,
    val surface2: Color,
    val paper: Color,
    val muted: Color,
    val gold: Color,
    val crimson: Color,
    val jade: Color,
    val outline: Color,
    val dark: Boolean
)

private val DarkPalette = Palette(
    ink = Color(0xFF0E0E10),
    surface1 = Color(0xFF17171B),
    surface2 = Color(0xFF212127),
    paper = Color(0xFFEDE9E1),
    muted = Color(0xFF8E8B85),
    gold = Color(0xFFD8B25F),
    crimson = Color(0xFFC2384F),
    jade = Color(0xFF5FA47A),
    outline = Color(0xFF3A3A42),
    dark = true
)

/**
 * Светлая тема — та же бумага, только наоборот: тёплый фон, тёмный текст.
 * Золото и зелень заметно темнее, чем в тёмной теме: исходные оттенки
 * на светлом фоне не читались бы ни текстом, ни рамкой.
 */
private val LightPalette = Palette(
    ink = Color(0xFFF6F2E9),
    surface1 = Color(0xFFFFFDF7),
    surface2 = Color(0xFFE6E0D1),
    paper = Color(0xFF1B1A18),
    muted = Color(0xFF6A665E),
    gold = Color(0xFF8A6A1F),
    crimson = Color(0xFFA32338),
    jade = Color(0xFF2F7A52),
    outline = Color(0xFFD5CEBE),
    dark = false
)

private val LocalPalette = staticCompositionLocalOf { DarkPalette }

// Цвета читаются по имени, как и раньше, но берутся из палитры темы.
// Геттеры композабельные — смена темы перерисовывает экраны сама.

val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
val Surface1: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface1
val Surface2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
val Paper: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.paper
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
val Gold: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.gold
val Crimson: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.crimson

/** Зелёный только для «верно»: золото — акцент интерфейса, им вердикт не отличить от кнопки. */
val Jade: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.jade

private fun schemeOf(p: Palette) = if (p.dark) {
    darkColorScheme(
        primary = p.gold,
        onPrimary = p.ink,
        secondary = p.paper,
        background = p.ink,
        onBackground = p.paper,
        surface = p.surface1,
        onSurface = p.paper,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.muted,
        error = p.crimson,
        outline = p.outline
    )
} else {
    lightColorScheme(
        primary = p.gold,
        onPrimary = p.ink,
        secondary = p.paper,
        background = p.ink,
        onBackground = p.paper,
        surface = p.surface1,
        onSurface = p.paper,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.muted,
        error = p.crimson,
        outline = p.outline
    )
}

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

/**
 * Тема идёт за системной настройкой: ночной режим включён — тёмная, нет — светлая.
 * Своего переключателя нет намеренно, читать при свете и в темноте телефон уже
 * умеет решать сам.
 */
@Composable
fun CrnogorskiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = schemeOf(palette), typography = typography, content = content)
    }
}
