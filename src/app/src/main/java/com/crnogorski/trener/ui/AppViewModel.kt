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
import com.crnogorski.trener.data.Exercise
import com.crnogorski.trener.data.Glossary
import com.crnogorski.trener.data.LessonProgressEntity
import com.crnogorski.trener.data.LessonRef
import com.crnogorski.trener.data.LessonRepository
import com.crnogorski.trener.data.LocalCheck
import com.crnogorski.trener.data.NOTE_REASON
import com.crnogorski.trener.data.ProgressStore
import com.crnogorski.trener.data.StoryChunk
import com.crnogorski.trener.data.StoryProgressEntity
import com.crnogorski.trener.data.StoryRef
import com.crnogorski.trener.data.needsModelCheck
import com.crnogorski.trener.data.referenceAnswer
import com.crnogorski.trener.data.typeName
import com.crnogorski.trener.net.CheckResult
import com.crnogorski.trener.net.HaikuChecker
import com.crnogorski.trener.srs.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class LessonCard(
    val ref: LessonRef,
    val done: Boolean,
    val score: String?
)

/** Вкладка главного экрана. Уроки и истории — разные занятия, мешать их в одном списке незачем. */
enum class HomeTab { Lessons, Stories }

/** Раздел главного экрана: заголовок и уроки под ним, в порядке из `index.json`. */
data class LessonGroup(
    val title: String,
    val cards: List<LessonCard>
) {
    val done: Int get() = cards.count { it.done }
}

data class HomeState(
    val groups: List<LessonGroup> = emptyList(),
    val stories: List<StoryCard> = emptyList(),
    val dueCount: Int = 0,
    val tab: HomeTab = HomeTab.Lessons,
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
    /** Результат последнего действия — показывается под кнопками. */
    val notice: String? = null
)

data class SessionItem(val lessonId: String, val exercise: Exercise)

/** Строка раздела «Истории» на главном экране. */
data class StoryCard(
    val ref: StoryRef,
    val done: Int,
    val finished: Boolean
)

/** Экран истории: где мы в тексте и сколько раз подряд не вышло. */
data class StoryState(
    val id: String,
    val title: String,
    val chunks: List<StoryChunk>,
    val index: Int = 0,
    val attempts: Int = 0,
    /** Что расслышал движок на последней попытке — без этого непонятно, что не так. */
    val heard: String = "",
    /** Насколько совпало: «Совпало слов: 5 из 8». */
    val note: String = "",
    val glossaryMe: Map<String, String> = emptyMap()
)

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
}

