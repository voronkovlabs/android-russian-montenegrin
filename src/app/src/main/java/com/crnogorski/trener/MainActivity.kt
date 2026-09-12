package com.crnogorski.trener

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import android.os.Process
import android.os.SystemClock
import com.crnogorski.trener.data.Config
import com.crnogorski.trener.data.Trace
import com.crnogorski.trener.notify.Reminder
import com.crnogorski.trener.speech.Speaker
import com.crnogorski.trener.ui.AppViewModel
import com.crnogorski.trener.ui.Backdrop
import com.crnogorski.trener.ui.CrnogorskiTheme
import com.crnogorski.trener.ui.DailySplash
import com.crnogorski.trener.ui.HomeScreen
import com.crnogorski.trener.ui.Ink
import com.crnogorski.trener.ui.Paper
import com.crnogorski.trener.ui.SessionScreen
import com.crnogorski.trener.ui.SettingsScreen
import com.crnogorski.trener.ui.StatsScreen
import com.crnogorski.trener.ui.StoryScreen
import com.crnogorski.trener.ui.Surface2

class MainActivity : ComponentActivity() {

    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Первой строкой: всё, что случится дальше, уже можно мерить. Читает
        // одну галочку из настроек — если она снята, дальше ничего не стоит.
        Trace.init(this)
        // Сколько прошло от рождения процесса до нашего кода. Это чужое время —
        // загрузка классов, Application, система, — и без него непонятно, наша
        // ли вина в долгом запуске вообще.
        Trace.event(
            "запуск: процесс → onCreate",
            SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        )

        enableEdgeToEdge()
        // Настройки курса поднимаются с диска до первого их чтения: файл
        // маленький, а разъехавшиеся значения в первые секунды работы хуже,
        // чем несколько миллисекунд на старте. Свежий тянется фоном.
        Trace.span("запуск: настройки курса с диска") { Config.load(this) }
        Trace.span("запуск: синтезатор речи") { speaker = Speaker(this) }

        // Напоминание назначается при каждом запуске: будильник Android не
        // переживает ни перезагрузку, ни обновление приложения, а вызов
        // идемпотентен — старый заменяется тем же PendingIntent.
        Trace.span("запуск: напоминание") { Reminder.schedule(this) }
        askForNotifications()

        // Конец видимого запуска: до этого мига человек смотрит на пустоту.
        // Меряем от onCreate, потому что «процесс → onCreate» уже записан
        // отдельно, и складывать их в одно число значило бы прятать, где ждали.
        val created = SystemClock.uptimeMillis()
        window.decorView.post {
            Trace.event("запуск: onCreate → первый кадр", SystemClock.uptimeMillis() - created)
        }

