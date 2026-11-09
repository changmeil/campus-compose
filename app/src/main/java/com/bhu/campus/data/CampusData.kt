package com.bhu.campus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

const val SOURCE_MANUAL = "manual"
const val SOURCE_XXT = "chaoxing"
const val SOURCE_DEMO = "demo"
const val SOURCE_WAKEUP = "wakeup"

/** 课表来源：学习通同步 / WakeUp 导入。 */
const val SCHEDULE_XXT = "chaoxing"
const val SCHEDULE_WAKEUP = "wakeup"

/**
 * 一台打水设备的二维码（简称「预设」）。
 *
 * 饮水机屏幕上的码是 `https://88wise.com/p?i=<token>&b=<设备号>`，
 * 实测同一台机器的码在当天可以反复用，所以记下来，下次点一下直接回到那台设备。
 * 每台机器一条，存多台就能在首页/电费页一键切换。
 */
data class WaterQr(
    val content: String,
    /** 展示用：从 b 参数尾巴取出来的设备号，例如 1882413285 → 13285。 */
    val deviceNo: String = "",
    /** 用户自己起的名，比如「宿舍楼下」；空着就用设备号。 */
    val label: String = "",
    val savedAt: Long = System.currentTimeMillis(),
) {
    val isValid: Boolean
        get() = content.isNotBlank() && savedAt > 0

    /** 列表和按钮上显示的名字。 */
    val title: String
        get() = label.ifBlank { if (deviceNo.isNotBlank()) "设备 $deviceNo" else "打水" }
}

const val TYPE_HOMEWORK = "homework"
const val TYPE_EXAM = "exam"

/** 最多记几台打水设备，太多了首页放不下。 */
const val MAX_WATER_QR = 6

/** 一节课。weekday/start/end 均为 1 基；weeks 为周次列表。 */
data class Course(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val weekday: Int = 1,
    val start: Int = 1,
    val end: Int = 2,
    val weeks: List<Int> = (1..16).toList(),
    val color: Int = 1,
    val source: String = SOURCE_MANUAL,
) {
    val sectionCount: Int get() = (end - start + 1).coerceAtLeast(1)
}

data class TaskItem(
    val id: String = UUID.randomUUID().toString(),
    val type: String = TYPE_HOMEWORK,
    val title: String,
    val course: String = "",
    val dueAt: Long? = null,
    val note: String = "",
    val done: Boolean = false,
    val source: String = SOURCE_MANUAL,
) {
    val isExam: Boolean get() = type == TYPE_EXAM
}

data class AppSettings(
    /** 第 1 周周一的 epochDay */
    val semesterStart: Long = defaultSemesterStart(),
    val totalWeeks: Int = 18,
    val building: String = "5",
    val unit: String = "1",
    val room: String = "",
    val notifyEnabled: Boolean = true,
    /** 最近一次成功查询电费的日期（yyyy-MM-dd），用于每天只自动查一次 */
    val lastQueryDay: String = "",
    /** 是否显示宿舍电费功能。其他学校的同学用不到可以关掉，底部 tab、首页卡片、每日自动查询都会跟着停。 */
    val showEnergy: Boolean = true,
    /** 今日页卡片的顺序（key 列表），长按拖动后存这儿；空表示默认顺序。 */
    val todayOrder: List<String> = emptyList(),
    /** 余额页卡片的顺序，同上。 */
    val energyOrder: List<String> = emptyList(),
    /**
     * 课表从哪儿来：学习通同步，还是 WakeUp 导入（见 [SCHEDULE_XXT] / [SCHEDULE_WAKEUP]）。
     * 决定今日页和课程表页上那个主入口按钮是「同步」还是「导入」。
     */
    val scheduleSource: String = SCHEDULE_XXT,
    /**
     * 自定义节次作息，每项 "HH:mm-HH:mm"，下标 0 就是第 1 节。
     * 空表示用 [DEFAULT_SECTION_TIMES]；导入 WakeUp 时按 ICS 里的真实时间自动校准。
     */
    val sectionTimes: List<String> = emptyList(),
    /** 打开 App 时自动同步学习通（作业、考试，必要时连课表）。 */
    val autoSync: Boolean = true,
    /**
     * 自动同步时是否连课表一起刷新。默认关：课表一学期基本不变，
     * 每次打开都重拉一遍既慢又容易把导入好的课表覆盖掉。
     * 手动点「同步」时不受这个开关限制，永远是全量。
     */
    val autoSyncSchedule: Boolean = false,
) {
    /** 实际生效的作息表（总是 12 项）。 */
    val effectiveSectionTimes: List<Pair<LocalTime, LocalTime>>
        get() = sectionTimesFromStored(sectionTimes)
}


