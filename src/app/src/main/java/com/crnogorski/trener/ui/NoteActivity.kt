package com.crnogorski.trener.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crnogorski.trener.BuildConfig
import com.crnogorski.trener.data.Complaint
import com.crnogorski.trener.data.ComplaintStore
import com.crnogorski.trener.data.Config
import com.crnogorski.trener.data.NOTE_REASON
import com.crnogorski.trener.data.VocabKind
import com.crnogorski.trener.data.VocabRepository
import com.crnogorski.trener.net.GithubIssues
import com.crnogorski.trener.notify.Replies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Жалоба на слово прямо с домашнего экрана (4.16, просьба владельца).
 *
 * Флажок на перевёртыше стоит рядом с флагом страны и значит ровно то же, что
 * флажок в шапке приложения: «тут что-то не так». Окно устроено как
 * [ShareActivity] и [KnownActivity] — поверх чужого экрана, вне списка
 * недавних, спросило и закрылось.
 *
 * **Причина — `note`, свободный текст без категории.** Категории (`эталон
 * неверен`, `опечатка`, `плохо слышно`) придуманы для заданий урока и говорят,
 * **что чинить**; про словарное слово они молчат, а выпадающий список на
 * карточке в две клетки — это трение там, где жалоба и так пишется редко.
 *
 * **Лемма при этом не теряется:** `exerciseId` заполняется идентификатором
 * карточки значения, то есть в заголовке issue будет видно слово. Это ровно
 * тот же приём, что у жалобы из урока: причина `note` говорит «смысл в
 * тексте», а `exerciseId` — «вот где это случилось».
 */
class NoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lemma = intent.getStringExtra(LEMMA).orEmpty()
        val word = intent.getStringExtra(WORD).orEmpty().ifEmpty { lemma }
        // Карта имён живёт в настройках курса: без неё issue уедет без имени
        // человека. Читается локальная копия, сети это не требует.
        Config.load(this)

        setContent {
            CrnogorskiTheme {
                NoteDialog(
                    word = word,
                    onSend = { send(lemma, it) },
                    onCancel = ::finish
                )
            }
        }
    }

    /**
     * Записать и закрыться — по тому же доводу, что в [ShareActivity]: человек
     * на домашнем экране, держать его ради файловой записи незачем. Отправка
     * пробуется сразу; не вышло — строка осталась в очереди и уедет при
     * следующем запуске приложения.
     */
    private fun send(lemma: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) {
            finish()
            return
        }
        val ctx = applicationContext
        Toast.makeText(ctx, "Жалоба записана", Toast.LENGTH_SHORT).show()
        finish()

        queue.launch {
            val store = ComplaintStore(ctx)
            store.append(
                Complaint(
                    ts = store.now(),
                    exerciseId = if (lemma.isEmpty()) ""
                    else VocabRepository.cardId(lemma, VocabKind.Meaning),
                    lessonId = if (lemma.isEmpty()) "" else VocabRepository.LESSON_ID,
                    type = "",
                    reason = NOTE_REASON,
                    note = body,
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME
                )
            )
            val issues = GithubIssues()
            if (issues.configured) {
                val device = store.deviceTag()
                val person = store.person()
                store.flush { complaint, raw ->
                    issues.create(complaint, raw, device, person)
                        .onSuccess { Replies.remember(ctx, it) }
                }
            }
        }
    }

    companion object {
        const val LEMMA = "lemma"
        const val WORD = "word"

        private val queue = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

@Composable
private fun NoteDialog(
    word: String,
    onSend: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Surface1,
        titleContentColor = Paper,
        textContentColor = Muted,
        title = { Text("Жалоба на слово") },
        text = {
            Column {
                Text(
                    if (word.isEmpty()) "Что не так?"
                    else "«$word» — что с ним не так?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Surface2,
                        focusedTextColor = Paper,
                        unfocusedTextColor = Paper,
                        cursorColor = Accent
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) {
                Text("Отправить", color = if (text.isNotBlank()) Accent else Muted)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Отмена", color = Muted) }
        }
    )
}
