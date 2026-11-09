package com.bhu.campus.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 默认 12 节次时间（可在学习通同步后用真实节次表覆盖展示）。 */
val DEFAULT_SECTION_TIMES: List<Pair<LocalTime, LocalTime>> = listOf(
    LocalTime.of(8, 0) to LocalTime.of(8, 45),
    LocalTime.of(8, 55) to LocalTime.of(9, 40),
    LocalTime.of(10, 0) to LocalTime.of(10, 45),
    LocalTime.of(10, 55) to LocalTime.of(11, 40),
    LocalTime.of(13, 30) to LocalTime.of(14, 15),
    LocalTime.of(14, 25) to LocalTime.of(15, 10),
    LocalTime.of(15, 25) to LocalTime.of(16, 10),
    LocalTime.of(16, 20) to LocalTime.of(17, 5),
    LocalTime.of(18, 30) to LocalTime.of(19, 15),
    LocalTime.of(19, 25) to LocalTime.of(20, 10),
    LocalTime.of(20, 20) to LocalTime.of(21, 5),
    LocalTime.of(21, 15) to LocalTime.of(22, 0),
)

val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 课表格子固定 12 行，作息表也按 12 节算。 */
const val SECTION_COUNT = 12

/**
 * 把设置里存的 "HH:mm-HH:mm" 解析成作息表。
 *
 * 存的是字符串而不是对象，是为了跟着 SharedPreferences 的 JSON 走，
 * 也方便以后手工改。缺项、坏项一律回落到默认作息，保证长度永远是 12。
 */
fun sectionTimesFromStored(stored: List<String>): List<Pair<LocalTime, LocalTime>> {
    if (stored.isEmpty()) return DEFAULT_SECTION_TIMES
    val parsed = stored.map { raw ->
        val from = raw.substringBefore('-').trim()
        val to = raw.substringAfter('-', "").trim()
        runCatching { LocalTime.parse(from) to LocalTime.parse(to) }.getOrNull()
            ?.takeIf { it.second.isAfter(it.first) }
    }
    if (parsed.all { it == null }) return DEFAULT_SECTION_TIMES
    return DEFAULT_SECTION_TIMES.mapIndexed { i, def -> parsed.getOrNull(i) ?: def }
}

fun encodeSectionTimes(times: List<Pair<LocalTime, LocalTime>>): List<String> =
    times.map { (from, to) ->
        String.format("%02d:%02d-%02d:%02d", from.hour, from.minute, to.hour, to.minute)
    }

/** 08:05 这种展示用格式。 */
fun LocalTime.hhmm(): String = String.format("%02d:%02d", hour, minute)


private val zone: ZoneId get() = ZoneId.systemDefault()

fun LocalDate.toEpochMillis(time: LocalTime = LocalTime.MIDNIGHT): Long =
    atTime(time).atZone(zone).toInstant().toEpochMilli()

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
fun Long.toLocalTime(): LocalTime = Instant.ofEpochMilli(this).atZone(zone).toLocalTime()
fun Long.toLocalDateTime(): LocalDateTime = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

fun Long.formatTime(): String = toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm"))

