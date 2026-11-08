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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bhu.campus.data.AlipayMiniApp
import com.bhu.campus.data.arrangeOrder
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.DrinkBalance
import com.bhu.campus.data.EnergySnapshot
import com.bhu.campus.data.EnergySync
import com.bhu.campus.data.formatDateTimeShort
import com.bhu.campus.ui.AlipayActionsDialog
import com.bhu.campus.ui.BathCard
import com.bhu.campus.ui.CardPadding
import com.bhu.campus.ui.LabeledValue
import com.bhu.campus.ui.Pill
import com.bhu.campus.ui.ReorderableColumn
import com.bhu.campus.ui.WaterPresetRow
import com.bhu.campus.ui.rememberAlipayEntry

/**
 * 余额页：宿舍电费 + 支付宝打水 + 洗浴热水 + 手机话费。
 *
 * 电费和洗浴统一成同一种卡片：标题行里挂「5 号楼 5311 / 卡号 43266」这种小标签（点一下改），
 * 右边一个「刷新」，下面是余额大字和明细。原来顶部那两个输入框 + 「查询余额」按钮已经去掉，
 * 免得同一件事有两种操作方式。
 */
@Composable
fun EnergyScreen(
    contentPadding: PaddingValues,
    onMessage: (String) -> Unit,
) {
    val settings = CampusStore.settings
    val snapshot = CampusStore.energy

    var building by remember { mutableStateOf(settings.building) }
    var room by remember { mutableStateOf(settings.room) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInput by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showAlipay by remember { mutableStateOf(false) }
    var showDrinkHelp by remember { mutableStateOf(false) }
    val alipayEntry = rememberAlipayEntry(onMessage)
    val context = LocalContext.current
    val drinkBalance = CampusStore.drinkBalance
    val drinkBalanceAt = CampusStore.drinkBalanceAt

    val configured = building.isNotBlank() && room.isNotBlank()
    val order = arrangeOrder(settings.energyOrder, listOf("energy", "water", "bath"))

    fun refresh() {
        if (loading) return
        if (!configured) {
            showInput = true
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching { EnergySync.refresh(building, room) }
                .onSuccess { result ->
                    onMessage("余额 ¥" + String.format("%.2f", result.balance))
                }
                .onFailure { error = it.message ?: "查询失败" }
            loading = false
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
            ReorderableColumn(
                keys = order,
                onOrderChange = { next -> CampusStore.updateSettings { it.copy(energyOrder = next) } },
            ) { key ->
            when (key) {
            "energy" -> Card(insideMargin = CardPadding) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "宿舍电费",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (configured) "$building 号楼 $room" else "未填楼号房间",
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showInput = true }
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            text = if (loading) "查询中…" else "刷新",
                            onClick = { refresh() },
                        )
                    }

                    if (!configured) {
                        Text(
                            text = "填一次楼号和房间号，以后就能在这里看宿舍电费余额。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { showInput = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(
                                text = "填写楼号房间号",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onPrimary,
                            )
                        }
                    } else if (snapshot == null) {
                        Text(
                            text = "还没有查询过，点右上角「刷新」拉一次。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        EnergyBody(snapshot)
                    }

                    if (error != null) {
                        Text(
                            text = error ?: "",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            "water" -> Card(insideMargin = CardPadding) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "支付宝 · 打水",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (drinkBalance == null) "未读取" else "社慧通",
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            text = "读取",
                            onClick = {
                                if (!DrinkBalance.isEnabled(context)) {
                                    showDrinkHelp = true
                                } else if (!AlipayMiniApp.openShetong(context)) {
                                    onMessage("没能打开支付宝，请确认已安装")
                                }
                            },
                        )
                    }

                    if (drinkBalance == null) {
                        Text(
                            text = "点右上「读取」打开社慧通小程序，余额会自己记下来。",
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
                                text = String.format("%.2f", drinkBalance),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = formatDateTimeShort(drinkBalanceAt) + " 读取",
                                fontSize = 10.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                    }

                    Text(
                        text = if (alipayEntry.presets.isEmpty()) {
                            "拍一次设备上的二维码，以后一点直达"
                        } else {
                            "已存 ${alipayEntry.presets.size} 台设备，点一下直接进打水页"
                        },
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    WaterPresetRow(
                        entry = alipayEntry,
                        onManage = { showAlipay = true },
                    )
                }
            }

            "bath" -> BathCard(onMessage = onMessage)
                }
            }

            Text(
                text = "电费、洗浴来自学校平台；只在点查询时访问，结果只存在本机。",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (showInput) {
            EnergyRoomDialog(
                currentBuilding = building,
                currentRoom = room,
                onDismiss = { showInput = false },
                onSave = { b, r ->
                    building = b
                    room = r
                    CampusStore.updateSettings { it.copy(building = b, room = r) }
                    showInput = false
                    if (b.isNotBlank() && r.isNotBlank()) {
                        loading = true
                        error = null
                        scope.launch {
                            runCatching { EnergySync.refresh(b, r) }
                                .onSuccess {
                                    onMessage("余额 ¥" + String.format("%.2f", it.balance))
                                }
                                .onFailure { error = it.message ?: "查询失败" }
                            loading = false
                        }
                    }
                },
            )
        }

        if (showAlipay) {
            AlipayActionsDialog(entry = alipayEntry, onDismiss = { showAlipay = false })
        }

        if (showDrinkHelp) {
            DrinkBalanceHelpDialog(
                onDismiss = { showDrinkHelp = false },
                onOpen = {
                    showDrinkHelp = false
                    if (!DrinkBalance.openSettings(context)) {
                        onMessage("打不开系统设置，请手动到「设置 → 无障碍」里开启")
                    }
                },
            )
        }
    }
}

