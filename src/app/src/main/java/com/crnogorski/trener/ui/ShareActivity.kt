package com.crnogorski.trener.ui

import android.content.Intent
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
import com.crnogorski.trener.data.Cirilica
import com.crnogorski.trener.data.Complaint
import com.crnogorski.trener.data.ComplaintStore
import com.crnogorski.trener.data.Config
import com.crnogorski.trener.data.MY_PHRASE_REASON
import com.crnogorski.trener.net.GithubIssues
import com.crnogorski.trener.notify.Replies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * «Поделиться» и «выделить текст» — запись прямо в «мои фразы».
 *
 * Идея владельца (144): он переводит фразу в Google Translate, и хочет отдать
 * её приложению одним нажатием, не переписывая руками. Перевод оттуда приходит
 * **сербской кириллицей** (русско-сербский переводчик другого не отдаёт), а
 * курс весь латиницей — поэтому по дороге текст переводится в латиницу,
 * см. [Cirilica].
 *
 * ## Почему это своя Activity, а не главная
 *
 * Повесить `ACTION_SEND` на `MainActivity` было бы на строку короче и заметно
 * хуже. Поделиться можно посреди урока — и тогда главный экран пересоздался бы
 * поверх задания; а после переустановки «поделиться» упёрлось бы в замок ключа,
 * хотя записи идей от ключа не зависят вовсе. Отдельное окно висит поверх
 * чужого приложения, пишет строку и закрывается: состояния приложения оно не
 * трогает и ключа не спрашивает.
 *
 * Два входа, и второй дешевле первого: `ACTION_SEND` — это кнопка «Поделиться»,
 * `ACTION_PROCESS_TEXT` — пункт в меню выделенного текста в любом приложении.
 *
 * ## Почему текст показывается, а не сохраняется молча
 *
 * Перевод письма машинный, и один случай он разобрать не может: русская фраза
 * из одних только общих букв («он дома») выглядит как сербская и станет «on
 * doma». Показанный текст это чинит человеком за секунду; молчаливая запись
 * положила бы мусор в стопку, которую потом разбирают неделями.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = shared(intent)?.trim().orEmpty()
        if (shared.isEmpty()) {
            finish()
            return
        }
        // Карта имён живёт в настройках курса: без неё issue уедет без имени
        // человека. Читается локальная копия, сети это не требует.
        Config.load(this)

        setContent {
            CrnogorskiTheme {
                ShareDialog(
                    incoming = shared,
                    onSave = ::save,
                    onCancel = ::finish
                )
            }
        }
    }

    private fun shared(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT ->
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    /**
     * Записать и закрыться.
     *
     * Окно закрывается **раньше**, чем строка ложится на диск, и это осознанно:
     * человек в чужом приложении, и держать его перед нашим диалогом ради
     * файловой записи незачем. Поэтому работа уходит в [queue] — область,
     * которая не умирает вместе с экраном.
     *
     * Отправка пробуется сразу, как и у записей из самого приложения. Не вышло
     * (нет сети, убили процесс) — строка осталась в очереди и уедет при
     * следующем запуске приложения, как уезжает всё остальное.
     */
    private fun save(text: String) {
        val body = text.trim()
        if (body.isEmpty()) {
            finish()
            return
        }
        val ctx = applicationContext
        Toast.makeText(ctx, "Фраза записана", Toast.LENGTH_SHORT).show()
        finish()

        queue.launch {
            val store = ComplaintStore(ctx)
            store.append(
                Complaint(
                    ts = store.now(),
                    exerciseId = "",
                    lessonId = "",
                    type = "",
                    reason = MY_PHRASE_REASON,
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
        /**
         * Своя область корутин: экран закрывается сразу, а `lifecycleScope`
         * вместе с ним отменил бы и запись. Живёт столько же, сколько процесс;
         * умрёт раньше времени — строка просто останется в очереди.
         */
        private val queue = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

@Composable
private fun ShareDialog(
    incoming: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    val converted = Cirilica.latin(incoming)
    var text by rememberSaveable { mutableStateOf(converted) }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Surface1,
        titleContentColor = Paper,
        textContentColor = Muted,
        title = { Text("Моя фраза") },
        text = {
            Column {
                Text(
                    if (converted != incoming) {
                        "Кириллица переведена в латиницу. Форма осталась как в " +
                            "переводчике: экавицу в иекавицу машина не переводит."
                    } else {
                        "Записывается в «мои фразы»: то, что понадобилось сказать, " +
                            "а нечем."
                    },
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
                // Переключатель вместо угадывания.
                //
                // Одна пара случаев машине не по зубам: русская фраза из
                // одних общих букв («Привет, как дела?») выглядит как
                // сербская, а сербская без ј, љ, њ, ђ, ћ, џ («Молим вас,
                // попуните формулар») — как русская. По умолчанию переводим
                // в латиницу, потому что ради этого всё и затевалось, а
                // вернуть исходное — одно нажатие.
                if (converted != incoming) {
                    TextButton(
                        onClick = { text = if (text == converted) incoming else converted }
                    ) {
                        Text(
                            if (text == converted) "Вернуть кириллицу"
                            else "Перевести в латиницу",
                            color = Accent,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text("Записать", color = if (text.isNotBlank()) Accent else Muted)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Отмена", color = Muted) }
        }
    )
}
