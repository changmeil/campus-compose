package com.bhu.campus.data

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * 学习通 Web 端接口的最小实现。
 *
 * 登录态来自原生 WebView（见 ChaoxingLoginActivity），把它 CookieManager 里的
 * cookie 汇总成一条 Cookie 头存起来，后续所有请求都带着它走。
 */
object ChaoxingClient {

    private const val PREFS = "campus_v2_cookie"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private const val URL_BASE = "https://i.chaoxing.com/base"
    private const val URL_COURSELIST = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata"
    private const val URL_SCHEDULE = "https://kb.chaoxing.com/pc/curriculum/getMyLessons"
    private const val URL_STUMIDDLE = "https://mooc1.chaoxing.com/visit/stucoursemiddle"
    private const val URL_WORKLIST = "https://mooc1.chaoxing.com/mooc2/work/list"
    private const val URL_EXAMLIST = "https://mooc1-api.chaoxing.com/exam-ans/exam/test/examcode/examlist"

    private var cookieHeader: String = ""

    /* ---------------- Cookie ---------------- */

    fun loadCookies(context: Context) {
        cookieHeader = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cookie", "") ?: ""
    }

    fun hasCookie(context: Context): Boolean {
        if (cookieHeader.isBlank()) loadCookies(context)
        return cookieHeader.contains("_uid=")
    }

    fun clearCookies(context: Context) {
        cookieHeader = ""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("cookie").apply()
    }

    /** 登录成功后，把 WebView 里各域名的 cookie 合并成一条。 */
    fun collectFromWebView(context: Context) {
        val manager = CookieManager.getInstance()
        val map = linkedMapOf<String, String>()
        mergeInto(map, cookieHeader)
        listOf(
            "https://i.chaoxing.com",
            "https://passport2.chaoxing.com",
            "https://mooc1.chaoxing.com",
            "https://mooc1-api.chaoxing.com",
            "https://mooc2-ans.chaoxing.com",
            "https://kb.chaoxing.com",
            "https://notice.chaoxing.com",
        ).forEach { domain ->
            runCatching { manager.getCookie(domain) }.getOrNull()?.let { mergeInto(map, it) }
        }
        cookieHeader = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cookie", cookieHeader).apply()
    }

