package com.crnogorski.trener.data

import android.content.Context

/**
 * Одна карточка виджета — готовая к показу, без единого поиска по данным.
 *
 * [word] лежит уже в том виде, в каком его увидят: иекавское написание
 * подставлено ([Ijekavica.show]), [stress] — номер ударной буквы в этой самой
 * строке или `-1`, если мы в ударении не уверены. Так сделано затем, чтобы
 * виджет ничего не искал: сложить `Ijekavica.show` в одном месте, а ударение
 * считать в другом значило бы однажды разметить не ту букву.
 */
data class WidgetWord(
    val word: String,
    val gloss: String,
    val stress: Int = -1,
    val emoji: String = "",
    /**
     * Лемма — ключ карточки. Показывается не она, а [word], но отметить слово
     * выученным (4.7) можно только по ней. Пусто у записей, сложенных до 4.7:
     * тогда отмечать нечего, и кнопка не показывается до пересборки списка.
     */
    val lemma: String = "",
    /** Нарисованная картинка (4.41, [WordPicture]); есть — вместо [emoji]. */
    val picture: String = "",
    /**
     * Совсем новое слово — у леммы нет ни одной карточки (4.43). Только такие
     * перевёртыш повторяет через несколько слов, если их не вспомнили: см.
     * [WidgetWords.next]. Решение Кати — начатые не повторяются.
     */
    val fresh: Boolean = false
)

/**
 * Список слов для виджета на домашнем экране — готовый, маленький, в настройках.
 *
 * ## Зачем отдельное хранилище, а не тот же словарь
 *
 * Виджет обновляется сам, раз в полчаса, и часто в свежем процессе, где нет
 * ничего. Разбирать в нём `words.json` нельзя: это 511 КБ и полторы-восемь
 * секунд по отчётам диагностики — ровно то, от чего избавлялись в 1.97, когда
 * выяснилось, что почти каждый запуск холодный. Виджет платил бы эту цену
 * каждые тридцать минут и вообще ни за что.
 *
 * Поэтому список готовит приложение — там словарь и так прочитан и разобран
 * (`AppViewModel.saveWidgetWords`), — а виджету достаётся строка из настроек.
 * Сто слов по паре десятков знаков это около трёх килобайт: читается мгновенно.
 *
 * **Готовое до последней мелочи.** В записи лежит и написание, и номер ударной
 * буквы, и эмодзи, так что виджет не открывает ни одного файла — даже
 * пятикилобайтных ударений. Это не мелочная экономия: чтение с диска потребовало
 * бы `goAsync` и корутины в приёмнике, который иначе укладывается в десять строк
 * синхронного кода. Побочно виджет не подмешивает свои замеры в диагностику:
 * сорок восемь чтений ударений в день утопили бы в сводке настоящий запуск.
 *
 * ## Почему `SharedPreferences`, а не свой файл
 *
 * Свой файл потребовал бы формата, разбора и обработки порчи. Здесь ничего
 * этого нет: строка либо есть, либо нет, и пустой виджет — законное состояние
 * (слов в обороте пока нет, приложение ещё не запускали ни разу). Файл настроек
 * тот же, что у [Pace], новостей и ответов на жалобы, и в Auto Backup он не
 * попадает — `data_extraction_rules.xml` включает только базу.
 *
 * ## Курсор, а не случайный выбор
 *
 * Слова лежат от самого шаткого к более твёрдому, и виджет идёт по списку по
 * порядку, заворачивая в начало. Случайный выбор из ста повторялся бы и
 * пропускал: за сто показов половина слов не показалась бы ни разу. Курсор
 * обходит список целиком и не повторяет подряд никогда.
 */
object WidgetWords {

    private const val PREFS = "crnogorski"
    private const val KEY_WORDS = "widget_words"
    private const val KEY_AT = "widget_at"

    /**
     * Курсор у каждого виджета свой: список один, но карточка-перевёртыш
     * (4.7) и простая карточка — разные занятия, и листать одна другую не
     * должна. Иначе нажатие на одной перелистывало бы и вторую.
     */
    const val PLAIN = KEY_AT
    const val FLIP = "widget_flip_at"

