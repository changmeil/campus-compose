package com.bhu.campus.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 慧生活798（校园直饮水 / 钱包）的直连接口。
 *
 * 接口是从官方客户端的行为里读出来的（开源项目 nocookies111/life-798 只给了调法，
 * 网关和盐值放在 secrets.properties 里没提交，这边是照着 release 包里内联的常量还原的）：
 *
 *  - `GET  /captcha/?s=<随机会话>`            → 图形验证码图片（PNG）
 *  - `POST /acc/login/code`                   → 发短信，body {un, authCode=图形码, s}
 *  - `POST /acc/login?sign=<md5>`             → 换 token，body {authCode=短信码, un}
 *  - `GET  /acc/wallet/owner` + Authorization → 钱包余额
 *
 * 登录分两条线：小程序线（ApplicationType 1,5，要 cid）和设备线（1,1，要 sign）。
 * 查钱包要走设备线，所以这里统一用 1,1。
 *
 * 服务端目前看起来并不校验 sign（用错盐值也照样返回「短信验证码错误」），
 * 但还是老实算上，免得哪天收紧了直接不能用。
 */
object Life798 {

    private const val TAG = "Life798"
    private const val GATEWAY = "https://i.ilife798.com/api/v1"
    private const val CID = "drinkwaterapp123456789"
    private const val APP_SALT = "AbCdEfGhIjKlMnOpQrStUvWxYz12345"
    private const val VERSION = "3.1.9"
    private const val PREFS = "life798"
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 15000

    /** 登录态。空 token 表示还没登录过。 */
    var token by mutableStateOf("")
        private set
    var phone by mutableStateOf("")
        private set

    /** 钱包余额（元）。null 表示没查过或已失效。 */
    var balance by mutableStateOf<Double?>(null)
        private set
    var balanceAt by mutableStateOf(0L)
        private set

    /** 钱包里的各个端点（饮水、洗浴等分开记账），点开能看到明细。 */
    var wallets by mutableStateOf<List<Wallet>>(emptyList())
        private set

    data class Wallet(val endpoint: String, val name: String, val balance: Double)

    /** 上次操作的错误，给界面显示用。 */
    var lastError by mutableStateOf<String?>(null)
        private set

