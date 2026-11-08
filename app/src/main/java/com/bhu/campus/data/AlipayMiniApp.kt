package com.bhu.campus.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 唤起支付宝小程序的入口。
 *
 * 支付宝外部唤起协议：`alipays://platformapi/startapp?appId=<小程序 appId>[&page=<页面路径>]`
 * 只能打开小程序（可以指定页面），拿不到登录态，也不需要额外权限。
 */
object AlipayMiniApp {

    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 渤海大学「社慧通生活服务」：水控打水、洗浴、设备预约、账单查询。 */
    const val SHETONG_APP_ID = "2021004146630557"

    /** 支付宝「扫一扫」。 */
    const val SCAN_APP_ID = "10000007"

    /** 打开社慧通小程序首页。返回 false 表示没唤起（多半是没装支付宝）。 */
    fun openShetong(context: Context): Boolean = open(context, SHETONG_APP_ID, null)

    /**
     * 把一段二维码内容当成「刚扫出来的码」交给支付宝处理。
     *
     * 饮水机屏幕上是动态码，内容形如 `https://88wise.com/p?i=xxx&b=设备号`，
     * 支付宝扫码后会路由到社慧通小程序的对应设备页。直接用 startapp 打开小程序只能到首页，
     * 所以这里走 `appId=10000007&qrcode=<内容>`：支付宝会把 qrcode 当作扫描结果做路由，
     * 效果和用户自己拿支付宝扫这张码完全一样。
     */
    fun openQrCode(context: Context, content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        val url = "alipays://platformapi/startapp?appId=$SCAN_APP_ID&qrcode=" + Uri.encode(trimmed)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 直接拉起支付宝扫一扫，让用户对着设备屏幕扫。 */
    fun openScanner(context: Context): Boolean = open(context, SCAN_APP_ID, null)

    fun open(context: Context, appId: String, page: String? = null): Boolean {
        if (appId.isBlank()) return false
        val url = buildString {
            append("alipays://platformapi/startapp?appId=")
            append(appId)
            if (!page.isNullOrBlank()) {
                append("&page=")
                append(Uri.encode(page))
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
