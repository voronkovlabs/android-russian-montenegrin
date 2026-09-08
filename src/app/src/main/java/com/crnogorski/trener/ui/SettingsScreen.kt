package com.crnogorski.trener.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.app.TimePickerDialog
import androidx.core.content.FileProvider
import android.app.NotificationManager
import android.provider.Settings
import com.crnogorski.trener.data.Pace
import com.crnogorski.trener.speech.Listener
import com.crnogorski.trener.notify.Reminder
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
    onCache: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onDailyMinutes: (Int) -> Unit,
    onShowSplash: () -> Unit,
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
            Text("НАСТРОЙКИ", style = MaterialTheme.typography.labelSmall, color = Accent)
            Spacer(Modifier.height(6.dp))
            Text("Занятие", style = MaterialTheme.typography.displaySmall, color = Paper)
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ежедневное задание",
                    style = MaterialTheme.typography.titleMedium,
                    color = Paper,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { onDailyMinutes(state.dailyMinutes - MINUTE_STEP) },
                    enabled = state.dailyMinutes > Pace.MIN_MINUTES
                ) { Text("−", color = Accent, style = MaterialTheme.typography.titleLarge) }
                Text(
                    "${state.dailyMinutes} мин",
                    style = MaterialTheme.typography.titleMedium,
                    color = Paper
                )
                TextButton(
                    onClick = { onDailyMinutes(state.dailyMinutes + MINUTE_STEP) },
                    enabled = state.dailyMinutes < Pace.MAX_MINUTES
                ) { Text("+", color = Accent, style = MaterialTheme.typography.titleLarge) }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Пятнадцать минут каждый день дают больше, чем два часа раз в неделю: " +
                    "решает регулярность, а не длина. Сколько заданий в эти минуты влезает, " +
                    "приложение считает по замерам — сколько у тебя на самом деле уходит " +
                    "на задание каждого типа. Время идёт с любого занятия, не только с " +
                    "ежедневного.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            Spacer(Modifier.height(18.dp))
            ReminderRow()

            Spacer(Modifier.height(28.dp))
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
            Text("ОТЧЁТ", style = MaterialTheme.typography.labelSmall, color = Accent)
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
                    color = if (state.complaintCount > 0) Accent else Muted
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
            Text("ПРОВЕРКА", style = MaterialTheme.typography.labelSmall, color = Accent)
            Spacer(Modifier.height(6.dp))
            Text("Память ответов", style = MaterialTheme.typography.displaySmall, color = Paper)
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onCache(!state.cacheEnabled) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = state.cacheEnabled,
                    onCheckedChange = onCache,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Accent,
                        checkmarkColor = Ink,
                        uncheckedColor = Muted
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Помнить засчитанные ответы",
                    style = MaterialTheme.typography.titleMedium,
                    color = Paper
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Ответ, который Claude уже засчитал, второй раз в сеть не уходит: " +
                    "запрос тот же самый, значит и вердикт тот же, — но появляется он " +
                    "мгновенно и без интернета. Ошибочные ответы не запоминаются никогда, " +
                    "а жалоба на задание стирает всё, что по нему запомнено.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatBox(Modifier.weight(1f), "ИЗ ПАМЯТИ", state.cacheHits, Accent)
                StatBox(Modifier.weight(1f), "СПРОШЕНО", state.cacheAsked, Paper)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Считаются только показанные вердикты: сорванная проверка не в счёт. " +
                    "Кнопка ниже обнуляет и счёт тоже.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            Spacer(Modifier.height(14.dp))
            SecondaryAction(
                text = "Забыть запомненное (${state.cacheCount})",
                enabled = state.cacheCount > 0 || state.cacheHits > 0 || state.cacheAsked > 0,
                onClick = onClearCache
            )

            Spacer(Modifier.height(36.dp))

            Text("РЕЧЬ", style = MaterialTheme.typography.labelSmall, color = Accent)
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

            Spacer(Modifier.height(20.dp))
            RecognitionRow()

            Spacer(Modifier.height(20.dp))
            SplashRow(onShowSplash)

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
                }) { Text("Отложить", color = Accent) }
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

/** Одно число под подписью — плитка вроде той, что считает жалобы. */
/**
 * Распознавание речи: локальный движок и гудки записи.
 *
 * Обе настройки живут здесь, а не в модели, по той же причине, что и
 * напоминание: их читает только `Listener`, и тащить их через `AppViewModel`
 * значило бы связать половину экрана ради двух флагов.
 *
 * Строка про гудки — не кнопка, а объяснение. Гудки начала и конца записи
 * играет системный движок, у `SpeechRecognizer` настройки на это нет вовсе, и
 * единственное, что в нашей власти, — заглушить поток, в который он их шлёт.
 * Музыку Android даёт глушить всегда; звонок, уведомления и системные звуки —
 * только с доступом к «Не беспокоить». Поэтому если гудки слышны и после
 * 1.28, значит они не в музыке, и выбор тут человека: дать доступ или терпеть.
 */
