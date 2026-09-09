package com.crnogorski.trener.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crnogorski.trener.BuildConfig
import com.crnogorski.trener.data.AppDb
import com.crnogorski.trener.data.CardEntity
import com.crnogorski.trener.data.Complaint
import com.crnogorski.trener.data.ComplaintReason
import com.crnogorski.trener.data.ComplaintStore
import com.crnogorski.trener.data.ComplaintVerdict
import com.crnogorski.trener.data.Config
import com.crnogorski.trener.data.DayStatEntity
import com.crnogorski.trener.data.Exercise
import com.crnogorski.trener.data.Glossary
import com.crnogorski.trener.data.LessonProgressEntity
import com.crnogorski.trener.data.LessonRef
import com.crnogorski.trener.data.LessonRepository
import com.crnogorski.trener.data.LocalCheck
import com.crnogorski.trener.data.MatchPair
import com.crnogorski.trener.data.IDEA_REASON
import com.crnogorski.trener.data.NOTE_REASON
import com.crnogorski.trener.data.Pace
import com.crnogorski.trener.data.ProgressStore
import com.crnogorski.trener.data.StoryChunk
import com.crnogorski.trener.data.StoryMode
import com.crnogorski.trener.data.StoryProgressEntity
import com.crnogorski.trener.data.StoryRef
import com.crnogorski.trener.data.VerdictCache
import com.crnogorski.trener.data.VocabFile
import com.crnogorski.trener.data.VocabKind
import com.crnogorski.trener.data.VocabRepository
import com.crnogorski.trener.data.VocabWord
import com.crnogorski.trener.data.exerciseFor
import com.crnogorski.trener.data.matchExercise
import com.crnogorski.trener.data.matchPairFor
import com.crnogorski.trener.data.needsModelCheck
import com.crnogorski.trener.data.referenceAnswer
import com.crnogorski.trener.data.typeName
import com.crnogorski.trener.net.CheckResult
import com.crnogorski.trener.net.GithubIssues
import com.crnogorski.trener.net.HaikuChecker
import com.crnogorski.trener.net.Verdict
import com.crnogorski.trener.srs.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

data class LessonCard(
    val ref: LessonRef,
    val done: Boolean,
    val score: String?
)

/**
 * Вкладка главного экрана. Уроки и истории — разные занятия, мешать их в одном
 * списке незачем.
 *
 * [Today] стоит первой и открывается по умолчанию: смысл ежедневного задания
 * в том, чтобы не выбирать. Остальные три — для тех дней, когда выбрать
 * хочется.
 */
enum class HomeTab { Today, Lessons, Stories, Words }

/** Раздел главного экрана: заголовок и уроки под ним, в порядке из `index.json`. */
data class LessonGroup(
    val title: String,
    val cards: List<LessonCard>
) {
    val done: Int get() = cards.count { it.done }
}

/**
 * Одно направление словаря на главном экране.
 *
 * [due] — сколько карточек просрочено, [fresh] — сколько новых слов ещё можно
 * взять сегодня, [ready] — сколько можно прогнать вне расписания, [learned] —
 * сколько уже выучено (десять верных ответов, см. `VocabRepository.LEARNED`).
 *
 * Новых слов в день намеренно немного: карточке нужно 8–10 встреч, и обещать
 * себе больше десятка слов в день значит копить долг.
 */
data class VocabTrack(
    val due: Int = 0,
    val fresh: Int = 0,
    val ready: Int = 0,
    val learned: Int = 0,
    val started: Int = 0,
    val total: Int = 0
)

/**
 * Два направления, две плашки.
 *
 * [toTarget] — с русского на черногорский: назвать слово, поставить его в
 * форму. [toNative] — обратно: понять услышанное или прочитанное. Это разное
 * знание и держится оно по-разному, поэтому и очереди раздельные.
 */
data class VocabTracks(
    val toTarget: VocabTrack = VocabTrack(),
    val toNative: VocabTrack = VocabTrack()
)

/**
 * Отрезки истории, предложенные на сегодня.
 *
 * Истории в общую сессию не подмешиваются: у них свой экран, свой цикл
 * «послушал — сказал — открылся перевод» и требование говорить вслух. Поэтому
 * они идут хвостом — отдельным шагом после упражнений. Заодно это удобно:
 * в транспорте или при людях хвост просто пропускают, а занятие всё равно
 * считается сделанным.
 */
data class StoryStep(
    val id: String,
    val title: String,
    val mode: StoryMode,
    /** Сколько отрезков предлагаем сегодня. */
    val chunks: Int,
    /** Сколько осталось в истории всего. */
    val left: Int
)

/**
 * Ежедневное задание — то, что делают, когда не хотят выбирать.
 *
 * Ограничено минутами, а не числом упражнений ([minutes]): двадцать заданий на
 * выбор варианта и двадцать на чтение вслух — это разное время, и обещать
 * «двадцать заданий в день» значит обещать неизвестно что. Сколько какой тип
 * занимает, приложение замеряет само (`Pace`).
 *
 * [spent] — сколько минут уже потрачено сегодня, на любые занятия, не только
 * на ежедневное. [estimate] — во сколько минут оценено набранное.
 */
data class DailyPlan(
    val items: List<SessionItem> = emptyList(),
    val review: Int = 0,
    val words: Int = 0,
    val lesson: Int = 0,
    val lessonTitle: String = "",
    val story: StoryStep? = null,
    val minutes: Int = Pace.DEFAULT_MINUTES,
    val spent: Int = 0,
    val estimate: Int = 0,
    /** Сколько секунд бюджета осталось. Не минут: округление врало бы у нуля. */
    val left: Int = 0
) {
    /** Бюджет на сегодня выбран — можно и дальше, но уже сверх нормы. */
    val full: Boolean get() = left <= 0

    /** Брать нечего: и курс, и словарь на сегодня исчерпаны. */
    val empty: Boolean get() = items.isEmpty() && story == null
}

/**
 * Сводка для главного экрана: одна строка, по нажатию — полный отчёт.
 *
 * Отдельный кусочек, а не весь [StatsState]: главный экран пересобирается
 * после каждого занятия, и тянуть ради строки все тридцать дней и весь курс
 * незачем.
 */
data class StatsBrief(
    val todayMinutes: Int = 0,
    val todayAnswers: Int = 0,
    val totalMinutes: Int = 0,
    val totalSessions: Int = 0,
    val streak: Int = 0
)

/**
 * Заставка после ежедневного задания.
 *
 * Собирается один раз, в момент, когда занятие уже записано: числа тут те же,
 * что в отчёте, но за сегодня и за это занятие. Держать её в состоянии сессии
 * нельзя — сессия к тому времени закрыта.
 */
data class SplashState(
    /** Черногорская фраза и её перевод: меняются по тому, чем кончился день. */
    val phrase: String,
    val gloss: String,
    val streak: Int,
    /** Минут за сегодня — всё занятие целиком, не только это. */
    val minutes: Int,
    /** Заданий в этом занятии и доля верных с первого раза. */
    val answers: Int,
    val accuracy: Int,
    /** Строка мелким под числами: слова и курс. */
    val footer: String
)

/**
 * Отчёт по занятиям.
 *
 * [today] и [total] — одна и та же форма строки: сегодняшний день и сумма всех
 * дней. [days] — ровно тридцать дней подряд, **включая пустые**: без них график
 * молча склеивал бы пропуски и показывал занятия там, где их не было.
 *
 * Всё остальное — состояние «прямо сейчас», а не история: сколько уроков курса
 * закрыто, сколько слов заведено и выучено, сколько карточек просрочено. Копить
 * это по дням незачем — оно и так восстанавливается из базы в любой момент.
 */
data class StatsState(
    val today: DayStatEntity = DayStatEntity(day = ""),
    val total: DayStatEntity = DayStatEntity(day = ""),
    val days: List<DayStatEntity> = emptyList(),
    val lessonsDone: Int = 0,
    val lessonsTotal: Int = 0,
    val exercisesDone: Int = 0,
    val exercisesTotal: Int = 0,
    val wordsIntroduced: Int = 0,
    val wordsLearned: Int = 0,
    val wordsTotal: Int = 0,
    val dueLessons: Int = 0,
    val dueWords: Int = 0,
    /** В скольких из тридцати дней вообще занимались. */
    val activeDays: Int = 0,
    /** Сколько дней подряд занимались сейчас и сколько подряд выходило лучше всего. */
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val loading: Boolean = true
)

data class HomeState(
    val groups: List<LessonGroup> = emptyList(),
    val storyGroups: List<StoryGroup> = emptyList(),
    val dueCount: Int = 0,
    val vocab: VocabTracks = VocabTracks(),
    val daily: DailyPlan = DailyPlan(),
    val stats: StatsBrief = StatsBrief(),
    val tab: HomeTab = HomeTab.Today,
    /** Заголовки развёрнутых разделов. По умолчанию свёрнуты все. */
    val expandedGroups: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null
)

/** Экран настроек: копия прогресса, отчёт о жалобах, проверка синтеза речи. */
data class SettingsState(
    val complaintCount: Int = 0,
    val filePath: String = "",
    val versionName: String = "",
    val versionCode: Int = 0,
    /** Папка для копии прогресса, если выбрана и право на неё живо. */
    val progressFolder: String? = null,
    /** Путь копии на самом телефоне — она пишется всегда. */
    val progressLocal: String = "",
    /** Когда и куда копия легла в последний раз. */
    val progressLastSave: String? = null,
    /** Есть ли на телефоне копия, из которой можно восстановиться. */
    val progressLocalExists: Boolean = false,
    /** Сколько минут в день назначено ежедневному заданию. */
    val dailyMinutes: Int = Pace.DEFAULT_MINUTES,
    /** Помнить ли ответы, засчитанные Claude. По умолчанию да. */
    val cacheEnabled: Boolean = true,
    /** Сколько жалоб ждёт отправки в GitHub. */
    val complaintsLeft: Int = 0,
    /** Почему очередь встала, если встала. */
    val complaintsError: String? = null,
    /** Настроен ли токен: без него отправлять нечем. */
    val issuesReady: Boolean = false,
    /** Сколько ответов уже запомнено. */
    val cacheCount: Int = 0,
    /** Вердиктов взято из памяти. */
    val cacheHits: Int = 0,
    /** Вердиктов спрошено у модели. */
    val cacheAsked: Int = 0,
    /** Когда настройки курса забирали в последний раз и чем это кончилось. */
    val tuningFetched: String? = null,
    /** Результат последнего действия — показывается под кнопками. */
    val notice: String? = null
)

data class SessionItem(val lessonId: String, val exercise: Exercise)

/** Строка раздела «Истории» на главном экране. */
data class StoryCard(
    val ref: StoryRef,
    val mode: StoryMode,
    val done: Int,
    val finished: Boolean
)

/**
 * Группа историй на вкладке «Истории» — по одной на занятие.
 *
 * Тексты в группах те же самые, разное — что с ними делают. Поэтому группа, а
 * не отдельный список историй: перевод «На рынке» и чтение «На рынке» — одна
 * история и два разных прогресса.
 */
data class StoryGroup(
    val mode: StoryMode,
    val cards: List<StoryCard>
) {
    val title: String get() = mode.title
    val done: Int get() = cards.count { it.finished }
}

