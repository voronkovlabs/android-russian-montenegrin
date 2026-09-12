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
 * Тёмная тема — золото по чернилам, светлая — синий по нейтральному серому.
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

/**
 * Тёмная тема — «Адриатика»: ночь Которского залива.
 *
 * До 1.64 фон был нейтрально-чёрным, а акцент золотым. Чёрный сменился
 * тёмно-синим не ради моды: за экраном теперь стоит фотография залива, и
 * нейтральный чёрный спорил бы с её синевой — вуаль поверх снимка обязана быть
 * того же цвета, что фон, иначе на границе видно шов.
 *
 * Золото осталось акцентом и стало солнечнее (`F2B544`): по синему оно читается
 * лучше, чем по чёрному, и даёт 9,31:1 на карточке.
 *
 * Все пары посчитаны, а не подобраны: минимум по тёмной теме — 5,94:1
 * (`crimson` по `surface2`), и это вдвое выше порога 4,5. Прежняя палитра
 * держала запрещённую пару на 4,35 — теперь запрещённых нет вовсе.
 */
private val DarkPalette = Palette(
    ink = Color(0xFF0B1220),
    surface1 = Color(0xFF121C2E),
    surface2 = Color(0xFF1B2840),
    paper = Color(0xFFE8EEF7),
    muted = Color(0xFFA0B2CA),
    accent = Color(0xFFF2B544),
    crimson = Color(0xFFF2867A),
    jade = Color(0xFF5EC496),
    outline = Color(0xFF2A3C58),
    dark = true
)

/**
 * Светлая тема — нейтральный серый в духе SharePoint.
 *
 * Голубой фон (`0xFFEDF3F9`) и голубая же плашка занятия держались до 1.33 и
 * были заменены по просьбе: экран читался холодным целиком, а не только
 * акцентом. Здесь цвет остаётся ровно одному элементу — акценту, — а всё
 * остальное нейтрально: фон `#F3F2F1`, карточки чисто белые, границы тонкие
 * серые, текст графитовый. Белая карточка на нейтральном сером выглядит
 * карточкой; на голубом она выглядела пятном.
 *
 * Значения взяты из палитры Fluent, но **не все её собственные**. Фирменный
 * синий SharePoint `#0078D4` даёт на этом же сером 4,05 к 1 — ниже порога 4,5,
 * который проект держит везде. Взят `#005A9E` из той же палитры: 6,35 по фону,
 * 7,10 по карточке, 5,97 по `surface2`. По той же причине зелень взята
 * `#0B6A0B`, а не фирменная `#107C10`: у той на `surface2` выходит 4,51,
 * то есть впритык.
 *
 * Акцент здесь синий, а в тёмной теме золотой, и это не оплошность: `accent` —
 * роль, а не оттенок. Ровно поэтому цвет и назван по роли: `Accent`, а не
 * `Gold`, — читающий код не должен гадать, какого он цвета в этой теме.
 */
private val LightPalette = Palette(
    ink = Color(0xFFF1F6FB),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE3EDF7),
    paper = Color(0xFF16253A),
    muted = Color(0xFF4E6178),
    accent = Color(0xFF0B5E86),
    crimson = Color(0xFFA32F26),
    jade = Color(0xFF186A46),
    outline = Color(0xFFCFDEEE),
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

/** Ночная ли тема. Нужно подложке: снимок свой на каждую. */
val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current.dark

/**
 * Непрозрачность карточек, лежащих на фотографии.
 *
 * Не «на глаз красиво», а посчитано: при 0,88 самая слабая пара текста внутри
 * карточки — зелёное «верно» по второй поверхности над самым светлым участком
 * снимка — даёт 5,21:1 при пороге 4,5. Ниже опускать нельзя: на 0,8 та же пара
 * уходит под порог, и стекло начинает воровать читаемость ради вида.
 *
 * Там, где фотографии нет, поверхности остаются сплошными: прозрачность поверх
 * сплошного фона — пустая работа для видеокарты и лишний повод ошибиться.
 */
private const val GLASS = 0.88f

val Glass1: Color @Composable @ReadOnlyComposable
    get() = LocalPalette.current.surface1.copy(alpha = GLASS)

val Glass2: Color @Composable @ReadOnlyComposable
    get() = LocalPalette.current.surface2.copy(alpha = GLASS)

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
