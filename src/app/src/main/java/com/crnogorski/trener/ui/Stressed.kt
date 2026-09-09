package com.crnogorski.trener.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.crnogorski.trener.data.Stress

/**
 * Черногорское слово с выделенной ударной буквой.
 *
 * Метка ставится **оформлением, а не знаком**: `vòda` рядом с `voda` — это
 * приглашение начать печатать `ò`, а у нас диакритика значима (č/ć/š/ž/đ
 * различают слова) и попадает в сверку ответа. Оформление строку не меняет
 * вовсе, поэтому его нельзя ни скопировать в ответ, ни спутать с буквой.
 *
 * Выделение — **жирный плюс цвет акцента**, и это по жалобе: одной жирности в
 * тексте задания не видно. Начертания у гарнитуры два, разница между ними на
 * телефоне в одну букву не читается совсем, а цвет виден сразу и работает даже
 * в мелком шрифте эталона. Подчёркивание занято подсказками по словам, и второй
 * смысл у той же черты был бы хуже, чем никакого.
 *
 * Где уверенности нет, [Stress.of] отдаёт `null`, и слово печатается как было —
 * без метки, а не с догадкой. Отсутствие метки читается как «мы не знаем», и
 * это честнее, чем половина слов с неверным ударением.
 */
@Composable
fun stressed(word: String): AnnotatedString {
    val mark = markStyle()
    val at = Stress.of(word) ?: return AnnotatedString(word)
    return buildAnnotatedString { appendMarked(word, at, mark) }
}

/**
 * Фраза, размеченная по словам.
 *
 * Внутри фразы решает [Stress.inPhrase], а не [Stress.of]: энклитика ударения
 * не имеет вовсе, а после проклитики оно уезжает на неё — обе оговорки
 * обязательны, иначе разметка врёт в каждой второй строке.
 *
 * Русский текст сюда попадает наравне с черногорским и остаётся нетронутым сам
 * собой: слоговые вершины считаются по латинским гласным, у кириллицы их нет.
 */
@Composable
fun stressedPhrase(text: String): AnnotatedString {
    val mark = markStyle()
    return buildAnnotatedString {
        var cursor = 0
        var previous = ""
        WORDS.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            cursor = match.range.last + 1
            val word = match.value
            val at = Stress.inPhrase(word, previous)
            previous = word
            if (at == null) append(word) else appendMarked(word, at, mark)
        }
        append(text.substring(cursor))
    }
}

/**
 * Слово с меткой, добавленное к строящейся строке.
 *
 * Отдельной функцией, потому что зовут её из двух мест и из [GlossedText],
 * где слово вдобавок обёрнуто ссылкой на подсказку.
 */
fun AnnotatedString.Builder.appendMarked(word: String, at: Int, mark: SpanStyle) {
    append(word.substring(0, at))
    pushStyle(mark)
    append(word.substring(at, at + 1))
    pop()
    append(word.substring(at + 1))
}

/** Чем помечаем ударную букву. Цвет — из палитры, а не по месту. */
@Composable
fun markStyle(): SpanStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Accent)

/** Слово: буквы и дефис. Те же правила, что у подсказок по словам. */
private val WORDS = Regex("[\\p{L}-]+")
