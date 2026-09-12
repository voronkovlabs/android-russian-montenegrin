package com.crnogorski.trener.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Вид записи внутри одного диалога: подпись, подсказка и пример.
 *
 * Заведён ради идей, у которых видов стало два, но сделан общим: у жалобы он
 * один, и это ровно тот же список длиной в единицу. Так в диалоге нет ветки
 * «а если видов несколько» — есть список, и выпадашка показывается, когда в
 * нём больше одного.
 */
data class QuickKind(
    val title: String,
    val hint: String,
    val placeholder: String
)

/**
 * Жалоба, не привязанная к заданию: кнопка рядом с шестерёнкой, доступная всегда.
 *
 * Диалог, а не отдельный экран, — сознательно. Пожаловаться можно посреди
 * задания («голос читает слишком быстро», «кнопка не нажимается»), и урок при
 * этом не должен ни сбрасываться, ни перематываться: диалог рисуется поверх,
 * состояние экрана под ним остаётся нетронутым, после «Записать» урок
 * продолжается с того же места.
 *
 * Категорий тут нет в отличие от жалобы на задание: чинить по такой записи
 * нечего конкретного, весь смысл — в тексте.
 */
@Composable
fun ComplaintButton(onSave: (String) -> Unit) {
    QuickNote(
        icon = Icons.Outlined.Flag,
        description = "Пожаловаться",
        title = "Жалоба",
        kinds = listOf(
            QuickKind(
                title = "Жалоба",
                hint = "Что не так? Уйдёт в issues с меткой «заметка».",
                placeholder = "Например: голос читает слишком быстро"
            )
        )
    ) { text, _ -> onSave(text) }
}

/**
 * Идея — рядом с жалобой и тем же диалогом.
 *
 * Отличается от жалобы не механикой, а тем, что с записью потом делают: жалоба
 * говорит «сломано, почини», идея — «сделай, когда дойдут руки». Одной кнопкой
 * их не свести: разбирают их в разное время и в разном настроении, а метка
 * в issues только тогда и работает, когда её ставит человек в момент записи, а
 * не разработчик задним числом по догадке.
 *
 * **Видов идеи два**, и выбираются они выпадающим списком. «Общая» — обычное
 * «сделать бы». «Живая фраза» — как здесь говорят на самом деле: подслушанное
 * на рынке или у врача, сырьё для будущих уроков и историй. Ровно по тому же
 * доводу это отдельный вид, а не примечание в тексте: в файле «Kako ide?» и
 * «сделать кнопку побольше» выглядят одинаково, а метку задним числом не
 * восстановить.
 *
 * Записывается тем же путём, что жалобы (файл как очередь → issue), поэтому и
 * без сети не теряется.
 */
@Composable
fun IdeaButton(onSave: (String, Boolean) -> Unit) {
    QuickNote(
        icon = Icons.Outlined.Lightbulb,
        description = "Записать идею",
        title = "Идея",
        kinds = listOf(
            QuickKind(
                title = "Общая",
                hint = "Что стоит сделать? Уйдёт в issues с меткой «идея».",
                placeholder = "Например: показывать перевод по долгому нажатию"
            ),
            QuickKind(
                title = "Реальная фраза",
                hint = "Как здесь говорят на самом деле. Уйдёт с меткой «живая фраза».",
                placeholder = "Например: Kako ide? — вместо «Kako si?» на рынке"
            )
        )
    ) { text, kind -> onSave(text, kind == 1) }
}

/**
 * Общий диалог на оба случая.
 *
 * Кнопка и диалог различаются только словами, поэтому и код у них один: две
 * копии разошлись бы на первой же правке — в одной поправили бы подсказку, в
 * другой забыли.
 */
@Composable
private fun QuickNote(
    icon: ImageVector,
    description: String,
    title: String,
    kinds: List<QuickKind>,
    onSave: (String, Int) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Черновик переживает и поворот экрана, и случайную отмену — терять набранное обидно.
    var text by rememberSaveable { mutableStateOf("") }
    // Вид сбрасывается на первый после записи: по умолчанию идея общая, и
    // выбранный однажды вид не должен незаметно прилипнуть к следующей записи.
    var kind by rememberSaveable { mutableIntStateOf(0) }

    IconButton(onClick = { open = true }) {
        Icon(icon, contentDescription = description, tint = Muted)
    }

    if (!open) return
    val chosen = kinds[kind.coerceIn(kinds.indices)]

    AlertDialog(
        onDismissRequest = { open = false },
        containerColor = Surface1,
        titleContentColor = Paper,
        textContentColor = Muted,
        title = { Text(title) },
        text = {
            Column {
                if (kinds.size > 1) {
                    KindPicker(kinds = kinds, chosen = kind) { kind = it }
                    Spacer(Modifier.height(14.dp))
                }
                Text(chosen.hint, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(chosen.placeholder, color = Muted) },
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
            TextButton(
                onClick = {
                    onSave(text, kind)
                    text = ""
                    kind = 0
                    open = false
                },
                enabled = text.isNotBlank()
            ) { Text("Записать", color = if (text.isNotBlank()) Accent else Muted) }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text("Отмена", color = Muted) }
        }
    )
}

/**
 * Выпадающий список видов.
 *
 * Выпадашка, а не два чипа рядом: видов может стать больше двух (живая фраза
 * появилась третьей записью за неделю), и чипы тогда полезут в две строки, а
 * список останется той же одной строкой.
 */
@Composable
private fun KindPicker(kinds: List<QuickKind>, chosen: Int, onPick: (Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface2)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                kinds[chosen].title,
                style = MaterialTheme.typography.bodyMedium,
                color = Paper,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Outlined.ExpandMore, contentDescription = "Выбрать вид", tint = Accent)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Surface1)
        ) {
            kinds.forEachIndexed { i, k ->
                DropdownMenuItem(
                    text = {
                        Text(k.title, color = if (i == chosen) Accent else Paper)
                    },
                    onClick = {
                        onPick(i)
                        expanded = false
                    }
                )
            }
        }
    }
}
