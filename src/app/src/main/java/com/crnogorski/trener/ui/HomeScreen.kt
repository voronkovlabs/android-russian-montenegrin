package com.crnogorski.trener.ui

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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    state: HomeState,
    onLesson: (String) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    onNote: (String) -> Unit
) {
    if (state.loading) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) { CircularProgressIndicator(color = Gold) }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("CRNOGORSKI", style = MaterialTheme.typography.labelSmall, color = Gold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Курс",
                        style = MaterialTheme.typography.displaySmall,
                        color = Paper
                    )
                }
                ComplaintButton(onSave = onNote)
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Настройки",
                        tint = Muted
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (state.error != null) {
            item {
                Text(state.error, color = Crimson, style = MaterialTheme.typography.bodyMedium)
            }
        }

        item {
            ReviewCard(count = state.dueCount, onClick = onReview)
            Spacer(Modifier.height(14.dp))
        }

        items(state.lessons, key = { it.ref.id }) { card ->
            LessonRow(card, onClick = { onLesson(card.ref.id) })
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ReviewCard(count: Int, onClick: () -> Unit) {
    val active = count > 0
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Gold else Surface1)
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
                color = if (card.done) Gold.copy(alpha = 0.35f) else Surface2,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            card.ref.id.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (card.done) Gold else Muted,
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
