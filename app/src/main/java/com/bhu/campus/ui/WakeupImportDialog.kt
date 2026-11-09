package com.bhu.campus.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.Course
import com.bhu.campus.data.WakeupImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.LocalTime

/**
 * 导入 WakeUp 课表的对话框。
 *
 * 两条输入通道：选文件（WakeUp 导出的 .ics / .json）或者直接粘贴文本。
 * WakeUp 的「分享码」是加密的，解不开，所以界面上直接把路径指到「导出为 ICS」。
 */
@Composable
fun WakeupImportDialog(
    onDismiss: () -> Unit,
    onImport: (
        courses: List<Course>,
        firstMonday: Long?,
        sectionTimes: List<Pair<LocalTime, LocalTime>>,
        replace: Boolean,
    ) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val existing = CampusStore.courses.size

    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<WakeupImport.Parsed?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readText(context, uri) } }
                .onSuccess {
                    text = it
                    parsed = runCatching { WakeupImport.parse(it, CampusStore.settings.totalWeeks) }
                        .onFailure { e -> error = e.message ?: "解析失败" }
                        .getOrNull()
                }
                .onFailure { error = "读文件失败：${it.message}" }
            busy = false
        }
    }

    fun doParse() {
        error = null
        parsed = runCatching { WakeupImport.parse(text, CampusStore.settings.totalWeeks) }
            .onFailure { e -> error = e.message ?: "解析失败" }
            .getOrNull()
    }

    OverlayDialog(
        show = true,
        title = "导入课表",
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
                text = "在 WakeUp 里点右上角「⋯」→ 导出 → 导出为 ICS，把文件存到手机后选进来；" +
                    "也可以把 ICS / JSON 内容直接粘在下面。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )

            TextField(
                value = text,
                onValueChange = {
                    text = it
                    parsed = null
                    error = null
                },
                label = "ICS 或 JSON 内容",
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 10,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = if (busy) "读取中…" else "选择文件",
                    onClick = {
                        if (!busy) picker.launch(arrayOf("*/*"))
                    },
                )
                TextButton(
                    text = "粘贴",
                    onClick = {
                        val clip = clipboard.getText()?.text.orEmpty()
                        if (clip.isBlank()) {
                            error = "剪贴板是空的"
                        } else {
                            text = clip
                            doParse()
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                if (text.isNotBlank()) {
                    TextButton(text = "解析", onClick = { doParse() })
                }
            }

            error?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            parsed?.let { result ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "识别到 ${result.courses.size} 门课，" +
                            "共 ${result.courses.sumOf { it.sectionCount }} 节（${result.kind}）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    result.firstMonday?.let { day ->
                        val monday = LocalDate.ofEpochDay(day)
                        Text(
                            text = "第 1 周周一：$monday（导入后会把学期起始日改到这里）",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                    result.notes.forEach { note ->
                        Text(
                            text = "· $note",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                    Text(
                        text = result.courses.take(6).joinToString("、") { it.name } +
                            if (result.courses.size > 6) " 等" else "",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = "取消", onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                val ready = parsed
                if (ready != null && existing > 0) {
                    TextButton(
                        text = "追加",
                        onClick = {
                            onImport(ready.courses, ready.firstMonday, ready.sectionTimes, false)
                        },
                    )
                    Button(
                        onClick = {
                            onImport(ready.courses, ready.firstMonday, ready.sectionTimes, true)
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("覆盖全部", color = MiuixTheme.colorScheme.onPrimary)
                    }
                } else {
                    Button(
                        onClick = {
                            if (ready != null) {
                                onImport(
                                    ready.courses,
                                    ready.firstMonday,
                                    ready.sectionTimes,
                                    existing > 0,
                                )
                            } else {
                                doParse()
                            }
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(if (ready == null) "解析" else "导入", color = MiuixTheme.colorScheme.onPrimary)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** ICS 一般是 UTF-8，但有些教务导出的中文文件是 GBK，读出来是乱码时再换一次。 */
private fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("读不到这个文件")
    val utf8 = String(bytes, Charsets.UTF_8)
    return if (utf8.contains('\uFFFD')) String(bytes, charset("GBK")) else utf8
}
