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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crnogorski.trener.data.ComplaintReason
import com.crnogorski.trener.data.Exercise
import com.crnogorski.trener.data.LocalCheck
import com.crnogorski.trener.speech.AnswerLanguage
import com.crnogorski.trener.speech.Listener
import com.crnogorski.trener.speech.Speaker

@Composable
fun SessionScreen(
    state: SessionState,
    speaker: Speaker,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onRetryBlock: () -> Unit,
    onComplain: (ComplaintReason, String) -> Unit,
    onNote: (String) -> Unit,
    onExit: () -> Unit
) {
    // Системная «Назад» должна возвращать к списку уроков, а не закрывать приложение.
    // Выход из приложения остаётся только на главном экране, где BackHandler-а нет.
    BackHandler { onExit() }

    if (state.finished) {
        FinishedView(state, onExit)
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        SessionHeader(state, onNote, onExit)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(20.dp))

            if (state.index == 0 && state.note.isNotBlank() && state.phase == Phase.Input) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface1)
                        .padding(14.dp)
                ) {
                    Text("ГРАММАТИКА УРОКА", style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    Text(state.note, style = MaterialTheme.typography.bodyMedium, color = Paper)
                }
                Spacer(Modifier.height(24.dp))
            }

            when (val phase = state.phase) {
                is Phase.Blocked -> BlockedView(phase.message, onRetryBlock, onExit)
                is Phase.Checking -> {
                    ExerciseBody(state, speaker, enabled = false, onSubmit = {}, onSkip = {})
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = Accent
                        )
                        Spacer(Modifier.height(0.dp))
                        Text(
                            "  Claude проверяет ответ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted
                        )
                    }
                }
                is Phase.Result -> {
                    ExerciseBody(state, speaker, enabled = false, onSubmit = {}, onSkip = {})
                    Spacer(Modifier.height(20.dp))
                    ResultView(phase, state.current)
                    AnswerTail(state, onNext, onComplain)
                }
                is Phase.Skipped -> {
                    ExerciseBody(state, speaker, enabled = false, onSubmit = {}, onSkip = {})
                    Spacer(Modifier.height(20.dp))
                    SkippedView(phase)
                    AnswerTail(state, onNext, onComplain)
                }
                Phase.Input -> ExerciseBody(state, speaker, enabled = true, onSubmit = onSubmit, onSkip = onSkip)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SessionHeader(state: SessionState, onNote: (String) -> Unit, onExit: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Выйти из урока",
                    tint = Muted
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${state.index + 1} / ${state.items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
            // Пожаловаться можно и посреди задания: диалог поверх, урок не сбивается.
            ComplaintButton(onSave = onNote)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = Accent,
            trackColor = Surface2
        )
    }
}

