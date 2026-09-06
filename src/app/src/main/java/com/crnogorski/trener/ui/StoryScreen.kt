package com.crnogorski.trener.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crnogorski.trener.data.StoryMode
import com.crnogorski.trener.speech.Listener
import kotlinx.coroutines.delay
import com.crnogorski.trener.speech.Speaker

/** После скольких неудач подряд даём пройти дальше, не взяв отрезок. */
private const val ATTEMPTS_BEFORE_SKIP = 3

/**
 * То же для перевода, но считая от показанного варианта.
 *
 * Первые три неудачи там уже потрачены на то, чтобы вспомнить перевод, — после
 * них показан черногорский текст. Дать бросить прямо в этот момент значит
 * лишить смысла саму подсказку: произнести-то её всё равно надо.
 */
private const val ATTEMPTS_BEFORE_SKIP_REVEALED = 5

/**
 * Сколько раз молча переслушать, если движок вообще ничего не разобрал.
 *
 * Это не ошибка чтения, а промежуток между появлением отрезка и первым словом:
 * человек ещё ведёт глазами по строке, а движок уже сдался. Дважды переслушать
 * дешевле, чем возвращать кнопку на ровном месте.
 */
private const val SILENT_RETRIES = 2

/**
 * Пауза перед тем, как начать слушать новый отрезок.
 *
 * Нужна обеим сторонам: движку — чтобы отпустить предыдущий заход, человеку —
 * чтобы довести глаза до новой строки. Без неё распознавание успевало сдаться
 * раньше, чем начиналось чтение.
 */
private const val AUTO_START_DELAY_MS = 500L

/** Сколько элементов списка идёт до первого отрезка: заголовок истории. */
private const val HEADER_ITEMS = 1

/**
 * Сколько места оставляем над читаемой строкой.
 *
 * Прижимать её к самому верху неуютно: пропадает только что прочитанное, и
 * теряется нить. Полоски примерно в одно предложение с переводом хватает,
 * чтобы видеть, откуда пришёл.
 */
private val KEEP_ABOVE = 96.dp

/**
 * История: связный текст, который проходят вслух по отрезкам.
 *
 * Два занятия на одном тексте, [StoryMode].
 *
 * **Чтение вслух.** Пока всё получается, экран ничего не просит нажимать:
 * отрезок появился — распознавание уже слушает, прочитал — перевод открылся,
 * и следующий отрезок снова слушает. Должно ощущаться обычным чтением вслух,
 * а не выполнением заданий по одному. Кнопка возвращается ровно тогда, когда
 * что-то пошло не так: не разобрали, не дали доступ к микрофону или человек
 * сам прервал прослушиванием образца.
 *
 * **Перевод вслух.** Показан русский, сказать надо по-черногорски. Здесь
 * кнопка есть всегда и слушать само не начинает: перевод сперва надо
 * придумать, а самослушающий экран торопил бы. После трёх неудач черногорский
 * вариант открывается, и дальше отрезок работает как чтение — произнести его
 * всё равно надо.
 *
 * После нескольких неудач подряд отрезок можно оставить — движок распознавания
 * ошибается сам по себе, и упереться в него навсегда нельзя.
 */
