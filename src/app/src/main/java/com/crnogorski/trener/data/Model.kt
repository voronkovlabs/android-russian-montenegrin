package com.crnogorski.trener.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LessonIndex(
    val lessons: List<LessonRef>
)

@Serializable
data class LessonRef(
    val id: String,
    val title: String,
    val file: String,
    /**
     * Раздел, в который урок попадает на главном экране.
     *
     * Плоский список перестаёт листаться уже на двадцати уроках, а их будет
     * больше. Пустая строка означает «без раздела» — такие уроки идут первыми
     * и без заголовка, чтобы старый `index.json` не ломался.
     */
    val section: String = "",

    /**
     * Урок не из программы курса, а тематический: язык из жизни вокруг.
     *
     * Курс — лестница A1–A2, и разделы у неё грамматические: «Прошлое»,
     * «Если бы», «Сложные конструкции». Урок про рынок в эту
     * последовательность не встаёт — он ни на чём не стоит и ни к чему не
     * ведёт, поэтому живёт своей группой в конце списка.
     *
     * Флаг нужен **отчёту**: «доля пройденного курса» обязана мерить лестницу,
     * а не всё, что лежит в оглавлении. Тематических уроков может стать
     * сколько угодно, и без этого знаменатель рос бы вечно, а полоса не
     * заполнилась бы никогда.
     *
     * На всё остальное он не влияет: ежедневное задание берёт первый
     * незакрытый урок по порядку оглавления, а тематические стоят в конце —
     * значит до них дойдёт, когда курс кончится. Руками их открывают с вкладки
     * «Уроки» в любой день, и время с них идёт в дневную норму, как с любого
     * занятия.
     */
    val extra: Boolean = false
)

/**
 * Что человек делает с историей. Текст один и тот же, занятие разное.
 *
 * [Read] — чтение вслух: показан черногорский, надо его произнести.
 * [Listen] — на слух: текст закрыт прочерками, отрезок сначала звучит, и
 * повторить его надо с голоса. Не вышло за несколько попыток — текст
 * открывается, и дальше это [Read].
 * [Translate] — перевод вслух: показан русский, надо сказать то же
 * по-черногорски. Проверяется моделью, а не сравнением строк: одну мысль
 * выражают по-разному, и это не ошибка.
 *
 * Порядок объявления — порядок групп на главном экране, от простого к трудному.
 *
 * [key] уходит в базу и в копию прогресса — менять его нельзя, иначе
 * пройденное перестанет находиться.
 */
enum class StoryMode(val key: String, val title: String) {
    Read("read", "Чтение вслух"),
    Listen("listen", "На слух"),
    Translate("translate", "Перевод вслух");

    companion object {
        fun of(key: String): StoryMode = entries.firstOrNull { it.key == key } ?: Read
    }
}

@Serializable
data class StoryIndex(val stories: List<StoryRef> = emptyList())

@Serializable
data class StoryRef(
    val id: String,
    val title: String,
    val file: String,
    /** Уровень сложности, 1-5: истории идут по возрастанию. */
    val level: Int = 1,
    /**
     * Диалог, а не сплошной текст.
     *
     * Нужно оглавлению: чтобы отличить одно от другого в списке, не читая
     * файлы. Внутри самого файла признак другой — размеченные роли у
     * отрезков, — и на экране считается по нему: два поля разойтись не
     * смогут, потому что второе никто не пишет руками.
     */
    val dialog: Boolean = false,
    /** Сколько отрезков — чтобы главный экран показал прогресс, не читая файл. */
    val chunks: Int = 0
)

/**
 * Отрезок истории: столько, сколько читается вслух за один заход распознавания.
 *
 * Нарезано заранее, в файле, а не алгоритмом на устройстве: перевод даётся на
 * каждый отрезок отдельно, а делить фразу и переводить её половинки — разные
 * задачи, и вторую машина не решает.
 */
