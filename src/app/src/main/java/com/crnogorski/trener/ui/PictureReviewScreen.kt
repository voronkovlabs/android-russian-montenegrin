package com.crnogorski.trener.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crnogorski.trener.data.Ijekavica
import com.crnogorski.trener.data.PictureMark
import com.crnogorski.trener.data.PictureReview
import com.crnogorski.trener.data.VocabRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Слово, у которого есть картинка, и его толкование для показа. */
private data class Pic(val lemma: String, val gloss: String)

// Подложки под картинкой — фоны тем «Адриатики» (Theme.kt), заданные литералами:
// картинки проверяют на обеих темах независимо от той, что включена сейчас.
private val DARK_BACK = Color(0xFF0B1220)
private val LIGHT_BACK = Color(0xFFF1F6FB)

/**
 * Просмотр сгенерированных картинок к словам (4.36): листаем по одной, ставим
 * «принято» или «отвергнуто», пишем комментарий. Оценки лежат в
 * [PictureReview] и сохраняются сразу при каждом изменении.
 *
 * Список — слова нынешнего круга ([PictureReview.lemmas]), в порядке словаря.
 * Открывается на первом слове без вердикта.
 *
 * «Принять» и «Отвергнуть» стоят сразу под словом, выше комментария (4.37,
 * владелец): на них жмут на каждой картинке, а комментарий пишут изредка, и
 * тянуться к кнопкам через поле в самый низ экрана незачем.
 */
@Composable
fun PictureReviewScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current

    val pics by produceState<List<Pic>?>(null) {
        value = withContext(Dispatchers.IO) {
            val have = PictureReview.lemmas(context).toSet()
            val words = VocabRepository(context).load().words
            val known = words.filter { it.id in have }
                .map { Pic(it.id, it.gloss) }
            // Слов без записи в словаре быть не должно, но если есть — в конец.
            val inDict = known.map { it.lemma }.toSet()
            known + have.filter { it !in inDict }.sorted().map { Pic(it, "") }
        }
    }

    // Отступ под клавиатуру ставим сами: иначе поле комментария внизу уходит под
    // неё — та же беда, что была у замка ключа (жалоба 131).
    Column(Modifier.fillMaxSize().background(Ink).imePadding()) {
        val list = pics
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Загружаю…", color = Muted)
            }
        } else {
            Review(list, onClose)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Review(list: List<Pic>, onClose: () -> Unit) {
    val context = LocalContext.current
    var marks by remember { mutableStateOf(PictureReview.load(context)) }
    var index by remember {
        mutableIntStateOf(
            list.indexOfFirst { marks[it.lemma]?.verdict == null }.coerceAtLeast(0)
        )
    }
    var darkBack by remember { mutableStateOf(true) }

    val pic = list[index]
    val mark = marks[pic.lemma]
    val accepted = marks.values.count { it.verdict == "accept" }
    val rejected = marks.values.count { it.verdict == "reject" }

    fun put(next: PictureMark) {
        marks = marks + (next.lemma to next)
        PictureReview.save(context, marks)
    }

    fun rate(verdict: String) {
        put(
            PictureMark(
                pic.lemma, verdict, mark?.comment.orEmpty(), System.currentTimeMillis()
            )
        )
        if (index < list.lastIndex) index++
    }

    val bitmap = remember(pic.lemma) {
        runCatching {
            context.assets.open("pictures/${pic.lemma}.webp").use { BitmapFactory.decodeStream(it) }
                ?.asImageBitmap()
        }.getOrNull()
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть", tint = Muted)
        }
        Text(
            "${index + 1} из ${list.size}",
            style = MaterialTheme.typography.labelLarge,
            color = Muted,
            modifier = Modifier.weight(1f)
        )
        Text(
            "✓ $accepted  ✗ $rejected",
            style = MaterialTheme.typography.labelLarge,
            color = Muted
        )
    }

    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Свайп по картинке листает (4.38): влево — следующая, вправо —
        // предыдущая, как страницы. Порог 60 dp: случайное касание при
        // прокрутке экрана листать не должно.
        val swipe = with(LocalDensity.current) { 60.dp.toPx() }
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (darkBack) DARK_BACK else LIGHT_BACK)
                .pointerInput(list.size) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            if (dx < -swipe && index < list.lastIndex) index++
                            else if (dx > swipe && index > 0) index--
                        }
                    ) { change, amount ->
                        change.consume()
                        dx += amount
                    }
                }
                .padding(16.dp)
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, modifier = Modifier.size(200.dp))
            } else {
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    Text("нет файла", color = Muted)
                }
            }
        }
        Row {
            TextButton(onClick = { darkBack = true }) {
                Text("Тёмная", color = if (darkBack) Accent else Muted)
            }
            TextButton(onClick = { darkBack = false }) {
                Text("Светлая", color = if (!darkBack) Accent else Muted)
            }
        }

        Text(
            Ijekavica.show(pic.lemma),
            style = MaterialTheme.typography.headlineLarge,
            color = Paper,
            textAlign = TextAlign.Center
        )
        Text(
            pic.gloss,
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))
        when (mark?.verdict) {
            "accept" -> Text("Принято", color = Jade, style = MaterialTheme.typography.titleMedium)
            "reject" -> Text("Отвергнуто", color = Crimson, style = MaterialTheme.typography.titleMedium)
            else -> Spacer(Modifier.height(24.dp))
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { rate("reject") },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(14.dp),
                // Вердикт виден и на кнопках (4.38): выбранная яркая, другая
                // приглушена. Нажать другую — значит поменять вердикт.
                colors = ButtonDefaults.buttonColors(
                    containerColor = Crimson.copy(alpha = if (mark?.verdict == "accept") 0.35f else 1f),
                    contentColor = Ink
                )
            ) { Text(if (mark?.verdict == "reject") "✗ Отвергнуто" else "Отвергнуть", style = MaterialTheme.typography.titleMedium) }
            Button(
                onClick = { rate("accept") },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Jade.copy(alpha = if (mark?.verdict == "reject") 0.35f else 1f),
                    contentColor = Ink
                )
            ) { Text(if (mark?.verdict == "accept") "✓ Принято" else "Принять", style = MaterialTheme.typography.titleMedium) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = mark?.comment.orEmpty(),
            onValueChange = { text ->
                put(
                    PictureMark(
                        pic.lemma, mark?.verdict, text,
                        if (mark?.at ?: 0L > 0L) mark!!.at else System.currentTimeMillis()
                    )
                )
            },
            label = { Text("Комментарий") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
    }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { index-- }, enabled = index > 0) {
                Text("← Назад", color = if (index > 0) Paper else Muted)
            }
            TextButton(onClick = { index++ }, enabled = index < list.lastIndex) {
                Text("Вперёд →", color = if (index < list.lastIndex) Paper else Muted)
            }
        }
    }
}
