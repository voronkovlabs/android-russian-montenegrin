package com.crnogorski.trener.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Откуда читать уроки: из APK или из скачанного пакета уроков (0.51).
 *
 * Уроки весят меньше мегабайта, APK — шестьдесят. Чтобы новый урок не тянул за
 * собой всё приложение, уроки выходят ещё и отдельным пакетом (релиз
 * `katya-lessons-N`, см. `net/LessonPack.kt`), а здесь решается, чьи файлы
 * брать.
 *
 * В пакете — `lessons/`, `stories/`, `glossary.json` и `content.json` с номером.
 * Тот же `content.json` лежит в assets самого APK. Берётся **более свежий** из
 * двух: после обновления APK его собственные уроки могут оказаться новее
 * скачанного когда-то пакета, и тогда пакет молча отходит в сторону.
 *
 * Файла нет в пакете — читаем из APK: так пакет не обязан повторять всё.
 */
object Content {

    /** Каталог действующего пакета; `null` — читаем из APK. */
    @Volatile private var active: File? = null
    @Volatile private var ready = false

    /**
     * Растёт при каждой смене источника. [LessonRepository] сравнивает его со
     * своим и сбрасывает кэш: экземпляров репозитория несколько, а кэш у
     * каждого свой.
     */
    @Volatile var generation = 0
        private set

    private fun root(context: Context) = File(context.filesDir, "lessons-pack")

    /** Номер уроков внутри APK. */
    fun bundledVersion(context: Context): Int = runCatching {
        context.assets.open(INFO).bufferedReader().use { JSONObject(it.readText()).optInt("version") }
    }.getOrDefault(0)

    /** Номер скачанного пакета; 0 — пакета нет. */
    fun packVersion(context: Context): Int =
        root(context).listFiles()?.mapNotNull { it.name.toIntOrNull()?.takeIf { _ -> it.isDirectory } }
            ?.maxOrNull() ?: 0

    /** Номер уроков, которые сейчас показываются. */
    fun version(context: Context): Int {
        ensure(context)
        return if (active != null) packVersion(context) else bundledVersion(context)
    }

    private fun ensure(context: Context) {
        if (ready) return
        refresh(context, bump = false)
    }

    /** Пересчитать, откуда читать. */
    fun refresh(context: Context, bump: Boolean = true) {
        val pack = packVersion(context)
        val dir = File(root(context), pack.toString())
        active = if (pack > bundledVersion(context) && dir.isDirectory) dir else null
        ready = true
        // Первое чтение кэшей ещё не застало — сбрасывать нечего.
        if (bump) generation++
        // Всё, кроме действующего, больше не нужно.
        root(context).listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
    }

    fun read(context: Context, path: String): String {
        ensure(context)
        active?.let { dir ->
            val file = File(dir, path)
            if (file.isFile) return file.readText()
        }
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    /**
     * Распаковать скачанный архив как пакет [version].
     *
     * Сперва во временный каталог, потом переименование: оборвись распаковка
     * посередине — полупакета не останется, и уроки будут прежними. Старые
     * пакеты удаляются, когда новый вступает в дело ([refresh]).
     */
    fun install(context: Context, zip: File, version: Int) {
        val root = root(context).apply { mkdirs() }
        val tmp = File(root, "$version.tmp").apply { deleteRecursively(); mkdirs() }
        val base = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(tmp, entry.name)
                // Имя вида «../../x» вывело бы запись за пределы каталога.
                if (!out.canonicalPath.startsWith(base)) error("чужой путь в архиве: ${entry.name}")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
        if (!File(tmp, INFO).isFile) error("в архиве нет $INFO")
        val dir = File(root, version.toString())
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) error("не переименовался каталог пакета")
        // В дело пакет вступает только по [refresh]: посреди занятия уроки
        // подменять нельзя, а прежний пакет до тех пор остаётся на месте.
    }

    private const val INFO = "content.json"
}
