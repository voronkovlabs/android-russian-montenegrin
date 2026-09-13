package com.crnogorski.trener.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Одна карточка = одно задание из урока.
 * Интервалы считаются по упрощённому SM-2 (см. srs/Scheduler.kt).
 */
@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val exerciseId: String,
    val lessonId: String,
    val dueAt: Long,
    val intervalDays: Int,
    val ease: Double,
    /** Верных ответов подряд. Ошибка сбрасывает в ноль — это streak, не счёт. */
    val repetitions: Int,
    val lapses: Int,
    /**
     * Верных ответов всего, за всю жизнь карточки.
     *
     * Отличается от [repetitions] тем, что ошибка его не обнуляет, и нужен он
     * ровно для одного: сказать, выучено слово или нет. Порог — десять
     * (`VocabRepository.LEARNED`), и это не выдумка: Saragi, Nation и Meister
     * (1978) нашли около десяти встреч как минимум, при котором слово
     * закрепляется, а Webb (2007) намерил, что при десяти и более разнесённых
     * встречах припоминание через неделю поднимается выше 80%, тогда как при
     * менее чем шести падает ниже 30%.
     *
     * Streak на эту роль не годится: интервалы растут, и десять верных подряд
     * набегают годами.
     */
    @ColumnInfo(defaultValue = "0") val correct: Int = 0
)

@Entity(tableName = "lesson_progress")
data class LessonProgressEntity(
    @PrimaryKey val lessonId: String,
    val completedAt: Long,
    val correct: Int,
    val total: Int
)

/**
 * Сколько отрезков истории пройдено. Истории вне SRS, поэтому таблица своя
 * и простая: ключ, счётчик и отметка о том, что дочитано.
 *
 * Ключ составной: у одной истории два занятия — прочитать вслух и перевести
 * вслух ([StoryMode]). Прогресс у них общим быть не может, иначе прочитанная
 * история открывалась бы переведённой.
 */
@Entity(tableName = "story_progress", primaryKeys = ["storyId", "mode"])
data class StoryProgressEntity(
    val storyId: String,
    val mode: String,
    val chunksDone: Int,
    val finishedAt: Long
)

/**
 * Один день занятий.
 *
 * Отдельная таблица, а не выкладки по карточкам, потому что по карточкам этого
 * не восстановить: в `cards` лежит только нынешнее состояние — когда карточка
 * будет спрошена снова, — а не то, что происходило вчера. Историю надо копить,
 * и копится она здесь.
 *
 * Ключ — **календарный день** строкой `2026-09-08`, а не отметка времени.
 * «Сегодня» человек понимает как день, а не как последние сутки; строкой — чтобы
 * сортировка по дате была сортировкой по тексту, а границы дня считались один
 * раз при записи, а не в каждом запросе.
 *
 * Время разложено по видам занятий сразу, а не одним числом с разбором потом:
 * потом разбирать будет нечем, ответ уже забыт. Итог — их сумма ([seconds]), и
 * отдельной колонки под него нет: две записи одного факта однажды разойдутся.
 */
/**
 * Один пройденный срез: одинаковая проверка, повторяемая время от времени.
 *
 * Смысл таблицы — в том, чего нет в остальных: **сравнимая точка во времени**.
 * `cards` хранит нынешнее состояние, `day_stats` — усердие; ни то, ни другое
 * не отвечает на вопрос «стал ли я знать больше», потому что и материал, и его
 * трудность меняются каждый день. Срез спрашивает одно и то же одинаковым
 * способом, поэтому два его результата можно честно поставить рядом.
 *
 * Ключ — время начала: срезов за день может быть и два, а склеивать их в один
 * день значило бы терять половину.
 */
@Entity(tableName = "checkups")
data class CheckupEntity(
    @PrimaryKey val takenAt: Long,
    /** Календарный день, для подписи на экране. */
    val day: String,
    val total: Int,
    val correct: Int,
    /** Сколько секунд занял — сам по себе показатель беглости. */
    val seconds: Int
)

