package com.crnogorski.trener.data

import android.content.Context
import com.crnogorski.trener.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Настройки курса, которые можно менять **не пересобирая приложение**.
 *
 * Файл лежит в репозитории — `config/tuning.json`, — и правится прямо в
 * вебе GitHub, хоть с телефона. Приложение забирает его при запуске.
 *
 * Три уровня, и порядок именно такой:
 *
 * 1. **значения по умолчанию — здесь, в коде.** Не в assets: тогда первый
 *    запуск без сети работает сам собой, а битый или пустой файл ничего не
 *    ломает — он лишь перекрывает то, что задал;
 * 2. **последний скачанный файл** на диске. Он и применяется при запуске:
 *    мгновенно и без сети;
 * 3. **свежий файл из репозитория**, скачиваемый фоном. Пришёл — применяется
 *    сразу и ложится на диск до следующего раза.
 *
 * JSON **неполный тоже годится**: чего в нём нет, берётся из значений по
 * умолчанию. Поэтому менять один параметр можно файлом из одной строки.
 *
 * Всё, что приходит извне, проходит через [sane]: ноль или отрицательное
 * значение в интервале повторений — это деление на ноль и вечный цикл, а файл
 * правит человек с телефона, ночью, одним пальцем.
 */
@Serializable
data class Tuning(
    val srs: Srs = Srs(),
    val daily: Daily = Daily(),
    val vocab: Vocab = Vocab(),
    val story: Story = Story(),
    val speech: Speech = Speech(),
    val diag: Diag = Diag(),
    /**
     * Кто есть кто: **хвост хеша ANDROID_ID → имя человека**.
     *
     * Issue заводятся все под одним аккаунтом — токен в приложении один, — и
     * авторство в GitHub не различает никого. Различает только метка
     * устройства, а имя телефона из настроек («Redmi 13C») говорит модель, а
     * не человека. Эта карта переводит одно в другое: жалоба приходит
     * подписанной «Катя», и issue получает метку с её именем.
     *
     * **Лежит в настройках, а не в коде**, ровно по той причине, по которой
     * тут лежат пороги диагностики: карта будет меняться. Сброс телефона к
     * заводским меняет ANDROID_ID, а с ним и хвост; появится четвёртый
     * телефон — его надо будет добавить. Пересобирать APK ради одной строки
     * незачем, а править файл можно прямо в вебе GitHub, с телефона.
     *
     * Чей хвост какой, видно в уже заведённых issue: он стоит в метке
     * устройства последним.
     */
    val people: Map<String, String> = emptyMap()
) {
    /** Интервальные повторения. Дни, если не сказано иначе. */
    @Serializable
    data class Srs(
        /** Первый интервал после верного ответа. */
        val firstDays: Int = 1,
        /** Второй интервал у заданий уроков. Семь — по жалобе на однообразие. */
        val lessonSecondDays: Int = 7,
        /** Второй интервал у словаря: слову нужно около десяти встреч. */
        val vocabSecondDays: Int = 3,
        val easeStart: Double = 2.5,
        val easeStep: Double = 0.05,
        val easeMin: Double = 1.3,
        val easeMax: Double = 3.0,
        /** Через сколько минут вернуть карточку после ошибки — ещё в этой сессии. */
        val lapseMinutes: Int = 10,
        /** Через сколько часов вернуть пропущенное задание. */
        val skipHours: Int = 4,
        /** Сколько карточек берётся в один подход повторения. */
        val reviewLimit: Int = 25,
        /**
         * Насколько быстрее среднего должен быть ответ, чтобы считаться лёгким.
         *
         * Доля от замеренного среднего по этому типу задания: 0,5 значит
         * «вдвое быстрее обычного». **Ноль выключает** послабление вовсе —
         * тогда расписание работает как до 1.80.
         */
        val easyUnder: Double = 0.5,
        /** Во сколько раз дальше откладывается легко отвеченное. */
        val easyBonus: Double = 1.6
    )

    /** Ежедневное задание. */
    @Serializable
    data class Daily(
        val defaultMinutes: Int = 15,
        val minMinutes: Int = 5,
        val maxMinutes: Int = 60,
        /** По скольку заданий вводится новый урок. */
        val lessonPortion: Int = 6,
        /** Сколько просроченных карточек рассматривать при сборке. */
        val pool: Int = 60,
        val lessonShare: Double = 0.34,
        val reviewShare: Double = 0.34,
        val wordShare: Double = 0.22,
        val storyShare: Double = 0.120,
        /** Сколько заходов даётся на задание, где отвечают голосом. */
        val spokenAttempts: Int = 3,
        /**
         * Сколько новых слов пропускается **вперёд словарного долга**.
         *
         * Ноль значит «как было до 1.75»: сперва всё просроченное, новые слова
         * следом. По замеру копии прогресса от 12.09.2026 это означало, что
         * новых слов не бывает вовсе — за две недели их завелось тридцать
         * вместо полутора сотен. Словарного времени хватает на семь карточек
         * в день, а созревает их двадцать, и очередь долга не кончается
         * никогда.
         *
         * Три — это примерно сорок секунд занятия и девяносто слов в месяц.
         * Цена честная и её стоит знать: долг от этого растёт быстрее, потому
         * что каждое новое слово — это ещё две карточки в обороте.
         */
        val freshLead: Int = 3
    )

    /** Словарь. */
    @Serializable
    data class Vocab(
        val newPerDay: Int = 10,
        val sessionLimit: Int = 25,
        /** Сколько верных ответов делают слово выученным. */
        val learned: Int = 10,
        /** Со скольких повторений открывается следующая ступень карточки. */
        val stepReps: Int = 2,
        val matchPairs: Int = 5,
        val matchMin: Int = 4,
        val matchScreens: Int = 2
    )

    /** Истории. */
    @Serializable
    data class Story(
        /** После скольких неудач показывается черногорский текст. */
        val attemptsBeforeReveal: Int = 3,
        /** Какая доля слов должна прозвучать, чтобы чтение засчиталось. */
        val readingPass: Double = 0.75
    )

    /** Речь. */
    @Serializable
    data class Speech(
        /** Темп кнопки «Медленнее». Ниже 0,3 движок рвёт слова на слоги. */
        val slowRate: Double = 0.35,
        /**
         * Пауза между словами в медленном чтении, мс.
         *
         * Ноль возвращает прежнее поведение — фраза читается слитно, только
         * медленно. Само по себе замедление темпа слова друг от друга не
         * отделяет: движок растягивает звуки, а промежутки оставляет теми же.
         */
        val slowGapMs: Int = 250,
        val normalRate: Double = 0.95,
        /** Высота голоса собеседника в диалоге. */
        val lowPitch: Double = 0.78,
        /** Через сколько секунд считать, что движок не ответит уже никогда. */
        val watchdogSeconds: Int = 20,
        /** Сколько держать заглушку гудков после конца записи. */
        val muteTailMs: Int = 600,
        val retryDelayMs: Int = 300,
        /**
         * Какая доля слов должна совпасть, чтобы сказанное было засчитано.
         *
         * Единица возвращает прежнюю строгость: слово в слово.
         */
        val spokenPass: Double = 0.70,
        /**
         * На сколько знаков слово может разойтись с эталоном и всё же считаться
         * тем же словом. Для коротких слов допуск меньше — см. `LocalCheck.close`.
         */
        val spokenSlack: Int = 2
    )

    /**
     * Диагностика подвисаний. Пороги тут не для красоты: их придётся крутить
     * по ходу поиска, а пересобирать APK ради одного числа — полдня.
     */
    @Serializable
    data class Diag(
        /** С какой длительности событие попадает в сырой список, а не только в сводку. */
        val slowMs: Int = 300,
        /** С какой задержки главного потока считать, что приложение подвисло. */
        val freezeMs: Int = 700,
        /** Сколько копить, прежде чем отправить отчёт. */
        val windowMinutes: Int = 60,
        /** Потолок записей: набралось больше — отправляем не дожидаясь срока. */
        val maxRecords: Int = 600
    )

    /**
     * Приводит значения в разумные пределы.
     *
     * Не «проверяет и отвергает», а именно правит: отвергнутый файл оставил бы
     * человека без обратной связи — он поменял число, ничего не произошло, и
     * почему, неясно. Обрезанное же значение видно сразу по поведению.
     */
    fun sane(): Tuning = Tuning(
        srs = srs.copy(
            firstDays = srs.firstDays.coerceIn(1, 30),
            lessonSecondDays = srs.lessonSecondDays.coerceIn(1, 90),
            vocabSecondDays = srs.vocabSecondDays.coerceIn(1, 90),
            easeStart = srs.easeStart.coerceIn(1.3, 4.0),
            easeStep = srs.easeStep.coerceIn(0.0, 0.5),
            easeMin = srs.easeMin.coerceIn(1.1, 3.0),
            easeMax = srs.easeMax.coerceIn(1.3, 5.0),
            lapseMinutes = srs.lapseMinutes.coerceIn(1, 24 * 60),
            skipHours = srs.skipHours.coerceIn(1, 72),
            reviewLimit = srs.reviewLimit.coerceIn(1, 200),
            easyUnder = srs.easyUnder.coerceIn(0.0, 1.0),
            // Ниже единицы множитель означал бы «за лёгкий ответ спросим
            // раньше», а это не послабление, а наказание за знание.
            easyBonus = srs.easyBonus.coerceIn(1.0, 5.0)
        ),
        daily = daily.copy(
            defaultMinutes = daily.defaultMinutes.coerceIn(1, 240),
            minMinutes = daily.minMinutes.coerceIn(1, 240),
            maxMinutes = daily.maxMinutes.coerceIn(5, 240),
            lessonPortion = daily.lessonPortion.coerceIn(1, 50),
            pool = daily.pool.coerceIn(5, 500),
            lessonShare = daily.lessonShare.coerceIn(0.0, 1.0),
            reviewShare = daily.reviewShare.coerceIn(0.0, 1.0),
            wordShare = daily.wordShare.coerceIn(0.0, 1.0),
            storyShare = daily.storyShare.coerceIn(0.0, 0.9),
            spokenAttempts = daily.spokenAttempts.coerceIn(1, 10),
            // Потолок тут не от балды: больше десяти новых слов в день не
            // пропустит дневная норма, и «сто вперёд долга» просто выкинуло бы
            // повторение из занятия целиком.
            freshLead = daily.freshLead.coerceIn(0, 10)
        ),
        vocab = vocab.copy(
            newPerDay = vocab.newPerDay.coerceIn(0, 100),
            sessionLimit = vocab.sessionLimit.coerceIn(1, 200),
            learned = vocab.learned.coerceIn(1, 100),
            stepReps = vocab.stepReps.coerceIn(1, 50),
            matchPairs = vocab.matchPairs.coerceIn(2, 10),
            matchMin = vocab.matchMin.coerceIn(2, 10),
            matchScreens = vocab.matchScreens.coerceIn(0, 10)
        ),
        story = story.copy(
            attemptsBeforeReveal = story.attemptsBeforeReveal.coerceIn(1, 20),
            readingPass = story.readingPass.coerceIn(0.1, 1.0)
        ),
        speech = speech.copy(
            slowRate = speech.slowRate.coerceIn(0.3, 1.5),
            normalRate = speech.normalRate.coerceIn(0.5, 2.0),
            lowPitch = speech.lowPitch.coerceIn(0.5, 1.5),
            watchdogSeconds = speech.watchdogSeconds.coerceIn(5, 120),
            muteTailMs = speech.muteTailMs.coerceIn(0, 5000),
            retryDelayMs = speech.retryDelayMs,
            spokenPass = speech.spokenPass.coerceIn(0.1, 1.0),
            spokenSlack = speech.spokenSlack.coerceIn(0, 3),
            // Секунда паузы между словами — это уже не «медленно», а «сломалось».
            slowGapMs = speech.slowGapMs.coerceIn(0, 1000).coerceIn(0, 5000)
        ),
        diag = diag.copy(
            slowMs = diag.slowMs.coerceIn(1, 60_000),
            freezeMs = diag.freezeMs.coerceIn(50, 60_000),
            windowMinutes = diag.windowMinutes.coerceIn(1, 7 * 24 * 60),
            maxRecords = diag.maxRecords.coerceIn(10, 10_000)
        ),
        // Ключ приводится к нижнему регистру: хвост хеша печатается
        // строчными, а в файл его перенесут копированием из issue — где он
        // тоже строчный, но глазами этого не проверяют. Имя обрезается: это
        // имя, а не заметка, и уходит оно в метку GitHub.
        people = people
            .asSequence()
            .map { (key, name) -> key.trim().lowercase() to name.trim().take(30) }
            .filter { (key, name) -> key.isNotBlank() && name.isNotBlank() }
            .take(20)
            .toMap()
    )
}

