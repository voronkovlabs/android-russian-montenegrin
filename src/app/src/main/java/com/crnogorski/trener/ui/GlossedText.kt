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
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

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
    color: Color
) {
    if (gloss.isEmpty()) {
        Text(text, style = style, color = color)
        return
    }

    var picked by remember(text) { mutableStateOf<Pair<String, String>?>(null) }

    val annotated = buildAnnotatedString {
        var cursor = 0
        WORD.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            cursor = match.range.last + 1

            val word = match.value
            val meaning = gloss[word.lowercase()]
            if (meaning == null) {
                append(word)
            } else {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = word,
                        styles = TextLinkStyles(
                            style = SpanStyle(textDecoration = TextDecoration.Underline)
                        )
                    ) { picked = word to meaning }
                ) { append(word) }
            }
        }
        append(text.substring(cursor))
    }

    Text(annotated, style = style, color = color)

    val shown = picked
    if (shown != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "${shown.first} — ${shown.second}",
            style = MaterialTheme.typography.bodyMedium,
            color = Accent
        )
    }
}

/** Слово для подсказки: буквы и дефис, цифры и знаки не в счёт. */
private val WORD = Regex("[\\p{L}-]+")