data class SessionState(
    val title: String,
    val note: String = "",
    val items: List<SessionItem>,
    val index: Int = 0,
    val phase: Phase = Phase.Input,
    val correct: Int = 0,
    val isReview: Boolean = false,
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
private const val REVIEW_LIMIT = 25

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LessonRepository(app)
    private val dao = AppDb.get(app).dao()
    private val checker = HaikuChecker()
    private val complaints = ComplaintStore(app)
    private val progress = ProgressStore(app, dao)

    /** Последний отправленный ответ — попадает в жалобу как есть. */
    private var lastAnswer: String = ""

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
    private var tab: HomeTab = HomeTab.Lessons
    private var expanded: Set<String> = emptySet()

    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session.asStateFlow()

    private val _settings = MutableStateFlow<SettingsState?>(null)
    val settings: StateFlow<SettingsState?> = _settings.asStateFlow()

    private val _story = MutableStateFlow<StoryState?>(null)
    val story: StateFlow<StoryState?> = _story.asStateFlow()

    /** Короткое подтверждение поверх любого экрана — показывается и гасится в MainActivity. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        refreshHome()
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
                val storyDone = dao.storyProgress().associateBy { it.storyId }
                _home.value = HomeState(
                    tab = tab,
                    expandedGroups = expanded,
                    groups = groups,
                    stories = repo.stories().stories.map { ref ->
                        val p = storyDone[ref.id]
                        StoryCard(ref, p?.chunksDone ?: 0, (p?.finishedAt ?: 0L) > 0L)
                    },
                    dueCount = dao.dueCount(System.currentTimeMillis()),
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

    fun startLesson(lessonId: String) {
        viewModelScope.launch {
            val lesson = repo.lesson(lessonId)
            val items = lesson.exercises.map { SessionItem(lessonId, it) }
            _session.value = SessionState(
                title = lesson.title,
                note = lesson.note,
                items = items,
                glossary = repo.glossary()
            )
            guardNetwork(items)
        }
    }

    fun startReview() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val total = dao.dueCount(now)
            val due = dao.dueCards(now, REVIEW_LIMIT)
            val byId = repo.exercisesIn(due.map { it.lessonId })
            val items = due.mapNotNull { card ->
                byId[card.exerciseId]?.let { (lessonId, ex) -> SessionItem(lessonId, ex) }
            }
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
            _settings.value = SettingsState(
                complaintCount = complaints.count(),
                filePath = complaints.file().absolutePath,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                progressFolder = progress.folderLabel(),
                progressLocal = progress.localFile().absolutePath,
                progressLastSave = progress.lastSave(),
                progressLocalExists = progress.localFile().exists()
            )
        }
    }

    fun closeSettings() {
        _settings.value = null
    }

    /**
     * Копия отчёта под именем с ярлыком устройства и меткой UTC — её и отдаёт
     * в share экран настроек. Интент собирается там: для него нужен Context
     * активности, а не приложения.
     */
    suspend fun reportToSend(): File? = complaints.prepareForSend()

    fun archiveComplaints() {
        viewModelScope.launch {
            val moved = complaints.archive()
            _settings.value = _settings.value?.copy(
                complaintCount = complaints.count(),
                notice = if (moved > 0) {
                    "Отложено записей: $moved. Файл остался на телефоне рядом с новым."
                } else {
                    "Откладывать нечего."
                }
            )
        }
    }

    // --- Истории ---

    /**
     * Открывает историю с того места, где её бросили.
     *
     * Дочитанную открываем с начала: возвращаться к ней имеет смысл только чтобы
     * перечитать целиком, а «продолжить с конца» — это пустой экран.
     */
    fun openStory(id: String) {
        viewModelScope.launch {
            // Файл истории может не читаться — не тот путь, битый JSON. Ронять
            // из-за этого приложение нельзя: assets правятся чаще, чем код.
            val story = runCatching { repo.story(id) }.getOrNull()
            if (story == null) {
                _notice.value = "Историю не открыть — файл не читается"
                return@launch
            }
            val done = dao.story(id)?.chunksDone ?: 0
            _story.value = StoryState(
                id = story.id,
                title = story.title,
                chunks = story.chunks,
                index = if (done >= story.chunks.size) 0 else done,
                glossaryMe = runCatching { repo.glossary().me }.getOrDefault(emptyMap())
            )
        }
    }

    fun closeStory() {
        _story.value = null
        autoSaveProgress()
        refreshHome()
    }

    fun restartStory() {
        _story.value = _story.value?.copy(index = 0, attempts = 0, heard = "", note = "")
    }

    /**
     * Разбор прочитанного отрезка. Порог тот же, что у чтения в уроках, — доля
     * слов, прозвучавших по порядку: дословного совпадения движок не даёт.
     */
    fun submitChunk(heard: String) {
        val state = _story.value ?: return
        val chunk = state.chunks.getOrNull(state.index) ?: return
        val score = LocalCheck.readingScore(heard, chunk.sr)

        if (score.passed) {
            val next = state.index + 1
            _story.value = state.copy(index = next, attempts = 0, heard = "", note = "")
            saveStory(state.id, next, next >= state.chunks.size)
        } else {
            _story.value = state.copy(
                attempts = state.attempts + 1,
                heard = heard,
                note = "Совпало слов: ${score.matched} из ${score.total}. Ещё раз."
            )
        }
    }

    /** Отрезок оставлен невзятым: движок ошибается сам по себе, упираться некуда. */
    fun skipChunk() {
        val state = _story.value ?: return
        val next = state.index + 1
        _story.value = state.copy(index = next, attempts = 0, heard = "", note = "")
        saveStory(state.id, next, next >= state.chunks.size)
    }

    private fun saveStory(id: String, done: Int, finished: Boolean) {
        viewModelScope.launch {
            dao.upsertStory(
                StoryProgressEntity(
                    storyId = id,
                    chunksDone = done,
                    finishedAt = if (finished) System.currentTimeMillis() else 0L
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
    fun addNote(text: String) {
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
                    type = item?.exercise?.typeName ?: if (openStory != null) "story" else "",
                    reason = NOTE_REASON,
                    note = body,
                    versionCode = BuildConfig.VERSION_CODE,
                    versionName = BuildConfig.VERSION_NAME
                )
            )
            // Сообщаем после записи, а не по нажатию: иначе подтверждение соврало бы,
            // если внешняя память вдруг недоступна.
            _notice.value = "Жалоба записана в отчёт"
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    // --- Проверка ответов ---

    fun submitText(answer: String) {
        val state = _session.value ?: return
        val ex = state.current
        if (answer.isBlank()) return
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
            is Exercise.Speaking -> localResult(
                LocalCheck.matchesSpoken(answer, ex.phrase),
                "",
                ex.phrase,
                answer
            )
            is Exercise.Repeat -> localResult(
                LocalCheck.matchesSpoken(answer, ex.phrase),
                "",
                ex.phrase,
                answer
            )
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
     * Пропуск задания: показываем правильный ответ и отодвигаем карточку,
     * не трогая ease и счётчик повторений. Ошибкой не считается и в счёт урока
     * не идёт — см. [Scheduler.postpone].
     */
    fun skipCurrent() {
        val state = _session.value ?: return
        val item = state.items[state.index]
        lastAnswer = ""

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = dao.card(item.exercise.id)
            cardBeforeAnswer = item.exercise.id to existing
            dao.upsertCard(
                existing?.let { Scheduler.postpone(it, now) }
                    ?: Scheduler.skippedCard(item.exercise.id, item.lessonId, now)
            )
        }

        _session.value = state.copy(phase = Phase.Skipped(item.exercise.referenceAnswer))
    }

    private fun localResult(correct: Boolean, note: String, expected: String, answer: String) {
        record(correct)
        _session.value = _session.value?.copy(
            phase = Phase.Result(correct, note, "", expected, answer),
            correct = (_session.value?.correct ?: 0) + if (correct) 1 else 0
        )
    }

    private fun checkWithModel(task: String, reference: String, answer: String) {
        _session.value = _session.value?.copy(phase = Phase.Checking)
        viewModelScope.launch {
            when (val result = checker.check(task, reference, answer)) {
                is CheckResult.Ok -> {
                    val v = result.verdict
                    record(v.correct)
                    _session.value = _session.value?.copy(
                        phase = Phase.Result(v.correct, v.feedback, v.better, reference, answer),
                        correct = (_session.value?.correct ?: 0) + if (v.correct) 1 else 0
                    )
                }
                is CheckResult.Failed -> {
                    _session.value = _session.value?.copy(
                        phase = Phase.Blocked("Проверка не прошла: ${result.message}")
                    )
                }
            }
        }
    }

    fun retryAfterBlock() {
        _session.value = _session.value?.copy(phase = Phase.Input)
    }

    private fun record(correct: Boolean) {
        val state = _session.value ?: return
        val item = state.items[state.index]
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = dao.card(item.exercise.id)
            cardBeforeAnswer = item.exercise.id to existing
            val updated: CardEntity = existing
                ?.let { Scheduler.update(it, correct, now) }
                ?: Scheduler.newCard(item.exercise.id, item.lessonId, correct, now)
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

            val snapshot = cardBeforeAnswer
            if (snapshot != null && snapshot.first == item.exercise.id) {
                val before = snapshot.second
                if (before == null) dao.deleteCard(item.exercise.id) else dao.upsertCard(before)
            }

            _session.value = _session.value?.copy(complaintFiled = true)
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
                complaintFiled = false
            )
        }
    }

    private fun finish(state: SessionState) {
        viewModelScope.launch {
            if (!state.isReview) {
                dao.upsertLesson(
                    LessonProgressEntity(
                        lessonId = state.items.first().lessonId,
                        completedAt = System.currentTimeMillis(),
                        correct = state.correct,
                        total = state.items.size
                    )
                )
            }
            _session.value = state.copy(finished = true)
            autoSaveProgress()
        }
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
