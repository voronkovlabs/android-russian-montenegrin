package com.crnogorski.trener.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
 * **Вуаль устроена неравномерно, и вверху её нет вовсе.** Первая попытка
 * (1.64) начиналась с десяти процентов и к низу доходила до 96 — вместе с уже
 * притемнёнными исходниками это съело снимок целиком, и владелец сказал прямо:
 * «картинка почти не видна». Теперь сверху чисто, к середине двенадцать
 * процентов, книзу 88: внизу живут кнопки, статусы и мелкий текст, и фактура
 * под ними стоила бы читаемости.
 *
 * Сами снимки тоже пришлось вернуть к жизни: присланные файлы были заранее
 * притемнены под текст, и на фоне темы почти не отличались от заливки. Им
 * поднят контраст и насыщенность (`research`-скрипта тут нет, правка разовая),
 * после чего заголовок держит 17,5:1 в тёмной теме и 10,7:1 в светлой — то
 * есть читаемость не пострадала, а выросла.
 *
 * Обе картинки лежат в `drawable-nodpi` как WebP шириной 1080 — 87 и 98 КБ на
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
        // Катина сборка: вместо снимка Пераста — Катина акварель (море, остров,
        // дрок на берегу). Файл один, светлый; для тёмной темы он притемняется
        // здесь же, матрицей, а не вторым файлом: так ночная версия всегда
        // совпадает с дневной, и прислать новую акварель — значит заменить
        // одну картинку.
        Image(
            painter = painterResource(R.drawable.bg_katya),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Акварель шире экрана телефона и обрезается по бокам; по центру
            // остаётся остров, ради которого она и выбрана.
            alignment = Alignment.Center,
            colorFilter = if (isDark) DARKEN else null,
            modifier = Modifier.fillMaxSize()
        )
        // Вуаль: вверху прозрачная, книзу — сплошной фон темы.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Ink.copy(alpha = 0.00f),
                        0.45f to Ink.copy(alpha = 0.12f),
                        0.75f to Ink.copy(alpha = 0.55f),
                        1.00f to Ink.copy(alpha = 0.88f)
                    )
                )
        )
        content()
    }
}

/**
 * Ночная версия акварели: яркость примерно на треть, синий чуть сильнее —
 * чтобы тёмная тема («ночь над заливом») не стала серой.
 */
private val DARKEN = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToScale(0.32f, 0.34f, 0.42f, 1f) }
)