@Entity(tableName = "day_stats")
data class DayStatEntity(
    @PrimaryKey val day: String,
    val lessonSeconds: Int = 0,
    val reviewSeconds: Int = 0,
    val wordSeconds: Int = 0,
    val storySeconds: Int = 0,
    /** Отвеченных заданий. Экран пар — одно задание, хоть слов на нём пять. */
    val answers: Int = 0,
    val correct: Int = 0,
    /** Закрытых уроков курса: все задания урока заведены. */
    val lessons: Int = 0,
    /** Доведённых до конца занятий — любых, включая повторение и словарь. */
    val sessions: Int = 0,
    val chunks: Int = 0,
    /** Новых слов, введённых в этот день. */
    val words: Int = 0
) {
    val seconds: Int get() = lessonSeconds + reviewSeconds + wordSeconds + storySeconds

    /**
     * Был ли этот день днём занятий.
     *
     * Не «минут больше нуля»: у дней, восстановленных задним числом из
     * `lesson_progress`, времени нет вовсе — его тогда не хранили, — но урок в
     * тот день закрыли. Вычёркивать такой день из серии значило бы наказать за
     * то, что раньше не считали время.
     */
    val active: Boolean
        get() = seconds > 0 || answers > 0 || lessons > 0 || sessions > 0 || chunks > 0
}

