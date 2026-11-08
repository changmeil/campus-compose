package com.bhu.campus.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.Life798
import com.bhu.campus.data.formatDateTimeShort
import com.bhu.campus.ui.AlipayActionsDialog
import com.bhu.campus.ui.CardPadding
import com.bhu.campus.ui.LabeledValue
import com.bhu.campus.ui.Life798LoginDialog
import com.bhu.campus.ui.Pill
import com.bhu.campus.ui.WaterPresetRow
import com.bhu.campus.ui.rememberAlipayEntry
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 余额页：只剩打水（慧生活798）一块。
 *
 * 余额直接走慧生活798 的接口（见 [Life798]），不再开支付宝、不读屏。
 * 第一次要点「登录」，输手机号 + 图形码 + 短信码，之后 token 存在本地，点「刷新」就行。
 *
 * 底下留了一排打水设备的快捷入口——那是「去接水」用的，和查余额是两件事，
 * 设备仍然靠支付宝社慧通小程序打开（扫码进设备页这件事只有支付宝能做）。
 */
@Composable
fun EnergyScreen(
    contentPadding: PaddingValues,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val alipayEntry = rememberAlipayEntry(onMessage)

    var showAlipay by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val balance = Life798.balance
    val balanceAt = Life798.balanceAt
    val loggedIn = Life798.loggedIn

    fun refresh(silent: Boolean = false) {
        if (loading) return
        if (!Life798.loggedIn) {
            showLogin = true
            return
        }
        loading = true
        note = null
        scope.launch {
            val err = Life798.refreshWallet()
            loading = false
            if (err == null) {
                if (!silent) onMessage("钱包 ¥" + String.format("%.2f", Life798.balance ?: 0.0))
            } else {
                note = err
            }
        }
    }

    // 打过卡的机器，进来先自己刷一次；一分钟内不重复请求
    LaunchedEffect(Unit) {
        if (Life798.loggedIn && System.currentTimeMillis() - Life798.balanceAt > 60_000) {
            refresh(silent = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(insideMargin = CardPadding) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "打水 · 慧生活798",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (loggedIn) Life798.phone else "未登录",
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { if (!loggedIn) showLogin = true }
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            text = when {
                                loading -> "查询中…"
                                !loggedIn -> "登录"
                                else -> "刷新"
                            },
                            onClick = { if (loggedIn) refresh() else showLogin = true },
                        )
                    }

                    if (!loggedIn) {
                        Text(
                            text = "登录一次就能直接查余额，不用开支付宝。手机号、图形码、短信码三步。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { showLogin = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(
                                text = "登录慧生活798",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onPrimary,
                            )
                        }
                    } else if (balance == null) {
                        Text(
                            text = "点右上「刷新」拉一次余额。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                text = "¥",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = String.format("%.2f", balance),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            Pill(
                                text = when {
                                    balance < 10 -> "快没钱了"
                                    else -> "够用"
                                },
                                color = if (balance < 10) Color(0xFFF0910C) else Color(0xFF34B45A),
                                background = if (balance < 10) Color(0x18F0910C) else Color(0x1834B45A),
                            )
                        }

                        if (Life798.wallets.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Life798.wallets.forEach { w ->
                                LabeledValue(w.name, "¥" + String.format("%.2f", w.balance))
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "慧生活798 · " + formatDateTimeShort(balanceAt) + " 更新",
                            fontSize = 9.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }

                    note?.let {
                        Text(
                            text = it,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (loggedIn) {
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text(
                                text = "退出登录",
                                fontSize = 10.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier
                                    .clickable {
                                        Life798.logout()
                                        note = null
                                    }
                                    .padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            Card(insideMargin = CardPadding) {
                Column {
                    Text(
                        text = "去打水",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (alipayEntry.presets.isEmpty()) {
                            "拍一次设备上的二维码，以后一点直达"
                        } else {
                            "已存 ${alipayEntry.presets.size} 台设备，点一下直接进打水页"
                        },
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    WaterPresetRow(
                        entry = alipayEntry,
                        onManage = { showAlipay = true },
                    )
                }
            }

            Text(
                text = "余额来自慧生活798 接口，只在点刷新时访问；登录信息只存在本机。",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (showAlipay) {
            AlipayActionsDialog(entry = alipayEntry, onDismiss = { showAlipay = false })
        }

        if (showLogin) {
            Life798LoginDialog(
                onDismiss = { showLogin = false },
                onSuccess = {
                    showLogin = false
                    onMessage("登录成功，正在查余额")
                    refresh()
                },
            )
        }
    }
}