    private var app: Context? = null

    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        token = p.getString("token", "").orEmpty()
        phone = p.getString("phone", "").orEmpty()
        balance = p.getString("balance", null)?.toDoubleOrNull()
        balanceAt = p.getLong("balanceAt", 0L)
    }

    val loggedIn: Boolean get() = token.isNotBlank()

    fun logout() {
        token = ""
        balance = null
        balanceAt = 0L
        wallets = emptyList()
        lastError = null
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("token")?.remove("balance")?.remove("balanceAt")?.apply()
    }

    /** 10 位随机会话串，图形码和短信都认它。 */
    fun newCaptchaKey(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString { repeat(10) { append(chars[Random.nextInt(chars.length)]) } }
    }

    /** 拉图形验证码（PNG 字节）。 */
    suspend fun captcha(key: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$GATEWAY/captcha/?s=${URLEncoder.encode(key, "UTF-8")}" +
                "&r=${System.currentTimeMillis()}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "WaterWidget/$VERSION (Android)")
            }
            try {
                val bytes = conn.inputStream.use { it.readBytes() }
                Log.i(TAG, "captcha ${bytes.size}B http=${conn.responseCode}")
                bytes
            } finally {
                conn.disconnect()
            }
        }.onFailure { lastError = "图形码拉不下来：${it.message}" }.getOrNull()
    }

    /** 提交图形码换短信。返回 null 表示成功，否则是错误信息。 */
    suspend fun sendCode(phone: String, graphCode: String, key: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("un", phone)
                .put("authCode", graphCode)
                .put("s", key)
            val json = runCatching {
                call("POST", "/acc/login/code", body.toString(), null, "1,1", VERSION)
            }.getOrElse { return@withContext "发不出去：${it.message}" }
            val err = errorOf(json)
            Log.i(TAG, "sendCode ${if (err == null) "ok" else err}")
            err
        }

    /** 提交短信验证码换 token。返回 null 表示成功。 */
    suspend fun login(phone: String, smsCode: String): String? = withContext(Dispatchers.IO) {
        val sign = md5("authCode=$smsCode&un=$phone$APP_SALT")
        val body = JSONObject().put("authCode", smsCode).put("un", phone)
        val json = runCatching {
            call("POST", "/acc/login?sign=$sign", body.toString(), null, "1,1", VERSION)
        }.getOrElse { return@withContext "登录失败：${it.message}" }
        errorOf(json)?.let { return@withContext it }

        val al = json.optJSONObject("data")?.optJSONObject("al")
        val newToken = al?.optString("token").orEmpty()
        if (newToken.isBlank()) return@withContext "登录返回里没有 token"

        token = newToken
        this@Life798.phone = phone
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("token", newToken)?.putString("phone", phone)?.apply()
        Log.i(TAG, "login ok, token 长度 ${newToken.length}")
        null
    }

    /** 拉钱包余额。返回 null 表示成功，否则是错误信息。 */
    suspend fun refreshWallet(): String? = withContext(Dispatchers.IO) {
        if (!loggedIn) return@withContext "还没登录"
        val json = runCatching {
            call("GET", "/acc/wallet/owner", null, token, "1,1", VERSION)
        }.getOrElse { return@withContext "查询失败：${it.message}" }

        errorOf(json)?.let {
            // token 过期：服务端一般回「登录已失效」这类话，清掉让用户重登
            if (it.contains("登录") || it.contains("token", true)) logout()
            return@withContext it
        }

        val data = json.optJSONObject("data")
        val list = buildList {
            data?.optJSONObject("aw")?.let(::add)
            val eps = data?.optJSONArray("eps")
            if (eps != null) for (i in 0 until eps.length()) add(eps.optJSONObject(i) ?: continue)
        }.mapNotNull { parseWallet(it) }

        val sum = list.firstOrNull { it.endpoint == "aw" }?.balance
            ?: list.firstOrNull()?.balance
            ?: 0.0
        balance = sum
        balanceAt = System.currentTimeMillis()
        wallets = list.filter { it.endpoint != "aw" }
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("balance", sum.toString())?.putLong("balanceAt", balanceAt)?.apply()
        Log.i(TAG, "wallet ￥$sum, 端点 ${list.size} 个")
        null
    }

    private fun parseWallet(w: JSONObject): Wallet? {
        val ep = w.optJSONObject("ep")
        val owner = w.optJSONObject("owner")
        val amount = w.optDouble("total", Double.NaN).takeIf { !it.isNaN() }
            ?: w.optDouble("olCash", Double.NaN).takeIf { !it.isNaN() }
            ?: w.optDouble("balance", 0.0)
        return Wallet(
            endpoint = ep?.optString("id").orEmpty().ifBlank { "aw" },
            name = ep?.optString("name").orEmpty().ifBlank { "校园钱包" },
            balance = amount,
        )
    }

    /** code != 0 时返回 msg；成功返回 null。 */
    private fun errorOf(json: JSONObject): String? {
        val code = json.optInt("code", -999)
        if (code == 0) return null
        return json.optString("msg").ifBlank { "接口返回 code=$code" }
    }

    private fun call(
        method: String,
        path: String,
        body: String?,
        token: String?,
        appType: String,
        version: String,
    ): JSONObject {
        val conn = (URL(GATEWAY + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", "Android_ilife798_$version")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("ApplicationType", appType)
            setRequestProperty("VersionCode", version)
            setRequestProperty("Accept-Language", "zh-Hans-CN;q=1")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", token)
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val http = conn.responseCode
            val stream = if (http >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toString("UTF-8")
            }.orEmpty()
            Log.i(TAG, "$method $path http=$http ${text.take(120)}")
            return runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("返回不是 JSON（http=$http）：${text.take(80)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(StandardCharsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
