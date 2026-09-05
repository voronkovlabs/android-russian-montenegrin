package com.crnogorski.trener.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crnogorski.trener.data.Exercise
import com.crnogorski.trener.speech.Listener
import com.crnogorski.trener.speech.Speaker

@Composable
fun SessionScreen(
    state: SessionState,
    speaker: Speaker,
    onSubmit: (String) -> Unit,
    onNext: () -> Unit,
    onRetryBlock: () -> Unit,
    onExit: () -> Unit
) {
    if (state.finished) {
        FinishedView(state, onExit)
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        SessionHeader(state, onExit)

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
                    ExerciseBody(state, speaker, enabled = false, onSubmit = {})
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = Gold
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
                    ExerciseBody(state, speaker, enabled = false, onSubmit = {})
                    Spacer(Modifier.height(20.dp))
                    ResultView(phase)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onNext,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
                    ) { Text("Дальше", style = MaterialTheme.typography.titleMedium) }
                }
                Phase.Input -> ExerciseBody(state, speaker, enabled = true, onSubmit = onSubmit)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SessionHeader(state: SessionState, onExit: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("Выйти", color = Muted) }
            Spacer(Modifier.weight(1f))
            Text(
                "${state.index + 1} / ${state.items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = Gold,
            trackColor = Surface2
        )
    }
}

@Composable
private fun ExerciseBody(
    state: SessionState,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit
) {
    when (val ex = state.current) {
        is Exercise.TranslateToTarget -> TextAnswer(
            key = ex.id,
            label = "Переведи на черногорский",
            prompt = ex.prompt,
            hint = ex.hint,
            enabled = enabled,
            onSubmit = onSubmit
        )

        is Exercise.TranslateToNative -> TextAnswer(
            key = ex.id,
            label = "Переведи на русский",
            prompt = ex.prompt,
            hint = ex.hint,
            enabled = enabled,
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
            onSubmit = onSubmit
        )

        is Exercise.Choice -> ChoiceAnswer(ex, enabled, onSubmit)

        is Exercise.WordBank -> WordBankAnswer(ex, enabled, onSubmit)

        is Exercise.Listening -> ListeningAnswer(ex, speaker, enabled, onSubmit)

        is Exercise.Speaking -> SpeakingAnswer(ex, speaker, enabled, onSubmit)
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Gold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Prompt(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Paper)
}

@Composable
private fun TextAnswer(
    key: String,
    label: String,
    prompt: String,
    hint: String,
    enabled: Boolean,
    speakable: String? = null,
    speaker: Speaker? = null,
    onSubmit: (String) -> Unit
) {
    var value by remember(key) { mutableStateOf("") }

    Label(label)
    Prompt(prompt)
    if (speakable != null && speaker != null) {
        Spacer(Modifier.height(10.dp))
        SmallAction("Прослушать") { speaker.speak(speakable) }
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
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold,
            unfocusedBorderColor = Surface2,
            focusedTextColor = Paper,
            unfocusedTextColor = Paper,
            disabledTextColor = Muted,
            cursorColor = Gold
        )
    )

    if (enabled) {
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Проверить", enabled = value.isNotBlank()) { onSubmit(value) }
    }
}

@Composable
private fun ChoiceAnswer(ex: Exercise.Choice, enabled: Boolean, onSubmit: (String) -> Unit) {
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
                    .clickable(enabled = enabled) { onSubmit(option) }
                    .padding(16.dp)
            ) {
                Text(option, style = MaterialTheme.typography.bodyLarge, color = Paper)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordBankAnswer(ex: Exercise.WordBank, enabled: Boolean, onSubmit: (String) -> Unit) {
    var picked by remember(ex.id) { mutableStateOf(listOf<String>()) }
    val remaining = remember(picked) {
        val counts = picked.groupingBy { it }.eachCount().toMutableMap()
        ex.bank.filter { word ->
            val left = counts[word] ?: 0
            if (left > 0) { counts[word] = left - 1; false } else true
        }
    }

    Label("Собери фразу")
    Prompt(ex.prompt)
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

    Label("Запиши услышанное")
    Text(
        "Нажми на кнопку и набери фразу так, как её произносят.",
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Прослушать") { speaker.speak(ex.audioText) }

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
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold,
            unfocusedBorderColor = Surface2,
            focusedTextColor = Paper,
            unfocusedTextColor = Paper,
            disabledTextColor = Muted,
            cursorColor = Gold
        )
    )

    if (enabled) {
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Проверить", enabled = value.isNotBlank()) { onSubmit(value) }
    }
}

@Composable
private fun SpeakingAnswer(
    ex: Exercise.Speaking,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit
) {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    var status by remember(ex.id) { mutableStateOf("") }
    var listening by remember(ex.id) { mutableStateOf(false) }

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

    Label("Произнеси вслух")
    Prompt(ex.phrase)
    Spacer(Modifier.height(8.dp))
    Text(ex.translation, style = MaterialTheme.typography.bodyMedium, color = Muted)
    Spacer(Modifier.height(16.dp))
    SmallAction("Послушать образец") { speaker.speak(ex.phrase) }

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
        TextButton(onClick = { onSubmit("") }) {
            Text("Пропустить", color = Muted)
        }
    }
}

@Composable
private fun ResultView(phase: Phase.Result) {
    val accent = if (phase.correct) Gold else Crimson
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
        if (!phase.correct) {
            Text("Правильно: ${phase.expected}", style = MaterialTheme.typography.bodyLarge, color = Paper)
            Spacer(Modifier.height(8.dp))
        }
        if (phase.feedback.isNotBlank()) {
            Text(phase.feedback, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        if (phase.better.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Естественнее: ${phase.better}", style = MaterialTheme.typography.bodyMedium, color = Gold)
        }
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
        Text("ГОТОВО", style = MaterialTheme.typography.labelSmall, color = Gold)
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
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Gold,
            contentColor = Ink,
            disabledContainerColor = Surface2,
            disabledContentColor = Muted
        )
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Surface2, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Gold)
    }
}

@Composable
private fun Chip(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) Gold.copy(alpha = 0.18f) else Surface2)
            .border(
                1.dp,
                if (filled) Gold.copy(alpha = 0.5f) else Surface2,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Paper)
    }
}
