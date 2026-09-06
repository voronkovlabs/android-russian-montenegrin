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
    val section: String = ""
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
data class StoryChunk(val sr: String, val ru: String)

/**
 * История для чтения вслух: 10-20 отрезков, каждый перечитывается до тех пор,
 * пока не получится. Вне SRS: прочитал — и всё, вернуться можно руками.
 */
@Serializable
data class Story(
    val id: String,
    val title: String,
    val note: String = "",
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

    /** Аудирование: TTS произносит [audioText], надо записать услышанное. */
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
        is Exercise.Listening -> audioText
        is Exercise.Speaking -> phrase
        is Exercise.Repeat -> phrase
        is Exercise.Reading -> text
    }