@Serializable
data class StoryChunk(
    val sr: String,
    val ru: String,
    /**
     * Чья это реплика в диалоге: [CHUNK_THEM] — собеседника, [CHUNK_YOU] — ваша.
     *
     * У обычной истории поле пустое, и это не «неизвестно», а «роли нет»:
     * рассказ читают целиком, делить его между голосами не на что. Диалог от
     * истории отличается ровно этим полем — больше ничем, поэтому все три
     * занятия работают на нём без единой новой строки в проверке.
     */
    val who: String = ""
) {
    val theirs: Boolean get() = who == CHUNK_THEM
    val mine: Boolean get() = who == CHUNK_YOU
}

/** Реплика собеседника: её произносит приложение, а не человек. */
const val CHUNK_THEM = "them"

/** Ваша реплика: её надо сказать самому. */
const val CHUNK_YOU = "you"

/**
 * История для чтения вслух: 10-20 отрезков, каждый перечитывается до тех пор,
 * пока не получится. Вне SRS: прочитал — и всё, вернуться можно руками.
 */
@Serializable
data class Story(
    val id: String,
    val title: String,
    val note: String = "",
    /**
     * Как зовут собеседника в диалоге: «Продавщица», «Врач», «Сосед».
     *
     * Подпись к его репликам. Иконка и отступ говорят, что говорят двое,
     * а имя роли — кто именно, и без него диалог у врача не отличить от
     * диалога на почте, когда смотришь на середину списка.
     */
    val speaker: String = "",
    val chunks: List<StoryChunk>
)

/**
 * Словарь для подсказок по нажатию на слово: `assets/glossary.json`.
 *
 * Ключ — словоформа в нижнем регистре ровно в том виде, в каком она стоит
 * в задании, а не начальная форма: разбирать морфологию на устройстве нечем,
 * да и «plažu — пляж (вин. п.)» полезнее, чем отсылка к словарной статье.
 * Слова, которых в словаре нет, просто не подчёркиваются.
 */
@Serializable
data class Glossary(
    val me: Map<String, String> = emptyMap(),
    val ru: Map<String, String> = emptyMap()
)

/**
 * Пара на экране сопоставления: короткое толкование и слово.
 *
 * [cardId] приходит вместе с парой, а не выводится из [me], и это не
 * избыточность. У слова карточек две — «назови по-черногорски» (`-mean`) и
 * «назови по-русски» (`-back`), — и засчитать надо ровно ту, из-за которой
 * слово вообще оказалось на экране. Направление, таким образом, наследуется от
 * источника, и один и тот же экран годится обеим очередям.
 */
@Serializable
data class MatchPair(val cardId: String, val ru: String, val me: String)

/**
 * Ячейка парадигмы: от какого слова, что за форма и какая она.
 *
 * [lemma] отдельно у каждой ячейки, потому что в серии по образцу их
 * несколько: система живёт не в слове, а в типе, и видно её только когда
 * `kuća`, `godina` и `plaža` стоят рядом.
 */
@Serializable
data class ParadigmCell(
    val lemma: String,
    /** Подпись ячейки: «вин. ед.», «ты». */
    val label: String,
    /** Рамка-пример из словаря: «Vidim ___.» — падеж задан предлогом. */
    val frame: String,
    val form: String
)

@Serializable
data class Lesson(
    val id: String,
    val title: String,
    val note: String = "",
    val exercises: List<Exercise>
)

/**
 * Типы заданий. Дискриминатор в JSON — поле "type".
 *
 * Проверка бывает двух видов:
 *  - строгая локальная (нормализация строки), когда ответ однозначен;
 *  - через Claude Haiku, когда допустимы синонимы и разный порядок слов.
 */
@Serializable
sealed class Exercise {
    abstract val id: String

    /** Русский → черногорский, свободный ввод. Проверяет Haiku. */
    @Serializable
    @SerialName("ru_to_me")
    data class TranslateToTarget(
        override val id: String,
        val prompt: String,
        val reference: String,
        val hint: String = ""
    ) : Exercise()

    /** Черногорский → русский, свободный ввод. Проверяет Haiku. */
    @Serializable
    @SerialName("me_to_ru")
    data class TranslateToNative(
        override val id: String,
        val prompt: String,
        val reference: String,
        val hint: String = ""
    ) : Exercise()

    /** Выбор одного варианта. */
    @Serializable
    @SerialName("choice")
    data class Choice(
        override val id: String,
        val prompt: String,
        val options: List<String>,
        val answer: String,
        val explanation: String = ""
    ) : Exercise()

