package com.crnogorski.trener.ui

import android.app.Application
import android.net.ConnectivityManager
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
import com.crnogorski.trener.data.LessonProgressEntity
import com.crnogorski.trener.data.LessonRef
import com.crnogorski.trener.data.LessonRepository
import com.crnogorski.trener.data.LocalCheck
import com.crnogorski.trener.data.NOTE_REASON
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

data class HomeState(
    val lessons: List<LessonCard> = emptyList(),
    val dueCount: Int = 0,
    val loading: Boolean = true,
    val error: String? = null
)

/** Экран настроек: обслуживание отчёта о жалобах и проверка синтеза речи. */
data class SettingsState(
    val complaintCount: Int = 0,
    val filePath: String = "",
    val versionName: String = "",
    val versionCode: Int = 0,
    /** Результат последнего действия — показывается под кнопками. */
    val notice: String? = null
)

data class SessionItem(val lessonId: String, val exercise: Exercise)

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
    val complaintFiled: Boolean = false
) {
    val current: Exercise get() = items[index].exercise
    val progress: Float get() = if (items.isEmpty()) 0f else index.toFloat() / items.size
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LessonRepository(app)
    private val dao = AppDb.get(app).dao()
    private val checker = HaikuChecker()
    private val complaints = ComplaintStore(app)

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

    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session.asStateFlow()

    private val _settings = MutableStateFlow<SettingsState?>(null)
    val settings: StateFlow<SettingsState?> = _settings.asStateFlow()

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
                _home.value = HomeState(
                    lessons = refs.map { ref ->
                        val p = byId[ref.id]
                        LessonCard(ref, p != null, p?.let { "${it.correct}/${it.total}" })
                    },
                    dueCount = dao.dueCards(System.currentTimeMillis()).size,
                    loading = false
                )
            } catch (e: Exception) {
                _home.value = HomeState(loading = false, error = e.message ?: "Не удалось прочитать уроки")
            }
        }
    }

    fun startLesson(lessonId: String) {
        viewModelScope.launch {
            val lesson = repo.lesson(lessonId)
            val items = lesson.exercises.map { SessionItem(lessonId, it) }
            _session.value = SessionState(title = lesson.title, note = lesson.note, items = items)
            guardNetwork(items)
        }
    }

    fun startReview() {
        viewModelScope.launch {
            val due = dao.dueCards(System.currentTimeMillis())
            val all = repo.allExercises()
            val items = due.mapNotNull { card ->
                all[card.exerciseId]?.let { (lessonId, ex) -> SessionItem(lessonId, ex) }
            }
            if (items.isEmpty()) {
                refreshHome()
                return@launch
            }
            _session.value = SessionState(title = "Повторение", items = items, isReview = true)
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
        refreshHome()
    }

    // --- Настройки ---

    fun openSettings() {
        viewModelScope.launch {
            _settings.value = SettingsState(
                complaintCount = complaints.count(),
                filePath = complaints.file().absolutePath,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE
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

        viewModelScope.launch {
            complaints.append(
                Complaint(
                    ts = complaints.now(),
                    exerciseId = item?.exercise?.id.orEmpty(),
                    lessonId = item?.lessonId.orEmpty(),
                    type = item?.exercise?.typeName.orEmpty(),
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
                LocalCheck.matches(answer, ex.answer),
                ex.explanation,
                ex.answer,
                answer
            )
            is Exercise.Listening -> localResult(
                LocalCheck.matches(answer, ex.audioText),
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
                "",
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
