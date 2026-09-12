package com.crnogorski.trener.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crnogorski.trener.data.StoryMode
import com.crnogorski.trener.net.Release

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onLesson: (String) -> Unit,
    onDaily: (Boolean) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    /**
     * Открыть историю. Третий параметр — начать с первой фразы: так делает
     * плитка ежедневного задания, но не список на вкладке «Истории».
     */
    onStory: (String, StoryMode, Boolean) -> Unit,
    onVocab: (Boolean, Boolean) -> Unit,
    onTab: (HomeTab) -> Unit,
    onToggleGroup: (String) -> Unit,
    onStats: () -> Unit,
    onUpdate: () -> Unit,
    /** Доля скачанного обновления, если оно идёт прямо сейчас. */
    download: Float?,
    onNote: (String) -> Unit,
    onIdea: (String, Boolean) -> Unit
) {
    if (state.loading) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator(color = Accent) }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabIcon(
                    icon = Icons.Outlined.WbSunny,
                    label = "Сегодня",
                    selected = state.tab == HomeTab.Today
                ) { onTab(HomeTab.Today) }
                TabIcon(
                    icon = Icons.Outlined.School,
                    label = "Уроки",
                    selected = state.tab == HomeTab.Lessons
                ) { onTab(HomeTab.Lessons) }
                TabIcon(
                    icon = Icons.Outlined.AutoStories,
                    label = "Истории",
                    selected = state.tab == HomeTab.Stories
                ) { onTab(HomeTab.Stories) }
                TabIcon(
                    icon = Icons.Outlined.Style,
                    label = "Слова",
                    selected = state.tab == HomeTab.Words
                ) { onTab(HomeTab.Words) }
                Spacer(Modifier.weight(1f))
                IdeaButton(onSave = onIdea)
                ComplaintButton(onSave = onNote)
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Настройки",
                        tint = Muted
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("CRNOGORSKI", style = MaterialTheme.typography.labelSmall, color = Accent)
            Spacer(Modifier.height(6.dp))
            Text(
                when (state.tab) {
                    HomeTab.Today -> "Сегодня"
                    HomeTab.Lessons -> "Курс"
                    HomeTab.Stories -> "Истории"
                    HomeTab.Words -> "Слова"
                },
                style = MaterialTheme.typography.displaySmall,
                color = Paper
            )
            Spacer(Modifier.height(14.dp))
            StatsStrip(state.stats, onStats)
            val fresh = state.update
            if (fresh != null) {
                Spacer(Modifier.height(8.dp))
                UpdateStrip(fresh.version, fresh.sizeMb, download, onUpdate)
            }
            Spacer(Modifier.height(20.dp))
        }

        if (state.error != null) {
            item {
                Text(state.error, color = Crimson, style = MaterialTheme.typography.bodyMedium)
            }
        }

        when (state.tab) {
            HomeTab.Today -> {
                item {
                    DailyTile(state.daily, onStart = onDaily)
                    val step = state.daily.story
                    if (step != null) {
                        Spacer(Modifier.height(12.dp))
                        StoryStepTile(step, hasItems = state.daily.items.isNotEmpty()) {
                            onStory(step.id, step.mode, true)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            HomeTab.Lessons -> {
                item {
                    ReviewCard(count = state.dueCount, onClick = onReview)
                    Spacer(Modifier.height(4.dp))
                }

                state.groups.forEach { group ->
                    // Урок без раздела показываем без заголовка и всегда открытым:
                    // прятать его было бы некуда.
                    val open = group.title.isBlank() || group.title in state.expandedGroups
                    if (group.title.isNotBlank()) {
                        stickyHeader(key = "s-${group.title}") {
                            SectionHeader(
                                title = group.title,
                                done = group.done,
                                total = group.cards.size,
                                expanded = open
                            ) { onToggleGroup(group.title) }
                        }
                    }
                    if (open) {
                        items(group.cards, key = { it.ref.id }) { card ->
                            LessonRow(card, onClick = { onLesson(card.ref.id) })
                        }
                    }
                }
            }

            HomeTab.Words -> {
                item {
                    VocabTile(
                        title = "С русского",
                        hint = "Назвать слово и поставить его в форму",
                        track = state.vocab.toTarget,
                        onStart = { onVocab(false, false) },
                        onPractice = { onVocab(false, true) }
                    )
                    Spacer(Modifier.height(12.dp))
                    VocabTile(
                        title = "На русский",
                        hint = "Узнать слово: что оно значит",
                        track = state.vocab.toNative,
                        onStart = { onVocab(true, false) },
                        onPractice = { onVocab(true, true) }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            HomeTab.Stories -> {
                state.storyGroups.forEach { group ->
                    val open = group.title in state.expandedGroups
                    stickyHeader(key = "s-${group.title}") {
                        SectionHeader(
                            title = group.title,
                            done = group.done,
                            total = group.cards.size,
                            expanded = open
                        ) { onToggleGroup(group.title) }
                    }
                    if (open) {
                        if (group.cards.isEmpty()) {
                            item(key = "empty-${group.mode.key}") {
                                Text(
                                    "Историй пока нет.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Muted
                                )
                            }
                        } else {
                            // Ключ с режимом: тексты в группах одни и те же, и
                            // по одному id список нашёл бы две одинаковые строки.
                            items(group.cards, key = { "${group.mode.key}-${it.ref.id}" }) { card ->
                                StoryRow(card, onClick = { onStory(card.ref.id, card.mode, false) })
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * Ежедневное задание: одна кнопка вместо четырёх решений.
 *
 * Ограничение тут по времени, а не по числу заданий, поэтому крупно стоит
 * оценка в минутах, а состав — строкой ниже. Число заданий тоже показано:
 * оценка приблизительная, и без счётчика непонятно, надолго ли это.
 *
 * Когда норма на сегодня выбрана, плашка не запрещает продолжать, а
 * предлагает ещё один заход тем же размером. Заниматься сверх нормы — не
 * нарушение, но и не то, что случается само собой.
 */
/**
 * Строка отчёта под заголовком: сегодня и всего, одной строкой.
 *
 * Стоит на всех четырёх вкладках, а не только на «Сегодня», и это не небрежность:
 * потраченное время — не свойство вкладки, а свойство дня, и заниматься можно
 * из любой.
 *
 * Показаны ровно два числа — сегодняшнее и итог. Всё остальное (уроки, точность,
 * график) за нажатием: строка должна читаться боковым зрением, а не изучаться.
 */
@Composable
private fun StatsStrip(stats: StatsBrief, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Glass1)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (stats.todayMinutes > 0) {
                    "Сегодня ${stats.todayMinutes} мин · ${stats.todayAnswers} " +
                        answers(stats.todayAnswers)
                } else {
                    "Сегодня ещё не занимались"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Paper
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    // Серию показываем со второго дня: «1 день подряд» — это не
                    // серия, а просто сегодня, и место она занимала бы зря.
                    if (stats.streak > 1) {
                        append("${stats.streak} ${days(stats.streak)} подряд · ")
                    }
                    append("всего ${timeText(stats.totalMinutes * 60)} · ")
                    append("${stats.totalSessions} ${sessions(stats.totalSessions)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
        }
        Text("Отчёт", style = MaterialTheme.typography.labelSmall, color = Accent)
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Accent
        )
    }
}

/**
 * Строка про свежую версию — только когда она есть.
 *
 * На главном, а не в настройках: сюда заходят каждый день, а в настройки раз в
 * месяц. Размер написан прямо в строке — нажатие сразу начинает скачивание, и
 * шестьдесят мегабайт по мобильной сети человек должен видеть до нажатия, а не
 * после.
 */
@Composable
private fun UpdateStrip(version: String, sizeMb: Int, progress: Float?, onUpdate: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Glass1)
            .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            // Пока идёт скачивание, нажимать нечего: строка рассказывает, а не
            // предлагает. Второе нажатие всё равно ничего бы не начало, но
            // живая кнопка, от которой ничего не происходит, читается как
            // поломка.
            .clickable(enabled = progress == null, onClick = onUpdate)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (progress == null) {
                    "Есть версия $version · $sizeMb МБ"
                } else {
                    // Мегабайты, а не одни проценты: по ним видно скорость, и
                    // «12 из 58» на медленной сети честнее говорит, сколько ещё
                    // ждать, чем «21%».
                    val got = (sizeMb * progress).toInt()
                    "Качаю $version · $got из $sizeMb МБ"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Paper,
                modifier = Modifier.weight(1f)
            )
            if (progress == null) {
                Text("Обновить", style = MaterialTheme.typography.labelSmall, color = Accent)
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Accent
                )
            } else {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent
                )
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Accent,
                trackColor = Surface2
            )
        }
    }
}

private fun answers(n: Int): String = when {
    n % 100 in 11..14 -> "ответов"
    n % 10 == 1 -> "ответ"
    n % 10 in 2..4 -> "ответа"
    else -> "ответов"
}

private fun days(n: Int): String = when {
    n % 100 in 11..14 -> "дней"
    n % 10 == 1 -> "день"
    n % 10 in 2..4 -> "дня"
    else -> "дней"
}

private fun sessions(n: Int): String = when {
    n % 100 in 11..14 -> "занятий"
    n % 10 == 1 -> "занятие"
    n % 10 in 2..4 -> "занятия"
    else -> "занятий"
}

@Composable
private fun DailyTile(plan: DailyPlan, onStart: (Boolean) -> Unit) {
    val ready = plan.items.isNotEmpty()
    val stripe = Accent
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (ready) Glass2 else Glass1)
            // Метка уклона цветом: день речи должен отличаться от дня слов
            // раньше, чем прочитана подпись под заголовком.
            .drawBehind {
                if (ready) {
                    drawRoundRect(
                        color = stripe,
                        size = Size(6.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(3.dp.toPx())
                    )
                }
            }
            .clickable(enabled = ready) { onStart(plan.full) }
            .padding(start = 26.dp, top = 20.dp, end = 20.dp, bottom = 20.dp)
    ) {
        Text(
            if (plan.full) "СВЕРХ НОРМЫ" else plan.accent.title,
            style = MaterialTheme.typography.labelSmall,
            color = if (ready) Accent else Muted
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                ready -> "${plan.estimate} мин · ${plan.items.size} заданий"
                plan.nothingLeft -> "Сегодня брать нечего"
                else -> "На сегодня всё"
            },
            style = MaterialTheme.typography.titleLarge,
            color = Paper
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                ready && plan.lesson > 0 -> buildString {
                    append("Новое из «${plan.lessonTitle}» — ${plan.lesson}")
                    if (plan.review > 0) append(", повторение — ${plan.review}")
                    if (plan.words > 0) append(", слова — ${plan.words}")
                }

                ready -> buildString {
                    if (plan.review > 0) append("Повторение — ${plan.review}")
                    if (plan.review > 0 && plan.words > 0) append(", ")
                    if (plan.words > 0) append(if (plan.review > 0) "слова — ${plan.words}" else "Слова — ${plan.words}")
                }

                plan.full -> "Позанимались ${plan.spent} мин из ${plan.minutes}."
                else -> "Просроченного нет, дневная норма слов взята, курс введён до конца."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
        if (ready) {
            Spacer(Modifier.height(6.dp))
            Text(
                plan.accent.note,
                style = MaterialTheme.typography.bodyMedium,
                color = Accent
            )
        }
        if (plan.spent > 0 && ready) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Сегодня уже ${plan.spent} мин из ${plan.minutes}.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
        if (plan.full) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { onStart(true) }) {
                Text("Ещё ${plan.minutes} минут", color = Accent)
            }
        }
    }
}

/**
 * Хвост занятия: отрезки истории.
 *
 * Отдельной плашкой, а не заданием внутри сессии: у историй свой экран и своё
 * требование — говорить вслух. При людях хвост пропускают, и занятие от этого
 * не перестаёт быть сделанным.
 */
@Composable
private fun StoryStepTile(step: StoryStep, hasItems: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Glass1)
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Text(
            if (hasItems) "ПОТОМ, ВСЛУХ" else "ВСЛУХ",
            style = MaterialTheme.typography.labelSmall,
            color = Accent
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "«${step.title}» — ${step.chunks} ${chunkWord(step.chunks)}",
            style = MaterialTheme.typography.titleMedium,
            color = Paper
        )
        Spacer(Modifier.height(6.dp))
        Text(
            step.mode.title + ". Осталось в истории: ${step.left}.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

private fun chunkWord(n: Int): String = when {
    n % 100 in 11..14 -> "отрезков"
    n % 10 == 1 -> "отрезок"
    n % 10 in 2..4 -> "отрезка"
    else -> "отрезков"
}

/**
 * Иконка вкладки. Выбранная отличается не только цветом, но и подложкой:
 * одного акцентного оттенка мало, чтобы понять, где находишься.
 */
@Composable
private fun TabIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Glass1 else Color.Transparent)
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Accent else Muted)
    }
}

