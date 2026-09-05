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
            intervalDays = interval,
            ease = (card.ease + 0.05).coerceAtMost(3.0),
            dueAt = now + interval * DAY_MS
        )
    }
}
