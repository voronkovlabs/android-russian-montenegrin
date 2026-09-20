package com.crnogorski.trener.speech

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.crnogorski.trener.BuildConfig
import com.crnogorski.trener.data.Exercise
import com.crnogorski.trener.data.LessonRepository
import com.crnogorski.trener.data.LocalCheck
import com.crnogorski.trener.data.ReadingScore
import com.crnogorski.trener.data.Trace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Прогон корпуса: синтезатор читает весь наш текст, распознаватель слушает,
 * а мы сверяем расслышанное с оригиналом.
 *
 * ## Зачем
 *
 * `check_stories.py` ловит **одну** причину непроходимости — точку посреди
 * отрезка, — и найдена она была тем, что владелец уткнулся в неё, читая вслух.
 * Этот прогон ищет причины, которых мы ещё не знаем, и находит их до того, как
 * в них упрётся человек: цифры, турцизмы, дефисы, слово, которого движок не
 * выдаёт никогда.
 *
 * Второе, и оно чище первого: заодно меряется **синтезатор**. Если он
 * выговаривает слово так, что распознаватель того же языка его не берёт, — это
 * прямая улика против задания «на слух», где синтезатор и есть источник. Тут
 * оговорок нет вовсе: в том режиме человек слышит ровно то же самое.
 *
 * ## Чего прогон не доказывает
 *
 * **Провал — улика, зачёт не значит ничего.** Синтезатор говорит ровно, без
 * акцента и без пауз не на месте; не разобрано — значит дело в тексте. А вот
 * зачёт про живой голос не говорит ничего: оба конца тут от Google, на одной
 * языковой модели, и сойтись между собой они могут так, как не сойдутся с
 * человеком. Это **сито, а не оценка**: им отбирают подозрительные места, им
 * нельзя объявить корпус проверенным.
 *
 * Отдельно стоит помнить про живого носителя (20.09.2026): ему распознавание
 * взяло каждую фразу с первого раза даже в саду под птиц. Скидки в проверке
 * существуют для **нашего** произношения, а не потому, что движок плох.
 *
 * ## Два пути, и какой из них работает — вопрос к телефону
 *
 * Приложение **специально** не даёт микрофону слышать синтезатор: запись
 * подхватила бы его голос. Прогону нужно ровно обратное, и способов два.
 *
 * * **Через файл** ([CorpusRoute.File]) — `synthesizeToFile`, дальше
 *   `EXTRA_AUDIO_SOURCE`: движок читает звук из дескриптора вместо микрофона.
 *   Чисто и быстро, ни комнаты, ни громкости, ни подавления эха. Но поддержку
 *   движок объявлять не обязан и просьбу может молча не заметить;
 * * **по воздуху** ([CorpusRoute.Air]) — динамик в микрофон. Поддержано
 *   наверняка, но `VOICE_RECOGNITION` обычно идёт с подавлением эха, а оно
 *   давит именно то, что телефон играет сам.
 *
 * Поэтому путь **выбирается пробой** на заведомо простой фразе, а не верой:
 * прошла через файл — идём через файл, нет — по воздуху. Какой оказался,
 * написано и на экране, и в отчёте: без этого числа двух прогонов нельзя
 * ставить рядом.
 *
 * ## Порядок и защита
 *
 * Шаги идут по одному, колбэками, на главном потоке. Каждый обязан
 * закончиться: у распознавания есть свой сторож, у синтеза нашего нет вовсе —
 * поэтому сверх всего стоит [ITEM_LIMIT_MS] на строку. Прогон идёт без
 * человека, и повиснуть на одной фразе значит потерять весь сеанс.
 */
