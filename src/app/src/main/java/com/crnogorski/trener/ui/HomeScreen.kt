package com.crnogorski.trener.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.crnogorski.trener.data.StoryMode

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onLesson: (String) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    onStory: (String, StoryMode) -> Unit,
    onVocab: (Boolean, Boolean) -> Unit,
    onTab: (HomeTab) -> Unit,
    onToggleGroup: (String) -> Unit,
    onNote: (String) -> Unit
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
                    HomeTab.Lessons -> "Курс"
                    HomeTab.Stories -> "Истории"
                    HomeTab.Words -> "Слова"
                },
                style = MaterialTheme.typography.displaySmall,
                color = Paper
            )
            Spacer(Modifier.height(20.dp))
        }

        if (state.error != null) {
            item {
                Text(state.error, color = Crimson, style = MaterialTheme.typography.bodyMedium)
            }
        }

        when (state.tab) {
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
                                StoryRow(card, onClick = { onStory(card.ref.id, card.mode) })
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
            .background(if (selected) Surface1 else Color.Transparent)
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink)
            .clickable(onClick = onClick)
            .padding(top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Свернуть" else "Развернуть",
            tint = Muted,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$done / $total",
            style = MaterialTheme.typography.labelSmall,
            color = Muted
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
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(
                width = 1.dp,
                color = if (card.finished) Jade.copy(alpha = 0.45f) else Surface2,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            card.ref.title,
            style = MaterialTheme.typography.bodyLarge,
            color = Paper,
            modifier = Modifier.weight(1f)
        )
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
            .background(if (ready) Surface2 else Surface1)
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Accent else Surface1)
            .clickable(enabled = active, onClick = onClick)
            .padding(18.dp)
    ) {
        Text(
            "ПОВТОРЕНИЕ",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Ink.copy(alpha = 0.7f) else Muted
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (active) "$count ${cardsWord(count)} к повторению" else "Сейчас нечего повторять",
            style = MaterialTheme.typography.titleMedium,
            color = if (active) Ink else Muted
        )
    }
}

@Composable
private fun LessonRow(card: LessonCard, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(
                width = 1.dp,
                color = if (card.done) Accent.copy(alpha = 0.35f) else Surface2,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
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