/** Экран истории: где мы в тексте и сколько раз подряд не вышло. */
data class StoryState(
    val id: String,
    val title: String,
    val chunks: List<StoryChunk>,
    val mode: StoryMode = StoryMode.Read,
    val index: Int = 0,
    val attempts: Int = 0,
    /** Что расслышал движок на последней попытке — без этого непонятно, что не так. */
    val heard: String = "",
    /** Разбор последней попытки: «Совпало слов: 5 из 8» или замечание от модели. */
    val note: String = "",
    /**
     * Показан ли черногорский текст отрезка там, где он изначально закрыт.
     *
     * Появляется после нескольких неудач при переводе и на слух: дальше задача
     * уже не «переведи» и не «расслышь», а «произнеси», и проверяется она без
     * сети, как обычное чтение.
     */
    val revealed: Boolean = false,
    /** Ответ ушёл к модели и мы ждём вердикт. */
    val checking: Boolean = false,
    /** Как зовут собеседника в диалоге. У обычной истории пусто. */
    val speaker: String = "",
    val glossaryMe: Map<String, String> = emptyMap()
) {
    val current: StoryChunk? get() = chunks.getOrNull(index)

    /** Текст, который в этом режиме надо произнести прямо сейчас. */
    val target: String get() = current?.sr.orEmpty()

    /** Диалог, а не сплошной текст: у отрезков размечены роли. */
    val dialog: Boolean get() = chunks.any { it.who.isNotBlank() }

    /**
     * Сейчас говорит собеседник, и отвечать не надо — надо понять.
     *
     * Только при переводе вслух. При чтении реплики собеседника читают наравне
     * со своими (это репетиция диалога целиком), на слух — повторяют за
     * голосом; разводить их там не на что. А вот перевод — единственный режим,
     * где роль меняет саму задачу: свою реплику надо сказать по-черногорски,
     * а чужую просто разобрать на слух.
     */
    val theirTurn: Boolean get() = mode == StoryMode.Translate && current?.theirs == true
}

/** Что показывает экран задания прямо сейчас. */
sealed interface Phase {
    data object Input : Phase
    data object Checking : Phase
    data class Result(
        val correct: Boolean,
        val feedback: String,
        val better: String,
        val expected: String,
        /** Ответ, на который вынесен вердикт — разбирая ошибку, надо видеть, что именно ты написал. */
        val answer: String
    ) : Phase
    data class Blocked(val message: String) : Phase

    /** Задание пропущено осознанно — не ошибка, показываем только правильный ответ. */
    data class Skipped(val expected: String) : Phase

    /**
     * Произнесённое не совпало, но попытки ещё есть.
     *
     * Отдельная фаза, а не сразу [Result]: движок распознавания ошибается сам
     * по себе — теряет предлоги, слышит соседнее слово, — и записывать это в
     * незнание нечестно. В историях отрезок повторяют, пока не выйдет, и в
     * уроке должно быть так же. Карточка SRS до последней попытки не трогается
     * вовсе: вердикт по заданию выносится один раз.
     */
    data class Retry(
        val attempts: Int,
        /** Что расслышал движок — иначе непонятно, что исправлять в следующий раз. */
        val heard: String,
        /** Сколько попыток осталось. Считает модель: константа у неё. */
        val left: Int
    ) : Phase
}

data class SessionState(
    val title: String,
    val note: String = "",
    val items: List<SessionItem>,
    val index: Int = 0,
    val phase: Phase = Phase.Input,
    val correct: Int = 0,
    val isReview: Boolean = false,
    /**
     * Тренировка вне расписания.
     *
     * Верный ответ в ней только прибавляется к счёту и не двигает интервал:
     * прогнать карточку десять раз за минуту не значит выучить слово. Ошибка
     * же считается полноценной — см. `Scheduler.practice`.
     */
    val practice: Boolean = false,
    /**
     * Собрано ежедневным заданием.
     *
     * Отличается от повторения тем, что может вводить новые уроки порциями, —
     * а значит на этой сессии урок может закончиться, и запись о нём надо
     * сделать (см. [AppViewModel.finish]).
     */
    val daily: Boolean = false,
    /**
     * Когда показали текущее задание. По разнице со временем вердикта
     * замеряется темп — из него считается, сколько заданий влезает в
     * пятнадцать минут (`Pace`).
     */
    val shownAt: Long = 0L,
    val finished: Boolean = false,
    /** На текущее задание уже пожаловались — второй раз не предлагаем. */
    val complaintFiled: Boolean = false,
    /** Словарь подсказок по нажатию на слово; пустой — значит подсказок нет. */
    val glossary: Glossary = Glossary()
) {
    val current: Exercise get() = items[index].exercise
    val progress: Float get() = if (items.isEmpty()) 0f else index.toFloat() / items.size
}

/**
 * Сколько карточек берём в одну сессию повторения.
 *
 * Число из головы, но не с потолка: при 11 заданиях в уроке два с небольшим
 * урока — это подход минут на пятнадцать. Остальное подождёт до следующего раза,
 * карточки никуда не денутся.
 */
// Значения приходят из config/tuning.json (см. data/Tuning.kt): имена
// и места использования те же, менять их теперь можно без пересборки.
private val REVIEW_LIMIT: Int get() = Config.current.srs.reviewLimit

/**
 * Потолок словарной сессии и дневная норма новых слов.
 *
 * Десять слов в день — то, на чём сходится практика (для начинающих 5–15), и
 * жёсткое правило при этом одно: сперва доделать сегодняшние повторения, потом
 * добавлять новое. Отсюда порядок сборки сессии: просроченное, потом новые
 * слова, потом открывшиеся карточки — и всё это под общим потолком.
 */
private val VOCAB_LIMIT: Int get() = Config.current.vocab.sessionLimit
private val NEW_WORDS_PER_DAY: Int get() = Config.current.vocab.newPerDay

/**
 * Со скольких удачных повторений карточка считается усвоенной.
 *
 * По этому порогу открываются следующие: склонение — когда усвоено значение,
 * особая форма — когда усвоено склонение. Склонять слово, значения которого не
 * знаешь, упражнение ни о чём, а нагрузку такой порядок растягивает вдвое без
 * потери смысла.
 */
private val LEARNED_REPS: Int get() = Config.current.vocab.stepReps

/**
 * По скольку заданий вводить новый урок в ежедневном задании.
 *
 * Урок целиком — это одиннадцать заданий, то есть почти всё занятие, и это
 * блок: один материал подряд. Интерливинг (перемешивание похожих тем) на
 * отложенной проверке выигрывает у блоков, хотя во время самой тренировки
 * ощущается хуже — беглость ниже, ошибок больше. Порция в четыре задания
 * растягивает урок на два-три дня и оставляет место повторению и словам.
 */
private val LESSON_PORTION: Int get() = Config.current.daily.lessonPortion

/**
 * Как делится дневной бюджет между источниками.
 *
 * Доли, а не строгий приоритет. Строгий приоритет («сперва весь долг, потом
 * новое») правилен для одного словаря, но здесь источников четыре, и при
 * накопившемся долге повторение съедало бы день целиком месяцами: курс не
 * двигался бы вовсе. Доли гарантируют, что каждый день сдвигается всё
 * понемногу.
 *
 * Неизрасходованная доля не пропадает: она переливается следующему источнику
 * вторым проходом. Поэтому в день, когда повторять нечего, занятие всё равно
 * набирается полным.
 */
/**
 * Сколько просроченных карточек рассматривать при сборке.
 *
 * Не потолок занятия — потолок задаёт время. Это ограничение на выборку из
 * базы: в пятнадцать минут больше полусотни заданий не поместится никогда,
 * а разбирать ради этого весь накопившийся долг незачем.
 */
private val DAILY_POOL: Int get() = Config.current.daily.pool

private val STORY_SHARE: Double get() = Config.current.daily.storyShare
private val LESSON_SHARE: Double get() = Config.current.daily.lessonShare
private val REVIEW_SHARE: Double get() = Config.current.daily.reviewShare
private val WORD_SHARE: Double get() = Config.current.daily.wordShare

/**
 * После скольких неудач подряд показываем черногорский текст отрезка.
 *
 * И при переводе, и на слух: вспомнить или расслышать с четвёртого раза уже не
 * выйдет, а упереться в отрезок насовсем — верный способ бросить историю.
 * Показанный текст превращает отрезок в чтение вслух: произнести-то его всё
 * равно надо.
 */
private val ATTEMPTS_BEFORE_REVEAL: Int get() = Config.current.story.attemptsBeforeReveal

/**
 * Сколько заходов даётся на задание, где отвечают голосом.
 *
 * Три — столько же, сколько в историях. Одного было мало: движок распознавания
 * теряет предлоги и слышит соседнее слово сам по себе, и с одной попытки
 * задание проваливалось не по знанию, а по везению. Больше трёх не нужно: если
 * не вышло трижды, дело уже не в движке.
 */
private val SPOKEN_ATTEMPTS: Int get() = Config.current.daily.spokenAttempts

/**
 * Сколько дней показывает график в отчёте.
 *
 * Тридцать — просьба владельца, и число разумное само по себе: месяц виден
 * целиком, а недельный ритм (выходные-будни) на нём уже читается.
 */
private const val STATS_DAYS = 30

/**
 * Секунды в минуты, округляя к ближайшей.
 *
 * Не в UI, потому что округление тут — часть того, что мы утверждаем:
 * «12 минут» из 11 минут 40 секунд честнее, чем «11». Отсекать хвост значило бы
 * систематически занижать каждую цифру отчёта.
 */
