package com.crnogorski.trener.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
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

/**
 * Код причины для жалобы, не привязанной к заданию, — «просто заметка».
 *
 * Отдельной строкой, а не значением [ComplaintReason]: категории рисуются
 * чипами на экране результата, а у заметки выбирать не из чего, там только текст.
 * По этому коду при разборе отчёта видно, что [Complaint.exerciseId], если он
 * заполнен, — место, где заметку написали, а не то, на что жалуются.
 */
const val NOTE_REASON = "note"

/**
 * Чем кончилась отправка очереди.
 *
 * [left] — сколько осталось ждать, [error] — почему остановились. Ноль и `null`
 * значит «очередь пуста», а не «всё хорошо»: в настройках это разные строки.
 */
data class Flushed(val sent: Int = 0, val left: Int = 0, val error: String? = null)

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

    /**
     * Замок на файл.
     *
     * Отправка переписывает файл целиком (неотправленное остаётся, отправленное
     * уезжает в архив), а жалобу могут написать ровно в этот момент — тогда
     * строка потерялась бы молча. Дописывание и отправка теперь не пересекаются.
     */
    private val lock = Mutex()

    suspend fun append(complaint: Complaint) = withContext(Dispatchers.IO) {
        lock.withLock {
            val target = file()
            target.parentFile?.mkdirs()
            target.appendText(json.encodeToString(complaint) + "\n")
        }
    }

    /**
     * Отправить накопленное и вычеркнуть то, что ушло.
     *
     * Файл — очередь: жалоба сперва ложится на диск и только потом уезжает.
     * Порядок именно такой, потому что жалоба нужна ровно тогда, когда что-то
     * сломалось, — в том числе без сети, и терять её из-за этого нельзя.
     *
     * **Первая же неудача останавливает проход.** Если сеть отвалилась, десять
     * оставшихся попыток отвалятся тоже — а десять таймаутов подряд это полторы
     * минуты впустую и разряженный аккумулятор. Остальное подождёт следующего
     * раза.
     *
     * Отправленное не удаляется, а переезжает в `complaints-sent.jsonl`: issue
     * когда-нибудь закроют, а запись о том, что человек говорил, останется.
     */
    suspend fun flush(send: suspend (Complaint) -> Result<Int>): Flushed =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val current = file()
                if (!current.exists()) return@withLock Flushed()
                val lines = current.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return@withLock Flushed()

                val left = mutableListOf<String>()
                val done = mutableListOf<String>()
                var sent = 0
                var failure: String? = null

                for (line in lines) {
                    val complaint = runCatching { json.decodeFromString<Complaint>(line) }.getOrNull()
                    if (complaint == null) {
                        // Битую строку в очереди держать незачем: она не уедет
                        // никогда. В архив — там её хотя бы видно.
                        done += line
                        continue
                    }
                    if (failure != null) {
                        left += line
                        continue
                    }
                    val result = send(complaint)
                    if (result.isSuccess) {
                        sent++
                        done += line
                    } else {
                        failure = result.exceptionOrNull()?.message ?: "не отправилось"
                        left += line
                    }
                }

                if (done.isNotEmpty()) {
                    File(current.parentFile, SENT_NAME)
                        .appendText(done.joinToString("\n", postfix = "\n"))
                }
                if (left.isEmpty()) current.delete()
                else current.writeText(left.joinToString("\n", postfix = "\n"))

                Flushed(sent = sent, left = left.size, error = failure)
            }
        }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        val target = file()
        if (target.exists()) target.readLines().count { it.isNotBlank() } else 0
    }

    /**
     * Короткий и стабильный ярлык устройства: модель плюс шесть символов
     * от хеша ANDROID_ID.
     *
     * Именно хеш: сам ANDROID_ID — идентификатор, который не должен уезжать
     * в мессенджер вместе с файлом, а для «с какого телефона это пришло»
     * достаточно того, что ярлык не меняется от отправки к отправке.
     */
    @SuppressLint("HardwareIds")
    fun deviceTag(): String {
        val model = Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val short = if (androidId.isBlank()) "" else {
            MessageDigest.getInstance("SHA-256")
                .digest(androidId.toByteArray())
                .take(3)
                .joinToString("") { "%02x".format(it) }
        }
        return listOf(model, short).filter { it.isNotBlank() }.joinToString("-")
            .ifBlank { "device" }
            .take(40)
    }

    companion object {
        const val FILE_NAME = "complaints.jsonl"

        /** Отправленное. Не удаляем: issue закроют, а сказанное останется. */
        const val SENT_NAME = "complaints-sent.jsonl"
    }
}
