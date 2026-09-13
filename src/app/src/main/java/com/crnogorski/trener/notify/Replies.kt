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
import com.crnogorski.trener.MainActivity
import com.crnogorski.trener.R
import com.crnogorski.trener.net.GithubIssues

/**
 * «Твою жалобу разобрали» — уведомление тому, кто жаловался.
 *
 * Заведено 13.09.2026 по просьбе владельца: человек пишет из приложения, что
 * задание кривое, и дальше для него ничего не происходит — issue он не видит,
 * репозиторий приватный, а починка приезжает молча внутри очередной сборки.
 * Обратной связи нет вовсе, и жаловаться со временем перестают.
 *
 * ## Почему это опрос, а не пуш
 *
 * Пуша в буквальном смысле быть не может: сервера у приложения нет, аккаунтов
 * нет, FCM не подключён. Зато приложение и так ходит в GitHub при каждом
 * запуске — за настройками, за свежим релизом, за очередью жалоб. Значит
 * нужен ещё один такой же поход, а показать можно **местное** уведомление: для
 * человека оно неотличимо от пуша, просто приходит не в ту же секунду.
 *
 * Проверяется в двух местах: при запуске приложения и на вечернем будильнике
 * напоминания. Второе не для красоты — без него ответ увидел бы только тот,
 * кто и так открыл приложение, то есть тот, кому напоминать не надо.
 *
 * ## Карта имён тут не нужна
 *
 * Казалось бы, чтобы сказать Володе про его жалобу, надо знать, что это
 * Володин телефон. Не надо: **телефон знает только свои issue**, он сам их
 * заводил и запомнил номера. Карта `people` нужна владельцу, чтобы читать
 * список issue, а приложению — нет.
 *
 * ## Что считается ответом
 *
 * Только закрытие **как выполненной** (`state_reason = completed`). Закрытых
 * без правки у нас уже было две — «посмотрели, чинить нечего», — и по одному
 * лишь факту закрытия человек получил бы «учли» там, где ничего не сделали.
 * Отсюда правило разбора: не собираюсь чинить — закрывай как `not planned`.
 * Неизвестная причина (поле пустое) ответом не считается: соврать тут хуже,
 * чем промолчать.
 *
 * Текст уведомления самодостаточен, и это вынужденно: ссылку на issue дать
 * нельзя, репозиторий приватный и доступа к нему у домашних нет. Поэтому в
 * уведомлении заголовок его же записи — он её писал, узнает, — и первая строка
 * закрывающего комментария.
 *
 * ## Чего оно не обещает
 *
 * «Разобрали», а не «уже в твоей версии». Закрытая issue и доехавшая до
 * телефона сборка — разные события, и связать их машинно нечем: версия
 * названа в тексте моего комментария, а разбирать свой же текст — гадание.
 * Поэтому уведомление говорит правду поскромнее и добавляет, что починка
 * приедет с обновлением.
 */
object Replies {

    /**
     * Запомнить заведённую issue — её потом и проверяем.
     *
     * Зовётся после **удачной** отправки. Номер до сих пор выбрасывался:
     * `GithubIssues.create` его возвращал, а нужен он был только для ошибки.
     *
     * Лежит номер в настройках, а не в `complaints-sent.jsonl`: та строка —
     * байт в байт то, что записал телефон в момент жалобы, и дописывать в неё
     * то, что случилось позже, значит испортить единственную честную копию.
     *
     * Список подрезается сверху ([MAX]): issue, которую не закроют никогда,
     * иначе лежала бы в нём вечно.
     */
    fun remember(context: Context, number: Int) {
        if (number <= 0) return
        val kept = (pending(context) + number).distinct().takeLast(MAX)
        prefs(context).edit().putString(KEY, kept.joinToString(",")).apply()
    }

    /**
     * Сходить за ответами и показать их.
     *
     * Один запрос на всё: берём последние закрытые issue репозитория и
     * оставляем свои. Спрашивать про каждую свою по отдельности значило бы
     * слать десяток запросов ради ответа «ничего не изменилось», а он такой в
     * подавляющем большинстве заходов.
     *
     * Молчит при любой неудаче: ни сети, ни прав, ни разрешения на уведомления
     * — это не повод шуметь, проверка повторится сама при следующем запуске.
     */
    suspend fun check(context: Context, issues: GithubIssues = GithubIssues()) {
        val mine = pending(context)
        if (mine.isEmpty() || !issues.configured) return

        val closed = issues.closedAmong(mine.toSet()).getOrNull() ?: return
        if (closed.isEmpty()) return

        // Показываем не больше трёх за раз: пачка уведомлений подряд читается
        // как сбой, а не как забота. Непоказанные останутся в списке и придут
        // в следующий заход.
        val done = closed.filter { it.completed }.take(SHOW)
        done.forEach { show(context, it, issues) }

        // Забываем и показанное, и закрытое без правки: второе ответом не
        // стало, но и ждать по нему больше нечего.
        val settled = done.map { it.number } + closed.filterNot { it.completed }.map { it.number }
        val left = pending(context).filterNot { it in settled }
        prefs(context).edit().putString(KEY, left.joinToString(",")).apply()
    }

    private suspend fun show(context: Context, issue: GithubIssues.Closed, issues: GithubIssues) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ответы на жалобы", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Что сделали по тому, что ты написал" }
        )

        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val what = issue.title.substringAfter("] ", issue.title).trim()
        val said = issues.lastComment(issue.number).orEmpty()
        val full = listOf(what, said, "Изменение приедет со следующим обновлением.")
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        NotificationManagerCompat.from(context).notify(
            BASE_ID + issue.number,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (issue.idea) "Твою идею сделали" else "Твою жалобу разобрали")
                .setContentText(what)
                .setStyle(NotificationCompat.BigTextStyle().bigText(full))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun pending(context: Context): List<Int> =
        prefs(context).getString(KEY, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "crnogorski"
    private const val KEY = "answers_mine"

    /**
     * Свой канал, а не общий с напоминанием.
     *
     * Каналы Android — это то, что человек выключает по отдельности: устал от
     * вечерних напоминаний, но ответ на свою жалобу увидеть хочет. В одном
     * канале выбора у него нет.
     */
    private const val CHANNEL = "answers"

    /** Сколько своих issue помним. Больше сотни незакрытых — это не наш случай. */
    private const val MAX = 100

    /** Сколько уведомлений показываем за один заход. */
    private const val SHOW = 3

    /** Номер уведомления — от номера issue; единица занята напоминанием. */
    private const val BASE_ID = 2000
}
