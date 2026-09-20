package com.crnogorski.trener.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crnogorski.trener.data.StoryMode
import com.crnogorski.trener.speech.Listener
import kotlinx.coroutines.delay
import com.crnogorski.trener.speech.Speaker
import com.crnogorski.trener.data.VoiceRecorder
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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
 * Отступ у ваших реплик в диалоге.
 *
 * Иконка и подпись говорят, кто говорит, но читаются они по одной строке за
 * раз. Ступенька видна всем куском сразу — по ней ясно, что идёт разговор, ещё
 * до того, как прочитана хоть одна реплика.
 */
private val BUBBLE_CORNER = 14.dp

/**
 * Сколько места пузырь оставляет противоположному краю.
 *
 * Не украшение: пузырь во всю ширину не читается как реплика — сторона видна
 * только по свободному полю рядом. Восьмой доли хватает, чтобы поле было
 * заметно, и она не режет строку сверх нужного: черногорский текст тут крупный,
 * и каждый отнятый процент ширины — лишний перенос.
 */
private const val BUBBLE_MARGIN = 0.12f

/**
 * Сколько места оставляем над читаемой строкой.
 *
 * Прижимать её к самому верху неуютно: пропадает только что прочитанное, и
 * теряется нить. Полоски примерно в одно предложение с переводом хватает,
 * чтобы видеть, откуда пришёл.
 */
private val KEEP_ABOVE = 96.dp

/**
 * Насколько сторож экрана ждёт дольше сторожа распознавания.
 *
 * Не меньше секунды: если оба сработают разом, человек получит два сообщения об
 * одном и том же, а виноватым окажется тот, кто написал второе.
 */
private const val LISTEN_GUARD_MS = 4000L

/**
 * История: связный текст, который проходят вслух по отрезкам.
 *
 * Три занятия на одном тексте, [StoryMode].
 *
 * **Чтение вслух.** Пока всё получается, экран ничего не просит нажимать:
 * отрезок появился — распознавание уже слушает, прочитал — перевод открылся,
 * и следующий отрезок снова слушает. Должно ощущаться обычным чтением вслух,
 * а не выполнением заданий по одному. Кнопка возвращается ровно тогда, когда
 * что-то пошло не так: не разобрали, не дали доступ к микрофону или человек
 * сам прервал прослушиванием образца.
 *
 * **На слух.** Текст закрыт прочерками по числу букв. Отрезок звучит сам, и
 * повторить его надо с голоса — распознавание включается сразу после
 * последнего слова синтезатора (одновременно нельзя: запись подхватила бы
 * его же).
 *
 * **Перевод вслух.** Показан русский, сказать надо по-черногорски. Здесь
 * кнопка есть всегда и слушать само не начинает: перевод сперва надо
 * придумать, а самослушающий экран торопил бы.
 *
 * В двух последних после трёх неудач черногорский текст **показывается**, и
 * отрезок доигрывается как чтение вслух: произнести его всё равно надо, а
 * упереться в него навсегда нельзя — движок распознавания ошибается сам по
 * себе. Отсюда единственная развилка ниже: `hearing` и `translating` значат
 * «текст ещё закрыт», всё остальное — обычное чтение.
 */
