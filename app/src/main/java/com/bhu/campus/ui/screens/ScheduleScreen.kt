package com.bhu.campus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.Course
import com.bhu.campus.data.DEFAULT_SECTION_TIMES
import com.bhu.campus.data.WEEKDAY_LABELS
import com.bhu.campus.data.weekIndexOf
import com.bhu.campus.data.weekStartDate
import com.bhu.campus.ui.CardPadding
import com.bhu.campus.ui.CoursePalette
import java.time.LocalDate
import java.time.LocalTime

private val CellHeight = 54.dp
private val GutterWidth = 40.dp
private const val ROWS = 12

@Composable
fun ScheduleScreen(
    contentPadding: PaddingValues,
    onAddCourse: (Int, Int) -> Unit,
    onEditCourse: (Course) -> Unit,
    onImport: () -> Unit,
) {
    val courses = CampusStore.courses
    val settings = CampusStore.settings
    val today = LocalDate.now()
    val currentWeek = weekIndexOf(today, settings.semesterStart).coerceAtLeast(1)
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val week = (currentWeek + weekOffset).coerceIn(1, settings.totalWeeks.coerceAtLeast(1))

    val weekCourses = remember(courses, week) { courses.filter { week in it.weeks } }

    // 周一到周五固定显示；周末只有排了课才占一列
    val visibleDays = remember(weekCourses) {
        (1..7).filter { day -> day <= 5 || weekCourses.any { it.weekday == day } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        WeekBar(
            week = week,
            isCurrentWeek = week == currentWeek,
            firstDate = weekStartDate(week, settings.semesterStart),
            onPrev = { weekOffset -= 1 },
            onNext = { weekOffset += 1 },
            onToday = { weekOffset = 0 },
            onImport = onImport,
        )

        if (weekCourses.isEmpty()) {
            Text(
                text = "本周还没有课，点任意格子手动添加，或在今日页登录学习通同步",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        val vState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vState)
                .padding(bottom = 16.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val colWidth = ((maxWidth - GutterWidth) / visibleDays.size).coerceAtLeast(36.dp)
                Column {
                    DayHeaderRow(week = week, days = visibleDays, columnWidth = colWidth)
                    Row(Modifier.fillMaxWidth()) {
                        SectionGutter()
                        visibleDays.forEach { day ->
                            DayColumn(
                                day = day,
                                week = week,
                                width = colWidth,
                                isToday = day == today.dayOfWeek.value && week == currentWeek,
                                courses = weekCourses.filter { it.weekday == day },
                                onAddCourse = onAddCourse,
                                onEditCourse = onEditCourse,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekBar(
    week: Int,
    isCurrentWeek: Boolean,
    firstDate: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onImport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(
                imageVector = MiuixIcons.ChevronBackward,
                contentDescription = "上一周",
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "第 $week 周",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${firstDate.monthValue}/${firstDate.dayOfMonth} 起",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = "下一周",
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (!isCurrentWeek) {
            TextButton(text = "回到本周", onClick = onToday)
        }
        TextButton(text = "导入", onClick = onImport)
    }
}

@Composable
private fun DayHeaderRow(week: Int, days: List<Int>, columnWidth: Dp) {
    val settings = CampusStore.settings
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(GutterWidth))
        days.forEach { day ->
            val date = weekStartDate(week, settings.semesterStart).plusDays((day - 1).toLong())
            val isToday = date == today
            Column(
                modifier = Modifier
                    .width(columnWidth)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = WEEKDAY_LABELS[day - 1],
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isToday) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackground,
                )
                Text(
                    text = "${date.monthValue}/${date.dayOfMonth}",
                    fontSize = 9.sp,
                    color = if (isToday) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionGutter() {
    val now = LocalTime.now()
    val currentSection = DEFAULT_SECTION_TIMES.indexOfFirst { (start, end) ->
        !now.isBefore(start) && !now.isAfter(end)
    }
    Column(Modifier.width(GutterWidth)) {
        repeat(ROWS) { index ->
            Column(
                modifier = Modifier
                    .height(CellHeight)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (index == currentSection) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Text(
                    text = DEFAULT_SECTION_TIMES.getOrNull(index)?.first?.let {
                        String.format("%d:%02d", it.hour, it.minute)
                    } ?: "",
                    fontSize = 8.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: Int,
    week: Int,
    width: Dp,
    isToday: Boolean,
    courses: List<Course>,
    onAddCourse: (Int, Int) -> Unit,
    onEditCourse: (Course) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(CellHeight * ROWS)
            .background(
                when {
                    isToday -> MiuixTheme.colorScheme.primary.copy(alpha = 0.045f)
                    day >= 6 -> Color(0xFF787880).copy(alpha = 0.04f)
                    else -> Color.Transparent
                }
            ),
    ) {
        Column {
            repeat(ROWS) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CellHeight)
                        .clickable { onAddCourse(day, index + 1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF787880).copy(alpha = 0.08f))
                    )
                }
            }
        }

        courses.forEach { course ->
            val top = CellHeight * (course.start - 1)
            val height = CellHeight * course.sectionCount - 4.dp
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .offset(y = top + 2.dp)
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(9.dp))
                    .background(CoursePalette.soft(course.color))
                    .clickable { onEditCourse(course) },
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(CoursePalette.accent(course.color))
                )
                Column(Modifier.padding(start = 7.dp, end = 5.dp, top = 4.dp, bottom = 3.dp)) {
                    Text(
                        text = course.name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 14.sp,
                    )
                    if (course.location.isNotBlank()) {
                        Text(
                            text = course.location,
                            fontSize = 9.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (isToday) {
            val now = LocalTime.now()
            val index = DEFAULT_SECTION_TIMES.indexOfFirst { (start, end) ->
                !now.isBefore(start) && !now.isAfter(end)
            }
            if (index >= 0) {
                val (start, end) = DEFAULT_SECTION_TIMES[index]
                val total = java.time.Duration.between(start, end).toMinutes().coerceAtLeast(1)
                val passed = java.time.Duration.between(start, now).toMinutes().coerceIn(0, total)
                val y = CellHeight * index + CellHeight * (passed.toFloat() / total)
                Box(
                    Modifier
                        .offset(y = y)
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .background(Color(0xFFF0433F))
                )
            }
        }
    }
}

@Suppress("unused")
@Composable
private fun EmptyScheduleHint() {
    Card(insideMargin = CardPadding) {
        Text(
            text = "还没有课程",
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
