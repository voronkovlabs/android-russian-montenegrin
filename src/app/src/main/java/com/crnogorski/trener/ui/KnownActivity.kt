package com.crnogorski.trener.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
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

        setContent {
            CrnogorskiTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    containerColor = Surface1,
                    titleContentColor = Paper,
                    textContentColor = Muted,
                    title = { Text("${FlipWidget.KNOWN} Уже знаю") },
                    text = {
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
                    },
                    confirmButton = {
                        TextButton(onClick = { mark(lemma, word) }) {
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
        finish()
        queue.launch {
            val dao = AppDb.get(ctx).dao()
            val now = System.currentTimeMillis()
            for (kind in listOf(VocabKind.Meaning, VocabKind.Recall)) {
                val id = VocabRepository.cardId(lemma, kind)
                dao.upsertCard(Scheduler.known(dao.card(id), id, VocabRepository.LESSON_ID, now))
            }
            // Список пересоберётся при открытии приложения, а до того виджеты
            // показывали бы отмеченное слово снова.
            WidgetWords.remove(ctx, lemma)
            WordWidget.redraw(ctx)
            FlipWidget.redraw(ctx)
        }
    }

    companion object {
        const val LEMMA = "lemma"
        const val WORD = "word"

        private val queue = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