fun Long.formatDue(): String {
    val d = toLocalDateTime()
    return d.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

fun Long.formatFull(): String = toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/** 简短截止：今天/明天/N 天 + 时分 */
fun formatDueShort(ms: Long): String {
    val date = ms.toLocalDate()
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(today, date)
    val time = ms.formatTime()
    return when {
        days == 0L -> "今天 $time"
        days == 1L -> "明天 $time"
        days == 2L -> "后天 $time"
        else -> ms.toLocalDateTime().format(DateTimeFormatter.ofPattern("M/d HH:mm"))
    }
}

fun formatDateTimeShort(ms: Long): String =
    ms.toLocalDateTime().format(DateTimeFormatter.ofPattern("M/d HH:mm"))

fun Long.isSameDay(other: Long): Boolean = toLocalDate() == other.toLocalDate()

/** 第 n 周（1 基）；未开学返回 0，超出总周数返回大于总周数。 */
fun weekIndexOf(date: LocalDate, startEpochDay: Long): Int {
    val start = LocalDate.ofEpochDay(startEpochDay)
    val days = ChronoUnit.DAYS.between(start, date)
    if (days < 0) return 0
    return (days / 7).toInt() + 1
}

fun weekStartDate(week: Int, startEpochDay: Long): LocalDate =
    LocalDate.ofEpochDay(startEpochDay).plusWeeks((week - 1).toLong())

fun todayWeekday(): Int = LocalDate.now().dayOfWeek.value

/* ---------------- 周次文本 ---------------- */

fun expandWeeks(text: String): List<Int> {
    val out = mutableListOf<Int>()
    text.split(",", "，", " ").filter { it.isNotBlank() }.forEach { part ->
        val p = part.trim()
        if (p.contains("-") || p.contains("~")) {
            val bits = p.split("-", "~")
            val a = bits.getOrNull(0)?.trim()?.toIntOrNull()
            val b = bits.getOrNull(1)?.trim()?.toIntOrNull()
            if (a != null && b != null) {
                val from = minOf(a, b)
                val to = maxOf(a, b)
                for (w in from..to) if (w in 1..30) out += w
            }
        } else {
            p.toIntOrNull()?.let { if (it in 1..30) out += it }
        }
    }
    return out.distinct().sorted()
}

/**
 * 把周次列表压成「1-16」「1,3,5-9」这种文本。
 *
 * 注意：这里必须先把「当前区间」攒出来再吐出去，不能像老版本那样
 * 每遇到一个连续数字就去改上一次的输出——老写法在第一个数就连续时
 * （比如 1..16）会对空列表 removeAt(-1)，直接 IndexOutOfBounds 崩掉。
 */
fun compressWeeks(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val sorted = weeks.distinct().sorted()
    val out = mutableListOf<String>()
    var start = sorted.first()
    var prev = start

    fun flush() {
        out += if (start == prev) start.toString() else "$start-$prev"
    }

    for (w in sorted.drop(1)) {
        if (w == prev + 1) {
            prev = w
        } else {
            flush()
            start = w
            prev = w
        }
    }
    flush()
    return out.joinToString(",")
}

/* ---------------- 倒计时 ---------------- */

enum class Tone { Normal, Warn, Danger, Done }

data class Countdown(val text: String, val tone: Tone)

fun countdownOf(dueAt: Long?, done: Boolean): Countdown {
    if (done) return Countdown("已完成", Tone.Done)
    if (dueAt == null) return Countdown("未设截止", Tone.Normal)
    val now = System.currentTimeMillis()
    val diff = dueAt - now
    val dayMs = 24L * 60 * 60 * 1000
    if (diff < 0) {
        val overdue = -diff
        val days = (overdue + dayMs - 1) / dayMs
        return Countdown(if (days <= 0) "刚刚截止" else "已逾期 ${days} 天", Tone.Danger)
    }
    val days = diff / dayMs
    return when {
        days < 1 -> Countdown("今天 ${dueAt.formatTime()}", Tone.Danger)
        days < 2 -> Countdown("明天 ${dueAt.formatTime()}", Tone.Warn)
        days < 4 -> Countdown("${days} 天后", Tone.Warn)
        else -> Countdown("${days} 天后", Tone.Normal)
    }
}

/** 解析任务截止时间输入，支持 2026-11-08 23:59 / 11-8 23:59 / 11月8日 23:59 / 明天 23:59。 */
fun parseDueInput(text: String): Long? {
    val raw = text.trim()
    if (raw.isEmpty()) return null
    val now = LocalDateTime.now()
    val timeRegex = Regex("(\\d{1,2})[:：](\\d{2})")
    val timeMatch = timeRegex.find(raw)
    val time = if (timeMatch != null) {
        LocalTime.of(
            timeMatch.groupValues[1].toInt().coerceIn(0, 23),
            timeMatch.groupValues[2].toInt().coerceIn(0, 59)
        )
    } else LocalTime.of(23, 59)

    Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(raw)?.let { m ->
        val date = runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull() ?: return@let
        return date.toEpochMillis(time)
    }
    Regex("(\\d{1,2})月(\\d{1,2})日").find(raw)?.let { m ->
        val date = runCatching {
            var d = LocalDate.of(now.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            if (d.isBefore(LocalDate.now().minusMonths(4))) d = d.plusYears(1)
            d
        }.getOrNull() ?: return@let
        return date.toEpochMillis(time)
    }
    Regex("(\\d{1,2})-(\\d{1,2})").find(raw)?.let { m ->
        val date = runCatching {
            var d = LocalDate.of(now.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            if (d.isBefore(LocalDate.now().minusMonths(4))) d = d.plusYears(1)
            d
        }.getOrNull() ?: return@let
        return date.toEpochMillis(time)
    }
    val base = when {
        raw.contains("后天") -> LocalDate.now().plusDays(2)
        raw.contains("明天") -> LocalDate.now().plusDays(1)
        raw.contains("今天") || raw.contains("今晚") -> LocalDate.now()
        else -> null
    }
    return base?.toEpochMillis(time)
}

fun LocalDate.chineseDate(): String {
    val dow = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        else -> "星期日"
    }
    return "${monthValue} 月 ${dayOfMonth} 日 · $dow"
}
