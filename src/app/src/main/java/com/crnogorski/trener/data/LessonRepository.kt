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

    /**
     * Словарь подсказок. Читается один раз за запуск; если файла нет или он
     * битый, подсказки просто не появятся — задания от этого не ломаются.
     */
    suspend fun glossary(): Glossary = withContext(Dispatchers.IO) {
        cachedGlossary ?: runCatching {
            json.decodeFromString<Glossary>(read("glossary.json"))
        }.getOrDefault(Glossary()).also { cachedGlossary = it }
    }

    /** Все задания курса, разложенные по id — нужно для сборки сессии повторения. */
    suspend fun allExercises(): Map<String, Pair<String, Exercise>> = withContext(Dispatchers.IO) {
        buildMap {
            index().lessons.forEach { ref ->
                lesson(ref.id).exercises.forEach { ex -> put(ex.id, ref.id to ex) }
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

    /** Для распознавания речи: там диакритика теряется чаще, сверяем мягче. */
    fun matchesSpoken(heard: String, expected: String): Boolean =
        reflex(flatten(heard)) == reflex(flatten(expected))

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

    private fun words(s: String) = reflex(flatten(s)).split(' ').filter { it.isNotBlank() }
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
        const val PASS = 0.75f
    }
}
