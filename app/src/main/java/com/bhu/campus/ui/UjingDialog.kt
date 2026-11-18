package com.bhu.campus.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.Ujing
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * U净 登录：手机号 → 短信验证码 → token。
 *
 * 和慧生活那套不同，这边没有图形码，一步短信就能换到 token。
 */
@Composable
fun UjingLoginDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var phone by remember { mutableStateOf(CampusStore.ujing.mobile) }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun sendCode() {
        val mobile = phone.trim()
        if (mobile.length != 11) {
            note = "手机号得是 11 位"
            return
        }
        busy = true
        note = ""
        scope.launch {
            val (ok, err) = Ujing.sendCode(mobile)
            busy = false
            if (ok) {
                sent = true
                note = "验证码已发到 $mobile"
            } else {
                note = if (err.isBlank()) "没发出去，稍后再试" else err
            }
        }
    }

    fun submit() {
        val mobile = phone.trim()
        if (mobile.length != 11) {
            note = "手机号得是 11 位"
            return
        }
        if (code.isBlank()) {
            note = "填一下收到的验证码"
            return
        }
        busy = true
        note = ""
        scope.launch {
            val (session, err) = Ujing.login(mobile, code.trim())
            busy = false
            if (session != null) {
                CampusStore.updateUjing { it.copy(mobile = session.mobile, token = session.token) }
                Toast.makeText(context, "已登录 U净", Toast.LENGTH_SHORT).show()
                onSuccess()
            } else {
                note = if (err.isBlank()) "登录失败" else err
            }
        }
    }

    OverlayDialog(
        show = true,
        title = "登录 U净",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "用 U净 App 里那个手机号登录，验证码发到这个号码上。token 只存在手机本地，不上传。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )

            TextField(
                value = phone,
                onValueChange = { if (it.length <= 11) phone = it.filter { c -> c.isDigit() } },
                label = "手机号",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it.filter { c -> c.isDigit() } },
                    label = "短信验证码",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = if (sent) "重新获取" else "获取验证码",
                    onClick = { if (!busy) sendCode() },
                )
            }

            if (note.isNotBlank()) {
                Text(
                    text = note,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(text = "取消", onClick = onDismiss)
                Button(
                    onClick = { if (!busy) submit() },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        text = if (busy) "处理中…" else "登录",
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 添加一台洗衣机。
 *
 * 机器上贴的二维码内容是 `https://q.ujing.com.cn/ucqrc/index.html?cd=...`，
 * 用户可以整个粘进来，也可以只填后面那串 cd 码；扫出来是纯数字时也认。
 */
@Composable
fun UjingAddDeviceDialog(
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit,
) {
    var qr by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val context = LocalContext.current

    fun add() {
        val full = Ujing.normalizeQr(qr)
        if (full.isBlank()) {
            note = "把机器二维码里的链接或 cd 码填进来"
            return
        }
        val code = Ujing.deviceCode(full)
        CampusStore.addUjingBinding(full, label.trim().ifBlank { code })
        Toast.makeText(context, "已添加洗衣机 $code", Toast.LENGTH_SHORT).show()
        onAdded(full)
    }

    OverlayDialog(
        show = true,
        title = "添加洗衣机",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "扫码后把链接复制过来，或者只填 cd 后面那串数字。加一次就行，以后打开 App 自动查状态。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            TextField(
                value = qr,
                onValueChange = { qr = it },
                label = "二维码链接 / cd 码",
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = label,
                onValueChange = { if (it.length <= 12) label = it },
                label = "给它起个名字（可选，如 3楼左）",
                modifier = Modifier.fillMaxWidth(),
            )
            if (note.isNotBlank()) {
                Text(text = note, fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(text = "取消", onClick = onDismiss)
                Button(
                    onClick = { add() },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("添加", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 洗衣机管理页：列出已绑定的机器，可以删。 */
@Composable
fun UjingManageDialog(
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onLogout: () -> Unit,
    onOpenWash: () -> Unit = {},
) {
    val ujing = CampusStore.ujing
    val context = LocalContext.current

    OverlayDialog(
        show = true,
        title = "洗衣房设置",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (ujing.loggedIn) "账号：${ujing.mobile}" else "还没登录 U净",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (ujing.bindings.isEmpty()) {
                Text(
                    text = "还没加洗衣机。走到机器前面扫一下码，把链接粘进来就行。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            } else {
                ujing.bindings.forEach { b ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = b.label.ifBlank { Ujing.deviceCode(b.qrCode) },
                                fontSize = 13.sp,
                            )
                            Text(
                                text = Ujing.deviceCode(b.qrCode),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                        TextButton(
                            text = "移除",
                            onClick = {
                                CampusStore.removeUjingBinding(b.qrCode)
                                Toast.makeText(context, "已移除", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ujing.loggedIn) {
                    TextButton(text = "洗衣记录", onClick = onOpenWash)
                    TextButton(text = "退出登录", onClick = onLogout)
                }
                Spacer(Modifier.weight(1f))
                TextButton(text = "关闭", onClick = onDismiss)
                Button(
                    onClick = onAdd,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("添加机器", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 我的洗衣：余额、正在洗的机器、最近的洗衣记录。
 *
 * 三块数据都是打开时现拉的，不缓存 —— 状态变化快，存下来反而误导。
 * 订单字段名在不同版本的服务端里飘过（deviceName / virtualName / machineName），
 * 数据层已经按候选键宽松解析，这里只负责排版。
 */
@Composable
fun UjingWashDialog(onDismiss: () -> Unit) {
    val state = CampusStore.ujing
    val session = if (state.loggedIn) Ujing.Session(state.mobile, state.token) else null
    var loading by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<List<Ujing.WashOrder>>(emptyList()) }
    var history by remember { mutableStateOf<List<Ujing.WashOrder>>(emptyList()) }
    var programs by remember { mutableStateOf<List<Ujing.WashProgram>>(emptyList()) }
    var hideBalance by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(session) {
        if (session == null) {
            note = "还没登录 U净"
            return@LaunchedEffect
        }
        loading = true
        note = ""
        val (b, bErr) = Ujing.balance(session)
        balance = b
        // 余额接口要 userId，服务端没给就整行藏起来，别留个「—」占地方
        hideBalance = b.isBlank() && bErr.isNotBlank()
        val (run, runErr) = Ujing.runningOrders(session)
        running = run
        if (runErr.isNotBlank()) note = runErr
        val (hist, histErr) = Ujing.orderHistory(session)
        history = hist
        val deviceId = CampusStore.ujingDevices.firstOrNull()?.deviceId.orEmpty()
        if (deviceId.isNotBlank()) programs = Ujing.programs(session, deviceId).first
        if (note.isBlank() && histErr.isNotBlank()) note = histErr
        loading = false
    }

    OverlayDialog(show = true, title = "我的洗衣", onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!hideBalance) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("余额", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = when {
                            loading && balance.isBlank() -> "…"
                            balance.isBlank() -> "—"
                            else -> "¥$balance"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (programs.isNotEmpty()) {
                Text("洗涤模式", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                programs.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            text = listOfNotNull(
                                p.price.takeIf { it.isNotBlank() }?.let { "¥$it" },
                                p.minutes.takeIf { it > 0 }?.let { "$it 分钟" },
                            ).joinToString(" · "),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
            }

            Text("正在洗", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (running.isEmpty()) {
                Text(
                    text = if (loading) "查询中…" else "现在没有在洗的机器",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            } else {
                running.forEach { order ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(order.deviceName.ifBlank { "洗衣机" }, fontSize = 13.sp)
                        Text(
                            text = listOfNotNull(
                                order.remainMinutes.takeIf { it > 0 }?.let { "还剩 $it 分钟" },
                                order.statusText.takeIf { it.isNotBlank() && it != "0" },
                                order.program.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifBlank { "进行中" },
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
            }

            Text("最近记录", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (history.isEmpty()) {
                Text(
                    text = if (loading) "查询中…" else "还没查到洗衣记录",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            } else {
                history.take(8).forEach { order ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = order.deviceName.ifBlank { order.storeName.ifBlank { "洗衣机" } },
                                fontSize = 12.sp,
                            )
                            Text(
                                text = listOfNotNull(
                                    order.created.takeIf { it.isNotBlank() }?.let(::prettyTime),
                                    order.statusText.takeIf { it.isNotBlank() && it != "0" },
                                ).joinToString(" · "),
                                fontSize = 10.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                        if (order.amount.isNotBlank()) {
                            Text(
                                text = "¥${order.amount}",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                    }
                }
            }

            if (note.isNotBlank()) {
                Text(note, fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
            }

            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text("知道了", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}


/** 服务端给的是 ISO8601（`2026-11-06T12:17:25Z`，UTC），转成本地「11-06 20:17」。 */
private fun prettyTime(raw: String): String = runCatching {
    val instant = java.time.Instant.parse(raw)
    java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrElse { raw.replace('T', ' ').take(16) }
