package com.bhu.campus.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.bhu.campus.MainActivity
import org.json.JSONObject

/**
 * 小米超级岛通知（HyperOS 焦点通知）。
 *
 * 原理：还是发一条普通通知，只是往 `extras` 里塞一个 `miui.focus.param` 的 JSON，
 * 支持岛的系统会把它渲染成岛；不支持的系统当普通通知显示，什么都不会坏。
 *
 * 三个前提，缺一个都不会上岛：
 * 1. 系统支持 —— `persist.sys.feature.island` = 1，且协议版本 [protocol] >= 3；
 * 2. 通知权限 —— Android 13+ 要 POST_NOTIFICATIONS；
 * 3. 焦点通知权限 —— 侧载应用默认没有，靠 `IslandAuthHook`（LSPosed）把鉴权放行。
 *
 * 参数结构按官方文档：顶层 `param_v2`，`business` / `param_island` 必填，
 * 焦点通知样式走 `baseInfo`，岛样式走 `param_island.bigIslandArea`。
 * `updatable=true` 才能用同一个通知 id 反复刷新（倒计时必需）。
 */
object IslandNotify {

    private const val TAG = "CampusIsland"
    private const val CHANNEL_ID = "campus_island"
    private const val KEY_PARAM = "miui.focus.param"

