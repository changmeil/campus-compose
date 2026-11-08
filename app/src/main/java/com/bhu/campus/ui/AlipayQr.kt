package com.bhu.campus.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bhu.campus.data.AlipayMiniApp
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.QrDecoder
import com.bhu.campus.data.WaterQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 支付宝打水入口。
 *
 * 饮水机屏幕上是动态二维码，内容形如 `https://88wise.com/p?i=xxx&b=设备号`。
 * 只用 `startapp` 打开社慧通小程序只能到首页，没有任何设备可操作；
 * 把二维码内容当成「扫出来的结果」交给支付宝，才能进到那台机器的打水页。
 *
 * 每识别一次就记一台设备，最多 [com.bhu.campus.data.MAX_WATER_QR] 台，
 * 首页/电费页直接列出这些设备，点哪个进哪个。
 */
@Stable
class AlipayEntry(
    /** 已保存的设备，最新的在前。 */
    val presets: List<WaterQr>,
    private val openAction: (WaterQr) -> Boolean,
    private val removeAction: (WaterQr) -> Unit,
    private val renameAction: (WaterQr, String) -> Unit,
    private val pickAlbumAction: () -> Unit,
    private val scanAction: () -> Unit,
    private val homeAction: () -> Unit,
) {
    var busy by mutableStateOf(false)
        internal set

    fun pickAlbum() = pickAlbumAction()
    fun openScanner() = scanAction()
    fun openHome() = homeAction()

    /** 打开指定那台设备；返回 false 表示没唤起支付宝。 */
    fun open(qr: WaterQr): Boolean = openAction(qr)

    fun remove(qr: WaterQr) = removeAction(qr)
    fun rename(qr: WaterQr, label: String) = renameAction(qr, label)
}

@Composable
fun rememberAlipayEntry(onMessage: (String) -> Unit): AlipayEntry {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val presets = CampusStore.waterQrs

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadBitmap(context, uri) }
            val text = if (bitmap == null) null else QrDecoder.decode(bitmap)
            android.util.Log.d(
                "AlipayQr",
                "picked=$uri size=${bitmap?.width}x${bitmap?.height} text=$text",
            )
            busy = false
            when {
                text.isNullOrBlank() -> onMessage("没认出二维码，换一张或直接用支付宝扫一扫")
                else -> {
                    // 记住这台设备，下次直接在首页点它
                    val saved = CampusStore.saveWaterQr(text)
                    if (saved != null && !AlipayMiniApp.openQrCode(context, text)) {
                        onMessage("没能打开支付宝，请确认已安装")
                    }
                }
            }
        }
    }

    return remember(busy, presets) {
        AlipayEntry(
            presets = presets,
            openAction = { qr ->
                if (AlipayMiniApp.openQrCode(context, qr.content)) {
                    true
                } else {
                    onMessage("没能打开支付宝，请确认已安装")
                    false
                }
            },
            removeAction = { CampusStore.removeWaterQr(it) },
            renameAction = { qr, label -> CampusStore.renameWaterQr(qr, label) },
            pickAlbumAction = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            scanAction = {
                if (!AlipayMiniApp.openScanner(context)) {
                    onMessage("没能打开支付宝，请确认已安装")
                }
            },
            homeAction = {
                if (!AlipayMiniApp.openShetong(context)) {
                    onMessage("没能打开支付宝，请确认已安装")
                }
            },
        ).also { it.busy = busy }
    }
}

/** 相册图片按需降采样，避免 4000 万像素原图直接进内存。 */
private fun loadBitmap(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (longSide / sample > 3000) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    }
}.getOrNull()

/**
 * 首页/电费页里的设备快捷条：一台一个按钮，最右边「+」添新设备。
 * 只有一台时也照常显示，点了就是直达。
 */
@Composable
fun WaterPresetRow(
    entry: AlipayEntry,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entry.presets.forEach { qr ->
            PresetChip(
                text = qr.chipText(),
                // 每台设备长得一样，别用颜色区分「最新」，只有「管理」是中性色
                highlight = true,
                onClick = { entry.open(qr) },
            )
        }
        PresetChip(
            text = if (entry.presets.isEmpty()) "添加打水设备" else "管理",
            icon = true,
            highlight = false,
            onClick = onManage,
        )
    }
}