/**
 * Заголовок раздела: липкий и складной, свёрнут по умолчанию.
 *
 * Свёрнут потому, что разделов будет много, а нужен за раз один; липкий потому,
 * что в длинном открытом разделе иначе теряешь, где находишься. Один и тот же
 * для уроков и историй: разница между ними в строках под ним, а не в шапке. Фон непрозрачный
 * и во всю ширину — иначе строки уроков просвечивали бы из-под него.
 */
@Composable
private fun SectionHeader(
    title: String,
    done: Int,
    total: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    // Заголовок — такая же карточка, как всё остальное на экране.
    //
    // Раньше он заливался сплошным `Ink` во всю ширину: так было верно, пока
    // фон был однотонным, — заголовок липнет к верху, и строки под ним
    // просвечивать не должны. На фотографии (1.64) та же заливка превратилась
    // в чёрную плиту поперёк снимка, и владелец сказал «жутко». Теперь это
    // скруглённое стекло: непрозрачность своя, повышенная, — см. GlassHeader.
    // Заголовок отличается от своих строк **тремя** вещами сразу, и это не
    // избыточность: одной разницы не хватило (1.66 — «сливаются по цвету»).
    // Оттенок отвечает на расстоянии, отступ строк — при беглом взгляде,
    // а насыщенность заголовка — когда читают.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassHeader)
            // Тёплая подложка поверх стекла: в тёмной теме заголовок уходит в
            // тепло, в светлой — в синеву. Сдвиг по тону, а не по светлоте:
            // светлота в двух темах меняется местами, а тон работает в обеих.
            .background(Accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Свернуть" else "Развернуть",
            tint = Accent,
            modifier = Modifier.padding(end = 10.dp)
        )
        // Не капсом и не мелким: это заголовок раздела, по нему ищут глазами,
        // а разрядка в одиннадцать пунктов читается тяжелее всего на экране.
        // Мелкая моноширинная подпись остаётся счётчику — его не читают, на
        // него смотрят.
        // Заголовок белый и полужирный, а золото уходит счётчику и значку:
        // когда золотом набрано и название раздела, и код урока в строке под
        // ним, глазу не за что зацепиться — раньше строки выходили громче
        // собственного заголовка.
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp
            ),
            color = Paper,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$done / $total",
            style = MaterialTheme.typography.labelSmall,
            color = Accent
        )
    }
}

