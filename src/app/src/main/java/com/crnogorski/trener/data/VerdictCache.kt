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

/**
 * Ответ, который Claude однажды засчитал.
 *
 * [hash] — отпечаток всего запроса целиком (см. `HaikuChecker.key`): модель,
 * температура, системный промпт и собранное сообщение с заданием, эталоном и
 * ответом. Совпадение хэша означает, что ровно такой запрос уже отправлялся, а
 * при `temperature = 0` и прибитом снапшоте модели тот же запрос даёт тот же
 * вердикт. Больше кэш ни на что не опирается.
 *
 * Ответ в ключ идёт **дословно**, без нормализации регистра и пунктуации.
 * «Idem.» и «idem» — два разных запроса, и пусть будут два разных ключа:
 * промах не стоит ничего, а склейка двух разных ответов в один ключ выдала бы
 * второму чужой вердикт, молча. Это было единственное место во всей конструкции,
 * где кэш мог соврать, — его тут нет.
 *
 * Открытый текст лежит рядом с хэшем не для работы, а чтобы кэш можно было
 * прочитать глазами: по одному хэшу не видно, что именно засчитано.
 */
@Entity(tableName = "accepted")
data class AcceptedEntity(
    @PrimaryKey val hash: String,
    /** Задание, по которому это засчитано, — по нему жалоба сносит запись. */
    val exerciseId: String,
    val task: String,
    val reference: String,
    val answer: String,
    val feedback: String,
    val better: String,
    val savedAt: Long
)

@Dao
interface CacheDao {

    @Query("SELECT * FROM accepted WHERE hash = :hash")
    suspend fun find(hash: String): AcceptedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: AcceptedEntity)

    /** Всё, что засчитано по этому заданию: жалоба ставит вердикты под сомнение. */
    @Query("DELETE FROM accepted WHERE exerciseId = :exerciseId")
    suspend fun forget(exerciseId: String)

    @Query("DELETE FROM accepted")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM accepted")
    suspend fun count(): Int
}

/**
 * База кэша — **отдельная**, не `crnogorski.db`.
 *
 * Причина одна и она весомая: кэш обязан быть физически неспособен испортить
 * прогресс. Схема кэша ещё будет меняться, а каждое изменение схемы в общей базе
 * — это миграция, которую надо сверять с `AppDb_Impl.java`, и ненулевой шанс
 * уронить приложение на запуске вместе с единственными невосстановимыми данными.
 * Здесь же схема сносится разрушающе: потеря кэша стоит центы.
 */
@Database(entities = [AcceptedEntity::class], version = 1, exportSchema = false)
abstract class CacheDb : RoomDatabase() {
    abstract fun dao(): CacheDao

    companion object {
        @Volatile private var instance: CacheDb? = null

        fun get(context: Context): CacheDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CacheDb::class.java,
                "verdicts.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}

/**
 * Память о принятых ответах: то, что Claude уже засчитал, второй раз не спрашиваем.
 *
 * Хранятся **только** засчитанные ответы. Закэшированное «неверно» заморозило бы
 * ровно ту ошибку, ради которой существуют жалоба `verdict_wrong` и правка
 * промпта: на повторении тот же ответ получил бы тот же кривой вердикт, и
 * починка промпта ничего бы не изменила. К тому же ошибаются каждый раз
 * по-своему — попаданий там почти не было бы.
 *
 * Выгода не столько в деньгах (проверка стоит около 0,1 ¢, за год набегают
 * центы), сколько в том, что на повторении вердикт появляется мгновенно, без
 * похода в сеть.
 *
 * Устный перевод в историях не кэшируется вовсе: у `checkSpoken` нет ключа —
 * см. `HaikuChecker`. Движок распознавания каждый раз слышит чуть иначе, так
 * что попаданий там почти не бывает, а сбросить кривой вердикт в истории нечем:
 * категорийная жалоба есть только на экране результата урока.
 *
 * В копию прогресса кэш не попадает: это не прогресс, а экономия.
 */
class VerdictCache(context: Context) {

    private val dao = CacheDb.get(context).dao()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Галочка в настройках. По умолчанию включено.
     *
     * Выключение гасит и чтение, и запись, но накопленное не трогает: галочку
     * снимают, чтобы посмотреть, как отвечает живая модель, а не чтобы стереть
     * память. Для стирания рядом отдельная кнопка.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    suspend fun find(hash: String): AcceptedEntity? =
        if (enabled) dao.find(hash) else null

    suspend fun remember(
        hash: String,
        exerciseId: String,
        task: String,
        reference: String,
        answer: String,
        feedback: String,
        better: String
    ) {
        if (!enabled) return
        dao.put(
            AcceptedEntity(
                hash = hash,
                exerciseId = exerciseId,
                task = task,
                reference = reference,
                answer = answer,
                feedback = feedback,
                better = better,
                savedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Забыть всё, засчитанное по заданию, — на любую жалобу, а не только на
     * «Claude ошибся».
     *
     * Жалоба означает, что задание кривое, и вердикты по нему после этого
     * доверия не заслуживают: при неверном эталоне модель сравнивала ответ не
     * с тем, при двусмысленном задании — судила не о том. Лишние удалённые
     * строки стоят доли цента, а оставленный кривой вердикт пережил бы починку
     * урока и вылез на повторении.
     */
    suspend fun forget(exerciseId: String) = dao.forget(exerciseId)

    suspend fun clear(): Int {
        val had = dao.count()
        dao.clear()
        return had
    }

    suspend fun count(): Int = dao.count()

    private companion object {
        // Те же настройки, что у папки для копии прогресса: файл один на приложение.
        const val PREFS = "crnogorski"
        const val KEY_ENABLED = "verdict_cache"
    }
}
