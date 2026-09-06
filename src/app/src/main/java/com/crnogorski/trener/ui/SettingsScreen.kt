package com.crnogorski.trener.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.crnogorski.trener.data.ProgressStore
import com.crnogorski.trener.speech.Speaker
import kotlinx.coroutines.launch
import java.io.File

/** Фраза для проверки голоса: короткая, со всеми характерными звуками. */
private const val VOICE_PROBE = "Dobar dan, kako si?"

@Composable
fun SettingsScreen(
    state: SettingsState,
    speaker: Speaker,
    prepareReport: suspend () -> File?,
    onArchive: () -> Unit,
    onFolder: (Uri) -> Unit,
    onForgetFolder: () -> Unit,
    onSaveNow: () -> Unit,
    onSaveTo: (Uri) -> Unit,
    onRestore: (Uri) -> Unit,
    onRestoreLocal: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmArchive by remember { mutableStateOf(false) }
    var voiceChecked by remember { mutableStateOf(false) }

    // На урезанных прошивках выбор файлов может отсутствовать вовсе: тогда
    // launch бросает ActivityNotFoundException, и уронить приложение из-за
    // резервного копирования было бы совсем нелепо.
    var pickerMissing by remember { mutableStateOf(false) }
    fun safely(block: () -> Unit) {
        runCatching(block).onFailure { pickerMissing = true }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolder(uri) }

    val pickSaveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) onSaveTo(uri) }

    val pickRestoreFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onRestore(uri) }

    // Системная «назад» должна возвращать к списку уроков, а не закрывать приложение.
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Muted
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text("НАСТРОЙКИ", style = MaterialTheme.typography.labelSmall, color = Gold)
            Spacer(Modifier.height(6.dp))
            Text("Прогресс", style = MaterialTheme.typography.displaySmall, color = Paper)
            Spacer(Modifier.height(16.dp))

            Text(
                if (state.progressFolder != null) {
                    "После каждой сессии копия пишется в «${state.progressFolder}» и на сам " +
                        "телефон. Файл в папке переживёт удаление приложения, копия на " +
                        "телефоне — нет."
                } else {
                    "Копия пишется на сам телефон после каждой сессии, но удаление " +
                        "приложения её унесёт. Выбери папку — хоть в облаке, хоть в памяти " +
                        "телефона, — и копия переживёт переустановку."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            if (state.progressLastSave != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Последняя копия: ${state.progressLastSave}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.progressLastSave.contains("не вышло") ||
                        state.progressLastSave.contains("не удалось")
                    ) Crimson else Muted
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                state.progressLocal,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
            Spacer(Modifier.height(14.dp))

            if (state.progressFolder == null) {
                PrimaryAction(text = "Выбрать папку для копии") { safely { pickFolder.launch(null) } }
            } else {
                PrimaryAction(text = "Сохранить сейчас", onClick = onSaveNow)
                Spacer(Modifier.height(10.dp))
                SecondaryAction(text = "Другая папка") { safely { pickFolder.launch(null) } }
                Spacer(Modifier.height(10.dp))
                SecondaryAction(text = "Не сохранять больше", onClick = onForgetFolder)
            }

            Spacer(Modifier.height(10.dp))
            SecondaryAction(
                text = "Восстановить с телефона",
                enabled = state.progressLocalExists,
                onClick = onRestoreLocal
            )
            Spacer(Modifier.height(10.dp))
            SecondaryAction(text = "Восстановить из файла") {
                safely { pickRestoreFile.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }
            Spacer(Modifier.height(10.dp))
            SecondaryAction(text = "Сохранить в отдельный файл") {
                safely { pickSaveFile.launch(ProgressStore.FILE_NAME) }
            }

            if (pickerMissing) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Системный выбор файлов недоступен. Копия на телефоне при этом " +
                        "пишется, забрать её можно файловым менеджером.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Crimson
                )
            }

            Spacer(Modifier.height(36.dp))
            Text("ОТЧЁТ", style = MaterialTheme.typography.labelSmall, color = Gold)
            Spacer(Modifier.height(6.dp))
            Text("Жалобы на задания", style = MaterialTheme.typography.displaySmall, color = Paper)
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
            ) {
                scope.launch { prepareReport()?.let { shareComplaints(context, it) } }
            }

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
 * Приходит сюда не сам `complaints.jsonl`, а копия с именем вида
 * `complaints-<устройство>-<UTC>.jsonl`: в папке загрузок на той стороне
 * одинаковые имена превращаются в «complaints (2).jsonl» и перестают
 * различаться.
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
        putExtra(Intent.EXTRA_SUBJECT, "Crnogorski: ${file.name}")
        putExtra(Intent.EXTRA_TITLE, file.name)
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