    /** Сборка фразы из блоков слов. */
    @Serializable
    @SerialName("word_bank")
    data class WordBank(
        override val id: String,
        val prompt: String,
        val answer: String,
        val bank: List<String>,
        val explanation: String = ""
    ) : Exercise()

    /** Подстановка формы слова: падеж, спряжение. Одно-два слова, строгая проверка. */
    @Serializable
    @SerialName("form")
    data class Form(
        override val id: String,
        val prompt: String,
        val answer: String,
        val explanation: String = ""
    ) : Exercise()

    /**
     * Словарная карточка: набрать черногорское слово или его форму.
     *
     * От [Form] отличается тем, что подпись приходит вместе с заданием, а не
     * прибита в экране: одна и та же механика спрашивает то значение слова, то
     * падеж, то особую форму, и сказать, что именно, должна карточка.
     *
     * В файлах уроков этого типа нет и быть не может — задания собираются на
     * лету из `assets/vocab/words.json` (см. `exerciseFor`).
     */
    @Serializable
    @SerialName("word")
    data class Word(
        override val id: String,
        val label: String,
        val prompt: String,
        val answer: String,
        val explanation: String = "",
        /**
         * Другие слова, которые тоже верны.
         *
         * Толкование в словаре одно на слово, но русское слово часто покрывает
         * несколько черногорских: «дочь» — и `ćerka`, и `kći`, «интересный» —
         * и `zanimljiv`, и `interesantan`. Спрашивают знание слова, а не
         * угадывание, какое из двух записано эталоном, поэтому верны все.
         */
        val also: List<String> = emptyList(),
        /**
         * Ответ по-русски, а не по-черногорски.
         *
         * Меняет три вещи: язык распознавания и клавиатуры и то, как сверяется
         * ответ — у русского толкования вариантов обычно несколько
         * («говорить, разговаривать, беседовать»), и точное совпадение строки
         * тут не годится.
         */
        val native: Boolean = false,
        /**
         * Картинка к слову — эмодзи или пусто (`WordEmoji`).
         *
         * Пусто у двух третей слов, и это норма: «ответственность» картинкой
         * не бывает. Пусто **всегда** и у обратного перевода, даже когда
         * картинка у слова есть, — там спрашивают значение, и она была бы
         * ответом.
         */
        val icon: String = ""
    ) : Exercise()

    /**
     * Пары слов: слева значения, справа черногорские слова, надо сложить.
     *
     * Единственное задание, которое отвечает не за одну карточку, а за пять
     * сразу: экран — это пять слов, и вердикт по каждому свой. Отсюда и
     * отдельный путь записи (`AppViewModel.submitMatch`) — общий `record`
     * умеет двигать ровно одну карточку.
     *
     * Своей карточки у экрана нет и быть не может: набор слов на нём каждый
     * раз другой, и заводить SRS на случайную пятёрку бессмысленно. [id] нужен
     * жалобам и замеру времени, в базу он не попадает никогда.
     *
     * В файлах уроков этого типа нет — экраны собираются на лету из
     * `assets/vocab/words.json`, как и [Word].
     */
    @Serializable
    @SerialName("match")
    data class Match(
        override val id: String,
        val pairs: List<MatchPair>
    ) : Exercise()

    /** Аудирование: TTS произносит [audioText], надо записать услышанное. */
    /**
     * Парадигма целиком, одним экраном.
     *
     * Заведено 21.09.2026 по словам владельца: «когда какое-то слово надо
     * поставить в правильную форму один раз, а потом оно возвращается через
     * несколько уроков, то заметить систему сложно».
     *
     * Он прав, и это та же ошибка, которую проект уже однажды чинил экраном
     * пар: до 1.38 первой встречей со словом было требование его напечатать,
     * то есть спросить то, чего человек ни разу не видел. С формами было ровно
     * так же — а **правило нельзя вывести из ячеек, которые не показывали
     * рядом**.
     *
     * [ask] разводит две половины одного механизма: `false` — таблица показана
     * заполненной и ничего не спрашивает (первая встреча), `true` — те же
     * ячейки спрашиваются. Сперва увидеть систему, потом отвечать.
     *
     * В файлах уроков этого типа нет: задание собирается на лету из словаря,
     * как `word` и `match`. Карточка у него **та же самая `decl`**, что раньше
     * спрашивала по ячейке за раз, — значит долг повторений не вырос ни на
     * одну карточку, а вопрос стал другим.
     */
    @Serializable
    @SerialName("paradigm")
    data class Table(
        override val id: String,
        val title: String,
        val note: String = "",
        val cells: List<ParadigmCell>,
        val ask: Boolean = true
    ) : Exercise()

