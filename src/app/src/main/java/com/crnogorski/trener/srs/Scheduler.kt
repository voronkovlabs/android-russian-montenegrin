package com.crnogorski.trener.srs

import com.crnogorski.trener.data.CardEntity
import kotlin.math.roundToInt

/**
 * Упрощённый SM-2. Две оценки: угадал или нет.
 * Ошибка сбрасывает интервал и снижает лёгкость, но карточка возвращается
 * уже в этой же сессии — планировщик ставит её на 10 минут вперёд.
 */
object Scheduler {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val LAPSE_DELAY_MS = 10L * 60 * 1000
    private const val SKIP_DELAY_MS = 4L * 60 * 60 * 1000

    fun newCard(exerciseId: String, lessonId: String, correct: Boolean, now: Long): CardEntity =
        update(
            CardEntity(
                exerciseId = exerciseId,
                lessonId = lessonId,
                dueAt = now,
                intervalDays = 0,
                ease = 2.5,
                repetitions = 0,
                lapses = 0
            ),
            correct,
            now
        )

    /**
     * Пропуск без штрафа: карточка просто отодвигается на несколько часов.
     *
     * `ease`, `repetitions` и `intervalDays` не трогаем — «пропустить» значит
     * «не могу сейчас» (шумно, нет микрофона), а не «не знаю». Наказывать за это
     * лапсом значит подталкивать к тому, чтобы вместо пропуска бормотать
     * что попало ради зачёта.
     */
    fun postpone(card: CardEntity, now: Long): CardEntity =
        card.copy(dueAt = now + SKIP_DELAY_MS)

    /** Пропуск задания, которого ещё не было в SRS: заводим карточку нетронутой. */
    fun skippedCard(exerciseId: String, lessonId: String, now: Long): CardEntity =
        CardEntity(
            exerciseId = exerciseId,
            lessonId = lessonId,
            dueAt = now + SKIP_DELAY_MS,
            intervalDays = 0,
            ease = 2.5,
            repetitions = 0,
            lapses = 0
        )

    /**
     * Тренировка вне расписания: счёт идёт, а интервал не двигается.
     *
     * Правило намеренно несимметричное. Верный ответ прибавляется к счётчику и
     * больше ничего не меняет: прогнав карточку десять раз подряд за минуту,
     * нельзя получить интервал в полгода — разнесённости в такой прогонке нет,
     * и выдавать её за прочность значит врать самому себе. Ошибка же считается
     * полноценной: она и вне расписания означает, что слово не знают.
     */
    fun practice(card: CardEntity, correct: Boolean, now: Long): CardEntity =
        if (correct) card.copy(correct = card.correct + 1)
        else update(card, false, now)

    fun update(card: CardEntity, correct: Boolean, now: Long): CardEntity {
        if (!correct) {
            return card.copy(
                repetitions = 0,
                intervalDays = 0,
                lapses = card.lapses + 1,
                ease = (card.ease - 0.2).coerceAtLeast(1.3),
                dueAt = now + LAPSE_DELAY_MS
            )
        }

        val reps = card.repetitions + 1
        val interval = when (reps) {
            1 -> 1
            2 -> 3
            else -> (card.intervalDays * card.ease).roundToInt().coerceAtLeast(4)
        }
        return card.copy(
            repetitions = reps,
            correct = card.correct + 1,
            intervalDays = interval,
            ease = (card.ease + 0.05).coerceAtMost(3.0),
            dueAt = now + interval * DAY_MS
        )
    }
}
