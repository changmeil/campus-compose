package com.bhu.campus.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * 学生洗浴（智能控水 skyy.bhu.edu.cn）。
 *
 * 为什么不用 WebView 打开网页：
 * 那个页面只认 URL 上的 `?ticket=xxx` 一次性票据（企业微信工作台点进去时带的），
 * 浏览器/别的 App 里打开只会弹「缺少 ticket 验证」，拿不到票据就没法用。
 * 但它的前端把服务账号写死在 JS 里（`getTokenToDo` → GetToken(hbuser, 9ddec5…)），
 * 所以这里直接用它自己的接口，和网页版走的是同一套后端。
 *
 * 接口都是 GET + query，返回 JSON，金额单位是「分」，比率单位是「万分之一」：
 *  - api/GetToken?userid=&userpassword=            → Token
 *  - api/CheckKsPos?posno=&ano=&token=             → Balance(分) / FixedMoney(分) / CostDsp / CostState
 *  - api/GetAccCostInfo?ano=&token=&startdate=&enddate= → list[] 每次洗澡的扣费（DealMoney 是 "5.85 元" 这种字符串）
 *  - api/AccUseHzWatch?ano=&token=                 → List[] 四个浴区的用量/预约状态，一次拿全
 *  - api/BookCodeReq?classno=&ano=&token=          → 预约，返回 4 位 BookCode，30 分钟有效
 *  - api/BookCodeReqCancel?classno=&ano=&token=    → 取消预约
 *
 * 预约会写学校那边的状态，只在用户点了「预约」时调用，不做任何自动预订。
 */
object WaterClient {

    private const val BASE = "https://skyy.bhu.edu.cn/waterapi/"

    /** 写死在前端 JS 里的服务账号，见 chunk-eb7b 的 c3c8 模块。 */
    private const val SERVICE_USER = "hbuser"
    private const val SERVICE_PASS = "9ddec5049019a765dd940e0b06eb3483"

    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 12000

    private const val TOKEN_TTL = 30 * 60_000L

    /**
     * 查余额用的探针机位：随便一台合法的就行，余额是挂在卡上的。
     * 机位总量 146 台（一楼散 2-18、一楼单 21-59、二楼散 101-119、二楼单 121-191），
     * 但这个数字和空位不用自己扫了——AccUseHzWatch 直接给。
     */
    private val PROBES = listOf(30, 48, 53, 21, 3)

    private var tokenCache: String? = null
    private var tokenAt = 0L

    /**
     * 拉一次完整快照。
     *
     * @param card 一卡通卡号（`ano`），不是学号
     */
    suspend fun snapshot(card: String): WaterSnapshot =
        withContext(Dispatchers.IO) {
            val ano = card.trim()
            require(ano.isNotEmpty()) { "请先填校园卡号" }

            val tok = token()
            val status = balanceOf(ano, tok)
            val classes = classesOf(ano, tok)
            val records = recordsOf(ano, tok)

            WaterSnapshot(
                card = ano,
                balance = status.balance,
                quota = status.quota,
                classes = classes,
                records = records,
                fetchedAt = System.currentTimeMillis(),
            )
        }

    /* ------------------------------ 余额 ------------------------------ */

    private data class Status(val balance: Double, val quota: Double)

    private fun balanceOf(ano: String, token: String): Status {
        var lastMessage = "水控服务器没有返回余额"
        for (posno in PROBES) {
            val obj = runCatching { get("api/CheckKsPos?posno=$posno&ano=$ano&token=$token") }
                .getOrNull() ?: continue
            val ret = obj.optInt("RetNo", 0)
            val balance = obj.optInt("Balance", 0)
            // -4 = 机号无效，换下一个探针；-13/-31 仍带着余额，可以直接用
            if (ret == -4) continue
            if (balance <= 0 && ret != 0) {
                lastMessage = obj.optString("RetDsp", lastMessage)
                continue
            }
            return Status(balance / 100.0, obj.optInt("FixedMoney", 0) / 100.0)
        }
        throw IllegalStateException(lastMessage)
    }