@Composable
private fun ExerciseBody(
    state: SessionState,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit
) {
    when (val ex = state.current) {
        is Exercise.TranslateToTarget -> TextAnswer(
            key = ex.id,
            label = "Переведи на черногорский",
            prompt = ex.prompt,
            hint = ex.hint,
            enabled = enabled,
            language = AnswerLanguage.Target,
            // Задание по-русски: подсказка ведёт в черногорский.
            promptGloss = state.glossary.ru,
            onSubmit = onSubmit
        )

        is Exercise.TranslateToNative -> TextAnswer(
            key = ex.id,
            label = "Переведи на русский",
            prompt = ex.prompt,
            hint = ex.hint,
            enabled = enabled,
            language = AnswerLanguage.Native,
            promptGloss = state.glossary.me,
            speakable = ex.prompt,
            speaker = speaker,
            onSubmit = onSubmit
        )

        is Exercise.Form -> TextAnswer(
            key = ex.id,
            label = "Поставь слово в нужную форму",
            prompt = ex.prompt,
            hint = "",
            enabled = enabled,
            language = AnswerLanguage.Target,
            promptGloss = state.glossary.me,
            onSubmit = onSubmit
        )

        // Подпись приходит с заданием: словарная карточка спрашивает то
        // значение, то падеж, то особую форму — механика одна, вопрос разный.
        is Exercise.Word -> TextAnswer(
            key = ex.id,
            label = ex.label,
            prompt = ex.prompt,
            hint = "",
            enabled = enabled,
            // Язык ответа, а не задания: у обратного перевода отвечают
            // по-русски, и распознавание с клавиатурой должны быть русскими.
            language = if (ex.native) AnswerLanguage.Native else AnswerLanguage.Target,
            promptGloss = if (ex.native) emptyMap() else state.glossary.me,
            // Слово проще сказать, чем набрать: микрофон включается сам, а
            // клавиатура остаётся на месте — набрать руками можно всегда.
            autoListen = true,
            onSubmit = onSubmit
        )

        is Exercise.Choice -> ChoiceAnswer(ex, speaker, enabled, onSubmit)

        is Exercise.WordBank -> WordBankAnswer(ex, state.glossary.ru, enabled, onSubmit)

        is Exercise.Listening -> ListeningAnswer(ex, speaker, enabled, onSubmit)

        is Exercise.Speaking -> SpokenAnswer(
            key = ex.id,
            label = "Произнеси вслух",
            text = ex.phrase,
            translation = ex.translation,
            byEar = false,
            gloss = state.glossary.me,
            translationGloss = state.glossary.ru,
            speaker = speaker,
            enabled = enabled,
            onSubmit = onSubmit,
            onSkip = onSkip
        )

        is Exercise.Repeat -> SpokenAnswer(
            key = ex.id,
            label = "Повтори на слух",
            text = ex.phrase,
            translation = ex.translation,
            byEar = true,
            gloss = state.glossary.me,
            translationGloss = state.glossary.ru,
            speaker = speaker,
            enabled = enabled,
            onSubmit = onSubmit,
            onSkip = onSkip
        )

        is Exercise.Reading -> ReadingAnswer(
            ex = ex,
            speaker = speaker,
            gloss = state.glossary.me,
            translationGloss = state.glossary.ru,
            enabled = enabled,
            onSubmit = onSubmit,
            onSkip = onSkip
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Prompt(text: String, gloss: Map<String, String> = emptyMap()) {
    GlossedText(text, gloss, MaterialTheme.typography.headlineSmall, Paper)
}

/**
 * Текст на предложения. Читать вслух целым куском не выходит (см. ReadingAnswer),
 * поэтому граница предложения — это граница одного захода распознавания.
 */
private fun splitSentences(text: String): List<String> =
    Regex("[^.!?]+[.!?]*")
        .findAll(text)
        .map { it.value.trim() }
        .filter { it.isNotBlank() }
        .toList()

@Composable
private fun TextAnswer(
    key: String,
    label: String,
    prompt: String,
    hint: String,
    enabled: Boolean,
    language: AnswerLanguage,
    promptGloss: Map<String, String> = emptyMap(),
    speakable: String? = null,
    speaker: Speaker? = null,
    /** Начинать слушать сразу, не дожидаясь нажатия на микрофон. */
    autoListen: Boolean = false,
    onSubmit: (String) -> Unit
) {
    var value by remember(key) { mutableStateOf("") }
    var status by remember(key) { mutableStateOf("") }

    // Черногорскую фразу озвучиваем сразу, как только задание появилось: слышать
    // её нужно раньше, чем разбирать. Только в Input — иначе фраза повторилась бы
    // при переходе к результату, когда тот же блок перерисовывается неактивным.
    if (speakable != null && speaker != null) {
        LaunchedEffect(key) { if (enabled) speaker.speak(speakable) }
    }

    Label(label)
    Prompt(prompt, promptGloss)
    if (speakable != null && speaker != null) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("Прослушать") { speaker.speak(speakable) }
            SmallAction("Медленнее") { speaker.speak(speakable, slow = true) }
        }
    }
    if (hint.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
    Spacer(Modifier.height(20.dp))

    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Ответ", color = Muted) },
        textStyle = MaterialTheme.typography.bodyLarge,
        trailingIcon = {
            if (enabled) {
                MicButton(
                    language = language.speech,
                    enabled = true,
                    autoKey = if (autoListen) key else null,
                    onStatus = { status = it },
                    onText = { value = appendSpoken(value, it) }
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
            // Подсказка клавиатуре, на каком языке будет ответ: иначе раскладку
            // приходится переключать руками на каждом задании.
            hintLocales = LocaleList(language.keyboard)
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Surface2,
            focusedTextColor = Paper,
            unfocusedTextColor = Paper,
            disabledTextColor = Muted,
            cursorColor = Accent
        )
    )

    if (status.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }

    if (enabled) {
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Проверить", enabled = value.isNotBlank()) { onSubmit(value) }
    }
}

/** Продиктованное дописывается к набранному, а не затирает его. */
private fun appendSpoken(current: String, heard: String): String =
    if (current.isBlank()) heard else current.trimEnd() + " " + heard

@Composable
private fun ChoiceAnswer(
    ex: Exercise.Choice,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit
) {
    Label("Выбери вариант")
    Prompt(ex.prompt)
    Spacer(Modifier.height(20.dp))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ex.options.forEach { option ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .border(1.dp, Surface2, RoundedCornerShape(12.dp))
                    // Вариант проговаривается вслух: выбор глазами не даёт
                    // услышать, как выбранное звучит.
                    .clickable(enabled = enabled) {
                        speaker.speak(option)
                        onSubmit(option)
                    }
                    .padding(16.dp)
            ) {
                Text(option, style = MaterialTheme.typography.bodyLarge, color = Paper)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordBankAnswer(
    ex: Exercise.WordBank,
    gloss: Map<String, String>,
    enabled: Boolean,
    onSubmit: (String) -> Unit
) {
    var picked by remember(ex.id) { mutableStateOf(listOf<String>()) }
    val remaining = remember(picked) {
        val counts = picked.groupingBy { it }.eachCount().toMutableMap()
        ex.bank.filter { word ->
            val left = counts[word] ?: 0
            if (left > 0) { counts[word] = left - 1; false } else true
        }
    }

    Label("Собери фразу")
    Prompt(ex.prompt, gloss)
    Spacer(Modifier.height(20.dp))

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(14.dp)
    ) {
        if (picked.isEmpty()) {
            Text("Нажимай на слова ниже", color = Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                picked.forEachIndexed { i, word ->
                    Chip(word, filled = true, enabled = enabled) {
                        picked = picked.toMutableList().also { it.removeAt(i) }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        remaining.forEach { word ->
            Chip(word, filled = false, enabled = enabled) { picked = picked + word }
        }
    }

    if (enabled) {
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Проверить", enabled = picked.isNotEmpty()) {
            onSubmit(picked.joinToString(" "))
        }
    }
}

@Composable
private fun ListeningAnswer(
    ex: Exercise.Listening,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit
) {
    var value by remember(ex.id) { mutableStateOf("") }

    // Фраза звучит сразу: задание в том, чтобы её записать, а не в том,
    // чтобы догадаться нажать кнопку. Кнопка остаётся — послушать ещё раз.
    LaunchedEffect(ex.id) { if (enabled) speaker.speak(ex.audioText) }

    Label("Запиши услышанное")
    Text(
        "Набери фразу так, как её произносят. Можно послушать ещё раз.",
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallAction("Прослушать") { speaker.speak(ex.audioText) }
        SmallAction("Медленнее") { speaker.speak(ex.audioText, slow = true) }
    }

    if (speaker.voiceUnavailable) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Голос сербского не установлен. Настройки → Система → Языки → Синтез речи.",
            style = MaterialTheme.typography.bodyMedium,
            color = Crimson
        )
    }

    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Что ты услышал", color = Muted) },
        textStyle = MaterialTheme.typography.bodyLarge,
        // Микрофона здесь нет намеренно: продиктовать услышанное — значит
        // поручить распознавателю ровно ту работу, ради которой задание и есть.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
            hintLocales = LocaleList(AnswerLanguage.Target.keyboard)
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Surface2,
            focusedTextColor = Paper,
            unfocusedTextColor = Paper,
            disabledTextColor = Muted,
            cursorColor = Accent
        )
    )

    if (enabled) {
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Проверить", enabled = value.isNotBlank()) { onSubmit(value) }
    }
}

/**
 * Сказать вслух и сверить с распознанным — фраза, текст или повтор на слух.
 *
 * [byEar] — фраза звучит сама, а текст закрыт, пока его не откроют: опереться
 * должно быть не на что, кроме услышанного. Длинный текст читается не здесь,
 * а в ReadingAnswer: один заход распознавания — одна короткая фраза.
 */
@Composable
private fun SpokenAnswer(
    key: String,
    label: String,
    text: String,
    translation: String,
    byEar: Boolean,
    gloss: Map<String, String>,
    translationGloss: Map<String, String>,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    var status by remember(key) { mutableStateOf("") }
    var listening by remember(key) { mutableStateOf(false) }
    var revealed by remember(key) { mutableStateOf(false) }

    // После ответа текст открывается сам: иначе не с чем сверить услышанное.
    val showText = !byEar || revealed || !enabled

    if (byEar) {
        LaunchedEffect(key) { if (enabled) speaker.speak(text) }
    }

    fun start() {
        listening = true
        status = "Говори…"
        listener.listen(
            onResult = { heard ->
                listening = false
                status = ""
                onSubmit(heard)
            },
            onError = { message ->
                listening = false
                status = message
            }
        )
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else status = "Без доступа к микрофону задание не проверить"
    }

    Label(label)
    if (showText) {
        Prompt(text, gloss)
    } else {
        Text(
            "Текст закрыт — слушай и повторяй.",
            style = MaterialTheme.typography.headlineSmall,
            color = Muted
        )
    }
    Spacer(Modifier.height(8.dp))
    GlossedText(translation, translationGloss, MaterialTheme.typography.bodyMedium, Muted)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallAction(if (byEar) "Ещё раз" else "Послушать образец") { speaker.speak(text) }
        SmallAction("Медленнее") { speaker.speak(text, slow = true) }
        if (!showText) {
            SmallAction("Показать текст") { revealed = true }
        }
    }

    Spacer(Modifier.height(24.dp))
    PrimaryButton(if (listening) "Слушаю…" else "Записать", enabled = enabled && !listening) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (status.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }

    if (enabled) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Пропустить", color = Muted)
        }
    }
}

