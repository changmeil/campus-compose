package com.bhu.campus.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 把 WakeUp 课程表导出的数据转成我们自己的 [Course]。
 *
 * 支持两种输入：
 *  - **ICS（推荐）**：WakeUp → 右上角「导出」→「导出为 ICS」，或者它分享出来的 .ics 文件。
 *  - **JSON**：教务导出脚本 / WakeUp Windows 版那种 course 列表，字段名做了容错。
 *
 * 为什么不做「分享码」：WakeUp 6.x 的分享口令要在它自己服务器上换明文（要 APK 签名 + native
 * DES/RC4），第三方解不开，所以只走官方 ICS 导出这条路。
 *
 * 周次怎么定：不依赖用户填的开学日期，而是把「整张课表里最早的那节课所在的周」当作第 1 周，
 * 导入完成后顺手把学期起始日校准到这个周一。这样即使两边的开学日期设得不一样也不会错位。
 */
object WakeupImport {

    data class Parsed(
        val courses: List<Course>,
        /** 课表里最早那一周的周一（epochDay），用来校准「学期起始日」 */
        val firstMonday: Long?,
        /** 识别到的格式，用于界面上提示 */
        val kind: String,
        /** 解析过程中的提醒 */
        val notes: List<String>,
    )

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 节次时间和默认作息差超过这个分钟数，就认为时间对不上，记一条提醒。 */
    private const val SECTION_TOLERANCE_MIN = 30

    fun parse(raw: String, totalWeeks: Int = 18): Parsed {
        val text = raw.replace("\uFEFF", "").trim()
        if (text.isEmpty()) throw IllegalArgumentException("内容为空")
        return when {
            text.startsWith("{") || text.startsWith("[") -> parseJson(text, totalWeeks)
            text.contains("BEGIN:VCALENDAR", true) || text.contains("BEGIN:VEVENT", true) ->
                parseIcs(text, totalWeeks)
            // 分享码：一长串没有换行、没有冒号的字符
            text.length > 40 && !text.contains('\n') && !text.contains(':') ->
                throw IllegalArgumentException(
                    "这像是 WakeUp 的分享码，它是加密的，第三方解不开。" +
                        "请在 WakeUp 里选「导出 → 导出为 ICS」再导进来"
                )
            else -> throw IllegalArgumentException("没认出格式：需要 ICS 文件内容或 JSON")
        }
    }

    /* ------------------------------ ICS ------------------------------ */

    private class Event {
        var summary = ""
        var location = ""
        var description = ""
        var start: LocalDateTime? = null
        var end: LocalDateTime? = null
        var rrule = ""
    }

