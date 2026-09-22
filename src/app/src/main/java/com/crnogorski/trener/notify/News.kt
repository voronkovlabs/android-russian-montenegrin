package com.crnogorski.trener.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.crnogorski.trener.BuildConfig
import com.crnogorski.trener.MainActivity
import com.crnogorski.trener.R

/**
 * «Обновилось — вот что нового».
 *
 * Заведено 23.09.2026 по просьбе владельца, рядом с уведомлением о разобранной
 * жалобе и по тому же доводу: **человек ставит сборку и не знает, что в ней**.
 * Выпусков по три в день, и Катя с Володей до сих пор обновлялись вслепую.
 *
 * ## Показывается после установки, а не до
 *
 * До установки про новости говорит строка на главном, под «Есть версия 3.4 ·
 * 58 МБ», — и уведомления там нет намеренно: подгонять человека обновляться
 * невежливо, качать ему шестьдесят мегабайт или нет, он решает сам.
 *
 * А вот первый запуск новой версии — это **событие**, и тут уведомление
 * уместно ровно так же, как «твою жалобу разобрали».
 *
 * ## Не о каждой версии
 *
 * Новости берутся из тела релиза, и кладут их туда руками при выпуске
 * (`release.py --news`). Нет текста — нет уведомления. Иначе при трёх выпусках
 * в день человек за неделю приучился бы их не читать, и тогда они не сработают
 * там, где действительно нужны.
 *
 * ## Свой канал
 *
 * Третий, отдельно от напоминания и от ответов на жалобы. По той же причине,
 * по которой разведены первые два: каналы Android выключают по одному, и
 * «устал от вечерних напоминаний» не должно означать «не хочу знать, что
 * изменилось в приложении».
 *
 * ## Один раз на версию
 *
 * Отметка — имя версии, о которой уже рассказали. Проверка идёт при каждом
 * запуске (обновление и так спрашивается тогда же), а уведомление приходит
 * ровно однажды.
 */
object News {

    private const val CHANNEL = "news"
    private const val ID = 4100
    private const val PREFS = "crnogorski"
    private const val KEY = "news_told"

    /**
     * Рассказать про [news], если это новости **нашей** версии и мы их ещё не
     * рассказывали.
     *
     * Условие «нашей» существенно: тот же запрос отвечает и про версию, до
     * которой человек ещё не обновился, — про неё говорит строка на главном, а
     * уведомление было бы обещанием того, чего у него нет.
     */
    fun tell(context: Context, version: String, news: String) {
        if (news.isBlank()) return
        if (version != BuildConfig.VERSION_NAME) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, "") == version) return

        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        // Отметку ставим и без разрешения: показать всё равно не выйдет, а
        // копить долг из неспрошенных версий незачем — при трёх выпусках в
        // день он вылился бы пачкой, как только разрешение дадут.
        prefs.edit().putString(KEY, version).apply()
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Что нового", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Что изменилось в свежей версии" }
        )

        val open = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        NotificationManagerCompat.from(context).notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Обновилось до $version")
                .setContentText(news)
                .setStyle(NotificationCompat.BigTextStyle().bigText(news))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}
