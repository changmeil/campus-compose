package com.bhu.campus.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bhu.campus.data.Life798
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 慧生活798 登录：手机号 → 图形验证码 → 短信验证码。
 *
 * 三步是服务端定的顺序：图形码要和短信码绑在同一个会话串 `s` 上，所以不能省中间那步。
 * 登录成功后 token 存在本地，之后查余额就不用再输。
 */
@Composable
fun Life798LoginDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var phone by remember { mutableStateOf(Life798.phone) }
    var graphCode by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var sessionKey by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(1) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadCaptcha() {
        if (phone.length != 11) {
            note = "手机号得是 11 位"
            return
        }
        busy = true
        note = null
        scope.launch {
            val key = Life798.newCaptchaKey()
            val bytes = Life798.captcha(key)
            if (bytes == null) {
                note = Life798.lastError ?: "图形码拉不下来"
            } else {
                sessionKey = key
                image = bytes
                graphCode = ""
                step = 2
            }
            busy = false
        }
    }

    fun submitGraph() {
        if (graphCode.isBlank()) {
            note = "先照着图片填验证码"
            return
        }
        busy = true
        note = null
        scope.launch {
            val err = Life798.sendCode(phone, graphCode.trim(), sessionKey)
            if (err == null) {
                step = 3
                note = "短信已发出，收到后填下面的框"
            } else {
                note = err
            }
            busy = false
        }
    }

    fun submitSms() {
        if (smsCode.isBlank()) {
            note = "填一下短信里的验证码"
            return
        }
        busy = true
        note = null
        scope.launch {
            val err = Life798.login(phone, smsCode.trim())
            busy = false
            if (err == null) {
                onSuccess()
            } else {
                note = err
            }
        }
    }

    OverlayDialog(
        show = true,
        title = "登录慧生活798",
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = phone,
                onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                label = "手机号",
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )

            when (step) {
                1 -> Text(
                    text = "用慧生活798 绑定的手机号。下一步会给这个号发短信。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )

                2 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MiuixTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = !busy) { loadCaptcha() }
                                .padding(2.dp),
                        ) {
                            val bytes = image
                            if (bytes == null) {
                                Text(
                                    text = "点这里重新拉取",
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(8.dp),
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                            } else {
                                val bmp = remember(bytes) {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        ?.asImageBitmap()
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "图形验证码",
                                        modifier = Modifier.height(38.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        TextField(
                            value = graphCode,
                            onValueChange = { graphCode = it.take(8) },
                            label = "图形码",
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        )
                    }
                    Text(
                        text = "看不清就点图片换一张。",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }

                else -> {
                    TextField(
                        value = smsCode,
                        onValueChange = { smsCode = it.filter(Char::isDigit).take(8) },
                        label = "短信验证码",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Text(
                        text = "收不到就返回上一步重新发一次（一分钟内别连点）。",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }

            note?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = if (it.startsWith("短信已发出")) {
                        MiuixTheme.colorScheme.onBackgroundVariant
                    } else {
                        MiuixTheme.colorScheme.error
                    },
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = when (step) {
                        1 -> "取消"
                        2 -> "上一步"
                        else -> "上一步"
                    },
                    onClick = {
                        if (busy) return@TextButton
                        when (step) {
                            1 -> onDismiss()
                            else -> {
                                step -= 1
                                note = null
                            }
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        when (step) {
                            1 -> loadCaptcha()
                            2 -> submitGraph()
                            else -> submitSms()
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = !busy,
                ) {
                    Text(
                        text = when {
                            busy -> "处理中…"
                            step == 1 -> "下一步"
                            step == 2 -> "发短信"
                            else -> "登录"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