/**
 * Где лежат нынешние настройки.
 *
 * Читается отовсюду и меняется в двух местах — при запуске и когда пришёл
 * свежий файл, — поэтому `@Volatile`: иначе поток, взявший значение однажды,
 * может не увидеть обновления вовсе.
 */
object Config {

    @Volatile
    var current: Tuning = Tuning()
        private set

    /** Когда файл забирали в последний раз и чем это кончилось. */
    @Volatile
    var lastFetch: String? = null
        private set

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val stamp = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /**
     * Поднять сохранённое с диска. Зовётся до первого чтения настроек.
     *
     * Синхронно и намеренно: файл маленький, а разъезжающиеся настройки в
     * первые секунды работы хуже, чем несколько миллисекунд на старте.
     */
    fun load(context: Context) {
        val file = file(context)
        if (!file.exists()) return
        runCatching { json.decodeFromString<Tuning>(file.readText()).sane() }
            .onSuccess { current = it }
    }

    /**
     * Забрать свежий файл из репозитория.
     *
     * Ошибку сети глотаем молча: настройки уже есть, а сообщение «не удалось
     * обновить настройки» при каждом запуске в метро — это шум, а не польза.
     * Разобранный и обрезанный результат применяется сразу и ложится на диск.
     */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.GITHUB_TOKEN.isBlank()) return@withContext false
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/contents/$PATH")
            .addHeader("Authorization", "Bearer ${BuildConfig.GITHUB_TOKEN}")
            .addHeader("Accept", "application/vnd.github.raw")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val fresh = json.decodeFromString<Tuning>(text).sane()
                current = fresh
                file(context).writeText(text)
                lastFetch = LocalDateTime.now().format(stamp)
                true
            }
        }.getOrElse {
            lastFetch = "не вышло: ${it.message}"
            false
        }
    }

    private fun file(context: Context) = File(context.filesDir, LOCAL_NAME)

    private const val PATH = "config/tuning.json"
    private const val LOCAL_NAME = "tuning.json"
}
