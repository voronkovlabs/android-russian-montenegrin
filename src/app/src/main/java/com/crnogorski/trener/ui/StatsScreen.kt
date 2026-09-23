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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.crnogorski.trener.data.CheckupEntity
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
fun StatsScreen(state: StatsState, onClose: () -> Unit, onCheckup: () -> Unit) {
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

            Showcase(state)
            Spacer(Modifier.height(12.dp))

            if (state.before.real && state.after.real) {
                BeforeAfter(state)
                Spacer(Modifier.height(12.dp))
            }
            if (state.saidThen != null && state.saidNow != null) {
                SaidCard(state.saidThen, state.saidNow)
                Spacer(Modifier.height(12.dp))
            }
            CheckupCard(state.checkups, onCheckup)
            Spacer(Modifier.height(12.dp))

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
 * Витрина: одна плашка, с которой не стыдно сделать снимок.
 *
 * Заведена 13.09.2026 по просьбе владельца — «нужна секция, где видно,
 * насколько приложение улучшает мой уровень, яркая и наглядная, чтобы сделать
 * скриншот». Всё, что на ней написано, и так лежало в отчёте, но лежало
 * строчками мелким шрифтом вперемешку с просроченными карточками: читать её
 * можно было только зная, что ищешь.
 *
 * ## Что на ней есть и чего нет
 *
 * **Уровня по CEFR тут нет и не будет.** Соблазн написать «A2» большой, а
 * права такого у приложения нет никакого: A2 — это внешняя рамка, которую
 * ставит экзамен, а не тренажёр, считающий собственные карточки. Курс
 * **составлен** по программе A1–A2, и это правда; «твой уровень A2» — уже
 * неправда, и на снимке в чужой ленте она была бы враньём не мне, а
 * посторонним людям.
 *
 * Поэтому показано то, что действительно измерено: сколько слов заведено и
 * сколько из них знается твёрдо (десять верных ответов, `VocabRepository.LEARNED`),
 * сколько курса пройдено, сколько часов и дней потрачено, какая доля ответов
 * верна. Ни одного придуманного сводного балла: «индекс владения языком» из
 * этих чисел собрать легко, и он был бы красив и бессмыслен.
 *
 * ## Почему она такая
 *
 * **Снимок уходит туда, где про приложение не знают ничего**, поэтому плашка
 * самодостаточна: на ней есть имя, язык и дата начала. Без даты числа не
 * значат ничего — «148 слов» это подвиг за месяц и позор за три года.
 *
 * Единственное крупное число — слова. Не потому, что оно самое лестное
 * (сейчас как раз нет), а потому, что вопрос «сколько ты знаешь слов» —
 * единственный вопрос про язык, который человек со стороны понимает без
 * пояснений.
 *
 * Заливка своя, с уклоном в акцент, и это тут не украшение: плашка обязана
 * отличаться от соседних стеклянных карточек, иначе на снимке она станет
 * одной из. Тон берётся из палитры, а не задаётся числом, поэтому работает в
 * обеих темах.
 */
@Composable
private fun Showcase(state: StatsState) {
    val hours = state.total.seconds / 3600.0
    val course =
        if (state.exercisesTotal > 0) state.exercisesDone.toFloat() / state.exercisesTotal else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .background(
                Brush.verticalGradient(
                    listOf(Accent.copy(alpha = 0.20f), Color.Transparent)
                )
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CRNOGORSKI",
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (state.since.isNotBlank()) {
                Text(
                    "учу с " + humanDate(state.since),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            state.wordsIntroduced.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = Paper
        )
        Text(
            "слов черногорского",
            style = MaterialTheme.typography.titleMedium,
            color = Accent
        )
        if (state.wordsLearned > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "из них ${state.wordsLearned} знаю твёрдо",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }

        Spacer(Modifier.height(18.dp))
        Bar(course)
        Spacer(Modifier.height(6.dp))
        Text(
            "Курс пройден на ${(course * 100).toInt()}% · " +
                "${state.exercisesDone} из ${state.exercisesTotal} заданий",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )

        Spacer(Modifier.height(18.dp))
        // Три колонки, а не четыре: на узком экране (Redmi 13C — 360 dp,
        // минус отступы экрана и плашки остаётся около 280) четвёртая подпись
        // переносится на вторую строку и ряд перестаёт читаться рядом.
        // Тексты вслух ушли в строку под ним — там перенос не страшен.
        Row(Modifier.fillMaxWidth()) {
            Brag("${state.lessonsDone}/${state.lessonsTotal}", "уроков")
            Brag(hoursText(hours), "занятий")
            Brag(
                "${state.daysLearned}",
                plural(state.daysLearned, "день", "дня", "дней")
            )
        }

        val tail = listOfNotNull(
            if (state.total.answers > 0) {
                "${state.total.answers} " +
                    plural(state.total.answers, "ответ", "ответа", "ответов") +
                    " · ${state.total.correct * 100 / state.total.answers}% верных"
            } else null,
            if (state.storiesDone > 0) {
                "${state.storiesDone} " +
                    plural(state.storiesDone, "текст", "текста", "текстов") +
                    " прочитано вслух"
            } else null
        )
        if (tail.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                tail.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
    }
}

/** Одно число витрины с подписью. Четыре в ряд — ширина делится поровну. */
@Composable
private fun RowScope.Brag(value: String, label: String) {
    Column(Modifier.weight(1f)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Paper)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

/**
 * Полоса пройденного курса.
 *
 * Своя, а не `LinearProgressIndicator`: тому нельзя задать скругление, а на
 * витрине полоса стоит рядом с крупным числом и прямыми углами выдаёт
 * служебный виджет посреди плаката.
 */
@Composable
private fun Bar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Surface2)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(Accent)
        )
    }
}