    @Serializable
    @SerialName("listening")
    data class Listening(
        override val id: String,
        val audioText: String,
        val translation: String
    ) : Exercise()

    /** Произношение: надо произнести [phrase], сверяем с распознанным текстом. */
    @Serializable
    @SerialName("speaking")
    data class Speaking(
        override val id: String,
        val phrase: String,
        val translation: String
    ) : Exercise()

    /**
     * Повторение на слух: фраза звучит, текст закрыт.
     *
     * От [Speaking] отличается только тем, что видно на экране: там текст перед
     * глазами и задача — прочитать его вслух, здесь опереться не на что, кроме
     * услышанного. Проверяется одинаково, `matchesSpoken`.
     */
    @Serializable
    @SerialName("repeat")
    data class Repeat(
        override val id: String,
        val phrase: String,
        val translation: String
    ) : Exercise()

    /**
     * Чтение вслух: связный текст в несколько предложений.
     *
     * От [Speaking] отличается не длиной, а способом проверки: на трёх
     * предложениях требовать дословного совпадения бессмысленно, движок
     * теряет и склеивает слова. Считается доля слов эталона, которые
     * прозвучали по порядку — см. `LocalCheck.readingScore`.
     */
    @Serializable
    @SerialName("reading")
    data class Reading(
        override val id: String,
        val text: String,
        val translation: String
    ) : Exercise()
}

/**
 * Строковый тип задания — тот же дискриминатор, что в JSON урока.
 * Нужен в жалобах: по нему видно, какого рода задание оказалось кривым.
 */
val Exercise.typeName: String
    get() = when (this) {
        is Exercise.TranslateToTarget -> "ru_to_me"
        is Exercise.TranslateToNative -> "me_to_ru"
        is Exercise.Choice -> "choice"
        is Exercise.WordBank -> "word_bank"
        is Exercise.Form -> "form"
        is Exercise.Word -> "word"
        is Exercise.Match -> "match"
        // Показ и спрос считаются разными типами намеренно: у Pace это
        // прикидка времени, а посмотреть таблицу и заполнить её — разное
        // время в разы.
        is Exercise.Table -> if (ask) "paradigm" else "paradigm_show"
        is Exercise.Listening -> "listening"
        is Exercise.Speaking -> "speaking"
        is Exercise.Repeat -> "repeat"
        is Exercise.Reading -> "reading"
    }

/** Нужен ли интернет и вызов Haiku для проверки этого задания. */
val Exercise.needsModelCheck: Boolean
    get() = this is Exercise.TranslateToTarget || this is Exercise.TranslateToNative

/** Текст, который показывается как «правильный ответ» после проверки. */
val Exercise.referenceAnswer: String
    get() = when (this) {
        is Exercise.TranslateToTarget -> reference
        is Exercise.TranslateToNative -> reference
        is Exercise.Choice -> answer
        is Exercise.WordBank -> answer
        is Exercise.Form -> answer
        is Exercise.Word -> answer
        // Показывать это как «правильный ответ» некому: экран пар сам себя
        // раскрывает по ходу. Строка нужна жалобе — по ней видно, какая
        // пятёрка слов оказалась вместе.
        is Exercise.Match -> pairs.joinToString("; ") { "${it.me} — ${it.ru}" }
        is Exercise.Table -> cells.joinToString("; ") { "${it.label}: ${it.form}" }
        is Exercise.Listening -> audioText
        is Exercise.Speaking -> phrase
        is Exercise.Repeat -> phrase
        is Exercise.Reading -> text
    }