@Composable
fun StoryScreen(
    state: StoryState,
    speaker: Speaker,
    onSubmit: (String) -> Unit,
    onSkipChunk: () -> Unit,
    onContinue: () -> Unit,
    onNativeNext: () -> Unit,
    onReveal: () -> Unit,
    onRestart: () -> Unit,
    onNote: (String) -> Unit,
    onIdea: (String, String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    var listening by remember(state.id) { mutableStateOf(false) }

    // Режим носителя. Записыватель живёт столько же, сколько экран, — как и
    // распознаватель строкой выше, и по той же причине: он держит системный
    // ресурс, и отпускать его надо ровно при уходе.
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember(state.id) { mutableStateOf(false) }
    var recorded by remember(state.id, state.index) { mutableStateOf(false) }
    var saveFailed by remember(state.id, state.index) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Заход начинается со входом в историю, а не с первой записи: имя папки —
    // это время, когда человек сел читать.
    LaunchedEffect(state.id, state.run, state.native) {
        if (state.native) recorder.startPass(state.id)
    }
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
        onDispose {
            // Начатую и не остановленную запись бросаем: обрывок фразы,
            // записанный по дороге с экрана, архиву не нужен.
            recorder.cancel()
            listener.stop()
            // Замолчать обязательно: иначе назначенное на конец фразы включит
            // микрофон уже на другом экране.
            speaker.silence()
        }
    }

    // Отрезок звучит прямо сейчас — микрофон ждёт последнего слова.
    var speaking by remember(state.id, state.index) { mutableStateOf(false) }

    val reading = state.mode == StoryMode.Read

    // Говорит собеседник: отвечать не надо, надо разобрать на слух.
    val theirTurn = state.theirTurn

    // Перевод чужой реплики открывают по нажатию — как перевод в обычной
    // истории: он тут награда за понимание, а не подстрочник.
    var translated by remember(state.id, state.index) { mutableStateOf(false) }

    // Текст ещё закрыт: на слух — прочерками, при переводе — вовсе не показан.
    val hearing = state.mode == StoryMode.Listen && !state.revealed
    val translating = state.mode == StoryMode.Translate && !state.revealed && !theirTurn

    fun start() {
        listening = true
        status = ""
        listener.listen(
            onResult = { heard ->
                // Когда проверка мгновенная, listening не гасим: при удаче сразу
                // поедет следующий отрезок, и мигание кнопкой между ними ни к
                // чему. При переводе гасим — дальше ждать вердикта модели,
                // а это уже не «слушаю».
                if (translating) listening = false
                onSubmit(heard)
            },
            onError = { message ->
                listening = false
                stalled = true
                status = message
            },
            onSilence = {
                // Когда текст перед глазами, ничего не услышать — обычно значит
                // «не успели начать», и переслушать дешевле, чем возвращать
                // кнопку. При переводе кнопку нажали сознательно: молчание там
                // настоящее.
                if (!translating && silent < SILENT_RETRIES) {
                    silent++
                    start()
                } else {
                    listening = false
                    stalled = true
                    status = if (translating) {
                        "Ничего не расслышал. Нажми и скажи ещё раз."
                    } else {
                        "Ничего не расслышал. Нажми, когда будешь готов."
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

    /**
     * Произнести отрезок и сразу за этим начать слушать.
     *
     * Ровно это и есть упражнение «на слух»: одновременно говорить и слушать
     * нельзя — запись подхватила бы голос синтезатора, — поэтому микрофон
     * включается по концу фразы, а не по таймеру.
     */
    fun playThenListen(slow: Boolean = false) {
        listener.cancel()
        listening = false
        paused = false
        stalled = false
        silent = 0
        speaking = true
        status = ""
        speaker.speak(state.target, slow = slow, low = state.current?.theirs == true) {
            speaking = false
            start()
        }
    }

    fun record() {
        if (!granted) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (hearing) {
            playThenListen()
            return
        }
        paused = false
        stalled = false
        silent = 0
        start()
    }

    fun sample(slow: Boolean) {
        // Слушать и говорить одновременно нельзя: распознавание примет за чтение
        // голос синтезатора. Когда текст открыт, образец — это просто образец, и
        // после него ждём нажатия; на слух он и есть задание, поэтому там за ним
        // сразу идёт микрофон.
        if (hearing) {
            playThenListen(slow)
            return
        }
        listener.cancel()
        listening = false
        paused = true
        speaker.speak(state.target, slow = slow, low = state.current?.theirs == true)
    }

    /**
     * Произнести одно нажатое слово.
     *
     * Работает как [sample], только короче, и по той же причине **сначала
     * глушит распознавание**: слушать и говорить одновременно нельзя, иначе
     * микрофон примет синтезатор за чтение. Без этого нажатие на слово посреди
     * чтения портило бы собственную попытку.
     *
     * После слова экран ждёт нажатия «Читать вслух» — как и после образца.
     */
    fun speakWord(word: String) {
        listener.cancel()
        listening = false
        paused = true
        speaker.speak(word, low = state.current?.theirs == true)
    }

    /**
     * Проиграть чужую реплику.
     *
     * Отдельно от [sample], потому что тут нечего прерывать и незачем ставить
     * `paused`: микрофон в чужой ход не включается вовсе.
     */
    fun playTheirs(slow: Boolean = false) {
        speaking = true
        speaker.speak(state.target, slow = slow, low = true) { speaking = false }
    }

    val done = state.index >= state.chunks.size
    // Само идёт, только пока всё гладко, и не при переводе: его надо сперва
    // придумать, и отсчёт тишины начался бы раньше первого слова.
    // `passed` гасит автозапуск целиком: отрезок сдан, на экране текст и
    // перевод, и человек читает их столько, сколько хочет. Без этой оговорки
    // экран посчитал бы происходящее обычным чтением вслух (текст-то открыт) и
    // немедленно включил бы микрофон.
    // В режиме носителя не запускается ничего: ни распознавание, ни
    // синтезатор. Проверять нечего, а робот, читающий ту же фразу вслед за
    // человеком, — просто помеха.
    val auto = (reading || hearing) && !theirTurn && granted && !paused && !stalled &&
        state.attempts == 0 && !done && !state.passed && !state.native

    // Ключи без paused и stalled: их снимает нажатие кнопки, которое и так зовёт
    // start(). Будь они ключами, эффект запустил бы распознавание вторым.
    LaunchedEffect(state.id, state.index, granted) {
        if (done) return@LaunchedEffect
        // Режим носителя сам не делает ничего. Одного `auto` здесь
        // мало: ветка `reading` ниже смотрит на режим истории, а он у нас
        // по-прежнему «чтение вслух», и распознавание запустилось бы
        // прямо поверх записи.
        if (state.native) return@LaunchedEffect
        // Чужая реплика звучит сама и без разрешения на микрофон: слушать её
        // можно и не отвечая.
        if (theirTurn) {
            delay(AUTO_START_DELAY_MS)
            playTheirs()
            return@LaunchedEffect
        }
        if (!granted || paused || stalled || state.attempts > 0) return@LaunchedEffect
        when {
            reading -> {
                delay(AUTO_START_DELAY_MS)
                start()
            }
            // Пауза и тут не лишняя: движок TTS тоже не отвечает мгновенно,
            // а обрывать собственную фразу на первом слове некрасиво.
            hearing -> {
                delay(AUTO_START_DELAY_MS)
                playThenListen()
            }
            else -> Unit
        }
    }

    // Неудачу экран узнаёт по счётчику попыток: сам onResult не знает, засчитали
    // прочитанное или нет — это решает AppViewModel. Без этого кнопка осталась бы
    // навсегда в состоянии «Слушаю…».
    LaunchedEffect(state.attempts) {
        if (state.attempts > 0) listening = false
    }

    // А это вторая половина того же: при удаче `onResult` флаг намеренно не
    // гасит — в чтении сразу поедет следующий отрезок, и мигать кнопкой между
    // ними незачем. Расчёт был на то, что за удачей **всегда** идёт новый
    // заход, и он неверен: при переводе экран сам не слушает, в конце истории
    // слушать больше нечего, а перед чужой репликой микрофон не включается
    // вовсе. Во всех трёх случаях кнопка оставалась в «Слушаю…» навсегда —
    // найдено владельцем на устройстве 12.09.2026.
    //
    // Поэтому: сменился отрезок и автозапуска не будет — гасим.
    LaunchedEffect(state.id, state.index, auto) {
        if (!auto) listening = false
    }

    // Последний рубеж: что бы ни случилось ниже, экран не имеет права остаться
    // в «Слушаю…». Движок может не ответить, ответ может прийти в закрытый
    // заход, вердикт может не дойти — человеку во всех случаях нужна живая
    // кнопка, а не мёртвый экран. Ждём дольше сторожа распознавания, чтобы не
    // перебивать его сообщение своим.
    LaunchedEffect(listening, state.index) {
        if (!listening) return@LaunchedEffect
        delay(Listener.WATCHDOG_MS + LISTEN_GUARD_MS)
        if (listening) {
            listening = false
            stalled = true
            status = "Распознавание молчит. Нажми ещё раз."
        }
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
                IdeaButton(onSave = onIdea)
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
                    // В режиме носителя всё, что видит человек, — по-черногорски
                    // (идея 101). Он пришёл читать, а не разбирать чужой язык.
                    if (state.native) "ČITANJE NAGLAS" else state.mode.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent
                )
                Spacer(Modifier.height(6.dp))
                Text(state.title, style = MaterialTheme.typography.displaySmall, color = Paper)
                Spacer(Modifier.height(24.dp))
            }

            itemsIndexed(state.chunks) { i, chunk ->
              // Роли размечены только у диалога; у обычной истории пузырей нет
              // вовсе — там говорит один человек, и делить нечего.
              val dialog = chunk.who.isNotBlank()
              Column(Modifier.fillMaxWidth()) {
                if (dialog && chunk.theirs) {
                    SpeakerName(state.speaker)
                }
                when {
                    i < state.index -> {
                        // Пройденное: текст приглушён, перевод под ним — он и есть награда.
                        //
                        // В режиме носителя награждать некого: русская строка ему шум,
                        // да и подчёркивание слов с подсказками тоже: они ведут в тот же
                        // русский перевод.
                        Bubble(dialog, chunk.mine) {
                            if (state.native) {
                                Text(
                                    chunk.sr,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Jade
                                )
                            } else {
                                GlossedText(
                                    chunk.sr, state.glossaryMe,
                                    MaterialTheme.typography.bodyLarge, Jade,
                                    onWord = ::speakWord
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    chunk.ru,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Muted
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    i == state.index -> {
                        // Русская фраза при переводе — само задание, и остаётся
                        // на месте, даже когда ниже открылся черногорский текст.
                        // Подсказки по словам ей не нужны: русский и так родной.
                        if (state.mode == StoryMode.Translate && !theirTurn) {
                            Bubble(dialog, chunk.mine) {
                                Text(
                                    chunk.ru,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Paper
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                        }

                        when {
                            theirTurn -> TheirLineControls(
                                speaking = speaking,
                                translated = translated,
                                translation = chunk.ru,
                                onReplay = ::playTheirs,
                                onTranslate = { translated = true },
                                onNext = onSkipChunk
                            )

                            translating -> TranslateControls(
                                listening = listening,
                                checking = state.checking,
                                status = status,
                                heard = state.heard,
                                note = state.note,
                                onRecord = ::record
                            )

                            hearing -> {
                                Bubble(dialog, chunk.mine) {
                                    MaskedText(
                                        text = chunk.sr,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Muted,
                                        onReveal = onReveal
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Нажми на любое слово — покажу текст.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Muted
                                )
                                Spacer(Modifier.height(16.dp))
                                ListenControls(
                                    listening = listening,
                                    speaking = speaking,
                                    waiting = auto,
                                    voiceMissing = speaker.voiceUnavailable,
                                    status = status,
                                    heard = state.heard,
                                    note = state.note,
                                    onReplay = ::sample,
                                    onRecord = ::record
                                )
                            }

                            // Режим носителя. Стоит первой из всех веток: он отменяет
                            // и проверку, и подсказки, и всю остальную механику занятия.
                            state.native -> {
                                Bubble(dialog, chunk.mine) {
                                    // Без подсказок по словам и без ударений: носителю
                                    // они ни к чему, а разметка по сербской норме его
                                    // ещё и сбила бы.
                                    Text(
                                        chunk.sr,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Paper
                                    )
                                }
                                Spacer(Modifier.height(20.dp))
                                NativeControls(
                                    recording = recording,
                                    recorded = recorded,
                                    failed = saveFailed,
                                    last = state.index + 1 >= state.chunks.size,
                                    onRecord = {
                                        if (!granted) {
                                            // Спрашиваем по нажатию, а не при входе:
                                            // системный диалог, выскочивший перед гостем
                                            // сам по себе, читается как подвох.
                                            permission.launch(Manifest.permission.RECORD_AUDIO)
                                        } else if (recording) {
                                            recording = false
                                            val at = state.index
                                            scope.launch {
                                                val ok = recorder.stop(at, chunk)
                                                recorded = ok
                                                saveFailed = !ok
                                                if (ok) recorder.writeManifest(state.id, state.title)
                                            }
                                        } else {
                                            recorder.start()
                                            recording = recorder.recording
                                            // Движок не взялся — говорим сразу, а не молчим.
                                            saveFailed = !recording
                                        }
                                    },
                                    onNext = onNativeNext
                                )
                            }

                            // Сдано на слух: текст, перевод и кнопка. Ветка
                            // стоит раньше общей, потому что после сдачи текст
                            // тоже открыт, и иначе отрезок читался бы как
                            // «не вышло на слух — прочитай».
                            state.passed -> {
                                Bubble(dialog, chunk.mine) {
                                    GlossedText(
                                        chunk.sr, state.glossaryMe,
                                        MaterialTheme.typography.headlineSmall, Paper,
                                        onWord = ::speakWord
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    chunk.ru,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Muted
                                )
                                Spacer(Modifier.height(20.dp))
                                PrimaryButton("Продолжить", onClick = onContinue)
                            }

                            else -> {
                                // Текст открыт — своим ходом или сразу: дальше
                                // это одно и то же чтение вслух.
                                if (state.mode != StoryMode.Read) {
                                    Text(
                                        if (state.mode == StoryMode.Translate) "СКАЖИ ТАК"
                                        else "НЕ ВЫШЛО НА СЛУХ — ПРОЧИТАЙ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Accent
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                Bubble(dialog, chunk.mine) {
                                    GlossedText(
                                        chunk.sr, state.glossaryMe,
                                        MaterialTheme.typography.headlineSmall, Paper,
                                        onWord = ::speakWord
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                ReadingControls(
                                    listening = listening,
                                    waiting = auto,
                                    status = status,
                                    heard = state.heard,
                                    note = state.note,
                                    attempts = state.attempts,
                                    // Отсчёт до «дальше» разный: при обычном
                                    // чтении с первой неудачи, а после подсказки —
                                    // с учётом уже потраченных на неё попыток.
                                    skipAfter = if (state.mode == StoryMode.Read) {
                                        ATTEMPTS_BEFORE_SKIP
                                    } else {
                                        ATTEMPTS_BEFORE_SKIP_REVEALED
                                    },
                                    stalled = stalled,
                                    onSample = ::sample,
                                    onRecord = ::record,
                                    onSkip = onSkipChunk
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }

                    else -> {
                        // Впереди показываем ту сторону, с которой работают:
                        // черногорский текст был бы ответом и при переводе,
                        // и на слух.
                        Bubble(dialog, chunk.mine) {
                            if (state.mode == StoryMode.Listen) {
                                // Плашки, а не прочерки, и без нажатия: открывать
                                // отрезок, до которого ещё не дошли, незачем — а
                                // выглядеть он должен так же, как выглядит текущий,
                                // иначе список выдаёт два разных способа закрыть
                                // текст там, где способ один.
                                MaskedText(
                                    text = chunk.sr,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Muted.copy(alpha = 0.45f),
                                    onReveal = null
                                )
                            } else {
                                Text(
                                    when {
                                        state.mode == StoryMode.Read -> chunk.sr
                                        // Русский у чужой реплики — тоже ответ: её
                                        // надо разобрать на слух, а не прочитать
                                        // заранее.
                                        chunk.theirs -> "…"
                                        else -> chunk.ru
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Muted.copy(alpha = 0.45f)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
              }
            }

            // Хвост есть всегда, даже пустой: иначе прокрутка к последнему
            // отрезку упиралась бы в конец списка и не доводила его до верха.
            item {
                if (done) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            state.native -> "PROČITANO"
                            reading -> "ПРОЧИТАНО"
                            else -> "ПЕРЕВЕДЕНО"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Jade
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (state.native) {
                            "Hvala vam! Priča je pročitana do kraja."
                        } else {
                            "История пройдена до конца. К ней можно вернуться в любой момент — " +
                                "на повторение она не встаёт."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                    Spacer(Modifier.height(16.dp))
                    // Крупной кнопкой — выход к списку, а не повторное чтение
                    // (идея 87, Катя). До 1.95 было наоборот, и это была
                    // ошибка акцента: историю только что дочитали, и обычное
                    // желание здесь — взять следующую, а не начать ту же с начала.
                    // Перечитывание не убрано, только перестало быть главным.
                    PrimaryButton(
                        if (state.native) "Na spisak priča" else "К списку историй",
                        onClick = onClose
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onRestart) {
                        Text(
                            if (state.native) "Pročitaj ponovo" else "Пройти заново",
                            color = Muted
                        )
                    }
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
 * Кто говорит: иконка, подпись и — для ваших реплик — отступ.
 *
 * Подпись берётся из файла («Продавщица», «Врач»), потому что иконка говорит
 * только «их двое», а кто именно, важно: диалог у врача от диалога на почте
 * иначе не отличить, глядя на середину списка.
 */
@Composable
private fun SpeakerName(speaker: String) {
    Text(
        speaker.ifBlank { "Собеседник" },
        style = MaterialTheme.typography.labelSmall,
        color = Accent
    )
    Spacer(Modifier.height(5.dp))
}

/**
 * Реплика диалога — пузырём, как в мессенджере.
 *
 * До 1.60 роль разводили иконкой, подписью и ступенькой в 28 dp. Читалось это
 * плохо: ступенька теряется, а подпись приходится читать, чтобы понять, кто
 * говорит. Пузырь отвечает на тот же вопрос формой — её видно раньше, чем
 * прочитана хоть буква.
 *
 * **Свои реплики отличаются рамкой, а не заливкой** — так выбрал владелец из
 * трёх показанных вариантов. Цветная подложка под черногорским текстом спорила
 * бы с ним за внимание: тут не переписка, а то, что надо прочесть вслух.
 *
 * Внутрь пузыря идёт **только текст**. Кнопки и строка распознавания остаются
 * снаружи, во всю ширину: они относятся к занятию, а не к реплике, и прыгали
 * бы слева направо вместе с ролью говорящего.
 *
 * У обычной истории пузырей нет вовсе (`dialog = false`): говорит один человек,
 * делить нечего, а рамка вокруг каждого отрезка превратила бы текст в список.
 */
@Composable
private fun Bubble(
    dialog: Boolean,
    mine: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!dialog) {
        Column(content = content)
        return
    }
    val shape = RoundedCornerShape(BUBBLE_CORNER)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        if (mine) Spacer(Modifier.weight(BUBBLE_MARGIN))
        Column(
            Modifier
                // fill = false: пузырь по содержимому, но не шире своей доли —
                // короткая реплика должна оставаться короткой.
                .weight(1f, fill = false)
                .clip(shape)
                .background(Surface1)
                .then(
                    if (mine) Modifier.border(1.dp, Accent.copy(alpha = 0.55f), shape)
                    else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            content = content
        )
        if (!mine) Spacer(Modifier.weight(BUBBLE_MARGIN))
    }
}

/**
 * Чужая реплика при переводе вслух: её надо разобрать, а не перевести.
 *
 * Микрофона тут нет намеренно. Повторять за собеседником — это занятие «на
 * слух», и оно уже есть отдельно; здесь реплика нужна как условие задачи —
 * поняли, что вам сказали, и отвечаете следующим отрезком. Перевод открывается
 * по нажатию, как в историях: сперва разобрать, потом сверить.
 */
@Composable
private fun TheirLineControls(
    speaking: Boolean,
    translated: Boolean,
    translation: String,
    onReplay: (Boolean) -> Unit,
    onTranslate: () -> Unit,
    onNext: () -> Unit
) {
    Text(
        if (speaking) "Говорит…" else "Слушай, что вам сказали",
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallAction("Ещё раз") { onReplay(false) }
        SmallAction("Медленнее") { onReplay(true) }
        if (!translated) SmallAction("Перевод") { onTranslate() }
    }
    if (translated) {
        Spacer(Modifier.height(12.dp))
        Text(translation, style = MaterialTheme.typography.bodyMedium, color = Paper)
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Дальше", onClick = onNext)
}

/**
 * Закрытый текст: плашка на каждое слово, шириной ровно с него.
 *
 * Ширина не приблизительная, а измеренная тем же начертанием
 * ([rememberTextMeasurer]), которым слово было бы напечатано, — иначе плашки
 * врали бы о длине, а длина тут и есть условие задачи: слышно, сколько всего
 * надо разобрать и не потерялось ли слово. Прочерки по букве говорили то же
 * самое, но занимали втрое больше места и читались как текст, которым не были.
 *
 * Знаки препинания остаются видимыми — они показывают строение фразы и
 * подсказкой не являются.
 *
 * **Нажатие открывает текст**, и это не украшение, а единственный выход из
 * отрезка: до 1.35 в режиме «на слух» его не было вовсе, если движок ничего
 * не разбирал (см. `AppViewModel.revealChunk`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaskedText(
    text: String,
    style: TextStyle,
    color: Color,
    /** `null` — плашки не нажимаются: у отрезка впереди открывать нечего. */
    onReveal: (() -> Unit)?
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val words = remember(text) { text.split(' ').filter { it.isNotBlank() } }
    // Высота плашки — по настоящей строке этого начертания, а не по кеглю:
    // у выносных элементов буквы выше самого кегля.
    val tall = with(density) { measurer.measure("Ag", style).size.height.toDp() }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        words.forEach { word ->
            val core = word.trim { !it.isLetterOrDigit() }
            val head = word.substringBefore(core, "")
            val tail = if (core.isEmpty()) "" else word.substringAfterLast(core, "")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (core.isEmpty()) {
                    // Кусок целиком из знаков — тире между репликами,
                    // многоточие: закрывать нечего, печатаем как есть.
                    Text(word, style = style, color = color)
                } else {
                    if (head.isNotEmpty()) Text(head, style = style, color = color)
                    val wide = with(density) { measurer.measure(core, style).size.width.toDp() }
                    Box(
                        Modifier
                            .width(wide)
                            .height(tall * 0.72f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color.copy(alpha = 0.30f))
                            .then(
                                if (onReveal == null) Modifier
                                else Modifier.clickable(onClick = onReveal)
                            )
                    )
                    if (tail.isNotEmpty()) Text(tail, style = style, color = color)
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
/**
 * Кнопки режима носителя: запись, перезапись, дальше.
 *
 * За экраном тут посторонний человек, который нашей механики не знает и не
 * должен узнавать. Отсюда четыре правила.
 *
 * **Все подписи по-черногорски** (идея 101). Человек пришёл прочитать текст, а
 * не разбирать чужой язык на кнопках, и русское «Записать» для него ровно такая
 * же загадка, какой было бы «Snimi» для нас. По той же причине у пройденных
 * отрезков в этом режиме не показывается русский перевод и не подчёркиваются
 * слова с подсказками: подсказки ведут в тот же русский.
 *
 * **Запись начинается по нажатию, а не сама.** Во всём остальном приложении
 * микрофон включается сам — там человек знает, что делать. Здесь автозапуск
 * записал бы возню, вопрос «а что нажимать?» и полминуты тишины.
 *
 * **«Snimi ponovo» доступна сразу**, а не после чьего-то вердикта: носитель сам
 * слышит, что оговорился, и должен перечитать фразу одним нажатием.
 * Новая запись замещает прежнюю файлом, а не ложится рядом.
 *
 * **«Nastavi» нажимают явно.** Само ничего не едет: человек может
 * прочитать фразу глазами, перевести дух и начать когда готов.
 */
@Composable
private fun NativeControls(
    recording: Boolean,
    recorded: Boolean,
    failed: Boolean,
    last: Boolean,
    onRecord: () -> Unit,
    onNext: () -> Unit
) {
    PrimaryButton(
        when {
            recording -> "Zaustavi"
            recorded -> "Snimi ponovo"
            else -> "Snimi"
        },
        onClick = onRecord
    )
    Spacer(Modifier.height(10.dp))
    Text(
        when {
            recording -> "Snima se…"
            failed -> "Snimanje nije uspjelo. Pokušajte ponovo."
            recorded -> "Snimljeno. Ako želite, pročitajte ponovo — " +
                "prethodni snimak se zamjenjuje."
            else -> "Pročitajte rečenicu naglas."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) Crimson else Muted
    )
    if (!recording) {
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onNext) {
            Text(
                if (last) "Završi" else "Nastavi",
                color = if (recorded) Accent else Muted
            )
        }
    }
}

@Composable
private fun ReadingControls(
    listening: Boolean,
    waiting: Boolean,
    status: String,
    heard: String,
    note: String,
    attempts: Int,
    skipAfter: Int,
    /** Движок сдался сам: слушал и не разобрал ничего. */
    stalled: Boolean,
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

    Attempt(heard = heard, note = note, status = status)

    // Выход даётся либо после честных неудач, либо когда сдался сам движок.
    // Второе обязательно: «ничего не расслышал» попыткой не считается — и это
    // правильно, это не ошибка чтения, — но без такой оговорки счётчик стоял бы
    // на нуле, а отрезок не отпускал бы вовсе. Ровно на этом владелец и застрял.
    if (attempts >= skipAfter || stalled) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Не выходит — дальше", color = Muted)
        }
    }
}

/**
 * Управление текущим отрезком при переводе вслух, пока текст ещё закрыт.
 *
 * Кнопка здесь есть всегда, в том числе когда всё получается: перевод надо
 * сперва придумать, и экран, слушающий сам, торопил бы. Образца тут нет
 * намеренно — он и был бы ответом; «Послушать» появится вместе с текстом,
 * когда отрезок превратится в чтение.
 */
@Composable
private fun TranslateControls(
    listening: Boolean,
    checking: Boolean,
    status: String,
    heard: String,
    note: String,
    onRecord: () -> Unit
) {
    PrimaryButton(
        when {
            checking -> "Проверяю…"
            listening -> "Слушаю…"
            else -> "Сказать по-черногорски"
        },
        enabled = !listening && !checking,
        onClick = onRecord
    )
    Attempt(heard = heard, note = note, status = status)
}

/**
 * Управление текущим отрезком на слух.
 *
 * Отрезок звучит сам и сам же переходит в запись — это и есть упражнение.
 * Кнопки рядом переигрывают фразу целиком (и снова слушают): не расслышал —
 * не значит «нажми и говори», значит «дай ещё раз послушать».
 */
@Composable
private fun ListenControls(
    listening: Boolean,
    speaking: Boolean,
    waiting: Boolean,
    voiceMissing: Boolean,
    status: String,
    heard: String,
    note: String,
    onReplay: (Boolean) -> Unit,
    onRecord: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallAction("Ещё раз") { onReplay(false) }
        SmallAction("Медленнее") { onReplay(true) }
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
                when {
                    speaking -> "Читаю — слушай"
                    listening -> "Слушаю — повтори"
                    else -> "Включаю микрофон…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Accent
            )
        }
    } else {
        PrimaryButton(
            if (listening) "Слушаю…" else "Послушать и повторить",
            enabled = !listening && !speaking,
            onClick = onRecord
        )
    }

    if (voiceMissing) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Голос сербского не установлен — на слух ничего не прозвучит. " +
                "Проверить можно в настройках.",
            style = MaterialTheme.typography.bodyMedium,
            color = Crimson
        )
    }

    Attempt(heard = heard, note = note, status = status)
}

/**
 * Разбор последней попытки: что расслышали, чем это не подошло и что сорвалось.
 *
 * Один блок на все режимы — строки в нём одни и те же, и расходиться им незачем.
 */
@Composable
private fun Attempt(heard: String, note: String, status: String) {
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
}

/**
 * Текст, закрытый прочерками: по одному на букву или цифру, через пробел.
 *
 * Прочерки, а не пустое место, потому что длина слова и их число в отрезке —
 * не подсказка, а условие задачи: понятно, сколько всего надо расслышать и
 * не потерялось ли слово. Знаки препинания остаются как есть — по ним слышно
 * вопрос и конец фразы.
 */
