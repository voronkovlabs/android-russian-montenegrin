package com.crnogorski.trener.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.crnogorski.trener.data.Secrets

/**
 * Замок на входе: нет ключа Anthropic — приложение не работает вовсе.
 *
 * Требование владельца, 23.09.2026, дословно: «Апи ключ должен быть
 * обязательным. Если его нет, то его надо спрашивать в модальном режиме и не
 * давать работать дальше вообще».
 *
 * ## Он строже, чем требует устройство, и это решение
 *
 * Приложение офлайн-способно почти целиком: ключ нужен только свободным
 * переводам (`ru_to_me`, `me_to_ru`) и устному переводу в историях. Всё
 * остальное — уроки, словарь, чтение вслух, распознавание — работает и без
 * сети вовсе. То есть замок закрывает куда больше, чем ломается без ключа.
 *
 * Так решено намеренно: с открытым репозиторием сборку ставит кто угодно, и
 * наполовину работающее приложение без объяснения причины хуже, чем честная
 * стена с одним понятным требованием.
 *
 * ## Что отсюда следует про переустановку
 *
 * После установки начисто **ключ вводится раньше, чем восстанавливается
 * прогресс**: до настроек иначе не добраться. Это порядок, а не тупик, но
 * знать о нём надо — копия прогресса на месте и никуда не денется.
 *
 * ## Флажок жалобы прямо здесь
 *
 * Иначе единственные, кто не может пожаловаться, — это те, кто упёрся в самую
 * глухую стену приложения. При цели «жалобы от кого угодно» это ровно
 * наоборот, а стоит он двух строк: [ComplaintButton] — готовый диалог.
 *
 * Жалоба отсюда ложится в файл и уедет в issues сама: токен GitHub зашит в
 * сборку и от ключа Anthropic не зависит.
 *
 * ## Почему принимается по виду, а не проверкой
 *
 * Настоящая проверка — это запрос к `api.anthropic.com`, то есть деньги
 * владельца и работающий интернет. Замок обязан закрываться и в метро, поэтому
 * здесь только `Secrets.looksReal`, а живая проба живёт кнопкой в настройках.
 */
@Composable
fun KeyGate(context: Context, onNote: (String) -> Unit, modifier: Modifier = Modifier) {
    var typed by remember { mutableStateOf("") }
    val ready = Secrets.looksReal(typed)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "КЛЮЧ",
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier.weight(1f)
            )
            // Пожаловаться можно и отсюда — см. KDoc.
            ComplaintButton(onSave = onNote)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Нужен ключ Anthropic",
            style = MaterialTheme.typography.displaySmall,
            color = Paper
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Свободные переводы проверяет Claude, и ходит он по твоему ключу — " +
                "в приложение ключ не зашит. Заведи свой на console.anthropic.com, " +
                "раздел API Keys, и вставь сюда. Ключ остаётся на этом телефоне: " +
                "ни в репозиторий, ни в копию прогресса он не попадает.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            placeholder = { Text("sk-ant-…", color = Muted) },
            // Заглавная буква в начале превратила бы «sk-ant-» в «Sk-ant-», и
            // человек искал бы ошибку в ключе, а не в клавиатуре.
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Paper,
                unfocusedTextColor = Paper,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Muted,
                cursorColor = Accent
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(6.dp))
        Text(
            if (typed.isBlank() || ready) {
                "Ключ вводится один раз. После переустановки — заново."
            } else {
                "Ключ начинается с «sk-ant-» — похоже, скопировалось не целиком."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (typed.isBlank() || ready) Muted else Crimson
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { Secrets.save(context, typed) },
            enabled = ready,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить и начать") }
    }
}
