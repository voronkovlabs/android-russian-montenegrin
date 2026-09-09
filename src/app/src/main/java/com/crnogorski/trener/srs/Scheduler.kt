package com.crnogorski.trener.srs

import com.crnogorski.trener.data.CardEntity
import com.crnogorski.trener.data.Config
import kotlin.math.roundToInt

/**
 * Упрощённый SM-2. Две оценки: угадал или нет.
 * Ошибка сбрасывает интервал и снижает лёгкость, но карточка возвращается
 * уже в этой же сессии — планировщик ставит её на 10 минут вперёд.
 */
object Scheduler {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    // Значения приходят из config/tuning.json (см. data/Tuning.kt): имена
    // и места использования те же, менять их теперь можно без пересборки.
    private val cfg get() = Config.current.srs

    /**
     * Второй интервал: через сколько дней задание вернётся после двух верных
     * ответов подряд.
     *
     * У уроков неделя, и это **не** классический SM-2: там второй шаг три дня.
     * Изменено по жалобе — «надоело проходить одни и те же задания», и жалоба
     * справедливая. При трёх днях задание за первую неделю встречается трижды,
     * а фраз в уроке одиннадцать: занятие превращается в бубнёж одного и того
     * же. Скука тут дороже точности расписания — брошенное занятие удерживает
     * ноль процентов, а слегка растянутое всё же большинство.
     *
     * У словаря по-прежнему три дня. Слову нужно около десяти встреч, и они
     * и есть содержание словарной работы: растянув их, мы не разнообразим
     * занятие, а просто отодвинем результат на полгода.
     */


    /** Тот же ключ, под которым словарные карточки лежат в `cards`. */
    private const val VOCAB_LESSON = "vocab"
    private val LAPSE_DELAY_MS get() = cfg.lapseMinutes * 60_000L
    private val SKIP_DELAY_MS get() = cfg.skipHours * 60L * 60 * 1000

    fun newCard(exerciseId: String, lessonId: String, correct: Boolean, now: Long): CardEntity =
        update(
            CardEntity(
                exerciseId = exerciseId,
                lessonId = lessonId,
                dueAt = now,
                intervalDays = 0,
                ease = cfg.easeStart,
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

    /**
     * Первая встреча со словом — на экране пар.
     *
     * Карточка заводится, но не назначается: `dueAt` остаётся «сейчас», а
     * интервал нулевым. Узнать слово среди пяти, когда ответ тут же на экране,
     * не значит его знать, и растягивать по такому ответу нечего — но встречей
     * это было, а счёт [CardEntity.correct] встречи и считает.
     *
     * Промах здесь **не** лапс, в отличие от [practice]. Слово новое: не знать
     * его нормально, и записывать это в ошибку значило бы штрафовать за то,
     * что человек только начал.
     */
    fun metCard(exerciseId: String, lessonId: String, correct: Boolean, now: Long): CardEntity =
        CardEntity(
            exerciseId = exerciseId,
            lessonId = lessonId,
            dueAt = now,
            intervalDays = 0,
            ease = 2.5,
            repetitions = 0,
            lapses = 0,
            correct = if (correct) 1 else 0
        )

    fun update(card: CardEntity, correct: Boolean, now: Long): CardEntity {
        if (!correct) {
            return card.copy(
                repetitions = 0,
                intervalDays = 0,
                lapses = card.lapses + 1,
                ease = (card.ease - cfg.easeStep * 4).coerceAtLeast(cfg.easeMin),
                dueAt = now + LAPSE_DELAY_MS
            )
        }

        val reps = card.repetitions + 1
        val interval = when (reps) {
            1 -> cfg.firstDays
            2 -> if (card.lessonId == VOCAB_LESSON) cfg.vocabSecondDays
            else cfg.lessonSecondDays
            else -> (card.intervalDays * card.ease).roundToInt().coerceAtLeast(4)
        }
        return card.copy(
            repetitions = reps,
            correct = card.correct + 1,
            intervalDays = interval,
            ease = (card.ease + cfg.easeStep).coerceAtMost(cfg.easeMax),
            dueAt = now + interval * DAY_MS
        )
    }
}