@Composable
fun StoryScreen(
    state: StoryState,
    speaker: Speaker,
    onSubmit: (String) -> Unit,
    onSkipChunk: () -> Unit,
    onRestart: () -> Unit,
    onNote: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    var listening by remember(state.id) { mutableStateOf(false) }
    var status by remember(state.id, state.index) { mutableStateOf("") }

    // Человек прервал слушание сам — послушал образец. Пока не нажмёт «Читать
    // вслух», запись не возобновляем: иначе она подхватит голос синтезатора.
    var paused by remember(state.id, state.index) { mutableStateOf(false) }

    // Слушание сорвалось не по вине чтения: движок отвалился, нет разрешения,
    // или он так и не разобрал ни слова. Дальше — только по нажатию.
    var stalled by remember(state.id, state.index) { mutableStateOf(false) }
    var silent by remember(state.id, state.index) { mutableStateOf(0) }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    BackHandler { onClose() }

    DisposableEffect(Unit) {
        onDispose { listener.stop() }
    }

    val reading = state.mode == StoryMode.Read

    fun start() {
        listening = true
        status = ""
        listener.listen(
            onResult = { heard ->
                // При чтении listening не гасим: при удаче сразу поедет следующий
                // отрезок, и мигание кнопкой между ними ни к чему. При переводе
                // гасим — дальше ждать вердикта модели, а это уже не «слушаю».
                if (!reading) listening = false
                onSubmit(heard)
            },
            onError = { message ->
                listening = false
                stalled = true
                status = message
            },
            onSilence = {
                // При чтении ничего не услышать — обычно значит «не успели
                // начать», и переслушать дешевле, чем возвращать кнопку. При
                // переводе кнопку нажали сознательно: молчание тут настоящее.
                if (reading && silent < SILENT_RETRIES) {
                    silent++
                    start()
                } else {
                    listening = false
                    stalled = true
                    status = if (reading) {
                        "Ничего не расслышал. Нажми, когда будешь готов."
                    } else {
                        "Ничего не расслышал. Нажми и скажи ещё раз."
                    }
                }
            }
        )
    }

    // Разрешение только выставляет флаг: слушать начнёт эффект ниже. Позвать
    // start() ещё и отсюда значило бы запустить распознавание дважды подряд.
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (!ok) status = "Без доступа к микрофону историю не прочитать"
    }

    fun record() {
        if (!granted) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        paused = false
        stalled = false
        silent = 0
        start()
    }

    fun sample(slow: Boolean) {
        // Слушать и говорить одновременно нельзя: распознавание примет за чтение
        // голос синтезатора.
        listener.cancel()
        listening = false
        paused = true
        speaker.speak(state.chunks.getOrNull(state.index)?.sr.orEmpty(), slow = slow)
    }

    val done = state.index >= state.chunks.size
    // Слушаем сами, только пока всё идёт гладко — и только при чтении: перевод
    // надо сперва придумать, и отсчёт тишины начался бы раньше первого слова.
    val auto = reading && granted && !paused && !stalled && state.attempts == 0 && !done

    // Ключи без paused и stalled: их снимает нажатие кнопки, которое и так зовёт
    // start(). Будь они ключами, эффект запустил бы распознавание вторым.
    LaunchedEffect(state.id, state.index, granted) {
        if (reading && granted && !paused && !stalled && state.attempts == 0 && !done) {
            delay(AUTO_START_DELAY_MS)
            start()
        }
    }

    // Неудачу экран узнаёт по счётчику попыток: сам onResult не знает, засчитали
    // прочитанное или нет — это решает AppViewModel. Без этого кнопка осталась бы
    // навсегда в состоянии «Слушаю…».
    LaunchedEffect(state.attempts) {
        if (state.attempts > 0) listening = false
    }

    // Читаемая строка не должна уезжать за нижний край: прочитанное копится
    // сверху и выталкивает её вниз. Подводим её к верху окна — под ней как раз
    // помещаются кнопки и сообщения, а над ней остаётся пройденное.
    val listState = rememberLazyListState()
    val keepAbove = with(LocalDensity.current) { KEEP_ABOVE.roundToPx() }
    LaunchedEffect(state.id, state.index, state.attempts) {
        // Отрицательное смещение оставляет полоску над отрезком, а не прячет её.
        listState.animateScrollToItem(
            state.index.coerceAtMost(state.chunks.size) + HEADER_ITEMS,
            -keepAbove
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Закрыть историю",
                        tint = Muted
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${state.index.coerceAtMost(state.chunks.size)} / ${state.chunks.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted
                )
                ComplaintButton(onSave = onNote)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (state.chunks.isEmpty()) 0f
                    else state.index.toFloat() / state.chunks.size
                },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Accent,
                trackColor = Surface2
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)
        ) {
            item {
                Text(
                    state.mode.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent
                )
                Spacer(Modifier.height(6.dp))
                Text(state.title, style = MaterialTheme.typography.displaySmall, color = Paper)
                Spacer(Modifier.height(24.dp))
            }

            itemsIndexed(state.chunks) { i, chunk ->
                when {
                    i < state.index -> {
                        // Пройденное: текст приглушён, перевод под ним — он и есть награда.
                        GlossedText(
                            chunk.sr, state.glossaryMe,
                            MaterialTheme.typography.bodyLarge, Jade
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            chunk.ru,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted
                        )
                        Spacer(Modifier.height(14.dp))
                    }

                    i == state.index -> {
                        if (reading) {
                            GlossedText(
                                chunk.sr, state.glossaryMe,
                                MaterialTheme.typography.headlineSmall, Paper
                            )
                            Spacer(Modifier.height(16.dp))
                            ReadingControls(
                                listening = listening,
                                waiting = auto,
                                status = status,
                                heard = state.heard,
                                note = state.note,
                                attempts = state.attempts,
                                onSample = ::sample,
                                onRecord = ::record,
                                onSkip = onSkipChunk
                            )
                        } else {
                            // Подсказки по словам тут не нужны: русский и так
                            // родной, а черногорский — это и есть ответ.
                            Text(
                                chunk.ru,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Paper
                            )
                            Spacer(Modifier.height(16.dp))
                            TranslateControls(
                                listening = listening,
                                checking = state.checking,
                                revealed = state.revealed,
                                reference = chunk.sr,
                                glossary = state.glossaryMe,
                                status = status,
                                heard = state.heard,
                                note = state.note,
                                attempts = state.attempts,
                                onSample = ::sample,
                                onRecord = ::record,
                                onSkip = onSkipChunk
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                    }

                    else -> {
                        // Впереди показываем ту сторону, с которой работают:
                        // черногорский текст при переводе был бы ответом.
                        Text(
                            if (reading) chunk.sr else chunk.ru,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Muted.copy(alpha = 0.45f)
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            // Хвост есть всегда, даже пустой: иначе прокрутка к последнему
            // отрезку упиралась бы в конец списка и не доводила его до верха.
            item {
                if (done) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (reading) "ПРОЧИТАНО" else "ПЕРЕВЕДЕНО",
                        style = MaterialTheme.typography.labelSmall,
                        color = Jade
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "История пройдена до конца. К ней можно вернуться в любой момент — " +
                            "на повторение она не встаёт.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Пройти заново", onClick = onRestart)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onClose) { Text("К списку историй", color = Muted) }
                } else {
                    // Не fillMaxHeight: внутри списка высота не ограничена, и он
                    // молча схлопнулся бы в ноль. Нужен размер окна, а не родителя.
                    Spacer(Modifier.fillParentMaxHeight())
                }
            }
        }
    }
}

/**
 * Управление текущим отрезком при чтении вслух.
 *
 * В гладком случае кнопки нет вовсе — только строка «Слушаю»: нажимать нечего,
 * просто читай. Кнопка появляется, когда слушание сорвалось или его прервали.
 */
@Composable
private fun ReadingControls(
    listening: Boolean,
    waiting: Boolean,
    status: String,
    heard: String,
    note: String,
    attempts: Int,
    onSample: (Boolean) -> Unit,
    onRecord: () -> Unit,
    onSkip: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallAction("Послушать") { onSample(false) }
        SmallAction("Медленнее") { onSample(true) }
    }
    Spacer(Modifier.height(16.dp))

    if (waiting) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.height(16.dp).width(16.dp),
                strokeWidth = 2.dp,
                color = Accent
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (listening) "Слушаю — читай вслух" else "Включаю микрофон…",
                style = MaterialTheme.typography.bodyMedium,
                color = Accent
            )
        }
    } else {
        PrimaryButton(
            if (listening) "Слушаю…" else "Читать вслух",
            enabled = !listening,
            onClick = onRecord
        )
    }

    if (heard.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Услышано: $heard",
            style = MaterialTheme.typography.bodyMedium,
            color = Paper
        )
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(note, style = MaterialTheme.typography.bodyMedium, color = Crimson)
    }
    if (status.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }

    if (attempts >= ATTEMPTS_BEFORE_SKIP) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Не выходит — дальше", color = Muted)
        }
    }
}

