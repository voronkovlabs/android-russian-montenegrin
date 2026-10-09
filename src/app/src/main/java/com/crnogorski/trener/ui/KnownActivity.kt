package com.crnogorski.trener.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.crnogorski.trener.data.AppDb
import com.crnogorski.trener.data.Config
import com.crnogorski.trener.data.VocabKind
import com.crnogorski.trener.data.VocabRepository
import com.crnogorski.trener.data.WidgetWords
import com.crnogorski.trener.srs.Scheduler
import com.crnogorski.trener.widget.FlipWidget
import com.crnogorski.trener.widget.WordWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Подтверждение «это слово я уже знаю» — с виджета-перевёртыша (4.7).
 *
 * Виджет сам диалогов показывать не умеет, поэтому это окно поверх домашнего
 * экрана, устроенное как «Поделиться» ([ShareActivity]): прозрачное, вне
 * списка недавних, спрашивает и закрывается.
 *
 * Подтверждение — требование владельца, и довод за ним сильный: кнопка
 * маленькая и стоит на домашнем экране, где пальцы ходят весь день, а цена
 * случайного касания — слово, пропавшее из занятий на два месяца.
 *
 * Что делается со словом — [Scheduler.known]: счёт верных ответов поднимается
 * до «выучено», слово уходит из пула, и на его место занятие возьмёт новое;
 * через два месяца его спросят один раз. Карточек две — значение и обратный
 * перевод: «знаю слово» говорит про обе стороны.
 *
 * **Подтверждение можно отключить галочкой «Больше не спрашивать»** (4.44,
 * Катя: «мне приходится часто нажимать на эту кнопку, чтобы добраться до
 * новых слов, и каждый раз подтверждать мучительно»). Тогда окно не
 * появляется вовсе — ни здесь, ни на перевёртышах в приложении: отметка
 * ставится сразу, а о сделанном говорит всплывающая строка. Вернуть вопрос
 * можно галочкой в настройках ([confirm]).
 */
class KnownActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lemma = intent.getStringExtra(LEMMA).orEmpty()
        val word = intent.getStringExtra(WORD).orEmpty().ifEmpty { lemma }
        if (lemma.isEmpty()) {
            finish()
            return
        }
        // Сроки и порог «выучено» живут в настройках курса: без них в свежем
        // процессе сработали бы умолчания из кода, а не то, что задано.
        Config.load(this)

        if (!confirm(this)) {
            mark(lemma, word)
            return
        }

        setContent {
            CrnogorskiTheme {
                var never by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = ::finish,
                    containerColor = Surface1,
                    titleContentColor = Paper,
                    textContentColor = Muted,
                    title = { Text("${FlipWidget.KNOWN} Уже знаю") },
                    text = {
                        Column {
                            Text(
                                buildAnnotatedString {
                                    append("Отметить ")
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Paper)) {
                                        append(word)
                                    }
                                    append(
                                        " выученным? Слово уйдёт из занятий, на его место " +
                                            "придёт новое. Через два месяца его спросят один раз " +
                                            "— проверить, что оно правда держится."
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            NeverAsk(never) { never = it }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (never) setConfirm(applicationContext, false)
                            mark(lemma, word)
                        }) {
                            Text("Знаю", color = Accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = ::finish) { Text("Отмена", color = Muted) }
                    }
                )
            }
        }
    }

    /**
     * Записать и закрыться. Окно закрывается сразу, работа уходит в [queue] —
     * по тому же доводу, что в [ShareActivity]: человек на домашнем экране, и
     * держать его ради записи в базу незачем.
     */
    private fun mark(lemma: String, word: String) {
        val ctx = applicationContext
        Toast.makeText(ctx, "$word — в выученных", Toast.LENGTH_SHORT).show()

        // Из списка виджетов слово убирается **до** закрытия окна и до базы:
        // это запись в настройки, она мгновенная, а база в свежем процессе
        // открывается не сразу. Окно закрыто — процесс становится кэшированным
        // и на Xiaomi может не дожить до конца корутины; тогда отметка
        // потеряется, но слово хотя бы не будет мелькать как ни в чём не
        // бывало. Список пересоберётся при открытии приложения.
        WidgetWords.remove(ctx, lemma)
        finish()

        queue.launch {
            val dao = AppDb.get(ctx).dao()
            val now = System.currentTimeMillis()
            for (kind in listOf(VocabKind.Meaning, VocabKind.Recall)) {
                val id = VocabRepository.cardId(lemma, kind)
                dao.upsertCard(Scheduler.known(dao.card(id), id, VocabRepository.LESSON_ID, now))
            }
            WordWidget.redraw(ctx)
            FlipWidget.redraw(ctx)
        }
    }

    companion object {
        const val LEMMA = "lemma"
        const val WORD = "word"

        private const val CONFIRM_KEY = "known_confirm"

        /** Спрашивать ли подтверждение у 🧠 — и в виджете, и в приложении. */
        fun confirm(context: Context): Boolean =
            context.getSharedPreferences("crnogorski", Context.MODE_PRIVATE)
                .getBoolean(CONFIRM_KEY, true)

        fun setConfirm(context: Context, value: Boolean) {
            context.getSharedPreferences("crnogorski", Context.MODE_PRIVATE)
                .edit().putBoolean(CONFIRM_KEY, value).apply()
        }

        private val queue = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * Галочка «Больше не спрашивать» под вопросом «Уже знаю» — общая для окна
 * виджета и диалога на перевёртышах в приложении. Срабатывает только вместе
 * с «Знаю»: отказ от отметки отказом от вопроса не считается.
 */
@androidx.compose.runtime.Composable
fun NeverAsk(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Accent, checkmarkColor = Ink, uncheckedColor = Muted
            )
        )
        Text("Больше не спрашивать", color = Paper, style = MaterialTheme.typography.bodyMedium)
    }
}
