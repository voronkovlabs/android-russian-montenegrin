package com.crnogorski.trener.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Форма слова: написание и ячейка парадигмы (`a-s` — винительный единственного,
 * `Vmr1s` — настоящее время, первое лицо единственного).
 */
@Serializable
data class VocabForm(val f: String, val s: String)

/** Живое предложение из пула, где слово стоит в форме [f]. */
@Serializable
data class VocabExample(
    val sr: String,
    val ru: String,
    val f: String,
    val id: String = "",
    val level: Int = 0
)

/**
 * Слово словаря: перевод, живые формы, примеры.
 *
 * [n] — место по частоте: слова вводятся сверху вниз. [odd] — формы с
 * изменённой основой (`apoteka` → `apoteci`), единственные, что учат
 * поштучно. [doubt] — подозрение вычитки, слово при этом остаётся в списке.
 */
@Serializable
data class VocabWord(
    val id: String,
    val n: Int = 0,
    val pos: String = "",
    val gloss: String = "",
    /**
     * Парадигма — **не из этого файла**, а из полосы `forms-N.json`, и лежит
     * здесь только после подстановки (`VocabRepository.hydrate`).
     */
    val forms: List<VocabForm> = emptyList(),
    /**
     * Сколько у слова форм. Число, оставшееся в `words.json` вместо самих форм.
     *
     * Ради него всё и затевалось: отбору нужно знать не парадигму, а лишь то,
     * можно ли завести карточку склонения, — и ради этого знания приходилось
     * держать в памяти 889 КБ форм.
     */
    val nf: Int = 0,
    val odd: List<String> = emptyList(),
    val ex: List<VocabExample> = emptyList(),
    val doubt: String = ""
) {
    /**
     * Есть ли у слова парадигма — без чтения парадигм.
     *
     * Смотрит и на `forms`: если однажды соберут словарь толстым файлом, как
     * было до 1.97, приложение не должно молча перестать заводить карточки
     * склонения. Тихая потеря функции хуже лишней проверки.
     */
    val hasForms: Boolean get() = nf > 0 || forms.isNotEmpty()
}

/** Полоса парадигм: файл `forms-N.json`. */
@Serializable
data class VocabForms(val forms: Map<String, List<VocabForm>> = emptyMap())

/**
 * Словарь целиком.
 *
 * [frames] — рамки «ячейка → образец фразы», [slots] — их же человеческие
 * подписи. И то и другое лежит в файле, а не в коде: это данные языка, и
 * пересобираются они тем же скриптом, что и сами слова.
 */
@Serializable
data class VocabFile(
    val version: Int = 0,
    val frames: Map<String, String> = emptyMap(),
    val slots: Map<String, String> = emptyMap(),
    val words: List<VocabWord> = emptyList()
)

/** Что именно спрашивает карточка. Ключ уходит в `exerciseId` и не меняется. */
enum class VocabKind(val key: String) {
    /** Значение: «аптека» → `apoteka`. Одна на слово. */
    Meaning("mean"),

    /**
     * Образец склонения: форма меняется от повторения к повторению.
     *
     * Это не «карточка на каждый падеж»: половина падежей — одно и то же
     * написание, а остальное русский выводит сам, падежная система у него та
     * же. Проверяется правило, а правило одно, поэтому и карточка одна.
     */
    Pattern("decl"),

    /** Неожиданная форма: основа поменялась, вывести её нельзя. */
    Odd("form"),

    /**
     * Обратный перевод: `apoteka` → «аптека».
     *
     * Отдельная карточка, а не оборот той же: узнавание и порождение — разное
     * знание, и держатся они по-разному. Узнавание проще, поэтому и заводится
     * само, как только слово вообще заведено.
     */
    Recall("back")
}

/**
 * Словарные карточки: три вида заданий вокруг одной леммы.
 *
 * Единица — слово, а не словоформа. Форм у сербского существительного
 * пятнадцать, разных написаний семь, а непредсказуемых — одно; карточка на
 * каждую ячейку была бы курсом, который никто не закончит.
 *
 * Данные готовит `research/tools/build_vocab.py` из srLex и размеченного пула
 * предложений. В приложение едут только сами слова и формы — 439 КБ, — а не
 * лексикон, из которого они добыты.
 */
class VocabRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var cached: VocabFile? = null

    /** Прочитанные полосы парадигм: номер полосы → леммы с формами. */
    private val bands = mutableMapOf<Int, Map<String, List<VocabForm>>>()

    /**
     * Отсутствие файла — не ошибка: раздел просто окажется пустым.
     *
     * Выброшенные леммы (`Excluded`) отсеиваются **здесь, а не у мест
     * использования**, и это принципиально: по `file.words` ходят с десяток
     * разных мест — отбор на сегодня, экран пар, подмена отложенного, «Срез»,
     * счётчики в отчёте. Фильтр в каждом из них означал бы десять мест, где
     * его забудут, и слово вылезло бы ровно там, куда не посмотрели. Ровно
     * такая дыра была у «отложить на потом» в 1.86.
     */
    suspend fun load(): VocabFile = withContext(Dispatchers.IO) {
        cached ?: Trace.span("assets: словарь") {
            runCatching {
                json.decodeFromString<VocabFile>(
                    context.assets.open(PATH).bufferedReader().use { it.readText() }
                ).let { file ->
                    file.copy(words = file.words.filter { Excluded.allows(it.id) })
                }
            }.getOrDefault(VocabFile())
        }.also { cached = it }
    }

    /**
     * Парадигмы форм для этих слов — читаются полосами и по требованию.
     *
     * ## Зачем
     *
     * Разбор словаря — самая дорогая работа при запуске: по десяти отчётам
     * диагностики 1,5–7,8 секунды, в среднем около четырёх. А с 1.92 известно,
     * что почти каждый запуск **холодный**: телефон выгружает приложение из
     * памяти по нескольку раз в неделю, и словарь разбирается заново.
     *
     * Формы занимали **73% файла** (889 КБ из 1212), а нужны они только там,
     * где строится карточка склонения или особой формы, — то есть для одного
     * слова, а не для всех 3501. Всему остальному хватает числа [VocabWord.nf].
     *
     * ## Почему полосами, а не одним файлом
     *
     * Слова вводятся по частоте, а в обороте держится две сотни незаученных:
     * до третьей тысячи ученик дойдёт не скоро. Полоса — 500 слов по рангу,
     * около 120 КБ; на деле читается одна-две вместо 889 КБ.
     *
     * Прочитанное остаётся в памяти: полос всего восемь, и повторное чтение
     * стоило бы ровно того, ради чего всё затевалось.
     */
    suspend fun formsOf(words: Collection<VocabWord>): Map<String, List<VocabForm>> =
        withContext(Dispatchers.IO) {
            val need = words.asSequence()
                .filter { it.nf > 0 && it.forms.isEmpty() }
                .map { band(it.n) }
                .distinct()
                .filter { it !in bands }
                .toList()
            need.forEach { band ->
                bands[band] = Trace.span("assets: формы, полоса $band") {
                    runCatching {
                        json.decodeFromString<VocabForms>(
                            context.assets.open("vocab/forms-$band.json")
                                .bufferedReader().use { it.readText() }
                        ).forms
                    }.getOrDefault(emptyMap())
                }
            }
            // Отдаём накопленное целиком: карта маленькая, а копировать её по
            // куску на каждый вызов дороже, чем отдать как есть.
            buildMap { bands.values.forEach { putAll(it) } }
        }

    /**
     * Подставить слову его парадигму.
     *
     * Нужно ровно перед постройкой карточки склонения или особой формы —
     * больше нигде: остальные виды карточек форм не спрашивают.
     */
    suspend fun withForms(word: VocabWord): VocabWord = hydrate(word, formsOf(listOf(word)))

    fun hydrate(word: VocabWord, forms: Map<String, List<VocabForm>>): VocabWord =
        if (word.forms.isNotEmpty()) word
        else forms[word.id]?.let { word.copy(forms = it) } ?: word

    /**
     * Сколько новых слов уже взято сегодня.
     *
     * Дневной потолок — единственное, что удерживает словарь от превращения в
     * долг: карточке нужно 8–10 встреч, и каждое взятое сегодня слово это
     * работа, назначенная себе будущему.
     *
     * Считается по дате, а не по скользящим суткам: «сегодня» человек понимает
     * как календарный день, а не как «за последние 24 часа».
     */
    fun introducedToday(): Int =
        if (prefs.getString(KEY_DAY, "") == today()) prefs.getInt(KEY_COUNT, 0) else 0

    /**
     * Отметить взятые слова.
     *
     * Отмечаем при сборке сессии, а не по факту ответа: бросить сессию на
     * полуслове можно, и тогда день потратится зря. Ошибка эта в одну сторону —
     * новых слов будет меньше обещанного, а не больше, — и она безопаснее
     * обратной, при которой потолок бы не работал вовсе.
     */
    fun noteIntroduced(count: Int) {
        if (count <= 0) return
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_COUNT, introducedToday() + count)
            .apply()
    }

    private fun today(): String = LocalDate.now().toString()

    companion object {
        private const val PATH = "vocab/words.json"

        /**
         * Сколько слов в одной полосе парадигм.
         *
         * Пятьсот — то же число, что у `research/tools/split_forms.py`, и
         * разойтись они не должны: приложение искало бы форму не в том файле.
         */
        private const val BAND = 500

        /** Полоса слова по его месту в частотном списке (`n` идёт с единицы). */
        fun band(n: Int): Int = (maxOf(n, 1) - 1) / BAND

        // Те же настройки, что у копии прогресса: файл один на приложение.
        private const val PREFS = "crnogorski"
        private const val KEY_DAY = "vocab_day"
        private const val KEY_COUNT = "vocab_today"

        /** Все словарные карточки лежат в SRS под этим «уроком». */
        const val LESSON_ID = "vocab"

        /**
         * Сколько верных ответов считаем достаточным, чтобы назвать слово выученным.
         *
         * Десять — не круглое число наугад. Saragi, Nation и Meister (1978)
         * нашли примерно десять встреч как порог, за которым слово
         * закрепляется; Webb (2007) намерил, что при десяти и более
         * разнесённых встречах припоминание через неделю держится выше 80%, а
         * при менее чем шести падает ниже 30%. Порог считается по общему счёту
         * верных ответов, а не по серии подряд: интервалы растут, и десять
         * подряд набегали бы годами.
         *
         * Выученное из очереди не пропадает — просто перестаёт быть срочным.
         */
        val LEARNED: Int get() = Config.current.vocab.learned

        /**
         * Идентификатор карточки: `w-apoteka-mean`, `w-apoteka-form-apoteci`.
         *
         * Он уходит в базу и в жалобы, поэтому не меняется никогда — как и
         * `exerciseId` у заданий уроков. Леммы латинские и без дефисов, так
         * что разобрать такой ключ обратно можно однозначно.
         */
        fun cardId(lemma: String, kind: VocabKind, form: String = ""): String =
            if (kind == VocabKind.Odd) "w-$lemma-${kind.key}-$form"
            else "w-$lemma-${kind.key}"

        /**
         * Сколько пар на экране сопоставления и сколько таких экранов за заход.
         *
         * Пять — столько, сколько читается одним взглядом: искать глазами
         * приходится по всему столбцу, и на восьми парах экран превращается в
         * головоломку на внимание, а не на слова. Меньше четырёх собирать
         * незачем: последняя пара складывается сама, и на трёх задание
         * наполовину состоит из подарка.
         *
         * Два экрана за заход — потолок, а не норма: пары идут вперемешку с
         * набором слов, и превращать занятие в сплошное узнавание нельзя.
         * Узнать слово легче, чем вспомнить, и лёгкое вытесняло бы трудное.
         */
        val MATCH_PAIRS: Int get() = Config.current.vocab.matchPairs
        val MATCH_MIN: Int get() = Config.current.vocab.matchMin
        val MATCH_SCREENS: Int get() = Config.current.vocab.matchScreens

        fun isVocab(exerciseId: String): Boolean = exerciseId.startsWith("w-")

        /** Лемма из идентификатора карточки, или null, если это не она. */
        fun lemmaOf(exerciseId: String): String? {
            if (!isVocab(exerciseId)) return null
            val rest = exerciseId.removePrefix("w-")
            return VocabKind.entries
                .firstNotNullOfOrNull { kind ->
                    val mark = "-${kind.key}"
                    val cut = rest.indexOf(mark)
                    if (cut > 0) rest.take(cut) else null
                }
        }
    }
}

