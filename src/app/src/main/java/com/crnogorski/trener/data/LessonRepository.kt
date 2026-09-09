package com.crnogorski.trener.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class LessonRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private var cachedIndex: LessonIndex? = null
    private var cachedGlossary: Glossary? = null
    private var cachedStories: StoryIndex? = null
    private val cachedLessons = mutableMapOf<String, Lesson>()

    suspend fun index(): LessonIndex = withContext(Dispatchers.IO) {
        cachedIndex ?: json.decodeFromString<LessonIndex>(read("lessons/index.json"))
            .also { cachedIndex = it }
    }

    suspend fun lesson(id: String): Lesson = withContext(Dispatchers.IO) {
        cachedLessons[id] ?: run {
            val ref = index().lessons.first { it.id == id }
            json.decodeFromString<Lesson>(read("lessons/${ref.file}"))
                .also { cachedLessons[id] = it }
        }
    }

    /** Оглавление историй; отсутствие файла — не ошибка, просто раздел пустой. */
    suspend fun stories(): StoryIndex = withContext(Dispatchers.IO) {
        cachedStories ?: runCatching {
            json.decodeFromString<StoryIndex>(read("stories/index.json"))
        }.getOrDefault(StoryIndex()).also { cachedStories = it }
    }

    suspend fun story(id: String): Story = withContext(Dispatchers.IO) {
        val ref = stories().stories.first { it.id == id }
        json.decodeFromString<Story>(read("stories/" + ref.file))
    }

    /**
     * Словарь подсказок. Читается один раз за запуск; если файла нет или он
     * битый, подсказки просто не появятся — задания от этого не ломаются.
     */
    suspend fun glossary(): Glossary = withContext(Dispatchers.IO) {
        cachedGlossary ?: runCatching {
            json.decodeFromString<Glossary>(read("glossary.json"))
        }.getOrDefault(Glossary()).also { cachedGlossary = it }
    }

    /**
     * Задания названных уроков, разложенные по id — для сборки повторения.
     *
     * Именно названных, а не всех: у карточки есть `lessonId`, и разбирать
     * ради десятка просроченных карточек весь курс незачем. При полусотне
     * уроков разница между двумя файлами и всеми пятьюдесятью заметна на глаз.
     */
    suspend fun exercisesIn(lessonIds: Collection<String>): Map<String, Pair<String, Exercise>> =
        withContext(Dispatchers.IO) {
            val known = index().lessons.map { it.id }.toSet()
            buildMap {
                lessonIds.distinct().filter { it in known }.forEach { id ->
                    lesson(id).exercises.forEach { ex -> put(ex.id, id to ex) }
                }
            }
        }

    private fun read(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}

/**
 * Строгая проверка для заданий с единственным верным ответом.
 * Игнорирует регистр, пунктуацию и лишние пробелы. Диакритику не игнорирует:
 * č/ć/š/ž/đ несут смысл, и привыкать печатать их стоит сразу.
 *
 * Экавица засчитывается наравне с иекавицей — см. [reflex]: курс черногорский,
 * но правильный сербский ответ ошибкой не считается.
 */
object LocalCheck {

    fun normalize(input: String): String = input
        .trim()
        .lowercase()
        .replace(Regex("[.,!?;:\"'()]"), "")
        .replace(Regex("\\s+"), " ")

    /**
     * Строгое сравнение: экавица здесь **не** прощается.
     *
     * Для заданий, где вариант выбирают из показанных, а не набирают: в l06e07
     * выбор между `sutra` и `sjutra` — и есть всё задание, прощать тут нечего.
     */
    fun matches(answer: String, expected: String): Boolean =
        normalize(answer) == normalize(expected)

    /**
     * Сравнение набранного руками: правильный сербский ответ засчитывается.
     *
     * Разница со [matches] в том, что там форму выбирают из двух показанных,
     * а здесь вспоминают и печатают — и `vreme` вместо `vrijeme` значит, что
     * слово человек знает.
     */
    fun matchesTyped(answer: String, expected: String): Boolean {
        val a = normalize(answer)
        val e = normalize(expected)
        return a == e || reflex(a) == reflex(e)
    }

    /**
     * Ответ на обратный перевод: сверка с русским толкованием.
     *
     * Толкование словаря — это список синонимов с пометами
     * («гастрон. сыр», «говорить, разговаривать; рассказывать»), а не один
     * ответ. Верным считается любой из них: спрашивают, знает ли человек
     * слово, а не помнит ли он словарную статью целиком. Пометы и скобки
     * выбрасываются — набирать «зоол.» никто не станет.
     */
    fun matchesGloss(answer: String, gloss: String): Boolean {
        val given = normalize(answer)
        if (given.isBlank()) return false
        return glossVariants(gloss).any { it == given }
    }

