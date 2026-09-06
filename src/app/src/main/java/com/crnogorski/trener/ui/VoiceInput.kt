package com.crnogorski.trener.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.crnogorski.trener.speech.Listener

/**
 * Микрофон в поле ввода: продиктовать ответ вместо того, чтобы набирать его
 * с переключением раскладки.
 *
 * [language] — тег языка ответа, а не задания: в переводе на русский диктуют
 * по-русски. Распознанное приходит в [onText] куском, дописывать его к уже
 * набранному — дело вызывающего.
 *
 * Ошибки уходят в [onStatus], а не проглатываются: без доступа к микрофону
 * кнопка иначе просто не работала бы молча.
 */
@Composable
fun MicButton(
    language: String,
    enabled: Boolean,
    onStatus: (String) -> Unit,
    onText: (String) -> Unit
) {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    var listening by remember { mutableStateOf(false) }

    // Распознаватель держит сервис: уходя с задания, его надо отпустить.
    DisposableEffect(Unit) {
        onDispose {
            listener.stop()
        }
    }

    fun start() {
        if (!listener.isAvailable()) {
            onStatus("На устройстве нет распознавания речи")
            return
        }
        listening = true
        onStatus("Говори…")
        listener.listen(
            language = language,
            onResult = { heard ->
                listening = false
                onStatus("")
                onText(heard)
            },
            onError = { message ->
                listening = false
                onStatus(message)
            }
        )
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else onStatus("Без доступа к микрофону диктовать не выйдет")
    }

    IconButton(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        enabled = enabled && !listening
    ) {
        Icon(
            if (listening) Icons.Filled.Mic else Icons.Outlined.Mic,
            contentDescription = "Продиктовать ответ",
            tint = if (listening) Gold else Muted
        )
    }
}
