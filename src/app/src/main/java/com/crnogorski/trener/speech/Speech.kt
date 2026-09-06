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

/** Язык ответа на черногорском — тот же sr-RS, что и у синтеза. */
const val TAG_TARGET = "sr-RS"

/** Язык ответа на русском: перевод с черногорского набирают и диктуют по-русски. */
const val TAG_NATIVE = "ru-RU"

/**
 * Язык ответа. Теги разные: распознаванию нужен sr-RS, а клавиатуре —
 * sr-Latn-RS, иначе Gboard откроет сербскую кириллицу вместо латиницы.
 */
enum class AnswerLanguage(val speech: String, val keyboard: String) {
    Target(TAG_TARGET, "sr-Latn-RS"),
    Native(TAG_NATIVE, TAG_NATIVE)
}

private const val NORMAL_RATE = 0.9f

/** «Медленнее» для аудирования: разобрать на слух с первого раза выходит не всегда. */
private const val SLOW_RATE = 0.55f

class Speaker(context: Context) {

    private var ready = false
    private var missingVoice = false

    private var tts: TextToSpeech? = null

    /**
     * Просьба произнести, пришедшая до готовности движка.
     *
     * Инициализация TextToSpeech асинхронная, а задание озвучивается сразу при
     * появлении на экране — без этой отложенной фразы первое задание за запуск
     * молчало бы.
     */
    private var pending: Pair<String, Boolean>? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(SERBIAN)
                missingVoice = result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ready = !missingVoice
                engine.setSpeechRate(NORMAL_RATE)
                pending?.let { (text, slow) -> speak(text, slow) }
            }
            pending = null
        }
    }

    /** true, если голос сербского не установлен — стоит показать подсказку. */
    val voiceUnavailable: Boolean get() = missingVoice

    fun speak(text: String, slow: Boolean = false) {
        if (!ready) {
            if (!missingVoice) pending = text to slow
            return
        }
        val engine = tts ?: return
        engine.setSpeechRate(if (slow) SLOW_RATE else NORMAL_RATE)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
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
     *
     * [language] — тег языка ответа: черногорский набирают и диктуют на sr-RS,
     * перевод на русский — на ru-RU. Движку это не подсказка, а требование:
     * с чужим языком он выдаёт правдоподобную бессмыслицу.
     *
     * [longForm] — чтение целого текста: движок просят не обрывать запись на
     * паузе между предложениями.
     */
    fun listen(
        language: String = TAG_TARGET,
        longForm: Boolean = false,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (longForm) {
                // Чтение в несколько предложений: пауза между ними не должна
                // обрывать запись. Движок вправе эти просьбы проигнорировать —
                // документация прямо это оговаривает, гарантий тут нет.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    2500
                )
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000)
            }
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