/**
 * Управление текущим отрезком при переводе вслух.
 *
 * Кнопка здесь есть всегда, в том числе когда всё получается: перевод надо
 * сперва придумать, и экран, слушающий сам, торопил бы. Образец звучит только
 * после того, как вариант показан, — до этого он и был бы ответом.
 */
@Composable
private fun TranslateControls(
    listening: Boolean,
    checking: Boolean,
    revealed: Boolean,
    reference: String,
    glossary: Map<String, String>,
    status: String,
    heard: String,
    note: String,
    attempts: Int,
    onSample: (Boolean) -> Unit,
    onRecord: () -> Unit,
    onSkip: () -> Unit
) {
    PrimaryButton(
        when {
            checking -> "Проверяю…"
            listening -> "Слушаю…"
            revealed -> "Прочитать вслух"
            else -> "Сказать по-черногорски"
        },
        enabled = !listening && !checking,
        onClick = onRecord
    )

    if (revealed) {
        Spacer(Modifier.height(18.dp))
        Text("СКАЖИ ТАК", style = MaterialTheme.typography.labelSmall, color = Accent)
        Spacer(Modifier.height(6.dp))
        GlossedText(reference, glossary, MaterialTheme.typography.bodyLarge, Paper)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("Послушать") { onSample(false) }
            SmallAction("Медленнее") { onSample(true) }
        }
    }

    if (heard.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Услышано: $heard",
            style = MaterialTheme.typography.bodyMedium,
            color = Paper
        )
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(note, style = MaterialTheme.typography.bodyMedium, color = Crimson)
    }
    if (status.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }

    if (revealed && attempts >= ATTEMPTS_BEFORE_SKIP_REVEALED) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Не выходит — дальше", color = Muted)
        }
    }
}
