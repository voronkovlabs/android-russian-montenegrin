package com.crnogorski.trener.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
data class ProgressCard(
    val exerciseId: String,
    val lessonId: String,
    val dueAt: Long,
    val intervalDays: Int,
    val ease: Double,
    val repetitions: Int,
    val lapses: Int
)

@Serializable
data class ProgressStory(
    val storyId: String,
    val chunksDone: Int,
    val finishedAt: Long,
    /** Чтение или перевод. Со значением по умолчанию: в старых копиях режима нет, а было там чтение. */
    val mode: String = StoryMode.Read.key
)

@Serializable
data class ProgressLesson(
    val lessonId: String,
    val completedAt: Long,
    val correct: Int,
    val total: Int
)

/**
 * Снимок прогресса целиком. [version] — версия формата, а не приложения:
 * понадобится, если однажды поменяется схема.
 */
@Serializable
data class ProgressSnapshot(
    val version: Int = 1,
    val exportedAt: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val cards: List<ProgressCard> = emptyList(),
    val lessons: List<ProgressLesson> = emptyList(),
    /** Появилось позже карточек, поэтому со значением по умолчанию: старые копии читаются как были. */
    val stories: List<ProgressStory> = emptyList()
)

/**
 * Чем кончилась запись копии.
 *
 * [folder] — null, если папка не выбрана вовсе; false — если выбрана, но записать
 * не вышло. Второе важно отличать от первого: молча не сохранять там, где человек
 * рассчитывает на сохранение, — худшее, что может делать резервное копирование.
 */
data class SaveResult(
    val cards: Int,
    val local: Boolean,
    val folder: Boolean?,
    val error: String? = null
)

/**
 * Копия прогресса в папке, которую владелец выбрал сам.
 *
 * Зачем, если есть Auto Backup от Android: тот отстаёт на сутки, молча не
 * работает без Google-аккаунта и восстанавливает только при установке начисто.
 * Своя копия лежит там, куда её положили, — хоть в папке OneDrive рядом с APK, —
 * и удаление приложения её не трогает: каталог не наш.
 *
 * Доступ к папке даёт система через выбор каталога, а право на него мы просим
 * постоянным. Переустановку право не переживает (файл переживает), поэтому после
 * неё папку указывают заново — один раз.
 */
