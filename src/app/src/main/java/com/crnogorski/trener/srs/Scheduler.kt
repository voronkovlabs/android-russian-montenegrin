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

    fun newCard(
        exerciseId: String,
        lessonId: String,
        correct: Boolean,
        now: Long,
        easy: Boolean = false
    ): CardEntity =
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
            now,
            easy
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

    /**
     * Отложить слово надолго: «сейчас мне это не нужно».
     *
     * Заведено по словам владельца: «надоело учить одни и те же слова, которые
     * не кажутся полезными для моей жизни прямо сейчас». Частотный список
     * честно даёт частотное, но чужая частота — не своя: человеку, живущему у
     * моря, «снегопад» и «налогоплательщик» не нужны ещё год.
     *
     * От [postpone] отличается только сроком, и разница смысловая: пропуск это
     * «не могу ответить сейчас» (шумно, нет микрофона) на несколько часов, а
     * это — «не хочу это слово» на недели.
     *
     * `ease`, `repetitions`, `lapses` и счёт встреч не трогаются вовсе.
     * Откладывание не ошибка и не достижение: слово вернётся ровно таким,
     * каким ушло, с той же историей.
     */
    fun snooze(card: CardEntity, now: Long): CardEntity =
        card.copy(dueAt = now + cfg.snoozeDays * DAY_MS)

    /**
     * Отложенное слово, которого ещё не было в SRS.
     *
     * Бывает и так: слово показали впервые, и оно сразу не нужно. Карточка
     * заводится пустой, но с далёкой датой — иначе отбор предложил бы его
     * завтра же как новое, и кнопка не сделала бы ничего.
     */
    fun snoozedCard(exerciseId: String, lessonId: String, now: Long): CardEntity =
        CardEntity(
            exerciseId = exerciseId,
            lessonId = lessonId,
            dueAt = now + cfg.snoozeDays * DAY_MS,
            intervalDays = 0,
            ease = cfg.easeStart,
            repetitions = 0,
            lapses = 0
        )

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
     * Показ парадигмы — знакомство, а не ответ.
     *
     * Таблица при первой встрече ничего не спрашивает: она показана целиком,
     * и нажать можно только «Понятно». Расписание двигать надо — иначе показ
     * вернулся бы в тот же день и спрос не наступил бы никогда, — а вот счёт
     * верных ответов не надо: ошибиться там нечем.
     *
     * Без этой оговорки слово доходило бы до «выучено» (десять верных),
     * **ни разу не будучи спрошенным**, — а это число стоит крупно на витрине
     * отчёта, с которой делают снимок. Найдено живым прогоном 24.09.2026.
     *
     * Лёгкость тоже не растёт: «стало легче» — вывод из припоминания, а
     * припоминания здесь не было.
     */
    fun shown(card: CardEntity, now: Long): CardEntity =
        update(card, correct = true, now = now)
            .copy(correct = card.correct, ease = card.ease)

    /** Показ, который заодно заводит карточку: см. [shown]. */
    fun shownCard(exerciseId: String, lessonId: String, now: Long): CardEntity =
        shown(
            CardEntity(
                exerciseId = exerciseId,
                lessonId = lessonId,
                dueAt = now,
                intervalDays = 0,
                ease = cfg.easeStart,
                repetitions = 0,
                lapses = 0
            ),
            now
        )

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

    /**
     * Верный ответ двигает карточку вперёд, неверный — сбрасывает.
     *
     * [easy] — ответили **уверенно**, то есть заметно быстрее обычного для
     * этого типа задания. Заведено по жалобе владельца (issue 67): «если
     * пользователь делает упражнение уверенно и без ошибок с первого раза, его
     * надо откладывать надолго, потому что оно для него слишком простое и
     * становится скучно». Это уже вторая жалоба на одно и то же — по первой
     * второй шаг у уроков стал неделей вместо трёх дней, и, судя по всему,
     * не добрал.
     *
     * В настоящем SM-2 оценок четыре, у нас двоичная, и [easy] возвращает
     * половину потерянного: «верно» и «легко» снова разные вещи. Спрашивать об
     * этом человека кнопкой было бы вернее всего и хуже всего — трение на
     * каждой карточке в приложении, смысл которого «открыл, нажал, сделал».
     * Поэтому мерим время: оно у нас и так замеряется по каждому типу
     * (`Pace.seconds`), и порог берётся от среднего этого же типа — выбор
     * варианта быстрее набора текста сам по себе, и сравнивать их между собой
     * не нужно.
     *
     * **Перекос намеренно односторонний.** Бонус даётся только за быстрый
     * ответ; медленный не наказывается ничем. Отвлеклись на телефон —
     * бонуса нет, и всё; наоборот это работать не должно, потому что медленный
     * ответ может значить и «думал», и «пришла смс».
     *
     * Плата прямая и её надо знать: интервалы растут быстрее, значит растёт и
     * забывание. Это обмен скуки на точность расписания, а не бесплатное
     * улучшение. Ноль в `srs.easyUnder` возвращает прежнее поведение.
     */
    fun update(
        card: CardEntity,
        correct: Boolean,
        now: Long,
        easy: Boolean = false
    ): CardEntity {
        if (!correct) {
            // Словарная карточка возвращается **завтра**, а не через десять
            // минут. Десятиминутный возврат — ступень переучивания, и она
            // хороша, когда материала мало: промахнулся, увидел снова, поправил.
            // В словаре из трёхсот слов та же ступень превращает занятие в
            // толчение одного и того же, и владелец пожаловался именно на это.
            // У заданий урока правило прежнее: там заход короткий и связный.
            val back =
                if (card.lessonId == VOCAB_LESSON) cfg.vocabLapseDays * DAY_MS
                else LAPSE_DELAY_MS
            return card.copy(
                repetitions = 0,
                intervalDays = 0,
                lapses = card.lapses + 1,
                ease = (card.ease - cfg.easeStep * 4).coerceAtLeast(cfg.easeMin),
                dueAt = now + back
            )
        }

        val reps = card.repetitions + 1
        val base = when (reps) {
            1 -> cfg.firstDays
            2 -> if (card.lessonId == VOCAB_LESSON) cfg.vocabSecondDays
            else cfg.lessonSecondDays
            else -> (card.intervalDays * card.ease).roundToInt().coerceAtLeast(4)
        }
        // Множитель бьёт и по первым шагам, а не только по разгону: скука
        // живёт именно там — задание, отвеченное с лёту, возвращается завтра.
        val interval = if (easy) (base * cfg.easyBonus).roundToInt().coerceAtLeast(base) else base
        // Лёгкость растёт втрое быстрее: одна прибавка — это шаг, которого на
        // разгоне не видно, а весь смысл в том, чтобы разгон ускорился.
        val step = if (easy) cfg.easeStep * 3 else cfg.easeStep
        return card.copy(
            repetitions = reps,
            correct = card.correct + 1,
            intervalDays = interval,
            ease = (card.ease + step).coerceAtMost(cfg.easeMax),
            dueAt = now + interval * DAY_MS
        )
    }
}
