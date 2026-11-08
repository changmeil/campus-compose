package com.bhu.campus.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import com.bhu.campus.R

/**
 * 取快递的快捷入口。
 *
 * 三个都是「外部唤起」，不需要任何权限：
 *
 * 1. 菜鸟驿站（在淘宝里）：`tbopen://m.taobao.com/tbopen/index.html?...&h5Url=<页面地址>`
 *    淘宝只对外暴露 tbopen 这一个跳转口，TMSActivity 本身不导出（直接 startActivity 会被
 *    SecurityException 拦掉）。带上 h5Url 后淘宝会用自家容器（Themis）打开这个 H5，
 *    进去就是「菜鸟驿站 · 待取件」那一页，连底部 tab 和登录态都在。
 *
 * 2. 拼多多「我的订单」：`pinduoduo://com.xunmeng.pinduoduo/orders.html`
 *
 * 3. 拼多多某个驿站：见 [openPddStation]。
 *
 * 关于驿站的来龙去脉（重要，别再绕一遍）：
 * - 驿站墙上码解出来是 `https://mdkd.pinduoduo.com/weixin/package?station_code=xxx`，
 *   浏览器打开只会 302 到微信授权、提示「请在微信客户端打开」；拼多多也没把 mdkd 域名
 *   注册进自己的 http/https intent-filter，所以纯 Intent 打不开。
 * - 拼多多的 scheme 路由规则是「把 path 拼到某个固定域名后面」，实测落到
 *   `https://m.pinduoduo.net/<path>`，域名是写死的，改不了目标 host。
 * - 但 NewPageActivity 的 Intent 里，真正要加载的网址存在 extras 的 `props`
 *   （`com.aimi.android.common.entity.ForwardProps`）里，字段就是 `url`。
 *   所以配一个 LSPosed 模块（CampusProbe，包名 com.bhu.campusprobe）在
 *   `NewPageActivity.onCreate` 之前把 `props.url` 换成驿站真实地址即可。
 * - 约定：外部发 `pinduoduo://com.xunmeng.pinduoduo/campusprobe?code=<驿站码>`，
 *   模块识别到 `/campusprobe` 这段哨兵路径就改写。没有模块时这句会落到
 *   m.pinduoduo.net 的 404 页，所以进来前先用 [hasProbe] 判断。
 */
object Express {

    /** 淘宝 · 菜鸟驿站（校园版）首页，默认停在「待取件」。 */
    private const val TAOBAO_CAINIAO_PAGE =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1100333/last-mile-fe/m-end-school-tab/home"

    /** 一个拼多多驿站：code 就是墙上二维码里 station_code 的值。 */
    data class Station(
        val name: String,
        /** 桌面长按菜单里显示的短名，短一点，别被截断。 */
        val short: String,
        val code: String,
        val note: String,
        /** 长按菜单里用的图标。 */
        @DrawableRes val iconRes: Int,
    )

    /** 常去的三个点，按取件频率排。 */
    val STATIONS = listOf(
        Station(
            name = "爱邻丁香超市",
            short = "爱邻丁香驿站",
            code = "C014275405",
            note = "拼多多驿站 · 一点直达",
            iconRes = R.drawable.shortcut_station_al,
        ),
        Station(
            name = "天街211公共服务站",
            short = "天街211驿站",
            code = "A073636476",
            note = "拼多多驿站 · 一点直达",
            iconRes = R.drawable.shortcut_station_al,
        ),
        Station(
            name = "好想来零食店旁 · 渤海大学驿站",
            short = "好想来驿站",
            code = "ENC_Q2dJMldSSUlla3hQVUVWT1dEWWFFRnFLRDFvNWdsbWN3VVVQY20zRmdWc3dBZz09",
            note = "拼多多驿站 · 一点直达",
            iconRes = R.drawable.shortcut_station_al,
        ),
    )

    /** 打开淘宝里的菜鸟驿站。返回 false 表示没唤起（多半是没装淘宝）。 */
    fun openCainiao(context: Context): Boolean {
        val url = "tbopen://m.taobao.com/tbopen/index.html" +
            "?action=ali.open.nav&module=h5&bootImage=&h5Url=" +
            Uri.encode(TAOBAO_CAINIAO_PAGE)
        return launch(context, url)
    }

    /** 打开拼多多「我的订单」，自己点一下「待收货」就能看到取件码。 */
    fun openPddOrders(context: Context): Boolean =
        launch(context, "pinduoduo://com.xunmeng.pinduoduo/orders.html")

    /**
     * 直达某个拼多多驿站的取件页。
     * 需要 CampusProbe 模块在 LSPosed 里启用（见类注释），否则会落到 404 兜底页。
     */
    fun openPddStation(context: Context, code: String): Boolean =
        launch(context, "pinduoduo://com.xunmeng.pinduoduo/campusprobe?code=$code")

    /**
     * 驿站直通是否可用。
     *
     * v3.5 之前 hook 是独立的模块（com.bhu.campusprobe），这里靠"有没有装那个包"判断。
     * 现在 hook 已经内置进本 App（[com.bhu.campus.hook.PddStationHook]），装了就有，
     * 所以恒为 true。
     *
     * 注意：它反映不了"LSPosed 里到底启没启用"——启用列表在 /data/adb，普通应用读不到。
     * 真没启用时表现是点进去落到 404 兜底页。
     */
    fun hasProbe(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    private fun launch(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
