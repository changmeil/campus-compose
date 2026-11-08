package com.bhu.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Remove
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bhu.campus.data.AppSettings
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.Course
import com.bhu.campus.data.TaskItem
import com.bhu.campus.data.TYPE_EXAM
import com.bhu.campus.data.TYPE_HOMEWORK
import com.bhu.campus.data.WEEKDAY_LABELS
import com.bhu.campus.data.compressWeeks
import com.bhu.campus.data.expandWeeks
import com.bhu.campus.data.formatFull
import com.bhu.campus.data.parseDueInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* ------------------------------ 课程 ------------------------------ */

@Composable
fun CourseDialog(
    course: Course?,
    defaultWeekday: Int,
    defaultStart: Int,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit,
    onDelete: (Course) -> Unit,
) {
    var name by remember { mutableStateOf(course?.name ?: "") }
    var teacher by remember { mutableStateOf(course?.teacher ?: "") }
    var location by remember { mutableStateOf(course?.location ?: "") }
    var weekday by remember { mutableIntStateOf(course?.weekday ?: defaultWeekday) }
    var start by remember { mutableIntStateOf(course?.start ?: defaultStart) }
    var end by remember { mutableIntStateOf(course?.end ?: defaultStart) }
    var weeksText by remember {
        mutableStateOf(course?.weeks?.let { compressWeeks(it) } ?: "1-16")
    }
    var color by remember { mutableIntStateOf(course?.color ?: 1) }
    val weeks = expandWeeks(weeksText)

    DialogScaffold(
        title = if (course == null) "添加课程" else "编辑课程",
        onDismiss = onDismiss,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (course != null) {
                    TextButton(
                        text = "删除",
                        onClick = { onDelete(course) },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(text = "取消", onClick = onDismiss)
                Button(
                    onClick = {
                        if (name.isBlank()) return@Button
                        val from = minOf(start, end)
                        val to = maxOf(start, end)
                        onSave(
                            (course ?: Course(name = name)).copy(
                                name = name.trim(),
                                teacher = teacher.trim(),
                                location = location.trim(),
                                weekday = weekday,
                                start = from,
                                end = to,
                                weeks = weeks.ifEmpty { (1..16).toList() },
                                color = color,
                                source = course?.source ?: "manual",
                            )
                        )
                    },
                    enabled = name.isNotBlank() && weeks.isNotEmpty(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        },
    ) {
        TextField(
            value = name,
            onValueChange = { name = it },
            label = "课程名",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(
                value = teacher,
                onValueChange = { teacher = it },
                label = "老师",
                modifier = Modifier.weight(1f),
            )
            TextField(
                value = location,
                onValueChange = { location = it },
                label = "教室",
                modifier = Modifier.weight(1f),
            )
        }
        FieldLabel("星期")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WEEKDAY_LABELS.forEachIndexed { index, label ->
                SelectablePill(
                    text = "周$label",
                    selected = weekday == index + 1,
                    onClick = { weekday = index + 1 },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stepper(
                label = "开始节次",
                value = start,
                range = 1..12,
                modifier = Modifier.weight(1f),
                onChange = {
                    start = it
                    if (end < it) end = it
                },
            )
            Stepper(
                label = "结束节次",
                value = end,
                range = 1..12,
                modifier = Modifier.weight(1f),
                onChange = {
                    end = it
                    if (start > it) start = it
                },
            )
        }
        TextField(
            value = weeksText,
            onValueChange = { weeksText = it },
            label = "上课周次（如 1-16 或 1,3,5）",
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel("颜色")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (1..7).forEach { index ->
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(CoursePalette.accent(index))
                        .clickable { color = index },
                    contentAlignment = Alignment.Center,
                ) {
                    if (color == index) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------ 任务 ------------------------------ */

@Composable
fun TaskDialog(
    task: TaskItem?,
    onDismiss: () -> Unit,
    onSave: (TaskItem) -> Unit,
    onDelete: (TaskItem) -> Unit,
) {
    var type by remember { mutableStateOf(task?.type ?: TYPE_HOMEWORK) }
    var title by remember { mutableStateOf(task?.title ?: "") }
    var course by remember { mutableStateOf(task?.course ?: "") }
    var dueText by remember {
        mutableStateOf(task?.dueAt?.let { it.formatFull() } ?: "")
    }
    var note by remember { mutableStateOf(task?.note ?: "") }
    val due = parseDueInput(dueText)

    DialogScaffold(
        title = if (task == null) "添加任务" else "编辑任务",
        onDismiss = onDismiss,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (task != null) {
                    TextButton(
                        text = "删除",
                        onClick = { onDelete(task) },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(text = "取消", onClick = onDismiss)
                Button(
                    onClick = {
                        if (title.isBlank()) return@Button
                        onSave(
                            (task ?: TaskItem(title = title)).copy(
                                type = type,
                                title = title.trim(),
                                course = course.trim(),
                                dueAt = due,
                                note = note.trim(),
                                source = task?.source ?: "manual",
                            )
                        )
                    },
                    enabled = title.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectablePill("作业", type == TYPE_HOMEWORK, Modifier.weight(1f)) { type = TYPE_HOMEWORK }
            SelectablePill("考试", type == TYPE_EXAM, Modifier.weight(1f)) { type = TYPE_EXAM }
        }
        TextField(
            value = title,
            onValueChange = { title = it },
            label = "标题",
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = course,
            onValueChange = { course = it },
            label = "课程（可留空）",
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = dueText,
            onValueChange = { dueText = it },
            label = "截止时间，如 11-20 23:59",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("今天 23:59", "明天 23:59", "后天 23:59").forEach { preset ->
                SelectablePill(preset, selected = false) { dueText = preset }
            }
        }
        if (dueText.isNotBlank() && due == null) {
            Text(
                text = "时间格式没认出来，试试 11-20 23:59 或 2026-11-20 23:59",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.error,
            )
        }
        TextField(
            value = note,
            onValueChange = { note = it },
            label = "备注（可留空）",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ------------------------------ 设置 ------------------------------ */

@Composable
fun SettingsDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onClearData: () -> Unit,
) {
    var startText by remember { mutableStateOf(LocalDate.ofEpochDay(settings.semesterStart).toString()) }
    var weeksText by remember { mutableStateOf(settings.totalWeeks.toString()) }
    var building by remember { mutableStateOf(settings.building) }
    var room by remember { mutableStateOf(settings.room) }
    var showEnergy by remember { mutableStateOf(settings.showEnergy) }

    DialogScaffold(
        title = "设置",
        onDismiss = onDismiss,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(text = "清空数据", onClick = onClearData)
                Spacer(Modifier.weight(1f))
                TextButton(text = "取消", onClick = onDismiss)
                Button(
                    onClick = {
                        val day = runCatching { LocalDate.parse(startText.trim()) }
                            .getOrNull()?.toEpochDay() ?: settings.semesterStart
                        onSave(
                            settings.copy(
                                semesterStart = day,
                                totalWeeks = (weeksText.toIntOrNull() ?: settings.totalWeeks).coerceIn(1, 30),
                                building = building.trim(),
                                room = room.trim(),
                                showEnergy = showEnergy,
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        },
    ) {
        TextField(
            value = startText,
            onValueChange = { startText = it },
            label = "第 1 周周一（yyyy-MM-dd）",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val thisMonday = LocalDate.now().minusDays((LocalDate.now().dayOfWeek.value - 1).toLong())
            SelectablePill("本周一", false) { startText = thisMonday.toString() }
            SelectablePill("上周一", false) { startText = thisMonday.minusWeeks(1).toString() }
            SelectablePill("四周前", false) { startText = thisMonday.minusWeeks(4).toString() }
        }
        TextField(
            value = weeksText,
            onValueChange = { weeksText = it },
            label = "学期总周数",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(
                value = building,
                onValueChange = { building = it },
                label = "默认楼号",
                modifier = Modifier.weight(1f),
            )
            TextField(
                value = room,
                onValueChange = { room = it },
                label = "默认房间号",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "学期起始日决定“第几周”的换算；学习通同步成功后会自动校准。",
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("显示宿舍电费", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "其他学校用不到可以关掉，底部「电费」页和首页卡片会一起隐藏",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = showEnergy, onCheckedChange = { showEnergy = it })
        }
    }
}

/* ------------------------------ 公共壳 ------------------------------ */

@Composable
private fun DialogScaffold(
    title: String,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            body()
            Spacer(Modifier.height(4.dp))
            footer()
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

@Composable
fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (value > range.first) onChange(value - 1) },
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = MiuixIcons.Remove,
                    contentDescription = "减少",
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = value.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            IconButton(
                onClick = { if (value < range.last) onChange(value + 1) },
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = "增加",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
