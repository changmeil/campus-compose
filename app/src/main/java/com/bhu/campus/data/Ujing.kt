package com.bhu.campus.data

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * U净 自助洗衣（美的 / 无锡小净）。
 *
 * 接口来自官方 App 2.4.20（com.midea.vm.washer）里打包的 weex bundle：
 * 业务请求都走 `https://phoenix.ujing.online/api/v1/`，扫码查设备是
 * `devices/scanWasherCode`，下单是 `orders/create`。
 *
 * 登录：`GET captcha?mobile=` 发短信，`POST login` 换 token。
 * 两步的字段名都是实测出来的（服务端用 protobuf 的 JSON 映射，字段写错会被
 * 静默丢弃，只表现成「验证码校验失败」），见 [login] 里的说明。
 */
object Ujing {

    private const val TAG = "Ujing"
    private const val BASE = "https://phoenix.ujing.online/api/v1/"
    private const val CONNECT_TIMEOUT = 12000
    private const val READ_TIMEOUT = 20000

    /** 登录后拿到的东西。token 直接塞 Authorization，原样带就行。 */
    data class Session(val mobile: String, val token: String, val userId: String = "")

    /** 一台洗衣机/烘干机的实时状态。 */
    data class DeviceStatus(
        /** 扫码绑定时用的原始二维码内容，后面靠它复查 */
        val qrCode: String,
        val deviceId: String,
        val storeId: String,
        val name: String,
        /** true = 现在可以下单（空闲） */
        val idle: Boolean,
        /** 设备正在被别人用、预计还有多少分钟空出来；0 表示未知 */
        val remainMinutes: Int,
        val deviceTypeId: String,
        val type: String,
        /** 服务端给的中文原因，比如「此设备正在运行中，请使用其他设备」 */
        val reason: String = "",
        /** 已有订单号；"0" 表示没有。有单未付时扫码会带回来 */
        val orderId: String = "",
        /** 设备状态码，10 一般是「有你的未付款订单」 */
        val status: Int = 0,
    )

    /* --------------------------- 网络底座 --------------------------- */

    private fun headers(token: String?): MutableMap<String, String> {
        val h = mutableMapOf(
            "content-type" to "application/json; charset=utf-8",
            // weex 请求里写死的三个头，服务端按这套认客户端
            "x-app-code" to "BA",
            "x-app-version" to "2.4.20",
            "weex-version" to "1.1.43",
            "x-mobile-brand" to (Build.BRAND ?: "Android"),
            "x-mobile-model" to (Build.MODEL ?: "Android"),
            "User-Agent" to "okhttp/4.3.1",
        )
        if (!token.isNullOrBlank()) {
            // 实测：裸 token 会被判成 401 "JWT token is missing"，必须带 Bearer
            h["Authorization"] = "Bearer $token"
        }
        return h
    }