@Composable
private fun DrinkBalanceHelpDialog(
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = "读取打水余额",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "打水余额在支付宝「社慧通」小程序里，没有对外的查询接口。所以只能这样读：" +
                    "小程序页面出现时，把屏幕上那个「余额 ￥36.85」记下来。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Text(
                text = "需要在系统设置里打开一次「无障碍 → 校园一站 · 打水余额」。它只在支付宝里读余额数字，" +
                    "不点击、不输入、不读其它页面，也不会把内容发到任何地方。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        text = "去设置",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnergyBody(snapshot: EnergySnapshot) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
        Text("¥", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 3.dp))
        Spacer(Modifier.width(2.dp))
        Text(
            text = String.format("%.2f", snapshot.balance),
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Pill(
            text = when {
                snapshot.balance <= 0 -> "已欠费"
                snapshot.balance < 20 -> "余额偏低"
                else -> "余额充足"
            },
            color = when {
                snapshot.balance <= 0 -> Color(0xFFF0433F)
                snapshot.balance < 20 -> Color(0xFFF0910C)
                else -> Color(0xFF34B45A)
            },
            background = when {
                snapshot.balance <= 0 -> Color(0x14F0433F)
                snapshot.balance < 20 -> Color(0x18F0910C)
                else -> Color(0x1834B45A)
            },
        )
    }

    Spacer(Modifier.height(10.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    LabeledValue(
        "房间",
        listOfNotNull(
            snapshot.areaName.takeIf { it.isNotBlank() },
            snapshot.buildingNo.takeIf { it.isNotBlank() }?.let { "$it 号楼" },
            snapshot.unitNo.takeIf { it.isNotBlank() }?.let { "$it 单元" },
            snapshot.roomNo.takeIf { it.isNotBlank() },
        ).joinToString(" ")
    )
    if (snapshot.userNo.isNotBlank()) LabeledValue("户号", snapshot.userNo)
    if (snapshot.meterNo.isNotBlank()) LabeledValue("表号", snapshot.meterNo)

    Spacer(Modifier.height(8.dp))
    Text(
        text = "缴费平台 · " + formatDateTimeShort(snapshot.fetchedAt) + " 更新",
        fontSize = 9.sp,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

/** 楼号 / 房间号，样式和洗浴的「校园卡号」弹窗保持一致。 */
@Composable
private fun EnergyRoomDialog(
    currentBuilding: String,
    currentRoom: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var b by remember { mutableStateOf(currentBuilding) }
    var r by remember { mutableStateOf(currentRoom) }

    OverlayDialog(
        show = true,
        title = "宿舍房间",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = b,
                    onValueChange = { b = it.trim().take(20) },
                    label = "楼号",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                TextField(
                    value = r,
                    onValueChange = { r = it.trim().take(20) },
                    label = "房间号",
                    modifier = Modifier.weight(1.4f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Text(
                text = "房间号填门上那个编号（如 5311），只存在本机，用来向学校缴费平台查余额。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onSave(b, r) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        text = "保存",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
