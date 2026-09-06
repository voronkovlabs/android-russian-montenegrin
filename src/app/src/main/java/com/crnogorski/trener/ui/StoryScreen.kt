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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crnogorski.trener.speech.Listener
import com.crnogorski.trener.speech.Speaker

/** После скольких неудач подряд даём пройти дальше, не взяв отрезок. */
private const val ATTEMPTS_BEFORE_SKIP = 3

/**
 * История: связный текст, который читают вслух по отрезкам.
 *
 * Пока всё получается, экран ничего не просит нажимать: отрезок появился —
 * распознавание уже слушает, прочитал — перевод открылся, и следующий отрезок
 * снова слушает. Должно ощущаться обычным чтением вслух, а не выполнением
 * заданий по одному.
 *
 * Кнопка возвращается ровно тогда, когда что-то пошло не так: не разобрали,
 * не дали доступ к микрофону или человек сам прервал прослушиванием образца.
 * После трёх неудач подряд отрезок можно оставить — движок распознавания
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

    fun start() {
        listening = true
        status = ""
        listener.listen(
            onResult = { heard ->
                // listening не гасим: при удаче сразу поедет следующий отрезок,
                // и мигание кнопкой между ними ни к чему.
                onSubmit(heard)
            },
            onError = { message ->
                listening = false
                status = message
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
    // Слушаем сами, только пока всё идёт гладко. Сорвалось — ждём нажатия.
    val auto = granted && !paused && state.attempts == 0 && !done

    // Ключи без paused: снятие паузы — это нажатие кнопки, и запускает его
    // record(). Будь paused ключом, эффект запустил бы распознавание вторым.
    LaunchedEffect(state.id, state.index, granted) {
        if (granted && !paused && state.attempts == 0 && !done) start()
    }

    // Неудачу экран узнаёт по счётчику попыток: сам onResult не знает, засчитали
    // прочитанное или нет — это решает AppViewModel. Без этого кнопка осталась бы
    // навсегда в состоянии «Слушаю…».
    LaunchedEffect(state.attempts) {
        if (state.attempts > 0) listening = false
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

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text("ИСТОРИЯ", style = MaterialTheme.typography.labelSmall, color = Accent)
            Spacer(Modifier.height(6.dp))
            Text(state.title, style = MaterialTheme.typography.displaySmall, color = Paper)
            Spacer(Modifier.height(24.dp))

            state.chunks.forEachIndexed { i, chunk ->
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
                        GlossedText(
                            chunk.sr, state.glossaryMe,
                            MaterialTheme.typography.headlineSmall, Paper
                        )
                        Spacer(Modifier.height(16.dp))
                        CurrentChunkControls(
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
                        Spacer(Modifier.height(20.dp))
                    }

                    else -> {
                        Text(
                            chunk.sr,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Muted.copy(alpha = 0.45f)
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            if (done) {
                Spacer(Modifier.height(10.dp))
                Text("ПРОЧИТАНО", style = MaterialTheme.typography.labelSmall, color = Jade)
                Spacer(Modifier.height(12.dp))
                Text(
                    "История дочитана до конца. Её можно перечитать в любой момент — " +
                        "на повторение она не встаёт.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Прочитать заново", onClick = onRestart)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClose) { Text("К списку историй", color = Muted) }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * Управление текущим отрезком.
 *
 * В гладком случае кнопки нет вовсе — только строка «Слушаю»: нажимать нечего,
 * просто читай. Кнопка появляется, когда слушание сорвалось или его прервали.
 */
@Composable
private fun CurrentChunkControls(
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
