package com.crnogorski.trener.speech

import android.content.Context
import android.content.SharedPreferences
import com.crnogorski.trener.data.Config
import com.crnogorski.trener.data.Trace
import android.media.AudioManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import java.io.File
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

private val NORMAL_RATE: Float get() = Config.current.speech.normalRate.toFloat()

/** Обычный тон — им говорит «сам текст». */
private const val NORMAL_PITCH = 1.0f

/**
 * Тон собеседника в диалоге.
 *
 * Голос у `sr-RS` в Android один, выбирать не из чего, поэтому роли разводятся
 * высотой. Ниже настолько, чтобы разница слышалась сразу, но речь не поехала:
 * на 0,6 движок начинает бубнить и разбирать становится труднее, чем нужно.
 */
private val LOW_PITCH: Float get() = Config.current.speech.lowPitch.toFloat()

/**
 * «Медленнее» для аудирования: разобрать на слух с первого раза выходит не всегда.
 *
 * Было 0,55 — по жалобе владельца («читает всё равно слишком быстро») стало
 * 0,4. Ниже опускаться незачем: движок начинает рвать слова на слоги, и
 * разбирать становится труднее, а не легче.
 */
private val SLOW_RATE: Float get() = Config.current.speech.slowRate.toFloat()

/** Пауза между словами в медленном чтении. Ноль — читать слитно, как раньше. */
private val SLOW_GAP_MS: Long get() = Config.current.speech.slowGapMs.toLong()

/** Разделитель слов для медленного чтения. */
private val SPACES = Regex("\\s+")

class Speaker(context: Context) {

    /** Нужен, чтобы снять заглушку гудков перед своей фразой — см. [speak]. */
    private val audio = context.getSystemService(AudioManager::class.java)

    private var ready = false
    private var missingVoice = false

    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Просьба произнести, пришедшая до готовности движка.
     *
     * Инициализация TextToSpeech асинхронная, а задание озвучивается сразу при
     * появлении на экране — без этой отложенной фразы первое задание за запуск
     * молчало бы.
     */
    private var pending: Pending? = null

    private class Pending(
        val text: String,
        val slow: Boolean,
        val low: Boolean,
        val onDone: (() -> Unit)?
    )

