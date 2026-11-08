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
import java.time.ZoneId
import java.util.UUID

const val SOURCE_MANUAL = "manual"
const val SOURCE_XXT = "chaoxing"
const val SOURCE_DEMO = "demo"

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
)

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

data class EnergySnapshot(
    val balance: Double = 0.0,
    val areaName: String = "",
    val buildingNo: String = "",
    val unitNo: String = "",
    val roomNo: String = "",
    val userNo: String = "",
    val meterNo: String = "",
    val fetchedAt: Long = 0L,
)

data class EnergyPoint(val balance: Double, val fetchedAt: Long)

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

    private lateinit var prefs: SharedPreferences

    /** 只用于刷新桌面长按菜单，不参与持久化。 */
    private var appContext: Context? = null

    var courses by mutableStateOf<List<Course>>(emptyList())
        private set
    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set
    var settings by mutableStateOf(AppSettings())
        private set
    var energy by mutableStateOf<EnergySnapshot?>(null)
        private set
    var history by mutableStateOf<List<EnergyPoint>>(emptyList())
        private set
    var xxt by mutableStateOf(XxtState())
        private set
    /** 今日天气，只用于 Compose 观察；持久化在 WeatherClient 自己的 prefs 里。 */
    var weather by mutableStateOf<WeatherInfo?>(null)
        private set
    /** 保存过的打水设备，最新的在最前面；点一下就能直接回到那台机器。 */
    var waterQrs by mutableStateOf<List<WaterQr>>(emptyList())
        private set
    /** 洗浴（智能控水）用的一卡通卡号，空着就是还没设置。 */
    var waterCard by mutableStateOf("")
        private set
    /** 最近一次洗浴快照（余额 / 空位 / 扣费记录）。 */
    var bath by mutableStateOf<WaterSnapshot?>(null)
        private set
    /** 社慧通账户里的打水余额，靠无障碍读小程序首页那个数字拿到；null 表示还没读到过。 */
    var drinkBalance by mutableStateOf<Double?>(null)
        private set
    /** 上面那个余额是什么时候读到的。 */
    var drinkBalanceAt by mutableStateOf(0L)
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
        courses = decodeCourses(prefs.getString("courses", null))
        tasks = decodeTasks(prefs.getString("tasks", null))
        settings = decodeSettings(prefs.getString("settings", null))
        energy = decodeEnergy(prefs.getString("energy", null))
        history = decodeHistory(prefs.getString("history", null))
        xxt = decodeXxt(prefs.getString("xxt", null))
        val stored = decodeWaterQrs(prefs.getString("waterQrs", null))
        // 老版本只存了一条 waterQr，这里把它升级成第一台预设（先赋值再落盘，别把老数据写没了）
        val legacy = if (stored.isEmpty()) decodeLegacyWaterQr() else null
        waterQrs = stored.ifEmpty { listOfNotNull(legacy) }
        if (legacy != null) persistWaterQrs()
        waterCard = prefs.getString("waterCard", "").orEmpty()
        bath = decodeBath(prefs.getString("bath", null))
        drinkBalance = prefs.getString("drinkBalance", null)?.toDoubleOrNull()
        drinkBalanceAt = prefs.getLong("drinkBalanceAt", 0L)
    }

    /* ---------------- 打水余额（无障碍读屏） ---------------- */

    /**
     * 无障碍服务在社慧通小程序首页读到余额后调这里。
     *
     * 读数相同就不重复落盘，避免小程序页面每次重绘都写一遍。
     */
    fun recordDrinkBalance(value: Double): Boolean {
        if (value < 0) return false
        val current = drinkBalance
        // 一分钟内读到同一个数就不重复落盘；小程序页面每次重绘都会来一遍
        if (current != null && kotlin.math.abs(current - value) < 0.005 &&
            System.currentTimeMillis() - drinkBalanceAt < 60_000
        ) {
            return false
        }
        drinkBalance = value
        drinkBalanceAt = System.currentTimeMillis()
        prefs.edit()
            .putString("drinkBalance", value.toString())
            .putLong("drinkBalanceAt", drinkBalanceAt)
            .apply()
        return true
    }

    /** 清掉读到的余额（换账号或数据可疑时用）。 */
    fun clearDrinkBalance() {
        drinkBalance = null
        drinkBalanceAt = 0L
        prefs.edit().remove("drinkBalance").remove("drinkBalanceAt").apply()
    }

    /* ---------------- 洗浴 ---------------- */

    fun saveWaterCard(card: String) {
        val value = card.trim()
        waterCard = value
        if (value.isEmpty()) {
            bath = null
            prefs.edit().remove("waterCard").remove("bath").apply()
        } else {
            prefs.edit().putString("waterCard", value).apply()
        }
    }

    fun recordBath(snapshot: WaterSnapshot) {
        bath = snapshot
        prefs.edit().putString("bath", encodeBath(snapshot)).apply()
    }

    /** 预约/取消之后本地立刻改掉对应的行，不用再打一次网络。同一时间只可能有一张预约。 */
    fun patchBathClass(classNo: Int, booked: Boolean, code: String) {
        val current = bath ?: return
        val patched = current.copy(
            classes = current.classes.map {
                when {
                    it.no == classNo -> it.copy(booked = booked, bookCode = code)
                    booked -> it.copy(booked = false, bookCode = "")
                    else -> it
                }
            },
        )
        recordBath(patched)
    }

    private fun encodeBath(s: WaterSnapshot): String {
        val records = JSONArray()
        s.records.take(30).forEach {
            records.put(
                JSONObject()
                    .put("posNo", it.posNo)
                    .put("zone", it.zone)
                    .put("time", it.time)
                    .put("money", it.money)
                    .put("balance", it.balance),
            )
        }
        val classes = JSONArray()
        s.classes.forEach {
            classes.put(
                JSONObject()
                    .put("no", it.no)
                    .put("name", it.name)
                    .put("posNum", it.posNum)
                    .put("free", it.free)
                    .put("freeRate", it.freeRate)
                    .put("bookRate", it.bookRate)
                    .put("booked", it.booked)
                    .put("bookCode", it.bookCode),
            )
        }
        return JSONObject()
            .put("card", s.card)
            .put("balance", s.balance)
            .put("quota", s.quota)
            .put("records", records)
            .put("classes", classes)
            .put("fetchedAt", s.fetchedAt)
            .toString()
    }

    private fun decodeBath(raw: String?): WaterSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val records = ArrayList<WaterCost>()
            o.optJSONArray("records")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    records += WaterCost(
                        posNo = item.optInt("posNo"),
                        zone = item.optString("zone"),
                        time = item.optString("time"),
                        money = item.optDouble("money"),
                        balance = item.optDouble("balance"),
                    )
                }
            }
            val classes = ArrayList<WaterClass>()
            o.optJSONArray("classes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    classes += WaterClass(
                        no = item.optInt("no"),
                        name = item.optString("name"),
                        posNum = item.optInt("posNum"),
                        free = item.optInt("free"),
                        freeRate = item.optInt("freeRate"),
                        bookRate = item.optInt("bookRate"),
                        booked = item.optBoolean("booked"),
                        bookCode = item.optString("bookCode"),
                    )
                }
            }
            WaterSnapshot(
                card = o.optString("card"),
                balance = o.optDouble("balance"),
                quota = o.optDouble("quota"),
                classes = classes,
                records = records,
                fetchedAt = o.optLong("fetchedAt"),
            )
        }.getOrNull()
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
            .putString("energy", energy?.let(::encodeEnergy))
            .putString("history", encodeHistory(history))
            .putString("xxt", encodeXxt(xxt))
            .apply()
    }

    /* ---------------- 课程 ---------------- */

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

    /* ---------------- 电费 ---------------- */

    fun recordEnergy(snapshot: EnergySnapshot) {
        energy = snapshot
        val point = EnergyPoint(snapshot.balance, snapshot.fetchedAt)
        history = (history.filterNot {
            sameDay(it.fetchedAt, snapshot.fetchedAt)
        } + point).sortedBy { it.fetchedAt }.takeLast(400)
        persist()
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val zone = ZoneId.systemDefault()
        val da = Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
        val db = Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
        return da == db
    }

    /* ---------------- 学习通同步写入 ---------------- */

    fun replaceSyncedCourses(list: List<Course>) {
        val manual = courses.filter { it.source != SOURCE_XXT }
        courses = manual + list
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
        energy = null
        history = emptyList()
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
            )
        }.getOrDefault(AppSettings())
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull {
            array.optString(it).takeIf { s -> s.isNotBlank() }
        }
    }

    private fun encodeEnergy(s: EnergySnapshot): String = JSONObject()
        .put("balance", s.balance)
        .put("areaName", s.areaName)
        .put("buildingNo", s.buildingNo)
        .put("unitNo", s.unitNo)
        .put("roomNo", s.roomNo)
        .put("userNo", s.userNo)
        .put("meterNo", s.meterNo)
        .put("fetchedAt", s.fetchedAt)
        .toString()

    private fun decodeEnergy(raw: String?): EnergySnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            EnergySnapshot(
                balance = o.optDouble("balance", 0.0),
                areaName = o.optString("areaName"),
                buildingNo = o.optString("buildingNo"),
                unitNo = o.optString("unitNo"),
                roomNo = o.optString("roomNo"),
                userNo = o.optString("userNo"),
                meterNo = o.optString("meterNo"),
                fetchedAt = o.optLong("fetchedAt"),
            )
        }.getOrNull()
    }

    private fun encodeHistory(list: List<EnergyPoint>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("b", it.balance).put("t", it.fetchedAt))
        }
        return arr.toString()
    }

    private fun decodeHistory(raw: String?): List<EnergyPoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                EnergyPoint(o.optDouble("b"), o.optLong("t"))
            }
        }.getOrDefault(emptyList())
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