/** 按用户存的顺序排：丢掉已经不存在的 key，新加的接在后面。 */
fun arrangeOrder(saved: List<String>, defaults: List<String>): List<String> {
    val kept = saved.filter { it in defaults }.distinct()
    return kept + defaults.filterNot { it in kept }
}

fun defaultSemesterStart(): Long {
    val today = LocalDate.now()
    // 找本周周一，再往前推到"看起来像开学"的那一周（8 月最后一个周一）
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    var start = monday
    while (start.minusWeeks(1).monthValue >= 8 && start.minusWeeks(1).dayOfMonth > 20) {
        start = start.minusWeeks(1)
    }
    return start.toEpochDay()
}

data class XxtState(
    val loggedIn: Boolean = false,
    val userName: String = "",
    val school: String = "",
    val lastSync: Long = 0L,
    val semesterLabel: String = "",
) {
    val hasSynced: Boolean get() = lastSync > 0
}

/* ------------------------------------------------------------------ */
/*  Store：单例 + JSON 持久化（SharedPreferences），并在 Compose 中可观察  */
/* ------------------------------------------------------------------ */

object CampusStore {

    private const val PREFS = "campus_v2"

    /** 距上次同步至少这么久才自动再拉一次。 */
    private const val AUTO_SYNC_STALE_MS = 2 * 3600_000L

    /** 自动同步看多远：90 天内没有作业/考试就不用拉了。 */
    private const val TODO_HORIZON_DAYS = 90L

    private const val DAY_MS = 86_400_000L

    private lateinit var prefs: SharedPreferences

    /** 只用于刷新桌面长按菜单，不参与持久化。 */
    private var appContext: Context? = null