    /** Сколько слов держим. Больше сотни виджет не обойдёт и за неделю. */
    const val LIMIT = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Переписать список. Курсор не сбрасывается: он и так берётся по остатку от
     * размера, а сброс возвращал бы виджет к первому слову после каждого
     * занятия.
     */
    fun save(context: Context, words: List<WidgetWord>) {
        val line = words.take(LIMIT).joinToString("\n") {
            listOf(
                it.word, it.gloss, it.stress.toString(), it.emoji, it.lemma, it.picture,
                if (it.fresh) "1" else ""
            ).joinToString("\t")
        }
        prefs(context).edit().putString(KEY_WORDS, line).apply()
    }

    fun all(context: Context): List<WidgetWord> =
        prefs(context).getString(KEY_WORDS, "").orEmpty()
            .lineSequence()
            .mapNotNull { row ->
                val f = row.split('\t')
                if (f.size < 4 || f[0].isEmpty()) null
                else WidgetWord(
                    f[0], f[1], f[2].toIntOrNull() ?: -1, f[3],
                    f.getOrElse(4) { "" }, f.getOrElse(5) { "" }, f.getOrElse(6) { "" } == "1"
                )
            }
            .toList()

    /** Слово, которое виджет показывает сейчас, — без сдвига. */
    fun current(context: Context, cursor: String = PLAIN): WidgetWord? {
        val words = all(context)
        if (words.isEmpty()) return null
        if (cursor == FLIP) showing(context)?.let { (lemma, _) ->
            words.firstOrNull { it.lemma == lemma }?.let { return it }
        }
        return words[prefs(context).getInt(cursor, 0).mod(words.size)]
    }

    /**
     * Следующее слово по списку: сдвинуть курсор и отдать.
     *
     * У перевёртыша так ходит только тик системы. Повтор, если он был на
     * экране, при этом сходит — тик не нажатие, — а очередь повторов стоит:
     * шаги в ней считаются словами, которые человек перевернул сам.
     */
    fun advance(context: Context, cursor: String = PLAIN): WidgetWord? {
        val words = all(context)
        if (words.isEmpty()) return null
        val at = (prefs(context).getInt(cursor, 0) + 1).mod(words.size)
        prefs(context).edit().putInt(cursor, at).apply {
            if (cursor == FLIP) remove(KEY_SHOWING)
        }.apply()
        return words[at]
    }

    /**
     * Следующее слово перевёртыша по нажатию — с повтором нового (4.43).
     *
     * Катя: «если человеку в виджете показали новое слово, и он не нажал ни
     * на мозг, ни на галочку, значит, это слово для него новое. Такое слово
     * надо ещё раз показать через пять слов, и потом ещё через пять». Так
     * устроено заучивание и в Anki: новое возвращают в том же заходе
     * короткими шагами, а не через полный круг из ста слов, когда оно уже
     * забыто.
     *
     * [missed] — уходящее слово перевернули и не отметили: нажатие «дальше»
     * без ✅. Перейти, не увидев ответа, перевёртыш не даёт — первое нажатие
     * переворачивает, второе листает, — поэтому «не вспомнил» здесь
     * однозначно. ✅ зовёт сюда же с `false`, 🧠 убирает слово через [remove].
     *
     * Повторяются **только совсем новые** ([WidgetWord.fresh]) — решение
     * Кати. Вспомнили на повторе — следующего не будет; не вспомнили — идёт
     * по плану. Очередь лежит отдельно от списка: приложение переписывает
     * список после каждого занятия, а очередь при этом теряться не должна.
     * Слово, которого в новом списке нет, из очереди молча выпадает.
     */
    fun next(context: Context, missed: Boolean, gap: Int, replays: Int): WidgetWord? {
        val words = all(context)
        if (words.isEmpty()) return null
        val byLemma = words.associateBy { it.lemma }
        val queue = replayQueue(context).toMutableList()
        val shown = current(context, FLIP)
        val showing = showing(context)
        if (missed && gap > 0 && shown != null && shown.fresh && shown.lemma.isNotEmpty()) {
            // Сколько повторов осталось после этого показа: на первом проходе
            // по списку — все, на повторе — то, что записано при нём.
            val left = if (showing?.first == shown.lemma) showing.second else replays
            if (left > 0 && queue.none { it.lemma == shown.lemma }) {
                // +1: шаг ниже вычитается сразу, а «через пять» значит пять
                // других слов между показами.
                queue += Replay(shown.lemma, gap + 1, left - 1)
            }
        }
        val stepped = queue.map { it.copy(wait = it.wait - 1) }
            .filter { it.lemma in byLemma }
        val due = stepped.firstOrNull { it.wait <= 0 }
        val edit = prefs(context).edit()
        val word = if (due != null) {
            edit.putString(KEY_SHOWING, due.lemma + "\t" + due.left)
            byLemma.getValue(due.lemma)
        } else {
            val at = (prefs(context).getInt(FLIP, 0) + 1).mod(words.size)
            edit.putInt(FLIP, at).remove(KEY_SHOWING)
            words[at]
        }
        edit.putString(KEY_REPLAY, stepped.filter { it !== due }.joinToString("\n") {
            it.lemma + "\t" + it.wait + "\t" + it.left
        }).apply()
        return word
    }