    private fun call(
        path: String,
        method: String,
        body: JSONObject?,
        token: String?,
    ): Pair<Int, String> {
        val url = URL(BASE + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            headers(token).forEach { (k, v) ->
                setRequestProperty(k, v)
            }
            if (body != null && method != "GET") {
                doOutput = true
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use {
            val out = ByteArrayOutputStream()
            it.copyTo(out)
            out.toString("UTF-8")
        }.orEmpty()
        conn.disconnect()
        Log.i(TAG, "$method $path -> $code ${text.take(400)}")
        return code to text
    }

    private fun parse(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

    private fun ok(json: JSONObject?): Boolean = json != null && json.optInt("code", -1) == 0

    /* --------------------------- 登录 --------------------------- */

    /** 发短信验证码。失败时 null，第二个值是给用户看的原因。 */
    suspend fun sendCode(mobile: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (status, text) = call("captcha?mobile=$mobile", "GET", null, null)
        val json = parse(text)
        when {
            ok(json) -> true to ""
            else -> false to (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
        }
    }

    /**
     * 用手机号和短信码换 token。
     *
     * 两个字段名是实测出来的：服务端用 protobuf 的 JSON 映射，字段对不上会被
     * 静默丢弃（表现成「验证码校验失败」），只有给候选字段塞数字、让解析器报
     * 类型错误才能确认。结论是 `mobile` + `captcha`。
     *
     * 失败码 1102 = 验证码错误或已过期（5 分钟内有效）。
     */
    suspend fun login(mobile: String, code: String): Pair<Session?, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("mobile", mobile).put("captcha", code)
            val (status, text) = call("login", "POST", body, null)
            val json = parse(text)
            if (!ok(json)) {
                val message = json?.optString("message").orEmpty()
                val codeNum = json?.optInt("code", -1) ?: -1
                Log.w(TAG, "login failed: code=$codeNum $message")
                val shown = when {
                    codeNum == 1102 || message.contains("验证码错误") -> "验证码不对，或已经过期了，重新获取一次"
                    message.isNotBlank() -> message
                    else -> "HTTP $status"
                }
                return@withContext null to shown
            }
            val data = json?.optJSONObject("data")
            val token = findToken(data) ?: findToken(json)
            Log.i(TAG, "login ok tokenLen=${token?.length ?: 0} data=${data?.toString()?.take(300)}")
            if (token.isNullOrBlank()) {
                return@withContext null to "登录成功但没拿到 token，响应：${text.take(200)}"
            }
            Session(
                mobile = mobile,
                token = token,
                userId = data?.optString("id").orEmpty(),
            ) to ""
        }

    /** 在登录响应里找 token：字段名可能是 token / accessToken / id / data 整串。 */
    private fun findToken(json: JSONObject?): String? {
        json ?: return null
        val prefer = listOf("token", "accessToken", "access_token", "jwt", "authorization", "id", "userId")
        prefer.forEach { key ->
            val v = json.optString(key)
            if (v.isNotBlank() && v.length > 8) return v
        }
        val data = json.optJSONObject("data") ?: return null
        if (data !== json) findToken(data)?.let { return it }
        // data 本身就是一坨字符串 token 的情况
        if (data.length() == 0) return null
        return null
    }

    /* --------------------------- 扫码查设备 --------------------------- */

    /**
     * 扫机器上的二维码查状态。
     *
     * 二维码内容是 `https://q.ujing.com.cn/ucqrc/index.html?cd=xxxx`，
     * 原样当 qrCode 传回去即可；只给了 cd 码时这里补成完整地址。
     */
    suspend fun scanWasher(qrCode: String, session: Session): Pair<DeviceStatus?, String> =
        withContext(Dispatchers.IO) {
            val full = normalizeQr(qrCode)
            if (full.isBlank()) return@withContext null to "二维码内容为空"
            val body = JSONObject().put("qrCode", full)
            val (status, text) = call("devices/scanWasherCode", "POST", body, session.token)
            val json = parse(text)
            if (!ok(json)) {
                return@withContext null to (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
            }
            val data = json?.optJSONObject("data")
            val result = data?.optJSONObject("result")
                ?: data?.optJSONArray("result")?.optJSONObject(0)
            result ?: return@withContext null to "没解析到设备信息：${text.take(200)}"
            Log.i(TAG, "scan raw=$text")
            DeviceStatus(
                qrCode = full,
                deviceId = result.optString("deviceId").ifBlank { result.optString("virtualId") },
                storeId = result.optString("storeId"),
                name = result.optString("deviceName").ifBlank {
                    result.optString("name").ifBlank { result.optString("deviceNo") }
                },
                idle = result.optBoolean("createOrderEnabled", false),
                remainMinutes = result.optInt("minRemaintime", result.optInt("remainTime", 0)),
                deviceTypeId = result.optString("deviceTypeId"),
                // 下单 body 里的 type 是「业务类型」，洗衣机固定 1
                // （官方扫码后跳程序页带的就是 {type:1, deviceId}）；
                // moduleType(7) 是硬件模块代号，混用会被判「定价异常」。
                type = result.optString("type").ifBlank { "1" },
                reason = result.optString("reason"),
                orderId = result.optString("orderId").let { if (it == "0") "" else it },
                status = result.optInt("status", 0),
            ) to ""
        }

    /* --------------------------- 订单 / 余额 / 门店 --------------------------- */

    /** 一条洗衣订单，进行中和历史共用一个结构。 */
    data class WashOrder(
        val id: String,
        val deviceName: String,
        val storeName: String,
        val program: String,
        val statusText: String,
        val remainMinutes: Int,
        /** 金额原文，比如 "3.50"；取不到就空着 */
        val amount: String,
        val created: String,
    )

    /** 门店里的一台机器。 */
    data class StoreDevice(
        val deviceId: String,
        val name: String,
        val idle: Boolean,
        val remainMinutes: Int,
        val type: String,
    )

    data class StoreDetail(
        val storeId: String,
        val name: String,
        val devices: List<StoreDevice>,
        /** 运营主体，支付时必带 */
        val serviceSubjectId: String = "",
    ) {
        val idleCount: Int get() = devices.count { it.idle }
    }

    /**
     * 进行中的订单：`GET orders/running`。
     *
     * 官方 weex 里这个接口不带任何参数，返回当前账号在跑的机器（洗完前一直有）。
     * 拿它就能在今日页显示「3 楼左机 还剩 12 分钟」这种。
     */
    suspend fun runningOrders(session: Session): Pair<List<WashOrder>, String> =
        withContext(Dispatchers.IO) {
            val (status, text) = call("orders/running", "GET", null, session.token)
            val json = parse(text)
            if (!ok(json)) {
                return@withContext emptyList<WashOrder>() to
                    (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
            }
            val orders = pickOrders(json)
            Log.i(TAG, "running orders=${orders.size} ${text.take(300)}")
            orders to ""
        }

    /**
     * 历史订单：`GET orders/history`。
     *
     * 服务端分页，但只取第一页就够看了（默认给最近的一批）。
     */
    suspend fun orderHistory(session: Session, page: Int = 1): Pair<List<WashOrder>, String> =
        withContext(Dispatchers.IO) {
            val (status, text) = call("orders/history?page=$page&pageSize=20", "GET", null, session.token)
            val json = parse(text)
            if (!ok(json)) {
                return@withContext emptyList<WashOrder>() to
                    (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
            }
            val orders = pickOrders(json)
            Log.i(TAG, "history orders=${orders.size} ${text.take(300)}")
            orders to ""
        }

    /**
     * 钱包余额：`GET bill/user/balance`。
     *
     * 这个接口要 `userId`，裸调会回 1002「请求参数验证失败」；
     * 登录响应里不一定带 id，所以先 `GET user` 补一次。
     */
    suspend fun balance(session: Session): Pair<String, String> = withContext(Dispatchers.IO) {
        val uid = if (session.userId.isNotBlank()) session.userId else fetchUserId(session)
        val query = if (uid.isBlank()) "" else "?userId=$uid"
        val (status, text) = call("bill/user/balance$query", "GET", null, session.token)
        val json = parse(text)
        if (!ok(json)) {
            Log.w(TAG, "balance failed uid=$uid ${text.take(200)}")
            return@withContext "" to (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
        }
        val data = json?.optJSONObject("data")
        val value = data?.pick("balance", "amount", "money", "availableAmount", "usableAmount")
            ?: json?.pick("balance", "amount")
        Log.i(TAG, "balance=$value uid=$uid ${text.take(200)}")
        value.orEmpty() to ""
    }

    /** 顺手从 `GET user` 里捞一下用户 id，余额接口要用。 */
    private fun fetchUserId(session: Session): String {
        val (_, text) = call("user", "GET", null, session.token)
        val json = parse(text) ?: return ""
        val data = json.optJSONObject("data")
        val id = data?.pick("id", "userId", "uid").orEmpty().ifBlank {
            json.pick("id", "userId", "uid")
        }
        Log.i(TAG, "user id=$id ${text.take(200)}")
        return id
    }

    /**
     * 门店详情：`GET app/washer/store/info?storeId=`。
     *
     * 扫码查机器只能拿到一台，这个接口能把整间洗衣房的机器一次列出来，
     * 于是「哪台空着」不用一台台扫。
     */
    suspend fun storeDetail(session: Session, storeId: String): Pair<StoreDetail?, String> =
        withContext(Dispatchers.IO) {
            if (storeId.isBlank()) return@withContext null to "没有门店号"
            val (status, text) = call("app/washer/store/info?storeId=$storeId", "GET", null, session.token)
            val json = parse(text)
            if (!ok(json)) {
                return@withContext null to
                    (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
            }
            val data = json?.optJSONObject("data")
            val devices = mutableListOf<StoreDevice>()
            arraysOf(json).forEach { arr ->
                arr.forEach { o ->
                    val id = o.pick("deviceId", "virtualId", "id")
                    if (id.isBlank()) return@forEach
                    val name = o.pick("deviceName", "name", "deviceNo", "virtualName")
                    if (name.isBlank() && o.pick("storeId").isBlank()) return@forEach
                    devices += StoreDevice(
                        deviceId = id,
                        name = name.ifBlank { id.takeLast(6) },
                        idle = o.optBoolean("createOrderEnabled", false) ||
                            o.pick("status").let { it == "1" || it == "IDLE" || it == "idle" } ||
                            o.optInt("surplusStatus", -1) == 1,
                        remainMinutes = o.optInt("minRemaintime", o.optInt("remainTime", 0)),
                        type = o.pick("deviceTypeId", "type"),
                    )
                }
            }
            val storeName = data?.pick("storeName", "name", "storeAddr").orEmpty()
            val subjectId = data?.pick("serviceSubjectId").orEmpty().ifBlank {
                findString(json, "serviceSubjectId")
            }
            Log.i(TAG, "store $storeId devices=${devices.size} ${text.take(300)}")
            StoreDetail(storeId, storeName, devices.distinctBy { it.deviceId }, subjectId) to ""
        }

    /** 一台机器能选的洗涤模式，带价格和时长。 */
    data class WashProgram(
        val id: String,
        val name: String,
        /** 已经换算成元的显示文本，比如 "4" / "3.5"；没价格就是空 */
        val price: String,
        val minutes: Int,
        val desc: String = "",
    )

    /**
     * 洗涤模式：`GET app/washer/devices/program/info?deviceId=`。
     *
     * 下单前先看这个——不同模式价格差别不小，学生最关心的就是「洗一次多少钱」。
     */
    suspend fun programs(session: Session, deviceId: String): Pair<List<WashProgram>, String> =
        withContext(Dispatchers.IO) {
            if (deviceId.isBlank()) return@withContext emptyList<WashProgram>() to "没有设备号"
            val (status, text) =
                call("app/washer/devices/program/info?deviceId=$deviceId", "GET", null, session.token)
            val json = parse(text)
            if (!ok(json)) {
                return@withContext emptyList<WashProgram>() to
                    (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
            }
            // 只认 data.deviceWashModel 这一层。递归捞会把 additionParams 里的
            // 「筒自洁」选项也当成洗涤模式，价格全是 0，看着像白洗。
            val arr = json?.optJSONObject("data")?.optJSONArray("deviceWashModel")
            val list = mutableListOf<WashProgram>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.pick("workModelName", "washModelName", "name", "modelName")
                    if (name.isBlank()) continue
                    if (o.optInt("hide", 0) == 1) continue
                    // basePrice 以「分」为单位：400 = 4 元
                    val cents = o.optInt("promotionPrice", 0).takeIf { it > 0 }
                        ?: o.optInt("basePrice", 0)
                    list += WashProgram(
                        id = o.pick("workModelId", "id", "washModelId"),
                        name = name,
                        price = if (cents > 0) yuan(cents) else "",
                        minutes = o.optInt("time", o.optInt("duration", 0)),
                        desc = o.optString("description"),
                    )
                }
            }
            Log.i(TAG, "programs=${list.size} raw=${text.take(1500)}")
            list to ""
        }

    /* --------------------------- 订单状态 --------------------------- */

    /**
     * 订单状态码，抄自官方 weex bundle 里的 `ORDER_STATUSTEXT`。
     *
     * 这套码是整个洗衣流程的主线：建单 10 → 付钱 20 → 自洁 22/30/35 → 洗衣 21/40 → 50。
     * 界面和岛上显示什么，一律按它分支，不靠中文文案猜（文案服务端改过一次）。
     */
    object OrderStatus {
        const val BOOKED = 10          // 已预约（等你付款）
        const val PAID = 20            // 已支付（等启动）
        const val STARTING = 21        // 启动中
        const val SELF_CLEAN_START = 22 // 自洁启动中
        const val LIQUID = 24          // 正在投放洗衣液
        const val PROTECT = 29         // 退单保护
        const val SELF_CLEANING = 30   // 自洁中
        const val SELF_CLEAN_DONE = 35 // 自洁完成（5 分钟内要启动洗衣）
        const val RUNNING = 40         // 运行中
        const val DONE = 50            // 订单完成
        const val TIMEOUT_UNPAID = 51  // 超时未支付
        const val START_FAILED = 52    // 启动失败
        const val CANCELLED = 53       // 订单已取消
        const val TIMEOUT_UNSTART = 54 // 超时未启动
        const val FAULT = 60           // 故障中

        /** 这张单已经结束了（不管是洗完、超时还是取消）。 */
        fun ended(s: Int) = s == DONE || s == TIMEOUT_UNPAID || s == START_FAILED ||
            s == CANCELLED || s == TIMEOUT_UNSTART

        /** 机器已经在转了（含自洁和洗涤）。 */
        fun spinning(s: Int) = s == STARTING || s == SELF_CLEAN_START || s == LIQUID ||
            s == SELF_CLEANING || s == SELF_CLEAN_DONE || s == RUNNING || s == PROTECT

        fun textOf(s: Int): String = when (s) {
            BOOKED -> "已预约"
            PAID -> "已支付"
            STARTING -> "启动中"
            SELF_CLEAN_START -> "自洁启动中"
            LIQUID -> "正在投放洗衣液"
            PROTECT -> "退单保护"
            SELF_CLEANING -> "自洁中"
            SELF_CLEAN_DONE -> "自洁完成"
            RUNNING -> "运行中"
            DONE -> "订单完成"
            TIMEOUT_UNPAID -> "超时未支付"
            START_FAILED -> "启动失败"
            CANCELLED -> "订单已取消"
            TIMEOUT_UNSTART -> "超时未启动"
            FAULT, 61 -> "故障中"
            else -> ""
        }
    }

    /** 一张订单的实时状态，界面和岛都看它。 */
    data class OrderDetail(
        val orderId: String,
        val status: Int,
        val statusText: String,
        /** 这一阶段还剩多少秒（自洁/洗涤/启动窗口）；0 = 没有倒计时 */
        val remainSeconds: Int,
        val deviceName: String = "",
        val program: String = "",
        val amount: String = "",
        /** 服务端原文，出问题时打日志用 */
        val raw: String = "",
    ) {
        val ended: Boolean get() = OrderStatus.ended(status)
        val paid: Boolean get() = status != OrderStatus.BOOKED && !ended
        /** 标题给人看的那一段：机型 + 模式 */
        val label: String
            get() = listOf(deviceName, program).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /**
     * 订单详情：`GET orders/{id}/detail`。
     *
     * 这是唯一能准确回答「钱付了没、机器转到哪一步」的地方 ——
     * `orders/running` 只回运行中的那批，已支付待启动的单根本不在里面，
     * 之前岛上一直卡在「待支付」就是因为只查了 running。
     */
    suspend fun orderDetail(session: Session, orderId: String): Pair<OrderDetail?, String> =
        withContext(Dispatchers.IO) {
            if (orderId.isBlank()) return@withContext null to "没有订单号"
            val (code, text) = call("orders/$orderId/detail", "GET", null, session.token)
            val json = parse(text)
            if (!ok(json)) {
                return@withContext null to
                    (json?.optString("message").orEmpty().ifBlank { "HTTP $code" })
            }
            val node = orderNode(json) ?: return@withContext null to "没解析到订单"
            val status = node.optInt("status", -1).takeIf { it >= 0 }
                ?: findInt(json, "status")
            Log.i(TAG, "orderDetail $orderId status=$status remain=${remainSecondsOf(node)} raw=$text")
            val remark = node.pick("statusRemark", "statusName", "statusText", "statusDesc")
            OrderDetail(
                orderId = orderId,
                status = status,
                statusText = remark.ifBlank { OrderStatus.textOf(status) },
                remainSeconds = remainSecondsOf(node),
                deviceName = node.pick("deviceTypeName").let { type ->
                    val no = node.pick("deviceNo")
                    when {
                        type.isNotBlank() && no.isNotBlank() -> "$type $no 号"
                        type.isNotBlank() -> type
                        else -> node.pick("deviceName", "virtualName")
                    }
                },
                program = node.pick(
                    "cycle", "programName", "modeName", "workModelName", "setName", "workModel",
                ),
                amount = node.pick(
                    "payPrice", "payAmount", "washPrice", "totalAmount", "amount", "price",
                ),
                raw = text,
            ) to ""
        }

    /**
     * 启动洗衣：`GET orders/{id}/control/start`。
     *
     * 只对**已支付**的单有效；没付钱调用会被服务端挡回来（原话会放在 second 里）。
     * 选了筒自洁的单要先自洁完，否则同样起不来。
     */
    suspend fun startWash(session: Session, orderId: String): Pair<Boolean, String> =
        control(session, orderId, "start", "启动洗衣")

    /**
     * 启动筒自洁：`GET orders/{id}/control/selfClean`。
     *
     * 官方提示里写得很直白：要先在洗衣机面板上按【筒自洁】按钮，再点确认，
     * 否则机器不认这条指令。自洁 3 分钟（深度自洁 10 分钟），
     * 自洁结束后 5 分钟内必须启动洗衣，否则订单自动取消。
     */
    suspend fun startSelfClean(session: Session, orderId: String): Pair<Boolean, String> =
        control(session, orderId, "selfClean", "启动筒自洁")

    /** 中途停机：`GET orders/{id}/control/stop`。 */
    suspend fun stopWash(session: Session, orderId: String): Pair<Boolean, String> =
        control(session, orderId, "stop", "停止洗涤")

    private suspend fun control(
        session: Session,
        orderId: String,
        action: String,
        what: String,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (orderId.isBlank()) return@withContext false to "没有订单号"
        val (code, text) = call("orders/$orderId/control/$action", "GET", null, session.token)
        val json = parse(text)
        if (!ok(json)) {
            val msg = json?.optString("message").orEmpty()
            Log.w(TAG, "$what failed $orderId ${text.take(300)}")
            return@withContext false to msg.ifBlank { "$what 失败（HTTP $code）" }
        }
        Log.i(TAG, "$what ok $orderId ${text.take(300)}")
        true to ""
    }

    /** 订单详情里 data 可能直接是订单，也可能包一层 order；都认。 */
    private fun orderNode(json: JSONObject?): JSONObject? {
        val data = json?.optJSONObject("data") ?: return json
        listOf("order", "orderInfo", "washerOrder", "detail").forEach { k ->
            data.optJSONObject(k)?.let { if (it.length() > 0) return it }
        }
        return data
    }

    /**
     * 从订单里算剩余秒数。
     *
     * 单位是实测出来的：detail 里的 `remainTime` 是**秒**（超强洗 60 分钟的单，
     * 洗了 32 分钟后服务端给 1600），而扫码接口的 `minRemaintime` 是**分钟**。
     * 混着当分钟算会把 26 分钟显示成 26 小时。
     * 有结束时间戳时优先用时间戳，它是绝对的，不受轮询间隔影响。
     */
    private fun remainSecondsOf(node: JSONObject): Int {
        listOf(
            "washEndTime", "endTime", "expectEndTime", "finishTime", "expectFinishTime",
            "estimateEndTime", "orderEndTime", "overTime",
        ).forEach { k ->
            val raw = parseTime(node.optString(k))
            if (raw > 0) {
                val sec = ((raw - System.currentTimeMillis()) / 1000).toInt()
                if (sec in 1..7200) return sec
            }
        }
        // 秒
        listOf(
            "remainTime", "surplusTime", "remainSecond", "surplusSecond",
            "remainingSeconds", "remainingTime",
        ).forEach { k ->
            val v = node.optInt(k, 0)
            if (v > 0) return v
        }
        // 分钟
        listOf("minRemaintime", "remainMinute", "surplusMinute", "remainMinutes").forEach { k ->
            val v = node.optInt(k, 0)
            if (v > 0) return v * 60
        }
        return 0
    }

    /** ISO 时间（`2026-11-13T07:52:26Z`）和毫秒时间戳都认；解析不了返回 0。 */
    private fun parseTime(raw: String): Long {
        if (raw.isBlank() || raw == "null") return 0L
        raw.toLongOrNull()?.let { v ->
            return if (v < 10_000_000_000L) v * 1000 else v
        }
        return runCatching {
            java.time.Instant.parse(raw).toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            }.getOrDefault(0L)
        }
    }

    /* --------------------------- 预约（建单占位） --------------------------- */

    /**
     * 建单占住机器：`POST orders/create`。
     *
     * body 是逆向 `washer/program.js` 得出来的五个字段：
     * `type` / `deviceTypeId` / `deviceId` / `deviceWashModelId` / `storeId`，
     * 少一个服务端都会报参数验证失败（但不会告诉你是哪个，得试）。
     *
     * App 里只走到这一步：建单后机器就归你占用，未付款约 3 分钟服务端自动撤单。
     * 付款交给 U净 自己的小程序 —— 微信/支付宝/银联都要 SDK，App 里做不了。
     */
    suspend fun createOrder(
        session: Session,
        device: DeviceStatus,
        programId: String,
    ): Pair<OrderCreated?, String> = withContext(Dispatchers.IO) {
        if (device.deviceId.isBlank()) return@withContext null to "没有设备号，先刷新一次"
        if (programId.isBlank()) return@withContext null to "先选一个洗涤模式"
        val storeId = device.storeId.ifBlank { storeIdOf(session, device.deviceId) }
        // 这几个 id 在服务端是数字类型：传字符串能过参数校验，
        // 但到「按机型 + 模式查价」那步会变成 1725 定价异常。
        val body = JSONObject().apply {
            put("type", num(device.type.ifBlank { "1" }))
            put("deviceTypeId", num(device.deviceTypeId))
            put("deviceId", device.deviceId)
            put("deviceWashModelId", num(programId))
            put("storeId", storeId)
        }
        val (status, text) = call("orders/create", "POST", body, session.token)
        val json = parse(text)
        if (!ok(json)) {
            Log.w(TAG, "createOrder failed ${text.take(300)}")
            return@withContext null to
                (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
        }
        val data = json?.optJSONObject("data")
        val orderId = data?.pick("orderId", "id", "orderNo").orEmpty()
            .ifBlank { json?.pick("orderId").orEmpty() }
        Log.i(TAG, "createOrder ok storeId=$storeId orderId=$orderId raw=${text.take(600)}")
        if (orderId.isBlank()) {
            return@withContext null to "下单成功但没返回订单号：${text.take(160)}"
        }
        OrderCreated(
            orderId = orderId,
            amount = data?.pick("payAmount", "amount", "totalAmount", "price").orEmpty(),
            raw = text,
        ) to ""
    }

    /** 建单结果。`amount` 是应付金额原文（元）。 */
    data class OrderCreated(val orderId: String, val amount: String, val raw: String)

    /** 取消订单：`POST orders/cancelOrder`。未付款的单可以直接撤掉。 */
    suspend fun cancelOrder(session: Session, orderId: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("orderId", num(orderId))
            val (status, text) = call("orders/cancelOrder", "POST", body, session.token)
            val json = parse(text)
            if (!ok(json)) {
                Log.w(TAG, "cancelOrder failed $orderId ${text.take(200)}")
                return@withContext false to
                    (json?.optString("message").orEmpty().ifBlank { "HTTP $status" })
            }
            Log.i(TAG, "cancelOrder ok $orderId ${text.take(200)}")
            true to ""
        }

    /**
     * 把门店号补齐：`GET devices/{id}`。
     *
     * 扫码接口不给 `storeId`（`washer/home.js` 里那版只回设备本身），
     * 建单又必须带，所以打开预约页时先补一次。
     * 字段层级各学校不一致，写死路径容易空，于是从整个响应里兜底捞。
     */
    suspend fun withStoreId(session: Session, device: DeviceStatus): DeviceStatus =
        withContext(Dispatchers.IO) {
            if (device.storeId.isNotBlank()) device
            else device.copy(storeId = storeIdOf(session, device.deviceId))
        }

    private fun storeIdOf(session: Session, deviceId: String): String {
        val (_, text) = call("devices/$deviceId", "GET", null, session.token)
        val json = parse(text) ?: return ""
        val found = findString(json, "storeId")
        Log.i(TAG, "device $deviceId storeId=$found raw=${text.take(500)}")
        return found
    }

    /** 在整个 JSON 里递归找一个键的值（只认字符串）。 */
    private fun findString(root: JSONObject?, key: String): String {
        if (root == null) return ""
        root.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        root.keys().forEach { k ->
            when (val v = root.opt(k)) {
                is JSONObject -> findString(v, key).takeIf { it.isNotBlank() }?.let { return it }
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) {
                        val item = v.optJSONObject(i) ?: continue
                        findString(item, key).takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return ""
    }

    /** 同上，找整数；找不到给 -1。 */
    private fun findInt(root: JSONObject?, key: String): Int {
        if (root == null) return -1
        val direct = root.opt(key)
        if (direct is Number) return direct.toInt()
        if (direct is String) direct.trim().toIntOrNull()?.let { return it }
        root.keys().forEach { k ->
            when (val v = root.opt(k)) {
                is JSONObject -> findInt(v, key).takeIf { it >= 0 }?.let { return it }
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) {
                        val item = v.optJSONObject(i) ?: continue
                        findInt(item, key).takeIf { it >= 0 }?.let { return it }
                    }
                }
            }
        }
        return -1
    }

    /** 从响应里捞出「所有像订单的对象数组」，取第一个非空的。 */
    private fun pickOrders(json: JSONObject?): List<WashOrder> {
        val candidates = arraysOf(json)
        for (arr in candidates) {
            val orders = arr.mapNotNull { o ->
                val id = o.pick("orderId", "id")
                if (id.isBlank()) return@mapNotNull null
                // 洗衣机这边没有 deviceName，是「机型 + 机号」，比如 10kg滚筒机 3 号
                val typeName = o.pick("deviceTypeName")
                val no = o.pick("deviceNo")
                val device = when {
                    typeName.isNotBlank() && no.isNotBlank() -> "$typeName $no 号"
                    typeName.isNotBlank() -> typeName
                    else -> o.pick("deviceName", "virtualName", "machineName", "deviceNo")
                }
                val store = o.pick("storeName", "storeAddr", "shopName")
                // 全是 id 的数组多半是别的东西，跳过
                if (device.isBlank() && store.isBlank() && o.pick("status", "statusName").isBlank()) {
                    return@mapNotNull null
                }
                WashOrder(
                    id = id,
                    deviceName = device,
                    storeName = store,
                    program = o.pick("programName", "modeName", "workModel", "setName"),
                    // status 是数字码（"50"），给人看的是 statusRemark（"订单完成"）
                    statusText = o.pick("statusRemark", "statusName", "statusText", "statusDesc"),
                    remainMinutes = o.optInt("minRemaintime", o.optInt("remainTime", o.optInt("surplusTime", 0))),
                    amount = o.pick("payAmount", "totalAmount", "amount", "price", "orderAmount"),
                    created = o.pick("createAt", "createTime", "gmtCreate", "startTime", "orderTime"),
                )
            }
            if (orders.isNotEmpty()) return orders
        }
        return emptyList()
    }

    /** 递归收集 JSON 里所有非空对象数组，订单/设备都能用同一套捞法。 */
    private fun arraysOf(root: JSONObject?): List<List<JSONObject>> {
        val out = mutableListOf<List<JSONObject>>()
        fun walk(value: Any?, depth: Int) {
            if (depth > 6 || value == null) return
            when (value) {
                is JSONObject -> value.keys().forEach { walk(value.opt(it), depth + 1) }
                is org.json.JSONArray -> {
                    val objs = (0 until value.length()).mapNotNull { value.optJSONObject(it) }
                    if (objs.isNotEmpty()) {
                        out += objs
                        objs.forEach { o -> o.keys().forEach { walk(o.opt(it), depth + 1) } }
                    } else {
                        (0 until value.length()).forEach { walk(value.opt(it), depth + 1) }
                    }
                }
            }
        }
        walk(root, 0)
        return out
    }

    /** 能当数字用就当数字塞进 JSON —— 服务端按数字建索引。 */
    private fun num(raw: String): Any = raw.trim().toIntOrNull() ?: raw

    /** 金额从分换成元，整数就不带小数点：400 -> "4"，350 -> "3.5"。 */
    private fun yuan(cents: Int): String {
        if (cents % 100 == 0) return (cents / 100).toString()
        return String.format(java.util.Locale.CHINA, "%.2f", cents / 100.0)
            .trimEnd('0').trimEnd('.')
    }

    /** 多个候选键里取第一个非空的字符串（服务端不同版本字段名换过）。 */
    private fun JSONObject.pick(vararg keys: String): String {
        keys.forEach { k ->
            val v = optString(k)
            if (v.isNotBlank() && v != "null" && v != "0") return v
        }
        return ""
    }

    /** 「755501240412324394」这种裸 cd 码补成完整二维码地址。 */
    fun normalizeQr(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        if (v.startsWith("http", ignoreCase = true)) return v
        if (v.startsWith("cd=")) return "https://q.ujing.com.cn/ucqrc/index.html?$v"
        return "https://q.ujing.com.cn/ucqrc/index.html?cd=$v"
    }

    /** 从二维码链接里抠出 cd 码，用来给设备起个短名字。 */
    fun deviceCode(qrCode: String): String =
        Regex("cd=([A-Za-z0-9]+)").find(qrCode)?.groupValues?.get(1) ?: qrCode.takeLast(6)
}
