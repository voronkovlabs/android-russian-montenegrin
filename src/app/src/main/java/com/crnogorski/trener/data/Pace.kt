package com.crnogorski.trener.data

import android.content.Context
import java.time.LocalDate

/**
 * Сколько времени уходит на задание и сколько его потрачено сегодня.
 *
 * Нужно ежедневному заданию: человек просил ограничивать занятие не числом
 * упражнений, а минутами, — а 20 заданий на выбор варианта и 20 на чтение
 * вслух это очень разное время. Значит время надо мерить.
 *
 * Меряется оно по типам заданий, а не по каждому заданию отдельно: внутри
 * типа разброс невелик, а по типам он в разы, и статистики на десять типов
 * набирается за пару занятий вместо года.
 *
 * ### Почему среднее, а не медиана
 *
 * Медиана честнее при выбросах, но требует хранить выборку. Здесь вместо неё
 * выбросы срезаются на входе ([SAMPLE_MAX]): телефон кладут экраном вверх и
 * уходят, и одно такое измерение перекосило бы среднее навсегда. Обрезанное
 * среднее по двум числам (счётчик и сумма) для нашей задачи не хуже.
 *
 * ### Почему счётчик не растёт бесконечно
 *
 * После [MEMORY] измерений среднее перестало бы двигаться вовсе, а темп
 * меняется: то же задание через полгода делается вдвое быстрее. Поэтому при
 * достижении потолка сумма и счётчик делятся пополам — старое ещё влияет, но
 * уступает новому.
 *
 * ### Априорная оценка
 *
 * Пока измерений нет, берётся [DEFAULTS] — прикидка на глаз. Она не
 * выбрасывается после первого же замера, а входит в среднее с весом
 * [PRIOR] измерений: одно случайное «ответил за две секунды» иначе сделало бы
 * весь тип двухсекундным, и первое ежедневное задание вышло бы на сотню
 * упражнений.
 */
class Pace(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Сколько минут в день человек назначил себе. */
    var minutes: Int
        get() = prefs.getInt(KEY_MINUTES, DEFAULT_MINUTES)
        set(value) {
            prefs.edit().putInt(KEY_MINUTES, value.coerceIn(MIN_MINUTES, MAX_MINUTES)).apply()
        }

    /** Сколько секунд занимает задание такого типа — по замерам или по прикидке. */
    fun seconds(type: String): Double {
        val prior = DEFAULTS[type] ?: DEFAULT_SECONDS
        val n = prefs.getInt(countKey(type), 0)
        val sum = prefs.getFloat(sumKey(type), 0f).toDouble()
        return (prior * PRIOR + sum) / (PRIOR + n)
    }

    /**
     * Записать, сколько заняло задание.
     *
     * Слишком короткое не считаем вовсе: это не «быстро ответил», а промах
     * мимо кнопки или второе нажатие. Слишком длинное обрезаем, а не
     * выбрасываем: человек действительно думал, просто неизвестно, сколько
     * из этого думал именно над заданием.
     */
    fun record(type: String, seconds: Double) {
        if (seconds < SAMPLE_MIN) return
        val value = seconds.coerceAtMost(SAMPLE_MAX)
        var n = prefs.getInt(countKey(type), 0)
        var sum = prefs.getFloat(sumKey(type), 0f)
        if (n >= MEMORY) {
            n /= 2
            sum /= 2f
        }
        prefs.edit()
            .putInt(countKey(type), n + 1)
            .putFloat(sumKey(type), sum + value.toFloat())
            .apply()
    }

    /**
     * Потраченные сегодня секунды.
     *
     * По календарной дате, как и дневная норма слов: «сегодня» человек
     * понимает как день, а не как последние 24 часа.
     */
    fun spentToday(): Int =
        if (prefs.getString(KEY_DAY, "") == today()) prefs.getInt(KEY_SPENT, 0) else 0

    /**
     * Отметить потраченное время.
     *
     * Считается **любое** занятие, а не только ежедневное задание: если урок
     * прошли руками из вкладки, время потрачено на язык, и требовать сверх
     * него ещё пятнадцать минут было бы враньём. По той же причине обрезка
     * та же, что у замера, — забытое на столе приложение не должно закрывать
     * день само.
     */
    fun spend(seconds: Double) {
        if (seconds < SAMPLE_MIN) return
        val value = seconds.coerceAtMost(SAMPLE_MAX).toInt()
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_SPENT, spentToday() + value)
            .apply()
    }

    /** Сколько секунд дневного бюджета ещё не потрачено. */
    fun leftToday(): Int = (minutes * 60 - spentToday()).coerceAtLeast(0)

    private fun today(): String = LocalDate.now().toString()

    private fun countKey(type: String) = "pace_n_" + type

    private fun sumKey(type: String) = "pace_s_" + type

    companion object {
        // Тот же файл настроек, что у словаря и копии прогресса.
        private const val PREFS = "crnogorski"
        private const val KEY_MINUTES = "daily_minutes"
        private const val KEY_DAY = "daily_day"
        private const val KEY_SPENT = "daily_spent"

        /**
         * Пятнадцать минут в день.
         *
         * Не круглое число наугад: на этом сходятся и практика интервальных
         * повторений, и исследования распределённой практики — пятнадцать
         * минут каждый день дают больше, чем два часа раз в неделю. Важна
         * тут регулярность, а не длина: из «разнесённое лучше собранного в
         * кучу» не следует, что тридцать минут вредны. Пятнадцать выбраны
         * как то, что реально делается каждый день.
         */
        const val DEFAULT_MINUTES = 15
        const val MIN_MINUTES = 5
        const val MAX_MINUTES = 60

        /** Меньше этого — не ответ, а промах по кнопке. */
        private const val SAMPLE_MIN = 1.5

        /**
         * Потолок одного измерения.
         *
         * Полторы минуты хватает на самое долгое задание — чтение вслух
         * тремя предложениями. Всё, что дольше, это отложенный телефон.
         */
        private const val SAMPLE_MAX = 90.0

        /** Вес прикидки в среднем — в измерениях. */
        private const val PRIOR = 4

        /** После скольких замеров начинаем забывать старые. */
        private const val MEMORY = 60

        private const val DEFAULT_SECONDS = 20.0

        /**
         * Прикидка по типам, в секундах. Уточняется замерами, поэтому важна
         * только первую неделю — но первую неделю важна очень: по ней
         * собирается самое первое ежедневное задание.
         */
        private val DEFAULTS = mapOf(
            "choice" to 9.0,
            "word_bank" to 22.0,
            "form" to 15.0,
            "word" to 13.0,
            // Экран пар: пять слов за раз, и искать их приходится глазами по
            // столбцу. Это не пять отдельных заданий по тринадцать секунд, но
            // и не одно.
            "match" to 40.0,
            "listening" to 26.0,
            "speaking" to 18.0,
            "repeat" to 16.0,
            "reading" to 55.0,
            "ru_to_me" to 38.0,
            "me_to_ru" to 32.0,
            // Отрезок истории: не задание урока, но время тоже съедает.
            "story" to 30.0
        )
    }
}