    /** Толкование, разобранное на отдельные варианты ответа. */
    fun glossVariants(gloss: String): List<String> =
        gloss.replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\b[а-яё]{2,8}\\.(?=\\s|$)"), " ")
            // Номера значений в статье («вода 1 и 2») — такой же разделитель,
            // как запятая: без этого весь хвост слипался бы в один вариант.
            .replace(Regex("\\d+"), ";")
            .split(';', ',')
            .map { normalize(it) }
            .filter { it.isNotBlank() }

    /**
     * Для распознавания речи: там диакритика теряется чаще, сверяем мягче.
     *
     * Сравниваются разобранные слова, а не строки: по пути числительные и
     * единицы измерения приводятся к одному виду — см. [words].
     */
    fun matchesSpoken(heard: String, expected: String): Boolean =
        words(heard) == words(expected)

    /**
     * Сводит иекавицу и экавицу к одному виду: `lijepo` и `lepo` после этого
     * равны, `vrijeme` и `vreme` тоже.
     *
     * Замена сплошная (`ije` → `e`, потом `je` → `e`), а не по списку слов:
     * она применяется к обеим сравниваемым строкам сразу, поэтому «jedan»
     * и там и там превращается в «edan» и по-прежнему совпадает само с собой.
     * Столкнуться двум разным словам в одной форме теоретически можно, но за
     * лишний засчитанный ответ в личном тренажёре платить нечем.
     *
     * Слова, где рефлекс не сплошной, идут списком до общего правила:
     * черногорское `sjutra` от сербского `sutra` заменой `je` → `e` не получить.
     */
    fun reflex(normalized: String): String {
        var s = normalized
        SPECIAL.forEach { (from, to) -> s = s.replace(from, to) }
        return s.replace("ije", "e").replace("je", "e")
    }

    private val SPECIAL = listOf(
        "sjutra" to "sutra",
        "śutra" to "sutra",
        "ovđe" to "ovde",
        "onđe" to "onde",
        "đe" to "gde",
        "śever" to "sever",
        "iđem" to "idem"
    )

    /**
     * Насколько прочитанное вслух совпало с текстом — доля слов эталона,
     * прозвучавших в распознанном по порядку (наибольшая общая подпоследовательность).
     *
     * На трёх предложениях дословное совпадение недостижимо: движок склеивает
     * слова, теряет предлоги и не ставит знаков. Порядок при этом учитывается —
     * иначе те же слова, прочитанные вразнобой, засчитались бы как чтение.
     */
    fun readingScore(heard: String, expected: String): ReadingScore {
        val want = words(expected)
        val got = words(heard)
        if (want.isEmpty()) return ReadingScore(0, 0)

        val dp = Array(want.size + 1) { IntArray(got.size + 1) }
        for (i in want.indices) {
            for (j in got.indices) {
                dp[i + 1][j + 1] = if (want[i] == got[j]) {
                    dp[i][j] + 1
                } else {
                    maxOf(dp[i][j + 1], dp[i + 1][j])
                }
            }
        }
        return ReadingScore(dp[want.size][got.size], want.size)
    }

    private fun flatten(s: String) = normalize(s)
        .replace('č', 'c').replace('ć', 'c')
        .replace('š', 's').replace('ž', 'z')
        .replace("đ", "dj")

    /**
     * Слова для сверки: свёрнутая иекавица, числительные цифрами, единицы
     * сокращениями.
     *
     * Последнее — не украшение, а условие работоспособности. Движок
     * распознавания **нормализует числа сам**: на «Dva kilograma, to je
     * dvadeset dva evra» он отдаёт «2 kg to je 20 2 EUR», и посимвольная
     * сверка давала два слова из семи. Пройти такой отрезок было нельзя вовсе.
     *
     * Поэтому к одному виду сводятся обе строки: числительные становятся
     * цифрами, единицы — сокращениями, а рассыпанное на разряды число
     * собирается обратно ([join]) — «20 2» и «22» после этого равны.
     *
     * Плата за это — «jedan» и «jedna» стали неразличимы: род числительного
     * «один» на слух больше не проверяется. Терпимо ровно по той же причине,
     * что и свёртка иекавицы: за лишний засчитанный ответ в личном тренажёре
     * платить нечем, а за незасчитанный верный — платит человек.
     */
    private fun words(s: String) =
        join(reflex(flatten(s)).split(' ').filter { it.isNotBlank() }.map { CANON[it] ?: it })

