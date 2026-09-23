package com.crnogorski.trener.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crnogorski.trener.data.LessonRepository
import com.crnogorski.trener.speech.CorpusCheck
import com.crnogorski.trener.speech.CorpusRoute
import com.crnogorski.trener.speech.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Прогон корпуса — инструмент разработчика, а не пользователя.
 *
 * Синтезатор читает весь наш текст, распознаватель слушает, а расслышанное
 * сверяется с оригиналом; зачем это нужно и чего не доказывает, написано в
 * [CorpusCheck]. Экран отдельный, а не строка в настройках, по двум причинам:
 * прогон идёт полтысячи заходов и требует, чтобы экран не гас, — а гаснущий
 * экран уносит с собой распознавание.
 *
 * Состояние живёт **в самом экране**, а не в `AppViewModel`: прогон это разовое
 * дело, переживать уходы с экрана он не должен и не может — уйдя, его прерывают
 * намеренно.
 */
@Composable
fun CorpusScreen(speaker: Speaker, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = remember { MainScope() }
    val check = remember {
        CorpusCheck(context.applicationContext, LessonRepository(context), speaker, scope)
    }
    val state by check.state.collectAsStateWithLifecycle()

    // Экран не должен гаснуть: распознавание закрывается вместе с приложением,
    // ушедшим в паузу, и часовой прогон оборвался бы на первой же минуте.
    val window = remember { (context as? android.app.Activity)?.window }
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            check.release()
            scope.cancel()
        }
    }

    // Пропускать зачтённое — по умолчанию да: корпус растёт понемногу, и
    // почти всегда проверить надо ровно прибавку. Полный прогон нужен редко,
    // когда меняется сам прибор — например, обновился синтезатор.
    var onlyNew by rememberSaveable { mutableStateOf(true) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) check.start(onlyNew) }

    fun launch() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            check.start(onlyNew)
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
            Text("ПРОВЕРКА", style = MaterialTheme.typography.labelSmall, color = Accent)
            Spacer(Modifier.height(6.dp))
            Text("Прогон корпуса", style = MaterialTheme.typography.displaySmall, color = Paper)
            Spacer(Modifier.height(16.dp))
            Text(
                "Синтезатор читает весь наш текст, распознаватель слушает, " +
                    "расслышанное сверяется с оригиналом. Провал — улика про текст; " +
                    "зачёт про живой голос не говорит ничего.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
            Spacer(Modifier.height(20.dp))

            Card {
                if (state.running) {
                    val share =
                        if (state.total == 0) 0f else state.done.toFloat() / state.total
                    Text(
                        "${state.done} из ${state.total}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Paper
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { share },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Accent,
                        trackColor = Surface2
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Не прошло: ${state.failed}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.failed > 0) Crimson else Muted
                    )
                    if (state.last.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.last,
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    }
                } else {
                    Text(
                        "Телефон будет говорить и слушать сам. Оставь его на столе " +
                            "в тихой комнате, звук не убирай.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )
                }

                state.route?.let { route ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when (route) {
                            CorpusRoute.File -> "Путь: через файл — комната не мешает"
                            CorpusRoute.Air -> "Путь: по воздуху — нужна тишина и громкость"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent
                    )
                }

                if (state.note.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Paper
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            if (state.running) {
                PrimaryButton("Остановить") { check.stop() }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Остановленный прогон всё равно сохранит отчёт по пройденному.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onlyNew = !onlyNew }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = onlyNew,
                        onCheckedChange = { onlyNew = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Accent, checkmarkColor = Ink, uncheckedColor = Muted
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Только непроверенное",
                        style = MaterialTheme.typography.titleMedium,
                        color = Paper,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (onlyNew) {
                        "Зачтённое прошлым отчётом пропускается — обычно это " +
                            "минуты вместо часа. Провалы гоняются заново всегда."
                    } else {
                        "Весь корпус целиком, около часа. Нужно, когда сменился " +
                            "сам прибор: обновился синтезатор или распознаватель."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Прогнать корпус") { launch() }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) { content() }
}
