package com.crnogorski.trener.data

import android.content.Context
import com.crnogorski.trener.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Оценка одной картинки: принята, отвергнута или пока без вердикта, плюс
 * комментарий. Комментарий живёт и без вердикта — его можно написать раньше.
 */
data class PictureMark(
    val lemma: String,
    val verdict: String? = null,
    val comment: String = "",
    val at: Long = 0L
)

/**
 * Оценки сгенерированных картинок к словам (4.36).
 *
 * Лежат в `filesDir/picture-review.json`, а не в Room и не в настройках, и в
 * копию прогресса не входят: это пометки владельца о картинках, а не прогресс
 * ученика. Потеря файла стоит часа разбора, потеря прогресса — месяцев.
 *
 * Пишется при каждом изменении, целиком: оценок сотни, файл в несколько
 * килобайт, а «потеряли набранное» тут хуже любой лишней записи.
 */
object PictureReview {
    /**
     * Круг оценки (4.37). Первый шёл по всем 176 картинкам, второй — только по
     * переделанным после него, третий (4.38) — по всем нарисованным после них. У круга свой файл: вердикты первого («отвергнуто»)
     * относятся к старым картинкам и на новые переносить их нельзя.
     */
    private const val ROUND = 3
    private const val FILE = "picture-review-$ROUND.json"
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    private fun file(context: Context) = File(context.filesDir, FILE)

    /**
     * Леммы круга: из `assets/pictures/round.txt` (по строке), а нет его — все
     * картинки папки. Файл кладётся вместе с переделанными картинками, так что
     * экран показывает ровно то, что надо пересмотреть.
     */
    fun lemmas(context: Context): List<String> {
        val listed = runCatching {
            context.assets.open("pictures/round.txt").bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrNull()
        return listed ?: context.assets.list("pictures").orEmpty()
            .filter { it.endsWith(".webp") }
            .map { it.removeSuffix(".webp") }
    }

    /** Всё сохранённое, по лемме. Битый или отсутствующий файл — пусто. */
    fun load(context: Context): Map<String, PictureMark> {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val items = JSONObject(f.readText()).optJSONArray("items") ?: JSONArray()
            buildMap {
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val lemma = o.getString("lemma")
                    put(
                        lemma,
                        PictureMark(
                            lemma = lemma,
                            verdict = if (o.isNull("verdict")) null else o.optString("verdict"),
                            comment = o.optString("comment"),
                            at = o.optLong("at")
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Записать все оценки разом. Пустые (ни вердикта, ни комментария) не пишутся. */
    fun save(context: Context, marks: Map<String, PictureMark>) {
        runCatching {
            val items = JSONArray()
            marks.values
                .filter { it.verdict != null || it.comment.isNotBlank() }
                .sortedBy { it.at }
                .forEach {
                    items.put(
                        JSONObject()
                            .put("lemma", it.lemma)
                            .put("verdict", it.verdict ?: JSONObject.NULL)
                            .put("comment", it.comment)
                            .put("at", it.at)
                    )
                }
            val root = JSONObject()
                .put("device", ComplaintStore(context).deviceTag())
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("round", ROUND)
                .put("items", items)
            file(context).writeText(root.toString(2))
        }
    }

    /** Сколько слов получили вердикт (комментарий без вердикта не в счёт). */
    fun judged(context: Context): Int = load(context).values.count { it.verdict != null }

    /**
     * Архив с одним `picture-review.json` для системной шторки. Кладётся в
     * `cache/share`, как архив записей носителя; предыдущие такие убираются.
     */
    fun exportZip(context: Context): File {
        save(context, load(context)) // свежие device и версия в шапке
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name.startsWith("kartinki-ocenki-")) it.delete() }
        val out = File(dir, "kartinki-ocenki-r$ROUND-${STAMP.format(LocalDateTime.now())}.zip")
        // В архив идут файлы всех кругов, а не только нынешнего: оценки прошлого
        // круга, не отправленные до обновления, иначе остались бы на телефоне.
        val rounds = context.filesDir.listFiles()
            ?.filter { it.name.startsWith("picture-review") && it.name.endsWith(".json") }
            .orEmpty()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in rounds) {
                zip.putNextEntry(ZipEntry(f.name))
                zip.write(f.readBytes())
                zip.closeEntry()
            }
        }
        return out
    }
}
