package com.crnogorski.trener.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.crnogorski.trener.R

/**
 * Фотография Пераста за экраном.
 *
 * Снимок владельца, по одному на тему: тёмный для ночной, выбеленный для
 * дневной. Не украшение ради украшения — приложение про место, где человек
 * живёт, и залив Котора отвечает на вопрос «зачем я это учу» лучше любой
 * надписи.
 *
 * **Вуаль обязательна и устроена неравномерно.** Сверху фотография почти
 * открыта — там заголовок и много неба, читать не мешает ничто. Книзу вуаль
 * цвета фона густеет: внизу живут кнопки, статусы и мелкий текст, и фактура
 * под ними стоила бы читаемости. Порог 4,5:1 проект держит везде, и подложка
 * не повод делать исключение: под заголовком меряно 13,6:1 в тёмной теме и
 * 10,4:1 в светлой.
 *
 * Обе картинки лежат в `drawable-nodpi` как WebP шириной 1080 — 38 и 56 КБ на
 * весь экран. `nodpi`, потому что плотность тут ни при чём: снимок один, и
 * растягивается он по ширине устройства, а не выбирается по корзине.
 *
 * Подложка есть **не на всех экранах**: только там, где содержимое разложено
 * карточками, — главный и отчёт. У задания и у истории во весь экран длинный
 * текст, который читают вслух, и фотография за ним мешала бы ровно тому, ради
 * чего приложение существует.
 */
@Composable
fun Backdrop(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(
                if (isDark) R.drawable.bg_kotor_dark else R.drawable.bg_kotor_light
            ),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Вуаль: вверху прозрачная, книзу — сплошной фон темы.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Ink.copy(alpha = 0.10f),
                        0.25f to Ink.copy(alpha = 0.18f),
                        0.60f to Ink.copy(alpha = 0.70f),
                        1.00f to Ink.copy(alpha = 0.96f)
                    )
                )
        )
        content()
    }
}