@Dao
interface AppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCard(card: CardEntity)

    /**
     * Просроченные карточки уроков, самые старые первыми, не больше [limit].
     *
     * Потолок обязателен: без него сессия повторения — это все накопившиеся
     * карточки разом, а их со временем становятся сотни. Такую сессию нельзя
     * ни закончить, ни бросить без потери.
     *
     * Словарные карточки сюда не входят (`skip` — их `lessonId`): их тысячи, и
     * они бы вытеснили уроки из повторения целиком. У словаря свой раздел и
     * свой потолок — это решение владельца, а не случайность запроса.
     */
    @Query(
        "SELECT * FROM cards WHERE dueAt <= :now AND lessonId != :skip " +
            "ORDER BY dueAt ASC LIMIT :limit"
    )
    suspend fun dueCards(now: Long, limit: Int, skip: String): List<CardEntity>

    /** Сколько просрочено на самом деле — счётчик на главном экране честный. */
    @Query("SELECT COUNT(*) FROM cards WHERE dueAt <= :now AND lessonId != :skip")
    suspend fun dueCount(now: Long, skip: String): Int

    @Query("SELECT COUNT(*) FROM cards WHERE dueAt <= :now AND lessonId != :skip")
    fun dueCountFlow(now: Long, skip: String): Flow<Int>

    /**
     * Все словарные карточки разом.
     *
     * Именно все, а не просроченные: раздел словаря должен знать и то, что
     * уже выучено, — образец склонения открывается только после того, как
     * усвоено значение слова.
     */
    @Query("SELECT * FROM cards WHERE lessonId = :lesson")
    suspend fun vocabCards(lesson: String): List<CardEntity>

    @Query("SELECT COUNT(*) FROM cards WHERE dueAt <= :now AND lessonId = :lesson")
    suspend fun vocabDue(now: Long, lesson: String): Int

    /**
     * Карточки одного урока.
     *
     * Нужно ежедневному заданию: урок вводится порциями, и чтобы понять, какая
     * порция следующая, надо знать, какие задания уже заведены. Спрашивать
     * ради этого всю таблицу нельзя — в ней тысячи словарных карточек.
     */
    @Query("SELECT * FROM cards WHERE lessonId = :lesson")
    suspend fun cardsIn(lesson: String): List<CardEntity>

    @Query("SELECT * FROM cards WHERE exerciseId = :id")
    suspend fun card(id: String): CardEntity?

    /** Весь прогресс целиком — для копии в папку владельца. */
    @Query("SELECT * FROM cards")
    suspend fun allCards(): List<CardEntity>

    /** Нужно для отката: жалоба на сломанное задание снимает карточку, если она только что создалась. */
    @Query("DELETE FROM cards WHERE exerciseId = :id")
    suspend fun deleteCard(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLesson(progress: LessonProgressEntity)

    @Query("SELECT * FROM lesson_progress")
    suspend fun lessonProgress(): List<LessonProgressEntity>

    @Query("SELECT * FROM lesson_progress")
    fun lessonProgressFlow(): Flow<List<LessonProgressEntity>>

    /**
     * Прибавить к сегодняшнему дню.
     *
     * Одним запросом, а не «прочитать — сложить — записать»: занятие пишет
     * статистику из нескольких мест сразу (ответ, конец занятия, отрезок
     * истории), и корутины между чтением и записью успевают переслоиться.
     * `ON CONFLICT DO UPDATE` складывает прямо в базе, и потеряться там нечему.
     */
    @Query(
        "INSERT INTO day_stats " +
            "(day, lessonSeconds, reviewSeconds, wordSeconds, storySeconds, " +
            "answers, correct, lessons, sessions, chunks, words) " +
            "VALUES (:day, :lessonSeconds, :reviewSeconds, :wordSeconds, :storySeconds, " +
            ":answers, :correct, :lessons, :sessions, :chunks, :words) " +
            "ON CONFLICT(day) DO UPDATE SET " +
            "lessonSeconds = lessonSeconds + excluded.lessonSeconds, " +
            "reviewSeconds = reviewSeconds + excluded.reviewSeconds, " +
            "wordSeconds = wordSeconds + excluded.wordSeconds, " +
            "storySeconds = storySeconds + excluded.storySeconds, " +
            "answers = answers + excluded.answers, " +
            "correct = correct + excluded.correct, " +
            "lessons = lessons + excluded.lessons, " +
            "sessions = sessions + excluded.sessions, " +
            "chunks = chunks + excluded.chunks, " +
            "words = words + excluded.words"
    )
    suspend fun bumpDay(
        day: String,
        lessonSeconds: Int = 0,
        reviewSeconds: Int = 0,
        wordSeconds: Int = 0,
        storySeconds: Int = 0,
        answers: Int = 0,
        correct: Int = 0,
        lessons: Int = 0,
        sessions: Int = 0,
        chunks: Int = 0,
        words: Int = 0
    )

    /** Все дни: строк тут столько, сколько дней занимались, — их немного. */
    @Query("SELECT * FROM day_stats ORDER BY day")
    suspend fun days(): List<DayStatEntity>

    /**
     * Положить день целиком — для восстановления из копии.
     *
     * Отдельно от [bumpDay], и это не дубль: тот **прибавляет**, а
     * восстановление обязано класть ровно то, что в файле. Прибавлением
     * восстановление удвоило бы день, если копию накатить дважды.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: DayStatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStory(progress: StoryProgressEntity)

    @Query("SELECT * FROM story_progress")
    suspend fun storyProgress(): List<StoryProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addCheckup(checkup: CheckupEntity)

    @Query("SELECT * FROM checkups ORDER BY takenAt")
    suspend fun checkups(): List<CheckupEntity>

    @Query("SELECT * FROM story_progress WHERE storyId = :id AND mode = :mode")
    suspend fun story(id: String, mode: String): StoryProgressEntity?
}

/**
 * Версия 2 добавила таблицу историй.
 *
 * Миграция, а не разрушающий откат: прогресс — единственное, что в этом
 * приложении нельзя восстановить, и терять его при обновлении недопустимо.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Форма записи ровно та, что генерирует Room: обратные кавычки и
        // отдельный PRIMARY KEY в конце. Room сверяет схему при открытии базы,
        // и расхождение уронило бы приложение на запуске вместе с прогрессом.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `story_progress` (" +
                "`storyId` TEXT NOT NULL, " +
                "`chunksDone` INTEGER NOT NULL, " +
                "`finishedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`storyId`))"
        )
    }
}

/**
 * Версия 3 развела чтение и перевод: у истории появилось второе занятие,
 * а у строки прогресса — колонка режима в первичном ключе.
 *
 * Ключ в SQLite не расширяется на месте, поэтому таблица пересоздаётся, а
 * старые строки переезжают как чтение: до этой версии другого занятия не было.
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `story_progress_new` (" +
                "`storyId` TEXT NOT NULL, " +
                "`mode` TEXT NOT NULL, " +
                "`chunksDone` INTEGER NOT NULL, " +
                "`finishedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`storyId`, `mode`))"
        )
        db.execSQL(
            "INSERT INTO `story_progress_new` (`storyId`, `mode`, `chunksDone`, `finishedAt`) " +
                "SELECT `storyId`, 'read', `chunksDone`, `finishedAt` FROM `story_progress`"
        )
        db.execSQL("DROP TABLE `story_progress`")
        db.execSQL("ALTER TABLE `story_progress_new` RENAME TO `story_progress`")
    }
}

/**
 * Версия 4 добавила счётчик верных ответов.
 *
 * Самая безопасная из миграций — добавление колонки со значением по умолчанию.
 * У старых карточек счёт начинается с нуля: узнать, сколько раз на них
 * ответили верно до этой версии, всё равно неоткуда, а обнулять прогресс
 * повторений ради счётчика было бы куда хуже.
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `cards` ADD COLUMN `correct` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Версия 5 добавила статистику по дням.
 *
 * Таблица новая, старые данные не трогаются — самая безопасная форма миграции
 * после добавления колонки.
 *
 * Заодно **задним числом восстанавливаются закрытые уроки**: у
 * `lesson_progress` есть `completedAt`, и по нему видно, в какой день урок был
 * закрыт. Со временем так не выйдет — его история не хранилась нигде, и график
 * минут начнётся с дня установки. Честнее показать пустое начало, чем
 * придумывать числа.
 *
 * `'localtime'` обязателен: без него полночь считалась бы по Гринвичу, и
 * вечерние занятия уезжали бы на день вперёд.
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Форма ровно та, что генерирует Room (сверено по AppDb_Impl.java):
        // расхождение уронило бы приложение на запуске вместе с прогрессом.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_stats` (" +
                "`day` TEXT NOT NULL, " +
                "`lessonSeconds` INTEGER NOT NULL, " +
                "`reviewSeconds` INTEGER NOT NULL, " +
                "`wordSeconds` INTEGER NOT NULL, " +
                "`storySeconds` INTEGER NOT NULL, " +
                "`answers` INTEGER NOT NULL, " +
                "`correct` INTEGER NOT NULL, " +
                "`lessons` INTEGER NOT NULL, " +
                "`sessions` INTEGER NOT NULL, " +
                "`chunks` INTEGER NOT NULL, " +
                "`words` INTEGER NOT NULL, " +
                "PRIMARY KEY(`day`))"
        )
        db.execSQL(
            "INSERT INTO `day_stats` " +
                "(`day`, `lessonSeconds`, `reviewSeconds`, `wordSeconds`, `storySeconds`, " +
                "`answers`, `correct`, `lessons`, `sessions`, `chunks`, `words`) " +
                "SELECT date(`completedAt` / 1000, 'unixepoch', 'localtime'), " +
                "0, 0, 0, 0, 0, 0, COUNT(*), 0, 0, 0 " +
                "FROM `lesson_progress` GROUP BY 1"
        )
    }
}

/**
 * Версия 6 добавила срезы.
 *
 * Таблица новая, старого не трогает: самая безопасная форма миграции. Задним
 * числом тут восстанавливать нечего и не из чего — срез это измерение, а не
 * побочный след занятий, и до 1.82 его просто никто не делал.
 */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Форма ровно та, что генерирует Room (сверено по AppDb_Impl.java).
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `checkups` (" +
                "`takenAt` INTEGER NOT NULL, " +
                "`day` TEXT NOT NULL, " +
                "`total` INTEGER NOT NULL, " +
                "`correct` INTEGER NOT NULL, " +
                "`seconds` INTEGER NOT NULL, " +
                "PRIMARY KEY(`takenAt`))"
        )
    }
}

@Database(
    entities = [
        CheckupEntity::class,
        CardEntity::class,
        LessonProgressEntity::class,
        StoryProgressEntity::class,
        DayStatEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "crnogorski.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
            )
                .build().also { instance = it }
        }
    }
}
