package com.crnogorski.trener.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.crnogorski.trener.MainActivity
import com.crnogorski.trener.R
import com.crnogorski.trener.data.WidgetWord
import com.crnogorski.trener.data.WidgetWords

/**
 * Словарная карточка на домашнем экране (4.5, идея 143).
 *
 * Владелец: «слова мелькали перед глазами при обычной работе с телефоном, но
 * ничего не блокировали». Виджет поэтому ничего не спрашивает и ничего не
 * записывает — ни времени занятия, ни карточек, ни ответов, ни записи в
 * журнал. Показ, а не ответ, ровно как парадигма при первой встрече.
 *
 * ## «Меняться при каждом новом показе» — чего Android не даёт
 *
 * Системе неизвестно, что на виджет смотрят: события «виджет стал виден» в
 * платформе нет вовсе. Единственный автоматический такт — `updatePeriodMillis`,
 * и меньше получаса система не принимает, сколько ни проси.
 *
 * Зато этот такт **бесплатный**: ради виджета телефон не будится, обновление
 * ждёт, пока экран включат. Чаще можно было бы только своим будильником — до
 * девяноста шести пробуждений в день ради смены слова, — и это ровно та цена,
 * которую в проекте не платят даже за вечернее напоминание.
 *
 * Поэтому требование закрыто с другой стороны: **нажатие на виджет даёт
 * следующее слово**. Мгновенно, без открытия приложения, сколько раз нажал —
 * столько слов и увидел.
 *
 * Приложение это не отменяет: оно же кладёт сюда список и просит перерисовать,
 * так что слово, которое только что отвечали в занятии, из виджета уходит.
 *
 * ## Что показано
 *
 * Черногорское слово крупно, русское мелко. Не «сначала перевод, потом
 * открыть»: припоминание сильнее узнавания, но опроса тут быть не должно —
 * ошибку записать нечем, а виджет обещан незанятием.
 *
 * ## Почему здесь нет ни корутин, ни чтения файлов
 *
 * Всё, что нужно показать — написание, ударение, эмодзи, перевод, — лежит
 * готовым в [WidgetWords]: список складывает приложение, где словарь и так
 * разобран. Приёмнику остаётся прочитать настройки и нарисовать, то есть он
 * укладывается в синхронный код и не нуждается ни в `goAsync`, ни в своей
 * области корутин.
 */
class WordWidget : AppWidgetProvider() {

    /**
     * Обе причины обновления обслуживаются одинаково, поэтому перехват здесь,
     * а не в `onUpdate`: и тик системы, и нажатие значат «следующее слово».
     */
    override fun onReceive(context: Context, intent: Intent) {
        val advance = when (intent.action) {
            ACTION_NEXT, AppWidgetManager.ACTION_APPWIDGET_UPDATE -> true
            ACTION_REDRAW -> false
            else -> {
                super.onReceive(context, intent)
                return
            }
        }
        draw(context, advance)
    }

    companion object {

        /** Нажатие: следующее слово. */
        private const val ACTION_NEXT = "com.crnogorski.trener.WIDGET_NEXT"

        /** Просьба от приложения перерисовать, не сдвигая слово. */
        private const val ACTION_REDRAW = "com.crnogorski.trener.WIDGET_REDRAW"

        /**
         * Перерисовать виджет тем же словом.
         *
         * Зовётся приложением после того, как список переписан: слово, только
         * что отвеченное в занятии, из оборота ушло, и показывать его незачем.
         * Слово при этом **не сдвигается** — иначе каждое возвращение на
         * главный экран перелистывало бы карточку за человека.
         *
         * Виджета на экране может не быть вовсе, и это обычное дело: тогда не
         * рассылаем ничего.
         */
        fun redraw(context: Context) {
            if (ids(context).isEmpty()) return
            context.sendBroadcast(
                Intent(context, WordWidget::class.java).setAction(ACTION_REDRAW)
            )
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, WordWidget::class.java)
            )

        private fun draw(context: Context, advance: Boolean) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val word = if (advance) WidgetWords.advance(context)
            else WidgetWords.current(context)
            AppWidgetManager.getInstance(context)
                .updateAppWidget(ids, views(context, word))
        }

        private fun views(context: Context, word: WidgetWord?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.word_widget)

            if (word == null) {
                // Пустой виджет — законное состояние, а не поломка: список
                // складывает приложение, и до первого его запуска на новой
                // сборке брать слова неоткуда. Сказать об этом надо прямо,
                // иначе виджет читается как сломанный.
                //
                // И нажатие тут ведёт в приложение, а не «дальше»: листать
                // нечего, а сказано ровно то, что надо сделать.
                views.setOnClickPendingIntent(R.id.widget_root, open(context))
                views.setTextViewText(R.id.widget_word, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_gloss, "слова появятся, когда откроешь приложение")
                views.setViewVisibility(R.id.widget_emoji, View.GONE)
                return views
            }

            views.setOnClickPendingIntent(R.id.widget_root, tap(context))
            views.setTextViewText(R.id.widget_word, stressed(context, word))
            views.setTextViewText(R.id.widget_gloss, word.gloss)
            if (word.emoji.isEmpty()) {
                views.setViewVisibility(R.id.widget_emoji, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_emoji, View.VISIBLE)
                views.setTextViewText(R.id.widget_emoji, word.emoji)
            }
            return views
        }

        /**
         * Ударная буква — жирным и цветом акцента, как в самом приложении
         * (`ui/Stressed.kt`). Одной жирности на телефоне не видно, это уже
         * стоило жалобы; там оформление делает Compose, здесь спаны, а правило
         * то же: не уверены в ударении — метки нет вовсе.
         */
        internal fun stressed(context: Context, word: WidgetWord): CharSequence {
            val at = word.stress
            if (at < 0 || at >= word.word.length) return word.word
            val accent = ContextCompat.getColor(context, R.color.widget_accent)
            return SpannableString(word.word).apply {
                setSpan(StyleSpan(Typeface.BOLD), at, at + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(accent), at, at + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
        }

        private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun tap(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WordWidget::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
