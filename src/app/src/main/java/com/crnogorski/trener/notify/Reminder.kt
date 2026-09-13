package com.crnogorski.trener.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.crnogorski.trener.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.crnogorski.trener.R
import com.crnogorski.trener.data.Pace
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Одно напоминание в день, вечером.
 *
 * Занятие каждый день выигрывает у занятия раз в неделю не потому, что мозг
 * не усваивает больше за раз, а потому, что раз в неделю не делают. Значит
 * главное, что может сделать приложение, — напомнить.
 *
 * **Вечер выбран не наугад.** Память на слова лучше закрепляется при обучении
 * днём, а моторный навык — при тренировке перед сном; сон сразу после занятия
 * закрепляет заметно лучше, чем сон через день бодрствования. Идеал — два
 * коротких захода, но два занятия в день выдержать труднее, чем одно, поэтому
 * по умолчанию одно и вечернее: оно покрывает произношение и остаётся близко
 * ко сну.
 *
 * **Будильник неточный** ([AlarmManager.setAndAllowWhileIdle]). Точный
 * потребовал бы `SCHEDULE_EXACT_ALARM` — разрешения, которое Android выдаёт
 * неохотно и правильно делает: напоминание, опоздавшее на девять минут,
 * остаётся напоминанием.
 *
 * **Сделанное сегодня не напоминается.** Проверяется по потраченным минутам
 * (`Pace`), а не по тому, открывали ли приложение: заниматься можно и из
 * вкладок, и это ровно то же занятие.
 */
object Reminder {

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, true)

    fun hour(context: Context): Int = prefs(context).getInt(KEY_HOUR, DEFAULT_HOUR)

    fun minute(context: Context): Int = prefs(context).getInt(KEY_MINUTE, 0)

    /** Включить, выключить или передвинуть. Перепланирует само. */
    fun set(context: Context, on: Boolean, hour: Int, minute: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ON, on)
            .putInt(KEY_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_MINUTE, minute.coerceIn(0, 59))
            .apply()
        schedule(context)
    }

    /**
     * Назначить ближайшее напоминание. Вызывать можно сколько угодно: старое
     * заменяется по тому же [PendingIntent], дублей не бывает.
     */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context)
        if (!enabled(context)) {
            alarms.cancel(intent)
            return
        }
        val now = LocalDateTime.now()
        var at = now.withHour(hour(context)).withMinute(minute(context))
            .withSecond(0).withNano(0)
        // Назначенное время сегодня уже прошло — значит завтра.
        if (!at.isAfter(now)) at = at.plusDays(1)
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            intent
        )
    }

    /**
     * Показать напоминание — если сегодня ещё не занимались и разрешение есть.
     *
     * Без разрешения молча ничего не делаем: просить его отсюда некому, а
     * падать из-за напоминания тем более незачем.
     */
    fun show(context: Context) {
        if (Pace(context).leftToday() <= 0) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ежедневное задание", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Напоминание позаниматься" }
        )
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val minutes = Pace(context).minutes
        NotificationManagerCompat.from(context).notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Crnogorski")
                .setContentText("Занятие на $minutes минут ждёт")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "crnogorski"
    private const val KEY_ON = "remind_on"
    private const val KEY_HOUR = "remind_hour"
    private const val KEY_MINUTE = "remind_minute"
    private const val CHANNEL = "daily"
    private const val ID = 1

    const val ACTION = "com.crnogorski.trener.REMIND"

    /**
     * Восемь вечера. Достаточно поздно, чтобы день уже сложился, и достаточно
     * рано, чтобы пятнадцать минут ещё нашлись.
     */
    const val DEFAULT_HOUR = 20
}

/**
 * Приёмник напоминания и перезагрузки.
 *
 * Оба случая в одном классе, потому что делают одно: назначают следующее
 * напоминание. Будильники Android не переживают перезагрузку — без
 * `BOOT_COMPLETED` напоминание молча исчезло бы после первого же выключения
 * телефона, и заметить это было бы нечем.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Reminder.ACTION) {
            Reminder.show(context)
            answers(context)
        }
        // И после показа, и после перезагрузки — назначить следующее.
        Reminder.schedule(context)
    }

    /**
     * Заодно посмотреть, не ответили ли на жалобы.
     *
     * На том же будильнике, а не своим: ещё один будильник ради одного
     * запроса в сутки — это лишняя строка в манифесте и лишний повод для
     * системы разбудить телефон. А главное, проверка при запуске приложения
     * показывает ответ только тому, кто и так его открыл, — то есть тому, кому
     * ничего напоминать не надо.
     *
     * Приёмник живёт считанные секунды, поэтому [goAsync] и свой срок:
     * не уложились — значит не уложились, проверка повторится при следующем
     * запуске. Тянуть приёмник до последнего хуже, чем не узнать сегодня.
     */
    private fun answers(context: Context) {
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { withTimeout(TIMEOUT_MS) { Replies.check(app) } }
            done.finish()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
