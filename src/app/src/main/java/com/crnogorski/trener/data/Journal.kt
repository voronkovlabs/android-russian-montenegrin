package com.crnogorski.trener.data

import android.content.Context
import com.crnogorski.trener.BuildConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал прохождений: что реально запускали, на какой сборке и с какой оценкой.
 *
 * Заведён 23.09.2026 по идее владельца. Тестов в проекте нет принципиально —
 * идёт этап лаборатории, — и этот журнал не заменяет их, а отвечает на другой
 * вопрос, который без него приходилось держать в голове: **что из
 * построенного действительно обкатано живым пользованием**.
 *
 * ## Телефон только регистрирует
 *
 * Здесь нет и не будет правила «N дней без жалоб — значит протестировано».
 * Телефон знает, **что запускали**; что при этом **менялось в коде**, знает
 * git, а он на машине разработчика. Значит вывод делается там, при выпуске
 * (`tools/release.py`), а журналу остаются голые факты.
 *
 * Из этого следует всё остальное: журнал не нужно ни сжимать, ни вычёркивать
 * из него обкатанное, ни хранить пороги в APK. Он просто пишется и уезжает.
 *
 * ## Два сигнала, и они очень разной громкости
 *
 * **Тишина слаба.** «Жалоб не было» значит одно из трёх: работает, сломано
 * незаметно, или никто не дошёл. Третье и есть та беда, которой проект боится
 * больше всего: пропажу 88 лемм из словаря ручной прогон не заметил бы никак.
 * Поэтому журнал говорит **числом запусков**, а слово «протестировано» не
 * произносит: три запуска — это совпадение, а не проверка.
 *
 * **Жалоба громка.** Она значит, что сюда дошли и смотрели внимательно. И она
 * же — лапс для единицы курса: как ошибка в `Scheduler` сбрасывает интервал,
 * так жалоба обнуляет накопленную обкатанность, и в зачёт идут только чистые
 * запуски **после починки**. Прошлая сотня не считается — она была до того,
 * как выяснилось, что там сломано.
 *
 * Отсюда самое полезное чтение отчёта, которого сейчас нет ни у чего: **по
 * этой единице была жалоба, её починили, и с тех пор её никто не запускал** —
 * значит чинили вслепую.
 *
 * ## Что пишется
 *
 * Строка на событие, `runs.jsonl` рядом с жалобами и диагностикой:
 *
 * * `t` — `run` (задание прошли) или `rate` (поставили оценку);
 * * `u` — единица: урок (`l58`), история с занятием (`s21:read`) или словарь;
 * * `k` — тип задания (`form`, `paradigm`, `reading`…);
 * * `ok` — верен ли ответ; у показа и пропуска пусто;
 * * `r` — оценка: `1` лайк, `-1` дизлайк, `0` снята;
 * * `v` — `versionCode`, без него журнал бесполезен: карточка, отвеченная в
 *   1.40, ничего не говорит про 3.2;
 * * `w` — хвост хеша устройства.
 *
 * **Устройство лежит в каждой строке, а не только в шапке issue**, и это
 * требование владельца. Оно же условие работоспособности: главное чтение —
 * «никто **из троих** не запускал» — есть утверждение о трёх телефонах, и
 * свести в него три отчёта можно, только если каждая строка знает, чья она.
 * В шапку и в метку оно тоже идёт, но метки живут своей жизнью: их
 * переименовывают и снимают, а текст issue нет.
 *
 * ## Всегда включён
 *
 * Галочки у журнала нет, в отличие от диагностики. Иначе на телефоне со снятой
 * галочкой молчание было бы неотличимо от «никто не открывал» — ложная
 * находка, выглядящая как настоящая, а это худший вид ошибки в приборе.
 *
 * Стоит он одной строки в файл на ответ: занятие это полсотни строк в день.
 */
object Journal {

    private const val FILE = "runs.jsonl"
    private const val PREFS = "crnogorski"
    private const val KEY_WINDOW = "journal_window"

    /** Словарь — не урок, но единица: у него свои задания и свой экран. */
    const val VOCAB = "vocab"

    data class Report(val title: String, val body: String)

    fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE)

    /** Задание прошли. [ok] пусто у показа и пропуска — отвечать там нечего. */
    fun note(context: Context, unit: String, kind: String, ok: Boolean?, who: String) {
        write(context, mapOf("t" to "run", "u" to unit, "k" to kind, "ok" to ok), who)
    }

    /** Поставили оценку: `1`, `-1` или `0`, если выбор сняли. */
    fun rate(context: Context, unit: String, kind: String, rate: Int, who: String) {
        write(context, mapOf("t" to "rate", "u" to unit, "k" to kind, "r" to rate), who)
    }

    private fun write(context: Context, fields: Map<String, Any?>, who: String) {
        val row = buildString {
            append('{')
            append("\"ts\":\"").append(LocalDateTime.now()).append('"')
            fields.forEach { (key, value) ->
                if (value == null) return@forEach
                append(",\"").append(key).append("\":")
                if (value is String) append('"').append(escape(value)).append('"')
                else append(value)
            }
            append(",\"v\":").append(BuildConfig.VERSION_CODE)
            if (who.isNotBlank()) append(",\"w\":\"").append(escape(who)).append('"')
            append("}\n")
        }
        runCatching {
            val f = file(context)
            val fresh = !f.exists() || f.length() == 0L
            f.appendText(row)
            // Окно считается от первой строки, а не от отправки: телефон,
            // пролежавший неделю, не должен слать пустоту по расписанию.
            if (fresh) {
                prefs(context).edit()
                    .putLong(KEY_WINDOW, System.currentTimeMillis())
                    .apply()
            }
        }
    }

    fun count(context: Context): Int =
        runCatching { file(context).readLines().size }.getOrDefault(0)

    /**
     * Пора ли отправлять: набрался объём или вышло окно.
     *
     * Два условия по той же причине, что у диагностики: по одному объёму
     * отчёт с редко занимающегося телефона не ушёл бы месяц, а по одному
     * времени приходил бы из трёх строк.
     */
    fun due(context: Context): Boolean {
        val lines = count(context)
        if (lines == 0) return false
        if (lines >= Config.current.diag.journalRecords) return true
        val start = prefs(context).getLong(KEY_WINDOW, 0L)
        if (start == 0L) return true
        return System.currentTimeMillis() - start >=
            Config.current.diag.journalHours * 3_600_000L
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        prefs(context).edit().remove(KEY_WINDOW).apply()
    }

    /**
     * Отчёт для issue: сводка глазами, сырые строки машине.
     *
     * Оба нужны, и это тот же довод, по которому у жалобы внутри issue лежит
     * исходный JSONL: разметку сверху читает человек, а разобрать её обратно
     * нельзя — пустые поля выброшены, остальное перемешано с подписями. Сырые
     * строки разбирает `release.py`, и именно они тут главные.
     */
    fun report(context: Context, device: String): Report? {
        val lines = runCatching { file(context).readLines() }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val runs = linkedMapOf<String, IntArray>()
        var likes = 0
        var dislikes = 0
        lines.forEach { line ->
            val unit = value(line, "u") ?: return@forEach
            when (value(line, "t")) {
                "run" -> {
                    val row = runs.getOrPut(unit) { IntArray(2) }
                    row[0]++
                    if (line.contains("\"ok\":true")) row[1]++
                }
                "rate" -> {
                    if (line.contains("\"r\":1")) likes++
                    if (line.contains("\"r\":-1")) dislikes++
                }
            }
        }

        val body = buildString {
            appendLine(device)
            appendLine()
            appendLine("Записей: ${lines.size}. Оценки: +$likes / −$dislikes.")
            appendLine()
            appendLine("| единица | запусков | верно |")
            appendLine("|---|--:|--:|")
            runs.entries.sortedByDescending { it.value[0] }.forEach { (unit, row) ->
                appendLine("| $unit | ${row[0]} | ${row[1]} |")
            }
            appendLine()
            appendLine("Вывод «обкатано» делается не здесь, а при выпуске: телефон знает,")
            appendLine("что запускали, но не знает, что менялось в коде.")
            appendLine()
            appendLine("<details><summary>Записи целиком</summary>")
            appendLine()
            appendLine("```json")
            lines.forEach { appendLine(it) }
            appendLine("```")
            appendLine()
            appendLine("</details>")
        }

        val stamp = STAMP.format(LocalDateTime.now())
        return Report("[журнал] $stamp · ${lines.size} записей · $device", body)
    }

    /** Достать строковое поле, не разбирая JSON: строки пишем мы сами. */
    private fun value(line: String, key: String): String? {
        val at = line.indexOf("\"$key\":\"")
        if (at < 0) return null
        val from = at + key.length + 4
        val to = line.indexOf('"', from)
        return if (to < 0) null else line.substring(from, to)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
}
