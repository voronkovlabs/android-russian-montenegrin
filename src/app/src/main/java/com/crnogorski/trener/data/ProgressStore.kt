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
import java.time.Instant
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
    val lessons: List<ProgressLesson> = emptyList()
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
                }
            )
        }

    /**
     * Пишет копию в выбранную папку. Файл всегда один и тот же и переписывается:
     * иначе за полгода занятий в папке накопятся сотни снимков.
     *
     * @return число сохранённых карточек или null, если папки нет или запись не удалась.
     */
    suspend fun saveToFolder(versionCode: Int, versionName: String): Int? =
        withContext(Dispatchers.IO) {
            val tree = folder() ?: return@withContext null
            val snap = snapshot(versionCode, versionName)
            runCatching {
                val target = existing(tree, FILE_NAME) ?: create(tree, FILE_NAME)
                ?: return@withContext null
                // «wt» — с усечением: без него короткий снимок оставил бы хвост старого.
                context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                    out.write(json.encodeToString(snap).toByteArray())
                } ?: return@withContext null
                snap.cards.size
            }.getOrNull()
        }

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
            val snap = json.decodeFromString<ProgressSnapshot>(text)
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
            snap.cards.size to snap.lessons.size
        }.getOrNull()
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
        private val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}