    /** OS 版本/协议：1、2 是旧焦点通知，3 才支持超级岛。 */
    fun protocol(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    /** 设备是否支持岛（系统属性，读不到就当不支持）。 */
    fun supported(context: Context): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod(
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.invoke(null, "persist.sys.feature.island", false) as? Boolean ?: false
    }.getOrDefault(false)

    /** 应用是否拿到了焦点通知权限（系统 SystemUI 提供的私有接口）。 */
    fun hasFocusPermission(context: Context): Boolean = runCatching {
        val uri = Uri.parse("content://miui.statusbar.notification.public")
        val extras = Bundle().apply { putString("package", context.packageName) }
        context.contentResolver.call(uri, "canShowFocus", null, extras)
            ?.getBoolean("canShowFocus", false) ?: false
    }.getOrDefault(false)

    /**
     * 岛上的字。左区放说明，右区只放要一眼看清的数字。
     *
     * 布局：
     * ```
     * ┌───────────────┬──────────┐
     * │ 洗涤中         │          │
     * │ 3 号机   单脱水 │  12 分钟  │   ← 左：kicker / detail / note
     * └───────────────┴──────────┘     右：headline
     * ```
     *
     * @param kicker 左区最上层小字，四个字以内最合适（洗涤中 / 待支付 / 预约码）
     * @param headline 右区大字，倒计时或预约码这种要一眼看清的；留空则右区不显示
     * @param detail 左区主文字（哪台机器 / 哪个浴区）
     * @param note 左区第二行补充说明，可空
     */
    data class Content(
        val kicker: String,
        val headline: String,
        val detail: String,
        val accent: String = "#006EFF",
        val note: String = "",
    ) {
        val oneLine: String
            get() = listOf(kicker, headline).filter { it.isNotBlank() }.joinToString(" ")

        /** 右区是不是纯数字（倒计时 03:00 / 预约码 8374）。是的话走等宽数字排版。 */
        val digitOnly: Boolean
            get() = headline.isNotBlank() && headline.all { it.isDigit() || it == ':' || it == ' ' || it == '.' }
    }

    /**
     * 拼 `miui.focus.param`。
     *
     * @param float 只在**第一次**冒出来时给 true：岛会从状态栏浮起并展开一次。
     *   后续每秒的刷新必须给 false，否则每走一秒都重演一遍展开动画，
     *   岛就一直「弹大」不收回。
     */
    fun params(content: Content, float: Boolean = true): String {
        val pv2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "campus")
            // 没有这个，同一 id 的通知就不会更新，倒计时也就走不动
            put("updatable", true)
            put("islandFirstFloat", float)
            put("enableFloat", float)
            // 状态栏那行 + 息屏那行，官方文档标的是必填
            put("ticker", content.oneLine)
            put("aodTitle", content.oneLine)
            put(
                "param_island",
                JSONObject().apply {
                    put("islandProperty", 1)
                    put("islandPriority", 2)
                    put(
                        "bigIslandArea",
                        JSONObject().apply {
                            // 左区：图标 + 说明文字（type=1 是「图标 + 文字」）
                            put(
                                "imageTextInfoLeft",
                                JSONObject().apply {
                                    put("type", 1)
                                    put("picInfo", JSONObject().put("type", 1))
                                    put(
                                        "textInfo",
                                        JSONObject().apply {
                                            // 系统把 frontTitle 和 title 直接拼在一起，不补空格，
                                            // 所以自己带一个，否则会变成「待支付3 号机」
                                            put("frontTitle", content.kicker + " ")
                                            put("title", content.detail)
                                            if (content.note.isNotBlank()) put("content", content.note)
                                            put("showHighlightColor", true)
                                        },
                                    )
                                },
                            )
                            // 右区：纯数字（type=2 才是「只有文字」，用 type=1 会量出 0 宽度不显示）
                            if (content.headline.isNotBlank()) {
                                if (content.digitOnly) {
                                    // 倒计时走 sameWidthDigitInfo：系统按等宽数字排版，
                                    // 每秒 09 → 08 时宽度不会跳，右边「秒」不会左右抖。
                                    put(
                                        "sameWidthDigitInfo",
                                        JSONObject().apply {
                                            put("digit", content.headline)
                                            put("showHighlightColor", true)
                                        },
                                    )
                                } else {
                                    // 「订单已撤销」这种中文，等宽数字排版装不下，回到普通文字
                                    put(
                                        "imageTextInfoRight",
                                        JSONObject().apply {
                                            put("type", 2)
                                            put(
                                                "textInfo",
                                                JSONObject().apply {
                                                    put("title", content.headline)
                                                    put("showHighlightColor", true)
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                            put("picInfo", JSONObject().put("type", 1))
                        },
                    )
                    put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1)))
                },
            )
            put(
                "baseInfo",
                JSONObject().apply {
                    put("type", 2)
                    put("title", content.oneLine)
                    put(
                        "content",
                        listOf(content.detail, content.note).filter { it.isNotBlank() }.joinToString(" · "),
                    )
                    put("colorTitle", content.accent)
                },
            )
        }
        return JSONObject().put("param_v2", pv2).toString()
    }

    private fun ensureChannel(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "超级岛", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        // 倒计时每秒重发一次，声音/震动必须关掉，否则会一直响
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    },
            )
        }
        return nm
    }

    /**
     * 发一条（或按 id 更新一条）岛通知。
     *
     * 返回 false 基本只有一种原因：通知权限没给。
     */
    fun show(
        context: Context,
        id: Int,
        content: Content,
        float: Boolean = true,
        ongoing: Boolean = true,
    ): Boolean = show(context, id, params(content, float), content.oneLine, content.detail, ongoing)

    fun show(
        context: Context,
        id: Int,
        params: String,
        title: String,
        text: String,
        ongoing: Boolean = false,
    ): Boolean = runCatching {
        val nm = ensureChannel(context)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            // 点岛回 App 看详情，否则点上去毫无反应，像坏了
            .setContentIntent(openApp(context, id))
            // 每次刷新都只默默替换，不再提示一次
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .build()
        notification.extras.putString(KEY_PARAM, params)
        // 每秒钟刷一次倒计时，系统默认每次绑定视图都会「提示一下」（震动/响铃），
        // 这两个开关分别关掉提示、以及更新时自动浮起，否则会一秒震一次。
        notification.extras.putBoolean("miui.focus.enableAlert", false)
        notification.extras.putBoolean("miui.island.updateNoFloat", true)
        nm.notify(id, notification)
        true
    }.onFailure { Log.w(TAG, "notify failed", it) }.getOrDefault(false)

    /** 点岛的跳转：回到 App 首页（洗衣卡片在「今日」页）。 */
    private fun openApp(context: Context, id: Int): PendingIntent? = runCatching {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    /** 撤掉：岛和通知一起消失。 */
    fun cancel(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(id) }
    }
}