    /**
     * Номер звучащей сейчас фразы и что делать, когда она договорена.
     *
     * Номер сквозной, а не хэш текста: два одинаковых отрезка подряд дали бы
     * один и тот же идентификатор, и конец первого засчитался бы за конец
     * второго.
     */
    private var utterance = 0L
    private var currentId: String? = null
    private var whenDone: (() -> Unit)? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) = finish(utteranceId)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = finish(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // Оборванную фразу не продолжаем: её оборвали намеренно. Сравнение
            // с текущим номером обязательно — QUEUE_FLUSH останавливает старую
            // фразу уже после того, как назначена новая.
            if (utteranceId == currentId) {
                whenDone = null
                currentId = null
            }
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(SERBIAN)
                missingVoice = result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ready = !missingVoice
                engine.setSpeechRate(NORMAL_RATE)
                engine.setOnUtteranceProgressListener(progress)
                pending?.let { speak(it.text, it.slow, it.low, it.onDone) }
            }
            pending = null
        }
    }

    /** true, если голос сербского не установлен — стоит показать подсказку. */
    val voiceUnavailable: Boolean get() = missingVoice

    /**
     * [onDone] вызывается на главном потоке, когда фраза договорена, — и ровно
     * один раз. Нужен упражнению «на слух»: слушать и говорить одновременно
     * нельзя, микрофон включается только после последнего слова.
     */
    fun speak(
        text: String,
        slow: Boolean = false,
        low: Boolean = false,
        onDone: (() -> Unit)? = null
    ) {
        if (!ready) {
            if (!missingVoice) {
                pending = Pending(text, slow, low, onDone)
            } else {
                // Голоса нет и не будет. Продолжение всё равно должно случиться:
                // иначе экран «на слух» замер бы, дожидаясь конца фразы, которой
                // не было.
                onDone?.let { main.post(it) }
            }
            return
        }
        val engine = tts ?: return
        // Снимаем заглушку гудков до того, как начнём говорить.
        //
        // По жалобе владельца (issue 75): «можно ли сделать произношение
        // громче, я плохо слышу даже на максимальной громкости». Громкость
        // была ни при чём. Заглушка, которой глушатся гудки распознавания,
        // держится ещё `MUTE_TAIL_MS` после конца захода, а в историях цикл
        // плотный — «сказал, послушал, сказал», — и следующая фраза начинала
        // звучать в приглушённый поток. Человек слышал не тихий синтезатор, а
        // собственную заглушку.
        audio?.let { Listener.releaseBeepMute(it) }
        val id = (++utterance).toString()
        currentId = id
        whenDone = onDone
        engine.setSpeechRate(if (slow) SLOW_RATE else NORMAL_RATE)
        engine.setPitch(if (low) LOW_PITCH else NORMAL_PITCH)

        val words = if (slow && SLOW_GAP_MS > 0) text.trim().split(SPACES) else emptyList()
        if (words.size > 1) {
            byWords(engine, words, id)
            return
        }

        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finish(id)
        }
    }

    /**
     * Записать фразу в файл тем же голосом, каким она звучала бы вслух.
     *
     * Нужно прогону корпуса: распознаватель умеет принимать звук файлом, и
     * тогда проверка не зависит ни от громкости, ни от тишины в комнате, ни от
     * подавления эха — а по воздуху зависит от всего сразу.
     *
     * **Удалось или нет, говорит сам файл, а не колбэк.** У `synthesizeToFile`
     * неудача приходит в тот же `onDone`, что и удача, и различить их в общем
     * слушателе нечем; пустой файл — это и есть неудача, проверяет её
     * вызывающий.
     *
     * Темп и тон здесь всегда обычные: прогон меряет текст, а не чтение с
     * замедлением.
     */
    fun toFile(text: String, file: File, onDone: () -> Unit) {
        val engine = tts
        if (!ready || engine == null) {
            main.post(onDone)
            return
        }
        val id = (++utterance).toString()
        currentId = id
        whenDone = onDone
        engine.setSpeechRate(NORMAL_RATE)
        engine.setPitch(NORMAL_PITCH)
        if (engine.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
            finish(id)
        }
    }

    /**
     * Медленное чтение — по словам, с паузой между ними.
     *
     * По жалобе (issue 72): «медленнее должно читать ещё медленнее, идеально
     * если при этом возможно отделять слова друг от друга». Второе важнее
     * первого и одним темпом не достигается: движок на низком темпе растягивает
     * **звуки**, а промежутки между словами оставляет прежними, и фраза
     * по-прежнему слышится одним комом. Разделить её можно только очередью —
     * слово, тишина, слово.
     *
     * Идентификатор вешается **только на последнее** слово: конец фразы должен
     * случиться один раз, а не после каждого слова. Промежуточные куски идут
     * без него и колбэков не порождают вовсе — [finish] их и так отсёк бы по
     * несовпадению, но лишних сообщений в главный поток лучше не слать.
     *
     * Первое слово идёт с `QUEUE_FLUSH`, остальное дописывается: иначе
     * предыдущая фраза не оборвалась бы, а новая встала бы за ней в очередь.
     */
    private fun byWords(engine: TextToSpeech, words: List<String>, id: String) {
        words.forEachIndexed { i, word ->
            val last = i == words.lastIndex
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val ok = engine.speak(word, mode, null, if (last) id else null)
            if (ok != TextToSpeech.SUCCESS) {
                finish(id)
                return
            }
            if (!last) engine.playSilentUtterance(SLOW_GAP_MS, TextToSpeech.QUEUE_ADD, null)
        }
    }

    /** Замолчать и забыть, что было назначено на конец фразы. */
    fun silence() {
        whenDone = null
        currentId = null
        tts?.stop()
    }

    private fun finish(id: String?) {
        main.post {
            if (id != currentId) return@post
            val done = whenDone
            whenDone = null
            currentId = null
            done?.invoke()
        }
    }

    fun release() {
        silence()
        tts?.shutdown()
        tts = null
    }
}

