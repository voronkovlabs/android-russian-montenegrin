package com.crnogorski.trener.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.crnogorski.trener.MainActivity
import com.crnogorski.trener.R
import com.crnogorski.trener.net.Release
import com.crnogorski.trener.net.Updater
import com.crnogorski.trener.notify.notificationsAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Скачивание обновления — на весь процесс, а не на экран (0.51).
 *
 * До 0.51 APK качался в корутине модели экрана: свернул приложение — система
 * вправе прибить процесс, и шестьдесят мегабайт начинались заново. Теперь
 * работа здесь, процесс держит [UpdateService], а по окончании в шторке
 * уведомление: нажал — APK сразу у системного установщика.
 */
object UpdateWork {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _progress = MutableStateFlow<Float?>(null)

    /** Сколько скачано, от нуля до единицы; `null` — не качаем. */
    val progress: StateFlow<Float?> = _progress

    /** Версия, которая качается, — для текста уведомления. */
    @Volatile
    var version: String = ""
        private set

    /**
     * Приложение на экране — тогда установщик открывается сразу, без шторки:
     * из фона Android запуск чужого экрана всё равно не пустит. Ставит
     * [MainActivity] в `onStart`/`onStop`.
     */
    @Volatile
    var visible = false

    /**
     * Начать скачивание. [say] — строка для человека: чем кончилось. Второй
     * вызов во время скачивания ничего не делает — каталог чистится перед
     * закачкой, и первый проход писал бы в удалённый файл.
     */
    fun start(context: Context, release: Release, say: (String) -> Unit) {
        if (_progress.value != null) return
        val app = context.applicationContext
        version = release.version
        _progress.value = 0f
        ContextCompat.startForegroundService(app, Intent(app, UpdateService::class.java))
        scope.launch {
            val updater = Updater(app)
            updater.download(release) { part -> _progress.value = part }
                .onSuccess { file ->
                    _progress.value = null
                    val install = updater.installIntent(file)
                    if (visible) {
                        say("Открываю установщик…")
                        app.startActivity(install)
                    } else if (notificationsAllowed(app)) {
                        ready(app, install, release.version)
                    }
                    // Приложение могло вернуться на экран, пока шло уведомление,
                    // — оно остаётся в шторке, его и нажмут.
                }
                .onFailure {
                    _progress.value = null
                    say("Не скачалось: ${it.message}")
                    if (!visible && notificationsAllowed(app)) failed(app, it.message.orEmpty())
                }
        }
    }

    /** «Скачано — нажми, чтобы установить»: нажатие отдаёт APK системе. */
    private fun ready(context: Context, install: Intent, version: String) {
        val tap = PendingIntent.getActivity(
            context, 0, install,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            context,
            NotificationCompat.Builder(context, channel(context))
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Версия $version скачана")
                .setContentText("Нажми, чтобы установить")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
        )
    }

    private fun failed(context: Context, why: String) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            context,
            NotificationCompat.Builder(context, channel(context))
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Обновление не скачалось")
                .setContentText(why)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }

    private fun post(context: Context, n: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(READY_ID, n)
    }

    /**
     * Канал свой: важность высокая — «скачано» должно всплыть, а не тихо лечь
     * в шторку. Каналы Android выключают по одному, и обновления не должны
     * молчать вместе с напоминаниями.
     */
    internal fun channel(context: Context): String {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Обновление приложения", NotificationManager.IMPORTANCE_HIGH)
        )
        return CHANNEL
    }

    private const val CHANNEL = "update"
    private const val READY_ID = 41
}

/**
 * Служба переднего плана на время скачивания обновления (0.51). Сама ничего
 * не делает — держит процесс и показывает
 * процент в шторке; работа в [UpdateWork]. Скачалось — уходит сама.
 */
class UpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false
    private var shown = -2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification(UpdateWork.progress.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!watching) {
            watching = true
            scope.launch {
                UpdateWork.progress.collect { part ->
                    if (part == null) {
                        ServiceCompat.stopForeground(this@UpdateService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val percent = (part * 100).toInt()
                        if (percent != shown) {
                            shown = percent
                            getSystemService(NotificationManager::class.java).notify(ID, notification(part))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(part: Float?): Notification {
        val percent = ((part ?: 0f) * 100).toInt()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Процент — тихим каналом записи: высокая важность нужна только
        // готовому «нажми, чтобы установить», а не каждому проценту.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS, "Скачивание обновления", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, PROGRESS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Качаю версию ${UpdateWork.version}")
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val ID = 42
        const val PROGRESS = "update_progress"
    }
}