/**
 * Задание по карточке.
 *
 * [repetitions] — сколько раз карточку уже проходили: по нему у образца
 * склонения выбирается ячейка. Не случайно, а по счётчику — иначе одно и то же
 * повторение показывало бы разное при каждой перерисовке экрана.
 */
fun VocabFile.exerciseFor(
    word: VocabWord,
    kind: VocabKind,
    form: String = "",
    repetitions: Int = 0
): Exercise? = when (kind) {
    // Картинки тут нет намеренно: спрашивают, что значит слово, и картинка
    // была бы ответом. Показать её после ответа было бы можно, но тело задания
    // рисуется один раз, до развилки по фазам (см. SessionScreen), и «покажи
    // только на результате» потребовало бы протащить фазу внутрь. Не стоит
    // того, пока никто не попросил.
    VocabKind.Recall -> Exercise.Word(
        id = VocabRepository.cardId(word.id, kind),
        label = "Что это значит?",
        // Показываем иекавское написание, а ключ карточки остаётся прежним:
        // словарь собран из сербского источника, а курс черногорский. См.
        // [Ijekavica] — там же, почему нельзя просто переименовать лемму.
        prompt = Ijekavica.show(word.id),
        answer = word.gloss,
        explanation = "",
        native = true
    )

    VocabKind.Meaning -> Exercise.Word(
        id = VocabRepository.cardId(word.id, kind),
        label = "Как это по-черногорски?",
        prompt = word.gloss,
        // Эталоном показывается черногорская форма. Экавскую при этом
        // засчитает свёртка (`LocalCheck.reflex` сводит `ovdje` и `ovde` к
        // одному виду), так что цена ошибки тут нулевая, а польза прямая:
        // до этого приложение выдавало сербскую форму за черногорскую.
        answer = Ijekavica.show(word.id),
        // Слова с тем же толкованием засчитываются наравне с эталоном: по
        // словарю таких пар 55, и «дочь» — это и ćerka, и kći. Ключ тот же,
        // что у экрана пар: первый вариант статьи после разбора помет.
        //
        // Плюс **определённая форма прилагательного**: по жалобам владельца
        // (74 и 91, оба раза `nov` против `novi`). В словаре лемма стоит в
        // неопределённой форме, а человек пишет определённую — и это не
        // ошибка перевода, а выбор одной из двух законных форм. Различает их
        // определённость («новый дом вообще» против «тот самый новый дом»),
        // и спрашивать её карточкой значения никто не собирался.
        also = synonyms(word) + definite(word),
        explanation = "",
        // Картинка рядом с русским условием ответа не выдаёт: она значит ровно
        // то же, что написанное слово, а спрашивают черногорское.
        icon = WordEmoji.of(word.id).orEmpty()
    )

    VocabKind.Pattern -> {
        val slot = word.forms.getOrNull(
            if (word.forms.isEmpty()) 0 else repetitions % word.forms.size
        )
        slot?.let {
            // Живое предложение лучше рамки, но оно есть меньше чем у трети
            // слов: пул — 5987 фраз. Рамка добирает остальных, и в ней падеж
            // задан предлогом, а не подписью.
            val sample = word.ex.firstOrNull { ex -> ex.f == it.f }
            val prompt = sample?.let { s -> blank(s.sr, s.f) }
                ?: frames[it.s]
                ?: return null
            Exercise.Word(
                id = VocabRepository.cardId(word.id, kind),
                label = slots[it.s]?.let { name -> "Поставь в нужную форму: $name" }
                    ?: "Поставь в нужную форму",
                prompt = "$prompt  (${Ijekavica.show(word.id)})",
                answer = it.f,
                explanation = sample?.ru.orEmpty(),
                // Слово тут и так написано в скобках — картинка ничего не
                // открывает, зато держит перед глазами, о чём идёт речь.
                icon = WordEmoji.of(word.id).orEmpty()
            )
        }
    }

    VocabKind.Odd -> {
        val slot = word.forms.firstOrNull { it.f == form }
        val pattern = slot?.let { frames[it.s] }
        Exercise.Word(
            id = VocabRepository.cardId(word.id, kind, form),
            label = slot?.s?.let { slots[it] }?.let { "Особая форма: $it" }
                ?: "Особая форма",
            prompt = "${pattern ?: "___"}  (${Ijekavica.show(word.id)})",
            answer = form,
            // Основа тут меняется, и сказать об этом стоит прямо: иначе
            // выглядит как опечатка в задании.
            explanation = "Основа меняется: ${Ijekavica.show(word.id)} → $form",
            icon = WordEmoji.of(word.id).orEmpty()
        )
    }
}

