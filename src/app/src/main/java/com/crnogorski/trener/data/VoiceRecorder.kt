package com.crnogorski.trener.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.provider.MediaStore
import com.crnogorski.trener.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Запись в хранилище: что и где лежит.
 *
 * [path] — путь внутри `zapisi`, то есть `s01/2026-09-20_1412/01.m4a`. Он же
 * имя записи в архиве и он же ключ, по которому потом удаляют отправленное.
 */
data class Recorded(val uri: Uri, val path: String)

/** Архив и то, что в него попало. */
data class Archive(val file: File, val paths: List<String>)

/**
 * Запись голоса носителя по отрезкам истории (идея 99).
 *
 * Телефон отдают носителю языка, он читает историю вслух, мы пишем голос.
 * Назначение — **архив и сырьё для будущих уроков**, а не озвучка приложения:
 * читать эти файлы само приложение не обязано и не умеет. Отсюда всё
 * устройство: важно не потерять и разложить так, чтобы через полгода было
 * понятно, что где лежит.
 *
 * ## Куда пишем и почему не к себе
 *
 * Хотелось писать в каталог приложения, как жалобы и копию прогресса, а папку
 * добавить в OneDrive и дать ему синхронизировать самому. Так не выйдет:
 * каталог приложения — это `/Android/data/<пакет>/files/`, а с Android 11 в
 * `Android/data` посторонним приложениям ходу нет вовсе. OneDrive такую папку
 * не увидит, и весь замысел рассыпается.
 *
 * Поэтому записи идут в **общее хранилище**, в обычную папку «Документы», через
 * `MediaStore`. Разрешений это не требует: своё приложение кладёт свои файлы.
 * Папку видно любым файловым менеджером — её и добавляют в облако.
 *
 * ## Порядок: сперва на диск, потом в хранилище
 *
 * `MediaRecorder` пишет во **временный файл** в каталоге приложения, и только
 * после остановки файл переносится в «Документы». Два довода, и оба из опыта
 * проекта. Писать напрямую в общее хранилище значит зависеть от него посреди
 * фразы. А неудавшийся перенос так оставляет запись на телефоне — потерять
 * голос живого человека, который пришёл в гости и читал полчаса, нельзя.
 *
 * ## Раскладка
 *
 * ```
 * Документы/Crnogorski/zapisi/<история>/<2026-09-20_1412>/
 *     01.m4a, 02.m4a, …
 *     zapis.json
 * ```
 *
 * Папка на каждое прохождение — просьба владельца: носитель может прочесть одну
 * историю трижды, и сравнивать дубли удобнее целыми заходами.
 *
 * `zapis.json` обязателен, и не для порядка. У отрезков истории **нет своих
 * идентификаторов**, они позиционные ([StoryChunk] — это просто `{sr, ru}` по
 * порядку). Значит номер файла — единственная привязка, и первая же
 * перенарезка истории сделает её ложной. Текст отрезка, лежащий рядом со
 * звуком, переживает и перенарезку: по нему запись найдёт своё место всегда.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var temp: File? = null

    /** Куда складываем этот заход: `zapisi/s01/2026-09-20_1412`. */
    private var pass: String? = null

    /** Что уже записано в этом заходе — из этого соберётся `zapis.json`. */
    private val done = mutableListOf<Entry>()

    private data class Entry(val index: Int, val file: String, val sr: String, val who: String)

    /** Идёт ли запись прямо сейчас — по этому экран рисует «Стоп». */
    var recording: Boolean = false
        private set

    /**
     * Начать заход по истории. Зовётся при входе в неё, а не при первой записи:
     * имя папки — это время начала чтения, а не время первой удачной фразы.
     */
    fun startPass(storyId: String) {
        pass = "$ROOT/$storyId/${STAMP.format(LocalDateTime.now())}"
        done.clear()
    }

    /**
     * Начать запись отрезка.
     *
     * Повторный вызов без остановки ничего не делает: кнопка блокируется на
     * экране, но между нажатием и стартом движка есть щель.
     */
    fun start() {
        if (recording) return
        val file = File(context.cacheDir, "zapis.m4a")
        runCatching {
            @Suppress("DEPRECATION")
            val rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            temp = file
            recording = true
        }.onFailure { release() }
    }

    /**
     * Остановить запись и перенести её в «Документы».
     *
     * [index] — номер отрезка, он же имя файла. **Перезапись отрезка заменяет
     * прежний файл**, а не кладётся рядом: носитель слышит, что оговорился, и
     * жмёт «Ещё раз» — вторая попытка это и есть запись, а первая мусор.
     * Заменяется и строка в списке для `zapis.json`.
     *
     * Возвращает `true`, если запись легла в общее хранилище.
     */
    suspend fun stop(index: Int, chunk: StoryChunk): Boolean = withContext(Dispatchers.IO) {
        val file = temp
        val folder = pass
        // Остановка умеет бросить, если записи не было вовсе (нажали и сразу
        // отпустили): движок в этом случае считает файл негодным и прав.
        val ok = runCatching { recorder?.stop() }.isSuccess
        release()
        if (!ok || file == null || folder == null || !file.exists()) {
            file?.delete()
            return@withContext false
        }

        val name = "%02d.m4a".format(index + 1)
        val moved = runCatching { put(folder, name, "audio/mp4", file.readBytes()) }
            .getOrDefault(false)
        if (moved) {
            file.delete()
            done.removeAll { it.index == index }
            done += Entry(index, name, chunk.sr, chunk.who)
            done.sortBy { it.index }
        }
        moved
    }

    /** Бросить начатую запись, ничего не сохраняя: ушли с экрана посреди фразы. */
    fun cancel() {
        runCatching { recorder?.stop() }
        release()
        temp?.delete()
        temp = null
    }

    /**
     * Дописать опись захода.
     *
     * Пишется **после каждого отрезка**, а не в конце истории: носитель бросит
     * чтение на середине, экран закроют, процесс убьют — а опись должна
     * описывать то, что уже лежит рядом. Файл один и перезаписывается целиком,
     * он маленький.
     */
    suspend fun writeManifest(storyId: String, title: String) = withContext(Dispatchers.IO) {
        val folder = pass ?: return@withContext
        if (done.isEmpty()) return@withContext
        val body = JSONObject()
            .put("storyId", storyId)
            .put("title", title)
            .put("recordedAt", LocalDateTime.now().toString())
            .put("app", BuildConfig.VERSION_NAME)
            .put(
                "chunks",
                JSONArray().apply {
                    done.forEach {
                        put(
                            JSONObject()
                                .put("index", it.index + 1)
                                .put("file", it.file)
                                .put("sr", it.sr)
                                .put("who", it.who)
                        )
                    }
                }
            )
            .toString(1)
        runCatching { put(folder, MANIFEST, "application/json", body.toByteArray()) }
        Unit
    }

    /** Сколько отрезков записано в этом заходе — для строки на экране. */
    val count: Int get() = done.size

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
        recording = false
    }

    /**
     * Положить файл в общее хранилище.
     *
     * Существующий с тем же именем сначала удаляется: иначе `MediaStore` заведёт
     * рядом «01 (1).m4a», и перезапись отрезка превратилась бы в свалку.
     */
    private fun put(folder: String, name: String, mime: String, bytes: ByteArray): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = "${android.os.Environment.DIRECTORY_DOCUMENTS}/$folder"
        drop(collection, path, name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
        }
        val uri: Uri = resolver.insert(collection, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun drop(collection: Uri, path: String, name: String) {
        runCatching {
            context.contentResolver.delete(
                collection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("$path%", name)
            )
        }
    }

    companion object {
        private const val ROOT = "Crnogorski/zapisi"
        private const val MANIFEST = "zapis.json"

        /**
         * Собрать всё записанное в один zip с датой отправки.
         *
         * Сначала файлы отдавались шторке списком, и в OneDrive они легли
         * **плоско**, без папок (найдено владельцем 20.09.2026). Это хуже,
         * чем просто неаккуратно: у каждого прохождения файлы зовутся одинаково
         * (`01.m4a`, `02.m4a`…), и в одной папке они сталкиваются именами.
         * Архив же едет одним файлом, и строение папок лежит внутри него.
         *
         * Кладётся в `cache/share`, а не рядом с записями: архив — вещь
         * одноразовая, и в следующий раз он уехал бы внутрь самого себя.
         *
         * Сжатие отключено (`STORED`): m4a уже сжат, и пропускать его через
         * deflate значит тратить время ради процента размера. `zapis.json`
         * маленький, его судьба ничего не решает.
         */
        suspend fun archive(context: Context): Archive? = withContext(Dispatchers.IO) {
            val files = files(context)
            if (files.isEmpty()) return@withContext null
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            // Старые архивы убираем: кэш не место для истории отправок.
            dir.listFiles()?.forEach { if (it.name.startsWith("zapisi-")) it.delete() }
            val out = File(dir, "zapisi-${STAMP.format(LocalDateTime.now())}.zip")
            val packed = mutableListOf<String>()
            runCatching {
                ZipOutputStream(out.outputStream().buffered()).use { zip ->
                    zip.setMethod(ZipOutputStream.STORED)
                    files.forEach { rec ->
                        val bytes = context.contentResolver.openInputStream(rec.uri)
                            ?.use { it.readBytes() } ?: return@forEach
                        val entry = ZipEntry(rec.path).apply {
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            crc = CRC32().apply { update(bytes) }.value
                        }
                        zip.putNextEntry(entry)
                        zip.write(bytes)
                        zip.closeEntry()
                        packed += rec.path
                    }
                }
            }.onFailure {
                out.delete()
                return@withContext null
            }
            if (packed.isEmpty()) {
                out.delete()
                return@withContext null
            }
            Archive(out, packed)
        }

        /**
         * Удалить записи по их путям внутри `zapisi`.
         *
         * Удаляется ровно то, что попало в архивы, и ничего сверх того:
         * если после отправки носитель прочёл ещё одну историю, её записи
         * останутся. Голос человека, который пришёл в гости и читал полчаса,
         * второй раз не запишешь.
         */
        fun forget(context: Context, paths: Set<String>): Int {
            if (paths.isEmpty()) return 0
            var gone = 0
            files(context).forEach { rec ->
                if (rec.path in paths) {
                    runCatching { context.contentResolver.delete(rec.uri, null, null) }
                        .onSuccess { gone += it }
                }
            }
            return gone
        }

        /**
         * Все записи, что лежат в общем хранилище, — для отправки в облако.
         *
         * Нужно потому, что **OneDrive не синхронизирует произвольную папку**.
         * Замысел владельца («добавлю папку в OneDrive, дальше он сам») не
         * работает: в приложении OneDrive для Android автоматическая выгрузка
         * есть только у камеры и берёт она лишь изображения и видео, а его
         * провайдер в системном выборе файлов умеет открывать, но не создавать.
         * Значит дописать файл в облако мы не можем ни сами, ни через систему.
         *
         * Остаётся отдать файлы системной шторке и дать человеку выбрать
         * OneDrive — одно нажатие вместо ручного похода в два приложения.
         *
         * Спрашиваем у `MediaStore`, а не помним список у себя: файлы переживают
         * и перезапуск, и переустановку приложения, и складывал их, может быть,
         * прошлый месяц. Хранилище и есть единственная правда о том, что есть.
         */
        fun files(context: Context): List<Recorded> {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val root = "${android.os.Environment.DIRECTORY_DOCUMENTS}/$ROOT"
            val found = mutableListOf<Recorded>()
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME
                    ),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$root%"),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} ASC, " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} ASC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val rel = (c.getString(pathCol) ?: "").trim('/')
                        val inside = rel.removePrefix(root).trim('/')
                        val name = c.getString(nameCol) ?: continue
                        found += Recorded(
                            uri = android.content.ContentUris.withAppendedId(
                                collection, c.getLong(idCol)
                            ),
                            path = if (inside.isBlank()) name else "$inside/$name"
                        )
                    }
                }
            }
            return found
        }

        /**
         * Моно, 44,1 кГц, 128 кбит/с.
         *
         * Речь одного человека в помещении; стерео тут нечего писать, а
         * экономить дальше незачем: история из двенадцати отрезков это около
         * минуты звука и пара мегабайт. Материал собирается один раз и, может
         * быть, надолго — резать качество ради места глупо.
         */
        private const val SAMPLE_RATE = 44_100
        private const val BIT_RATE = 128_000

        private val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

        /** Путь для строки в настройках: человеку надо знать, где искать. */
        const val HUMAN_PATH = "Документы/Crnogorski/zapisi"

        private const val PREFS = "crnogorski"
        private const val KEY_ON = "native_mode"
        private const val KEY_SENT = "voice_sent"

        /**
         * Что уже уехало в архиве.
         *
         * Помним пути, а не факт «отправляли»: между отправкой и удалением
         * носитель может прочитать ещё историю, и свежие записи стирать нельзя.
         * Кнопка удаления поэтому говорит не «очистить», а «удалить
         * отправленное», и удаляет ровно перечисленное.
         */
        fun sent(context: Context): Set<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_SENT, emptySet()).orEmpty()

        fun noteSent(context: Context, paths: Collection<String>) {
            val all = sent(context) + paths
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_SENT, all).apply()
        }

        fun clearSent(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_SENT).apply()
        }

        /**
         * Включён ли режим носителя.
         *
         * Живёт в тех же настройках телефона, что галочка звука на заставке и
         * распознавание без сети, и читается прямо там, где нужен, — режим
         * телефонный, а не курсовой, и в `config/tuning.json` ему не место:
         * настройки курса едут на все три телефона разом, а носитель приходит
         * к одному.
         */
        fun isOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ON, false)

        fun setOn(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ON, on).apply()
        }
    }
}