private fun WaterQr.chipText(): String =
    if (label.isNotBlank() && deviceNo.isNotBlank()) "$label · $deviceNo"
    else if (deviceNo.isNotBlank()) "设备 $deviceNo"
    else "打水"

@Composable
private fun PresetChip(
    text: String,
    highlight: Boolean,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (highlight) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    else MiuixTheme.colorScheme.secondaryContainer
    val fg = if (highlight) MiuixTheme.colorScheme.primary
    else MiuixTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
fun AlipayActionsDialog(
    entry: AlipayEntry,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<WaterQr?>(null) }
    var deleting by remember { mutableStateOf<WaterQr?>(null) }

    OverlayDialog(
        show = true,
        title = "支付宝 · 打水",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entry.busy) {
                Text(
                    text = "正在识别二维码…",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            if (entry.presets.isNotEmpty()) {
                Text(
                    text = "已保存 ${entry.presets.size} 台设备",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                entry.presets.forEach { qr ->
                    PresetItem(
                        qr = qr,
                        onOpen = {
                            onDismiss()
                            entry.open(qr)
                        },
                        onRename = { renaming = qr },
                        onDelete = { deleting = qr },
                    )
                }
            } else if (!entry.busy) {
                Text(
                    text = "还没有保存的设备。先用下面的「从相册选二维码」存一台，之后首页会直接列出来。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            ActionItem(
                title = "扫一扫 · 直接去接水",
                subtitle = "对着饮水机屏幕扫，这一次不存设备；想留下来用相册",
                onClick = {
                    onDismiss()
                    entry.openScanner()
                },
            )
            ActionItem(
                title = "从相册选二维码",
                subtitle = "识别出来会自动存成一台预设，下次一点直达",
                onClick = {
                    onDismiss()
                    entry.pickAlbum()
                },
            )
            ActionItem(
                title = "打开社慧通首页",
                subtitle = "缴费、洗浴、设备预约，不含具体设备",
                onClick = {
                    onDismiss()
                    entry.openHome()
                },
            )
        }
    }

    renaming?.let { qr ->
        RenamePresetDialog(
            qr = qr,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                entry.rename(qr, name)
                renaming = null
            },
        )
    }

    deleting?.let { qr ->
        OverlayDialog(
            show = true,
            title = "删除这台设备？",
            onDismissRequest = { deleting = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${qr.title} 会从首页移除，打水功能本身不受影响，随时可以重新拍码加回来。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(text = "取消", onClick = { deleting = null })
                    TextButton(text = "删除", onClick = {
                        entry.remove(qr)
                        deleting = null
                    })
                }
            }
        }
    }
}

@Composable
private fun RenamePresetDialog(
    qr: WaterQr,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(qr.label) }
    OverlayDialog(
        show = true,
        title = "给这台设备起个名",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = if (qr.deviceNo.isNotBlank()) "设备 ${qr.deviceNo}" else "设备名",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "比如「宿舍楼下」「四楼茶水间」，留空就显示设备号。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(text = "取消", onClick = onDismiss)
                TextButton(text = "保存", onClick = { onConfirm(name) })
            }
        }
    }
}

/** 已保存设备的一行：点开、改名、删除。 */
@Composable
private fun PresetItem(
    qr: WaterQr,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(insideMargin = CardPadding) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = qr.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (qr.deviceNo.isNotBlank()) "设备 ${qr.deviceNo} · 点一下直接进" else "点一下直接进",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(16.dp),
                )
            }
            IconButton(onClick = onRename, backgroundColor = MiuixTheme.colorScheme.secondaryContainer) {
                Icon(
                    imageVector = MiuixIcons.Edit,
                    contentDescription = "改名",
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onDelete, backgroundColor = MiuixTheme.colorScheme.secondaryContainer) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "删除",
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(insideMargin = CardPadding) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