/**
 * Другие слова словаря с тем же толкованием.
 *
 * Перебор по всем словам на каждое задание — 1152 сравнения, доли миллисекунды;
 * заводить ради этого индекс в кэше не стоит того, чтобы усложнять `VocabFile`.
 *
 * Сравнивается **первый вариант статьи**, а не вся строка: у «postojati»
 * толкование «быть, существовать», у соседа может быть просто «быть», и по
 * целой строке они не совпали бы никогда.
 */
/**
 * Определённая форма прилагательного: `nov` → `novi`.
 *
 * Только прибавлением `-i` и только у прилагательных. Настоящая парадигма
 * богаче (`dobar` → `dobri` с выпадением беглого «а»), но там форма и так
 * лежит в `forms`, а здесь нужен дешёвый случай, на который жалуются: лемма
 * плюс одна буква.
 */
private fun definite(word: VocabWord): List<String> =
    if (word.pos == "ADJ" && !word.id.endsWith("i")) listOf(word.id + "i")
    else emptyList()


private fun VocabFile.synonyms(word: VocabWord): List<String> {
    val key = LocalCheck.glossVariants(word.gloss).firstOrNull() ?: return emptyList()
    return synonymIndex()[key].orEmpty().filter { it != word.id }
}

/**
 * Указатель «первое слово толкования → все слова с ним».
 *
 * Считается один раз на словарь, и это не преждевременная оптимизация, а
 * починка по диагностике с телефона (отчёты 63 и 70, 13.09.2026). До неё
 * [synonyms] перебирал **все 1152 слова на каждую построенную карточку**, а на
 * каждом слове звал [LocalCheck.glossVariants] — четыре регулярки, разбиение и
 * нормализация. Пока словарь был тощим, это терялось в шуме; когда 1.75
 * пустила новые слова вперёд долга и карточек стало вдесятеро больше, сборка
 * ежедневного задания выросла с четырёх секунд до **шестнадцати-девятнадцати**.
 * Квадрат от числа карточек — он и был.
 *
 * Урок на будущее стоит записать: починка, снявшая ограничение с роста, вскрыла
 * место, которое этого роста не выдерживало. Второе следует за первым, и
 * искать такое надо сразу после того, как что-то в приложении начало расти.
 *
 * Хранится по **ссылке на файл**, а не по равенству: у `VocabFile` равенство
 * сравнивает все 1152 слова, и проверка кэша стоила бы дороже самого кэша.
 * Файл живёт один на запуск (`VocabRepository.load` его кэширует), поэтому
 * индекс строится ровно однажды.
 */
