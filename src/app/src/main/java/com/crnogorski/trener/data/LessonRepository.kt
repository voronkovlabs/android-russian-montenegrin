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
        cachedIndex ?: Trace.span("assets: оглавление уроков") {
            json.decodeFromString<LessonIndex>(read("lessons/index.json"))
        }.also { cachedIndex = it }
    }

    suspend fun lesson(id: String): Lesson = withContext(Dispatchers.IO) {
        cachedLessons[id] ?: run {
            val ref = index().lessons.first { it.id == id }
            Trace.span("assets: урок", id) {
                json.decodeFromString<Lesson>(read("lessons/${ref.file}"))
            }.also { cachedLessons[id] = it }
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
        cachedGlossary ?: Trace.span("assets: подсказки по словам") {
            runCatching {
                json.decodeFromString<Glossary>(read("glossary.json"))
            }.getOrDefault(Glossary())
        }.also { cachedGlossary = it }
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
          Trace.span("assets: уроки под повторение", "${lessonIds.distinct().size} шт.") {
            val known = index().lessons.map { it.id }.toSet()
            buildMap {
                lessonIds.distinct().filter { it in known }.forEach { id ->
                    lesson(id).exercises.forEach { ex -> put(ex.id, id to ex) }
                }
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
        // Оставляем **только буквы и цифры**, всё прочее — в пробел.
        //
        // Раньше здесь стоял список знаков `.,!?;:"'()`, и он молча пропускал
        // всё, чего в нём нет. Немецкие кавычки `„ “` из народных сказок (1.94)
        // прилипали к слову, и `„Ja` переставало совпадать с услышанным `ja`:
        // отрезок из шести слов получал четыре из шести и не проходил порог в
        // 75%. Найдено владельцем на живом чтении 20.09.2026.
        //
        // Перечисляем то, что **оставляем**, а не то, что выбрасываем: список
        // знаков обречён быть неполным — «ёлочки», тире, многоточие, апостроф
        // с диакритикой, и так без конца.
        //
        // В пробел, а не в пустоту: так `tamo-amo` становится двумя словами,
        // ровно как их и слышит движок распознавания.
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

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
     * Замечание к засчитанному ответу, который отличается от эталона только
     * рефлексом ятя. `null` — сказать нечего.
     *
     * Нужно из-за перекоса в данных: словарь собран из сербского источника и
     * даёт экавские формы (`deo`, `lepota`), а курс черногорский. Ответ
     * `dio` засчитывается и так, но эталон показывал сербское написание —
     * то есть учил не тому, что спрашивал. Пересобирать словарь дорого и
     * рискованно (лемма — ключ карточки, прогресс обнулится), а сказать
     * человеку правду — дёшево.
     *
     * Кто из двух форм черногорская, определяется само: **иекавская та,
     * которую свёртка меняет**. `reflex("dio") = "deo"`, значит `dio`
     * иекавский; `reflex("deo") = "deo"`, значит `deo` экавский. Отдельного
     * списка не нужно, и работает это в обе стороны — в словаре эталон
     * сербский, в уроках наоборот.
     */
    fun reflexNote(answer: String, expected: String): String? {
        val a = normalize(answer)
        val e = normalize(expected)
        if (a == e || strict(a) != strict(e)) return null
        // Говорим, только когда ответ иекавский, а эталон нет: обратный случай
        // куда чаще оказывается опиской, а не сербской формой.
        return if (strict(a) != a && strict(e) == e) {
            "«$a» — черногорская форма, в словаре сербская «$e»."
        } else {
            null
        }
    }

    /**
     * Свёртка ятя **только по надёжным случаям**: явный список и «ije».
     *
     * Обычная [reflex] снимает ещё и голое «je», а оно сидит в «pitanje»,
     * «rođendan», «putovanje», где никакого ятя нет: по такой свёртке 80 слов
     * словаря выглядят иекавскими. Для сверки ответа это терпимо — лишняя
     * мягкость, — но для замечания смертельно: приложение уверенно сообщало бы
     * ложь про язык. Поэтому замечание опирается на узкое правило, и молчит
     * там, где не уверено.
     */
    private fun strict(normalized: String): String {
        var s = normalized
        SPECIAL.forEach { (from, to) -> s = s.replace(from, to) }
        WHOLE.forEach { (from, to) -> s = from.replace(s, to) }
        return s.replace("ije", "e")
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
     * Сказанное — с двойной скидкой на слух.
     *
     * Заведено по жалобе Кати (issue 68): «повторяет фразу три раза, и каждый
     * раз распознавалка видит ошибку в одном-двух звуках». До 1.80 проверка
     * [matchesSpoken] требовала совпадения **слово в слово**, и одно слово
     * мимо означало промах целиком — трижды подряд.
     *
     * Скидок две, и одной из них не хватило бы.
     *
     * * **По знакам.** Движок отдаёт слово чуть иначе — «vidjet» вместо
     *   «vidjeti», — и по словам это промах целиком. Поэтому слово считается
     *   тем же, если расходится на пару знаков ([close]);
     * * **по словам.** Предлог движок глотает совсем, и никакая точность
     *   внутри слова этого не покроет. Поэтому дальше считается доля
     *   совпавших слов, порог в настройках (`speech.spokenPass`).
     *
     * Порядок при этом важен, считается он тем же способом, что у чтения
     * вслух, — наибольшей общей подпоследовательностью. Те же слова вразнобой
     * фразой не являются.
     *
     * Чем за это плачено, стоит помнить: настоящие оговорки — не тот предлог,
     * не то окончание — теперь чаще проходят. Проект и так решил не оценивать
     * качество произношения («достаточно того, что движок распознал фразу»), а
     * из двух зол расписание должно выбирать мягкое: заброшенное занятие
     * удерживает ноль процентов. Единица в `spokenPass` возвращает прежнюю
     * строгость.
     *
     * На фразе из двух слов любой порог остаётся «всё или ничего» — там
     * работает только скидка по знакам.
     */
    fun spokenScore(heard: String, expected: String): ReadingScore {
        val want = words(expected)
        val got = words(heard)
        if (want.isEmpty()) return ReadingScore(0, 0)

        val dp = Array(want.size + 1) { IntArray(got.size + 1) }
        for (i in want.indices) {
            for (j in got.indices) {
                dp[i + 1][j + 1] = if (close(want[i], got[j])) {
                    dp[i][j] + 1
                } else {
                    maxOf(dp[i][j + 1], dp[i + 1][j])
                }
            }
        }
        return ReadingScore(dp[want.size][got.size], want.size, spoken = true)
    }

    /**
     * Одно ли это слово с точностью до пары знаков.
     *
     * Допуск растёт с длиной, и это не косметика: для «u» и «i» любой допуск
     * означал бы, что предлоги неразличимы вовсе, а они несут падеж. Поэтому
     * до четырёх знаков — совпадение точное, дальше знак, у длинных слов
     * `speech.spokenSlack`.
     */
    fun close(a: String, b: String): Boolean {
        if (a == b) return true
        val slack = when {
            maxOf(a.length, b.length) < 4 -> 0
            maxOf(a.length, b.length) < 7 -> 1
            else -> Config.current.speech.spokenSlack
        }
        if (slack == 0) return false
        if (kotlin.math.abs(a.length - b.length) > slack) return false
        return distance(a, b) <= slack
    }

    /**
     * Расстояние Левенштейна — на двух коротких словах это дешевле, чем любая
     * хитрость вокруг. Строка памяти одна: полная таблица тут не нужна.
     */
    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur.copyOf()
        }
        return prev[b.length]
    }

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
        WHOLE.forEach { (from, to) -> s = from.replace(s, to) }
        return s.replace("ije", "e").replace("je", "e")
    }

    /**
     * Иекавские формы, которые общее правило не ловит: перед «о» ять даёт
     * «-io» там, где в экавице «-eo» — `dio`/`deo`, `cio`/`ceo`.
     *
     * Заменяется **слово целиком**, и это не придирка: подстрока «dio» сидит
     * в «radio» и «studio», а «cio» — в «socio-», и общая замена наделала бы
     * из них «radeo» и «soceo» на ровном месте.
     *
     * Списком, а не правилом «-io в конце → -eo»: под такое правило попал бы
     * «bio» (был) и стал бы «beo» (белый). Слова разные, а после свёртки
     * совпали бы — ровно то столкновение, которого свёртка не должна создавать.
     */
    private val WHOLE = listOf(
        Regex("\bdio\b") to "deo",
        Regex("\bcio\b") to "ceo"
    )

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
/**
 * Доля прозвучавшего: сколько слов эталона нашлось в услышанном, по порядку.
 *
 * [spoken] говорит, каким порогом мерить. Их два, и это не недосмотр: чтение
 * трёх предложений подряд и одна фраза за три захода — разные задачи, и
 * прощать им надо разное. Порог чтения живёт в `story.readingPass`, порог
 * одной фразы — в `speech.spokenPass`.
 */
data class ReadingScore(val matched: Int, val total: Int, val spoken: Boolean = false) {
    val passed: Boolean
        get() = total > 0 && matched.toFloat() / total >= if (spoken) SPOKEN_PASS else PASS

    companion object {
        val PASS: Float get() = Config.current.story.readingPass.toFloat()
        val SPOKEN_PASS: Float get() = Config.current.speech.spokenPass.toFloat()
    }
}
