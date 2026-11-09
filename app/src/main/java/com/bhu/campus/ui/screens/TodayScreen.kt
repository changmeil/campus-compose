package com.bhu.campus.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.icon.extended.Weeks
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.arrangeOrder
import com.bhu.campus.data.SCHEDULE_WAKEUP
import com.bhu.campus.data.Express
import com.bhu.campus.data.Tone
import com.bhu.campus.data.WeatherInfo
import com.bhu.campus.data.chineseDate
import com.bhu.campus.data.countdownOf
import com.bhu.campus.data.formatTime
import com.bhu.campus.data.weekIndexOf
import com.bhu.campus.ui.CardListPadding
import com.bhu.campus.ui.CardPadding
import com.bhu.campus.ui.CoursePalette
import com.bhu.campus.ui.EmptyHint
import com.bhu.campus.ui.HeroStat
import com.bhu.campus.ui.Pill
import com.bhu.campus.ui.ReorderableColumn
import com.bhu.campus.ui.SectionHeader
import java.time.LocalDate
import java.time.LocalTime

@Composable
private fun WeatherBlock(weather: WeatherInfo?) {
    if (weather == null) return
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = weather.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = "${weather.temp}°",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${weather.text} ${weather.low}°~${weather.high}°",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.78f),
        )
        if (weather.city.isNotBlank()) {
            Text(
                text = weather.city,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
fun TodayScreen(
    contentPadding: PaddingValues,
    onOpenSchedule: () -> Unit,
    onOpenTasks: () -> Unit,
    onSyncXxt: () -> Unit,
    onLoginXxt: () -> Unit,
    onImportWakeup: () -> Unit,
    syncing: Boolean,
) {
    val courses = CampusStore.courses
    val tasks = CampusStore.tasks
    val settings = CampusStore.settings
    val xxt = CampusStore.xxt
    val weather = CampusStore.weather
    val context = LocalContext.current
    // 作息跟着设置走，导入 WakeUp 后会变成学校真实的那份
    val sectionTimes = remember(settings.sectionTimes) { settings.effectiveSectionTimes }
    val wakeupSource = settings.scheduleSource == SCHEDULE_WAKEUP
    val order = arrangeOrder(settings.todayOrder, listOf("hero", "courses", "tasks", "express", "xxt"))
    // 三个拼多多驿站靠 CampusProbe 模块改写 props.url 才能直达，没模块就别摆出来
    val probeInstalled = remember { Express.hasProbe(context) }
    val today = LocalDate.now()
    val week = weekIndexOf(today, settings.semesterStart)
    val weekday = today.dayOfWeek.value
    val todayCourses = courses
        .filter { it.weekday == weekday && week > 0 && week in it.weeks }
        .sortedBy { it.start }
    val pending = tasks.filter { !it.done }
        .sortedWith(compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.title }))
    val overdueCount = pending.count { it.dueAt != null && it.dueAt < System.currentTimeMillis() }
    val now = LocalTime.now()

    Box(Modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 6.dp,
                bottom = contentPadding.calculateBottomPadding() + 20.dp,
            ),
    ) {
        ReorderableColumn(
            keys = order,
            onOrderChange = { next -> CampusStore.updateSettings { it.copy(todayOrder = next) } },
        ) { key ->
        when (key) {
        "hero" -> {
            Card(
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primary),
                insideMargin = PaddingValues(0.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = today.chineseDate(),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.82f),
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = if (week <= 0) "假期中" else "第 $week 周",
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                                if (week > settings.totalWeeks) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "学期已结束",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                }
                            }
                        }
                        WeatherBlock(weather)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        HeroStat(todayCourses.size.toString(), "今天的课")
                        HeroStat(pending.size.toString(), "未完成")
                        HeroStat(overdueCount.toString(), "已逾期")
                    }
                }
            }
        }

        "courses" -> {
            SectionHeader("今天的课", trailing = {
                TextButton(text = "课程表", onClick = onOpenSchedule)
            })
            Card(insideMargin = CardListPadding) {
                if (todayCourses.isEmpty()) {
                    EmptyHint(MiuixIcons.Info, "今天没有课，好好休息")
                } else {
                    todayCourses.forEachIndexed { index, course ->
                        if (index > 0) HorizontalDivider()
                        val startTime = sectionTimes.getOrNull(course.start - 1)?.first
                        val endTime = sectionTimes.getOrNull(course.end - 1)?.second
                        val isNow = startTime != null && endTime != null &&
                            !now.isBefore(startTime) && !now.isAfter(endTime)
                        val isPast = endTime != null && now.isAfter(endTime)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.width(46.dp)) {
                                Text(
                                    text = if (course.start == course.end) "${course.start}"
                                    else "${course.start}-${course.end}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPast) MiuixTheme.colorScheme.onBackgroundVariant
                                    else MiuixTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = startTime?.formatTimeShort() ?: "第 ${course.start} 节",
                                    fontSize = 10.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                            }
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(CoursePalette.accent(course.color).copy(alpha = if (isPast) 0.4f else 1f))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = course.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isPast) MiuixTheme.colorScheme.onBackgroundVariant
                                    else MiuixTheme.colorScheme.onBackground,
                                )
                                val meta = listOfNotNull(
                                    course.location.takeIf { it.isNotBlank() },
                                    course.teacher.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                            if (isNow) {
                                Pill(
                                    text = "正在进行",
                                    color = MiuixTheme.colorScheme.primary,
                                    background = MiuixTheme.colorScheme.primaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }

        "tasks" -> {
            SectionHeader("待办提醒", trailing = {
                TextButton(text = "全部", onClick = onOpenTasks)
            })
            Card(insideMargin = CardListPadding) {
                if (pending.isEmpty()) {
                    EmptyHint(MiuixIcons.Tasks, "没有待办，保持住")
                } else {
                    pending.take(4).forEachIndexed { index, task ->
                        if (index > 0) HorizontalDivider()
                        val countdown = countdownOf(task.dueAt, task.done)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (task.isExam) Color(0xFFF0433F) else Color(0xFF3482FF)
                                    )
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = task.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val meta = listOfNotNull(
                                    task.course.takeIf { it.isNotBlank() },
                                    task.dueAt?.let { com.bhu.campus.data.formatDueShort(it) },
                                ).joinToString(" · ")
                                Text(
                                    text = meta.ifBlank { if (task.isExam) "考试" else "作业" },
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Pill(
                                text = countdown.text,
                                color = toneColor(countdown.tone),
                                background = toneSoft(countdown.tone),
                            )
                        }
                    }
                }
            }
        }

        "express" -> {
            SectionHeader("取快递")
            Card(insideMargin = CardPadding) {
                val rows = buildList {
                    add(
                        ExpressAction(
                            title = "菜鸟驿站 · 待取件",
                            subtitle = "淘宝里的菜鸟驿站，包裹和取件码都在这",
                            onClick = {
                                if (!Express.openCainiao(context)) {
                                    Toast.makeText(context, "没能打开淘宝，请确认已安装", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ),
                    )
                    if (probeInstalled) {
                        // 装了 CampusProbe 模块才放这三个驿站，否则点进去是 404 页
                        Express.STATIONS.forEach { station ->
                            add(
                                ExpressAction(
                                    title = station.name,
                                    subtitle = station.note,
                                    onClick = {
                                        if (!Express.openPddStation(context, station.code)) {
                                            Toast.makeText(context, "没能打开拼多多，请确认已安装", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                ),
                            )
                        }
                    } else {
                        add(
                            ExpressAction(
                                title = "拼多多驿站 · 一点直达",
                                subtitle = "需要 CampusProbe 模块，在 LSPosed 里启用后可用",
                                onClick = {
                                    Toast.makeText(
                                        context,
                                        "没检测到 CampusProbe 模块，去 LSPosed 里启用它",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            ),
                        )
                    }
                }
                Column {
                    rows.forEachIndexed { index, row ->
                        ExpressRow(title = row.title, subtitle = row.subtitle, onClick = row.onClick)
                        if (index != rows.lastIndex) HorizontalDivider()
                    }
                }
            }
        }

        "xxt" -> {
            SectionHeader(if (wakeupSource) "课表来源 · WakeUp" else "课表来源 · 学习通")
            Card(insideMargin = CardPadding) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (wakeupSource) MiuixIcons.Weeks else MiuixIcons.Update,
                            contentDescription = null,
                            tint = if (wakeupSource || xxt.loggedIn) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (wakeupSource) {
                                    if (courses.isEmpty()) "还没有导入课表"
                                    else "已导入 ${courses.size} 门课"
                                } else {
                                    when {
                                        !xxt.loggedIn -> "未登录"
                                        xxt.userName.isNotBlank() -> "${xxt.userName} 已登录"
                                        else -> "已登录"
                                    }
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (wakeupSource) {
                                    "在 WakeUp 里导出为 ICS，再导进来就是最新课表；上课时间也会一起校准"
                                } else {
                                    when {
                                        !xxt.loggedIn -> "登录后可直接同步课程表、作业和考试"
                                        xxt.hasSynced -> "上次同步 ${com.bhu.campus.data.formatDateTimeShort(xxt.lastSync)}" +
                                            (if (xxt.semesterLabel.isNotBlank()) " · ${xxt.semesterLabel}" else "")
                                        else -> "已登录，还没同步过"
                                    }
                                },
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        wakeupSource -> TextButton(
                            text = if (courses.isEmpty()) "导入 WakeUp 课表" else "重新导入",
                            onClick = onImportWakeup,
                        )
                        xxt.loggedIn -> TextButton(
                            text = if (syncing) "同步中…" else "同步课表与作业",
                            onClick = { if (!syncing) onSyncXxt() },
                        )
                        else -> TextButton(text = "登录学习通", onClick = onLoginXxt)
                    }
                }
            }
        }
        }
        }
    }
    }
}

fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Danger -> Color(0xFFF0433F)
    Tone.Warn -> Color(0xFFF0910C)
    Tone.Done -> Color(0xFF34B45A)
    Tone.Normal -> Color(0xFF3482FF)
}

fun toneSoft(tone: Tone): Color = when (tone) {
    Tone.Danger -> Color(0x14F0433F)
    Tone.Warn -> Color(0x18F0910C)
    Tone.Done -> Color(0x1834B45A)
    Tone.Normal -> Color(0x143482FF)
}

private fun LocalTime.formatTimeShort(): String =
    String.format("%02d:%02d", hour, minute)

/** 「取快递」里的一行数据，攒够了一次性渲染，好统一加分隔线。 */
private data class ExpressAction(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

/** 「取快递」里的一行：标题 + 说明 + 右侧箭头，整行可点。 */
@Composable
private fun ExpressRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
