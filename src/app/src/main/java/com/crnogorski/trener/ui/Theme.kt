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
 * `accent` — акцент. Экраны о теме не знают вообще ничего: меняются значения,
 * имена ролей остаются.
 *
 * Тёмная тема — тёплая, золото по чернилам; светлая — голубая, синий по льду.
 * Оттенок роли меняется вместе с темой, и код это не волнует.
 */
@Immutable
data class Palette(
    val ink: Color,
    val surface1: Color,
    val surface2: Color,
    val paper: Color,
    val muted: Color,
    val accent: Color,
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
    accent = Color(0xFFD8B25F),
    // Прежний 0xFFC2384F давал контраст 3,4 к 1 на карточке — для подписи
    // в 11 пунктов этого мало, и «ОШИБКА» читалась хуже всего остального.
    crimson = Color(0xFFDB5A70),
    jade = Color(0xFF5FA47A),
    outline = Color(0xFF3A3A42),
    dark = true
)

/**
 * Светлая тема — голубая: холодный фон, белые карточки, синий акцент.
 *
 * Акцент здесь синий, а в тёмной теме золотой, и это не оплошность: `accent` —
 * роль, а не оттенок. Ровно поэтому цвет и назван по роли: `Accent`, а не
 * `Gold`, — читающий код не должен гадать, какого он цвета в этой теме.
 *
 * Акцент, зелень и красный взяты заметно темнее, чем в тёмной теме: на светлом
 * фоне исходные оттенки не читались бы ни текстом, ни рамкой. Синий выбран так,
 * чтобы работать и текстом по фону, и подложкой кнопки со светлым текстом.
 */
private val LightPalette = Palette(
    ink = Color(0xFFEDF3F9),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFD6E3F0),
    paper = Color(0xFF10222F),
    muted = Color(0xFF56718A),
    accent = Color(0xFF1F6FA8),
    crimson = Color(0xFFB02A3C),
    jade = Color(0xFF1E7A5A),
    outline = Color(0xFFC5D6E5),
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
val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val Crimson: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.crimson

/** Зелёный только для «верно»: золото — акцент интерфейса, им вердикт не отличить от кнопки. */
val Jade: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.jade

private fun schemeOf(p: Palette) = if (p.dark) {
    darkColorScheme(
        primary = p.accent,
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
        primary = p.accent,
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
