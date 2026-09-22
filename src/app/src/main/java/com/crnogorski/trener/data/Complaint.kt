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
 * Код для идеи — «сделать бы», а не «сломано».
 *
 * Отдельный код, а не заметка с приметой в тексте: по нему ставится метка на
 * issue, а метку задним числом по тексту не восстановишь. Как и [NOTE_REASON],
 * в файл он уходит навсегда и меняться не должен.
 */
const val IDEA_REASON = "idea"

/**
 * Живая фраза: как здесь говорят на самом деле.
 *
 * Отдельный код, а не пометка в тексте, ровно по той же причине, по которой
 * идея отделена от жалобы: метку на issue ставит человек **в момент записи**, а
 * задним числом по тексту её не восстановить — «Kako ide?» и «сделать кнопку
 * побольше» выглядят в файле одинаково.
 *
 * Разбирают их по-разному: идея ждёт своей очереди, а подслушанная фраза — это
 * сырьё для курса, её место в уроке или в истории. Потому и метки две.
 */
const val PHRASE_REASON = "idea_phrase"

/**
 * Моя фраза: то, что хочется научиться говорить самому.
 *
 * От [PHRASE_REASON] отличается **источником, а не видом записи**, и потому
 * это третий код, а не пометка внутри второго. Живая фраза подслушана — её
 * приносят с рынка или от врача, и ценность её в том, что так говорят здесь.
 * Своя фраза приходит с другой стороны: понадобилось сказать, а нечем. Первая
 * пополняет курс тем, что в языке есть; вторая — тем, чего не хватает
 * **этому** ученику, и разбирают их поэтому врозь.
 *
 * Довод про метку тот же, что и всегда: ставит её человек в момент записи, а
 * задним числом «Kako ide?» от «как сказать „можно счёт?“» не отличить.
 */
const val MY_PHRASE_REASON = "idea_my_phrase"

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
    suspend fun flush(send: suspend (Complaint, String) -> Result<Int>): Flushed =
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
                    // Отдаём и разобранную запись, и саму строку: в issue
                    // уезжает то, что записал телефон, — байт в байт.
                    val result = send(complaint, line)
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
     * Кто прислал: имя телефона, как его назвал хозяин.
     *
     * Имя аккаунта Google приложению недоступно — с Android 8 `GET_ACCOUNTS`
     * показывает только те аккаунты, которые приложение завело само, а
     * читать чужие нельзя вовсе. Единственное человеческое имя, которое
     * система отдаёт без разрешений, — то, что задано в «Настройки → Об
     * устройстве → Имя устройства» (`Settings.Global.DEVICE_NAME`, оно же
     * видно по Bluetooth). Его владелец меняет сам, и «Телефон Ани» в issue
     * говорит ровно то, что нужно: от кого пришло.
     *
     * Модель дописывается, только если её нет в имени: «Redmi Note 13» и так
     * называется моделью по умолчанию, и «Redmi Note 13 (Redmi Note 13)»
     * выглядело бы глупо.
     *
     * **Впереди имени телефона стоит имя человека**, если хвост хеша нашёлся
     * в карте `people` из подтягиваемых настроек (`Tuning.people`). Модель
     * отвечает на вопрос «с какого аппарата», а разбирают жалобы по другому —
     * «от кого», и до 1.71 этот перевод делался в уме, по отдельным записям.
     * Карта лежит в настройках, потому что будет меняться: сброс телефона к
     * заводским меняет ANDROID_ID, а с ним и хвост.
     *
     * Хвост из шести символов хеша ANDROID_ID остаётся как **различитель**:
     * два телефона одной модели с непереименованными именами иначе слились бы
     * в один. Именно хеш, а не сам идентификатор: для «с какого телефона это
     * пришло» достаточно того, что хвост не меняется, а ANDROID_ID — это
     * идентификатор устройства, и уезжать целиком ему незачем.
     */
    @SuppressLint("HardwareIds")
    fun deviceTag(): String {
        // Одно и то же имя лежит в двух таблицах настроек, и на разных
        // прошивках заполнена то одна, то другая.
        val readers = listOf<() -> String?>(
            { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) },
            { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }
        )
        val chosen = readers.firstNotNullOfOrNull { read ->
            runCatching { read() }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
        val model = Build.MODEL.trim()
        val name = when {
            chosen == null -> model
            model.isBlank() || chosen.contains(model, ignoreCase = true) -> chosen
            else -> "$chosen ($model)"
        }

        // Длину режем у имени телефона, а не у всей строки: хвост хеша стоит
        // последним, и общий `take` откусил бы именно его — то единственное,
        // по чему телефоны и различают.
        return listOf(person().orEmpty(), name.ifBlank { "телефон" }.take(40), hash())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    /**
     * Чей это телефон — или `null`, если хвоста нет в карте.
     *
     * Отдельно от [deviceTag], потому что уходит отдельно: в метку issue.
     * Метка видна в списке, не открывая, а «от кого пришло» читают раньше, чем
     * разбирают. В теле имя тоже остаётся: метки живут своей жизнью, их
     * переименовывают и снимают, а текст issue не меняется.
     *
     * Незнакомый телефон не выдумывается: пусто значит пусто, и в issue
     * останется модель с хвостом — по ней и добавят строчку в настройки.
     */
    fun person(): String? = Config.current.people[hash()]?.takeIf { it.isNotBlank() }

    /**
     * Хвост хеша сам по себе — для журнала прохождений.
     *
     * Он кладёт устройство **в каждую запись**, а не только в шапку: сводить
     * отчёты трёх телефонов в «никто не запускал» иначе нечем, а метки
     * переименовывают и снимают.
     */
    fun deviceId(): String = hash()

    /**
     * Хвост хеша ANDROID_ID — различитель телефона.
     *
     * Именно хеш, а не сам идентификатор: для «с какого телефона это пришло»
     * довольно того, что хвост не меняется, а ANDROID_ID уезжать целиком
     * незачем.
     */
    @SuppressLint("HardwareIds")
    private fun hash(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        if (androidId.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray())
            .take(3)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FILE_NAME = "complaints.jsonl"

        /** Отправленное. Не удаляем: issue закроют, а сказанное останется. */
        const val SENT_NAME = "complaints-sent.jsonl"
    }
}
