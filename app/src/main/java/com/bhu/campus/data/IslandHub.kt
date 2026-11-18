package com.bhu.campus.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 超级岛的「一块」：同一时刻只放一个岛，谁重要谁上。
 *
 * 倒计时不能交给系统——小米的岛不会自己走秒，只能我们自己按同一个通知 id
 * 反复重发。所以这里维护一个 [Live]，每秒钟重算一次剩余时间再刷新通知。
 *
 * 保活：这个循环挂在进程的主线程 Handler 上，进程活着就一直走。
 * App 退到后台后进程会不会被冻，取决于有没有常驻组件——
 * [com.bhu.campus.DrinkBalanceService]（无障碍服务）开着就稳，
 * 所以 App 启动和服务启动时都会 [attach] 一次。
 */
object IslandHub {

    private const val TAG = "CampusIsland"

    const val ID_WASH = 8101
    const val ID_BATH = 8102
    const val ID_ALERT = 8103

    /** 洗完/超时之后，岛再留多久自己消失。 */
    private const val LINGER_MS = 10 * 60_000L

    /** 待支付 3 分钟到点后，挂「确认中」等外部轮询定性的时长。 */
    private const val PENDING_MS = 90_000L

    /** 洗衣订单的五个阶段。文案、颜色、要不要倒数都由它决定。 */
    enum class WashPhase(val kicker: String, val accent: String, val countdown: Boolean) {
        /** 建单没付钱，3 分钟内会被撤 */
        WAIT_PAY("待支付", "#FF6A00", true),
        /** 付好了但机器还没启动 —— 等你去点「开始洗衣」 */
        PAID("已支付", "#B26A00", false),
        /** 筒自洁中（3 分钟，深度 10 分钟） */
        SELF_CLEAN("筒自洁", "#7E57C2", true),
        /** 自洁完了，5 分钟内必须启动洗衣，否则订单自动取消 */
        READY("待洗衣", "#00A86B", true),
        /** 真的在洗 */
        RUNNING("洗涤中", "#006EFF", true),
    }

    private enum class Kind { COUNTDOWN, STATIC }

    private class Live(
        val id: Int,
        val kind: Kind,
        val kicker: String,
        val detail: String,
        /** 0 表示不倒数（待启动/预约码这种） */
        val endAt: Long,
        val accent: String,
        /** 不倒数时右区直接显示的固定文字（预约码、「待启动」） */
        val text: String = "",
        /** 剩多少秒开始变红；0 = 不变红 */
        val warnSeconds: Int = 0,
        var phase: Int = 0, // 0=进行中 1=已结束（洗完/超时）
    )

    private var live: Live? = null
    private var appCtx: Context? = null
    private var lastText: String = ""
    private var started = false

