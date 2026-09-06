package com.crnogorski.trener.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    var open by rememberSaveable { mutableStateOf(false) }
    // Черновик переживает и поворот экрана, и случайную отмену — терять набранное обидно.
    var text by rememberSaveable { mutableStateOf("") }

    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Flag, contentDescription = "Пожаловаться", tint = Muted)
    }

    if (!open) return

    AlertDialog(
        onDismissRequest = { open = false },
        containerColor = Surface1,
        titleContentColor = Paper,
        textContentColor = Muted,
        title = { Text("Жалоба") },
        text = {
            Column {
                Text(
                    "Что не так? Уйдёт в тот же отчёт, что и жалобы на задания.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Например: голос читает слишком быстро", color = Muted) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold,
                        unfocusedBorderColor = Surface2,
                        focusedTextColor = Paper,
                        unfocusedTextColor = Paper,
                        cursorColor = Gold
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(text)
                    text = ""
                    open = false
                },
                enabled = text.isNotBlank()
            ) { Text("Записать", color = if (text.isNotBlank()) Gold else Muted) }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) { Text("Отмена", color = Muted) }
        }
    )
}
