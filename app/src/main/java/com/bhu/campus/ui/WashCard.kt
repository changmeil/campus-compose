package com.bhu.campus.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.Ujing
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 洗衣房卡片（余额页用）。
 *
 * 只做两件事：告诉你哪台空着、点一下把机器约下来。
 * 付款不在 App 里做 —— 建单只占位，钱去 U净 小程序付。
 */
@Composable
fun WashRoomCard(
    busy: Boolean,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onManage: () -> Unit,
    onWash: () -> Unit,
    onReserve: (Ujing.DeviceStatus) -> Unit,
) {
    val ujing = CampusStore.ujing
    val devices = CampusStore.ujingDevices
    val err = CampusStore.ujingError

    Card(insideMargin = CardPadding) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (ujing.loggedIn) "U净 · ${ujing.mobile}" else "U净 自助洗衣",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = when {
                            !ujing.loggedIn -> "登录后能看到每台机器空不空"
                            ujing.bindings.isEmpty() -> "还没加机器，去机器前扫一次码"
                            busy -> "正在查设备状态…"
                            err.isNotBlank() -> err
                            devices.isEmpty() -> "共 ${ujing.bindings.size} 台"
                            else -> "${devices.count { it.idle }} 台空闲 / 共 ${devices.size} 台"
                        },
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (!ujing.loggedIn) {
                    TextButton(text = "登录", onClick = onLogin)
                } else {
                    TextButton(text = if (busy) "查询中" else "刷新", onClick = {
                        if (!busy) onRefresh()
                    })
                    TextButton(text = "管理", onClick = onManage)
                }
            }

            if (ujing.loggedIn && devices.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // 正在跟的那张单：卡片上直接显示进度，不用点进去看
                val order = CampusStore.ujingOrder
                val myOrder = order?.takeIf { ujing.orderId.isNotBlank() }
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    val label = ujing.bindings
                        .firstOrNull { it.qrCode == device.qrCode }?.label
                        .orEmpty()
                        .ifBlank { Ujing.deviceCode(device.qrCode) }
                    // 订单是否落在这台机器上（按机型+机号对不上就退化成「有单即算」）
                    val mine = device.orderId.isNotBlank() ||
                        (myOrder != null && myOrder.label.isNotBlank() &&
                            device.name.isNotBlank() &&
                            myOrder.label.contains(device.name, ignoreCase = true))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        when {
                            mine && myOrder != null -> {
                                val st = myOrder.status
                                val (text, color) = when {
                                    st == Ujing.OrderStatus.BOOKED -> "待支付" to Color(0xFFB26A00)
                                    st == Ujing.OrderStatus.PAID -> "待启动" to Color(0xFFB26A00)
                                    st == Ujing.OrderStatus.SELF_CLEANING ||
                                        st == Ujing.OrderStatus.SELF_CLEAN_START ->
                                        "自洁中" to Color(0xFF6A4BB5)
                                    st == Ujing.OrderStatus.SELF_CLEAN_DONE ->
                                        "待洗衣" to Color(0xFF00875A)
                                    myOrder.remainSeconds > 0 ->
                                        "剩 ${myOrder.remainSeconds / 60} 分" to Color(0xFF0066CC)
                                    else -> (myOrder.statusText.ifBlank { "洗涤中" }) to Color(0xFF0066CC)
                                }
                                Pill(text = text, color = color, background = color.copy(alpha = 0.12f))
                                TextButton(
                                    // 已支付还没启动的单，按钮直接写明白该干嘛
                                    text = if (st == Ujing.OrderStatus.PAID) "去启动" else "查看",
                                    onClick = { onReserve(device) },
                                )
                            }

                            device.orderId.isNotBlank() -> {
                                // 有单但还没查到详情
                                Pill(
                                    text = "已预约",
                                    color = Color(0xFFB26A00),
                                    background = Color(0xFFB26A00).copy(alpha = 0.12f),
                                )
                                TextButton(text = "查看", onClick = { onReserve(device) })
                            }

                            device.idle -> {
                                Pill(
                                    text = "空闲",
                                    color = MiuixTheme.colorScheme.primary,
                                    background = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                                )
                                TextButton(text = "预约", onClick = { onReserve(device) })
                            }

                            else -> {
                                Pill(
                                    text = if (device.remainMinutes > 0) "剩 ${device.remainMinutes} 分" else "使用中",
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    background = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.10f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(text = "我的洗衣 · 记录", onClick = onWash)
                }
            }
        }
    }
}
