package com.crnogorski.trener.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.crnogorski.trener.BuildConfig
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Диагностика подвисаний: сколько времени занимает то, что может занять много.
 *
 * Заведена по жалобе владельца — «приложение периодически подвисает», и заметно
 * это **на запуске**. Гадать тут нечем: в первые секунды приложение читает базу,
 * разбирает 400 КБ словаря, собирает ежедневное задание и лезет в сеть сразу за
 * тремя вещами (настройки курса, свежий релиз, очередь жалоб). Любое из этого
 * может встать на десятки секунд, и снаружи все они выглядят одинаково.
 *
 * Поэтому замер, а не рассуждение.
 *
 * ## Что меряется
 *
 * [span] оборачивает подозрительный вызов и записывает, сколько он занял.
 * Обёрнуто то, что ходит в сеть, на диск или в базу, — список в `CLAUDE.md`.
 *
 * Отдельно работает **сторож главного потока**: раз в секунду он просит главный
 * поток ответить и записывает задержку, если тот молчал дольше порога. Без него
 * картина была бы неполной и обманчивой: подвисание бывает и вне нашего кода —
 * сборка мусора, инициализация синтезатора речи, файловая система, — и тогда
 * все наши замеры окажутся быстрыми, а телефон всё равно стоял колом.
 *
 * ## Что хранится
 *
 * Двумя частями, и это не прихоть:
 *
 * * **сводка по каждому имени** (сколько раз, сумма, худший) — по ней видно,
 *   куда уходит время вообще, включая то, что быстро, но вызывается тысячу раз;
 * * **сырые события дольше порога** — по ним видно **порядок**: что за чем шло
 *   в ту секунду, когда экран замер. Сводка порядка не хранит, а именно он чаще
 *   всего и объясняет подвисание.
 *
 * ## Когда уезжает
 *
 * События копятся в памяти, при уходе с экрана ложатся в файл, а **отправляется
 * файл при следующем запуске** — если он старше окна или в нём накопилось
 * больше предела. Порядок именно такой, потому что диагностируем мы запуск:
 * трасса запуска дописывается уже после самого запуска, и отправить её в том же
 * запуске значило бы отправить половину. Заодно отправка не вмешивается в то,
 * что измеряет.
 *
 * Вручную — кнопкой в настройках: она сбрасывает память в файл и шлёт всё сразу.
 *
 * ## Цена
 *
 * Выключено — [span] вызывает переданный кусок и больше ничего: одна проверка
 * булева поля. Включено — два чтения часов и запись в память; на диск пишем
 * пачкой, при уходе с экрана. Сторож — один спящий поток и одно сообщение в
 * секунду. По умолчанию всё выключено: диагностика нужна, пока ловят ошибку, а
 * не всегда.
 */
object Trace {

    private const val PREFS = "crnogorski"
    private const val KEY_ON = "diag_on"
    private const val KEY_WINDOW = "diag_window_start"
    private const val KEY_EXIT_SEEN = "diag_exit_seen"
    private const val FILE = "diag.jsonl"

    /**
     * Включена ли запись. Публичное поле, а не функция, потому что [span]
     * встраивается в место вызова и читает его напрямую — иначе выключенная
     * диагностика стоила бы вызова метода на каждом замере.
     */
    @Volatile
    var enabled: Boolean = false
        private set

    /** Сводка: имя → [сколько раз, сумма мс, худший мс]. */
    private val totals = ConcurrentHashMap<String, LongArray>()

    /** Сырые события дольше порога, в порядке появления. */
    private val slow = Collections.synchronizedList(mutableListOf<Rec>())

    private var watchdog: Thread? = null
    private val started = SystemClock.uptimeMillis()

    private data class Rec(val at: Long, val name: String, val ms: Long, val note: String)

    /** Готовый отчёт: заголовок для списка issue и тело со сводкой. */
    data class Report(val title: String, val body: String)

    // ------------------------------------------------------------ включение

    /**
     * Прочитать галочку и, если включено, поставить сторожа.
     *
     * Зовётся из `MainActivity.onCreate` первой строкой: всё, что случится
     * позже, уже можно мерить.
     */
    fun init(context: Context) {
        enabled = prefs(context).getBoolean(KEY_ON, false)
        if (enabled) startWatchdog()
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
        enabled = on
        if (on) startWatchdog() else {
            totals.clear()
            slow.clear()
        }
    }

