package com.bhu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.TaskItem
import com.bhu.campus.data.countdownOf
import com.bhu.campus.data.formatDueShort
import com.bhu.campus.ui.CardPadding
import com.bhu.campus.ui.EmptyHint
import com.bhu.campus.ui.Pill

private val FILTERS = listOf("全部", "作业", "考试", "逾期", "完成")

@Composable
fun TasksScreen(
    contentPadding: PaddingValues,
    onEditTask: (TaskItem) -> Unit,
) {
    val tasks = CampusStore.tasks
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()

    val filtered = remember(tasks, filter, now) {
        val base = when (filter) {
            1 -> tasks.filter { !it.isExam }
            2 -> tasks.filter { it.isExam }
            3 -> tasks.filter { !it.done && it.dueAt != null && it.dueAt < now }
            4 -> tasks.filter { it.done }
            else -> tasks
        }
        base.sortedWith(
            compareBy(
                { it.done },
                { it.dueAt ?: Long.MAX_VALUE },
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        TabRow(
            tabs = FILTERS,
            selectedTabIndex = filter,
            onTabSelected = { filter = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (filtered.isEmpty()) {
            EmptyHint(
                icon = MiuixIcons.Tasks,
                text = when (filter) {
                    3 -> "没有逾期任务"
                    4 -> "还没有完成的任务"
                    else -> "这里还是空的，点右上角 + 添加"
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { task ->
                    TaskCard(task = task, onClick = { onEditTask(task) })
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskItem, onClick: () -> Unit) {
    val countdown = countdownOf(task.dueAt, task.done)
    Card(onClick = onClick, insideMargin = CardPadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                state = if (task.done) ToggleableState.On else ToggleableState.Off,
                onClick = { CampusStore.toggleTask(task.id) },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = if (task.isExam) "考试" else "作业",
                        color = if (task.isExam) MiuixTheme.colorScheme.onErrorContainer
                        else MiuixTheme.colorScheme.primary,
                        background = if (task.isExam) MiuixTheme.colorScheme.errorContainer
                        else MiuixTheme.colorScheme.primaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = task.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        color = if (task.done) MiuixTheme.colorScheme.onBackgroundVariant
                        else MiuixTheme.colorScheme.onBackground,
                    )
                }
                val meta = listOfNotNull(
                    task.course.takeIf { it.isNotBlank() },
                    task.dueAt?.let { formatDueShort(it) },
                    task.note.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.padding(top = 2.dp)) {
                Pill(
                    text = countdown.text,
                    color = toneColor(countdown.tone),
                    background = toneSoft(countdown.tone),
                )
            }
        }
    }
}