/**
 * Строка истории. Показывает не счёт, а сколько отрезков пройдено: истории
 * не оцениваются и на повторение не встают, важно только, докуда дошёл.
 */
@Composable
private fun StoryRow(card: StoryCard, onClick: () -> Unit) {
    val started = card.done > 0
    Row(
        Modifier
            // Отступ такой же, как у строки урока: заголовки у вкладок общие,
            // значит и подчинение должно выглядеть одинаково.
            .padding(start = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Glass1)
            .border(
                width = 1.dp,
                color = if (card.finished) Jade.copy(alpha = 0.45f) else Surface2,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                card.ref.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Paper
            )
            // Диалог помечен словом, а не только иконками внутри: в списке из
            // трёх десятков строк надо видеть, что открываешь, до того как
            // открыл.
            if (card.ref.dialog) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Диалог",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accent
                )
            }
        }
        Text(
            when {
                card.finished -> when (card.mode) {
                    StoryMode.Read -> "прочитано"
                    StoryMode.Listen -> "разобрано"
                    StoryMode.Translate -> "переведено"
                }
                started -> "${card.done} / ${card.ref.chunks}"
                else -> "${card.ref.chunks} отрезков"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (card.finished) Jade else Muted,
            textAlign = TextAlign.End
        )
    }
}