class ProgressStore(private val context: Context, private val dao: AppDao) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Копия рядом с жалобами, в каталоге приложения на внешней памяти.
     *
     * Пишется всегда и ничего не требует: ни выбора папки, ни разрешений, ни
     * установленного облака. Удаление приложения её уносит вместе с каталогом,
     * зато она есть при любом раскладе и её видно любым файловым менеджером —
     * достаточно скопировать перед удалением. Забирается и `gradlew pullComplaints`.
     */
    fun localFile(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    /** Папка, если она выбрана и право на неё ещё живо. */
    fun folder(): Uri? {
        val saved = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse) ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == saved && it.isWritePermission
        }
        return if (granted) saved else null
    }

    /** Человекочитаемое имя папки для экрана настроек. */
    fun folderLabel(): String? = folder()?.let { uri ->
        Uri.decode(uri.lastPathSegment ?: uri.toString()).substringAfterLast(':')
    }

    fun rememberFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
    }

    fun forgetFolder() {
        folder()?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove(KEY_FOLDER).apply()
    }

    suspend fun snapshot(versionCode: Int, versionName: String): ProgressSnapshot =
        withContext(Dispatchers.IO) {
            ProgressSnapshot(
                exportedAt = STAMP.format(Instant.now()),
                versionCode = versionCode,
                versionName = versionName,
                cards = dao.allCards().map {
                    ProgressCard(it.exerciseId, it.lessonId, it.dueAt, it.intervalDays,
                        it.ease, it.repetitions, it.lapses)
                },
                lessons = dao.lessonProgress().map {
                    ProgressLesson(it.lessonId, it.completedAt, it.correct, it.total)
                },
                stories = dao.storyProgress().map {
                    ProgressStory(it.storyId, it.chunksDone, it.finishedAt, it.mode)
                }
            )
        }

    /**
     * Пишет копию: обязательно рядом с приложением, а если выбрана папка — то и туда.
     *
     * Имя файла всегда одно и то же и переписывается: иначе за полгода занятий
     * в папке накопятся сотни снимков.
     *
     * Локальная копия — это тот самый запасной путь, ради которого всё и затевалось:
     * нет облака, нет папки, отозвано право — прогресс всё равно сохранён, просто
     * в месте, которое не переживёт удаления приложения.
     */
    suspend fun save(versionCode: Int, versionName: String): SaveResult =
        withContext(Dispatchers.IO) {
            val snap = snapshot(versionCode, versionName)
            val text = json.encodeToString(snap)

            val local = runCatching {
                localFile().apply { parentFile?.mkdirs() }.writeText(text)
            }.isSuccess

            val tree = folder()
            var folderOk: Boolean? = null
            var error: String? = null
            if (tree != null) {
                val attempt = runCatching {
                    val target = existing(tree, FILE_NAME) ?: create(tree, FILE_NAME)
                    ?: error("папка не принимает новые файлы")
                    // «wt» — с усечением: без него короткий снимок оставил бы хвост старого.
                    context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                        out.write(text.toByteArray())
                    } ?: error("папка недоступна")
                }
                folderOk = attempt.isSuccess
                if (!attempt.isSuccess) {
                    error = attempt.exceptionOrNull()?.message ?: "запись не удалась"
                }
            }

            remember(local, folderOk, error)
            SaveResult(snap.cards.size, local, folderOk, error)
        }

    private fun remember(local: Boolean, folderOk: Boolean?, error: String?) {
        val note = when {
            folderOk == true -> "в папку и на телефон"
            folderOk == false -> "только на телефон, в папку не вышло: ${error.orEmpty()}"
            local -> "на телефон"
            else -> "не удалось"
        }
        prefs.edit()
            .putString(KEY_LAST, "${LOCAL_STAMP.format(LocalDateTime.now())} — $note")
            .apply()
    }

    /** Строка о последней копии для экрана настроек. */
    fun lastSave(): String? = prefs.getString(KEY_LAST, null)

    /** Пишет копию в файл, выбранный вручную через системный диалог. */
    suspend fun saveTo(uri: Uri, versionCode: Int, versionName: String): Int? =
        withContext(Dispatchers.IO) {
            val snap = snapshot(versionCode, versionName)
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(json.encodeToString(snap).toByteArray())
                } ?: return@withContext null
                snap.cards.size
            }.getOrNull()
        }

    /**
     * Заливает снимок обратно.
     *
     * Дописывает и заменяет по ключу, но ничего не удаляет: восстановление не
     * должно уметь терять данные. Если карточка есть и там и там, побеждает файл.
     *
     * @return сколько карточек и уроков влилось, или null, если файл не разобрать.
     */
    suspend fun restoreFrom(uri: Uri): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            } ?: return@withContext null
            apply(json.decodeFromString<ProgressSnapshot>(text))
        }.getOrNull()
    }

    /** Восстановление из копии рядом с приложением — когда папки нет или она отвалилась. */
    suspend fun restoreLocal(): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val file = localFile()
        if (!file.exists()) return@withContext null
        runCatching { apply(json.decodeFromString<ProgressSnapshot>(file.readText())) }.getOrNull()
    }

    private suspend fun apply(snap: ProgressSnapshot): Pair<Int, Int> {
        snap.cards.forEach {
            dao.upsertCard(
                CardEntity(it.exerciseId, it.lessonId, it.dueAt, it.intervalDays,
                    it.ease, it.repetitions, it.lapses)
            )
        }
        snap.lessons.forEach {
            dao.upsertLesson(
                LessonProgressEntity(it.lessonId, it.completedAt, it.correct, it.total)
            )
        }
        snap.stories.forEach {
            dao.upsertStory(
                StoryProgressEntity(it.storyId, it.mode, it.chunksDone, it.finishedAt)
            )
        }
        return snap.cards.size to snap.lessons.size
    }

    private fun existing(tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    private fun create(tree: Uri, name: String): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        return DocumentsContract.createDocument(
            context.contentResolver, parent, "application/json", name
        )
    }

    companion object {
        const val FILE_NAME = "crnogorski-progress.json"
        private const val PREFS = "crnogorski"
        private const val KEY_FOLDER = "progress_folder"
        private const val KEY_LAST = "progress_last_save"
        private val LOCAL_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM HH:mm")
        private val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}