    private fun mergeInto(map: MutableMap<String, String>, raw: String?) {
        if (raw.isNullOrBlank()) return
        raw.split(";").forEach { piece ->
            val kv = piece.trim()
            val i = kv.indexOf('=')
            if (i > 0) map[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
        }
    }

    /* ---------------- HTTP ---------------- */

    private fun encodeForm(form: Map<String, String>): String = form.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private fun request(url: String, method: String = "GET", form: Map<String, String>? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Accept", "text/html,application/json,*/*")
            if (cookieHeader.isNotBlank()) setRequestProperty("Cookie", cookieHeader)
        }
        if (form != null) {
            val body = encodeForm(form).toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val text = runCatching {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        }.getOrDefault("")

        // 回写 Set-Cookie，保持会话新鲜
        val setCookies = conn.headerFields.entries
            .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value ?: emptyList() }
        if (setCookies.isNotEmpty()) {
            val map = linkedMapOf<String, String>()
            mergeInto(map, cookieHeader)
            setCookies.forEach { mergeInto(map, it.substringBefore(';')) }
            cookieHeader = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("网络请求失败（HTTP $code）")
        return text
    }

    private fun looksLoggedOut(html: String): Boolean =
        html.contains("请重新登录") || html.contains("passport2.chaoxing.com") && html.contains("登录")

    /* ---------------- 解析工具 ---------------- */

    private fun unescape(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun hiddenValue(html: String, id: String): String {
        Regex("id=\"$id\"[^>]*value=\"([^\"]*)\"").find(html)?.let { return it.groupValues[1] }
        Regex("value=\"([^\"]*)\"[^>]*id=\"$id\"").find(html)?.let { return it.groupValues[1] }
        return ""
    }

    private fun stripTags(html: String): String {
        val noScript = html.replace(Regex("<script[\\s\\S]*?</script>"), " ")
            .replace(Regex("<style[\\s\\S]*?</style>"), " ")
        return unescape(noScript.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /* ---------------- 账号 ---------------- */

    data class Profile(val name: String, val school: String, val fid: String)

    fun fetchProfile(): Profile {
        val html = request(URL_BASE)
        val name = Regex("账号：([^<\"\\s]{1,20})").find(html)?.groupValues?.get(1)
            ?: Regex("aria-label=\"账号：([^\"]{1,20})\"").find(html)?.groupValues?.get(1)
            ?: "我"
        val school = Regex("id=\"siteName\"[^>]*title=\"([^\"]+)\"").find(html)
            ?.groupValues?.get(1) ?: ""
        val fid = Regex("\"fid\"\\s*:\\s*\"?(\\d+)\"?").find(html)?.groupValues?.get(1)
            ?: Regex("var fid = '(\\d+)'").find(html)?.groupValues?.get(1) ?: "0"
        return Profile(name, school, fid)
    }

    /* ---------------- 课程列表 ---------------- */

    data class XxtCourse(
        val name: String,
        val teacher: String,
        val courseId: String,
        val clazzId: String,
        val cpi: String,
    )

    fun fetchCourses(fid: String): List<XxtCourse> {
        val url = URL_COURSELIST + "?" + encodeForm(
            mapOf(
                "courseType" to "1",
                "courseFid" to fid.ifBlank { "0" },
                "courseTeacherid" to "0",
                "courseOrderBy" to "0",
            )
        )
        val html = request(url)
        val out = mutableListOf<XxtCourse>()
        val seen = mutableSetOf<String>()
        Regex("stucoursemiddle\\?courseid=(\\d+)&clazzid=(\\d+)&cpi=(\\d+)").findAll(html).forEach { m ->
            val key = m.groupValues.drop(1).joinToString("|")
            if (seen.add(key)) {
                val seg = html.substring(m.range.last + 1, minOf(html.length, m.range.last + 2500))
                val name = Regex("course-name[^>]*title=\"([^\"]+)\"").find(seg)
                    ?.groupValues?.get(1)?.let(::unescape) ?: "未命名课程"
                val teacher = Regex("<p class=\"line2 color3\"[^>]*title=\"([^\"]+)\"").find(seg)
                    ?.groupValues?.get(1)?.let(::unescape) ?: ""
                out += XxtCourse(name, teacher, m.groupValues[1], m.groupValues[2], m.groupValues[3])
            }
        }
        return out
    }

    /* ---------------- 课表 ---------------- */

    data class Curriculum(
        val maxWeek: Int = 20,
        val firstWeekEpochDay: Long? = null,
        val schoolYear: String = "",
        val semester: String = "",
        val userName: String = "",
        val sectionTimes: List<Pair<String, String>> = emptyList(),
    )

    private data class WeekResponse(val curriculum: Curriculum, val lessons: List<JSONObject>)

    private fun parseWeek(json: JSONObject): WeekResponse {
        val data = json.optJSONObject("data") ?: JSONObject()
        val cur = data.optJSONObject("curriculum") ?: JSONObject()
        val arr = data.optJSONArray("lessonArray") ?: data.optJSONArray("lessons")
        val lessons = mutableListOf<JSONObject>()
        if (arr != null) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { lessons += it }
        }
        val times = mutableListOf<Pair<String, String>>()
        cur.optJSONArray("lessonTimeConfigArray")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optString(i)
                val m = Regex("(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})").find(s)
                if (m != null) times += m.groupValues[1] to m.groupValues[2]
            }
        }
        val firstDate = cur.optLong("firstWeekDate", 0L)
        val epochDay = if (firstDate > 0) {
            java.time.Instant.ofEpochMilli(firstDate).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        } else null
        return WeekResponse(
            curriculum = Curriculum(
                maxWeek = (cur.optInt("maxWeek", 20).takeIf { it > 0 } ?: 20).coerceAtMost(30),
                firstWeekEpochDay = epochDay,
                schoolYear = cur.optString("schoolYear"),
                semester = cur.optString("semester"),
                userName = cur.optString("userName"),
                sectionTimes = times,
            ),
            lessons = lessons,
        )
    }

    private fun requestWeek(week: Int?): WeekResponse {
        val body = if (week == null) emptyMap() else mapOf("week" to week.toString())
        val text = request(URL_SCHEDULE, "POST", body)
        val json = JSONObject(text)
        if (json.optInt("result", 0) != 1) {
            throw IllegalStateException(json.optString("msg").ifBlank { "课表接口返回失败" })
        }
        return parseWeek(json)
    }

    /** 逐周拉全学期，聚合同一节课的周次。 */
    fun fetchFullSchedule(onProgress: (String, Float) -> Unit): Pair<Curriculum, List<Course>> {
        onProgress("读取课表信息…", 0.05f)
        val first = requestWeek(null)
        val curriculum = first.curriculum
        val merged = linkedMapOf<String, MutableMap<String, Any>>()

        val total = curriculum.maxWeek.coerceIn(1, 30)
        var emptyStreak = 0
        for (week in 1..total) {
            onProgress("拉取第 $week 周课表…", 0.1f + 0.45f * week / total)
            val resp = runCatching { requestWeek(week) }.getOrNull() ?: continue
            if (resp.lessons.isEmpty()) {
                emptyStreak++
                if (emptyStreak >= 4) break
                continue
            }
            emptyStreak = 0
            resp.lessons.forEach { les ->
                val name = les.optString("name").ifBlank { return@forEach }
                val key = listOf(
                    les.optString("lessonId", name),
                    les.optInt("dayOfWeek").toString(),
                    les.optInt("beginNumber").toString(),
                    les.optInt("length").toString(),
                    les.optString("location"),
                    les.optString("teacherName"),
                    name,
                ).joinToString("|")
                val slot = merged.getOrPut(key) {
                    mutableMapOf(
                        "name" to name,
                        "teacher" to les.optString("teacherName"),
                        "location" to les.optString("location"),
                        "weekday" to les.optInt("dayOfWeek", 1),
                        "start" to les.optInt("beginNumber", 1),
                        "length" to les.optInt("length", 2),
                        "weeks" to mutableListOf<Int>(),
                    )
                }
                @Suppress("UNCHECKED_CAST")
                (slot["weeks"] as MutableList<Int>).add(week)
            }
        }

        val courses = merged.values.mapNotNull { slot ->
            val weekday = (slot["weekday"] as Int).coerceIn(1, 7)
            val start = (slot["start"] as Int).coerceIn(1, 12)
            val length = (slot["length"] as Int).coerceAtLeast(1)
            @Suppress("UNCHECKED_CAST")
            val weeks = (slot["weeks"] as MutableList<Int>).distinct().sorted()
            if (weeks.isEmpty()) return@mapNotNull null
            val name = slot["name"] as String
            Course(
                id = "xxt-" + listOf(name, weekday, start, slot["location"], slot["teacher"])
                    .joinToString("-").hashCode(),
                name = name,
                teacher = slot["teacher"] as String,
                location = slot["location"] as String,
                weekday = weekday,
                start = start,
                end = (start + length - 1).coerceAtMost(12),
                weeks = weeks,
                color = colorFor(name),
                source = SOURCE_XXT,
            )
        }.sortedWith(compareBy({ it.weekday }, { it.start }))

        return curriculum to courses
    }

    private fun colorFor(name: String): Int {
        var hash = 0
        name.forEach { hash = hash * 31 + it.code }
        return (Math.abs(hash) % 7) + 1
    }

    /* ---------------- 作业 ---------------- */

    private data class XxtHomework(val title: String, val status: String, val url: String)

    private fun homeworkList(course: XxtCourse): List<XxtHomework> {
        val page = request(
            URL_STUMIDDLE + "?" + encodeForm(
                mapOf(
                    "courseid" to course.courseId,
                    "clazzid" to course.clazzId,
                    "cpi" to course.cpi,
                    "ismooc2" to "1",
                    "v" to "2",
                )
            )
        )
        val workEnc = hiddenValue(page, "workEnc")
        if (workEnc.isBlank()) return emptyList()
        val openc = hiddenValue(page, "openc")
        val pageEnc = hiddenValue(page, "enc")
        val url = URL_WORKLIST + "?" + encodeForm(
            mapOf(
                "courseId" to course.courseId,
                "classId" to course.clazzId,
                "cpi" to course.cpi,
                "ut" to "s",
                "openc" to openc,
                "enc" to workEnc,
                "t" to System.currentTimeMillis().toString(),
                "stuenc" to pageEnc,
                "isdisplaytable" to "2",
            )
        )
        val html = request(url)
        if (html.contains("没有此页面访问权限")) return emptyList()
        val out = mutableListOf<XxtHomework>()
        Regex(
            "<li onclick=\"goTask\\(this\\);[^\"]*\"\\s+data=\"([^\"]+)\"[\\s\\S]*?" +
                "<p class=\"overHidden2 fl\">([^<]+)</p>[\\s\\S]*?<p class=\"status fl\">([^<]*)</p>"
        ).findAll(html).forEach { m ->
            val rawUrl = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }
                .getOrDefault(m.groupValues[1])
            out += XxtHomework(
                title = unescape(m.groupValues[2]).trim(),
                status = m.groupValues[3].trim(),
                url = rawUrl,
            )
        }
        return out
    }

    private fun isPending(status: String): Boolean {
        val marks = listOf("已完成", "已提交", "待批阅", "已批阅", "已批改")
        return marks.none { status.contains(it) }
    }

    private fun parseHomeworkDeadline(html: String): String? {
        val text = stripTags(html)
        val m = Regex(
            "作答时间[:：]\\s*(\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2})\\s*至\\s*" +
                "(\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2})"
        ).find(text) ?: return null
        return m.groupValues[2]
    }

    /** "MM-DD HH:MM" → 与今天最接近的年份补全。 */
    private fun nearestMillis(monthDay: String): Long? {
        val m = Regex("(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})").find(monthDay.trim()) ?: return null
        val (mo, d, hh, mm) = m.destructured
        val today = LocalDate.now()
        var best: LocalDateTime? = null
        for (year in listOf(today.year - 1, today.year, today.year + 1)) {
            val candidate = runCatching {
                LocalDateTime.of(year, mo.toInt(), d.toInt(), hh.toInt(), mm.toInt())
            }.getOrNull() ?: continue
            if (best == null ||
                kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(candidate.toLocalDate(), today)) <
                kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(best!!.toLocalDate(), today))
            ) {
                best = candidate
            }
        }
        return best?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    }

    fun fetchHomework(
        courses: List<XxtCourse>,
        onProgress: (String, Float) -> Unit,
    ): List<TaskItem> {
        val out = mutableListOf<TaskItem>()
        courses.forEachIndexed { index, course ->
            onProgress("检查作业：${course.name}", 0.55f + 0.3f * index / courses.size.coerceAtLeast(1))
            val list = runCatching { homeworkList(course) }.getOrDefault(emptyList())
            list.filter { isPending(it.status) }.forEach { hw ->
                val deadline = if (hw.url.startsWith("http")) {
                    runCatching {
                        parseHomeworkDeadline(request(hw.url))?.let(::nearestMillis)
                    }.getOrNull()
                } else null
                out += TaskItem(
                    id = "xxt-hw-" + (course.name + hw.title + deadline).hashCode(),
                    type = TYPE_HOMEWORK,
                    title = hw.title.ifBlank { "未命名作业" },
                    course = course.name,
                    dueAt = deadline,
                    note = hw.status,
                    source = SOURCE_XXT,
                )
            }
        }
        return out
    }

    /* ---------------- 考试 ---------------- */

    fun fetchExams(onProgress: (String, Float) -> Unit): List<TaskItem> {
        onProgress("拉取考试安排…", 0.9f)
        val html = runCatching { request(URL_EXAMLIST) }.getOrDefault("")
        if (html.isBlank()) return emptyList()
        val out = mutableListOf<TaskItem>()
        Regex("<tr[^>]*class=\"dataTr\"[^>]*>([\\s\\S]*?)</tr>").findAll(html).forEach { row ->
            val cells = Regex("<td[^>]*>([\\s\\S]*?)</td>").findAll(row.groupValues[1])
                .map { unescape(it.groupValues[1].replace(Regex("<[^>]+>"), "")).trim() }
                .toList()
            if (cells.size >= 8) {
                val name = cells[1]
                val timeText = cells[2]
                if (name.isBlank()) return@forEach
                val start = Regex("(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2})").find(timeText)
                val end = Regex("至\\s*(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2})").find(timeText)
                val due = (end ?: start)?.let { m ->
                    runCatching {
                        LocalDateTime.of(
                            LocalDate.parse(m.groupValues[1]),
                            java.time.LocalTime.parse(m.groupValues[2].let { if (it.length == 4) "0$it" else it })
                        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                }
                out += TaskItem(
                    id = "xxt-exam-" + (name + due).hashCode(),
                    type = TYPE_EXAM,
                    title = name,
                    course = "",
                    dueAt = due,
                    note = cells.getOrElse(4) { "" },
                    source = SOURCE_XXT,
                )
            }
        }
        return out.distinctBy { it.title + "|" + it.dueAt }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
    }

    /* ---------------- 同步编排 ---------------- */

    data class SyncReport(
        val courses: Int,
        val tasks: Int,
        val exams: Int,
        val userName: String,
        val school: String,
        val semesterLabel: String,
        val weekStartEpochDay: Long?,
        val warnings: List<String>,
    )

    suspend fun sync(context: Context, onProgress: (String, Float) -> Unit): SyncReport = withContext(Dispatchers.IO) {
        loadCookies(context)
        if (!hasCookie(context)) throw IllegalStateException("还没有登录学习通")

        val warnings = mutableListOf<String>()
        onProgress("校验登录状态…", 0.02f)
        val profile = runCatching { fetchProfile() }.getOrElse {
            throw IllegalStateException("登录已失效，请重新登录学习通")
        }
        if (!profile.fid.isNullOrBlank() && profile.fid != "0") {
            runCatching { fetchCourses(profile.fid) }.getOrElse { err ->
                if (err.message?.contains("登录") == true) throw IllegalStateException("登录已失效，请重新登录学习通")
            }
        }

        val courses = runCatching { fetchCourses(profile.fid) }.getOrDefault(emptyList())
        if (courses.isEmpty()) warnings += "没取到课程列表"

        val (curriculum, schedule) = runCatching { fetchFullSchedule(onProgress) }
            .getOrElse {
                warnings += "课表拉取失败：${it.message ?: "未知错误"}"
                Curriculum() to emptyList()
            }

        val homework = runCatching { fetchHomework(courses, onProgress) }
            .getOrElse {
                warnings += "作业拉取失败：${it.message ?: "未知错误"}"
                emptyList()
            }

        val exams = runCatching { fetchExams(onProgress) }
            .getOrElse {
                warnings += "考试拉取失败：${it.message ?: "未知错误"}"
                emptyList()
            }

        onProgress("写入本地数据…", 0.97f)
        CampusStore.replaceSyncedCourses(schedule)
        CampusStore.replaceSyncedTasks(homework + exams)

        val semesterLabel = listOf(curriculum.schoolYear, curriculum.semester)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        curriculum.firstWeekEpochDay?.let { day ->
            CampusStore.updateSettings { it.copy(semesterStart = day, totalWeeks = curriculum.maxWeek) }
        }
        CampusStore.updateXxt {
            it.copy(
                loggedIn = true,
                userName = profile.name,
                school = profile.school,
                lastSync = System.currentTimeMillis(),
                semesterLabel = semesterLabel,
            )
        }

        SyncReport(
            courses = schedule.size,
            tasks = homework.size,
            exams = exams.size,
            userName = profile.name,
            school = profile.school,
            semesterLabel = semesterLabel,
            weekStartEpochDay = curriculum.firstWeekEpochDay,
            warnings = warnings,
        )
    }

    fun todaySectionIndex(sectionTimes: List<Pair<String, String>>): Int {
        if (sectionTimes.isEmpty()) return -1
        val now = LocalDateTime.now().toLocalTime()
        sectionTimes.forEachIndexed { index, (start, end) ->
            val s = runCatching { java.time.LocalTime.parse(start.padStart(5, '0')) }.getOrNull() ?: return@forEachIndexed
            val e = runCatching { java.time.LocalTime.parse(end.padStart(5, '0')) }.getOrNull() ?: return@forEachIndexed
            if (!now.isBefore(s) && !now.isAfter(e)) return index + 1
        }
        return -1
    }

    fun semesterLabelFor(epochDay: Long?): String {
        val day = epochDay ?: return ""
        val date = LocalDate.ofEpochDay(day)
        return String.format(Locale.CHINA, "第 1 周 %d/%d 起", date.monthValue, date.dayOfMonth)
    }
}