    /* ------------------------------ 浴区 / 预约 ------------------------------ */

    private fun classesOf(ano: String, token: String): List<WaterClass> {
        val obj = get("api/AccUseHzWatch?ano=$ano&token=$token")
        if (obj.optInt("RetNo", 0) != 0) {
            throw IllegalStateException(obj.optString("RetDsp", "拿不到浴区信息"))
        }
        val list = obj.optJSONArray("List") ?: return emptyList()
        val out = ArrayList<WaterClass>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val posNum = item.optInt("PosNum", 0)
            val freeRate = item.optInt("UseFreeRate", 0)
            out += WaterClass(
                no = item.optInt("ClassNo", 0),
                name = item.optString("ClassName", ""),
                posNum = posNum,
                // 服务端给的是「空闲率」，换算回台数（和网页版 getUseFree 的算法一致）
                free = (freeRate * posNum / 10000.0).roundToInt(),
                freeRate = freeRate / 100,
                bookRate = item.optInt("BookRate", 0) / 100,
                booked = item.optInt("Actkind", 0) == 1,
                bookCode = item.optString("BookCode", ""),
            )
        }
        return out
    }

    /**
     * 预约某个浴区。成功时 [BookResult.code] 是 4 位预约码，30 分钟内到控水器上输入就能用。
     *
     * 会真实占用一个预约名额，只在用户明确点「预约」时调用。
     */
    suspend fun book(card: String, classNo: Int): BookResult = withContext(Dispatchers.IO) {
        val ano = card.trim()
        require(ano.isNotEmpty()) { "请先填校园卡号" }
        val obj = get("api/BookCodeReq?classno=$classNo&ano=$ano&token=${token()}")
        val ok = obj.optInt("RetNo", -1) == 0
        BookResult(
            ok = ok,
            code = obj.optString("BookCode", ""),
            message = obj.optString("RetDsp", if (ok) "预约成功" else "预约失败"),
        )
    }

    /** 取消某个浴区的预约。 */
    suspend fun cancelBook(card: String, classNo: Int): BookResult = withContext(Dispatchers.IO) {
        val ano = card.trim()
        require(ano.isNotEmpty()) { "请先填校园卡号" }
        val obj = get("api/BookCodeReqCancel?classno=$classNo&ano=$ano&token=${token()}")
        val ok = obj.optInt("RetNo", -1) == 0
        BookResult(
            ok = ok,
            code = obj.optString("BookCode", ""),
            message = obj.optString("RetDsp", if (ok) "已取消" else "取消失败"),
        )
    }

    /* ------------------------------ 扣费记录 ------------------------------ */

    private fun recordsOf(ano: String, token: String): List<WaterCost> {
        val obj = get("api/GetAccCostInfo?ano=$ano&token=$token&startdate=&enddate=")
        if (obj.optInt("RetNo", 0) != 0) {
            // 记录查不到不算致命，余额照样能看
            return emptyList()
        }
        val list = obj.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<WaterCost>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            out += WaterCost(
                posNo = item.optInt("PosNo", 0),
                zone = item.optString("ClassName", ""),
                time = item.optString("DealTime", ""),
                money = yuan(item.optString("DealMoney", "")),
                balance = yuan(item.optString("Balance", "")),
            )
        }
        return out.sortedByDescending { it.time }
    }

    /** "5.85 元" / "31.56 元" → 5.85 / 31.56。 */
    private fun yuan(raw: String): Double =
        raw.replace("元", "").trim().toDoubleOrNull() ?: 0.0

    /* ------------------------------ HTTP ------------------------------ */

    private fun token(force: Boolean = false): String {
        val cached = tokenCache
        if (!force && cached != null && System.currentTimeMillis() - tokenAt < TOKEN_TTL) {
            return cached
        }
        val obj = get("api/GetToken?userid=$SERVICE_USER&userpassword=$SERVICE_PASS")
        val fresh = obj.optString("Token", "")
        if (fresh.isBlank()) {
            throw IllegalStateException(obj.optString("RetDsp", "水控服务器没有返回 Token").ifBlank { "水控服务器没有返回 Token" })
        }
        tokenCache = fresh
        tokenAt = System.currentTimeMillis()
        return fresh
    }

    private fun get(path: String): JSONObject {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            // 不自动跟跳转：校外网络会被 302 到上网认证页，跟过去只会拿到一坨 HTML
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val text = readAll(if (code >= 400) conn.errorStream else conn.inputStream)
        val location = conn.getHeaderField("Location").orEmpty()
        conn.disconnect()

        if (code in 300..399) throw IllegalStateException(gatewayHint(location))
        if (code >= 400) throw IllegalStateException("水控服务器 HTTP $code")
        if (text.isEmpty()) throw IllegalStateException("水控服务器返回空响应")
        // 网关拦截时也可能直接回 200 + HTML 登录页
        if (!text.trimStart().startsWith("{")) throw IllegalStateException(gatewayHint(text))
        return JSONObject(text)
    }

    /**
     * 学校水控只对校内网开放：从校外（蜂窝、代理节点）请求会被网关 302 到上网认证页。
     * 这里把那种情况翻译成人话，否则用户只会看到 JSON 解析报错。
     */
    private fun gatewayHint(detail: String): String {
        val web = detail.contains("<!DOCTYPE", true) || detail.contains("<html", true) ||
            detail.contains("oauth", true) || detail.contains("sso.", true) ||
            detail.contains("gateway", true) || detail.contains("auth", true)
        return if (web) {
            "学校水控只允许校内网访问：当前网络被转到上网认证页。请连上校园 WiFi（或校内网）后再点刷新。"
        } else {
            "水控服务器返回了异常内容，稍后再试"
        }
    }

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        stream.use { input ->
            ByteArrayOutputStream().use { buffer ->
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    buffer.write(chunk, 0, read)
                }
                return String(buffer.toByteArray(), StandardCharsets.UTF_8)
            }
        }
    }
}

