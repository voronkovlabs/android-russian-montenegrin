package com.crnogorski.trener.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.crnogorski.trener.data.Stress

/**
 * Черногорское слово с жирной ударной буквой.
 *
 * Метка ставится **оформлением, а не знаком**: `vòda` рядом с `voda` — это
 * приглашение начать печатать `ò`, а у нас диакритика значима (č/ć/š/ž/đ
 * различают слова) и попадает в сверку ответа. Жирная буква не меняет строку
 * вовсе, поэтому её нельзя ни скопировать в ответ, ни спутать с буквой.
 *
 * Где уверенности нет, [Stress.of] отдаёт `null`, и слово печатается как было —
 * без метки, а не с догадкой. Отсутствие метки читается как «мы не знаем», и
 * это честнее, чем половина слов с неверным ударением.
 */
fun stressed(word: String): AnnotatedString {
    val at = Stress.of(word) ?: return AnnotatedString(word)
    return buildAnnotatedString {
        append(word.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(word.substring(at, at + 1))
        }
        append(word.substring(at + 1))
    }
}