@Composable
private fun ResultView(phase: Phase.Result, exercise: Exercise) {
    val accent = if (phase.correct) Jade else Crimson

    // Точное совпадение с эталоном показывать незачем — строка дублировала бы «Правильно».
    val differs = !LocalCheck.matches(phase.answer, phase.expected)
    val showAnswer = phase.answer.isNotBlank() && (!phase.correct || differs)
    // Для речи это не то, что ты сказал, а то, что расслышал движок.
    val spoken = exercise is Exercise.Speaking ||
        exercise is Exercise.Repeat ||
        exercise is Exercise.Reading
    val answerLabel = if (spoken) "Услышано" else "Твой ответ"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            if (phase.correct) "ВЕРНО" else "ОШИБКА",
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
        Spacer(Modifier.height(10.dp))
        if (showAnswer) {
            Text(
                "$answerLabel: ${phase.answer}",
                style = MaterialTheme.typography.bodyLarge,
                color = Paper
            )
            Spacer(Modifier.height(8.dp))
        }
        if (!phase.correct) {
            Text("Правильно: ${phase.expected}", style = MaterialTheme.typography.bodyLarge, color = Paper)
            Spacer(Modifier.height(8.dp))
        } else if (showAnswer) {
            // Ответ засчитан, но не совпал с эталоном: «Правильно» тут вводило бы в заблуждение.
            Text("Эталон: ${phase.expected}", style = MaterialTheme.typography.bodyMedium, color = Muted)
            Spacer(Modifier.height(8.dp))
        }
        if (phase.feedback.isNotBlank()) {
            Text(phase.feedback, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        if (phase.better.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Естественнее: ${phase.better}", style = MaterialTheme.typography.bodyMedium, color = Accent)
        }
    }
}

/**
 * Чтение вслух — по одному предложению за заход.
 *
 * Целым текстом не выходит: движок распознавания на длинной фразе возвращает
 * «ничего не расслышал», а просьбы не обрывать запись на паузе документация
 * Android разрешает игнорировать — что он и делает. Короткая фраза
 * распознаётся надёжно, поэтому текст читается по предложению, услышанное
 * склеивается и оценивается целиком, уже в AppViewModel.
 *
 * Ошибка распознавания стоит одного предложения, а не всего текста: сорванное
 * перечитывается на месте, прочитанное раньше не теряется.
 */
@Composable
private fun ReadingAnswer(
    ex: Exercise.Reading,
    speaker: Speaker,
    gloss: Map<String, String>,
    translationGloss: Map<String, String>,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    val sentences = remember(ex.id) { splitSentences(ex.text) }
    var heard by remember(ex.id) { mutableStateOf(listOf<String>()) }
    var status by remember(ex.id) { mutableStateOf("") }
    var listening by remember(ex.id) { mutableStateOf(false) }

    val index = heard.size.coerceAtMost(sentences.size - 1)
    val current = sentences.getOrElse(index) { ex.text }

    fun start() {
        listening = true
        status = ""
        listener.listen(
            onResult = { text ->
                listening = false
                val collected = heard + text
                if (collected.size >= sentences.size) {
                    onSubmit(collected.joinToString(" "))
                } else {
                    heard = collected
                }
            },
            onError = { message ->
                listening = false
                status = "$message. Это предложение можно перечитать."
            }
        )
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else status = "Без доступа к микрофону задание не проверить"
    }

    Label("Прочитай вслух")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sentences.forEachIndexed { i, sentence ->
            GlossedText(
                text = sentence,
                gloss = gloss,
                style = MaterialTheme.typography.headlineSmall,
                color = when {
                    !enabled -> Paper
                    i < heard.size -> Jade
                    i == index -> Paper
                    else -> Muted
                }
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    GlossedText(ex.translation, translationGloss, MaterialTheme.typography.bodyMedium, Muted)

    if (enabled) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Предложение ${index + 1} из ${sentences.size} — читается по одному.",
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("Послушать") { speaker.speak(current) }
            SmallAction("Медленнее") { speaker.speak(current, slow = true) }
            if (heard.isNotEmpty()) {
                SmallAction("Сначала") {
                    heard = emptyList()
                    status = ""
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        if (listening) "Слушаю…" else "Записать предложение",
        enabled = enabled && !listening
    ) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (status.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }

    if (enabled) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Пропустить", color = Muted)
        }
    }
}

/** Общий хвост под ответом и под пропуском: «Дальше» плюс жалоба. */
@Composable
private fun AnswerTail(
    state: SessionState,
    onNext: () -> Unit,
    onComplain: (ComplaintReason, String) -> Unit
) {
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)
    ) { Text("Дальше", style = MaterialTheme.typography.titleMedium) }
    Spacer(Modifier.height(4.dp))
    ComplaintBlock(
        exerciseId = state.current.id,
        filed = state.complaintFiled,
        onComplain = onComplain
    )
}