/**
 * Одно направление словаря: плашка вместо списка.
 *
 * Выбирать тут нечего — очередь собирается сама: сперва просроченные карточки,
 * потом новые слова в порядке частоты. Список из тысячи слов был бы витриной,
 * а не занятием.
 *
 * Направлений два и плашки поэтому две: назвать слово и узнать слово — разное
 * знание, и держится оно по-разному. Внизу отдельная кнопка тренировки: она
 * идёт вне расписания, берёт самое шаткое и доступна всегда, сколько угодно
 * раз.
 */
@Composable
private fun VocabTile(
    title: String,
    hint: String,
    track: VocabTrack,
    onStart: () -> Unit,
    onPractice: () -> Unit
) {
    val ready = track.due > 0 || track.fresh > 0
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (ready) Glass2 else Glass1)
            .padding(bottom = 6.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = track.total > 0, onClick = onStart)
                .padding(18.dp)
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) Accent else Muted
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    track.total == 0 -> "Словарь не загрузился"
                    track.due > 0 && track.fresh > 0 ->
                        "${track.due} на повторение, ${track.fresh} новых"
                    track.due > 0 -> "${track.due} на повторение"
                    track.fresh > 0 -> "${track.fresh} новых"
                    else -> "На сегодня всё"
                },
                style = MaterialTheme.typography.titleMedium,
                color = Paper
            )
            Spacer(Modifier.height(6.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = Muted)
            Spacer(Modifier.height(10.dp))
            Text(
                "Начато ${track.started} из ${track.total}, выучено ${track.learned}",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
        if (track.ready > 0) {
            TextButton(
                onClick = onPractice,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Тренировать (${track.ready})", color = Accent)
            }
        }
    }
}

