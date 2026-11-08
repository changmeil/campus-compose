package com.bhu.campus.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 取快递：只留菜鸟驿站一个入口。
 *
 * 走淘宝的 `tbopen://` 跳转口：淘宝只对外暴露这一个中转，TMSActivity 本身不导出
 * （直接 startActivity 会被 SecurityException 拦掉）。带上 h5Url 后淘宝会用自家容器
 * （Themis）打开这个 H5，进去就是「菜鸟驿站 · 待取件」那一页，底部 tab 和登录态都在。
 *
 * 拼多多那套（订单页 + 三个驿站的 props.url 改写）已经整套删掉了：
 * 驿站墙上码是 `mdkd.pinduoduo.com/weixin/...`，只能在微信里打开，纯 Intent 进不去，
 * 原来靠内置的 LSPosed 模块改 `ForwardProps` 才勉强能到，维护成本远大于收益。
 */
object Express {

    /** 淘宝 · 菜鸟驿站（校园版）首页，默认停在「待取件」。 */
    private const val TAOBAO_CAINIAO_PAGE =
        "https://pages-fast.m.taobao.com/wow/z/uniapp/1100333/last-mile-fe/m-end-school-tab/home"

    /** 打开淘宝里的菜鸟驿站。返回 false 表示没唤起（多半是没装淘宝）。 */
    fun openCainiao(context: Context): Boolean {
        val url = "tbopen://m.taobao.com/tbopen/index.html" +
            "?action=ali.open.nav&module=h5&bootImage=&h5Url=" +
            Uri.encode(TAOBAO_CAINIAO_PAGE)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
