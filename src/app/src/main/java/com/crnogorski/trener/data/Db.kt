package com.crnogorski.trener.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    val repetitions: Int,
    val lapses: Int
)

@Entity(tableName = "lesson_progress")
data class LessonProgressEntity(
    @PrimaryKey val lessonId: String,
    val completedAt: Long,
    val correct: Int,
    val total: Int
)

@Dao
interface AppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCard(card: CardEntity)

    /**
     * Просроченные карточки, самые старые первыми, не больше [limit].
     *
     * Потолок обязателен: без него сессия повторения — это все накопившиеся
     * карточки разом, а их со временем становятся сотни. Такую сессию нельзя
     * ни закончить, ни бросить без потери.
     */
    @Query("SELECT * FROM cards WHERE dueAt <= :now ORDER BY dueAt ASC LIMIT :limit")
    suspend fun dueCards(now: Long, limit: Int): List<CardEntity>

    /** Сколько просрочено на самом деле — счётчик на главном экране честный. */
    @Query("SELECT COUNT(*) FROM cards WHERE dueAt <= :now")
    suspend fun dueCount(now: Long): Int

    @Query("SELECT COUNT(*) FROM cards WHERE dueAt <= :now")
    fun dueCountFlow(now: Long): Flow<Int>

    @Query("SELECT * FROM cards WHERE exerciseId = :id")
    suspend fun card(id: String): CardEntity?

    /** Нужно для отката: жалоба на сломанное задание снимает карточку, если она только что создалась. */
    @Query("DELETE FROM cards WHERE exerciseId = :id")
    suspend fun deleteCard(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLesson(progress: LessonProgressEntity)

    @Query("SELECT * FROM lesson_progress")
    suspend fun lessonProgress(): List<LessonProgressEntity>

    @Query("SELECT * FROM lesson_progress")
    fun lessonProgressFlow(): Flow<List<LessonProgressEntity>>
}

@Database(
    entities = [CardEntity::class, LessonProgressEntity::class],
    version = 1,
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
            ).build().also { instance = it }
        }
    }
}