        setContent {
            CrnogorskiTheme {
                val vm: AppViewModel = viewModel()
                val home by vm.home.collectAsStateWithLifecycle()
                val session by vm.session.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val story by vm.story.collectAsStateWithLifecycle()
                val stats by vm.stats.collectAsStateWithLifecycle()
                val splash by vm.splash.collectAsStateWithLifecycle()
                val notice by vm.notice.collectAsStateWithLifecycle()

                // Подтверждение записанной жалобы: поверх любого экрана и без
                // остановки — нажатие на шестерёнку не должно прерывать урок.
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(notice) {
                    val text = notice ?: return@LaunchedEffect
                    snackbar.showSnackbar(text)
                    vm.clearNotice()
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Ink,
                    snackbarHost = {
                        SnackbarHost(snackbar) { data ->
                            Snackbar(data, containerColor = Surface2, contentColor = Paper)
                        }
                    }
                ) { inner ->
                    val content = Modifier.fillMaxSize().padding(inner)
                    // Заставка идёт поверх всего и без отступов Scaffold: она
                    // во весь экран, и полоса под шторкой резала бы постер.
                    val done = splash
                    if (done != null) {
                        DailySplash(state = done, onClose = vm::closeSplash)
                        return@Scaffold
                    }
                    val active = session
                    val openSettings = settings
                    val openStory = story
                    val openStats = stats
                    if (active == null) {
                        androidx.compose.foundation.layout.Box(content) {
                            if (openStory != null) {
                                StoryScreen(
                                    state = openStory,
                                    speaker = speaker,
                                    onSubmit = vm::submitChunk,
                                    onSkipChunk = vm::skipChunk,
                                    onReveal = vm::revealChunk,
                                    onRestart = vm::restartStory,
                                    onNote = vm::addNote,
                                    onIdea = vm::addIdea,
                                    onClose = vm::closeStory
                                )
                            } else if (openStats != null) {
                                Backdrop { StatsScreen(state = openStats, onClose = vm::closeStats) }
                            } else if (openSettings != null) {
                                SettingsScreen(
                                    state = openSettings,
                                    speaker = speaker,
                                    onSendComplaints = { vm.sendComplaints(loud = true) },
                                    onFolder = vm::useProgressFolder,
                                    onForgetFolder = vm::forgetProgressFolder,
                                    onSaveNow = vm::saveProgressNow,
                                    onSaveTo = vm::saveProgressTo,
                                    onRestore = vm::restoreProgress,
                                    onRestoreLocal = vm::restoreLocalProgress,
                                    onCache = vm::useVerdictCache,
                                    onClearCache = vm::clearVerdictCache,
                                    onDailyMinutes = vm::setDailyMinutes,
                                    onShowSplash = vm::previewSplash,
                                    onRefreshTuning = vm::refreshTuning,
                                    onCheckUpdate = vm::checkUpdate,
                                    onDiagnostics = vm::setDiagnostics,
                                    onSendDiagnostics = { vm.sendDiagnostics() },
                                    onInstallUpdate = vm::installUpdate,
                                    onClose = vm::closeSettings
                                )
                            } else {
                                // Фотография залива — только под главным и
                                // отчётом: там содержимое разложено карточками.
                                Backdrop {
                                HomeScreen(
                                    state = home,
                                    onLesson = vm::startLesson,
                                    onDaily = { extra -> vm.startDaily(extra = extra) },
                                    onReview = vm::startReview,
                                    onSettings = vm::openSettings,
                                    onStory = { id, mode, fromStart ->
                                        vm.openStory(id, mode, fromStart)
                                    },
                                    onVocab = { back, practice ->
                                        vm.startVocab(back = back, practice = practice)
                                    },
                                    onTab = vm::selectTab,
                                    onToggleGroup = vm::toggleGroup,
                                    onStats = vm::openStats,
                                    onUpdate = vm::installUpdate,
                                    onNote = vm::addNote,
                                    onIdea = vm::addIdea
                                )
                                }
                            }
                        }
                    } else {
                        androidx.compose.foundation.layout.Box(content) {
                            SessionScreen(
                                state = active,
                                speaker = speaker,
                                onSubmit = vm::submitText,
                                onSkip = vm::skipCurrent,
                                onMatch = vm::submitMatch,
                                onNext = vm::next,
                                onRetryBlock = vm::retryAfterBlock,
                                onComplain = vm::complain,
                                onNote = vm::addNote,
                                onIdea = vm::addIdea,
                                onExit = vm::exitSession
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Разрешение на уведомления — один раз, при запуске, и только если
     * напоминание включено.
     *
     * Спрашивается здесь, а не в момент включения галочки, потому что включено
     * оно по умолчанию: иначе первое напоминание молча не пришло бы, и понять,
     * почему, было бы неоткуда. Отказ ничего не ломает — напоминания просто
     * не будет, а `Reminder.show` проверяет разрешение перед показом.
     */
    private fun askForNotifications() {
        if (!Reminder.enabled(this)) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Уходим с экрана — сбрасываем накопленные замеры на диск.
     *
     * Именно тут, а не в `onDestroy`: до него дело может не дойти вовсе, если
     * систему прижмёт память. Трасса запуска нужна целиком, и дописывается она
     * уже после того, как запуск кончился.
     */
    override fun onStop() {
        Trace.parkAsync(this)
        super.onStop()
    }

    override fun onDestroy() {
        speaker.release()
        super.onDestroy()
    }
}