fun minutesOf(seconds: Int): Int = (seconds + 30) / 60

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LessonRepository(app)
    private val dao = AppDb.get(app).dao()
    private val checker = HaikuChecker()
    private val complaints = ComplaintStore(app)
    private val issues = GithubIssues()
    private val progress = ProgressStore(app, dao)
    private val cache = VerdictCache(app)
    private val vocabRepo = VocabRepository(app)
    private val pace = Pace(app)

    /** Последний отправленный ответ — попадает в жалобу как есть. */
    private var lastAnswer: String = ""

    /**
     * Идёт ли проверка прямо сейчас.
     *
     * Раньше от повторной отправки защищала [Phase.Checking], которую ставили
     * сразу: экран с ней ввода не показывает. Теперь между отправкой и этой
     * фазой есть заглядывание в кэш, и на попадании фазы не будет вовсе —
     * значит нужен собственный засов, иначе один ответ можно отправить дважды.
     */
    private var checking = false

    /**
     * Состояние карточки до последней проверки: `exerciseId` и то, чем она была
     * (или null, если карточки ещё не существовало). Нужно, чтобы жалоба могла
     * откатить запись — см. [complain].
     */
    private var cardBeforeAnswer: Pair<String, CardEntity?>? = null

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    /**
     * Вкладка и развёрнутые разделы живут не в HomeState, а рядом.
     *
     * HomeState пересобирается целиком после каждого урока, и то, что человек
     * открыл руками, схлопывалось бы у него на глазах при каждом возвращении.
     */
    private var tab: HomeTab = HomeTab.Today
    private var expanded: Set<String> = emptySet()

    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session.asStateFlow()

    private val _settings = MutableStateFlow<SettingsState?>(null)
    val settings: StateFlow<SettingsState?> = _settings.asStateFlow()

    private val _stats = MutableStateFlow<StatsState?>(null)
    val stats: StateFlow<StatsState?> = _stats.asStateFlow()

    private val _splash = MutableStateFlow<SplashState?>(null)
    val splash: StateFlow<SplashState?> = _splash.asStateFlow()

    private val _story = MutableStateFlow<StoryState?>(null)
    val story: StateFlow<StoryState?> = _story.asStateFlow()

    /**
     * Когда показали текущий отрезок истории.
     *
     * Отдельно от [SessionState.shownAt]: у историй свой экран и своё
     * состояние. Отсчёт сбрасывается на каждой попытке, а не только на удачной,
     * — неудачная попытка это тоже потраченная минута.
     */
    private var storyClock: Long = 0L

    /** Короткое подтверждение поверх любого экрана — показывается и гасится в MainActivity. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        refreshHome()
        // Свежие настройки курса: пришли — применились сразу, не пришли —
        // работаем на вчерашних, и это не повод шуметь.
        viewModelScope.launch { Config.refresh(app) }
        // Неотправленное с прошлого раза: сети могло не быть, когда жаловались.
        sendComplaints()
    }

    fun refreshHome() {
        viewModelScope.launch {
            try {
                val refs = repo.index().lessons
                val byId = dao.lessonProgress().associateBy { it.lessonId }
                val cards = refs.map { ref ->
                    val p = byId[ref.id]
                    LessonCard(ref, p != null, p?.let { "${it.correct}/${it.total}" })
                }
                // Группируем подряд идущие, а не сортируем: порядок уроков задаёт
                // index.json, и раздел не должен его перетасовывать.
                val groups = buildList<LessonGroup> {
                    cards.forEach { card ->
                        val title = card.ref.section
                        val last = lastOrNull()
                        if (last != null && last.title == title) {
                            set(lastIndex, last.copy(cards = last.cards + card))
                        } else {
                            add(LessonGroup(title, listOf(card)))
                        }
                    }
                }
                val storyDone = dao.storyProgress().associateBy { it.storyId to it.mode }
                val storyRefs = repo.stories().stories
                _home.value = HomeState(
                    tab = tab,
                    expandedGroups = expanded,
                    groups = groups,
                    // Один и тот же список историй в каждом занятии: прогресс
                    // у них разный, а тексты общие.
                    storyGroups = StoryMode.entries.map { mode ->
                        StoryGroup(
                            mode,
                            storyRefs.map { ref ->
                                val p = storyDone[ref.id to mode.key]
                                StoryCard(
                                    ref, mode,
                                    p?.chunksDone ?: 0,
                                    (p?.finishedAt ?: 0L) > 0L
                                )
                            }
                        )
                    },
                    dueCount = dao.dueCount(System.currentTimeMillis(), VocabRepository.LESSON_ID),
                    vocab = vocabSummary(),
                    stats = statsBrief(),
                    daily = buildDaily(),
                    loading = false
                )
            } catch (e: Exception) {
                _home.value = HomeState(loading = false, error = e.message ?: "Не удалось прочитать уроки")
            }
        }
    }

    fun selectTab(next: HomeTab) {
        tab = next
        _home.value = _home.value.copy(tab = next)
    }

    fun toggleGroup(title: String) {
        expanded = if (title in expanded) expanded - title else expanded + title
        _home.value = _home.value.copy(expandedGroups = expanded)
    }

    // --- Ежедневное задание ---

    /**
     * Кандидат в занятие: задание и во сколько секунд оно оценено.
     *
     * Оценка нужна до того, как задание показано: набор режется по времени,
     * а не по числу упражнений.
     */
    private class Cand(val item: SessionItem, val seconds: Double)

    /**
     * Ежедневное задание: что делать сегодня, если открыть и не выбирать.
     *
     * Ничего не записывает: плашка на главном пересобирается после каждого
     * занятия, и если бы сборка отмечала взятые слова, дневная норма выгорала
     * бы от одного разглядывания экрана. Отмечает [startDaily], по готовому
     * набору.
     *
     * [extra] — «ещё столько же»: бюджет считается от нуля, а не от остатка.
     * Запрещать заниматься сверх нормы незачем, но и молча продолжать после
     * пятнадцати минут неправильно — это уже другое решение, и принимает его
     * человек.
     */
    private suspend fun buildDaily(extra: Boolean = false): DailyPlan {
        val minutes = pace.minutes
        val spent = (pace.spentToday() + 30) / 60
        val plan = DailyPlan(minutes = minutes, spent = spent, left = pace.leftToday())
        val budget = (if (extra) minutes * 60 else pace.leftToday()).toDouble()
        if (budget <= 0.0) return plan

        val now = System.currentTimeMillis()
        // Без сети свободные переводы не проверить. Не блокируем всё занятие,
        // как это делает урок, а просто не берём их: ежедневное задание должно
        // собираться в метро.
        val online = isOnline()
        fun ok(ex: Exercise) = online || !ex.needsModelCheck

        // --- кандидаты ---
        val portion = nextPortion()
        val lessonCands = portion?.second.orEmpty().filter(::ok).map {
            Cand(SessionItem(portion!!.first.id, it), pace.seconds(it.typeName))
        }

        val due = dao.dueCards(now, DAILY_POOL, VocabRepository.LESSON_ID)
        val known = repo.exercisesIn(due.map { it.lessonId })
        val reviewCands = spread(
            due.mapNotNull { card ->
                known[card.exerciseId]
                    ?.takeIf { ok(it.second) }
                    ?.let { (lesson, ex) ->
                        Cand(SessionItem(lesson, ex), pace.seconds(ex.typeName))
                    }
            }
        ) { it.item.lessonId }

        val wordCands = dailyWords(now)

        // --- дележ бюджета ---
        //
        // Первый проход — каждому источнику своя доля, второй — остаток
        // переливается тем, у кого материал ещё есть. Порядок второго прохода
        // не тот же, что первого: лишнее время лучше отдать долгу и словам,
        // чем вывалить сверх нормы ещё кусок нового урока.
        val storyBudget = budget * STORY_SHARE
        val body = budget - storyBudget
        val lists = listOf(lessonCands, reviewCands, wordCands)
        val caps = listOf(body * LESSON_SHARE, body * REVIEW_SHARE, body * WORD_SHARE)
        val cursor = IntArray(3)
        val picked = List(3) { mutableListOf<Cand>() }
        var used = 0.0

        fun drain(i: Int, cap: Double, count: Int = Int.MAX_VALUE) {
            var own = picked[i].sumOf { it.seconds }
            while (cursor[i] < lists[i].size && picked[i].size < count) {
                val cand = lists[i][cursor[i]]
                if (own + cand.seconds > cap || used + cand.seconds > body) break
                cursor[i]++
                own += cand.seconds
                used += cand.seconds
                picked[i] += cand
            }
        }

        // Первый проход: каждому своя доля, урок — ещё и порцией по числу
        // заданий, иначе доля времени пустила бы в занятие сразу весь урок.
        drain(0, caps[0], LESSON_PORTION)
        drain(1, caps[1])
        drain(2, caps[2])

        // Второй проход: остаток тем, у кого материал ещё есть. Порядок другой
        // — лишнее время лучше отдать долгу и словам, чем вывалить сверх нормы
        // ещё кусок нового урока.
        //
        // Ограничение порции тут снимается, и это не оплошность. В начале курса
        // повторять нечего, а дневная норма слов — десять; без этого прохода
        // первое занятие вышло бы трёхминутным при заказанных пятнадцати. Когда
        // повторению и словам есть чем занять время, до урока остаток не
        // доходит, и порция работает как задумано.
        drain(1, body)
        drain(2, body)
        drain(0, body)

        // --- порядок показа ---
        //
        // Новый материал идёт первым и подряд: задания урока опираются друг на
        // друга — сперва объясняют правило, потом его спрашивают, — и
        // перемешивать их между собой значит спрашивать раньше объяснения.
        // Всё остальное чередуется по кругу: повторение, слово, повторение,
        // слово. Это и есть интерливинг, ради которого затевалось.
        val items = picked[0].map { it.item }.toMutableList()
        var a = 0
        var b = 0
        while (a < picked[1].size || b < picked[2].size) {
            if (a < picked[1].size) items += picked[1][a++].item
            if (b < picked[2].size) items += picked[2][b++].item
        }

        // Истории — хвостом, отдельным шагом. Если упражнений не набралось
        // вовсе, весь бюджет уходит им: занятие всё равно должно состояться.
        val forStory = if (items.isEmpty()) budget else storyBudget
        val story = nextStory(forStory, online)

        return plan.copy(
            items = items,
            review = picked[1].size,
            words = picked[2].size,
            lesson = picked[0].size,
            lessonTitle = portion?.first?.title.orEmpty(),
            story = story,
            // Только упражнения: у истории свой шаг и свой счёт отрезков.
            estimate = if (items.isEmpty()) 0 else (used / 60 + 0.5).toInt().coerceAtLeast(1)
        )
    }

    /**
     * Следующая порция нового урока — первые несколько заданий первого урока,
     * который ещё не заведён целиком.
     *
     * Пройденные насквозь уроки отсеиваются по записи в `lesson_progress` — без
     * неё пришлось бы спрашивать карточки каждого урока курса при каждой
     * пересборке главного экрана.
     */
    private suspend fun nextPortion(): Pair<LessonRef, List<Exercise>>? {
        val finished = dao.lessonProgress().mapTo(mutableSetOf()) { it.lessonId }
        for (ref in repo.index().lessons) {
            if (ref.id in finished) continue
            val have = dao.cardsIn(ref.id).mapTo(mutableSetOf()) { it.exerciseId }
            val fresh = repo.lesson(ref.id).exercises.filter { it.id !in have }
            // Отдаём урок целиком, а не порцией: порцию отрежет дележ бюджета.
            // Здесь она была бы потолком, из-за которого в пустой день занятие
            // не набралось бы.
            if (fresh.isNotEmpty()) return ref to fresh
        }
        return null
    }

    /**
     * Словарные кандидаты: сперва просроченное, потом открывшееся и новое.
     *
     * Оба направления вперемешку и в одной очереди: назвать слово и узнать
     * слово — разное знание, но время у них общее, и делить его пополам
     * искусственно незачем.
     */
    private suspend fun dailyWords(now: Long): List<Cand> {
        val file = vocabRepo.load()
        if (file.words.isEmpty()) return emptyList()
        val all = dao.vocabCards(VocabRepository.LESSON_ID)
        val byId = all.associateBy { it.exerciseId }
        val words = file.words.associateBy { it.id }
        val items = mutableListOf<SessionItem>()

        all.filter { it.dueAt <= now }.sortedBy { it.dueAt }.forEach { card ->
            vocabExercise(file, words, card.exerciseId, card.repetitions)?.let {
                items += SessionItem(VocabRepository.LESSON_ID, it)
            }
        }
        addFreshVocab(file, byId, items, back = false)
        addFreshVocab(file, byId, items, back = true)

        // Экраны пар — первыми кандидатами: дележ бюджета берёт список по
        // порядку, и знакомство должно попадать в занятие раньше, чем набор
        // тех же слов.
        val matches = matchScreens(words, byId, items, all)
            .map { SessionItem(VocabRepository.LESSON_ID, it) }
        return (matches + items).map { Cand(it, pace.seconds(it.exercise.typeName)) }
    }

    /**
     * Какую историю читать сегодня и сколько отрезков.
     *
     * Начатое вперёд недочитанного: возвращаться к брошенной истории через
     * неделю значит перечитывать её с начала. Перевод вслух без сети не
     * проверить, поэтому офлайн это занятие не предлагается.
     */
    private suspend fun nextStory(seconds: Double, online: Boolean): StoryStep? {
        val chunks = (seconds / pace.seconds("story")).toInt()
        if (chunks < 1) return null
        val refs = repo.stories().stories
        if (refs.isEmpty()) return null
        val done = dao.storyProgress().associateBy { it.storyId to it.mode }

        // Нетронутые вперёд начатых, а не наоборот, — и это перевёрнутое
        // правило. Ежедневное задание открывает историю **с первой фразы**
        // (см. openStory), поэтому предлагать начатую значит предлагать
        // перечитать то же начало; а нетронутая — это каждый день новый текст.
        // Дочитывают истории до конца с вкладки «Истории», там продолжение с
        // места работает по-прежнему.
        var fresh: StoryStep? = null
        var started: StoryStep? = null
        for (ref in refs) {
            for (mode in StoryMode.entries) {
                if (mode == StoryMode.Translate && !online) continue
                val p = done[ref.id to mode.key]
                val left = ref.chunks - (p?.chunksDone ?: 0)
                if (left <= 0) continue
                val step = StoryStep(ref.id, ref.title, mode, minOf(chunks, left), left)
                if (p == null && fresh == null) fresh = step
                if (p != null && started == null) started = step
            }
        }
        return fresh ?: started
    }

    /** Запуск ежедневного задания. [extra] — ещё один заход сверх нормы. */
    fun startDaily(extra: Boolean = false) {
        viewModelScope.launch {
            val plan = buildDaily(extra = extra)
            if (plan.items.isEmpty()) {
                _home.value = _home.value.copy(daily = plan)
                return@launch
            }
            // Норма новых слов отмечается тем, что реально попало в занятие,
            // а не тем, что попало в кандидаты: остальное обрезал бюджет.
            val cards = dao.vocabCards(VocabRepository.LESSON_ID).associateBy { it.exerciseId }
            vocabRepo.noteIntroduced(freshWords(plan.items, cards))

            _session.value = SessionState(
                title = "Сегодня",
                note = dailyNote(plan),
                items = plan.items,
                // Как повторение: записи о прохождении урока делает не конец
                // сессии, а закрытая порция — см. finish.
                isReview = true,
                daily = true,
                shownAt = System.currentTimeMillis(),
                glossary = repo.glossary()
            )
            guardNetwork(plan.items)
        }
    }

    private fun dailyNote(plan: DailyPlan): String = buildList {
        if (plan.lesson > 0) add("новое из «${plan.lessonTitle}» — ${plan.lesson}")
        if (plan.review > 0) add("повторение — ${plan.review}")
        if (plan.words > 0) add("слова — ${plan.words}")
    }.joinToString(", ").replaceFirstChar { it.uppercase() }

    /** Настройка длины занятия. */
    fun setDailyMinutes(value: Int) {
        pace.minutes = value
        refreshHome()
        _settings.value = _settings.value?.copy(dailyMinutes = pace.minutes)
    }

    fun startLesson(lessonId: String) {
        viewModelScope.launch {
            val lesson = repo.lesson(lessonId)
            val items = lesson.exercises.map { SessionItem(lessonId, it) }
            _session.value = SessionState(
                title = lesson.title,
                note = lesson.note,
                items = items,
                shownAt = System.currentTimeMillis(),
                glossary = repo.glossary()
            )
            guardNetwork(items)
        }
    }

    private suspend fun vocabSummary(): VocabTracks {
        val file = vocabRepo.load()
        if (file.words.isEmpty()) return VocabTracks()
        val now = System.currentTimeMillis()
        val cards = dao.vocabCards(VocabRepository.LESSON_ID)
        val words = file.words.size

        fun track(back: Boolean, fresh: Int): VocabTrack {
            val mine = cards.filter { isBackCard(it.exerciseId) == back }
            return VocabTrack(
                due = mine.count { it.dueAt <= now },
                fresh = fresh,
                // Тренировать можно всё, что заведено и ещё не выучено, —
                // расписание тут не указ, на то она и тренировка.
                ready = mine.count { it.correct < VocabRepository.LEARNED },
                learned = mine.count { it.correct >= VocabRepository.LEARNED },
                started = if (back) mine.size else mine.count { isMeaning(it.exerciseId) },
                total = words
            )
        }

        val started = cards.count { isMeaning(it.exerciseId) }
        val budget = (NEW_WORDS_PER_DAY - vocabRepo.introducedToday()).coerceAtLeast(0)
        return VocabTracks(
            toTarget = track(back = false, fresh = minOf(budget, words - started)),
            // Обратный перевод своей дневной нормы не имеет: слово в него
            // попадает не «новым», а уже заведённым — назвать его надо было
            // раньше, чем узнать.
            toNative = track(
                back = true,
                fresh = started - cards.count { isBackCard(it.exerciseId) }
            )
        )
    }

    /**
     * Прибавить сегодняшнему дню.
     *
     * Все слагаемые перечислены явно, хотя у запроса есть значения по
     * умолчанию: подставлять их через синтетический мост Kotlin в
     * сгенерированный Room код — лишний риск ради экономии строк.
     */
    private suspend fun bump(
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
    ) {
        dao.bumpDay(
            day = LocalDate.now().toString(),
            lessonSeconds = lessonSeconds,
            reviewSeconds = reviewSeconds,
            wordSeconds = wordSeconds,
            storySeconds = storySeconds,
            answers = answers,
            correct = correct,
            lessons = lessons,
            sessions = sessions,
            chunks = chunks,
            words = words
        )
    }

    /**
     * Время одного задания — в ту корзину, к которой оно относится.
     *
     * Вид занятия определяется не по сессии, а по самому заданию, и это
     * важнее, чем кажется: ежедневное задание перемешивает урок, повторение и
     * слова в одном заходе, и «время сессии» не сказало бы ничего. Словарь
     * виден по `lessonId`, а новый материал от повторения отличает наличие
     * карточки: её у задания ещё не было — значит его видят впервые.
     *
     * [correct] в `null` означает пропуск: время потрачено, ответа не было.
     */
    private suspend fun noteAnswer(
        item: SessionItem,
        existing: CardEntity?,
        seconds: Int,
        correct: Boolean?
    ) {
        val vocab = item.lessonId == VocabRepository.LESSON_ID
        val fresh = existing == null
        bump(
            lessonSeconds = if (!vocab && fresh) seconds else 0,
            reviewSeconds = if (!vocab && !fresh) seconds else 0,
            wordSeconds = if (vocab) seconds else 0,
            answers = if (correct == null) 0 else 1,
            correct = if (correct == true) 1 else 0
        )
    }

    /** Строка под заголовком главного экрана. */
    private suspend fun statsBrief(): StatsBrief {
        val rows = dao.days()
        val today = rows.firstOrNull { it.day == LocalDate.now().toString() }
        return StatsBrief(
            todayMinutes = minutesOf(today?.seconds ?: 0),
            todayAnswers = today?.answers ?: 0,
            totalMinutes = minutesOf(rows.sumOf { it.seconds }),
            totalSessions = rows.sumOf { it.sessions },
            streak = streakNow(rows)
        )
    }

    /**
     * Серия: сколько дней подряд занимались, считая назад от сегодня.
     *
     * **Сегодняшний пропуск серию ещё не рвёт.** Если последним днём занятий
     * было вчера, счёт продолжается: день не кончился, и объявлять серию
     * прерванной в полдень значило бы врать. Порвётся она сама, когда вчера
     * станет позавчера.
     *
     * Днём занятий считается любой день, где хоть что-то было (см.
     * [DayStatEntity.active]), а не только день с минутами: у восстановленных
     * задним числом уроков времени нет вовсе.
     */
    private fun streakNow(rows: List<DayStatEntity>): Int {
        val active = rows.filter { it.active }.mapTo(mutableSetOf()) { it.day }
        if (active.isEmpty()) return 0
        val today = LocalDate.now()
        var cursor = if (today.toString() in active) today else today.minusDays(1)
        var count = 0
        while (cursor.toString() in active) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /**
     * Самая длинная серия за всё время.
     *
     * Считается по всей истории, а не по тридцати дням графика: рекорд на то и
     * рекорд, чтобы не исчезать, когда уезжает окно.
     */
    private fun bestStreak(rows: List<DayStatEntity>): Int {
        val active = rows.filter { it.active }.map { LocalDate.parse(it.day) }.sorted()
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (day in active) {
            run = if (prev != null && prev.plusDays(1) == day) run + 1 else 1
            if (run > best) best = run
            prev = day
        }
        return best
    }

    /**
     * Отчёт целиком.
     *
     * Собирается по нажатию, а не держится наготове: тут и все карточки, и все
     * файлы уроков, и словарь — на главном экране это считалось бы после
     * каждого задания впустую.
     */
    fun openStats() {
        _stats.value = StatsState(loading = true)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val rows = dao.days().associateBy { it.day }
            val start = LocalDate.now()
            val days = (STATS_DAYS - 1 downTo 0).map { back ->
                val key = start.minusDays(back.toLong()).toString()
                rows[key] ?: DayStatEntity(day = key)
            }
            val all = rows.values
            val lessons = repo.index().lessons
            val cards = dao.allCards()
            val vocab = cards.filter { it.lessonId == VocabRepository.LESSON_ID }
            val meanings = vocab.filter { isMeaning(it.exerciseId) }

            _stats.value = StatsState(
                today = rows[start.toString()] ?: DayStatEntity(day = start.toString()),
                total = DayStatEntity(
                    day = "",
                    lessonSeconds = all.sumOf { it.lessonSeconds },
                    reviewSeconds = all.sumOf { it.reviewSeconds },
                    wordSeconds = all.sumOf { it.wordSeconds },
                    storySeconds = all.sumOf { it.storySeconds },
                    answers = all.sumOf { it.answers },
                    correct = all.sumOf { it.correct },
                    lessons = all.sumOf { it.lessons },
                    sessions = all.sumOf { it.sessions },
                    chunks = all.sumOf { it.chunks },
                    words = all.sumOf { it.words }
                ),
                days = days,
                lessonsDone = dao.lessonProgress().size,
                lessonsTotal = lessons.size,
                // Заведённая карточка и значит «задание проходили»: до первого
                // ответа её не существует.
                exercisesDone = cards.count { it.lessonId != VocabRepository.LESSON_ID },
                exercisesTotal = lessons.sumOf { repo.lesson(it.id).exercises.size },
                wordsIntroduced = meanings.size,
                wordsLearned = meanings.count { it.correct >= VocabRepository.LEARNED },
                wordsTotal = vocabRepo.load().words.size,
                dueLessons = dao.dueCount(now, VocabRepository.LESSON_ID),
                dueWords = dao.vocabDue(now, VocabRepository.LESSON_ID),
                activeDays = days.count { it.active },
                streak = streakNow(rows.values.toList()),
                bestStreak = bestStreak(rows.values.toList()),
                loading = false
            )
        }
    }

    /**
     * Забрать настройки курса из репозитория по просьбе.
     *
     * Само приложение делает это при запуске молча; кнопка нужна, чтобы
     * увидеть правку сразу, не перезапуская, — и чтобы понять, дошла ли она
     * вообще.
     */
    fun refreshTuning() {
        viewModelScope.launch {
            val ok = Config.refresh(getApplication())
            _settings.value = _settings.value?.copy(tuningFetched = Config.lastFetch)
            _notice.value = if (ok) "Настройки курса обновлены." else Config.lastFetch
            refreshHome()
        }
    }

    fun closeStats() {
        _stats.value = null
    }

    /**
     * Закрыть заставку — и вместе с ней занятие.
     *
     * Обычного экрана «готово» у ежедневного задания больше нет: заставка его и
     * заменила, а два подряд экрана с одним и тем же смыслом читались бы как
     * недоделка.
     */
    fun closeSplash() {
        _splash.value = null
        // Заставку можно открыть и из настроек, руками: тогда закрывать нечего.
        if (_session.value != null) exitSession()
    }

    /** Показать заставку не занимаясь — кнопка внизу настроек. */
    fun previewSplash() {
        viewModelScope.launch { _splash.value = buildSplash(null) }
    }

    /**
     * Что показать на заставке.
     *
     * Считается **после** того, как занятие записано в день: иначе минуты и
     * серия отставали бы ровно на это занятие — то самое, за которое хвалим.
     */
    private suspend fun buildSplash(state: SessionState?): SplashState {
        val rows = dao.days()
        val today = rows.firstOrNull { it.day == LocalDate.now().toString() }
        // Без занятия (показ из настроек) считаем по всему дню: это честные
        // числа, а не выдуманные ради красивого снимка.
        val answers = state?.items?.size ?: today?.answers ?: 0
        val right = state?.correct ?: today?.correct ?: 0
        val accuracy = if (answers > 0) right * 100 / answers else 0
        val streak = streakNow(rows)
        val vocab = dao.vocabCards(VocabRepository.LESSON_ID)
        val learned = vocab.count {
            isMeaning(it.exerciseId) && it.correct >= VocabRepository.LEARNED
        }
        val lessons = dao.lessonProgress().size
        val (phrase, gloss) = splashPhrase(
            streak = streak,
            accuracy = accuracy,
            answers = answers,
            firstDay = rows.count { it.active } <= 1
        )
        return SplashState(
            phrase = phrase,
            gloss = gloss,
            streak = streak,
            minutes = minutesOf(today?.seconds ?: 0),
            answers = answers,
            accuracy = accuracy,
            footer = buildList {
                val fresh = today?.words ?: 0
                if (fresh > 0) add("+$fresh новых слов")
                if (learned > 0) add("$learned выучено")
                add("курс: $lessons из ${repo.index().lessons.size}")
            }.joinToString("  ·  ")
        )
    }

    /**
     * Фраза дня: черногорская, с переводом.
     *
     * Не одна на все случаи, и это главное в ней. Одна и та же надпись на
     * седьмой раз перестаёт читаться вовсе, а так заставка каждый раз говорит
     * что-то про сегодняшний день. Заодно и учит: экран-награда — единственное
     * место, где фразу читают без всякого задания.
     *
     * Порядок проверок — от редкого к обычному. Занятие без единой ошибки реже
     * длинной серии, поэтому идёт первым; ошибка на последнем задании не должна
     * отменять неделю подряд, поэтому серия идёт раньше «просто дня».
     */
    private fun splashPhrase(
        streak: Int,
        accuracy: Int,
        answers: Int,
        firstDay: Boolean
    ): Pair<String, String> = when {
        // Пять заданий — порог, ниже которого «без ошибок» ничего не значит.
        answers >= 5 && accuracy == 100 -> "Bez greške!" to "без единой ошибки"
        streak >= 7 -> "Nema predaje!" to "сдаваться не будем"
        streak >= 3 -> "Dan po dan!" to "день за днём"
        firstDay -> "Počelo je!" to "началось"
        else -> "Samo naprijed!" to "только вперёд"
    }

    /**
     * Раскладывает список по кругу, чтобы подряд не шли задания одного урока.
     *
     * Порядок внутри урока сохраняется — самое старое остаётся первым, — но
     * между уроками идёт чередование. Без этого повторение выходило пачками:
     * задания урока заводятся в один день, получают одинаковые интервалы и
     * становятся просроченными тоже разом, а сортировка по дате ставила их
     * подряд. Занятие из-за этого читалось как «опять седьмой урок», что и
     * попало в жалобу.
     */
    private fun <T> spread(items: List<T>, key: (T) -> String): List<T> {
        val queues = LinkedHashMap<String, MutableList<T>>()
        items.forEach { queues.getOrPut(key(it)) { mutableListOf() } += it }
        val out = ArrayList<T>(items.size)
        while (out.size < items.size) {
            queues.values.forEach { queue ->
                if (queue.isNotEmpty()) out += queue.removeAt(0)
            }
        }
        return out
    }

    private fun isMeaning(id: String) = id.endsWith("-" + VocabKind.Meaning.key)

    private fun isBackCard(id: String) = id.endsWith("-" + VocabKind.Recall.key)

    /**
     * Словарная сессия: просроченное, потом новые слова, потом открывшееся.
     *
     * Порядок именно такой и он не косметический. Сперва доделать сегодняшние
     * повторения, потом добавлять новое — иначе словарь превращается в долг,
     * который растёт быстрее, чем отдаётся.
     */
    /**
     * Словарная сессия: просроченное, потом новые слова, потом открывшееся.
     *
     * Порядок именно такой и он не косметический. Сперва доделать сегодняшние
     * повторения, потом добавлять новое — иначе словарь превращается в долг,
     * который растёт быстрее, чем отдаётся.
     *
     * [back] — направление: с русского или на русский. [practice] — прогон вне
     * расписания: берётся всё незаученное, начиная с самого шаткого, новых
     * слов не добавляется и интервалы не двигаются. Тренироваться так можно
     * сколько угодно — в этом и смысл.
     */
    fun startVocab(back: Boolean = false, practice: Boolean = false) {
        viewModelScope.launch {
            val file = vocabRepo.load()
            if (file.words.isEmpty()) {
                _home.value = _home.value.copy(error = "Словарь не загрузился.")
                return@launch
            }
            val now = System.currentTimeMillis()
            val all = dao.vocabCards(VocabRepository.LESSON_ID)
            val cards = all.filter { isBackCard(it.exerciseId) == back }
            val byId = all.associateBy { it.exerciseId }
            val words = file.words.associateBy { it.id }
            val items = mutableListOf<SessionItem>()

            fun add(ex: Exercise?) {
                if (ex != null && items.size < VOCAB_LIMIT) {
                    items += SessionItem(VocabRepository.LESSON_ID, ex)
                }
            }

            if (practice) {
                // Самое шаткое вперёд: сколько раз ответили верно за вычетом
                // двойного веса ошибок. Выученное (десять верных) не берём —
                // тренировать его незачем, оно вернётся по расписанию.
                cards.filter { it.correct < VocabRepository.LEARNED }
                    .sortedBy { it.correct - it.lapses * 2 }
                    .forEach { add(vocabExercise(file, words, it.exerciseId, it.repetitions)) }
            } else {
                // 1. Просроченное, самое старое первым.
                cards.filter { it.dueAt <= now }.sortedBy { it.dueAt }.forEach { card ->
                    add(vocabExercise(file, words, card.exerciseId, card.repetitions))
                }
                addFreshVocab(file, byId, items, back)
            }

            // Пары идут первыми: знакомство раньше проверки. Слово, впервые
            // взятое в этом заходе, встретится сперва на экране пар и лишь
            // потом в задании на набор — до 1.38 первой встречей был сразу
            // набор, то есть требование написать слово, которого человек ещё
            // ни разу не видел.
            items.addAll(
                0,
                matchScreens(words, byId, items, all)
                    .map { SessionItem(VocabRepository.LESSON_ID, it) }
            )

            if (!practice) vocabRepo.noteIntroduced(freshWords(items, byId))

            if (items.isEmpty()) {
                refreshHome()
                _home.value = _home.value.copy(
                    error = if (practice) "Тренировать пока нечего — сперва заведи слова."
                    else "На сегодня всё. Новые слова откроются завтра."
                )
                return@launch
            }

            _session.value = SessionState(
                title = if (back) "Слова: на русский" else "Слова",
                note = if (practice) {
                    "Тренировка вне расписания: счёт идёт, интервалы не двигаются."
                } else {
                    ""
                },
                items = items,
                // Как повторение, а не как урок: словарь не «проходят до конца»,
                // и запись в lesson_progress означала бы, что урок «vocab»
                // пройден — она бы переписывалась каждой сессией и уехала бы
                // в копию прогресса.
                isReview = true,
                practice = practice,
                shownAt = System.currentTimeMillis(),
                glossary = repo.glossary()
            )
        }
    }

    /**
     * Добавляет в сессию то, что открылось: новые слова и следующие ступени.
     *
     * Обратный перевод новых слов не заводит: слово попадает в него уже
     * заведённым. Дневная норма поэтому одна на оба направления — считается
     * она по словам, а не по карточкам.
     */
    private suspend fun addFreshVocab(
        file: VocabFile,
        byId: Map<String, CardEntity>,
        items: MutableList<SessionItem>,
        back: Boolean
    ) {
        fun add(ex: Exercise?) {
            if (ex != null && items.size < VOCAB_LIMIT) {
                items += SessionItem(VocabRepository.LESSON_ID, ex)
            }
        }

        if (back) {
            for (word in file.words) {
                if (items.size >= VOCAB_LIMIT) break
                if (!byId.containsKey(VocabRepository.cardId(word.id, VocabKind.Meaning))) continue
                if (byId.containsKey(VocabRepository.cardId(word.id, VocabKind.Recall))) continue
                add(file.exerciseFor(word, VocabKind.Recall))
            }
            return
        }

        // Следующая ступень у слов, где предыдущая усвоена, — ПЕРЕД новыми
        // словами, а не после. Порядок был обратный, и это ошибка: очередь
        // просроченного со временем упирается в потолок сессии, и падежи не
        // получали бы слота уже никогда. Открыть следующую ступень у слова,
        // которое человек уже знает, и дешевле, и полезнее, чем завести ещё
        // одно незнакомое.
        //
        // Своей дневной нормы у ступеней нет: их темп и так задан тем, как
        // быстро усваивается предыдущая.
        for (word in file.words) {
            if (items.size >= VOCAB_LIMIT) break
            if (!vocabLearned(byId, VocabRepository.cardId(word.id, VocabKind.Meaning))) continue

            val declId = VocabRepository.cardId(word.id, VocabKind.Pattern)
            if (word.forms.isNotEmpty() && !byId.containsKey(declId)) {
                add(file.exerciseFor(word, VocabKind.Pattern))
                continue
            }
            if (!vocabLearned(byId, declId)) continue
            val form = word.odd.firstOrNull {
                !byId.containsKey(VocabRepository.cardId(word.id, VocabKind.Odd, it))
            } ?: continue
            add(file.exerciseFor(word, VocabKind.Odd, form))
        }

        // Новые слова — сколько осталось на сегодня.
        var taken = 0
        val budget = (NEW_WORDS_PER_DAY - vocabRepo.introducedToday()).coerceAtLeast(0)
        for (word in file.words) {
            if (taken >= budget || items.size >= VOCAB_LIMIT) break
            if (byId.containsKey(VocabRepository.cardId(word.id, VocabKind.Meaning))) continue
            val before = items.size
            add(file.exerciseFor(word, VocabKind.Meaning))
            if (items.size > before) taken++
        }
    }

    /**
     * Экраны пар из слов, которые и так попали в набор.
     *
     * Отдельного отбора слов у пар нет, и это главное решение здесь. Экран
     * собирается из того, что сессия уже взяла: сперва новые слова этого
     * захода, потом самые шаткие из его же знакомых. Так пары не заводят
     * ничего сверх дневной нормы (её отмерил [addFreshVocab]) и не тянут в
     * занятие слов, которых в нём иначе не было бы, — экран получается
     * знакомством с тем, что через минуту спросят набором.
     *
     * Добор со стороны ([spare]) нужен на случай, когда своих слов не набралось
     * на экран: тогда берутся самые шаткие из всех незаученных. Экран из двух
     * пар не собираем вовсе — см. `MATCH_MIN`.
     *
     * Два ограничения на состав, и оба обязательные:
     *
     *  * **одна пара на лемму.** Слово стоит в очереди дважды — в обе стороны,
     *    — и обе его карточки дали бы на экране две одинаковые плашки;
     *  * **одинаковых толкований на экране быть не должно.** Две «спины» в
     *    левом столбце не различить ничем, и человек ошибётся не по незнанию,
     *    а потому что задание невозможное. Слово с занятым толкованием просто
     *    не берётся.
     *
     * Годятся только карточки значения и обратного перевода: падеж и особая
     * форма спрашивают не «что это слово значит», и сопоставлять там нечего.
     */
    private fun matchScreens(
        words: Map<String, VocabWord>,
        byId: Map<String, CardEntity>,
        pool: List<SessionItem>,
        spare: List<CardEntity>
    ): List<Exercise.Match> {
        fun pairable(id: String) = isMeaning(id) || isBackCard(id)
        fun shaky(card: CardEntity) = card.correct - card.lapses * 2

        val mine = pool.map { it.exercise.id }.filter(::pairable)
        val fresh = mine.filter { it !in byId }
        val known = mine.filter { it in byId }.sortedBy { shaky(byId.getValue(it)) }
        val extra = spare
            .filter { pairable(it.exerciseId) && it.correct < VocabRepository.LEARNED }
            .sortedBy(::shaky)
            .map { it.exerciseId }

        val cap = VocabRepository.MATCH_PAIRS * VocabRepository.MATCH_SCREENS
        val pairs = mutableListOf<MatchPair>()
        val lemmas = mutableSetOf<String>()
        val glosses = mutableSetOf<String>()
        for (cardId in fresh + known + extra) {
            if (pairs.size >= cap) break
            val lemma = VocabRepository.lemmaOf(cardId) ?: continue
            if (lemma in lemmas) continue
            val word = words[lemma] ?: continue
            val pair = matchPairFor(cardId, word) ?: continue
            if (!glosses.add(pair.ru)) continue
            lemmas += lemma
            pairs += pair
        }

        return pairs.chunked(VocabRepository.MATCH_PAIRS)
            .filter { it.size >= VocabRepository.MATCH_MIN }
            .map { matchExercise(it) }
    }

    /**
     * Сколько новых слов в наборе.
     *
     * Новое — то, у чего карточки значения ещё не было. Считается по готовому
     * набору, а не по ходу сборки: ежедневное задание режет набранное по
     * времени, и отметить норму до обрезки значило бы потратить день на слова,
     * которых человек не увидит.
     *
     * Экран пар считается наравне с набором: он тоже заводит карточку
     * (`Scheduler.metCard`), то есть тоже вводит слово. Не считать его значило
     * бы недосчитаться ровно тех слов, у которых бюджет срезал задание на
     * набор, а знакомство оставил. Отсюда же `distinct`: одно и то же слово
     * стоит и в паре, и заданием, а введено оно один раз.
     */
    private fun freshWords(items: List<SessionItem>, byId: Map<String, CardEntity>): Int =
        items.flatMap { item ->
            (item.exercise as? Exercise.Match)?.pairs?.map { it.cardId }
                ?: listOf(item.exercise.id)
        }.distinct().count { isMeaning(it) && !byId.containsKey(it) }

    private fun vocabLearned(cards: Map<String, CardEntity>, id: String): Boolean =
        (cards[id]?.repetitions ?: 0) >= LEARNED_REPS

    /**
     * Задание по идентификатору карточки.
     *
     * У образца склонения ячейка выбирается по числу повторений: не случайно —
     * иначе одно и то же повторение показывало бы разное при каждой
     * перерисовке экрана.
     */
    private fun vocabExercise(
        file: VocabFile,
        words: Map<String, VocabWord>,
        id: String,
        repetitions: Int
    ): Exercise? {
        val word = words[VocabRepository.lemmaOf(id) ?: return null] ?: return null
        return when {
            id.endsWith("-" + VocabKind.Recall.key) ->
                file.exerciseFor(word, VocabKind.Recall)
            id.endsWith("-" + VocabKind.Meaning.key) ->
                file.exerciseFor(word, VocabKind.Meaning)
            id.endsWith("-" + VocabKind.Pattern.key) ->
                file.exerciseFor(word, VocabKind.Pattern, repetitions = repetitions)
            else -> id.substringAfter("-" + VocabKind.Odd.key + "-", "")
                .takeIf { it.isNotBlank() }
                ?.let { file.exerciseFor(word, VocabKind.Odd, it) }
        }
    }

    fun startReview() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val total = dao.dueCount(now, VocabRepository.LESSON_ID)
            val due = dao.dueCards(now, REVIEW_LIMIT, VocabRepository.LESSON_ID)
            val byId = repo.exercisesIn(due.map { it.lessonId })
            // Вперемешку по урокам: подряд идущие задания одного урока читаются
            // как «опять седьмой урок» — по жалобе владельца.
            val items = spread(
                due.mapNotNull { card ->
                    byId[card.exerciseId]?.let { (lessonId, ex) -> SessionItem(lessonId, ex) }
                }
            ) { it.lessonId }
            if (items.isEmpty()) {
                refreshHome()
                return@launch
            }
            _session.value = SessionState(
                title = "Повторение",
                // Про остаток говорим прямо, иначе счётчик на главном не сходился бы
                // с длиной сессии и выглядел бы поломкой.
                note = if (total > items.size) {
                    "Просрочено карточек: $total. В этот подход взяты $REVIEW_LIMIT самых старых, " +
                        "остальные вернутся в следующий раз."
                } else {
                    ""
                },
                items = items,
                isReview = true,
                shownAt = System.currentTimeMillis(),
                glossary = repo.glossary()
            )
            guardNetwork(items)
        }
    }

    /** Свободные переводы без сети не проверить — предупреждаем на входе. */
    private fun guardNetwork(items: List<SessionItem>) {
        val needsNet = items.any { it.exercise.needsModelCheck }
        if (needsNet && !isOnline()) {
            _session.value = _session.value?.copy(
                phase = Phase.Blocked("Нет интернета. В уроке есть свободные переводы — их проверяет Claude, офлайн они не засчитаются.")
            )
        }
    }

    fun exitSession() {
        _session.value = null
        autoSaveProgress()
        refreshHome()
    }

    // --- Настройки ---

    fun openSettings() {
        viewModelScope.launch {
            val stats = cache.stats()
            _settings.value = SettingsState(
                complaintCount = complaints.count(),
                complaintsLeft = complaints.count(),
                issuesReady = issues.configured,
                filePath = complaints.file().absolutePath,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                progressFolder = progress.folderLabel(),
                progressLocal = progress.localFile().absolutePath,
                progressLastSave = progress.lastSave(),
                progressLocalExists = progress.localFile().exists(),
                dailyMinutes = pace.minutes,
                cacheEnabled = cache.enabled,
                cacheCount = cache.count(),
                cacheHits = stats.hits,
                cacheAsked = stats.asked,
                tuningFetched = Config.lastFetch
            )
        }
    }

    fun closeSettings() {
        _settings.value = null
    }

    fun useVerdictCache(enabled: Boolean) {
        cache.enabled = enabled
        _settings.value = _settings.value?.copy(
            cacheEnabled = enabled,
            notice = if (enabled) {
                "Засчитанные ответы снова запоминаются."
            } else {
                "Каждый ответ теперь проверяется заново. Запомненное осталось на месте."
            }
        )
    }

    fun clearVerdictCache() {
        viewModelScope.launch {
            val had = cache.clear()
            _settings.value = _settings.value?.copy(
                cacheCount = 0,
                cacheHits = 0,
                cacheAsked = 0,
                notice = if (had > 0) {
                    "Забыто ответов: $had. Счёт начат заново."
                } else {
                    "Забывать нечего."
                }
            )
        }
    }

    /**
     * Копия отчёта под именем с ярлыком устройства и меткой UTC — её и отдаёт
     * в share экран настроек. Интент собирается там: для него нужен Context
     * активности, а не приложения.
     */
    /**
     * Отправить накопленные жалобы в issues.
     *
     * Зовётся сама — после каждой жалобы и при запуске приложения, — и руками
     * из настроек. Без токена не делает ничего: жалобы просто копятся в файле,
     * и это не поломка, а работа вхолостую.
     *
     * [loud] отличает ручной запуск от автоматического. Молча уведомлять не о
     * чем: отправка идёт фоном и человека не касается. А на нажатие кнопки
     * ответить нужно всегда, даже если ответ — «отправлять нечего».
     */
    fun sendComplaints(loud: Boolean = false) {
        if (!issues.configured) {
            if (loud) _notice.value = "Токен GitHub не задан — жалобы копятся в файле."
            return
        }
        viewModelScope.launch {
            val device = complaints.deviceTag()
            val result = complaints.flush { complaint, raw ->
                issues.create(complaint, raw, device)
            }
            _settings.value = _settings.value?.copy(
                complaintCount = result.left,
                complaintsLeft = result.left,
                complaintsError = result.error
            )
            val text = when {
                result.sent > 0 && result.left == 0 -> "Отправлено в GitHub: ${result.sent}."
                result.sent > 0 -> "Отправлено ${result.sent}, осталось ${result.left}."
                result.error != null -> "Не отправилось: ${result.error}"
                loud -> "Отправлять нечего."
                else -> null
            }
            if (text != null && (loud || result.sent > 0)) _notice.value = text
        }
    }


    // --- Истории ---

    /**
     * Открывает историю с того места, где её бросили.
     *
     * Дочитанную открываем с начала: возвращаться к ней имеет смысл только чтобы
     * перечитать целиком, а «продолжить с конца» — это пустой экран.
     */
    /**
     * Открыть историю.
     *
     * [fromStart] — с первой фразы, а не с места, где бросили. Так открывает
     * ежедневное задание: по жалобе владельца («любая история в ежедневных
     * заданиях всегда должна начинаться сначала») — история, начатая с середины,
     * непонятна, а трёх отрезков в день не хватает, чтобы помнить прошлые.
     * На вкладке «Истории» продолжение с места осталось: там текст дочитывают.
     *
     * Прогресс от такого перечитывания не теряется — `saveStory` не опускает
     * счётчик, — но и не растёт, поэтому ежедневное задание берёт следующую
     * историю только когда её дочитают руками.
     */
    fun openStory(id: String, mode: StoryMode, fromStart: Boolean = false) {
        viewModelScope.launch {
            // Перевод проверяет модель, и без сети история встала бы на первом
            // же отрезке. Предупреждаем на входе — как с уроками (guardNetwork).
            if (mode == StoryMode.Translate && !isOnline()) {
                _notice.value =
                    "Нет интернета. Перевод вслух проверяет Claude — офлайн он не засчитается."
                return@launch
            }
            // Файл истории может не читаться — не тот путь, битый JSON. Ронять
            // из-за этого приложение нельзя: assets правятся чаще, чем код.
            val story = runCatching { repo.story(id) }.getOrNull()
            if (story == null) {
                _notice.value = "Историю не открыть — файл не читается"
                return@launch
            }
            val done = if (fromStart) 0 else dao.story(id, mode.key)?.chunksDone ?: 0
            storyClock = System.currentTimeMillis()
            _story.value = StoryState(
                id = story.id,
                title = story.title,
                chunks = story.chunks,
                speaker = story.speaker,
                mode = mode,
                index = if (done >= story.chunks.size) 0 else done,
                glossaryMe = runCatching { repo.glossary().me }.getOrDefault(emptyMap())
            )
        }
    }

    fun closeStory() {
        storyClock = 0L
        _story.value = null
        autoSaveProgress()
        refreshHome()
    }

    fun restartStory() {
        _story.value = _story.value?.copy(
            index = 0, attempts = 0, heard = "", note = "",
            revealed = false, checking = false
        )
    }

    /**
     * Разбор того, что человек произнёс.
     *
     * Чтение и повтор на слух проверяются на месте — долей слов, прозвучавших
     * по порядку: дословного совпадения движок не даёт. Перевод проверяет
     * модель: одну мысль выражают по-разному, и сравнение строк тут просто врёт.
     *
     * Показанный после неудач текст снова проверяется на месте, в любом режиме:
     * задача с этого момента не «переведи» и не «расслышь», а «произнеси»,
     * и сеть для неё не нужна.
     */
    fun submitChunk(heard: String) {
        val state = _story.value ?: return
        val chunk = state.chunks.getOrNull(state.index) ?: return
        if (storyClock > 0L) {
            val seconds = pace.spend((System.currentTimeMillis() - storyClock) / 1000.0)
            if (seconds > 0) viewModelScope.launch { bump(storySeconds = seconds) }
        }
        storyClock = System.currentTimeMillis()
        if (state.mode == StoryMode.Translate && !state.revealed) {
            gradeTranslation(state, chunk, heard)
        } else {
            gradeReading(state, chunk.sr, heard)
        }
    }

    private fun gradeReading(state: StoryState, text: String, heard: String) {
        val score = LocalCheck.readingScore(heard, text)
        if (score.passed) {
            advance(state)
            return
        }
        val attempts = state.attempts + 1
        _story.value = state.copy(
            attempts = attempts,
            heard = heard,
            note = "Совпало слов: ${score.matched} из ${score.total}. Ещё раз.",
            // На слух отрезок не бесконечен: не разобрал за три захода — текст
            // открывается, и дальше это обычное чтение вслух.
            revealed = state.revealed ||
                (state.mode == StoryMode.Listen && attempts >= ATTEMPTS_BEFORE_REVEAL)
        )
    }

    /**
     * Устный перевод отрезка: сперва своя сверка, потом модель.
     *
     * Если сказанное после свёртки диакритики и иекавицы совпало с эталоном
     * дословно, спрашивать не о чем — это он и есть. Модель нужна там, где
     * перевод другой: свой порядок слов, синоним, иная конструкция.
     *
     * Появилось по жалобе: сербское `još uvek` при черногорском эталоне
     * `još uvijek` модель однажды не засчитала, хотя промпт прямо велит
     * засчитывать экавицу. Промпт — просьба, а `LocalCheck.matchesSpoken` —
     * правило, и там, где правила достаточно, просить незачем. Заодно такой
     * отрезок проходится без сети и без денег.
     */
    private fun gradeTranslation(state: StoryState, chunk: StoryChunk, heard: String) {
        if (LocalCheck.matchesSpoken(heard, chunk.sr)) {
            advance(state)
            return
        }
        _story.value = state.copy(checking = true, heard = heard, note = "")
        viewModelScope.launch {
            val result = checker.checkSpoken(
                task = chunk.ru,
                reference = chunk.sr,
                heard = heard
            )
            // Пока ждали вердикт, историю могли закрыть или уйти с отрезка —
            // тогда он уже ни о чём.
            val now = _story.value ?: return@launch
            if (now.id != state.id || now.index != state.index) return@launch

            when (result) {
                is CheckResult.Ok ->
                    if (result.verdict.correct) {
                        advance(now)
                    } else {
                        val attempts = now.attempts + 1
                        _story.value = now.copy(
                            checking = false,
                            attempts = attempts,
                            note = result.verdict.feedback.ifBlank { "Не то. Попробуй иначе." },
                            revealed = attempts >= ATTEMPTS_BEFORE_REVEAL
                        )
                    }

                // Не ошибка ученика: попытку не засчитываем. Иначе оборванная
                // связь через три отрезка открыла бы ответ за него.
                is CheckResult.Failed -> _story.value = now.copy(
                    checking = false,
                    note = "Не удалось проверить: ${result.message}"
                )
            }
        }
    }

    /**
     * Открыть текст отрезка по просьбе.
     *
     * До 1.35 текст открывался только после трёх неудач подряд, и в режиме «на
     * слух» это оказалось ловушкой: «ничего не расслышал» за неудачу не
     * считается — и правильно, это не ошибка чтения, — но если движок молчит
     * раз за разом, счётчик стоит на нуле, текст не открывается, а выхода из
     * отрезка в этом режиме нет вовсе. Владелец так и застрял на фразе.
     *
     * Поэтому открыть можно самому, нажав на любую плашку. Попытку это не
     * тратит и ошибкой не считается: отрезок просто становится чтением вслух,
     * ровно как после трёх неудач.
     */
    fun revealChunk() {
        val state = _story.value ?: return
        if (state.revealed) return
        _story.value = state.copy(revealed = true)
    }

    /** Отрезок взят — или оставлен: движок ошибается сам по себе, упираться некуда. */
    fun skipChunk() {
        // Оставленный отрезок в счёт прочитанного не идёт: его не прочли.
        advance(_story.value ?: return, passed = false)
    }

    private fun advance(state: StoryState, passed: Boolean = true) {
        if (passed) viewModelScope.launch { bump(chunks = 1) }
        val next = state.index + 1
        _story.value = state.copy(
            index = next, attempts = 0, heard = "", note = "",
            revealed = false, checking = false
        )
        saveStory(state.id, state.mode, next, next >= state.chunks.size)
    }

    /**
     * Запомнить, докуда дошли.
     *
     * Счётчик **не опускается**: ежедневное задание открывает историю с начала,
     * и без этого правила три отрезка в занятии стирали бы девять, прочитанных
     * вчера с вкладки. Отметка о том, что дочитано, тоже не снимается.
     */
    private fun saveStory(id: String, mode: StoryMode, done: Int, finished: Boolean) {
        viewModelScope.launch {
            val was = dao.story(id, mode.key)
            dao.upsertStory(
                StoryProgressEntity(
                    storyId = id,
                    mode = mode.key,
                    chunksDone = maxOf(done, was?.chunksDone ?: 0),
                    finishedAt = when {
                        finished -> System.currentTimeMillis()
                        else -> was?.finishedAt ?: 0L
                    }
                )
            )
        }
    }

    // --- Копия прогресса ---

    /**
     * Запоминает выбранную папку и сразу же кладёт туда копию: иначе непонятно,
     * сработало ли, до самого конца первой сессии.
     */
    fun useProgressFolder(uri: Uri) {
        viewModelScope.launch {
            val taken = runCatching { progress.rememberFolder(uri) }.isSuccess
            val r = progress.save(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
            _settings.value = _settings.value?.copy(
                progressFolder = progress.folderLabel(),
                progressLastSave = progress.lastSave(),
                progressLocalExists = progress.localFile().exists(),
                notice = when {
                    !taken -> "Эта папка не даёт постоянного доступа. Выбери другую — " +
                        "копия на телефоне всё равно пишется."
                    r.folder == true -> "Папка выбрана, копия записана. Карточек: ${r.cards}."
                    else -> "Папка выбрана, но записать в неё не вышло: ${r.error.orEmpty()}. " +
                        "Копия на телефоне сохранена."
                }
            )
        }
    }

    fun forgetProgressFolder() {
        progress.forgetFolder()
        _settings.value = _settings.value?.copy(
            progressFolder = null,
            notice = "Папка забыта, копия больше не пишется."
        )
    }

    fun saveProgressNow() {
        viewModelScope.launch {
            val r = progress.save(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
            _settings.value = _settings.value?.copy(
                progressLastSave = progress.lastSave(),
                progressLocalExists = progress.localFile().exists(),
                notice = when {
                    r.folder == true -> "Копия обновлена в папке и на телефоне. Карточек: ${r.cards}."
                    r.folder == false -> "В папку не вышло: ${r.error.orEmpty()}. " +
                        "На телефоне копия обновлена, карточек: ${r.cards}."
                    r.local -> "Копия на телефоне обновлена. Карточек: ${r.cards}. " +
                        "Папка не выбрана — удаление приложения её унесёт."
                    else -> "Сохранить не удалось вообще никуда."
                }
            )
        }
    }

    /** Восстановление из копии на телефоне — когда папки нет или она отвалилась. */
    fun restoreLocalProgress() {
        viewModelScope.launch {
            val result = progress.restoreLocal()
            _settings.value = _settings.value?.copy(
                notice = if (result == null) {
                    "Копии на телефоне нет или её не разобрать."
                } else {
                    "Из копии на телефоне влито карточек: ${result.first}, уроков: ${result.second}."
                }
            )
            refreshHome()
        }
    }

    /** Ручное сохранение в произвольный файл — на случай, когда папки нет. */
    fun saveProgressTo(uri: Uri) {
        viewModelScope.launch {
            val saved = progress.saveTo(uri, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
            _settings.value = _settings.value?.copy(
                notice = if (saved != null) "Сохранено. Карточек: $saved." else "Сохранить не вышло."
            )
        }
    }

    fun restoreProgress(uri: Uri) {
        viewModelScope.launch {
            val result = progress.restoreFrom(uri)
            _settings.value = _settings.value?.copy(
                notice = if (result == null) {
                    "Файл не разобрать — это точно копия прогресса?"
                } else {
                    "Влито карточек: ${result.first}, уроков: ${result.second}."
                }
            )
            refreshHome()
        }
    }

    /**
     * Копия после каждой сессии, молча: лезть с сообщением посреди занятий незачем.
     *
     * Идёт всегда, даже когда папка не выбрана: копия на телефоне не требует ничего
     * и пишется при любом раскладе. Чем кончилось, видно в настройках строкой
     * «последняя копия» — так неудача не остаётся незамеченной навсегда.
     */
    private fun autoSaveProgress() {
        viewModelScope.launch {
            progress.save(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        }
    }

    // --- Жалоба не про задание ---

    /**
     * Свободная жалоба из кнопки рядом с шестерёнкой: доступна в любой момент.
     *
     * В отличие от [complain] не трогает ни SRS, ни [SessionState.complaintFiled] —
     * запись ничего не говорит о текущем задании, поэтому и откатывать нечего,
     * а урок продолжается с того же места.
     *
     * Если жалоба написана посреди урока, задание всё-таки запоминается — как
     * место, где это случилось. Путать его с предметом жалобы не даёт причина
     * [NOTE_REASON].
     */
    /**
     * Идея — «сделать бы», а не «сломано».
     *
     * Тем же путём, что заметка: файл как очередь, потом issue. Отличается
     * только код причины, из него берётся метка. Разводить их по разным
     * механизмам незачем — а вот метку человек должен поставить в момент
     * записи, задним числом по тексту её не восстановить.
     */
    fun addIdea(text: String) = addRemark(text, IDEA_REASON, "Идея записана")

    fun addNote(text: String) = addRemark(text, NOTE_REASON, "Жалоба записана")

    private fun addRemark(text: String, reason: String, said: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val item = _session.value?.let { it.items[it.index] }
        val openStory = _story.value

        viewModelScope.launch {
            complaints.append(
                Complaint(
                    ts = complaints.now(),
                    exerciseId = item?.exercise?.id.orEmpty(),
                    lessonId = item?.lessonId ?: openStory?.id.orEmpty(),
                    type = item?.exercise?.typeName
                        ?: openStory?.let { "story-" + it.mode.key }.orEmpty(),
                    reason = reason,
                    note = body,
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME
                )
            )
            // Сообщаем после записи, а не по нажатию: иначе подтверждение соврало бы,
            // если внешняя память вдруг недоступна.
            _notice.value = said
            sendComplaints()
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    // --- Проверка ответов ---

    fun submitText(answer: String) {
        val state = _session.value ?: return
        val ex = state.current
        if (answer.isBlank() || checking) return
        lastAnswer = answer

        when (ex) {
            is Exercise.TranslateToTarget -> checkWithModel(ex.prompt, ex.reference, answer)
            is Exercise.TranslateToNative -> checkWithModel(ex.prompt, ex.reference, answer)
            is Exercise.Form -> localResult(
                LocalCheck.matchesTyped(answer, ex.answer),
                ex.explanation,
                ex.answer,
                answer
            )
            // У обратного перевода эталон — словарная статья с синонимами,
            // и точное совпадение строки тут не годится.
            is Exercise.Word -> localResult(
                if (ex.native) LocalCheck.matchesGloss(answer, ex.answer)
                else LocalCheck.matchesTyped(answer, ex.answer) ||
                    ex.also.any { LocalCheck.matchesTyped(answer, it) },
                ex.explanation,
                ex.answer,
                answer
            )
            is Exercise.Listening -> localResult(
                LocalCheck.matchesTyped(answer, ex.audioText),
                ex.translation,
                ex.audioText,
                answer
            )
            is Exercise.Choice -> localResult(
                LocalCheck.matches(answer, ex.answer),
                ex.explanation,
                ex.answer,
                answer
            )
            is Exercise.WordBank -> localResult(
                LocalCheck.matches(answer, ex.answer),
                ex.explanation,
                ex.answer,
                answer
            )
            // Распознанное показывается отдельной строкой «Услышано», в note дублировать не нужно.
            is Exercise.Speaking -> spokenResult(
                LocalCheck.matchesSpoken(answer, ex.phrase),
                ex.phrase,
                answer
            )
            is Exercise.Repeat -> spokenResult(
                LocalCheck.matchesSpoken(answer, ex.phrase),
                ex.phrase,
                answer
            )
            // Экран пар строкой не отвечает: у него свой путь, submitMatch.
            // Ветка нужна компилятору, а её пустота — напоминание, что второго
            // способа ответить у этого задания нет.
            is Exercise.Match -> Unit
            is Exercise.Reading -> {
                val score = LocalCheck.readingScore(answer, ex.text)
                localResult(
                    score.passed,
                    "Совпало слов: ${score.matched} из ${score.total}.",
                    ex.text,
                    answer
                )
            }
        }
    }

    /**
     * Вердикт по экрану пар: одно задание, пять карточек.
     *
     * [wrong] — карточки, замешанные хоть в одной неверной паре. Верным
     * считается сложенное **с первой попытки**: собрать экран до конца всё
     * равно придётся, и без этого различия любой экран засчитывался бы целиком.
     *
     * Записывается это всегда как тренировка вне расписания
     * ([Scheduler.practice]) — даже в обычной сессии, и это осознанно. Выбрать
     * слово из пяти, когда ответ тут же на экране, легче, чем вспомнить его с
     * нуля; двигать по такому ответу интервал значило бы отложить слово на
     * месяц по узнаванию с подсказкой. В счёт встреч оно при этом идёт: десять
     * встреч, после которых слово считается выученным, — это встречи, а не
     * непременно припоминания. Промах же считается полноценным лапсом:
     * перепутал — значит слово шаткое.
     *
     * Промахом помечаются **оба** слова неверной пары. Перепутать можно только
     * два слова сразу, и какое из них человек не знал, экран не говорит.
     *
     * Карточку до ответа ([cardBeforeAnswer]) здесь не запоминаем: жалоба
     * откатывает одну карточку, а их тут пять, и какую из них имели в виду,
     * жалоба не сообщает. Поле обнуляется, чтобы жалоба на экран пар не
     * откатила заодно предыдущее задание.
     */
    fun submitMatch(wrong: Set<String>) {
        val state = _session.value ?: return
        val ex = state.current as? Exercise.Match ?: return
        if (state.phase != Phase.Input) return

        val seconds = noteTime(state, ex)
        cardBeforeAnswer = null
        lastAnswer = ""

        val missedCount = ex.pairs.count { it.cardId in wrong }
        viewModelScope.launch {
            // Экран пар — одно задание в счёте, сколько бы слов на нём ни было:
            // иначе «ответов за день» мерило бы не работу, а её нарезку.
            bump(
                wordSeconds = seconds,
                answers = 1,
                correct = if (missedCount == 0) 1 else 0
            )
            val now = System.currentTimeMillis()
            ex.pairs.forEach { pair ->
                val ok = pair.cardId !in wrong
                val existing = dao.card(pair.cardId)
                dao.upsertCard(
                    existing?.let { Scheduler.practice(it, ok, now) }
                        ?: Scheduler.metCard(pair.cardId, VocabRepository.LESSON_ID, ok, now)
                )
            }
        }

        val missed = ex.pairs.filter { it.cardId in wrong }
        _session.value = state.copy(
            phase = Phase.Result(
                correct = missed.isEmpty(),
                feedback = buildString {
                    append("С первой попытки: ")
                    append(ex.pairs.size - missed.size)
                    append(" из ")
                    append(ex.pairs.size)
                    append(".")
                    if (missed.isNotEmpty()) {
                        append(missed.joinToString(", ", prefix = "\nПерепутано: ") { it.me })
                    }
                },
                better = "",
                // Эталона у экрана пар нет: он раскрыл себя сам, пока его
                // собирали, и строка «Правильно: …» повторяла бы экран.
                expected = "",
                answer = ""
            ),
            correct = state.correct + if (missed.isEmpty()) 1 else 0
        )
    }

    /**
     * Пропуск задания: показываем правильный ответ и отодвигаем карточку,
     * не трогая ease и счётчик повторений. Ошибкой не считается и в счёт урока
     * не идёт — см. [Scheduler.postpone].
     */
    fun skipCurrent() {
        val state = _session.value ?: return
        val item = state.items[state.index]
        val seconds = noteTime(state, item.exercise, measure = false)
        lastAnswer = ""

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = dao.card(item.exercise.id)
            // Время потрачено, ответа не было: в счёт заданий пропуск не идёт,
            // иначе точность росла бы от того, что задания пропускают.
            noteAnswer(item, existing, seconds, correct = null)
            cardBeforeAnswer = item.exercise.id to existing
            dao.upsertCard(
                existing?.let { Scheduler.postpone(it, now) }
                    ?: Scheduler.skippedCard(item.exercise.id, item.lessonId, now)
            )
        }

        _session.value = state.copy(phase = Phase.Skipped(item.exercise.referenceAnswer))
    }

    /**
     * Вердикт по произнесённому: до [SPOKEN_ATTEMPTS] заходов на задание.
     *
     * Промах уходит в [Phase.Retry] и не трогает ни SRS, ни счёт урока —
     * засчитывается только последняя попытка. Так и в историях: отрезок
     * повторяют, пока не выйдет, потому что чаще всего исправлять надо не
     * произношение, а то, что движок услышал соседнее слово.
     *
     * Верный ответ засчитывается с любой попытки. Делить «с первого раза» и
     * «с третьего» здесь нечем: разницу между шатким произношением и осечкой
     * распознавания на устройстве не различить, а `Scheduler` из двух зол
     * должен выбирать мягкое.
     *
     * Время заданию это не приписывает лишнего: [Pace] считает от показа
     * задания до вердикта, а вердикт один — значит попытки войдут в замер как
     * часть одного и того же задания, чем они и являются.
     */
    private fun spokenResult(correct: Boolean, expected: String, answer: String) {
        val used = (_session.value?.phase as? Phase.Retry)?.attempts ?: 0
        if (correct || used + 1 >= SPOKEN_ATTEMPTS) {
            localResult(correct, "", expected, answer)
            return
        }
        _session.value = _session.value?.copy(
            phase = Phase.Retry(used + 1, answer, SPOKEN_ATTEMPTS - used - 1)
        )
    }

    private fun localResult(correct: Boolean, note: String, expected: String, answer: String) {
        record(correct)
        _session.value = _session.value?.copy(
            phase = Phase.Result(correct, note, "", expected, answer),
            correct = (_session.value?.correct ?: 0) + if (correct) 1 else 0
        )
    }

    /**
     * Проверка свободного перевода: сперва память о засчитанных ответах, потом сеть.
     *
     * Попадание — это не «примерно то же самое», а буквально тот же запрос,
     * который уже отправлялся (см. `HaikuChecker.key`). Поэтому вердикт из
     * памяти проходит ровно тот же путь, что живой: карточка SRS двигается,
     * счётчик растёт, откат по жалобе работает. Кэш подменяет поход в сеть,
     * а не разбор результата.
     *
     * [Phase.Checking] выставляется только после промаха: на попадании ждать
     * нечего, и «Проверяю…» мелькнуло бы зря.
     */
    private fun checkWithModel(task: String, reference: String, answer: String) {
        val exerciseId = _session.value?.current?.id.orEmpty()
        checking = true
        viewModelScope.launch {
            try {
                val key = checker.key(task, reference, answer)
                val known = cache.find(key)
                if (known != null) {
                    cache.countHit()
                    // В памяти лежат только засчитанные ответы, отсюда correct = true.
                    showVerdict(Verdict(true, known.feedback, known.better), reference, answer)
                    return@launch
                }

                _session.value = _session.value?.copy(phase = Phase.Checking)
                when (val result = checker.check(task, reference, answer)) {
                    is CheckResult.Ok -> {
                        val v = result.verdict
                        cache.countAsked()
                        if (v.correct) {
                            cache.remember(
                                hash = key,
                                exerciseId = exerciseId,
                                task = task,
                                reference = reference,
                                answer = answer,
                                feedback = v.feedback,
                                better = v.better
                            )
                        }
                        showVerdict(v, reference, answer)
                    }
                    is CheckResult.Failed -> {
                        _session.value = _session.value?.copy(
                            phase = Phase.Blocked("Проверка не прошла: ${result.message}")
                        )
                    }
                }
            } finally {
                checking = false
            }
        }
    }

    private fun showVerdict(v: Verdict, reference: String, answer: String) {
        record(v.correct)
        _session.value = _session.value?.copy(
            phase = Phase.Result(v.correct, v.feedback, v.better, reference, answer),
            correct = (_session.value?.correct ?: 0) + if (v.correct) 1 else 0
        )
    }

    fun retryAfterBlock() {
        _session.value = _session.value?.copy(phase = Phase.Input)
    }

    /**
     * Замер времени на задание.
     *
     * Два разных числа из одного отсчёта. [Pace.record] уточняет, сколько
     * занимает задание такого типа, — из этого считается, что влезает в
     * пятнадцать минут. [Pace.spend] списывает время с дневного бюджета, и
     * списывается оно в любом занятии, не только в ежедневном: урок, пройденный
     * руками из вкладки, — это тоже потраченное на язык время.
     *
     * [measure] снимается при пропуске: пропустить можно мгновенно, и такие
     * замеры сделали бы тип вдвое быстрее, чем он есть. Время при этом всё
     * равно списывается — оно потрачено.
     */
    private fun noteTime(state: SessionState, ex: Exercise, measure: Boolean = true): Int {
        if (state.shownAt <= 0L) return 0
        val seconds = (System.currentTimeMillis() - state.shownAt) / 1000.0
        if (measure) pace.record(ex.typeName, seconds)
        // Возвращаем то, что реально списано: в отчёт должно уйти ровно то же
        // время, каким ежедневное задание меряет свои пятнадцать минут.
        return pace.spend(seconds)
    }

    private fun record(correct: Boolean) {
        val state = _session.value ?: return
        val item = state.items[state.index]
        val seconds = noteTime(state, item.exercise)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = dao.card(item.exercise.id)
            noteAnswer(item, existing, seconds, correct)
            cardBeforeAnswer = item.exercise.id to existing
            val updated: CardEntity = when {
                existing == null ->
                    Scheduler.newCard(item.exercise.id, item.lessonId, correct, now)
                // Тренировка вне расписания интервал не двигает: см. Scheduler.
                state.practice -> Scheduler.practice(existing, correct, now)
                else -> Scheduler.update(existing, correct, now)
            }
            dao.upsertCard(updated)
        }
    }

    /**
     * Жалоба на текущее задание.
     *
     * Пишется в JSONL и заодно откатывает карточку к состоянию до ответа:
     * если задание кривое, ответ на него ничего не говорит о знаниях, а лапс
     * вернул бы карточку через 10 минут и мешал бы до самой починки урока.
     */
    fun complain(reason: ComplaintReason, note: String) {
        val state = _session.value ?: return
        val item = state.items[state.index]
        val result = state.phase as? Phase.Result

        viewModelScope.launch {
            complaints.append(
                Complaint(
                    ts = complaints.now(),
                    exerciseId = item.exercise.id,
                    lessonId = item.lessonId,
                    type = item.exercise.typeName,
                    reason = reason.code,
                    note = note.trim(),
                    userAnswer = lastAnswer,
                    expected = item.exercise.referenceAnswer,
                    verdict = result?.let {
                        ComplaintVerdict(it.correct, it.feedback, it.better)
                    },
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME
                )
            )

            // Жалоба ставит под сомнение и то, что по этому заданию засчитано
            // раньше: при неверном эталоне модель сравнивала ответ не с тем.
            cache.forget(item.exercise.id)

            val snapshot = cardBeforeAnswer
            if (snapshot != null && snapshot.first == item.exercise.id) {
                val before = snapshot.second
                if (before == null) dao.deleteCard(item.exercise.id) else dao.upsertCard(before)
            }

            _session.value = _session.value?.copy(complaintFiled = true)
            sendComplaints()
        }
    }

    fun next() {
        val state = _session.value ?: return
        if (state.index + 1 >= state.items.size) {
            finish(state)
        } else {
            _session.value = state.copy(
                index = state.index + 1,
                phase = Phase.Input,
                shownAt = System.currentTimeMillis(),
                complaintFiled = false
            )
        }
    }

    private fun finish(state: SessionState) {
        viewModelScope.launch {
            var closed = 0
            if (!state.isReview) {
                val lessonId = state.items.first().lessonId
                // Урок, пройденный второй раз, вторым в счёт не идёт: запись о
                // нём переписывается, а закрыт он был однажды.
                if (dao.lessonProgress().none { it.lessonId == lessonId }) closed++
                dao.upsertLesson(
                    LessonProgressEntity(
                        lessonId = lessonId,
                        completedAt = System.currentTimeMillis(),
                        correct = state.correct,
                        total = state.items.size
                    )
                )
            }
            if (state.daily) closed += closePortionedLessons(state)
            bump(sessions = 1, lessons = closed)
            _session.value = state.copy(finished = true)
            // Заставка только у ежедневного задания: это единственное занятие,
            // которое человек «сдаёт» целиком, — у повторения и словаря конца
            // нет по устройству.
            if (state.daily) _splash.value = buildSplash(state)
            autoSaveProgress()
        }
    }

    /**
     * Отметить уроки, которые ежедневное задание довело до конца.
     *
     * Урок оно вводит порциями по несколько заданий, поэтому «пройден» тут не
     * событие конца сессии, а факт: у всех заданий урока появились карточки.
     * Без этой записи урок остался бы вечно незакрытым — и на главном экране,
     * и для [nextPortion], который начал бы вводить его заново.
     *
     * В счёт идут задания, отвеченные верно хоть раз за всю жизнь карточки.
     * Для урока, растянутого на три дня, «сколько угадал с первого раза»
     * смысла уже не имеет — а вот сколько из него держится, имеет.
     */
    private suspend fun closePortionedLessons(state: SessionState): Int {
        var closed = 0
        val touched = state.items.map { it.lessonId }
            .distinct()
            .filter { it != VocabRepository.LESSON_ID }
        val finished = dao.lessonProgress().mapTo(mutableSetOf()) { it.lessonId }
        for (lessonId in touched) {
            if (lessonId in finished) continue
            val total = repo.lesson(lessonId).exercises.size
            val cards = dao.cardsIn(lessonId)
            if (cards.size < total) continue
            dao.upsertLesson(
                LessonProgressEntity(
                    lessonId = lessonId,
                    completedAt = System.currentTimeMillis(),
                    correct = cards.count { it.correct > 0 },
                    total = total
                )
            )
            closed++
        }
        return closed
    }

    fun expectedAnswer(): String = _session.value?.current?.referenceAnswer.orEmpty()

    private fun isOnline(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