/** «2026-08-25» → «25 августа 2026». Для снимка дата важнее всех чисел. */
private fun humanDate(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")))
}.getOrDefault(day)

/** Часы с одним знаком, пока их мало: «7,4 ч» честнее, чем «7 ч». */
private fun hoursText(hours: Double): String =
    if (hours >= 10) "${hours.toInt()} ч"
    else String.format(Locale("ru"), "%.1f ч", hours)

/**
 * «Было — стало»: первые дни занятий против последних.
 *
 * Сравниваются **дни занятий, а не календарные недели** — тогда в обеих
 * колонках лежит одинаковое количество работы, а пропуски ничего не сдвигают.
 * Окно семь дней, но не больше половины прожитого, иначе половины
 * перекрылись бы и отрезок сравнивался бы сам с собой; пока дней мало, плашки
 * нет вовсе.
 *
 * **Главная строка — секунды на ответ.** Это единственное здесь, что мерит
 * не усердие, а беглость: сколько времени уходит на то, чтобы понять задание и
 * ответить. Время реакции — обычная мера автоматизма, и меряется оно одним и
 * тем же прибором в обеих точках.
 *
 * Оговорку надо держать в голове, и она записана рядом на экране: состав
 * занятия день ото дня разный, а типы заданий отличаются по времени втрое
 * (выбор варианта — восемь секунд, экран пар — сорок). Часть движения на этой
 * строке — не беглость, а перекос дня.
 *
 * Точность тоже сравнивается, и у неё свой подвох в другую сторону: первые
 * уроки легче последних, так что рост точности отчасти съеден усложнением
 * материала. Обе оговорки честнее написать, чем спрятать: число, в которое
 * нельзя ткнуть пальцем, на снимке ничего не стоит.
 */
@Composable
private fun BeforeAfter(state: StatsState) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass1)
            .padding(16.dp)
    ) {
        Text("БЫЛО — СТАЛО", style = MaterialTheme.typography.labelSmall, color = Accent)
        Spacer(Modifier.height(4.dp))
        Text(
            "первые ${state.before.days} и последние ${state.after.days} " +
                plural(state.after.days, "день занятий", "дня занятий", "дней занятий"),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )

        Spacer(Modifier.height(16.dp))
        Versus(
            "секунд на ответ",
            "${state.before.perAnswer} с",
            "${state.after.perAnswer} с",
            better = state.after.perAnswer in 1 until state.before.perAnswer
        )
        Versus(
            "верных ответов",
            "${state.before.accuracy}%",
            "${state.after.accuracy}%",
            better = state.after.accuracy > state.before.accuracy
        )
        Versus(
            "новых слов",
            "${state.before.words}",
            "${state.after.words}",
            better = state.after.words > state.before.words
        )
        Versus(
            "минут",
            "${state.before.minutes}",
            "${state.after.minutes}",
            better = state.after.minutes > state.before.minutes
        )

        if (state.wordsCurve.size > 2) {
            Spacer(Modifier.height(18.dp))
            Text("Словарь", style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.height(8.dp))
            Curve(state.wordsCurve)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Секунды на ответ зависят и от состава занятия: выбор варианта быстрее " +
                "разговорного задания втрое. Точность — от того, что поздние уроки труднее ранних.",
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
    }
}

/** Одна строка сравнения: подпись слева, два числа справа. */
@Composable
private fun Versus(label: String, before: String, after: String, better: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            modifier = Modifier.weight(1f)
        )
        Text(before, style = MaterialTheme.typography.titleMedium, color = Muted)
        Text(
            "  →  ",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
        Text(
            after,
            style = MaterialTheme.typography.titleMedium,
            color = if (better) Jade else Paper
        )
    }
}

/**
 * Кривая накопленного словаря.
 *
 * Накопительная, а не по дням: по дневным столбикам роста не видно вовсе —
 * там всюду ноль-десять, — а накопительная линия и есть ответ на вопрос
 * «двигается ли дело».
 */
@Composable
private fun Curve(values: List<Int>) {
    val line = Accent
    val top = values.lastOrNull()?.coerceAtLeast(1) ?: 1
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        var prev: Offset? = null
        values.forEachIndexed { i, v ->
            val point = Offset(i * stepX, size.height - size.height * v / top)
            prev?.let { drawLine(line, it, point, strokeWidth = 3f) }
            prev = point
        }
    }
}

