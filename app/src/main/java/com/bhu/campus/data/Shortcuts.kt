package com.bhu.campus.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bhu.campus.R
import com.bhu.campus.ShortcutActionActivity

/**
 * 桌面图标长按菜单（App Shortcuts）。
 *
 * 里面放两类东西，都能一步到位：
 *  - 存过的打水设备（点一下直接拉起支付宝进那台机器）
 *  - 菜鸟驿站（点一下直接进「待取件」页）
 *
 * 注意系统有数量上限（[ShortcutManagerCompat.getMaxShortcutCountPerActivity]，MIUI 上通常 5 个），
 * 超了会按顺序裁掉，所以「设备在前、菜鸟在后」。
 *
 * 快捷方式本身不能带逻辑，统一跳 [ShortcutActionActivity]，由它按 kind + key 分发。
 */
object Shortcuts {

    private const val TAG = "CampusShortcuts"

    const val ACTION = "com.bhu.campus.action.SHORTCUT"
    const val EXTRA_KIND = "shortcut_kind"
    const val EXTRA_KEY = "shortcut_key"

    const val KIND_WATER = "water"
    const val KIND_CAINIAO = "cainiao"

    /** 菜鸟快捷方式的目标页，见 [Express.Page]。 */
    const val CAINIAO_PICKUP = "pickup"
    const val CAINIAO_IDENTITY = "identity"

    /**
     * 桌面上实际只露 4 个位置（MIUI 实测：注册 5 个也只显示 4 个），
     * 所以内容按「接水设备 → 淘宝菜鸟」排，超出的砍掉。
     */
    private const val VISIBLE_SLOTS = 4

    /**
     * 接水设备的图标按 **设备号** 定，不按列表顺序定——[CampusStore.waterQrs]
     * 是「最新用的排最前」，按顺序取图标的话，用一次就换位置了。
     * 表里没有的设备退回 [R.drawable.shortcut_water_cold]。
     */
    private fun waterIcon(deviceNo: String): Int = when (deviceNo) {
        "13285" -> R.drawable.shortcut_water_hot
        "87941" -> R.drawable.shortcut_water_cold
        else -> R.drawable.shortcut_water_cold
    }

    /** 重新生成动态快捷方式。数据一变就调一次（幂等，重复调只是覆盖）。 */
    fun sync(context: Context) {
        val app = context.applicationContext
        val max = runCatching {
            ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)
        }.getOrDefault(VISIBLE_SLOTS).coerceAtLeast(1)
        val slots = minOf(max, VISIBLE_SLOTS)

        val all = mutableListOf<ShortcutInfoCompat>()

        // 打水设备：waterQrs 已经是最新在前，顺序照搬
        CampusStore.waterQrs.forEach { qr ->
            all += build(
                app, "water_${qr.content.hashCode()}", qr.title, "接水 · ${qr.title}",
                KIND_WATER, qr.content, waterIcon(qr.deviceNo),
            )
        }

        // 淘宝菜鸟驿站：取件码（默认）和身份码（出库扫的那个）
        all += build(
            app, "cainiao", "菜鸟取件码", "取快递 · 取件码（我的驿站）",
            KIND_CAINIAO, CAINIAO_PICKUP, R.drawable.shortcut_cainiao,
        )
        all += build(
            app, "cainiao_identity", "菜鸟身份码", "取快递 · 身份码（出库扫）",
            KIND_CAINIAO, CAINIAO_IDENTITY, R.drawable.shortcut_cainiao,
        )

        val kept = all.take(slots)
        val dropped = all.size - kept.size
        if (dropped > 0) Log.w(TAG, "快捷方式只有 $slots 个位置，忽略 $dropped 个")

        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(app, kept)
        }.onFailure { Log.e(TAG, "setDynamicShortcuts failed", it) }
    }

    private fun build(
        context: Context,
        id: String,
        shortLabel: String,
        longLabel: String,
        kind: String,
        key: String,
        iconRes: Int,
    ): ShortcutInfoCompat {
        val intent = Intent(context, ShortcutActionActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_KEY, key)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setIntent(intent)
            .build()
    }
}