@Composable
private fun RecognitionRow() {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    val available = remember { runCatching { listener.onDeviceAvailable() }.getOrDefault(false) }
    var onDevice by remember { mutableStateOf(listener.onDevice) }

    val notifications = context.getSystemService(NotificationManager::class.java)
    var dndGranted by remember {
        mutableStateOf(
            runCatching { notifications.isNotificationPolicyAccessGranted }.getOrDefault(false)
        )
    }

    Text("РАСПОЗНАВАНИЕ", style = MaterialTheme.typography.labelSmall, color = Accent)
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = available) {
                onDevice = !onDevice
                listener.onDevice = onDevice
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = onDevice,
            enabled = available,
            onCheckedChange = {
                onDevice = it
                listener.onDevice = it
            },
            colors = CheckboxDefaults.colors(
                checkedColor = Accent, checkmarkColor = Ink, uncheckedColor = Muted
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Распознавать без сети",
            style = MaterialTheme.typography.titleMedium,
            color = if (available) Paper else Muted,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        if (available) {
            "Просит у Google локальную модель вместо облачной. Если модель sr-RS не " +
                "скачана, распознавание начнёт отвечать «язык недоступен» — тогда сними " +
                "галочку обратно. Иногда заодно пропадают гудки записи: их играет " +
                "облачный движок."
        } else {
            "Этот телефон локального распознавания не предлагает."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )

    Spacer(Modifier.height(18.dp))
    Text(
        "Гудки записи",
        style = MaterialTheme.typography.titleMedium,
        color = Paper
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (dndGranted) {
            "Доступ есть — на время записи глушатся музыка, системные звуки, " +
                "уведомления и звонок. Если гудки всё равно слышны, они не в этих потоках, " +
                "и сделать с ними нечего."
        } else {
            "Их играет системный движок, отключить их нечем — можно только заглушить " +
                "поток, в который он их шлёт. Музыку мы глушим и так, но если гудки " +
                "остались, значит они в системных звуках, а туда Android пускает только " +
                "с доступом к «Не беспокоить». Доступ нужен ровно на секунды записи."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )
    if (!dndGranted) {
        Spacer(Modifier.height(10.dp))
        SecondaryAction(text = "Дать доступ к «Не беспокоить»") {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                    )
                )
            }
            dndGranted =
                runCatching { notifications.isNotificationPolicyAccessGranted }.getOrDefault(false)
        }
    }
}

/**
 * Заставка: звук и показ без занятия.
 *
 * Кнопка «Показать заставку» нужна не пользователю, а владельцу: иначе увидеть
 * экран можно только пройдя ежедневное задание до конца, и любая правка в нём
 * проверяется через пятнадцать минут занятий.
 *
 * Галочка звука живёт в тех же настройках, что и распознавание, и читается
 * прямо на заставке — тащить её через модель ради одного флага незачем.
 */
@Composable
private fun SplashRow(onShow: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("crnogorski", Context.MODE_PRIVATE) }
    var sound by remember { mutableStateOf(prefs.getBoolean(SPLASH_SOUND_KEY, true)) }

    Text("ЗАСТАВКА", style = MaterialTheme.typography.labelSmall, color = Accent)
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                sound = !sound
                prefs.edit().putBoolean(SPLASH_SOUND_KEY, sound).apply()
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = sound,
            onCheckedChange = {
                sound = it
                prefs.edit().putBoolean(SPLASH_SOUND_KEY, it).apply()
            },
            colors = CheckboxDefaults.colors(
                checkedColor = Accent, checkmarkColor = Ink, uncheckedColor = Muted
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Звук на заставке",
            style = MaterialTheme.typography.titleMedium,
            color = Paper,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Гитарный риф играет по кругу, пока заставка открыта. В беззвучном режиме " +
            "телефона молчит в любом случае: поток музыки Android сам не глушит, и " +
            "иначе заставка грянула бы в метро.",
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )

    Spacer(Modifier.height(14.dp))
    SecondaryAction(text = "Показать заставку", onClick = onShow)
}

/** Шаг настройки длины занятия: пять минут. Минута туда-сюда ничего не решает. */
private const val MINUTE_STEP = 5

/**
 * Напоминание: галочка и время.
 *
 * Состояние читается из `Reminder` и живёт в самой строке, а не в
 * [SettingsState]: настройка эта ни на что в приложении не влияет — её
 * читает только будильник, — и тащить её через модель значило бы связать
 * половину экрана ради двух чисел.
 *
 * Время выбирается системным диалогом, а не своим: у человека уже есть
 * привычный ему двенадцати- или двадцатичетырёхчасовой выбор, и спорить с
 * ней незачем.
 */
@Composable
private fun ReminderRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Reminder.enabled(context)) }
    var hour by remember { mutableIntStateOf(Reminder.hour(context)) }
    var minute by remember { mutableIntStateOf(Reminder.minute(context)) }

    fun apply(nextOn: Boolean, nextHour: Int, nextMinute: Int) {
        on = nextOn
        hour = nextHour
        minute = nextMinute
        Reminder.set(context, nextOn, nextHour, nextMinute)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { apply(!on, hour, minute) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = on,
            onCheckedChange = { apply(it, hour, minute) },
            colors = CheckboxDefaults.colors(
                checkedColor = Accent,
                checkmarkColor = Ink,
                uncheckedColor = Muted
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Напоминать",
            style = MaterialTheme.typography.titleMedium,
            color = Paper,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            enabled = on,
            onClick = {
                TimePickerDialog(
                    context,
                    { _, h, m -> apply(true, h, m) },
                    hour,
                    minute,
                    true
                ).show()
            }
        ) {
            Text(
                "%02d:%02d".format(hour, minute),
                style = MaterialTheme.typography.titleMedium,
                color = if (on) Accent else Muted
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    Text(
        "Вечер выбран не наугад: моторный навык — а произношение это он — лучше " +
            "закрепляется при тренировке перед сном, и сон сразу после занятия держит " +
            "выученное лучше, чем сон через день бодрствования. Если за день уже " +
            "позанимались, напоминание не придёт.",
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )
}

@Composable
private fun StatBox(modifier: Modifier, label: String, value: Int, color: Color) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(8.dp))
        Text(
            "$value",
            style = MaterialTheme.typography.displaySmall,
            color = if (value > 0) color else Muted
        )
    }
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
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
