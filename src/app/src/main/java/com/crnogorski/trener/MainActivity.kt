package com.crnogorski.trener

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crnogorski.trener.speech.Speaker
import com.crnogorski.trener.ui.AppViewModel
import com.crnogorski.trener.ui.CrnogorskiTheme
import com.crnogorski.trener.ui.HomeScreen
import com.crnogorski.trener.ui.Ink
import com.crnogorski.trener.ui.SessionScreen

class MainActivity : ComponentActivity() {

    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        speaker = Speaker(this)

        setContent {
            CrnogorskiTheme {
                val vm: AppViewModel = viewModel()
                val home by vm.home.collectAsStateWithLifecycle()
                val session by vm.session.collectAsStateWithLifecycle()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Ink
                ) { inner ->
                    val content = Modifier.fillMaxSize().padding(inner)
                    val active = session
                    if (active == null) {
                        androidx.compose.foundation.layout.Box(content) {
                            HomeScreen(
                                state = home,
                                onLesson = vm::startLesson,
                                onReview = vm::startReview
                            )
                        }
                    } else {
                        androidx.compose.foundation.layout.Box(content) {
                            SessionScreen(
                                state = active,
                                speaker = speaker,
                                onSubmit = vm::submitText,
                                onNext = vm::next,
                                onRetryBlock = vm::retryAfterBlock,
                                onExit = vm::exitSession
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        speaker.release()
        super.onDestroy()
    }
}
