package com.crnogorski.trener.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.crnogorski.trener.data.DayStatEntity

/**
 * Отчёт по занятиям: сегодня, всего, тридцать дней, курс.
 *
 * Экран открывается по нажатию на строку под заголовком главного и живёт по
 * тем же правилам, что настройки: отдельный поток состояния в `AppViewModel`,
 * развилка ручным `if` в `MainActivity`, системная «назад» возвращает домой.
 *
 * Всё, что здесь показано, посчитано при открытии (`AppViewModel.openStats`), а
 * не держится наготове: тут и все карточки, и файлы всех уроков, и словарь.
 */
@Composable
fun StatsScreen(state: StatsState, onClose: () -> Unit) {
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Muted
                )
            }
        }

        if (state.loading) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator(color = Accent) }
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text("ОТЧЁТ", style = MaterialTheme.typography.labelSmall, color = Accent)
            Spacer(Modifier.height(6.dp))
            Text("Занятия", style = MaterialTheme.typography.displaySmall, color = Paper)
            Spacer(Modifier.height(24.dp))

            DayCard("Сегодня", state.today)
            Spacer(Modifier.height(12.dp))
            DayCard("Всего", state.total)

            Spacer(Modifier.height(12.dp))
            StreakCard(state.streak, state.bestStreak, state.today.active)

            Spacer(Modifier.height(28.dp))
            Section("Тридцать дней")
            DaysChart(state.days)
            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    append("Занимались ${state.activeDays} ")
                    append(plural(state.activeDays, "день", "дня", "дней"))
                    append(" из ${state.days.size}")
                    val best = state.days.maxOfOrNull { minutesOf(it.seconds) } ?: 0
                    if (best > 0) append(" · больше всего за день $best мин")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )

            Spacer(Modifier.height(28.dp))
            Section("Куда уходит время")
            TimeSplit(state.total)

            Spacer(Modifier.height(28.dp))
            Section("Курс и словарь")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Line("Уроков закрыто", "${state.lessonsDone} из ${state.lessonsTotal}")
                Line("Заданий пройдено", "${state.exercisesDone} из ${state.exercisesTotal}")
                Line(
                    "Слов заведено",
                    "${state.wordsIntroduced} из ${state.wordsTotal}, выучено ${state.wordsLearned}"
                )
                Line(
                    "Просрочено сейчас",
                    "${state.dueLessons} в уроках, ${state.dueWords} в словах"
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * Плашка дня: время крупно, остальное строкой под ним.
 *
 * Одна и та же форма для «сегодня» и «всего» — числа сравнимые, и разводить их
 * по разным вёрсткам значило бы мешать их сравнивать.
 */
@Composable
private fun DayCard(title: String, day: DayStatEntity) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .padding(16.dp)
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                timeText(day.seconds),
                style = MaterialTheme.typography.headlineSmall,
                color = Paper
            )
            Spacer(Modifier.weight(1f))
            if (day.answers > 0) {
                Text(
                    "${day.answers} ${plural(day.answers, "ответ", "ответа", "ответов")} · " +
                        "${day.correct * 100 / day.answers}% верно",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            listOfNotNull(
                count(day.lessons, "урок", "урока", "уроков"),
                count(day.sessions, "занятие", "занятия", "занятий"),
                count(day.words, "новое слово", "новых слова", "новых слов"),
                count(day.chunks, "отрезок", "отрезка", "отрезков")
            ).joinToString(" · ").ifBlank { "Пока пусто" },
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

/**
 * Серия дней подряд.
 *
 * Появилась в 1.40 по решению владельца: геймификация в проекте не запрещена,
 * без неё просто обходились. Своя плашка, а не строка в общем списке, потому
 * что смотрят на неё иначе, чем на остальные числа: не «сколько сделано», а
 * «не порвалось ли».
 *
 * **Сегодняшний пропуск серию ещё не рвёт** — день не кончился. Отсюда вторая
 * строка: она прямо говорит, засчитан ли сегодняшний день, чтобы «5 дней
 * подряд» вечером не оказалось приятной неправдой.
 *
 * Рекорд показывается только когда он больше нынешней серии: иначе плашка
 * дважды повторяла бы одно число.
 */
@Composable
private fun StreakCard(streak: Int, best: Int, todayDone: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("ПОДРЯД", style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.height(10.dp))
            Text(
                if (streak > 0) "$streak ${plural(streak, "день", "дня", "дней")}"
                else "Серии пока нет",
                style = MaterialTheme.typography.headlineSmall,
                color = if (streak > 0) Accent else Muted
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    todayDone -> "Сегодняшний день засчитан."
                    streak > 0 -> "Сегодня ещё не занимались — до полуночи серия держится."
                    else -> "Серия начнётся с первого занятия."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
        if (best > streak) {
            Text(
                "лучшая $best",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
    }
}

/**
 * График за тридцать дней: минуты столбиками, закрытые уроки — точками.
 *
 * Уроки не второй кривой и не второй шкалой, и это не упрощение. Урок
 * закрывается раз в несколько дней — это события, а не непрерывная величина:
 * линия из них лежала бы по нулю, а вторая шкала на том же поле читалась бы
 * как связь там, где её нет. Точка над столбиком говорит ровно то, что есть, —
 * «в этот день закрыли урок».
 *
 * Пустые дни рисуются нулевой высотой, но остаются на месте: без них график
 * склеивал бы пропуски и врал бы о том, что занимались каждый день.
 *
 * Цвета читаются до `Canvas`: внутри него композабельных геттеров темы уже нет.
 */
@Composable
private fun DaysChart(days: List<DayStatEntity>) {
    val bar = Accent
    val base = Surface2
    val mark = Jade
    val top = days.maxOfOrNull { minutesOf(it.seconds) }?.coerceAtLeast(1) ?: 1

    Column {
        Text(
            "$top мин",
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            if (days.isEmpty()) return@Canvas
            val gap = 2.dp.toPx()
            val slot = (size.width + gap) / days.size
            val width = (slot - gap).coerceAtLeast(1f)
            // Место под точку урока над самым высоким столбиком.
            val room = 10.dp.toPx()
            val floor = size.height - 1.dp.toPx()

            drawRect(
                color = base,
                topLeft = Offset(0f, floor),
                size = Size(size.width, 1.dp.toPx())
            )
            days.forEachIndexed { i, day ->
                val x = i * slot
                val minutes = minutesOf(day.seconds)
                val high = (floor - room) * minutes / top
                if (minutes > 0) {
                    drawRect(
                        color = bar,
                        topLeft = Offset(x, floor - high),
                        size = Size(width, high)
                    )
                }
                if (day.lessons > 0) {
                    drawCircle(
                        color = mark,
                        radius = 3.dp.toPx(),
                        center = Offset(x + width / 2, floor - high - room / 2)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                "${days.size} дней назад",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
            Spacer(Modifier.weight(1f))
            Text("сегодня", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Столбик — минуты за день, точка — день, в который закрыт урок.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

/**
 * Куда уходит время: одна полоска на четыре доли.
 *
 * Полоска, а не четыре числа: доли складываются в целое, и увидеть перекос
 * («повторение съело месяц») надо одним взглядом, а не сравнивая проценты
 * в столбик.
 */
@Composable
private fun TimeSplit(total: DayStatEntity) {
    val parts = listOf(
        Triple("Уроки", total.lessonSeconds, Accent),
        Triple("Повторение", total.reviewSeconds, Jade),
        Triple("Слова", total.wordSeconds, Paper),
        Triple("Истории", total.storySeconds, Muted)
    )
    val sum = total.seconds
    if (sum <= 0) {
        Text("Пока не за что зацепиться.", style = MaterialTheme.typography.bodyMedium, color = Muted)
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
    ) {
        parts.forEach { (_, seconds, color) ->
            if (seconds > 0) Share(color, seconds.toFloat() / sum)
        }
    }
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { (name, seconds, _) ->
            Line(name, "${timeText(seconds)} · ${seconds * 100 / sum}%")
        }
    }
}

/** Доля полоски: расширение RowScope, иначе `weight` внутри `forEach` не достать. */
@Composable
private fun RowScope.Share(color: Color, share: Float) {
    Spacer(
        Modifier
            .weight(share)
            .fillMaxHeight()
            .background(color)
    )
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Line(name: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Paper)
    }
}

/** «4 ч 10 мин», «12 мин», «—» — часы появляются только когда они есть. */
fun timeText(seconds: Int): String {
    val minutes = minutesOf(seconds)
    return when {
        minutes <= 0 -> "—"
        minutes < 60 -> "$minutes мин"
        minutes % 60 == 0 -> "${minutes / 60} ч"
        else -> "${minutes / 60} ч ${minutes % 60} мин"
    }
}

/** Ноль не показываем вовсе: строка «0 уроков» сообщает меньше, чем её отсутствие. */
private fun count(n: Int, one: String, few: String, many: String): String? =
    if (n <= 0) null else "$n ${plural(n, one, few, many)}"

/** Русский счёт: 1 урок, 2 урока, 5 уроков. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val tens = n % 100
    if (tens in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