    var courses by mutableStateOf<List<Course>>(emptyList())
        private set
    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set
    var settings by mutableStateOf(AppSettings())
        private set
    var xxt by mutableStateOf(XxtState())
        private set
    /** 今日天气，只用于 Compose 观察；持久化在 WeatherClient 自己的 prefs 里。 */
    var weather by mutableStateOf<WeatherInfo?>(null)
        private set
    /** 保存过的打水设备，最新的在最前面；点一下就能直接回到那台机器。 */
    var waterQrs by mutableStateOf<List<WaterQr>>(emptyList())
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        load()
    }

    /** 打水设备一有增删改，桌面长按菜单跟着变。 */
    private fun syncShortcuts() {
        appContext?.let { Shortcuts.sync(it) }
    }

    private fun load() {
        val rawCourses = decodeCourses(prefs.getString("courses", null))
        val mergedCourses = mergeDuplicateCourses(rawCourses)
        courses = mergedCourses
        tasks = decodeTasks(prefs.getString("tasks", null))
        settings = decodeSettings(prefs.getString("settings", null))
        xxt = decodeXxt(prefs.getString("xxt", null))
        val stored = decodeWaterQrs(prefs.getString("waterQrs", null))
        // 老版本只存了一条 waterQr，这里把它升级成第一台预设（先赋值再落盘，别把老数据写没了）
        val legacy = if (stored.isEmpty()) decodeLegacyWaterQr() else null
        waterQrs = stored.ifEmpty { listOfNotNull(legacy) }
        // 老版本导入把教室带进了合并键，同一门课会裂成两条；这里在读盘时兜一次底，
        // 用户不用重新导入也能看到干净的今日页。
        if (mergedCourses != rawCourses) persist()
        if (legacy != null) persistWaterQrs()
    }

    private fun decodeLegacyWaterQr(): WaterQr? = runCatching {
        prefs.getString("waterQr", null)?.let { raw ->
            val obj = JSONObject(raw)
            WaterQr(
                content = obj.optString("content"),
                deviceNo = obj.optString("deviceNo"),
                savedAt = obj.optLong("savedAt"),
            )
        }
    }.getOrNull()?.takeIf { it.isValid }

    /* ---------------- 打水设备预设 ---------------- */

    /** 记住一次成功识别的二维码；同一台机器只留一条，并挪到最前面。 */
    fun saveWaterQr(content: String): WaterQr? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val deviceNo = deviceNoOf(text)
        val old = waterQrs.firstOrNull {
            it.content == text || (deviceNo.isNotBlank() && it.deviceNo == deviceNo)
        }
        val qr = WaterQr(
            content = text,
            deviceNo = deviceNo,
            label = old?.label.orEmpty(),
            savedAt = System.currentTimeMillis(),
        )
        waterQrs = (listOf(qr) + waterQrs.filterNot {
            it.content == text || (deviceNo.isNotBlank() && it.deviceNo == deviceNo)
        }).take(MAX_WATER_QR)
        persistWaterQrs()
        return qr
    }

    fun removeWaterQr(qr: WaterQr) {
        waterQrs = waterQrs.filterNot { it.content == qr.content }
        persistWaterQrs()
    }

    fun renameWaterQr(qr: WaterQr, label: String) {
        waterQrs = waterQrs.map { if (it.content == qr.content) it.copy(label = label.trim()) else it }
        persistWaterQrs()
    }

    fun clearWaterQrs() {
        waterQrs = emptyList()
        prefs.edit().remove("waterQrs").remove("waterQr").apply()
        syncShortcuts()
    }

    private fun decodeWaterQrs(raw: String?): List<WaterQr> {
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val qr = WaterQr(
                    content = obj.optString("content"),
                    deviceNo = obj.optString("deviceNo"),
                    label = obj.optString("label"),
                    savedAt = obj.optLong("savedAt"),
                )
                if (qr.isValid) add(qr)
            }
        }
    }

    private fun persistWaterQrs() {
        val arr = JSONArray()
        waterQrs.forEach { qr ->
            arr.put(
                JSONObject()
                    .put("content", qr.content)
                    .put("deviceNo", qr.deviceNo)
                    .put("label", qr.label)
                    .put("savedAt", qr.savedAt),
            )
        }
        prefs.edit().putString("waterQrs", arr.toString()).remove("waterQr").apply()
        syncShortcuts()
    }

    /** `...&b=1882413285` → 13285（设备页上显示的就是这个号）。 */
    private fun deviceNoOf(content: String): String {
        val raw = Regex("[?&]b=(\\d+)").find(content)?.groupValues?.getOrNull(1) ?: return ""
        return if (raw.length > 5) raw.takeLast(5) else raw
    }

    fun reload() = load()

    private fun persist() {
        prefs.edit()
            .putString("courses", encodeCourses(courses))
            .putString("tasks", encodeTasks(tasks))
            .putString("settings", encodeSettings(settings))
            .putString("xxt", encodeXxt(xxt))
            .apply()
    }

    /* ---------------- 课程 ---------------- */

    /**
     * 同名、同节次的课并成一条：周次取并集，教室和老师各自拼起来。
     *
     * 合并键**只用课名 + 星期 + 节次**，不带教室也不带老师——这两个字段不同来源给的
     * 口径不一样：
     * - 教务里同一门课挂了两间教室（C505 / A109），带教室就裂成两条；
     * - WakeUp 的 ICS 通常没有教师字段（老师在 LOCATION 里），学习通却有，
     *   带老师就变成「有老师的一条 + 没老师的一条」，两边同时存在时今日页列两遍。
     */
    private fun mergeDuplicateCourses(list: List<Course>): List<Course> {
        val out = LinkedHashMap<String, Course>()
        list.forEach { raw ->
            // 顺手把老数据里「教室 老师」这种脏地点洗干净
            val course = raw.copy(location = WakeupImport.stripTeacher(raw.location, raw.teacher))
            val key = "${course.name}|${course.weekday}|${course.start}|${course.end}"
            val old = out[key]
            out[key] = if (old == null) {
                course
            } else {
                val places = (old.location.split(" / ") + course.location.split(" / "))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(2)
                val teachers = listOf(old.teacher, course.teacher)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(2)
                old.copy(
                    weeks = (old.weeks + course.weeks).distinct().sorted(),
                    location = places.joinToString(" / "),
                    teacher = teachers.joinToString(" / "),
                )
            }
        }
        return out.values.toList()
    }

    fun upsertCourse(course: Course) {
        val index = courses.indexOfFirst { it.id == course.id }
        courses = if (index >= 0) {
            courses.toMutableList().also { it[index] = course }
        } else {
            courses + course
        }
        persist()
    }

    fun deleteCourse(id: String) {
        courses = courses.filterNot { it.id == id }
        persist()
    }

    /**
     * 从 WakeUp 导入课表。
     *
     * @param replace true 覆盖现有课表，false 追加在后面
     * @param firstMonday 课表第一周的周一（epochDay），用来校准「学期起始日」；null 表示不动
     * @param sectionTimes 从 ICS 里推断出来的节次作息；空表示沿用当前作息
     */
    fun importCourses(
        imported: List<Course>,
        firstMonday: Long?,
        replace: Boolean,
        sectionTimes: List<Pair<LocalTime, LocalTime>> = emptyList(),
    ) {
        // 「追加」只保留手工/学习通那部分，上一次导入的 WakeUp 课整批换掉——
        // 否则重新导入一次，同一门课会在格子里叠两层、今日页列两遍。
        val kept = if (replace) emptyList() else courses.filterNot { it.source == SOURCE_WAKEUP }
        courses = mergeDuplicateCourses(kept + imported)
        settings = settings.copy(
            semesterStart = firstMonday ?: settings.semesterStart,
            sectionTimes = if (sectionTimes.isNotEmpty()) encodeSectionTimes(sectionTimes)
            else settings.sectionTimes,
            scheduleSource = SCHEDULE_WAKEUP,
        )
        persist()
    }

    /** 把节次作息恢复成默认的 12 节。 */
    fun resetSectionTimes() {
        settings = settings.copy(sectionTimes = emptyList())
        persist()
    }

    /* ---------------- 任务 ---------------- */

    fun upsertTask(task: TaskItem) {
        val index = tasks.indexOfFirst { it.id == task.id }
        tasks = if (index >= 0) {
            tasks.toMutableList().also { it[index] = task }
        } else {
            tasks + task
        }
        persist()
    }

    fun toggleTask(id: String) {
        tasks = tasks.map { if (it.id == id) it.copy(done = !it.done) else it }
        persist()
    }

    fun deleteTask(id: String) {
        tasks = tasks.filterNot { it.id == id }
        persist()
    }

    /* ---------------- 设置 ---------------- */

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        settings = block(settings)
        persist()
    }

    /* ---------------- 自动同步判定 ---------------- */

    /**
     * 打开 App 时该不该自动同步学习通。
     *
     * 四个条件都满足才拉：开关打开、已登录同步过、距上次超过 2 小时、
     * **90 天内有未完成的作业或考试**。最后一条是用户提的：假期里（或学期末作业都交完）
     * 再自动同步也拉不到新东西，白白耗流量和几秒钟。
     *
     * 手动点「同步」不受这里限制，随时能全量刷。
     */
    fun shouldAutoSync(now: Long = System.currentTimeMillis()): Boolean {
        if (!settings.autoSync) return false
        if (!xxt.loggedIn || xxt.lastSync <= 0L) return false
        if (now - xxt.lastSync < AUTO_SYNC_STALE_MS) return false
        val horizon = now + TODO_HORIZON_DAYS * DAY_MS
        // dueAt 为空的多半是没解析到截止时间的作业，算「还在进行」，保守当作有待办
        return tasks.any { !it.done && (it.dueAt == null || it.dueAt <= horizon) }
    }

    /* ---------------- 学习通同步写入 ---------------- */

    fun replaceSyncedCourses(list: List<Course>) {
        // 学习通同步只换掉上次同步的那批，手工课和 WakeUp 导入的留着；
        // 但同一门课两边都有时必须并成一条，否则今日页会把课列两遍。
        val manual = courses.filter { it.source != SOURCE_XXT }
        courses = mergeDuplicateCourses(manual + list)
        persist()
    }

    fun replaceSyncedTasks(list: List<TaskItem>) {
        val previous = tasks.filter { it.source == SOURCE_XXT }.associateBy {
            it.title + "|" + it.course + "|" + (it.dueAt ?: 0L)
        }
        val merged = list.map { item ->
            val kept = previous[item.title + "|" + item.course + "|" + (item.dueAt ?: 0L)]
            if (kept != null) item.copy(id = kept.id, done = kept.done) else item
        }
        tasks = tasks.filter { it.source != SOURCE_XXT } + merged
        persist()
    }

    fun updateXxt(block: (XxtState) -> XxtState) {
        xxt = block(xxt)
        persist()
    }

    fun updateWeather(info: WeatherInfo?) {
        weather = info
    }

    fun clearAll() {
        courses = emptyList()
        tasks = emptyList()
        xxt = XxtState()
        settings = AppSettings()
        persist()
    }

    fun clearDemoData() {
        courses = courses.filterNot { it.source == SOURCE_DEMO }
        tasks = tasks.filterNot { it.source == SOURCE_DEMO }
        persist()
    }

    /* ---------------- 编码 ---------------- */

    private fun encodeCourses(list: List<Course>): String {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("teacher", c.teacher)
                    .put("location", c.location)
                    .put("weekday", c.weekday)
                    .put("start", c.start)
                    .put("end", c.end)
                    .put("weeks", JSONArray(c.weeks))
                    .put("color", c.color)
                    .put("source", c.source)
            )
        }
        return arr.toString()
    }

    private fun decodeCourses(raw: String?): List<Course> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@mapNotNull null
                val start = o.optInt("start", 1).coerceIn(1, 12)
                val end = o.optInt("end", start).coerceIn(start, 12)
                Course(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    teacher = o.optString("teacher"),
                    location = o.optString("location"),
                    weekday = o.optInt("weekday", 1).coerceIn(1, 7),
                    start = start,
                    end = end,
                    weeks = parseWeeks(o.optJSONArray("weeks")),
                    color = o.optInt("color", 1).coerceIn(1, 7),
                    source = o.optString("source", SOURCE_MANUAL),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseWeeks(arr: JSONArray?): List<Int> {
        if (arr == null || arr.length() == 0) return (1..16).toList()
        return (0 until arr.length()).map { arr.optInt(it) }.filter { it in 1..30 }.sorted()
    }

    private fun encodeTasks(list: List<TaskItem>): String {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("type", t.type)
                    .put("title", t.title)
                    .put("course", t.course)
                    .put("dueAt", t.dueAt ?: JSONObject.NULL)
                    .put("note", t.note)
                    .put("done", t.done)
                    .put("source", t.source)
            )
        }
        return arr.toString()
    }

    private fun decodeTasks(raw: String?): List<TaskItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim()
                if (title.isEmpty()) return@mapNotNull null
                TaskItem(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    type = if (o.optString("type") == TYPE_EXAM) TYPE_EXAM else TYPE_HOMEWORK,
                    title = title,
                    course = o.optString("course"),
                    dueAt = if (o.isNull("dueAt")) null else o.optLong("dueAt"),
                    note = o.optString("note"),
                    done = o.optBoolean("done", false),
                    source = o.optString("source", SOURCE_MANUAL),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeSettings(s: AppSettings): String = JSONObject()
        .put("semesterStart", s.semesterStart)
        .put("totalWeeks", s.totalWeeks)
        .put("building", s.building)
        .put("unit", s.unit)
        .put("room", s.room)
        .put("notifyEnabled", s.notifyEnabled)
        .put("lastQueryDay", s.lastQueryDay)
        .put("showEnergy", s.showEnergy)
        .put("todayOrder", JSONArray(s.todayOrder))
        .put("energyOrder", JSONArray(s.energyOrder))
        .put("scheduleSource", s.scheduleSource)
        .put("sectionTimes", JSONArray(s.sectionTimes))
        .put("autoSync", s.autoSync)
        .put("autoSyncSchedule", s.autoSyncSchedule)
        .toString()

    private fun decodeSettings(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            AppSettings(
                semesterStart = o.optLong("semesterStart", defaultSemesterStart()),
                totalWeeks = o.optInt("totalWeeks", 18).coerceIn(1, 30),
                building = o.optString("building", "5"),
                unit = o.optString("unit", "1"),
                room = o.optString("room", ""),
                notifyEnabled = o.optBoolean("notifyEnabled", true),
                lastQueryDay = o.optString("lastQueryDay", ""),
                showEnergy = o.optBoolean("showEnergy", true),
                todayOrder = stringList(o.optJSONArray("todayOrder")),
                energyOrder = stringList(o.optJSONArray("energyOrder")),
                scheduleSource = when (o.optString("scheduleSource")) {
                    SCHEDULE_WAKEUP -> SCHEDULE_WAKEUP
                    else -> SCHEDULE_XXT
                },
                sectionTimes = stringList(o.optJSONArray("sectionTimes")),
                autoSync = o.optBoolean("autoSync", true),
                autoSyncSchedule = o.optBoolean("autoSyncSchedule", false),
            )
        }.getOrDefault(AppSettings())
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull {
            array.optString(it).takeIf { s -> s.isNotBlank() }
        }
    }

    private fun encodeXxt(s: XxtState): String = JSONObject()
        .put("loggedIn", s.loggedIn)
        .put("userName", s.userName)
        .put("school", s.school)
        .put("lastSync", s.lastSync)
        .put("semesterLabel", s.semesterLabel)
        .toString()

    private fun decodeXxt(raw: String?): XxtState {
        if (raw.isNullOrBlank()) return XxtState()
        return runCatching {
            val o = JSONObject(raw)
            XxtState(
                loggedIn = o.optBoolean("loggedIn", false),
                userName = o.optString("userName"),
                school = o.optString("school"),
                lastSync = o.optLong("lastSync"),
                semesterLabel = o.optString("semesterLabel"),
            )
        }.getOrDefault(XxtState())
    }
}
