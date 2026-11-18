package com.bhu.campus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.IslandHub
import com.bhu.campus.data.Ujing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 提示「会被自动撤单」这类要紧但不致命的话，用比正文更实的橙。 */
private val ColorWarn = Color(0xFFB26A00)
private val ColorDanger = Color(0xFFC62828)
private val ColorSelfClean = Color(0xFF6A4BB5)
private val ColorRunning = Color(0xFF0066CC)

/**
 * 预约洗衣机 → 看到它洗完。
 *
 * 钱还是在 U净 小程序里付（微信/支付宝都要 SDK，第三方 App 付不掉），
 * 但**付完之后的所有动作都在这里做**：
 *
 * ```
 * 建单(10) ──小程序付款──▶ 已支付(20) ──开始筒自洁──▶ 自洁中(30)
 *                                        └─开始洗衣─▶ 洗涤中(40) ──▶ 完成(50)
 * ```
 *
 * 一开始只有建单，岛上只能显示「待支付」，付完钱机器转起来 App 完全不知道
 * （`orders/running` 里没有已支付待启动的单）。现在每 3 秒查一次订单详情，
 * 状态、剩余时间、该点哪个按钮全都跟着真实进度走。
 */
@Composable
fun UjingReserveDialog(
    device: Ujing.DeviceStatus,
    onDismiss: () -> Unit,
    onReserved: () -> Unit,
) {
    val context = LocalContext.current
    val session = CampusStore.ujing.let {
        if (it.loggedIn) Ujing.Session(it.mobile, it.token) else null
    }
    val label = CampusStore.ujing.bindings
        .firstOrNull { it.qrCode == device.qrCode }?.label
        .orEmpty()
        .ifBlank { Ujing.deviceCode(device.qrCode) }

    var loading by remember { mutableStateOf(true) }
    var programs by remember { mutableStateOf<List<Ujing.WashProgram>>(emptyList()) }
    var selected by remember { mutableStateOf<Ujing.WashProgram?>(null) }
    var creating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    // 扫码不给门店号，建单必须要，打开时补一次
    var full by remember { mutableStateOf(device) }
    val scope = rememberCoroutineScope()

    // 正在跟的订单：先用内存里缓存的那份，避免打开瞬间空白
    var orderId by remember { mutableStateOf(CampusStore.ujing.orderId) }
    var detail by remember { mutableStateOf(CampusStore.ujingOrder) }
    /** 空 = 没在请求；start / selfClean / stop */
    var acting by remember { mutableStateOf("") }
    /** 停机要点两次，防误触 */
    var confirmStop by remember { mutableStateOf(false) }
    /** 本地倒计时终点，靠它走秒，轮询到了再校准 */
    var endAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 走秒：只有真的在倒数时才转
    LaunchedEffect(endAt) {
        if (endAt <= 0L) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= endAt) break
            delay(500)
        }
    }

    // 打开时：扫码带回的单号 / 上次记下的单号，都优先于选洗法
    LaunchedEffect(device.deviceId, device.orderId) {
        if (session == null) {
            note = "先登录 U净"
            loading = false
            return@LaunchedEffect
        }
        if (orderId.isBlank() && device.orderId.isNotBlank()) {
            orderId = device.orderId
            CampusStore.setUjingOrderId(device.orderId)
        }
        if (orderId.isNotBlank()) {
            // 有单就不该再让人选洗法，直接进进度视图
            loading = false
            return@LaunchedEffect
        }
        full = Ujing.withStoreId(session, device)
        val (list, err) = Ujing.programs(session, device.deviceId)
        programs = list
        selected = list.firstOrNull()
        note = if (list.isEmpty()) err.ifBlank { "这台机器没回洗法" } else ""
        loading = false
    }

    // 订单详情轮询：3 秒一次，只在对话框开着时跑
    LaunchedEffect(orderId, session?.token) {
        val id = orderId
        val s = session ?: return@LaunchedEffect
        if (id.isBlank()) return@LaunchedEffect
        while (true) {
            val (d, err) = Ujing.orderDetail(s, id)
            if (d != null) {
                detail = d
                CampusStore.setUjingOrderNow(d)
                // 剩余时间偏差大于 5 秒才校准，否则秒数会来回跳
                if (d.remainSeconds > 0) {
                    val fresh = System.currentTimeMillis() + d.remainSeconds * 1000L
                    if (kotlin.math.abs(fresh - endAt) > 5_000L) endAt = fresh
                } else if (d.ended) {
                    endAt = 0L
                } else if (d.status == Ujing.OrderStatus.BOOKED &&
                    endAt <= System.currentTimeMillis()
                ) {
                    // 刚建好的单服务端还没给剩余时间，先按 3 分钟本地倒数
                    endAt = System.currentTimeMillis() + 180_000L
                }
                if (d.ended) break
            } else if (err.isNotBlank()) {
                note = err
            }
            delay(3_000L)
        }
    }

    fun reserve() {
        val s = session ?: return
        val p = selected ?: return
        creating = true
        note = ""
        scope.launch {
            val (order, err) = Ujing.createOrder(s, full, p.id)
            creating = false
            if (order == null) {
                note = err.ifBlank { "预约失败" }
                return@launch
            }
            orderId = order.orderId
            CampusStore.setUjingOrderId(order.orderId)
            // 未付款的单 3 分钟就被服务端撤掉，这个倒计时值得挂在岛上
            IslandHub.washWaitPay(context, "$label · ${p.name}")
            onReserved()
        }
    }

    fun act(kind: String) {
        val s = session ?: return
        val id = orderId
        if (id.isBlank() || acting.isNotEmpty()) return
        acting = kind
        note = ""
        scope.launch {
            val (ok, err) = when (kind) {
                "start" -> Ujing.startWash(s, id)
                "selfClean" -> Ujing.startSelfClean(s, id)
                "stop" -> Ujing.stopWash(s, id)
                else -> false to ""
            }
            acting = ""
            if (!ok) {
                note = err.ifBlank { "指令没发出去，再点一次试试" }
                return@launch
            }
            Toast.makeText(
                context,
                when (kind) {
                    "start" -> "已下发启动，等机器转起来"
                    "selfClean" -> "已下发筒自洁"
                    else -> "已停止"
                },
                Toast.LENGTH_SHORT,
            ).show()
            // 立刻看一次结果，别等下一轮轮询
            val (d, _) = Ujing.orderDetail(s, id)
            if (d != null) {
                detail = d
                CampusStore.setUjingOrderNow(d)
            }
            onReserved()
        }
    }

    fun cancel() {
        val s = session ?: return
        val id = orderId
        if (id.isBlank()) return
        acting = "cancel"
        note = ""
        scope.launch {
            val (ok, err) = Ujing.cancelOrder(s, id)
            acting = ""
            if (ok) {
                IslandHub.dismissWash(context)
                CampusStore.setUjingOrderId("")
                CampusStore.setUjingOrderNow(null)
                Toast.makeText(context, "已撤销，机器放回去了", Toast.LENGTH_SHORT).show()
                onReserved()
                onDismiss()
            } else {
                note = err.ifBlank { "取消失败" }
            }
        }
    }

    val d = detail
    val status = d?.status ?: -1
    val remain = if (endAt > 0L) ((endAt - now) / 1000).coerceAtLeast(0L) else 0L
    val live = d != null && orderId.isNotBlank()

    OverlayDialog(show = true, title = "预约洗衣", onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------- 顶栏：机器名 + 当前状态 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                val (pill, pillColor) = when {
                    status == Ujing.OrderStatus.BOOKED -> "待支付" to ColorWarn
                    status == Ujing.OrderStatus.PAID -> "已支付" to ColorWarn
                    status == Ujing.OrderStatus.SELF_CLEAN_START ||
                        status == Ujing.OrderStatus.SELF_CLEANING -> "自洁中" to ColorSelfClean
                    status == Ujing.OrderStatus.SELF_CLEAN_DONE -> "待洗衣" to Color(0xFF00875A)
                    status == Ujing.OrderStatus.RUNNING ||
                        status == Ujing.OrderStatus.LIQUID ||
                        status == Ujing.OrderStatus.STARTING -> "洗涤中" to ColorRunning
                    d != null && d.ended -> (d.statusText.ifBlank { "已结束" }) to
                        Color(0xFF8A8A8A)
                    live -> (d?.statusText ?: "进行中") to ColorWarn
                    device.idle -> "空闲" to MiuixTheme.colorScheme.primary
                    else -> "使用中" to Color(0xFF8A8A8A)
                }
                Pill(
                    text = pill,
                    color = pillColor,
                    background = pillColor.copy(alpha = 0.12f),
                )
            }

            if (live) {
                val money = d?.amount?.takeIf { it.isNotBlank() }
                Text(
                    text = "订单 $orderId" + (if (money != null) " · ¥$money" else ""),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                d?.label?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                }

                // ---------- 剩余时间 ----------
                if (remain > 0L) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = mmss(remain),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Medium,
                            color = when {
                                status == Ujing.OrderStatus.RUNNING -> ColorRunning
                                status == Ujing.OrderStatus.SELF_CLEANING ||
                                    status == Ujing.OrderStatus.SELF_CLEAN_START -> ColorSelfClean
                                remain <= 60L -> ColorDanger
                                else -> ColorWarn
                            },
                        )
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        Text(
                            text = when {
                                status == Ujing.OrderStatus.RUNNING -> "后洗完"
                                status == Ujing.OrderStatus.SELF_CLEANING -> "后自洁完成"
                                status == Ujing.OrderStatus.SELF_CLEAN_DONE -> "内要启动洗衣"
                                status == Ujing.OrderStatus.BOOKED -> "后订单自动取消"
                                else -> ""
                            },
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                }

                // ---------- 各阶段的说明 + 按钮 ----------
                when {
                    status == Ujing.OrderStatus.BOOKED -> {
                        Text(
                            text = "机器现在归你占用。去 U净 小程序付掉这一单，" +
                                "回来这里点「开始洗衣」；不打算洗就撤销，别把机器空占着。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                text = "复制订单号",
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("orderId", orderId))
                                    Toast.makeText(context, "订单号已复制", Toast.LENGTH_SHORT).show()
                                },
                            )
                            TextButton(
                                text = if (acting == "cancel") "撤销中…" else "撤销预约",
                                onClick = { if (acting.isEmpty()) cancel() },
                            )
                        }
                    }

                    status == Ujing.OrderStatus.PAID || status == Ujing.OrderStatus.PROTECT -> {
                        Text(
                            text = "钱付好了，机器还等着启动。想先去去味儿就点筒自洁，" +
                                "否则直接开始洗衣。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                text = if (acting == "selfClean") "下发中…" else "开始桶自洁",
                                onClick = { act("selfClean") },
                            )
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { act("start") },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text(
                                    text = if (acting == "start") "启动中…" else "开始洗衣",
                                    color = MiuixTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Text(
                            text = "筒自洁：先在机器面板上按【筒自洁】，再点这里。清洁 3 分钟，" +
                                "期间别放衣服。",
                            fontSize = 10.sp,
                            color = ColorWarn,
                        )
                    }

                    status == Ujing.OrderStatus.SELF_CLEAN_START ||
                        status == Ujing.OrderStatus.SELF_CLEANING -> {
                        Text(
                            text = "筒自洁进行中，别放进衣服。洗完自洁后 5 分钟内必须启动洗衣，" +
                                "否则订单会被自动取消。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }

                    status == Ujing.OrderStatus.SELF_CLEAN_DONE -> {
                        Text(
                            text = "自洁完了，现在可以放衣服了。关好门、倒好洗衣液再点「开始洗衣」——" +
                                "5 分钟内不启动，订单就作废了。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { act("start") },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text(
                                    text = if (acting == "start") "启动中…" else "开始洗衣",
                                    color = MiuixTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }

                    status == Ujing.OrderStatus.STARTING ||
                        status == Ujing.OrderStatus.LIQUID ||
                        status == Ujing.OrderStatus.RUNNING ||
                        status == Ujing.OrderStatus.FAULT -> {
                        Text(
                            text = "机器在转了。洗完手机会提醒你，记得及时取走衣服。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            // 停机不可逆（衣服还泡在水里，重洗得再付一次钱），点两下才真停
                            TextButton(
                                text = when {
                                    acting == "stop" -> "停止中…"
                                    confirmStop -> "再点一次确认停止"
                                    else -> "中途停止"
                                },
                                onClick = {
                                    if (acting.isEmpty()) {
                                        if (confirmStop) {
                                            confirmStop = false
                                            act("stop")
                                        } else {
                                            confirmStop = true
                                            scope.launch {
                                                delay(4_000L)
                                                confirmStop = false
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }

                    d != null && d.ended -> {
                        Text(
                            text = d.statusText.ifBlank { "订单已结束" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    else -> {
                        Text(
                            text = d?.statusText?.ifBlank { "正在读订单状态…" } ?: "正在读订单状态…",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
            } else {
                // ---------- 还没建单：选洗法 ----------
                Text("洗法", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (loading) {
                    Text(
                        text = "读取中…",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                programs.forEach { p ->
                    val active = p.id == selected?.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = p }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = p.name,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = listOfNotNull(
                                p.minutes.takeIf { it > 0 }?.let { "$it 分钟" },
                                p.price.takeIf { it.isNotBlank() }?.let { "¥$it" },
                            ).joinToString(" · "),
                            fontSize = 11.sp,
                            color = if (active) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
                if (programs.isNotEmpty()) {
                    val p = selected
                    Text(
                        text = listOfNotNull(
                            "预约只占机器，不扣钱",
                            p?.price?.takeIf { it.isNotBlank() }?.let { "洗一次 ¥$it" },
                        ).joinToString(" · "),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                    Text(
                        text = "建单后约 3 分钟内付款有效，超时系统自动撤单；不想洗可以马上撤销。",
                        fontSize = 11.sp,
                        color = ColorWarn,
                    )
                }
            }

            if (note.isNotBlank()) {
                Text(
                    note,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                if (live) {
                    TextButton(text = "关闭", onClick = onDismiss)
                } else {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    Button(
                        onClick = { if (!creating && selected != null && device.idle) reserve() },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = when {
                                creating -> "预约中…"
                                !device.idle -> "机器在用"
                                else -> "预约这台"
                            },
                            color = MiuixTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 秒数转 mm:ss，超过一小时给 h:mm:ss（深度自洁 10 分钟用不到，但别显示成 66:00）。 */
private fun mmss(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) {
        "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    } else {
        "%02d:%02d".format(s / 60, s % 60)
    }
}