private val indexLock = Any()
private var indexedFile: VocabFile? = null
private var indexedGlosses: Map<String, List<String>> = emptyMap()

private fun VocabFile.synonymIndex(): Map<String, List<String>> = synchronized(indexLock) {
    if (indexedFile !== this) {
        indexedGlosses = words
            .groupBy { LocalCheck.glossVariants(it.gloss).firstOrNull().orEmpty() }
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, same) -> same.map { it.id } }
        indexedFile = this
    }
    indexedGlosses
}

/**
 * Пара для экрана сопоставления, или `null`, если слово на него не годится.
 *
 * Толкование берётся **первым вариантом** словарной статьи
 * ([LocalCheck.glossVariants]): на плашку нужно одно слово, а не «говорить,
 * разговаривать, беседовать; рассказывать, повествовать». Тот же разбор
 * работает и в обратном переводе, так что показанное на плашке — всегда то,
 * что там засчитывается.
 */
fun matchPairFor(cardId: String, word: VocabWord): MatchPair? =
    LocalCheck.glossVariants(word.gloss)
        .firstOrNull { it.isNotBlank() }
        ?.let { MatchPair(cardId = cardId, ru = it, me = Ijekavica.show(word.id)) }

/**
 * Экран пар из готовых пар.
 *
 * Идентификатор собран из лемм, а не из счётчика: он уходит в жалобу, и по
 * нему должно быть видно, какая именно пятёрка слов оказалась вместе, — жалоба
 * на такой экран почти наверняка означает «два значения не различить».
 */
fun matchExercise(pairs: List<MatchPair>): Exercise.Match =
    Exercise.Match(id = pairs.joinToString("+", prefix = "m-") { it.me }, pairs = pairs)

/** Прячет слово в предложении под прочерк — из примера получается задание. */
private fun blank(sentence: String, form: String): String =
    Regex("(?<![\\p{L}])${Regex.escape(form)}(?![\\p{L}])", RegexOption.IGNORE_CASE)
        .replace(sentence, "___")