    // -------------------------------------------------------------- запись

    /**
     * Замерить кусок работы.
     *
     * Встраиваемая (`inline`), поэтому годится и в обычном коде, и внутри
     * `suspend`-функций: переданный кусок исполняется прямо на месте вызова и
     * приостановки внутри него законны.
     *
     * Замер снимается **и когда внутри бросили исключение** — оборвавшийся
     * вызов тоже занял время, и знать об этом надо.
     */
    inline fun <T> span(name: String, note: String = "", block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            event(name, SystemClock.uptimeMillis() - t0, note)
        }
    }

    /** Записать готовый замер — для сторожа и для событий без своего куска кода. */
    fun event(name: String, ms: Long, note: String = "") {
        if (!enabled) return
        totals.compute(name) { _, old ->
            if (old == null) longArrayOf(1, ms, ms)
            else longArrayOf(old[0] + 1, old[1] + ms, maxOf(old[2], ms))
        }
        if (ms >= slowMs && slow.size < maxRecords) {
            slow += Rec(SystemClock.uptimeMillis() - started, name, ms, note)
        }
    }

    /** Есть ли что показывать: по этому числу настройки говорят о накопленном. */
    fun count(): Int = totals.values.sumOf { it[0] }.toInt()

    fun worstMs(): Long = totals.values.maxOfOrNull { it[2] } ?: 0

    // ------------------------------------------------------- сторож потока

    /**
     * Сторож главного потока.
     *
     * Раз в секунду просит главный поток выполнить пустяк и засекает, сколько
     * тот собирался. Это единственный замер, который видит подвисание **вне**
     * нашего кода, — а значит единственный, которому можно верить, когда все
     * наши замеры быстрые, а приложение всё равно стоит.
     *
     * Поток демонский и один: повторный вызов ничего не создаёт.
     */
    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        val main = Handler(Looper.getMainLooper())
        watchdog = thread(isDaemon = true, name = "diag-watchdog") {
            while (enabled) {
                val sent = SystemClock.uptimeMillis()
                val answered = CountDownLatch(1)
                main.post { answered.countDown() }
                val ok = answered.await(30, TimeUnit.SECONDS)
                val waited = SystemClock.uptimeMillis() - sent
                if (!ok || waited >= freezeMs) {
                    event("главный поток занят", waited, if (ok) "" else "не ответил за 30 с")
                }
                runCatching { Thread.sleep(1000) }
            }
        }
    }

    // ------------------------------------------------- как умер прошлый раз

    /**
     * Спросить систему, почему прошлый процесс умер, и записать в отчёт.
     *
     * Наши замеры видят только то, что успели записать живыми. Сторож главного
     * потока ловит замирание, но чем оно кончилось — пережил телефон паузу или
     * прибил приложение, — не знает никто: зависшее приложение не даёт написать
     * жалобу, а «оно само закрылось» никто не пишет, просто открывают заново.
     * В отчётах 50 и 60 главный поток стоял 41 и 23 секунды при системном пороге
     * неотвечающего приложения около пяти, и чем это кончилось у Кати с Володей,
     * мы не знаем до сих пор.
     *
     * Android ведёт этот журнал сам, и спросить его — один запрос без
     * разрешений. Отсюда три решения:
     *
     * * **это не ловушка падений.** Своего обработчика в умирающем процессе мы
     *   не ставим: за всю жизнь проекта не пришло ни одной жалобы на падение
     *   (96 issue), а код в уже сломанном процессе — самое дорогое место, где
     *   можно ошибиться. Здесь же запрос выполняется в здоровом приложении и
     *   только читает;
     * * **видно то, чего наш обработчик не увидел бы вовсе** — ANR, убийство по
     *   памяти, падение в нативном коде;
     * * **скучное не записывается.** Сам вышел, смахнули из недавних, обновили
     *   пакет — это не события, а обычная жизнь процесса, и в отчёте они были бы
     *   шумом.
     *
     * Каждая запись попадает в отчёт **один раз**: система хранит журнал долго
     * и отдавала бы одно и то же при каждом запуске. Отметка — время последней
     * показанной записи.
     *
     * У ANR и падений система хранит ещё и трассу; берём от неё начало, потому
     * что там стек главного потока — то есть ровно ответ на «на чём стояли».
     */
    fun noteExits(context: Context) {
        if (!enabled) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val p = prefs(context)
            val first = !p.contains(KEY_EXIT_SEEN)
            val seen = p.getLong(KEY_EXIT_SEEN, 0L)
            val all = am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            val fresh = all
                .filter { it.timestamp > seen && it.reason in TELLING }
                .sortedBy { it.timestamp }
            // Первый раз отмечаемся в отчёте, даже когда сообщать нечего.
            //
            // Проверить это на машине разработчика нечем: журнал ведёт система, и
            // пока ничего не умерло, молчащий код и сломанный код выглядят одинаково.
            // Отметка ставится один раз за установку и дальше молчит навсегда.
            if (fresh.isEmpty() && !first) return
            val marker = if (!first) emptyList() else listOf(
                JSONObject()
                    .put("t", "x")
                    .put("at", now())
                    .put("mark", true)
                    .put("why", "журнал смертей прочитан, система помнит записей: ${all.size}")
                    .put("fg", false)
                    .put("note", "")
                    .put("trace", "")
                    .toString()
            )
            val lines = marker + fresh.map { info ->
                JSONObject()
                    .put("t", "x")
                    .put("at", stamp(info.timestamp))
                    .put("why", reasonName(info.reason))
                    // Был ли человек в это время в приложении. Убийство по
                    // памяти в фоне — обычное дело, на экране — беда.
                    .put("fg", info.importance <= IMPORTANCE_FOREGROUND)
                    .put("note", info.description.orEmpty())
                    .put("trace", trace(info))
                    .toString()
            }
            file(context).appendText(lines.joinToString("\n", postfix = "\n"))
            // При пустом списке отмечаем текущее время: иначе ключ остался бы
            // незаданным и отметка повторялась бы каждый запуск.
            p.edit()
                .putLong(KEY_EXIT_SEEN, fresh.lastOrNull()?.timestamp ?: System.currentTimeMillis())
                .apply()
        }
    }

    /** Причины, о которых стоит знать. Остальное — обычная жизнь процесса. */
    private val TELLING = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_FREEZER,
    )

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "упало с исключением"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "упало в нативном коде"
        ApplicationExitInfo.REASON_ANR -> "не отвечало, система закрыла"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "убито из-за нехватки памяти"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "убито за прожорливость"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "не смогло запуститься"
        ApplicationExitInfo.REASON_SIGNALED -> "убито сигналом"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "умерло вместе с чужим процессом"
        ApplicationExitInfo.REASON_FREEZER -> "заморожено системой"
        else -> "причина $reason"
    }

    /**
     * Начало системной трассы, если она есть.
     *
     * Только начало: у ANR это несколько сотен килобайт со стеками всех
     * потоков, а нужен первый — главный. Тело issue ограничено по длине, и
     * целая трасса вытеснила бы из отчёта всё остальное.
     */
    private fun trace(info: ApplicationExitInfo): String =
        runCatching {
            info.traceInputStream?.bufferedReader()?.use { reader ->
                val buf = CharArray(TRACE_HEAD)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }.orEmpty()
        }.getOrDefault("")

    // --------------------------------------------------------------- файл

    /**
     * Сбросить накопленное в файл и очистить память.
     *
     * Зовётся при уходе с экрана: процесс могут убить в любой момент, а
     * потерять надо как можно меньше. Пишем пачкой — построчная запись на
     * каждый замер сама стала бы тем, что мы ищем.
     */
    suspend fun park(context: Context) = withContext(Dispatchers.IO) { parkNow(context) }

    /**
     * То же, но без корутины — из `Activity.onStop`, где ждать нельзя и некому.
     *
     * Отдельный поток на пару десятков строк: запись идёт после того, как экран
     * уже уехал, и задержать она может только саму себя.
     */
    fun parkAsync(context: Context) {
        if (!enabled) return
        val app = context.applicationContext
        thread(isDaemon = true, name = "diag-park") { parkNow(app) }
    }

    private fun parkNow(context: Context) {
        if (totals.isEmpty() && slow.isEmpty()) return
        val lines = buildList {
            add(
                JSONObject()
                    .put("t", "run")
                    .put("at", now())
                    // Версия — в каждом запуске, а не в заголовке отчёта: в
                    // одном отчёте могут лежать запуски разных сборок, и без
                    // этого непонятно, что уже починено. В первых трёх отчётах
                    // её не было вовсе, и версию пришлось восстанавливать по
                    // времени выпуска.
                    .put("ver", BuildConfig.VERSION_NAME)
                    .put("uptime", SystemClock.uptimeMillis() - started)
                    .toString()
            )
            totals.forEach { (name, v) ->
                add(
                    JSONObject().put("t", "a").put("n", name)
                        .put("c", v[0]).put("s", v[1]).put("m", v[2]).toString()
                )
            }
            synchronized(slow) {
                slow.forEach {
                    add(
                        JSONObject().put("t", "s").put("at", it.at).put("n", it.name)
                            .put("ms", it.ms).put("note", it.note).toString()
                    )
                }
            }
        }
        totals.clear()
        slow.clear()

        val f = file(context)
        runCatching {
            f.appendText(lines.joinToString("\n", postfix = "\n"))
            val p = prefs(context)
            if (!p.contains(KEY_WINDOW)) {
                p.edit().putLong(KEY_WINDOW, System.currentTimeMillis()).apply()
            }
        }
        Unit
    }

    fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE)

    /**
     * Пора ли отправлять: окно вышло или записей набралось больше предела.
     *
     * Два условия, а не одно, потому что занятие идёт пятнадцать минут в день:
     * по одному лишь размеру отчёт не ушёл бы неделю, а по одному лишь времени
     * — оказался бы из одной строки в день, когда телефон лежит.
     */
    fun due(context: Context): Boolean {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) return false
        val lines = runCatching { f.readLines().size }.getOrDefault(0)
        if (lines >= maxRecords) return true
        val start = prefs(context).getLong(KEY_WINDOW, 0L)
        if (start == 0L) return true
        return System.currentTimeMillis() - start >= windowMinutes * 60_000L
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        prefs(context).edit().remove(KEY_WINDOW).apply()
    }

    // -------------------------------------------------------------- отчёт

    /**
     * Отчёт для issue: сводка сверху, сырые долгие события под ней.
     *
     * Сводка отсортирована по **суммарному** времени, а не по худшему случаю:
     * секунда, потраченная сорока вызовами, и секунда одним вызовом требуют
     * разного лечения, но обе стоят одинаково, и увидеть надо обе.
     *
     * Длина тела issue ограничена, поэтому сырые события режутся с конца, а
     * сводка не режется никогда: без неё отчёт не читается вовсе.
     */
    fun report(context: Context, device: String): Report? {
        val f = file(context)
        if (!f.exists()) return null
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) return null

        val agg = linkedMapOf<String, LongArray>()
        val events = mutableListOf<String>()
        val runs = mutableListOf<String>()
        val deaths = mutableListOf<String>()
        val traces = mutableListOf<Pair<String, String>>()
        val marks = mutableListOf<String>()
        lines.forEach { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            when (o.optString("t")) {
                "run" -> runs += "%s · %s (в приложении %s)".format(
                    o.optString("at"),
                    o.optString("ver").ifBlank { "версия неизвестна" },
                    human(o.optLong("uptime"))
                )

                "a" -> {
                    val v = agg.getOrPut(o.optString("n")) { longArrayOf(0, 0, 0) }
                    v[0] += o.optLong("c")
                    v[1] += o.optLong("s")
                    v[2] = maxOf(v[2], o.optLong("m"))
                }

                "x" -> if (o.optBoolean("mark")) {
                    marks += "%s · %s".format(o.optString("at"), o.optString("why"))
                } else {
                    val at = o.optString("at")
                    deaths += buildString {
                        append(at)
                        append(" · ")
                        append(o.optString("why"))
                        append(if (o.optBoolean("fg")) " · был на экране" else " · в фоне")
                        val note = o.optString("note")
                        if (note.isNotBlank()) append(" · ").append(note)
                    }
                    // Трасса идёт не в пункт списка, а отдельным блоком: блок
                    // кода внутри пункта Markdown рендерит криво.
                    val tr = o.optString("trace")
                    if (tr.isNotBlank()) traces += at to tr.trimEnd()
                }

                "s" -> events += "%7s  %-34s %s".format(
                    human(o.optLong("ms")), o.optString("n"), o.optString("note")
                ).trimEnd()
            }
        }
        if (agg.isEmpty() && events.isEmpty() && deaths.isEmpty() && marks.isEmpty()) {
            return null
        }

        val table = agg.entries
            .sortedByDescending { it.value[1] }
            .joinToString("\n") { (name, v) ->
                "| %s | %d | %s | %s | %s |".format(
                    name, v[0], human(v[1]), human(v[1] / maxOf(v[0], 1)), human(v[2])
                )
            }

        val worst = agg.values.maxOfOrNull { it[2] } ?: 0L
        val head = buildString {
            appendLine("Запусков в отчёте: ${runs.size}")
            runs.takeLast(10).forEach { appendLine("* $it") }
            appendLine()
            // Смерти процесса — выше сводки времён: секунды показывают, где
            // медленно, а это показывает, что приложение закрылось не само.
            marks.forEach { appendLine("_$it._") }
            if (marks.isNotEmpty()) appendLine()
            if (deaths.isNotEmpty()) {
                appendLine("**Процесс умирал не по-хорошему (${deaths.size}):**")
                appendLine()
                deaths.forEach { appendLine("* $it") }
                appendLine()
                traces.forEach { (at, tr) ->
                    appendLine("<details><summary>Трасса системы: $at</summary>")
                    appendLine()
                    appendLine("```")
                    appendLine(tr)
                    appendLine("```")
                    appendLine()
                    appendLine("</details>")
                    appendLine()
                }
            }
            appendLine("| что | раз | всего | среднее | худший |")
            appendLine("|---|--:|--:|--:|--:|")
            appendLine(table)
        }

        val tail = if (events.isEmpty()) "" else buildString {
            appendLine()
            appendLine("<details><summary>Долгие события по порядку (${events.size})</summary>")
            appendLine()
            appendLine("```")
            events.takeLast(MAX_EVENTS).forEach { appendLine(it) }
            if (events.size > MAX_EVENTS) {
                appendLine("… ещё ${events.size - MAX_EVENTS}, срезаны по размеру issue")
            }
            appendLine("```")
            appendLine()
            appendLine("</details>")
        }
        val title = if (deaths.isEmpty()) {
            "[диагностика] ${now()} · худшее ${human(worst)} · $device"
        } else {
            // В списке issue видно, не открывая: этот отчёт не про секунды.
            "[диагностика] ${now()} · процесс умирал (${deaths.size}) · $device"
        }
        return Report(title.take(120), head + tail)
    }

    private fun human(ms: Long): String = when {
        ms >= 10_000 -> "%.0f с".format(ms / 1000.0)
        ms >= 1_000 -> "%.1f с".format(ms / 1000.0)
        else -> "$ms мс"
    }

    private fun now(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))

    /** То же в человеческом виде, но для чужой отметки времени — смерти процесса. */
    private fun stamp(millis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ пределы

    /** Что считать долгим событием: короче — только в сводку. */
    val slowMs: Long get() = Config.current.diag.slowMs.toLong()

    /** С какой задержки главного потока считать, что приложение подвисло. */
    val freezeMs: Long get() = Config.current.diag.freezeMs.toLong()

    /** Сколько копить, прежде чем отправлять. */
    val windowMinutes: Int get() = Config.current.diag.windowMinutes

    val maxRecords: Int get() = Config.current.diag.maxRecords

    /** Сколько сырых событий влезает в issue: тело ограничено 64 КБ. */
    private const val MAX_EVENTS = 400

    /**
     * Сколько знаков системной трассы берём.
     *
     * У ANR это сотни килобайт со стеками всех потоков; нужен первый, главный,
     * и он идёт в начале. Больше не влезло бы в issue вместе с остальным.
     */
    private const val TRACE_HEAD = 2000

    /**
     * Порог «человек был в приложении» в шкале важности процесса.
     *
     * Меньше — значит важнее: у переднего плана 100. Константа своя, потому что
     * `RunningAppProcessInfo.IMPORTANCE_FOREGROUND` тянул бы сюда ещё один
     * импорт ради одного числа, которое не менялось никогда.
     */
    private const val IMPORTANCE_FOREGROUND = 100
}