    private fun parseIcs(raw: String, totalWeeks: Int): Parsed {
        val notes = mutableListOf<String>()
        val events = mutableListOf<Event>()
        var cur: Event? = null
        var inEvent = false
        var noRrule = 0
        var mismatch = 0

        for (line in unfold(raw)) {
            val head = line.substringBefore(':').uppercase()
            when {
                head == "BEGIN" && line.contains("VEVENT", true) -> {
                    inEvent = true
                    cur = Event()
                }
                head == "END" && line.contains("VEVENT", true) -> {
                    cur?.let { if (it.start != null) events += it }
                    inEvent = false
                    cur = null
                }
                !inEvent || cur == null -> Unit
                else -> {
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val key = line.substring(0, idx).substringBefore(';').uppercase()
                    val value = unescape(line.substring(idx + 1).trim())
                    val ev = cur!!
                    when (key) {
                        "SUMMARY" -> ev.summary = value.trim()
                        "LOCATION" -> ev.location = value.trim()
                        "DESCRIPTION" -> ev.description = value
                        "DTSTART" -> ev.start = parseIcsTime(line.substring(idx + 1).trim())
                        "DTEND" -> ev.end = parseIcsTime(line.substring(idx + 1).trim())
                        "RRULE" -> ev.rrule = value
                    }
                }
            }
        }

        if (events.isEmpty()) {
            throw IllegalArgumentException("ICS 里没有课程（找不到 VEVENT）")
        }

        val firstDay = events.mapNotNull { it.start?.toLocalDate() }.minOrNull()
            ?: throw IllegalArgumentException("课程没有开始时间")
        val baseMonday = firstDay.minusDays((firstDay.dayOfWeek.value - 1).toLong())

        val merged = LinkedHashMap<String, Pair<Course, MutableSet<Int>>>()
        var colorSeed = 1

        events.forEach { ev ->
            val start = ev.start ?: return@forEach
            val name = cleanName(ev.summary)
            if (name.isEmpty()) return@forEach

            val day = start.dayOfWeek.value
            val startIdx = sectionOf(start.toLocalTime())
            val endIdx = ev.end?.let { sectionOf(it.toLocalTime()) } ?: startIdx
            val s = minOf(startIdx, endIdx)
            val e = maxOf(startIdx, endIdx)

            if (offBy(start.toLocalTime(), DEFAULT_SECTION_TIMES[s - 1].first) > SECTION_TOLERANCE_MIN) {
                mismatch++
            }

            val startWeek = weekOf(start.toLocalDate(), baseMonday)
            // DESCRIPTION 里的「第1-16周」比 RRULE 更贴近课表，优先用它
            val weeks = weeksFromDescription(ev.description)
                ?: weeksOf(ev.rrule, startWeek, baseMonday, totalWeeks)
            if (ev.rrule.isBlank() && weeks.size <= 1) noRrule++

            val location = ev.location.trim().ifBlank { field(ev.description, "地点", "教室", "上课地点") }
            val key = "$name|$location|$day|$s|$e"
            val exist = merged[key]
            val validWeeks = weeks.filter { it in 1..30 }
            if (exist == null) {
                merged[key] = Course(
                    name = name,
                    teacher = field(ev.description, "教师", "老师", "任课教师"),
                    location = location,
                    weekday = day,
                    start = s,
                    end = e,
                    weeks = validWeeks.distinct().sorted().ifEmpty { listOf(startWeek) },
                    color = ((colorSeed - 1) % 7) + 1,
                    source = SOURCE_WAKEUP,
                ) to validWeeks.toMutableSet()
                colorSeed++
            } else {
                exist.second += validWeeks
            }
        }

        val courses = merged.values
            .map { (course, weeks) ->
                course.copy(weeks = weeks.distinct().sorted().ifEmpty { course.weeks })
            }
            .sortedWith(compareBy({ it.weekday }, { it.start }, { it.name }))

        if (courses.isEmpty()) throw IllegalArgumentException("解析完成，但没有得到有效课程")

        if (mismatch > 0) {
            notes += "有 $mismatch 节课的上课时间和默认作息对不上，节次按最接近的一节估的"
        }
        if (noRrule > 0 && courses.size > 1) {
            notes += "有 $noRrule 条没有周次信息，只按出现的那一周记"
        }
        if (courses.any { it.location.isBlank() }) {
            notes += "部分课程没有地点"
        }

        return Parsed(
            courses = courses,
            firstMonday = baseMonday.toEpochDay(),
            kind = "ICS",
            notes = notes,
        )
    }