    /**
     * Собрать число обратно из разрядов: «20» и «2» — это 22.
     *
     * Движок разбирает «dvadeset dva» то как «22», то как «20 2», и оба вида
     * надо привести к одному. Складываем только там, где сложение и есть
     * значение: за круглым десятком — единицы, за круглой сотней — остаток
     * меньше сотни. «deset i petnaest» (десять пятнадцать, о времени) не
     * склеивается: между разрядами стоит «i».
     */
    private fun join(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        tokens.forEach { token ->
            val add = token.toIntOrNull()
            val base = out.lastOrNull()?.toIntOrNull()
            if (add != null && base != null && carries(base, add)) {
                out[out.size - 1] = (base + add).toString()
            } else {
                out += token
            }
        }
        return out
    }

    private fun carries(base: Int, add: Int): Boolean = when {
        base >= 1000 && base % 1000 == 0 -> add in 1..999
        base >= 100 && base % 100 == 0 -> add in 1..99
        base >= 20 && base % 10 == 0 -> add in 1..9
        else -> false
    }

    /**
     * Числительные и единицы — к одному виду. Ключи в том виде, в каком слово
     * доходит сюда: после [flatten] и [reflex], то есть без диакритики и с
     * экавицей («dvije» приходит как «dve»).
     *
     * Круглого «sto» в таблице намеренно нет: [flatten] сводит к нему «što»,
     * и «izvini što smetam» превратилось бы в сотню. Сотни есть только
     * составными словами, где спутать не с чем.
     */
    private val CANON: Map<String, String> = buildMap {
        listOf("nula" to 0,
               // Семья «jedan» доходит сюда без «j»: [reflex] меняет «je» на «e»
               // раньше, чем работает эта таблица. Исходные формы оставлены
               // рядом — они ничего не стоят и не дают забыть, о чём строка.
               "jedan" to 1, "jedna" to 1, "jedno" to 1, "jednu" to 1, "jednog" to 1,
               "jednom" to 1, "jedanaest" to 11,
               "edan" to 1, "edna" to 1, "edno" to 1, "ednu" to 1, "ednog" to 1,
               "ednom" to 1, "edanaest" to 11,
               "dva" to 2, "dve" to 2, "tri" to 3, "cetiri" to 4, "pet" to 5,
               "sest" to 6, "sedam" to 7, "osam" to 8, "devet" to 9, "deset" to 10,
               "dvanaest" to 12, "trinaest" to 13, "cetrnaest" to 14,
               "petnaest" to 15, "sesnaest" to 16, "sedamnaest" to 17,
               "osamnaest" to 18, "devetnaest" to 19,
               "dvadeset" to 20, "trideset" to 30, "cetrdeset" to 40, "pedeset" to 50,
               "sezdeset" to 60, "sedamdeset" to 70, "osamdeset" to 80, "devedeset" to 90,
               "stotina" to 100, "stotinu" to 100,
               "dvesta" to 200, "dvesto" to 200, "trista" to 300, "tristo" to 300,
               "cetiristo" to 400, "petsto" to 500, "seststo" to 600,
               "sedamsto" to 700, "osamsto" to 800, "devetsto" to 900,
               "hiljada" to 1000, "hiljadu" to 1000, "hiljade" to 1000
        ).forEach { (word, value) -> put(word, value.toString()) }

        // Единицы: движок пишет их сокращениями, а в тексте они словами.
        listOf("kilogram", "kilograma", "kilograme", "kilo", "kg").forEach { put(it, "kg") }
        listOf("gram", "grama", "g").forEach { put(it, "g") }
        listOf("litar", "litra", "litara", "l").forEach { put(it, "l") }
        listOf("evro", "evra", "evre", "eura", "eur", "€").forEach { put(it, "eur") }
        listOf("cent", "centa", "centi").forEach { put(it, "cent") }
        listOf("kilometar", "kilometara", "km").forEach { put(it, "km") }
        listOf("metar", "metara", "m").forEach { put(it, "m") }
        listOf("minut", "minuta", "min").forEach { put(it, "min") }
        listOf("sat", "sata", "sati", "h").forEach { put(it, "sat") }
        listOf("procenat", "procenata", "posto", "%").forEach { put(it, "posto") }
    }
}

/**
 * Результат чтения вслух: сколько слов эталона прозвучало из скольких.
 *
 * Порог намеренно не 100%: несколько потерянных движком слов — это его
 * беда, а не ошибка чтения.
 */
data class ReadingScore(val matched: Int, val total: Int) {
    val passed: Boolean get() = total > 0 && matched.toFloat() / total >= PASS

    companion object {
        val PASS: Float get() = Config.current.story.readingPass.toFloat()
    }
}