/**
 * Пропуск — не ошибка, поэтому нейтральные цвета и никакого «ОШИБКА»:
 * карточка только отодвинута, ease и счётчик повторений не тронуты.
 */
@Composable
private fun SkippedView(phase: Phase.Skipped) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(1.dp, Surface2, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("ПРОПУЩЕНО", style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(10.dp))
        Text(phase.expected, style = MaterialTheme.typography.bodyLarge, color = Paper)
        Spacer(Modifier.height(8.dp))
        Text(
            "Без штрафа: карточка вернётся через несколько часов.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

/**
 * Жалоба на задание. Свёрнута в одну строчку, пока не понадобится:
 * это инструмент правки курса, а не часть учебного потока.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComplaintBlock(
    exerciseId: String,
    filed: Boolean,
    onComplain: (ComplaintReason, String) -> Unit
) {
    // Ключ по заданию: разворот и выбранная причина не должны переезжать на следующее.
    var open by remember(exerciseId) { mutableStateOf(false) }
    var reason by remember(exerciseId) { mutableStateOf<ComplaintReason?>(null) }
    var note by remember(exerciseId) { mutableStateOf("") }

    if (filed) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Жалоба записана. Карточка не пойдёт в повторение из-за этого ответа.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
        return
    }

    if (!open) {
        TextButton(onClick = { open = true }) {
            Text("Пожаловаться на задание", color = Muted)
        }
        return
    }

    Spacer(Modifier.height(8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(14.dp)
    ) {
        Text("ЧТО НЕ ТАК", style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(12.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComplaintReason.entries.forEach { option ->
                Chip(option.label, filled = reason == option, enabled = true) {
                    reason = option
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Комментарий, если нужен", color = Muted) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Surface2,
                focusedTextColor = Paper,
                unfocusedTextColor = Paper,
                cursorColor = Accent
            )
        )

        Spacer(Modifier.height(14.dp))
        PrimaryButton("Записать жалобу", enabled = reason != null) {
            reason?.let { onComplain(it, note) }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { open = false }) { Text("Отмена", color = Muted) }
    }
}

@Composable
private fun BlockedView(message: String, onRetry: () -> Unit, onExit: () -> Unit) {
    Column {
        Text("НЕ ПОЛУЧИЛОСЬ", style = MaterialTheme.typography.labelSmall, color = Crimson)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Paper)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Повторить попытку") { onRetry() }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onExit) { Text("Вернуться к списку", color = Muted) }
    }
}

@Composable
private fun FinishedView(state: SessionState, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("ГОТОВО", style = MaterialTheme.typography.labelSmall, color = Accent)
        Spacer(Modifier.height(12.dp))
        Text(state.title, style = MaterialTheme.typography.displaySmall, color = Paper)
        Spacer(Modifier.height(16.dp))
        Text(
            "${state.correct} из ${state.items.size} с первого раза. " +
                "Ошибки вернутся в повторении.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton("К списку уроков") { onExit() }
    }
}

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Ink,
            disabledContainerColor = Surface2,
            disabledContentColor = Muted
        )
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
internal fun SmallAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Surface2, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Accent)
    }
}

@Composable
private fun Chip(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) Accent.copy(alpha = 0.18f) else Surface2)
            .border(
                1.dp,
                if (filled) Accent.copy(alpha = 0.5f) else Surface2,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Paper)
    }
}
