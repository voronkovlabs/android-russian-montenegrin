package com.crnogorski.trener.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Почему задание считается кривым.
 *
 * Категория важнее свободного текста: по ней сразу видно, что править —
 * JSON урока (`reference_wrong`, `ambiguous`, `typo`), системный промпт
 * в [com.crnogorski.trener.net.HaikuChecker] (`verdict_wrong`) или
 * само задание (`audio_unclear`).
 *
 * `code` попадает в файл и не должен меняться между версиями — иначе
 * старые жалобы перестанут группироваться с новыми.
 */
enum class ComplaintReason(val code: String, val label: String) {
    ReferenceWrong("reference_wrong", "Эталон неверен"),
    Ambiguous("ambiguous", "Мой ответ тоже верен"),
    VerdictWrong("verdict_wrong", "Claude ошибся"),
    AudioUnclear("audio_unclear", "Плохо слышно"),
    Typo("typo", "Опечатка"),
    Other("other", "Другое")
}

/** Вердикт проверяющего на момент жалобы — по нему видно, промпт виноват или эталон. */
@Serializable
data class ComplaintVerdict(
    val correct: Boolean,
    val feedback: String,
    val better: String
)

/**
 * Одна жалоба. Ключ — [exerciseId]: он уникален по всему курсу и не меняется
 * между версиями, поэтому по нему находится строка в файле урока в `assets/lessons`.
 */
@Serializable
data class Complaint(
    val ts: String,
    val exerciseId: String,
    val lessonId: String,
    val type: String,
    val reason: String,
    val note: String = "",
    val userAnswer: String = "",
    val expected: String = "",
    val verdict: ComplaintVerdict? = null,
    val versionCode: Int = 0,
    val versionName: String = ""
)

/**
 * Жалобы копятся в JSONL — по строке на запись.
 *
 * Файл лежит в каталоге приложения на внешней памяти, поэтому забирается
 * `adb pull` без root и без разрешений (см. задачу `pullComplaints`).
 * Дописывание строкой, а не перезапись целого JSON: файл переживает краш
 * на середине и читается `grep`-ом.
 */
class ComplaintStore(private val context: Context) {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /** Внешний каталог может быть недоступен (извлечена карта) — тогда пишем во внутренний. */
    fun file(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun now(): String = LocalDateTime.now().format(stamp)

    suspend fun append(complaint: Complaint) = withContext(Dispatchers.IO) {
        val target = file()
        target.parentFile?.mkdirs()
        target.appendText(json.encodeToString(complaint) + "\n")
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        val target = file()
        if (target.exists()) target.readLines().count { it.isNotBlank() } else 0
    }

    /**
     * Откладывает накопленное в сторону после отправки: файл переименовывается
     * в `complaints-sent-<дата>.jsonl`, новые жалобы пишутся в чистый.
     *
     * Именно переименование, а не удаление: Android не сообщает, дошла ли отправка
     * через share (`ACTION_SEND` не возвращает результат), поэтому удалять по факту
     * нажатия — значит однажды потерять жалобы молча. Дубли безопаснее: у каждой
     * записи есть `ts` и `exerciseId`, они схлопываются на стороне разработчика.
     *
     * @return сколько записей ушло в архив; 0 — если архивировать было нечего.
     */
    suspend fun archive(): Int = withContext(Dispatchers.IO) {
        val current = file()
        if (!current.exists()) return@withContext 0
        val lines = current.readLines().count { it.isNotBlank() }
        if (lines == 0) {
            current.delete()
            return@withContext 0
        }
        val name = LocalDateTime.now().format(archiveStamp)
        val moved = current.renameTo(File(current.parentFile, "complaints-sent-$name.jsonl"))
        if (moved) lines else 0
    }

    companion object {
        const val FILE_NAME = "complaints.jsonl"
        private val archiveStamp: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
    }
}
