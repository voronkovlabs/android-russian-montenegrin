package com.crnogorski.trener.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    /**
     * Распознаватель живёт, пока живёт экран, и **не пересоздаётся** на каждую
     * попытку.
     *
     * Пересоздание и было причиной ошибки 11 (`ERROR_SERVER_DISCONNECTED`) на
     * первом нажатии: свежий объект привязывается к системному сервису не
     * мгновенно, и первый заход срывался, а второй уже работал.
     */
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Один заход распознавания. [onResult] получает лучшую гипотезу,
     * [onError] — текст ошибки для показа в интерфейсе.
     *
     * [language] — тег языка ответа: черногорский набирают и диктуют на sr-RS,
     * перевод на русский — на ru-RU. Движку это не подсказка, а требование:
     * с чужим языком он выдаёт правдоподобную бессмыслицу.
     *
     * Один заход — одна короткая фраза. Просить движок не обрывать запись на
     * паузе бесполезно: документация разрешает эти просьбы игнорировать, а на
     * длинном тексте он возвращает «ничего не расслышал». Поэтому длинное
     * читается по предложению — см. ReadingAnswer и StoryScreen.
     */
    fun listen(
        language: String = TAG_TARGET,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        begin(language, onResult, onError, mayRetry = true)
    }

    private fun begin(
        language: String,
        onText: (String) -> Unit,
        onFail: (String) -> Unit,
        mayRetry: Boolean
    ) {
        val sr = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (list.isNullOrEmpty()) onFail("Ничего не расслышал")
                else onText(list.first())
            }

            override fun onError(error: Int) {
                // Срыв на холодной привязке лечится повтором, и делать это должны
                // мы, а не человек: он всё равно нажмёт кнопку второй раз.
                if (mayRetry && error in TRANSIENT) {
                    recognizer?.destroy()
                    recognizer = null
                    main.postDelayed(
                        { begin(language, onText, onFail, mayRetry = false) },
                        RETRY_DELAY_MS
                    )
                    return
                }
                onFail(describe(error))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        runCatching { sr.cancel() }
        sr.startListening(intent)
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Ничего не расслышал"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Тишина в микрофоне"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Распознавание не отвечает — нет сети?"
        SpeechRecognizer.ERROR_AUDIO -> "Микрофон занят другим приложением"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "Распознавание отключилось, попробуй ещё раз"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Слишком часто, подожди немного"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Сербский язык распознавания не установлен"
        else -> "Ошибка распознавания ($error)"
    }

    /**
     * Прервать текущий заход, не отпуская сервис.
     *
     * Нужно, когда слушать больше не надо, а сейчас же может понадобиться снова:
     * пересоздание распознавателя — та самая холодная привязка, из-за которой
     * первое нажатие срывалось с ошибкой 11.
     */
    fun cancel() {
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
    }

    /** Отпустить сервис, уходя с экрана. */
    fun stop() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private companion object {
        /** Ошибки, которые лечатся повтором, а не сообщением. */
        val TRANSIENT = setOf(
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        )

        /** Пауза перед повтором: пересозданному объекту нужно время на привязку. */
        const val RETRY_DELAY_MS = 300L
    }
}
