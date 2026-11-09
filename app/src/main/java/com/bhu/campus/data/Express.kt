package com.bhu.campus.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 取快递：只留菜鸟驿站一个入口。
 *
 * 校园驿站现在只有淘宝里那一套（「我的驿站」小程序），菜鸟 App 已经查不到取件码了。
 * 两个页面都在淘宝自己的容器（Themis）里，直接 startActivity 打不开，得走 `tbopen://` 中转：
 *
 *  - [Page.PICKUP]   「我的驿站」首页 —— 待取件包裹列表，**取件码就印在包裹卡片上**
 *                    （1-2-1234 = 1 号货架第 2 层 1234 号）。取件码没有独立页面，
 *                    服务大厅的「输入单号查取件码」也在这个首页里，所以这就是取件码页。
 *  - [Page.IDENTITY] 身份码 —— 出库时和高拍仪对扫的那个码（淘宝身份码，XN 开头）。
 *
 * 拼多多那套（订单页 + 三个驿站的 props.url 改写）已经整套删掉了：
 * 驿站墙上码是 `mdkd.pinduoduo.com/weixin/...`，只能在微信里打开，纯 Intent 进不去，
 * 原来靠内置的 LSPosed 模块改 `ForwardProps` 才勉强能到，维护成本远大于收益。
 */
object Express {

    private const val TAG = "CampusExpress"

    /** 「我的驿站」新版首页（2025 年换的地址），取件码在这一页。 */
    private const val CAINIAO_PICKUP =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1100333/last-mile-fe/m-end-school-tab/home"

    /** 老版「我的驿站」首页，新版加载不出来时兜底。 */
    private const val CAINIAO_PICKUP_LEGACY =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/school"

    /** 身份码（校园驿站出库要用这个）。 */
    private const val CAINIAO_IDENTITY =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1100410/last-mile-fe/m-end-identity-code/home"

    enum class Page(val label: String) {
        PICKUP("取件码"),
        IDENTITY("身份码"),
    }

    /** 打开淘宝里的菜鸟驿站。返回 false 表示没唤起（多半是没装淘宝）。 */
    fun openCainiao(context: Context, page: Page = Page.PICKUP): Boolean = when (page) {
        // 新版偶尔会白屏（uniapp 改版），退回老地址还能看到包裹
        Page.PICKUP -> launch(context, CAINIAO_PICKUP) || launch(context, CAINIAO_PICKUP_LEGACY)
        Page.IDENTITY -> launch(context, CAINIAO_IDENTITY)
    }

    /**
     * 用淘宝的中转口打开一个 H5。
     *
     * 两种 scheme 都试：`taobao://` 是网上实测能用的短格式，`tbopen://` 带 module=h5
     * 是淘宝官方 SDK 的写法，个别版本只认其中一个。
     */
    private fun launch(context: Context, h5: String): Boolean {
        val encoded = Uri.encode(h5)
        val schemes = listOf(
            "taobao://m.taobao.com/tbopen/index.html?h5Url=$encoded",
            "tbopen://m.taobao.com/tbopen/index.html" +
                "?action=ali.open.nav&module=h5&bootImage=&h5Url=$encoded",
        )
        for (url in schemes) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
            Log.w(TAG, "scheme 没唤起：$url")
        }
        return false
    }
}
