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
    val file: String
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
        val bank: List<String>
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
    }