@Composable
private fun ReviewCard(count: Int, onClick: () -> Unit) {
    val active = count > 0
    val stripe = Accent
    // Важное отмечается **одним** приёмом на весь экран — цветной полосой
    // слева, как у плашки занятия. Сплошная заливка акцентом (до 1.66) была
    // придумана для чёрного фона; на фотографии она стала жёлтым кирпичом во
    // всю ширину и спорила с самим снимком.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) Glass2 else Glass1)
            .drawBehind {
                if (active) {
                    drawRoundRect(
                        color = stripe,
                        size = Size(6.dp.toPx(), size.height),
                        cornerRadius = CornerRadius(3.dp.toPx())
                    )
                }
            }
            .clickable(enabled = active, onClick = onClick)
            .padding(start = 24.dp, top = 18.dp, end = 18.dp, bottom = 18.dp)
    ) {
        Text(
            "ПОВТОРЕНИЕ",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Accent else Muted
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (active) "$count ${cardsWord(count)} к повторению" else "Сейчас нечего повторять",
            style = MaterialTheme.typography.titleMedium,
            color = if (active) Paper else Muted
        )
    }
}

@Composable
private fun LessonRow(card: LessonCard, onClick: () -> Unit) {
    Row(
        Modifier
            // Отступ слева — самый дешёвый признак подчинения: видно, что
            // строка принадлежит заголовку, ещё до того как прочитан текст.
            .padding(start = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Glass1)
            // Рамки нет вовсе. Золотая рамка у пройденного урока (до 1.67)
            // делала строку заметнее собственного заголовка; о пройденности
            // и так говорят золотой код урока и счёт справа.
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            card.ref.id.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (card.done) Accent else Muted,
            modifier = Modifier.padding(end = 14.dp)
        )
        Text(
            card.ref.title,
            style = MaterialTheme.typography.bodyLarge,
            color = Paper,
            modifier = Modifier.weight(1f)
        )
        if (card.score != null) {
            Text(
                card.score,
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                textAlign = TextAlign.End
            )
        }
    }
}

private fun cardsWord(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "карточек"
        mod10 == 1 -> "карточка"
        mod10 in 2..4 -> "карточки"
        else -> "карточек"
    }
}
