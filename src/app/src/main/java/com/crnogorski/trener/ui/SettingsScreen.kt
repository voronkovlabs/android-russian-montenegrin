package com.crnogorski.trener.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.core.content.FileProvider
import com.crnogorski.trener.speech.Speaker
import java.io.File

/** Фраза для проверки голоса: короткая, со всеми характерными звуками. */
private const val VOICE_PROBE = "Dobar dan, kako si?"

@Composable
fun SettingsScreen(
    state: SettingsState,
    speaker: Speaker,
    complaintsFile: File,
    onArchive: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var confirmArchive by remember { mutableStateOf(false) }
    var voiceChecked by remember { mutableStateOf(false) }

    // Системная «назад» должна возвращать к списку уроков, а не закрывать приложение.
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("Назад", color = Muted) }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text("НАСТРОЙКИ", style = MaterialTheme.typography.labelSmall, color = Gold)
            Spacer(Modifier.height(6.dp))
            Text("Отчёт о заданиях", style = MaterialTheme.typography.displaySmall, color = Paper)
            Spacer(Modifier.height(24.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .padding(16.dp)
            ) {
                Text(
                    "НАКОПЛЕНО ЖАЛОБ",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${state.complaintCount}",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (state.complaintCount > 0) Gold else Muted
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    state.filePath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }

            Spacer(Modifier.height(20.dp))

            PrimaryAction(
                text = "Отправить отчёт",
                enabled = state.complaintCount > 0
            ) { shareComplaints(context, complaintsFile) }

            Spacer(Modifier.height(10.dp))

            SecondaryAction(
                text = "Очистить (уже отправлено)",
                enabled = state.complaintCount > 0
            ) { confirmArchive = true }

            if (state.notice != null) {
                Spacer(Modifier.height(14.dp))
                Text(state.notice, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Очистка не удаляет жалобы, а откладывает их в отдельный файл рядом: " +
                    "Android не сообщает, дошла ли отправка, и терять записи по нажатию нельзя.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            Spacer(Modifier.height(36.dp))

            Text("РЕЧЬ", style = MaterialTheme.typography.labelSmall, color = Gold)
            Spacer(Modifier.height(12.dp))
            SecondaryAction(text = "Проверить синтез речи") {
                voiceChecked = true
                speaker.speak(VOICE_PROBE)
            }
            if (voiceChecked) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (speaker.voiceUnavailable) {
                        "Голос sr-RS не установлен — аудирование будет молчать. " +
                            "Настройки → Система → Языки и ввод → Синтез речи → поставить сербский."
                    } else {
                        "Должно было прозвучать «$VOICE_PROBE». Если тихо — проверь громкость."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (speaker.voiceUnavailable) Crimson else Muted
                )
            }

            Spacer(Modifier.height(36.dp))
            Text(
                "Версия ${state.versionName} (${state.versionCode})",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            containerColor = Surface1,
            titleContentColor = Paper,
            textContentColor = Muted,
            title = { Text("Отложить ${state.complaintCount} шт.?") },
            text = {
                Text(
                    "Записи уйдут в отдельный файл, счётчик обнулится. " +
                        "Делай это после того, как убедишься, что отчёт дошёл."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmArchive = false
                    onArchive()
                }) { Text("Отложить", color = Gold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }) {
                    Text("Отмена", color = Muted)
                }
            }
        )
    }
}

/**
 * Отдаёт файл в системную шторку. Через FileProvider: прямой `file://` с Android 7
 * роняет получателя с FileUriExposedException.
 *
 * Результат отправки Android не возвращает — поэтому «очистить» отдельной кнопкой,
 * вручную, а не следом за этим вызовом.
 */
private fun shareComplaints(context: Context, file: File) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Crnogorski: жалобы на задания")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Отправить отчёт"))
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
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
private fun SecondaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Surface2,
            contentColor = Paper,
            disabledContainerColor = Surface1,
            disabledContentColor = Muted
        )
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}
