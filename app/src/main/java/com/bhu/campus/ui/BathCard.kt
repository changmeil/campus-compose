package com.bhu.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.IslandHub
import com.bhu.campus.data.WaterClass
import com.bhu.campus.data.WaterClient
import com.bhu.campus.data.WaterCost
import com.bhu.campus.data.WaterSnapshot
import com.bhu.campus.data.formatDateTimeShort

/**
 * 洗浴热水卡片（学校「智能控水」）。
 *
 * 网页版必须从企业微信工作台带 ticket 进来才能用，所以这里走 [WaterClient] 直接读接口：
 * 余额挂在卡上，各浴区的空闲台数和预约状态由服务端一次给全，还能在这儿直接预约拿预约码。
 */
@Composable
fun BathCard(onMessage: (String) -> Unit) {
    val card = CampusStore.waterCard
    val snapshot = CampusStore.bath
    val context = LocalContext.current

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInput by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<WaterClass?>(null) }
    var busyNo by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (loading) return
        if (card.isBlank()) {
            showInput = true
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching { WaterClient.snapshot(card) }
                .onSuccess { result ->
                    CampusStore.recordBath(result)
                    onMessage("洗浴余额 ¥" + String.format("%.2f", result.balance))
                }
                .onFailure { error = it.message ?: "查不到洗浴数据" }
            loading = false
        }
    }

    fun book(target: WaterClass) {
        if (busyNo != 0) return
        busyNo = target.no
        scope.launch {
            runCatching { WaterClient.book(card, target.no) }
                .onSuccess { r ->
                    if (r.ok) {
                        CampusStore.patchBathClass(target.no, true, r.code)
                        // 预约码要站着门口翻 App 找太麻烦，直接挂到岛上
                        if (r.code.isNotBlank()) {
                            IslandHub.bathCode(context, r.code, target.name)
                        }
                        onMessage(r.message.ifBlank { "预约码 " + r.code })
                    } else {
                        onMessage(r.message)
                    }
                }
                .onFailure { onMessage(it.message ?: "预约失败") }
            busyNo = 0
        }
    }

    fun cancelBook(target: WaterClass) {
        if (busyNo != 0) return
        busyNo = target.no
        scope.launch {
            runCatching { WaterClient.cancelBook(card, target.no) }
                .onSuccess { r ->
                    if (r.ok) {
                        CampusStore.patchBathClass(target.no, false, "")
                        IslandHub.dismiss(context)
                        onMessage("已取消预约")
                    } else {
                        onMessage(r.message)
                    }
                }
                .onFailure { onMessage(it.message ?: "取消失败") }
            busyNo = 0
        }
    }

    Card(insideMargin = CardPadding) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "洗浴热水",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (card.isBlank()) "未填卡号" else "卡号 $card",
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

            if (card.isBlank()) {
                Text(
                    text = "填一次校园卡号（一卡通号，不是学号），以后就能在这里看洗浴余额、看澡堂哪个区有空位，还能提前预约拿预约码。",
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
                        text = "填写校园卡号",
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
                SnapshotBody(
                    snapshot = snapshot,
                    busyNo = busyNo,
                    onBook = { pending = it },
                    onCancel = { cancelBook(it) },
                )
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

    if (showInput) {
        BathCardDialog(
            current = card,
            onDismiss = { showInput = false },
            onSave = { value ->
                CampusStore.saveWaterCard(value)
                showInput = false
                if (value.isNotBlank()) {
                    loading = true
                    error = null
                    scope.launch {
                        runCatching { WaterClient.snapshot(value) }
                            .onSuccess {
                                CampusStore.recordBath(it)
                                onMessage("洗浴余额 ¥" + String.format("%.2f", it.balance))
                            }
                            .onFailure { error = it.message ?: "查不到洗浴数据" }
                        loading = false
                    }
                }
            },
        )
    }

    pending?.let { target ->
        BookConfirmDialog(
            target = target,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                book(target)
            },
        )
    }
}

@Composable
private fun SnapshotBody(
    snapshot: WaterSnapshot,
    busyNo: Int,
    onBook: (WaterClass) -> Unit,
    onCancel: (WaterClass) -> Unit,
) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
        Text("¥", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 3.dp))
        Spacer(Modifier.width(2.dp))
        Text(
            text = String.format("%.2f", snapshot.balance),
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        if (snapshot.quota > 0) {
            Text(
                text = "定额 " + trimZero(snapshot.quota) + " 元/次",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }

    snapshot.booked?.let { booked ->
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "预约码 " + booked.bookCode,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = booked.name + " · 30 分钟内到控水器上输入",
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            TextButton(text = "取消", onClick = { onCancel(booked) })
        }
    }

    snapshot.last?.let { last ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = "上次 " + shortTime(last.time) + " · " + zoneShort(last.zone) +
                (if (last.posNo > 0) " ${last.posNo} 号" else "") +
                " · ¥" + String.format("%.2f", last.money),
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }

    if (snapshot.classes.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("浴区（空闲 / 总台数）", fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
        Spacer(Modifier.height(2.dp))
        snapshot.classes.forEach { zone ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = zone.name,
                        fontSize = 12.sp,
                        fontWeight = if (zone.booked) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = "空闲 ${zone.free}/${zone.posNum} · ${zone.freeRate}%" +
                            if (zone.bookRate > 0) " · 预约率 ${zone.bookRate}%" else "",
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                Text(
                    text = "${zone.free}/${zone.posNum}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        zone.free == 0 -> Color(0xFFF0433F)
                        zone.ratio <= 0.2f -> Color(0xFFF0910C)
                        else -> Color(0xFF34B45A)
                    },
                    modifier = Modifier.padding(end = 6.dp),
                )
                TextButton(
                    text = when {
                        zone.booked -> "取消"
                        busyNo == zone.no -> "处理中"
                        else -> "预约"
                    },
                    onClick = {
                        if (busyNo == 0) {
                            if (zone.booked) onCancel(zone) else onBook(zone)
                        }
                    },
                )
            }
        }
    }

    if (snapshot.records.size > 1) {
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))
        Text("最近扣费", fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
        snapshot.records.take(3).forEach { WaterCostRow(it) }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "水控平台 · " + formatDateTimeShort(snapshot.fetchedAt) + " 更新",
        fontSize = 9.sp,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

@Composable
private fun BookConfirmDialog(
    target: WaterClass,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = "预约 " + target.name,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "会占一个预约名额，码 30 分钟内有效。到浴室在控水器上输入这个码就能开阀，用不上随时可以取消。",
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
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        text = "预约",
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
private fun BathCardDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }

    OverlayDialog(
        show = true,
        title = "校园卡号",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(12) },
                label = "一卡通卡号",
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                text = "卡号只存在本机，用来向学校水控平台查余额、空位和预约，不会上传到别处。清空保存即可关闭这个功能。",
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
                    onClick = { onSave(text.trim()) },
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

/** "2026-11-07 14:11:00" → "11-07 14:11" */
private fun shortTime(raw: String): String {
    if (raw.length < 16) return raw
    return raw.substring(5, 16)
}

/** "一楼单-男浴" 就是它本身，别把「浴」截掉；只是去掉空串。 */
private fun zoneShort(raw: String): String = raw.trim()

private fun trimZero(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.2f", value)

/** 记录列表里用：一行一次洗澡。 */
@Composable
fun WaterCostRow(item: WaterCost, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(shortTime(item.time), fontSize = 12.sp)
            Text(
                text = item.zone + if (item.posNo > 0) " ${item.posNo} 号" else "",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
        Text(
            text = "-¥" + String.format("%.2f", item.money),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
