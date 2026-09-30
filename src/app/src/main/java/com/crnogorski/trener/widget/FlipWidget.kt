package com.crnogorski.trener.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import com.crnogorski.trener.MainActivity
import com.crnogorski.trener.R
import com.crnogorski.trener.data.AppDb
import com.crnogorski.trener.data.Config
import com.crnogorski.trener.data.Pace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.crnogorski.trener.data.WidgetWord
import com.crnogorski.trener.data.WidgetWords
import com.crnogorski.trener.speech.Speaker
import com.crnogorski.trener.ui.KnownActivity

/**
 * Карточка-перевёртыш на домашнем экране (4.7).
 *
 * Владелец, после первой недели с [WordWidget]: «сначала показывает русское
 * значение, а потом по нажатию черногорское. Должен быть какой-то эффект
 * переворачивания карточки. Флагом страны в углу показывать язык». Это уже не
 * мелькание, а припоминание — то самое, от которого в первом виджете
 * отказались, чтобы он оставался незанятием. Потому и виджет второй, а не
 * переделанный первый: у них разные задачи, и ставят их по желанию.
 *
 * Ответ по-прежнему никуда не пишется — оценить себя тут нечем, кроме
 * собственной честности. Пишет виджет ровно одно: отметку «уже знаю» (🧠), и
 * только через подтверждение ([KnownActivity]).
 *
 * ## Переворот
 *
 * Настоящего 3D виджеты не умеют: `RemoteViews` не принимает своих анимаций.
 * Две стороны лежат в `ViewFlipper`, а смена стороны идёт его штатными
 * анимациями `flip_out` и `flip_in` — сжатие по ширине в полоску и раскрытие
 * уже другой стороной. Глаз принимает это за поворот.
 *
 * Анимация играет только при **частичном** обновлении
 * (`partiallyUpdateAppWidget`): полное пересоздаёт виджет с нуля, и сменить
 * сторону там не у чего. Поэтому нажатие шлёт одну-две команды, а не всю
 * карточку.
 *
 * ## Два нажатия на слово
 *
 * Первое переворачивает на черногорское, второе даёт следующее слово —
 * снова русской стороной, и тоже переворотом. Какая сторона сейчас видна,
 * лаунчер не сообщает, поэтому она помнится в настройках.
 *
 * Тик системы (раз в полчаса) ставит новое слово **русской стороной**:
 * вернувшись к телефону, человек должен увидеть вопрос, а не ответ.
 */
class FlipWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> full(context, advance = true)
            ACTION_TAP -> {
                tap(context)
                study(context)
            }
            ACTION_REDRAW -> full(context, advance = false)
            ACTION_SAY -> say(context, intent.getStringExtra(EXTRA_WORD).orEmpty())
            else -> super.onReceive(context, intent)
        }
    }

    /**
     * Произнести слово — тем же [Speaker], что в приложении, с той же картой
     * выговора (`Vygovor`): голос виджета не должен учить другому
     * произношению, чем занятие.
     *
     * Движок синтеза поднимается асинхронно, поэтому приёмник держится через
     * [goAsync] до конца фразы и отпускается сразу за ней. Сторож на восемь
     * секунд — на случай, если движок не ответит вовсе: приёмнику отведено
     * около десяти, и зависший движок не должен уронить процесс.
     */
    private fun say(context: Context, word: String) {
        if (word.isEmpty()) return
        Config.load(context)
        val done = goAsync()
        val speaker = Speaker(context)
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                speaker.release()
                done.finish()
            }
        }
        main.postDelayed(finish, SAY_LIMIT_MS)
        speaker.speak(word) { main.post(finish) }
    }

    /**
     * Засчитать работу с карточкой в занятие (4.8).
     *
     * Владелец: «при активной работе с карточкой это время можно засчитывать в
     * занятия». Первый виджет по-прежнему не засчитывает ничего — там слова
     * мелькают сами, а здесь человек вспоминает и переворачивает.
     *
     * **Мерится время между нажатиями, но не больше порога простоя**
     * (`daily.idleSeconds`, десять секунд) — ровно то правило, по которому
     * `Touch` вычитает простой из заданий в приложении. Над словом думают
     * молча, и десять секунд тишины — это раздумье; дольше отличить раздумье
     * от телефона, отложенного на стол, нечем, и за одно молчание платим не
     * больше порога. Иначе нажатие утром после вечернего записало бы в
     * занятие ночь.
     *
     * **Копится, а не пишется каждым нажатием.** `Pace.spend` отбрасывает
     * всё короче полутора секунд — так в занятии отсекается мгновенный
     * пропуск, — а быстрое «перевернул — следующее» как раз короче. Поэтому
     * набегающее лежит в настройках и уходит, когда наберётся
     * [FLUSH_SECONDS]: счёт не теряет быстрых нажатий, а база не пишется на
     * каждое.
     *
     * Время уходит туда же, куда время словаря в приложении: в дневную норму
     * (`Pace`) и в отчёт (`day_stats.wordSeconds`). Ответов при этом не
     * прибавляется — их не было: ни верности, ни ошибки виджет не знает.
     * День с одной только работой в виджете продлевает серию: занятие было.
     */
    private fun study(context: Context) {
        Config.load(context)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_TAP, 0L)
        val limit = Config.current.daily.idleSeconds.takeIf { it > 0 } ?: DEFAULT_IDLE
        val gap = if (last == 0L) 0.0 else (now - last) / 1000.0
        val pending = prefs.getFloat(KEY_PENDING, 0f) + gap.coerceIn(0.0, limit.toDouble())
        if (pending < FLUSH_SECONDS) {
            prefs.edit().putLong(KEY_LAST_TAP, now).putFloat(KEY_PENDING, pending.toFloat()).apply()
            return
        }
        prefs.edit().putLong(KEY_LAST_TAP, now).putFloat(KEY_PENDING, 0f).apply()
        val spent = Pace(context).spend(pending)
        if (spent <= 0) return
        val done = goAsync()
        queue.launch {
            try {
                AppDb.get(context).dao().bumpDay(
                    day = LocalDate.now().toString(),
                    lessonSeconds = 0,
                    reviewSeconds = 0,
                    wordSeconds = spent,
                    storySeconds = 0,
                    answers = 0,
                    correct = 0,
                    lessons = 0,
                    sessions = 0,
                    chunks = 0,
                    words = 0
                )
            } finally {
                done.finish()
            }
        }
    }

    companion object {

        private const val KEY_LAST_TAP = "widget_flip_last_tap"
        private const val KEY_PENDING = "widget_flip_pending"

        /** С какого накопленного числа секунд писать в отчёт. */
        private const val FLUSH_SECONDS = 5.0

        /** Порог простоя, если в настройках курса он выключен нулём. */
        private const val DEFAULT_IDLE = 10

        /** Запись в базу переживает приёмник — `goAsync` держит его до конца. */
        private val queue = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val ACTION_TAP = "com.crnogorski.trener.FLIP_TAP"
        private const val ACTION_REDRAW = "com.crnogorski.trener.FLIP_REDRAW"
        private const val ACTION_SAY = "com.crnogorski.trener.FLIP_SAY"
        private const val EXTRA_WORD = "word"
        private const val SAY_LIMIT_MS = 8_000L

        /** Кнопка «уже знаю»: в голове уже есть — словами владельца. */
        const val KNOWN = "🧠"

        private const val PREFS = "crnogorski"

        /** Какая сторона видна: 0 — русская, 1 — черногорская. */
        private const val KEY_SIDE = "widget_flip_side"

        /**
         * Перерисовать после того, как список переписан.
         *
         * В отличие от [WordWidget.redraw], сторона сбрасывается на русскую:
         * под курсором могло оказаться уже другое слово — отмеченное «знаю»
         * ушло из списка, — и показать его оборотом значило бы выдать ответ
         * раньше вопроса.
         */
        fun redraw(context: Context) {
            if (ids(context).isEmpty()) return
            context.sendBroadcast(
                Intent(context, FlipWidget::class.java).setAction(ACTION_REDRAW)
            )
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, FlipWidget::class.java)
            )

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun side(context: Context): Int = prefs(context).getInt(KEY_SIDE, 0)

        private fun setSide(context: Context, side: Int) =
            prefs(context).edit().putInt(KEY_SIDE, side).apply()

        /** Полная перерисовка: слово русской стороной, без анимации. */
        private fun full(context: Context, advance: Boolean) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val word = if (advance) WidgetWords.advance(context, WidgetWords.FLIP)
            else WidgetWords.current(context, WidgetWords.FLIP)
            setSide(context, 0)

            val views = RemoteViews(context.packageName, R.layout.flip_widget)
            if (word == null) {
                // Как у простой карточки: пусто до первого запуска приложения
                // на новой сборке, и нажатие ведёт туда, а не «дальше».
                views.setTextViewText(
                    R.id.flip_ru,
                    context.getString(R.string.app_name) + ": слова появятся, когда откроешь приложение"
                )
                views.setViewVisibility(R.id.flip_known, View.GONE)
                views.setOnClickPendingIntent(R.id.flip_card, open(context))
            } else {
                fill(context, views, word)
                views.setOnClickPendingIntent(R.id.flip_card, tapIntent(context))
            }
            views.setDisplayedChild(R.id.flip_card, 0)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        /**
         * Нажатие на карточку — частичное обновление, чтобы сыграл переворот.
         *
         * Слова может не оказаться (список опустел, пока карточка висела) —
         * тогда полная перерисовка покажет пустое состояние.
         */
        private fun tap(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            if (WidgetWords.current(context, WidgetWords.FLIP) == null) {
                full(context, advance = false)
                return
            }
            val views = RemoteViews(context.packageName, R.layout.flip_widget)
            if (side(context) == 0) {
                setSide(context, 1)
                views.setDisplayedChild(R.id.flip_card, 1)
            } else {
                val next = WidgetWords.advance(context, WidgetWords.FLIP) ?: return
                setSide(context, 0)
                fill(context, views, next)
                views.setDisplayedChild(R.id.flip_card, 0)
            }
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
        }

        /** Обе стороны и кнопка «знаю» — под одно слово. */
        private fun fill(context: Context, views: RemoteViews, word: WidgetWord) {
            views.setTextViewText(R.id.flip_ru, word.gloss)
            views.setTextViewText(R.id.flip_word, WordWidget.stressed(context, word))
            views.setTextViewText(R.id.flip_gloss, word.gloss)
            views.setOnClickPendingIntent(R.id.flip_say, sayIntent(context, word))
            if (word.emoji.isEmpty()) {
                views.setViewVisibility(R.id.flip_emoji, View.GONE)
            } else {
                views.setViewVisibility(R.id.flip_emoji, View.VISIBLE)
                views.setTextViewText(R.id.flip_emoji, word.emoji)
            }
            // Записи, сложенные до 4.7, леммы не знают — отмечать нечего, и
            // кнопка прячется до пересборки списка при открытии приложения.
            if (word.lemma.isEmpty()) {
                views.setViewVisibility(R.id.flip_known, View.GONE)
            } else {
                views.setViewVisibility(R.id.flip_known, View.VISIBLE)
                views.setTextViewText(R.id.flip_known, KNOWN)
                views.setOnClickPendingIntent(R.id.flip_known, known(context, word))
            }
        }

        private fun tapIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, FlipWidget::class.java).setAction(ACTION_TAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Произносится показанное написание — иекавское, как на экране. */
        private fun sayIntent(context: Context, word: WidgetWord): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                3,
                Intent(context, FlipWidget::class.java)
                    .setAction(ACTION_SAY)
                    .putExtra(EXTRA_WORD, word.word),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * Отметка «знаю» открывает окно подтверждения: сам виджет диалогов
         * показывать не умеет, а случайное касание не должно менять прогресс.
         */
        private fun known(context: Context, word: WidgetWord): PendingIntent =
            PendingIntent.getActivity(
                context,
                2,
                Intent(context, KnownActivity::class.java)
                    .putExtra(KnownActivity.LEMMA, word.lemma)
                    .putExtra(KnownActivity.WORD, word.word)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
