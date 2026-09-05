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
 */
object LocalCheck {

    fun normalize(input: String): String = input
        .trim()
        .lowercase()
        .replace(Regex("[.,!?;:\"'()]"), "")
        .replace(Regex("\\s+"), " ")

    fun matches(answer: String, expected: String): Boolean =
        normalize(answer) == normalize(expected)

    /** Для распознавания речи: там диакритика теряется чаще, сверяем мягче. */
    fun matchesSpoken(heard: String, expected: String): Boolean {
        fun flatten(s: String) = normalize(s)
            .replace('č', 'c').replace('ć', 'c')
            .replace('š', 's').replace('ž', 'z')
            .replace("đ", "dj")
        return flatten(heard) == flatten(expected)
    }
}
