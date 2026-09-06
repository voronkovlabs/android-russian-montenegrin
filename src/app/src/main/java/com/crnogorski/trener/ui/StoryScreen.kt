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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.crnogorski.trener.speech.Listener
import com.crnogorski.trener.speech.Speaker

/** После скольких неудач подряд даём пройти дальше, не взяв отрезок. */
private const val ATTEMPTS_BEFORE_SKIP = 3

/**
 * История: связный текст, который читают вслух по отрезкам.
 *
 * Отрезок перечитывают, пока не получится, и только после этого под ним
 * появляется перевод — он тут награда, а не подсказка. Прочитанное остаётся
 * на экране: к концу истории сверху виден весь пройденный путь с переводом.
 *
 * Выход есть: после трёх неудач подряд отрезок можно оставить. Движок
 * распознавания ошибается сам по себе, и упереться в него навсегда нельзя.
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
    var listening by remember(state.id, state.index) { mutableStateOf(false) }
    var status by remember(state.id, state.index) { mutableStateOf("") }

    BackHandler { onClose() }

    fun start() {
        listening = true
        status = ""
        listener.listen(
            onResult = { heard ->
                listening = false
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
        if (granted) start() else status = "Без доступа к микрофону историю не прочитать"
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
                            speakable = chunk.sr,
                            speaker = speaker,
                            listening = listening,
                            status = status,
                            heard = state.heard,
                            note = state.note,
                            attempts = state.attempts,
                            onRecord = {
                                val granted = androidx.core.content.ContextCompat
                                    .checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                if (granted) start()
                                else permission.launch(Manifest.permission.RECORD_AUDIO)
                            },
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

            if (state.index >= state.chunks.size) {
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

@Composable
private fun CurrentChunkControls(
    speakable: String,
    speaker: Speaker,
    listening: Boolean,
    status: String,
    heard: String,
    note: String,
    attempts: Int,
    onRecord: () -> Unit,
    onSkip: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallAction("Послушать") { speaker.speak(speakable) }
        SmallAction("Медленнее") { speaker.speak(speakable, slow = true) }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(if (listening) "Слушаю…" else "Читать вслух", enabled = !listening, onClick = onRecord)

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