/** 一次洗澡的扣费。 */
data class WaterCost(
    val posNo: Int,
    val zone: String,
    /** "2026-11-07 14:11:00" */
    val time: String,
    val money: Double,
    val balance: Double,
)

/** 一个浴区此刻的状态 + 我在这儿的预约。 */
data class WaterClass(
    /** 服务端 ClassNo，预约接口要用 */
    val no: Int,
    val name: String,
    /** 机位总数 */
    val posNum: Int,
    /** 空闲台数（由空闲率换算） */
    val free: Int,
    /** 空闲率，百分比整数 */
    val freeRate: Int,
    /** 预约率，百分比整数 */
    val bookRate: Int,
    val booked: Boolean,
    /** 4 位预约码，没预约时是空串 */
    val bookCode: String,
) {
    val ratio: Float
        get() = if (posNum <= 0) 0f else (free.toFloat() / posNum).coerceIn(0f, 1f)
}

/** 预约/取消的结果。 */
data class BookResult(
    val ok: Boolean,
    val code: String,
    val message: String,
)

data class WaterSnapshot(
    val card: String = "",
    val balance: Double = 0.0,
    /** 单次预扣定额，一般 10 元 */
    val quota: Double = 0.0,
    val classes: List<WaterClass> = emptyList(),
    val records: List<WaterCost> = emptyList(),
    val fetchedAt: Long = 0L,
) {
    val last: WaterCost? get() = records.firstOrNull()

    /** 当前有效的那张预约（同一时间只可能有一张）。 */
    val booked: WaterClass? get() = classes.firstOrNull { it.booked }
}
