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
import com.crnogorski.trener.data.ComplaintStore
import com.crnogorski.trener.data.Config
import com.crnogorski.trener.data.Journal
import com.crnogorski.trener.data.Pace
import com.crnogorski.trener.data.VocabKind
import com.crnogorski.trener.data.VocabRepository
import com.crnogorski.trener.srs.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.crnogorski.trener.data.WidgetWord
import com.crnogorski.trener.data.WidgetWords
import com.crnogorski.trener.data.WordPicture
import com.crnogorski.trener.speech.Speaker
import com.crnogorski.trener.ui.KnownActivity
import com.crnogorski.trener.ui.NoteActivity

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
                Config.load(context)
                tap(context, missed = true)
                study(context)
            }
            ACTION_RECALLED -> {
                // Слово берётся до перехода: отмечается то, что было на
                // обороте, а не следующее. Кнопка живёт только на обороте,
                // поэтому переход здесь — всегда «следующее слово».
                val word = WidgetWords.current(context, WidgetWords.FLIP) ?: return
                Config.load(context)
                if (side(context) == 1) tap(context, missed = false)
                study(context, recalled = word)
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
     * **Мерится время между нажатиями, и только внутри захода.** Промежуток
     * дольше порога простоя (`daily.idleSeconds`, десять секунд) не
     * засчитывается **вовсе**: такое нажатие не продолжает работу, а начинает
     * новую, и счёт с него только стартует. Решение владельца (4.9): «начинать
     * засчитывать время можно только после первого нажатия». До 4.9 длинный
     * промежуток давал порог целиком — по правилу `Touch` в приложении, — и
     * каждое мимоходом нажатое слово записывало в занятие десять секунд,
     * которых не было.
     *
     * В приложении правило другое намеренно: там задание уже на экране и над
     * ним думают, а здесь до первого нажатия человек мог и не смотреть.
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
    private fun study(context: Context, recalled: WidgetWord? = null) {
        Config.load(context)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_TAP, 0L)
        val limit = Config.current.daily.idleSeconds.takeIf { it > 0 } ?: DEFAULT_IDLE
        val gap = if (last == 0L) 0.0 else (now - last) / 1000.0
        // Дольше порога — это не раздумье, а начало нового захода: ноль.
        val step = if (gap > limit) 0.0 else gap
        val pending = prefs.getFloat(KEY_PENDING, 0f) + step
        val flush = pending >= FLUSH_SECONDS
        prefs.edit()
            .putLong(KEY_LAST_TAP, now)
            .putFloat(KEY_PENDING, if (flush) 0f else pending.toFloat())
            .apply()
        val spent = if (flush) Pace(context).spend(pending) else 0
        if (spent <= 0 && recalled == null) return
        // Одна запись на нажатие, и одна `goAsync`: второй раз приёмник её
        // не выдаёт.
        val answer = if (recalled != null) 1 else 0
        val done = goAsync()
        queue.launch {
            try {
                recalled?.let { remember(context, it, now) }
                AppDb.get(context).dao().bumpDay(
                    day = LocalDate.now().toString(),
                    lessonSeconds = 0,
                    reviewSeconds = 0,
                    wordSeconds = spent,
                    storySeconds = 0,
                    answers = answer,
                    correct = answer,
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

    /**
     * «Вспомнил» — записать верный ответ по карточке значения (4.11).
     *
     * Карточка именно значения (`mean`): она и спрашивает «аптека» →
     * `apoteka`, то есть ровно то, что делает перевёртыш — русское спереди,
     * вспомнить черногорское. Как её двигать, решает [Scheduler.recalled] —
     * по тем же правилам, что занятие в приложении.
     *
     * В журнал ответ уходит с видом `widget`: он отвечает на «что обкатано
     * живым пользованием», и отличать ответы с домашнего экрана от ответов в
     * занятии там нужно — верность первых держится на слове человека.
     */
    private suspend fun remember(context: Context, word: WidgetWord, now: Long) {
        if (word.lemma.isEmpty()) return
        val dao = AppDb.get(context).dao()
        val id = VocabRepository.cardId(word.lemma, VocabKind.Meaning)
        dao.upsertCard(Scheduler.recalled(dao.card(id), id, VocabRepository.LESSON_ID, now))
        Journal.note(
            context,
            unit = Journal.VOCAB,
            kind = "widget",
            ok = true,
            who = ComplaintStore(context).deviceId()
        )
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
        private const val ACTION_RECALLED = "com.crnogorski.trener.FLIP_RECALLED"
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

            // На экране оборот — полная перерисовка его трогать не вправе
            // (4.47, жалоба Кати: после 🧠 мелькало следующее черногорское
            // слово). Полная заполняла обе стороны разом, видимый оборот
            // успевал показать **следующий** ответ, и лишь потом карточка
            // переворачивалась. То же правило, что у перехода по нажатию
            // (жалоба 165, 4.10): меняется только скрытая сторона, оборот
            // заполнится при перевороте на него.
            if (word != null && side(context) == 1) {
                setSide(context, 0)
                val views = RemoteViews(context.packageName, R.layout.flip_widget)
                fillFront(context, views, word)
                views.setDisplayedChild(R.id.flip_card, 0)
                AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
                return
            }
            setSide(context, 0)

            val views = RemoteViews(context.packageName, R.layout.flip_widget)
            if (word == null) {
                // Как у простой карточки: пусто до первого запуска приложения
                // на новой сборке, и нажатие ведёт туда, а не «дальше».
                views.setTextViewText(
                    R.id.flip_ru,
                    "Crnogorski: слова появятся, когда откроешь приложение"
                )
                views.setViewVisibility(R.id.flip_known, View.GONE)
                views.setViewVisibility(R.id.flip_note, View.GONE)
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
         *
         * **Меняется только скрытая сторона** (жалоба 165, 4.10). До этого
         * переход к следующему слову заполнял обе стороны разом, пока на экране
         * ещё стоял черногорский оборот, — и уходящая сторона успевала
         * мелькнуть **следующим** черногорским словом, то есть ответом раньше
         * вопроса. Теперь переход трогает только русскую сторону (она в этот
         * момент скрыта), а оборот заполняется при перевороте на него — тоже
         * пока он скрыт. Видимая сторона не меняется никогда.
         */
        private fun tap(context: Context, missed: Boolean) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val current = WidgetWords.current(context, WidgetWords.FLIP)
            if (current == null) {
                full(context, advance = false)
                return
            }
            val views = RemoteViews(context.packageName, R.layout.flip_widget)
            if (side(context) == 0) {
                setSide(context, 1)
                fillBack(context, views, current)
                views.setDisplayedChild(R.id.flip_card, 1)
            } else {
                // Дальше без ✅ — «не вспомнил»: совсем новое слово встанет в
                // очередь повтора (4.43, [WidgetWords.next]).
                val vocab = Config.current.vocab
                val next = WidgetWords.next(context, missed, vocab.replayGap, vocab.replays) ?: return
                setSide(context, 0)
                fillFront(context, views, next)
                views.setDisplayedChild(R.id.flip_card, 0)
            }
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
        }

        /** Обе стороны и кнопка «знаю» — под одно слово. */
        private fun fill(context: Context, views: RemoteViews, word: WidgetWord) {
            fillFront(context, views, word)
            fillBack(context, views, word)
        }

        /**
         * Русская сторона и кнопка «знаю». Кнопка стоит вне переворота и
         * относится к слову, а не к стороне, — поэтому меняется вместе с
         * лицевой, когда приходит новое слово.
         */
        private fun fillFront(context: Context, views: RemoteViews, word: WidgetWord) {
            views.setTextViewText(R.id.flip_ru, word.gloss)
            views.setViewVisibility(R.id.flip_note, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.flip_note, note(context, word))
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

        /** Черногорский оборот: слово, эмодзи, перевод для сверки, 🔊. */
        private fun fillBack(context: Context, views: RemoteViews, word: WidgetWord) {
            views.setTextViewText(R.id.flip_word, WordWidget.stressed(context, word))
            views.setTextViewText(R.id.flip_gloss, word.gloss)
            views.setOnClickPendingIntent(R.id.flip_say, sayIntent(context, word))
            // Намерение без слова внутри: отмечается то, что под курсором в
            // момент нажатия, а на обороте всегда оно.
            views.setOnClickPendingIntent(
                R.id.flip_recalled,
                PendingIntent.getBroadcast(
                    context,
                    4,
                    Intent(context, FlipWidget::class.java).setAction(ACTION_RECALLED),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            // Нарисованная картинка (4.41) — на месте эмодзи; нет её или не
            // прочиталась — эмодзи, как раньше.
            val picture = word.picture.takeIf { it.isNotEmpty() }
                ?.let { WordPicture.bitmap(context, it) }
            if (picture != null) {
                views.setViewVisibility(R.id.flip_emoji, View.GONE)
                views.setViewVisibility(R.id.flip_picture, View.VISIBLE)
                views.setImageViewBitmap(R.id.flip_picture, picture)
            } else if (word.emoji.isEmpty()) {
                views.setViewVisibility(R.id.flip_emoji, View.GONE)
                views.setViewVisibility(R.id.flip_picture, View.GONE)
            } else {
                views.setViewVisibility(R.id.flip_picture, View.GONE)
                views.setViewVisibility(R.id.flip_emoji, View.VISIBLE)
                views.setTextViewText(R.id.flip_emoji, word.emoji)
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

        /**
         * Жалоба на слово — окно поверх домашнего экрана, как и «уже знаю»:
         * текст надо набрать, а виджет полей ввода не умеет.
         */
        private fun note(context: Context, word: WidgetWord): PendingIntent =
            PendingIntent.getActivity(
                context,
                5,
                Intent(context, NoteActivity::class.java)
                    .putExtra(NoteActivity.LEMMA, word.lemma)
                    .putExtra(NoteActivity.WORD, word.word)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