class CorpusCheck(
    private val context: Context,
    private val repo: LessonRepository,
    private val speaker: Speaker,
    private val scope: CoroutineScope
) {

    private val listener = Listener(context)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(CorpusState())
    val state: StateFlow<CorpusState> = _state

    private var items: List<CorpusItem> = emptyList()
    private val results = mutableListOf<CorpusResult>()
    private var at = 0
    private var route = CorpusRoute.File
    private var guard = 0

    private val wav: File by lazy { File(context.cacheDir, "korpus.wav") }
    private val pcm: File by lazy { File(context.cacheDir, "korpus.pcm") }

    fun start() {
        if (_state.value.running) return
        results.clear()
        at = 0
        if (speaker.voiceUnavailable) {
            _state.value = CorpusState(
                note = "Сербского голоса на телефоне нет — читать нечем."
            )
            return
        }
        // Час разговора телефона с самим собой в отчёт о задержках не пойдёт.
        Trace.muted = true
        _state.value = CorpusState(running = true, note = "Собираю тексты…")
        scope.launch {
            val list = collect()
            if (list.isEmpty()) {
                Trace.muted = false
                _state.value = CorpusState(note = "Читать нечего — корпус пуст.")
                return@launch
            }
            items = list
            _state.value = _state.value.copy(total = list.size, note = "Пробую путь…")
            probe()
        }
    }

    /**
     * Остановить прогон и сохранить то, что успели.
     *
     * Полтысячи заходов — это час с лишним, и бросить на середине надо уметь:
     * отчёт по трёмстам строкам уже говорит больше, чем ничего.
     */
    fun stop() {
        if (!_state.value.running) return
        guard++
        main.removeCallbacksAndMessages(null)
        listener.cancel()
        speaker.silence()
        finish(stopped = true)
    }

    fun release() {
        guard++
        main.removeCallbacksAndMessages(null)
        listener.stop()
        Trace.muted = false
    }

    // --- сбор корпуса -------------------------------------------------------

    /**
     * Что читаем: отрезки историй и диалогов плюс речевые задания уроков.
     *
     * Задание на чтение разбирается на предложения тем же правилом, каким его
     * читает человек ([LocalCheck.sentences]): один заход — одно предложение,
     * и мерить надо ровно то, что будет происходить вживую.
     *
     * Чего тут нет намеренно: черногорские ответы `ru_to_me` и `word_bank`.
     * Вслух их сейчас не говорят, а это ещё четверть часа прогона ради текста,
     * который никто не произносит.
     */
    private suspend fun collect(): List<CorpusItem> = withContext(Dispatchers.Default) {
        val out = mutableListOf<CorpusItem>()
        runCatching {
            repo.stories().stories.forEach { ref ->
                val story = repo.story(ref.id)
                story.chunks.forEachIndexed { i, chunk ->
                    out += CorpusItem("${ref.id}#${i + 1}", ref.title, chunk.sr)
                }
            }
        }
        runCatching {
            repo.index().lessons.forEach { ref ->
                repo.lesson(ref.id).exercises.forEach { ex ->
                    when (ex) {
                        is Exercise.Speaking -> out += CorpusItem(ex.id, ref.title, ex.phrase)
                        is Exercise.Repeat -> out += CorpusItem(ex.id, ref.title, ex.phrase)
                        is Exercise.Reading ->
                            LocalCheck.sentences(ex.text).forEachIndexed { i, sentence ->
                                out += CorpusItem("${ex.id}.${i + 1}", ref.title, sentence)
                            }
                        else -> Unit
                    }
                }
            }
        }
        out
    }

    // --- проба пути ---------------------------------------------------------

    /**
     * Прошла ли через файл заведомо простая фраза.
     *
     * Простая — намеренно: на трудной пустой ответ значил бы и «путь не
     * поддержан», и «фраза не далась», а различить их было бы нечем.
     *
     * **Сверяем ответ с самой фразой, а не смотрим, пришло ли хоть что-то.**
     * Движок вправе молча не заметить просьбу читать файл — и тогда он слушает
     * микрофон, то есть комнату. В тишине это вернуло бы пустоту и путь сменился
     * бы правильно, но под разговор или телевизор пришла бы правдоподобная
     * чепуха, и прогон пошёл бы слушать комнату все пятьсот строк.
     */
    private fun probe() {
        val mine = ++guard
        // Сторож и на пробу: она первое, что видит человек, нажав кнопку, и
        // молчащий синтезатор оставил бы экран в «Пробую путь…» навсегда.
        main.postDelayed({
            if (mine == guard && _state.value.running) {
                guard++
                listener.cancel()
                route = CorpusRoute.Air
                begin()
            }
        }, ITEM_LIMIT_MS)

        say(PROBE) {
            if (mine != guard) return@say
            val from = strip()
            if (from == null) {
                // Синтезатор не умеет писать в файл — значит и путь через файл
                // невозможен, но вслух он говорить по-прежнему может.
                route = CorpusRoute.Air
                begin()
                return@say
            }
            hear(from) { heard, _ ->
                if (mine != guard) return@hear
                route = if (LocalCheck.readingScore(heard, PROBE).passed) {
                    CorpusRoute.File
                } else {
                    CorpusRoute.Air
                }
                begin()
            }
        }
    }

    private fun begin() {
        main.removeCallbacksAndMessages(null)
        _state.value = _state.value.copy(route = route, note = "")
        step()
    }

    // --- сам прогон ---------------------------------------------------------

    private fun step() {
        if (!_state.value.running) return
        if (at >= items.size) {
            finish(stopped = false)
            return
        }
        val item = items[at]
        val mine = ++guard
        // Сторож на всю строку: у синтеза своего нет, и молчащий движок
        // остановил бы прогон навсегда.
        main.postDelayed({
            if (mine == guard && _state.value.running) {
                // Защёлка: номер меняется до записи, иначе опоздавший ответ
                // движка записал бы ту же строку второй раз.
                guard++
                listener.cancel()
                speaker.silence()
                record(item, "", "молчание движка")
            }
        }, ITEM_LIMIT_MS)

        if (route == CorpusRoute.File) {
            say(item.text) {
                if (mine != guard) return@say
                val from = strip()
                if (from == null) {
                    record(item, "", "синтез не дал звука")
                    return@say
                }
                hear(from) { heard, error ->
                    if (mine == guard) record(item, heard, error)
                }
            }
        } else {
            // По воздуху говорить надо **после** отметки «движок слушает»:
            // сказанное раньше уйдёт в пустоту. Заглушку гудков снимает сам
            // Speaker, поэтому синтезатор звучит в полный голос.
            hear(null, onReady = { speaker.speak(item.text) }) { heard, error ->
                if (mine == guard) record(item, heard, error)
            }
        }
    }

    private fun record(item: CorpusItem, heard: String, error: String) {
        main.removeCallbacksAndMessages(null)
        val score = LocalCheck.readingScore(heard, item.text)
        val result = CorpusResult(item, heard, score.matched, score.total, error)
        results += result
        at++
        _state.value = _state.value.copy(
            done = at,
            failed = results.count { !it.passed },
            last = "${item.id}  ${result.mark}  ${item.text.take(40)}"
        )
        // Небольшая пауза между строками: движку надо отпустить прошлый заход.
        main.postDelayed(::step, GAP_MS)
    }

    // --- кирпичи ------------------------------------------------------------

    /** Синтез в файл: нужен только пути через файл, по воздуху говорят вслух. */
    private fun say(text: String, then: () -> Unit) {
        wav.delete()
        speaker.toFile(text, wav, then)
    }

    private fun hear(
        from: AudioSource?,
        onReady: (() -> Unit)? = null,
        then: (String, String) -> Unit
    ) {
        listener.listen(
            from = from,
            onResult = { then(it, "") },
            onError = { then("", it) },
            onSilence = { then("", "ничего не расслышал") },
            onReady = onReady
        )
    }

    /**
     * Выкусить из WAV сырые отсчёты.
     *
     * Движку отдаётся поток PCM, а частоту и число каналов он берёт отдельными
     * полями: заголовок он разбирать не станет и принял бы его за звук.
     */
    private fun strip(): AudioSource? {
        val bytes = runCatching { wav.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 44) return null
        if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        var rate = 0
        var channels = 1
        var bits = 0
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = tag(bytes, i)
            val size = le(bytes, i + 4, 4)
            val body = i + 8
            when {
                id == "fmt " && body + 16 <= bytes.size -> {
                    channels = le(bytes, body + 2, 2)
                    rate = le(bytes, body + 4, 4)
                    bits = le(bytes, body + 14, 2)
                }
                id == "data" -> {
                    if (rate == 0 || bits != 16) return null
                    val end = minOf(body + size, bytes.size)
                    if (end <= body) return null
                    return runCatching {
                        pcm.writeBytes(bytes.copyOfRange(body, end))
                        AudioSource(pcm, rate, channels)
                    }.getOrNull()
                }
            }
            if (size <= 0) return null
            i = body + size + (size and 1)
        }
        return null
    }

    private fun tag(b: ByteArray, at: Int): String =
        if (at + 4 > b.size) "" else String(b, at, 4, Charsets.US_ASCII)

    private fun le(b: ByteArray, at: Int, bytes: Int): Int {
        if (at + bytes > b.size) return 0
        var v = 0
        for (k in bytes - 1 downTo 0) v = (v shl 8) or (b[at + k].toInt() and 0xFF)
        return v
    }

    // --- отчёт --------------------------------------------------------------

    private fun finish(stopped: Boolean) {
        main.removeCallbacksAndMessages(null)
        listener.cancel()
        Trace.muted = false
        val name = "korpus-${STAMP.format(LocalDateTime.now())}.txt"
        val saved = save(name, report(stopped))
        _state.value = _state.value.copy(
            running = false,
            last = "",
            note = if (saved) "Отчёт: $HUMAN_PATH/$name" else "Отчёт сохранить не вышло."
        )
    }

    private fun report(stopped: Boolean): String = buildString {
        val bad = results.filter { !it.passed }
        appendLine("Прогон корпуса · ${SHOWN.format(LocalDateTime.now())}")
        appendLine("Версия ${BuildConfig.VERSION_NAME} · ${Build.MODEL}")
        appendLine(
            "Путь: " + when (route) {
                CorpusRoute.File -> "через файл (EXTRA_AUDIO_SOURCE)"
                CorpusRoute.Air -> "по воздуху (динамик → микрофон)"
            }
        )
        if (stopped) appendLine("Прогон остановлен вручную.")
        appendLine("Проверено ${results.size} из ${items.size}, не прошло ${bad.size}")
        appendLine()
        appendLine("Провал — улика про текст. Зачёт про живой голос не говорит")
        appendLine("ничего: синтезатор и распознаватель тут оба от Google.")
        appendLine()
        appendLine("=== НЕ ПРОШЛО ===")
        appendLine()
        bad.sortedBy { it.share }.forEach { append(lines(it)) }
        appendLine("=== ВСЁ ПОДРЯД ===")
        appendLine()
        results.forEach { append(lines(it)) }
    }

    private fun lines(r: CorpusResult): String = buildString {
        appendLine("${r.item.id}  ${r.mark}  ${r.item.source}")
        appendLine("  текст:    ${r.item.text}")
        if (r.error.isNotBlank()) {
            appendLine("  ошибка:   ${r.error}")
        } else {
            appendLine("  услышано: ${r.heard}")
            val missed = missed(r)
            if (missed.isNotBlank()) appendLine("  потеряно: $missed")
        }
        appendLine()
    }

    /** Какие именно слова не дошли — по ним и правится текст. */
    private fun missed(r: CorpusResult): String {
        if (r.heard.isBlank()) return ""
        val green = LocalCheck.matchedWords(r.heard, r.item.text)
        return LocalCheck.SHOWN_WORD.findAll(r.item.text)
            .mapIndexed { i, m -> i to m.value }
            .filter { it.first !in green }
            .joinToString(", ") { it.second }
    }

    /**
     * Отчёт кладётся в «Документы», а не в каталог приложения: туда с Android 11
     * файловому менеджеру ходу нет, и забрать файл можно было бы только кабелем.
     */
    private fun save(name: String, text: String): Boolean = runCatching {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = "${android.os.Environment.DIRECTORY_DOCUMENTS}/$ROOT"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
        }
        val uri: Uri = context.contentResolver.insert(collection, values) ?: return false
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    }.getOrDefault(false)

    companion object {
        /**
         * Фраза для пробы пути. Короткая, из первого урока и без единого
         * трудного места: если не далась она, дело в пути, а не в тексте.
         */
        private const val PROBE = "Dobar dan, kako ste?"

        /** Сколько ждать строку целиком, считая синтез и распознавание. */
        private const val ITEM_LIMIT_MS = 40_000L

        /** Пауза между строками: движку надо отпустить прошлый заход. */
        private const val GAP_MS = 400L

        private const val ROOT = "Crnogorski"
        const val HUMAN_PATH = "Документы/Crnogorski"

        private val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
        private val SHOWN: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}

/** Строка корпуса: что читаем и откуда она взялась. */
data class CorpusItem(val id: String, val source: String, val text: String)

/** Итог по строке. */
data class CorpusResult(
    val item: CorpusItem,
    val heard: String,
    val matched: Int,
    val total: Int,
    val error: String = ""
) {
    /**
     * Порог считает сам [ReadingScore]: мерить надо ровно тем, чем засчитываем,
     * а порог живёт в настройках курса и может поменяться.
     */
    val passed: Boolean
        get() = error.isEmpty() && ReadingScore(matched, total).passed

    /** Доля дошедших слов — по ней провалы ставятся худшими вперёд. */
    val share: Float get() = if (total == 0) 0f else matched.toFloat() / total

    val mark: String get() = if (error.isNotBlank()) "—" else "$matched/$total"
}

/** Каким путём звук дошёл до распознавателя. */
enum class CorpusRoute { File, Air }

data class CorpusState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val route: CorpusRoute? = null,
    val last: String = "",
    val note: String = ""
)