/**
 * Звук файлом вместо микрофона: сырой PCM и его формат.
 *
 * Именно сырой, без заголовка WAV: движку отдаётся поток отсчётов, а частоту
 * и число каналов он берёт из отдельных полей и разбирать заголовок не станет.
 */
class AudioSource(val file: File, val rate: Int, val channels: Int)

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
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Номер живого захода. По нему отбрасываются ответы отменённых и уже
     * закрытых: движок иногда присылает и результат, и ошибку.
     */
    private var session = 0

    /**
     * Дескриптор файла, отданный движку вместо микрофона.
     *
     * Держим его до конца захода и закрываем сами: движок получает свою копию,
     * а наша иначе висела бы открытой до сборки мусора — по пятьсот штук за
     * прогон корпуса.
     */
    private var source: ParcelFileDescriptor? = null


    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Есть ли на телефоне распознавание, работающее без сети. */
    fun onDeviceAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * Просить ли распознавание на устройстве.
     *
     * Настройка, а не наш выбор: у Google под sr-RS есть отдельная модель, но
     * установлена ли она локально — от телефона к телефону по-разному. Если
     * локальной нет, заход падает с «язык недоступен», и вернуть галочку надо
     * человеку, а не гадать за него. По умолчанию выключено: нынешний путь
     * работает, а этот проверяется.
     */
    var onDevice: Boolean
        get() = prefs.getBoolean(KEY_ON_DEVICE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ON_DEVICE, value).apply()
            // Движок переключается только на новом объекте.
            stop()
        }


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
        from: AudioSource? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSilence: (() -> Unit)? = null,
        onReady: (() -> Unit)? = null
    ) {
        val id = ++session
        val started = SystemClock.uptimeMillis()
        Trace.event("речь: заход начат", 0, language)
        // Заглушка нужна только микрофону: она глушит гудки записи. Когда звук
        // приходит файлом, гудков нет вовсе, а глушить поток музыки вредно —
        // именно в него говорит синтезатор.
        if (from == null) mute()
        // Сторож: движок распознавания умеет не ответить вовсе — ни результатом,
        // ни ошибкой. Тогда экран навсегда оставался в «Слушаю…», и выйти из
        // него можно было только из истории целиком. Своего таймаута у
        // SpeechRecognizer нет, поэтому он тут наш.
        main.postDelayed({
            if (claim(id)) {
                Trace.event("речь: сторож сработал", SystemClock.uptimeMillis() - started)
                onError("Распознавание не ответило. Нажми ещё раз.")
            }
        }, WATCHDOG_MS)
        begin(id, started, language, from, onResult, onError, onSilence, onReady, mayRetry = true)
    }

    /**
     * Закрыть заход [id], если он ещё жив.
     *
     * Возвращает `true` ровно один раз на заход: второй ответ движка (а он
     * бывает — результат вслед за ошибкой) и сработавший позже сторож
     * отбрасываются молча. Заодно снимает заглушку и отменяет всё
     * отложенное — и повтор, и сторожа.
     */
    private fun claim(id: Int): Boolean {
        if (id != session) return false
        session++
        main.removeCallbacksAndMessages(null)
        source?.let { runCatching { it.close() } }
        source = null
        // Заглушку снимаем с задержкой: гудок конца записи движок играет уже
        // после того, как отдал результат, и снятая сразу заглушка попадала бы
        // ровно на него. Если за это время начался новый заход, не снимаем
        // вовсе — сравнение с номером об этом и говорит.
        val closed = session
        main.postDelayed({ if (session == closed) unmute() }, MUTE_TAIL_MS)
        return true
    }

    /**
     * Распознаватель: на устройстве или обычный.
     *
     * Обычный сам решает, идти в сеть или нет. Локальный не ходит никогда —
     * но и работает, только если языковая модель скачана.
     */
    private fun newRecognizer(): SpeechRecognizer =
        if (onDevice && onDeviceAvailable()) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun begin(
        id: Int,
        started: Long,
        language: String,
        from: AudioSource?,
        onText: (String) -> Unit,
        onFail: (String) -> Unit,
        onSilence: (() -> Unit)?,
        onReady: (() -> Unit)?,
        mayRetry: Boolean
    ) {
        val sr = recognizer ?: newRecognizer().also { recognizer = it }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        // Звук файлом вместо микрофона. Поддержку движок объявлять не обязан —
        // просьбу можно молча не заметить, и тогда он станет слушать микрофон,
        // то есть тишину. Отличить это от неудачи можно только по ответу,
        // поэтому прогон корпуса сперва пробует путь на заведомо простой фразе.
        if (from != null) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(
                    from.file, ParcelFileDescriptor.MODE_READ_ONLY
                )
                source?.let { old -> runCatching { old.close() } }
                source = pfd
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                intent.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                intent.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, from.rate
                )
                intent.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, from.channels
                )
            }
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                Trace.event("речь: ответ движка", SystemClock.uptimeMillis() - started)
                if (!claim(id)) {
                    // Ответ в уже закрытый заход. Раньше это было невидимо, а
                    // именно тут теряются экраны: заход закрыт, а экран ждёт.
                    Trace.event("речь: ответ в закрытый заход", 0)
                    return
                }
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (list.isNullOrEmpty()) {
                    if (onSilence != null) onSilence() else onFail("Ничего не расслышал")
                } else {
                    onText(list.first())
                }
            }

            override fun onError(error: Int) {
                Trace.event("речь: ошибка движка", SystemClock.uptimeMillis() - started, "код $error")
                // Срыв на холодной привязке лечится повтором, и делать это должны
                // мы, а не человек: он всё равно нажмёт кнопку второй раз.
                if (mayRetry && error in TRANSIENT) {
                    // Повтор идёт тем же заходом: сторож продолжает тикать, и
                    // если движок не ответит и со второго раза, выход всё равно
                    // найдётся.
                    if (id != session) return
                    recognizer?.destroy()
                    recognizer = null
                    main.postDelayed(
                        {
                            if (id == session) {
                                begin(
                                    id, started, language, from,
                                    onText, onFail, onSilence, onReady, mayRetry = false
                                )
                            }
                        },
                        RETRY_DELAY_MS
                    )
                    return
                }
                if (!claim(id)) return
                // «Не расслышал» и «тишина» — не ошибка чтения, а то, что человек
                // ещё не начал говорить. Кто хочет, разбирает этот случай отдельно.
                if (error in SILENT && onSilence != null) {
                    onSilence()
                    return
                }
                onFail(describe(error))
            }

            // Прогону корпуса это единственная честная отметка «движок
            // слушает»: по воздуху говорить надо после неё, иначе начало фразы
            // уйдёт в пустоту.
            override fun onReadyForSpeech(params: Bundle?) {
                if (id == session) onReady?.invoke()
            }
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
        session++
        main.removeCallbacksAndMessages(null)
        // Здесь снимаем сразу: слушать больше не собираемся, ждать гудка нечего.
        unmute()
        runCatching { recognizer?.cancel() }
    }

    /** Отпустить сервис, уходя с экрана. */
    fun stop() {
        session++
        main.removeCallbacksAndMessages(null)
        unmute()
        recognizer?.destroy()
        recognizer = null
    }

    /**
     * Приглушить сигналы движка на время записи.
     *
     * Гудки в начале и конце распознавания играет системный движок, а не мы,
     * и выключить их нечем: у `SpeechRecognizer` такой настройки нет вовсе.
     * Что можно — заглушить поток, в который он их играет. Свой синтезатор в
     * это время молчит по построению (говорить и слушать одновременно
     * нельзя), так что терять на этом потоке нечего.
     *
     * Только `STREAM_MUSIC`: заглушить звонок или уведомления Android даёт
     * лишь с доступом к «Не беспокоить», а просить его ради двух гудков —
     * несоразмерно. Если на этом телефоне движок сигналит в другой поток,
     * гудки останутся, и сделать с ними будет нечего.
     *
     * Оставить телефон беззвучным навсегда заглушка не может: поставленную
     * процессом систему снимает сама, когда процесс умирает.
     */
    private fun mute() {
        if (muted.isNotEmpty()) return
        // Каким потоком движок играет гудки, не сказано нигде, и на разных
        // прошивках он разный: одной музыки на HyperOS не хватило. Поэтому
        // глушим всё, до чего дотягиваемся, и запоминаем что именно — снять
        // надо ровно это. Звонок, уведомления и системные звуки Android даёт
        // трогать только с доступом к «Не беспокоить»; без него попытка
        // молча не проходит, и заглушённой остаётся одна музыка.
        BEEP_STREAMS.forEach { stream ->
            val ok = runCatching {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            }.isSuccess
            if (ok) muted += stream
        }
    }

    private fun unmute() = releaseBeepMute(audio)

    companion object {
        /**
         * Потоки, которые заглушены сейчас. Снять надо ровно их: до каких
         * дотянулись, заранее неизвестно.
         *
         * Хранилище **общее на процесс, а не на экземпляр**, и это не
         * вольность: громкость у телефона одна, и две копии заглушки спорили
         * бы за неё — вторая сняла бы поставленное первой, или наоборот.
         * Заодно снять её может тот, кто заглушку не ставил, — синтезатор.
         */
        private val muted = mutableListOf<Int>()

        /**
         * Снять заглушку немедленно, не дожидаясь хвоста.
         *
         * Зовёт [Speaker] перед тем, как начать говорить. По жалобе владельца
         * (issue 75): «плохо слышу даже на максимальной громкости». Громкость
         * была ни при чём — заглушка держится ещё `MUTE_TAIL_MS` после конца
         * захода, чтобы съесть гудок конца записи, а в историях цикл плотный,
         * и следующая фраза начинала звучать в приглушённый поток. Человек
         * слышал не тихий синтезатор, а собственную заглушку.
         *
         * Гудок при этом не возвращается: он приходит сразу за результатом,
         * а синтезатор заговаривает заметно позже — свой заход уже кончился.
         */
        fun releaseBeepMute(audio: AudioManager) {
            if (muted.isEmpty()) return
            muted.forEach { stream ->
                runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
            }
            muted.clear()
        }

        /** Ошибки, которые лечатся повтором, а не сообщением. */
        val TRANSIENT = setOf(
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        )

        /** Движок ничего не услышал — говорить ещё не начали. */
        val SILENT = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        )

        /** Пауза перед повтором: пересозданному объекту нужно время на привязку. */
        val RETRY_DELAY_MS: Long get() = Config.current.speech.retryDelayMs.toLong()

        private const val PREFS = "crnogorski"
        private const val KEY_ON_DEVICE = "speech_on_device"

        /**
         * Потоки, в которые движок может играть гудки записи.
         *
         * Порядок важен: музыку нам дают глушить всегда, остальное — только с
         * доступом к «Не беспокоить». Первым идёт то, что сработает наверняка.
         */
        val BEEP_STREAMS = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_RING
        )

        /**
         * Сколько держать заглушку после конца захода.
         *
         * Гудок конца записи приходит уже после результата: снятая сразу
         * заглушка попадала бы ровно на него, и из двух гудков пропадал бы
         * только первый.
         */
        val MUTE_TAIL_MS: Long get() = Config.current.speech.muteTailMs.toLong()

        /**
         * Через сколько считать, что движок не ответит уже никогда.
         *
         * Своё молчание он объявляет сам и гораздо раньше — секунд через пять
         * тишины приходит `ERROR_SPEECH_TIMEOUT`. Двадцать секунд поэтому не
         * могут оборвать живое распознавание: столько не длится ни один
         * отрезок. Они нужны только на случай, когда ответа нет вообще.
         */
        val WATCHDOG_MS: Long get() = Config.current.speech.watchdogSeconds * 1000L
    }
}
