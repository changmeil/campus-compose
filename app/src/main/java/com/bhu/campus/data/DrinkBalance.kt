package com.bhu.campus.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.bhu.campus.DrinkBalanceService

/**
 * 从支付宝「社慧通」小程序首页把打水余额读出来。
 *
 * 为什么读屏幕而不是调接口：余额在社慧通自己的账户里，接口要登录态（抓包 + root），
 * 但小程序首页本来就明明白白把「余额 ￥36.85」画在屏幕上，而且这些文字在无障碍树里
 * 是独立可读的文本节点，取一次比逆向接口稳得多。
 *
 * 只认社慧通首页那一种排版，避免把账单里的「￥0.3」当成余额：
 * 1. 同一屏必须出现「含补贴」（社慧通余额行的固定后缀，别的页面没有）
 * 2. 找到 text 完全等于「余额」的节点
 * 3. 取它右边、垂直居中最接近的那个 `￥数字` 节点
 */
object DrinkBalance {

    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 社慧通首页余额行那个「含补贴￥0」后缀，用来确认当前确实是在社慧通首页。 */
    private const val ANCHOR = "含补贴"

    private val AMOUNT = Regex("""^[￥¥]\s*(\d{1,6})(?:\.(\d{1,2}))?$""")
    private val INLINE = Regex("""余额[：:\s]*[￥¥]\s*(\d{1,6})(?:\.(\d{1,2}))?""")

    private const val MAX_NODES = 600

    private class Item(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cy: Int get() = (top + bottom) / 2
        val height: Int get() = bottom - top
    }

    /**
     * 从当前窗口解出余额；返回 null 表示这一屏不是社慧通首页（或还没加载出来）。
     */
    fun parse(root: AccessibilityNodeInfo?): Double? {
        if (root == null) return null
        val items = collect(root)
        if (items.isEmpty()) return null
        if (items.none { it.text.contains(ANCHOR) }) return null

        // 情况一：小程序把「余额 ￥36.85」写在一个节点里
        items.firstNotNullOfOrNull { INLINE.find(it.text)?.let { m -> m.toYuan() } }?.let { return it }

        // 情况二：「余额」和「￥36.85」是两个节点（现在就是这种）
        val label = items.firstOrNull { it.text.isBalanceLabel() } ?: return null
        val candidates = items.mapNotNull { item ->
            val m = AMOUNT.find(item.text.trim().replace(",", "")) ?: return@mapNotNull null
            // 必须在「余额」右边，且纵向基本同一行
            if (item.left < label.right - 24) return@mapNotNull null
            if (kotlin.math.abs(item.cy - label.cy) > maxOf(label.height, 40)) return@mapNotNull null
            m.toYuan()?.let { value -> value to kotlin.math.abs(item.cy - label.cy) }
        }
        return candidates.minByOrNull { it.second }?.first
    }

    /**
     * 社慧通首页余额行的标题。用全等而不是 contains：
     * 旁边还有一个「余额充值」按钮，用 contains 会认错。
     */
    private fun String.isBalanceLabel(): Boolean {
        val t = trim()
        return t == "余额" || t == "余额(元)" || t == "余额（元）"
    }

    private fun MatchResult.toYuan(): Double? {
        val yuan = groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val cents = groupValues.getOrNull(2).orEmpty().padEnd(2, '0').toIntOrNull() ?: 0
        val value = yuan + cents / 100.0
        if (value < 0 || value > 100_000) return null
        return value
    }

    /** 广度遍历整棵树，只收有文字、且对用户可见的节点。 */
    private fun collect(root: AccessibilityNodeInfo): List<Item> {
        val out = ArrayList<Item>(48)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val rect = Rect()
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    out += Item(text, rect.left, rect.top, rect.right, rect.bottom)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    /* ---------------- 无障碍服务状态 ---------------- */

    /** 服务是否已在系统设置里被打开。 */
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DrinkBalanceService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.isNotBlank() &&
            enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** 跳到系统的无障碍设置页，让用户自己打开。 */
    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
