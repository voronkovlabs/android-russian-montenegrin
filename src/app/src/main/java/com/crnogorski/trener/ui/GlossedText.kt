package com.crnogorski.trener.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.crnogorski.trener.data.LocalCheck
import com.crnogorski.trener.data.Stress
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle

/**
 * Текст задания, где знакомые слова подчёркнуты и по нажатию показывают перевод
 * строкой ниже.
 *
 * Строкой, а не всплывающей подсказкой: на телефоне палец закрывает ровно то
 * место, куда нажали, и перевод под текстом читается спокойно, пока не нажали
 * следующее слово. Слов, которых нет в словаре, подчёркивание не касается —
 * так сразу видно, на что нажимать бесполезно.
 */
@Composable
fun GlossedText(
    text: String,
    gloss: Map<String, String>,
    style: TextStyle,
    color: Color,
    /**
     * Произнести нажатое слово.
     *
     * По просьбе владельца (20.09.2026): «надо учиться произносить
     * это с правильными ударениями». Жирная буква говорит, где ударение,
     * но не говорит, как оно звучит, а у половины слов метки нет вовсе:
     * где мы не уверены, там молчим. Голос закрывает и то, и другое.
     */
    onWord: ((String) -> Unit)? = null,
    /**
     * Номера слов, которые движок расслышал, — их показываем зелёным.
     *
     * Счёт «совпало 5 из 7» говорит, сколько, но не говорит, **какие**, а
     * перечитывать вслепую бесполезно. Считает их `LocalCheck.matchedWords`
     * по тому же выражению [LocalCheck.SHOWN_WORD], так что номера сходятся.
     */
    green: Set<Int> = emptySet()
) {
    val mark = markStyle()
    if (gloss.isEmpty() && onWord == null && green.isEmpty()) {
        Text(stressedPhrase(text), style = style, color = color)
        return
    }

    var picked by remember(text) { mutableStateOf<Pair<String, String>?>(null) }

    val annotated = buildAnnotatedString {
        var cursor = 0
        // Предыдущее слово нужно ударению: после проклитики оно уезжает на неё.
        var previous = ""
        var index = -1
        LocalCheck.SHOWN_WORD.findAll(text).forEach { match ->
            index++
            append(text.substring(cursor, match.range.first))
            cursor = match.range.last + 1

            val word = match.value
            val meaning = gloss[word.lowercase()]
            val at = Stress.inPhrase(word, previous)
            previous = word
            // Расслышанное слово зелёное целиком, вместе с ударной буквой:
            // два цвета в одном слове спорили бы за внимание, а ударение
            // остаётся видным по жирной букве.
            val heardIt = index in green
            val wordMark = if (heardIt) mark.copy(color = Jade) else mark
            // Нажимается **любое** слово, а не только знакомое словарю.
            //
            // До 2.1 было наоборот, и довод был честный: сразу видно, на что
            // нажимать бесполезно. Он перестал работать, когда у нажатия
            // появился второй смысл — услышать слово: произношение нужно и там,
            // где перевода нет, а в сказках таких слов большинство.
            //
            // Подчёркивание при этом осталось знаком перевода, а не нажимаемости:
            // иначе подчёркнутым стал бы весь текст и перестал что-либо значить.
            if (meaning == null && onWord == null) {
                // Ни перевода, ни голоса — нажимать не на что. Так остаётся в
                // уроках: там слово без подсказки отвечало бы «перевода нет», и
                // это шум, а не помощь.
                if (heardIt) {
                    withStyle(SpanStyle(color = Jade)) {
                        if (at == null) append(word) else appendMarked(word, at, wordMark)
                    }
                } else {
                    if (at == null) append(word) else appendMarked(word, at, mark)
                }
            } else {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = word,
                        // **Подчёркнуто — значит нажимается**, и никакого
                        // второго смысла у черты нет.
                        //
                        // В 2.1 подчёркивание оставили знаком перевода, а
                        // нажимались уже все слова. Вышло хуже обоих вариантов:
                        // черта перестала предсказывать, что будет по нажатию,
                        // и человек не знал, где можно послушать слово, а где
                        // нет. Владелец это и увидел: «клик по слову работает,
                        // но оно не подчёркнуто».
                        //
                        // Правило теперь одно на всё приложение и держится само
                        // собой: в уроках нажимаются только знакомые словарю
                        // слова — они и подчёркнуты; в историях нажимается
                        // всякое — подчёркнуто всякое. Есть ли перевод, говорит
                        // строка под текстом, а не оформление.
                        styles = TextLinkStyles(
                            style = SpanStyle(textDecoration = TextDecoration.Underline)
                        )
                    ) {
                        picked = word to meaning.orEmpty()
                        onWord?.invoke(word)
                    }
                ) {
                    if (heardIt) {
                        withStyle(SpanStyle(color = Jade)) {
                            if (at == null) append(word) else appendMarked(word, at, wordMark)
                        }
                    } else {
                        if (at == null) append(word) else appendMarked(word, at, mark)
                    }
                }
            }
        }
        append(text.substring(cursor))
    }

    Text(annotated, style = style, color = color)

    val shown = picked
    if (shown != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            // У слова без перевода строка всё равно появляется: молчание в
            // ответ на нажатие читается как поломка, а не как «перевода нет».
            if (shown.second.isBlank()) "${shown.first} — перевода нет"
            else "${shown.first} — ${shown.second}",
            style = MaterialTheme.typography.bodyMedium,
            color = Accent
        )
    }
}