    /** Слово в очереди повтора: через сколько нажатий и сколько повторов после. */
    private data class Replay(val lemma: String, val wait: Int, val left: Int)

    private fun replayQueue(context: Context): List<Replay> =
        prefs(context).getString(KEY_REPLAY, "").orEmpty().lineSequence().mapNotNull {
            val f = it.split('\t')
            if (f.size < 3 || f[0].isEmpty()) null
            else Replay(f[0], f[1].toIntOrNull() ?: return@mapNotNull null, f[2].toIntOrNull() ?: 0)
        }.toList()

    /** Повтор на экране сейчас: лемма и сколько повторов останется после него. */
    private fun showing(context: Context): Pair<String, Int>? {
        val f = prefs(context).getString(KEY_SHOWING, "").orEmpty().split('\t')
        if (f.size < 2 || f[0].isEmpty()) return null
        return f[0] to (f[1].toIntOrNull() ?: 0)
    }

    private const val KEY_REPLAY = "widget_flip_replay"
    private const val KEY_SHOWING = "widget_flip_showing"

    /**
     * Убрать слово из списка — после отметки «уже знаю».
     *
     * Список пересобирается приложением при каждом открытии, но до того виджет
     * показывал бы только что отмеченное слово снова. Курсоры не трогаются:
     * они берутся по остатку от размера, и на месте ушедшего окажется
     * следующее слово — ровно то, что и нужно после отметки.
     */
    fun remove(context: Context, lemma: String) {
        save(context, all(context).filter { it.lemma != lemma })
        // «Уже знаю» снимает и повтор: знакомое повторять незачем.
        val edit = prefs(context).edit()
        if (showing(context)?.first == lemma) edit.remove(KEY_SHOWING)
        edit.putString(KEY_REPLAY, replayQueue(context).filter { it.lemma != lemma }
            .joinToString("\n") { it.lemma + "\t" + it.wait + "\t" + it.left })
        edit.apply()
    }

    /**
     * Толкование, сокращённое до пригодного к взгляду.
     *
     * Статьи словаря писаны не для виджета: «второй; другой (не этот)»,
     * «говорить, разговаривать, беседовать; рассказывать, повествовать». В
     * скобках стоит уточнение, нужное при разборе ответа и лишнее при
     * мимолётном взгляде, а пятый оттенок смысла на карточке в два сантиметра
     * не прочитает никто. Остаются два первых варианта — именно они и отвечают
     * на «что это значит»: по словарю это медиана в девять знаков и восемнадцать
     * на девяносто процентов слов, то есть влезает целиком.
     *
     * Разделителем считается и запятая, и точка с запятой — как в
     * `LocalCheck.glossVariants`. Граница смыслов при этом теряется («слышать;
     * чуять» станет «слышать, чуять»), и это сознательный размен: взгляду
     * мимоходом важнее, чтобы строка кончалась словом, а не обрывом.
     *
     * Пробельные знаки сводятся к одному: список хранится строками с
     * табуляцией, и перевод строки внутри толкования разорвал бы запись.
     */
    fun short(gloss: String): String =
        gloss.replace(Regex("\\([^)]*\\)"), " ")
            .split(';', ',')
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString(", ")
}