    /** ICS 的续行：行首一个空格或 tab 表示接上一行。 */
    private fun unfold(raw: String): List<String> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + line.substring(1)
            } else {
                out += line
            }
        }
        return out
    }

    private fun unescape(s: String): String = s
        .replace("\\n", "\n", true)
        .replace("\\N", "\n", true)
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    private fun parseIcsTime(value: String): LocalDateTime? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val utc = v.endsWith("Z", true)
        val s = if (utc) v.dropLast(1) else v
        val local = runCatching {
            when {
                s.length >= 15 && s[8] == 'T' -> LocalDateTime.parse(s.substring(0, 15), DATE_TIME)
                s.length == 8 -> LocalDate.parse(s, DATE_ONLY).atStartOfDay()
                else -> null
            }
        }.getOrNull() ?: return null
        return if (utc) {
            local.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        } else {
            local
        }
    }

    private fun rulePart(rrule: String, key: String): String? =
        rrule.split(';')
            .firstOrNull { it.startsWith("$key=", ignoreCase = true) }
            ?.substringAfter('=')

    private fun weeksOf(
        rrule: String,
        startWeek: Int,
        baseMonday: LocalDate,
        totalWeeks: Int,
    ): List<Int> {
        if (rrule.isBlank()) return listOf(startWeek)
        val interval = rulePart(rrule, "INTERVAL")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = rulePart(rrule, "COUNT")?.toIntOrNull()
        if (count != null) {
            return (0 until count.coerceIn(1, 60)).map { startWeek + it * interval }
        }
        val until = rulePart(rrule, "UNTIL")?.let { parseIcsTime(it) }
        val endWeek = if (until != null) {
            weekOf(until.toLocalDate(), baseMonday)
        } else {
            maxOf(startWeek, totalWeeks)
        }
        return (startWeek..endWeek step interval).toList()
    }

    private fun weekOf(date: LocalDate, baseMonday: LocalDate): Int =
        ((date.toEpochDay() - baseMonday.toEpochDay()) / 7).toInt() + 1

    /**
     * 从 DESCRIPTION 里抠周次，比如「第1-16周」「1,3,5-9周」「第1-15周(单周)」。
     * 认不出来返回 null，交给 RRULE 算。
     */
    private fun weeksFromDescription(description: String): List<Int>? {
        if (description.isBlank()) return null
        val m = Regex("(?:第)?\\s*(\\d{1,2}(?:\\s*[-~－,，、]\\s*\\d{1,2})*)\\s*周")
            .find(description) ?: return null
        val raw = m.groupValues[1]
            .replace('－', '-')
            .replace('~', '-')
            .replace('、', ',')
            .replace('，', ',')
        var weeks = expandWeeks(raw).filter { it in 1..30 }
        if (weeks.isEmpty()) return null
        val single = description.contains("单周")
        val dual = description.contains("双周") || description.contains("偶周")
        if (single && !dual) weeks = weeks.filter { it % 2 == 1 }
        if (dual && !single) weeks = weeks.filter { it % 2 == 0 }
        return weeks.ifEmpty { null }
    }

    /** 按开始时间找最接近的一节（1 起）。 */
    private fun sectionOf(time: java.time.LocalTime): Int {
        var best = 1
        var bestDiff = Long.MAX_VALUE
        DEFAULT_SECTION_TIMES.forEachIndexed { index, pair ->
            val diff = abs(ChronoUnit.MINUTES.between(time, pair.first))
            if (diff < bestDiff) {
                bestDiff = diff
                best = index + 1
            }
        }
        return best.coerceIn(1, DEFAULT_SECTION_TIMES.size)
    }

    private fun offBy(a: java.time.LocalTime, b: java.time.LocalTime): Long =
        abs(ChronoUnit.MINUTES.between(a, b))

    private fun cleanName(raw: String): String = raw
        .replace(Regex("\\[(必修|选修|限选|公选|任选)\\]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** 从 DESCRIPTION 里抠「教师：张三」这种字段。 */
    private fun field(description: String, vararg labels: String): String {
        if (description.isBlank()) return ""
        labels.forEach { label ->
            val m = Regex("$label\\s*[：:]\\s*([^\\n;；,，]+)").find(description)
            if (m != null) return m.groupValues[1].trim()
        }
        return ""
    }

    /* ------------------------------ JSON ------------------------------ */

    private fun parseJson(raw: String, totalWeeks: Int): Parsed {
        val root = runCatching { JSONTokener(raw).nextValue() }
            .getOrElse { throw IllegalArgumentException("JSON 解析失败：${it.message}") }

        val items = mutableListOf<JSONObject>()
        when (root) {
            is JSONArray -> for (i in 0 until root.length()) root.optJSONObject(i)?.let { items += it }
            is JSONObject -> {
                val list = root.optJSONArray("courses") ?: root.optJSONArray("courseList")
                when {
                    list != null -> for (i in 0 until list.length()) list.optJSONObject(i)?.let { items += it }
                    root.optJSONArray("course_bases") != null -> {
                        // WakeUp Windows 版 / 一些导出脚本：bases 存课名，details 存时间地点
                        val bases = root.optJSONArray("course_bases")!!
                        val details = root.optJSONArray("course_details") ?: JSONArray()
                        val byId = mutableMapOf<String, JSONObject>()
                        for (i in 0 until bases.length()) {
                            bases.optJSONObject(i)?.let { byId[it.optString("id")] = it }
                        }
                        for (i in 0 until details.length()) {
                            val d = details.optJSONObject(i) ?: continue
                            val b = byId[d.optString("course_id")] ?: continue
                            val merged = JSONObject(d.toString())
                            merged.put("name", b.optString("name"))
                            items += merged
                        }
                    }
                    root.optString("name").isNotBlank() -> items += root
                }
            }
        }

        if (items.isEmpty()) throw IllegalArgumentException("JSON 里没找到课程数组")

        val notes = mutableListOf<String>()
        var colorSeed = 1
        val out = mutableListOf<Course>()

        items.forEach { o ->
            val name = cleanName(
                firstString(o, "name", "courseName", "course", "title", "subject")
            )
            if (name.isEmpty()) return@forEach

            val day = firstInt(o, "day", "dayOfWeek", "weekday")?.coerceIn(1, 7)
                ?: firstInt(o, "day_of_week")?.coerceIn(1, 7)
                ?: return@forEach

            val sections = o.optJSONArray("sections")
            val start = when {
                sections != null && sections.length() > 0 ->
                    sections.optInt(0, 1).coerceAtLeast(1)
                else -> firstInt(o, "startSection", "start", "startNode", "start_section", "start_node")
                    ?.coerceAtLeast(1) ?: 1
            }
            val end = when {
                sections != null && sections.length() > 1 ->
                    sections.optInt(sections.length() - 1, start).coerceAtLeast(start)
                else -> firstInt(o, "endSection", "end", "endNode", "end_section", "end_node")
                    ?.coerceAtLeast(start) ?: start
            }

            val weeks = weeksOfJson(o, totalWeeks)
            if (weeks.isEmpty()) return@forEach

            out += Course(
                name = name,
                teacher = firstString(o, "teacher", "teachers", "instructor"),
                location = firstString(o, "location", "room", "position", "classroom"),
                weekday = day,
                start = start,
                end = end,
                weeks = weeks.distinct().sorted(),
                color = ((colorSeed - 1) % 7) + 1,
                source = SOURCE_WAKEUP,
            )
            colorSeed++
        }

        if (out.isEmpty()) throw IllegalArgumentException("解析完成，但没有得到有效课程")

        val sorted = out.sortedWith(compareBy({ it.weekday }, { it.start }, { it.name }))
        val firstWeek = sorted.minOfOrNull { it.weeks.minOrNull() ?: 1 } ?: 1
        val monday = LocalDate.now()
            .minusDays((LocalDate.now().dayOfWeek.value - 1).toLong())
            .minusWeeks((firstWeek - 1).toLong())

        notes += "JSON 里没有学期起始日，暂按本周往前推 ${firstWeek - 1} 周，可在设置里改"
        return Parsed(sorted, monday.toEpochDay(), "JSON", notes)
    }

    private fun weeksOfJson(o: JSONObject, totalWeeks: Int): List<Int> {
        o.optJSONArray("weeks")?.let { arr ->
            val list = mutableListOf<Int>()
            for (i in 0 until arr.length()) {
                val v = arr.optInt(i, 0)
                if (v > 0) list += v
            }
            if (list.isNotEmpty()) return list
        }
        val text = firstString(o, "weeks", "week", "weekStr", "weekRange", "week_range")
        if (text.isNotBlank()) {
            val expanded = expandWeeks(text)
            if (expanded.isNotEmpty()) return expanded
        }
        val from = firstInt(o, "startWeek", "start_week", "fromWeek")?.coerceAtLeast(1) ?: 1
        val to = firstInt(o, "endWeek", "end_week", "toWeek")?.coerceAtLeast(from) ?: totalWeeks
        return (from..to).toList()
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        keys.forEach { k ->
            if (!o.has(k)) return@forEach
            val s = o.optString(k).trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return ""
    }

    private fun firstInt(o: JSONObject, vararg keys: String): Int? {
        keys.forEach { k ->
            if (!o.has(k)) return@forEach
            val v = o.optInt(k, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) return v
        }
        return null
    }
}