    /** 当前这个岛有没有已经「浮起」过一次。只有首次出现才浮，之后都安静更新。 */
    private var floated = false

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            // 有岛时每秒走一格（倒计时要看秒），没岛时慢速空转，省电
            handler.postDelayed(this, if (live == null) 10_000L else 1_000L)
        }
    }

    /** 拿 Context 并启动循环。重复调用无害。 */
    fun attach(context: Context) {
        appCtx = context.applicationContext
        if (started) return
        started = true
        handler.post(ticker)
    }

    /** 当前有没有岛在挂着。外部轮询靠它决定查勤频率。 */
    val active: Boolean get() = live != null

    // ------------------------------------------------------------ 对外

    /**
     * 洗衣订单：按阶段上岛。
     *
     * 一个订单从建单到洗完要走五段（见 [WashPhase]），每一段的左区文案、
     * 右区倒计时都不一样。外部轮询每次查到新状态就调一次，重复调同一个阶段不会重置倒计时。
     *
     * [endAt] 给 0 时按 [WashPhase.countdown] 决定：不倒数就显示 [rightText]。
     */
    fun wash(
        context: Context,
        phase: WashPhase,
        detail: String,
        endAt: Long = 0L,
        rightText: String = "",
    ) {
        live?.let { cur ->
            if (cur.id == ID_WASH && cur.kicker == phase.kicker && cur.detail == detail) {
                // 同一阶段重复刷新：只有倒数结束时间差得明显（超过 1 分钟）才重排
                if (!phase.countdown || endAt == 0L) return
                if (kotlin.math.abs(cur.endAt - endAt) <= 60_000L) return
            }
        }
        val countdown = phase.countdown && endAt > System.currentTimeMillis()
        start(
            context,
            Live(
                id = ID_WASH,
                kind = if (countdown) Kind.COUNTDOWN else Kind.STATIC,
                kicker = phase.kicker,
                detail = detail,
                endAt = if (countdown) endAt else 0L,
                accent = phase.accent,
                text = rightText.ifBlank {
                    when {
                        countdown -> ""
                        phase == WashPhase.PAID -> "待启动"
                        else -> phase.kicker
                    }
                },
                warnSeconds = when (phase) {
                    WashPhase.WAIT_PAY -> 30
                    WashPhase.READY -> 60
                    else -> 0
                },
            ),
        )
    }

    /** 洗衣机：订单建好了但还没付款。服务端 3 分钟就自动撤单。 */
    fun washWaitPay(context: Context, detail: String, seconds: Int = 180) {
        wash(context, WashPhase.WAIT_PAY, detail, System.currentTimeMillis() + seconds * 1000L)
    }

    /** 洗衣机：机器正在洗。 */
    fun washRunning(context: Context, detail: String, remainMinutes: Int) {
        if (remainMinutes <= 0) return
        wash(context, WashPhase.RUNNING, detail, System.currentTimeMillis() + remainMinutes * 60_000L)
    }

    /**
     * 洗完：岛上留一句「洗完了」，10 分钟后自己收。
     *
     * 服务端查到这个状态时往往本地倒计时也刚到点，两边内容一样，这里做个去重。
     */
    fun washDone(context: Context, detail: String) {
        live?.let { if (it.id == ID_WASH && it.kicker == "洗完了" && it.detail == detail) return }
        start(
            context,
            Live(
                id = ID_WASH,
                kind = Kind.STATIC,
                kicker = "洗完了",
                detail = detail,
                endAt = 0L,
                accent = "#00A86B",
                text = "取衣",
            ),
        )
        val item = live
        handler.postDelayed({ if (live === item) drop(context) }, LINGER_MS)
    }

    /** 洗澡预约码。静态岛，不走秒，10 分钟后自己收。左区显示浴区，右区放预约码。 */
    fun bathCode(context: Context, code: String, detail: String) {
        start(
            context,
            Live(
                id = ID_BATH,
                kind = Kind.STATIC,
                kicker = "预约码",
                detail = detail,
                endAt = 0L,
                accent = "#00A86B",
                text = code,
            ),
        )
        handler.postDelayed({ if (live?.id == ID_BATH) drop(context) }, LINGER_MS)
    }

    /**
     * 一次性提醒：作业快到期、打水余额不足这类。
     *
     * 不展开成岛（信息只有一句话），只做焦点通知样式，1 分钟后自动收。
     */
    fun alert(context: Context, title: String, detail: String, accent: String = "#FF6A00") {
        attach(context)
        val content = IslandNotify.Content(title, "", detail, accent)
        IslandNotify.show(context, ID_ALERT, content, float = false, ongoing = false)
        handler.postDelayed({ IslandNotify.cancel(context, ID_ALERT) }, 60_000L)
    }

    /** 撤掉当前岛（撤销预约、取消订单时用）。 */
    fun dismiss(context: Context) {
        drop(context)
    }

    /** 只撤洗衣岛——别的岛（洗澡预约码）正挂着时不要误伤。 */
    fun dismissWash(context: Context) {
        if (live?.id == ID_WASH) drop(context)
    }

    // ------------------------------------------------------------ 内部

    private fun start(context: Context, item: Live) {
        attach(context)
        // 岛上一次显示的是同一个 id 就先撤干净，避免旧文案残留
        val ctx = appCtx ?: context.applicationContext
        if (live != null && live?.id != item.id) IslandNotify.cancel(ctx, live!!.id)
        live = item
        lastText = ""
        floated = false
        tick()
    }

    private fun drop(context: Context) {
        val item = live ?: return
        IslandNotify.cancel(context, item.id)
        live = null
        lastText = ""
        floated = false
    }

    /**
     * 刷一次岛。
     *
     * [float] 只有在**这个岛第一次出现**时才给 true —— 让系统把它浮起来展开一次。
     * 之后倒计时每秒刷新都必须是 false，否则每刷新一次就重演一遍展开动画，
     * 岛会一直「弹大」收不回去。
     */
    private fun push(context: Context, content: IslandNotify.Content) {
        if (content.headline == lastText) return
        lastText = content.headline
        val first = !floated
        floated = true
        IslandNotify.show(context, live?.id ?: ID_ALERT, content, float = first, ongoing = true)
    }

    private fun tick() {
        val ctx = appCtx ?: return
        val item = live ?: return
        if (item.phase == 1) {
            // 已经进入「洗完了 / 已超时」，只等 LINGER_MS 后自己消失
            return
        }

        val now = System.currentTimeMillis()
        if (item.endAt == 0L) {
            // 静态岛（预约码）：只在第一次进来时推一次，push 内部按文案去重
            push(ctx, IslandNotify.Content(item.kicker, item.text, item.detail, item.accent))
            return
        }

        val remain = item.endAt - now
        if (remain <= 0) {
            finish(ctx, item)
            return
        }

        // 统一走 mm:ss：洗涤中只剩 12 分钟时，「11:59」比「12 分钟」更有说服力
        val text = mmss(remain)
        val accent = if (item.warnSeconds > 0 && remain <= item.warnSeconds * 1000L) "#E53935"
        else item.accent
        push(ctx, IslandNotify.Content(item.kicker, text, item.detail, accent))
    }

    private fun finish(ctx: Context, item: Live) {
        item.phase = 1
        lastText = ""

        if (item.kicker == WashPhase.WAIT_PAY.kicker) {
            // 3 分钟到点了，但付款是在微信里完成的，我们并不知道用户到底付没付。
            // 所以先挂「确认中」，由外部的订单轮询来定性：
            // 查到洗涤中会直接换成洗涤岛（live 被替换，下面这些回调就自动失效），
            // 90 秒还没人来替换，才认定是真的被撤单了。
            val pending = IslandNotify.Content(
                kicker = "待确认",
                headline = "",
                detail = item.detail,
                note = "正在核对订单状态",
                accent = "#B26A00",
            )
            IslandNotify.show(ctx, item.id, pending, float = false, ongoing = true)
            handler.postDelayed({
                if (live === item && item.phase == 1) {
                    IslandNotify.show(
                        ctx,
                        item.id,
                        IslandNotify.Content(
                            kicker = "已超时",
                            headline = "订单已撤销",
                            detail = item.detail,
                            note = "没付款，机器放回空闲了",
                            accent = "#8A8A8A",
                        ),
                        float = false,
                        ongoing = true,
                    )
                }
            }, PENDING_MS)
            handler.postDelayed({
                if (live === item) drop(ctx)
            }, PENDING_MS + LINGER_MS)
            Log.i(TAG, "island wait-pay expired, pending confirm")
            return
        }

        // 自洁完 5 分钟内不启动洗衣，订单就被服务端收掉了 —— 这时候不是「洗完了」
        if (item.kicker == WashPhase.READY.kicker) {
            IslandNotify.show(
                ctx,
                item.id,
                IslandNotify.Content(
                    kicker = "已超时",
                    headline = "订单已取消",
                    detail = item.detail,
                    note = "自洁后没启动洗衣",
                    accent = "#8A8A8A",
                ),
                float = false,
                ongoing = true,
            )
            handler.postDelayed({
                if (live === item) drop(ctx)
            }, LINGER_MS)
            Log.i(TAG, "island ready expired, order cancelled")
            return
        }

        val content = IslandNotify.Content(
            kicker = "洗完了",
            headline = "取衣",
            detail = item.detail,
            note = "记得去取衣服",
            accent = "#00A86B",
        )
        IslandNotify.show(ctx, item.id, content, float = false, ongoing = true)
        handler.postDelayed({
            if (live === item) drop(ctx)
        }, LINGER_MS)
        Log.i(TAG, "island finished: ${content.kicker}")
    }

    private fun mmss(ms: Long): String {
        val total = (ms + 999) / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }
}
