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
import com.crnogorski.trener.notify.Reminder
import com.crnogorski.trener.speech.Speaker
import com.crnogorski.trener.ui.AppViewModel
import com.crnogorski.trener.ui.CrnogorskiTheme
import com.crnogorski.trener.ui.HomeScreen
import com.crnogorski.trener.ui.Ink
import com.crnogorski.trener.ui.Paper
import com.crnogorski.trener.ui.SessionScreen
import com.crnogorski.trener.ui.SettingsScreen
import com.crnogorski.trener.ui.StoryScreen
import com.crnogorski.trener.ui.Surface2

class MainActivity : ComponentActivity() {

    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        speaker = Speaker(this)

        // Напоминание назначается при каждом запуске: будильник Android не
        // переживает ни перезагрузку, ни обновление приложения, а вызов
        // идемпотентен — старый заменяется тем же PendingIntent.
        Reminder.schedule(this)
        askForNotifications()

        setContent {
            CrnogorskiTheme {
                val vm: AppViewModel = viewModel()
                val home by vm.home.collectAsStateWithLifecycle()
                val session by vm.session.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val story by vm.story.collectAsStateWithLifecycle()
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
                    val active = session
                    val openSettings = settings
                    val openStory = story
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
                                    onClose = vm::closeStory
                                )
                            } else if (openSettings != null) {
                                SettingsScreen(
                                    state = openSettings,
                                    speaker = speaker,
                                    prepareReport = vm::reportToSend,
                                    onArchive = vm::archiveComplaints,
                                    onFolder = vm::useProgressFolder,
                                    onForgetFolder = vm::forgetProgressFolder,
                                    onSaveNow = vm::saveProgressNow,
                                    onSaveTo = vm::saveProgressTo,
                                    onRestore = vm::restoreProgress,
                                    onRestoreLocal = vm::restoreLocalProgress,
                                    onCache = vm::useVerdictCache,
                                    onClearCache = vm::clearVerdictCache,
                                    onDailyMinutes = vm::setDailyMinutes,
                                    onClose = vm::closeSettings
                                )
                            } else {
                                HomeScreen(
                                    state = home,
                                    onLesson = vm::startLesson,
                                    onDaily = { extra -> vm.startDaily(extra = extra) },
                                    onReview = vm::startReview,
                                    onSettings = vm::openSettings,
                                    onStory = vm::openStory,
                                    onVocab = { back, practice ->
                                        vm.startVocab(back = back, practice = practice)
                                    },
                                    onTab = vm::selectTab,
                                    onToggleGroup = vm::toggleGroup,
                                    onNote = vm::addNote
                                )
                            }
                        }
                    } else {
                        androidx.compose.foundation.layout.Box(content) {
                            SessionScreen(
                                state = active,
                                speaker = speaker,
                                onSubmit = vm::submitText,
                                onSkip = vm::skipCurrent,
                                onNext = vm::next,
                                onRetryBlock = vm::retryAfterBlock,
                                onComplain = vm::complain,
                                onNote = vm::addNote,
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

    override fun onDestroy() {
        speaker.release()
        super.onDestroy()
    }
}