/**
 * «Что я мог сказать тогда и что сегодня».
 *
 * Самая убедительная часть отчёта и единственная без единого числа. Люди
 * понимают разницу между «Zovem se Sergej» и фразой на семь слов мгновенно, а
 * проценты в чужой ленте не значат ничего.
 *
 * Обе фразы — **настоящие задания из закрытых уроков**, не подобранные для
 * красоты: первая из урока, закрытого раньше всех, вторая из закрытого
 * последним. Даты стоят рядом по той же причине, по которой они стоят на
 * витрине: без них рост не измеряется ничем.
 */
@Composable
private fun SaidCard(then: Milestone, now: Milestone) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass1)
            .padding(16.dp)
    ) {
        Text("ЧТО Я МОГ СКАЗАТЬ", style = MaterialTheme.typography.labelSmall, color = Accent)

        Spacer(Modifier.height(14.dp))
        Said(humanDate(then.day), then, Muted)
        Spacer(Modifier.height(16.dp))
        Said(humanDate(now.day), now, Paper)
    }
}

@Composable
private fun Said(when_: String, it: Milestone, tint: Color) {
    Text(when_, style = MaterialTheme.typography.labelSmall, color = Muted)
    Spacer(Modifier.height(4.dp))
    Text(it.me, style = MaterialTheme.typography.titleMedium, color = tint)
    Text(it.ru, style = MaterialTheme.typography.bodyMedium, color = Muted)
}

/**
 * Срез: единственное здесь, что мерит знание, а не усердие.
 *
 * Остальные числа отчёта отвечают на вопрос «сколько я занимался». На вопрос
 * «стал ли я знать больше» они не отвечают и не могут: материал каждый день
 * разный и разной трудности, и рост точности на нём значит в лучшем случае
 * «сегодня попалось попроще». Срез спрашивает **одинаково**, поэтому два его
 * результата можно честно поставить рядом — и это единственное место в
 * приложении, где слова «было — стало» сказаны без оговорок.
 *
 * Первый результат будет низким, и это не поломка: слова берутся из всего
 * словаря, а не из пройденных, то есть срез мерит знание языка, а не память
 * на свои карточки.
 *
 * Одна пара срезов ничего не доказывает — тридцать слов из 1152 дают разброс
 * в несколько процентов. Доказывает направление на трёх-четырёх, поэтому
 * список показывается целиком, а не только «было и стало».
 */
@Composable
private fun CheckupCard(checkups: List<CheckupEntity>, onCheckup: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass1)
            .padding(16.dp)
    ) {
        Text("СРЕЗ", style = MaterialTheme.typography.labelSmall, color = Accent)
        Spacer(Modifier.height(4.dp))
        Text(
            "Тридцать слов из всего словаря, без подсказок. На интервалы и " +
                "счёт дня не влияет.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )

        if (checkups.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            val first = checkups.first()
            val last = checkups.last()
            if (checkups.size > 1) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Score(first, Muted)
                    Text(
                        "  →  ",
                        style = MaterialTheme.typography.titleMedium,
                        color = Muted
                    )
                    Score(last, if (share(last) > share(first)) Jade else Paper)
                }
            } else {
                Score(first, Paper)
            }

            if (checkups.size > 2) {
                Spacer(Modifier.height(10.dp))
                Text(
                    checkups.joinToString("   ") {
                        "${humanShort(it.day)} ${it.correct}/${it.total}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onCheckup,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)
        ) {
            Text(
                if (checkups.isEmpty()) "Пройти срез" else "Пройти ещё раз",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun Score(checkup: CheckupEntity, tint: Color) {
    Column {
        Text(humanShort(checkup.day), style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(
            "${checkup.correct}/${checkup.total}",
            style = MaterialTheme.typography.headlineSmall,
            color = tint
        )
        Text(
            "${share(checkup)}%",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

private fun share(checkup: CheckupEntity): Int =
    if (checkup.total > 0) checkup.correct * 100 / checkup.total else 0

/** «2026-09-13» → «13 сен». В списке срезов год не нужен, а место нужно. */
private fun humanShort(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMM", Locale("ru")))
}.getOrDefault(day)

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
            .background(Glass1)
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
            .background(Glass1)
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
                "${days.size} ${dayWord(days.size)} назад",
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

/**
 * Склонение слова «день» для подписи под графиком.
 *
 * Окно графика тридцать дней, и при полном окне «дней» верно — поэтому
 * ошибка и жила долго. Видна она только у того, кто занимается меньше
 * месяца: «22 дней назад» вместо «22 дня».
 */
private fun dayWord(n: Int): String = when {
    n % 100 in 11..14 -> "дней"
    n % 10 == 1 -> "день"
    n % 10 in 2..4 -> "дня"
    else -> "дней"
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
