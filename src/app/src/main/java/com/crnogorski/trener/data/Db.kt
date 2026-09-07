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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStory(progress: StoryProgressEntity)

    @Query("SELECT * FROM story_progress")
    suspend fun storyProgress(): List<StoryProgressEntity>

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

@Database(
    entities = [CardEntity::class, LessonProgressEntity::class, StoryProgressEntity::class],
    version = 4,
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
