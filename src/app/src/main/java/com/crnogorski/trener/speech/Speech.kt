package com.crnogorski.trener.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * В Android нет локали «черногорский», используем sr-RS с латиницей.
 * Различия с черногорским произношением на слух минимальны.
 */
private val SERBIAN: Locale = Locale.Builder()
    .setLanguage("sr")
    .setScript("Latn")
    .setRegion("RS")
    .build()

class Speaker(context: Context) {

    private var ready = false
    private var missingVoice = false

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(SERBIAN)
                missingVoice = result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ready = !missingVoice
                engine.setSpeechRate(0.9f)
            }
        }
    }

    /** true, если голос сербского не установлен — стоит показать подсказку. */
    val voiceUnavailable: Boolean get() = missingVoice

    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

class Listener(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Один заход распознавания. [onResult] получает лучшую гипотезу,
     * [onError] — текст ошибки для показа в интерфейсе.
     */
    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sr-RS")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (list.isNullOrEmpty()) onError("Ничего не расслышал")
                else onResult(list.first())
            }

            override fun onError(error: Int) = onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Ничего не расслышал"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Тишина в микрофоне"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
                    SpeechRecognizer.ERROR_NETWORK -> "Распознавание не отвечает"
                    else -> "Ошибка распознавания ($error)"
                }
            )

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        sr.startListening(intent)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
