package com.crnogorski.trener.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crnogorski.trener.R

/**
 * Заставка после ежедневного задания.
 *
 * Единственный экран приложения, который ничего не спрашивает и ничему не учит:
 * занятие сделано, и это надо увидеть. Постер — рисунок владельца, лежит в
 * `drawable-nodpi/splash_poster.webp` (900 px, 286 КБ) целиком, как есть.
 *
 * **Экран тёмный всегда, в обеих темах**, и цвета здесь заданы на месте, а не
 * взяты из палитры. Это осознанное исключение из правила «цвет берётся из
 * `Theme.kt`»: тема описывает читаемую поверхность, а тут во весь экран горит
 * костёр, и никакой светлой версии у него быть не может. Числа поэтому лежат на
 * своей тёмной подложке, а не на теме.
 *
 * Постер вписан **по ширине** и прижат к верху: обрезать его нельзя ни по
 * высоте (уедет заголовок), ни по бокам (уедут края заголовка). Ниже он уходит
 * в тёмно-угольный градиент — так экран дочитывается до низа и не обрывается
 * краем картинки.
 *
 * Экран рисуется под системными полосами (`enableEdgeToEdge`), поэтому постер
 * опущен на высоту шторки, а панель поднята над навигацией: заголовок «Все идёт
 * по плану» начинается в первых процентах картинки, и без отступа часы и значки
 * встали бы прямо на него. Фон при этом остаётся во весь экран — под шторкой
 * виден костёр, а не серая полоса.
 */
@Composable
fun DailySplash(state: SplashState, onClose: () -> Unit) {
    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(SplashInk)) {
        // Продолжение костра вниз: постер кончается раньше экрана.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0xFF2A1206),
                        0.55f to Color(0xFF160A05),
                        1.0f to SplashInk
                    )
                )
        )
        Image(
            painter = painterResource(R.drawable.splash_poster),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .align(Alignment.TopCenter)
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = SplashPaper)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE60A0606))
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.phrase.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = SplashHot
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.gloss,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Serif,
                color = SplashMuted
            )

            Spacer(Modifier.height(18.dp))
            Text(state.streak.toString(), style = condensed(58.sp), color = SplashGold)
            Text(
                plural(state.streak, "ДЕНЬ ПОДРЯД", "ДНЯ ПОДРЯД", "ДНЕЙ ПОДРЯД"),
                style = condensed(20.sp),
                letterSpacing = 4.sp,
                color = SplashPaper
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat(state.minutes.toString(), "минут")
                Stat(state.answers.toString(), plural(state.answers, "ответ", "ответа", "ответов"))
                Stat("${state.accuracy}%", "верно")
            }

            Spacer(Modifier.height(18.dp))
            Text(
                state.footer,
                style = MaterialTheme.typography.bodyMedium,
                color = SplashMuted,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SplashGold,
                    contentColor = SplashInk
                )
            ) {
                Text("Готово", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Одно число из трёх: величина крупно, подпись под ней. */
@Composable
private fun RowScope.Stat(value: String, caption: String) {
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SplashPaper
        )
        Spacer(Modifier.height(2.dp))
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = SplashMuted)
    }
}

/**
 * Узкий гротеск — системный `sans-serif-condensed`.
 *
 * Своего файла шрифта в приложении нет и заводить его ради одного экрана не
 * стоило: Roboto Condensed есть в Android с незапамятных времён, а по духу он
 * ровно то, что нужно плакату. Если гарнитуры на прошивке не окажется, система
 * молча подставит обычный гротеск — экран от этого не сломается.
 */
@Composable
private fun condensed(size: TextUnit): TextStyle = TextStyle(
    fontFamily = FontFamily(
        Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Bold)
    ),
    fontWeight = FontWeight.Bold,
    fontSize = size
)

/** Русский счёт: 1 день, 2 дня, 5 дней. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    if (n % 100 in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}

// Цвета заставки: тёмные всегда, в обеих темах — см. описание экрана.
private val SplashInk = Color(0xFF0E0E10)
private val SplashPaper = Color(0xFFEDE9E1)
private val SplashMuted = Color(0xFF9A968F)
private val SplashGold = Color(0xFFD8B25F)
private val SplashHot = Color(0xFFFFB02E)
