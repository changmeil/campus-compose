package com.bhu.campus

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.DrinkBalance
import com.bhu.campus.data.IslandHub

/**
 * 只干一件事：支付宝里出现社慧通首页时，把「余额 ￥36.85」那个数字记下来。
 *
 * 触发是**被动**的——你不用在校园 App 里点任何按钮，只要无障碍服务开着，
 * 打水时进一次社慧通首页（或设备页）就会被读到，回来直接看到新数字。
 *
 * 不做任何点击、不注入事件、不读输入框——纯读文本。
 * 事件被 `accessibility_service.xml` 里的 packageNames 限制在支付宝一个包里。
 */
class DrinkBalanceService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastScanAt = 0L

    /** 小程序页面进来后多久再补扫一次。见 [onAccessibilityEvent] 里的说明。 */
    private val retryDelays = longArrayOf(500, 1600, 3200)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != DrinkBalance.ALIPAY_PACKAGE) return

        // 小程序加载是一串连续的内容变化事件，节流一下，别把主线程刷爆
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAt < 700) return
        lastScanAt = now

        scan()

        // 社慧通是分段渲染的：窗口切换那一刻，余额那一行常常还是空白，
        // 只扫一次会白扫（用户看到「没读到」，但其实等半秒就有了）。
        // 补几次，不做轮询——次数固定，代价可以忽略。
        retryDelays.forEach { delay ->
            handler.postDelayed(::scan, delay)
        }
    }

    /**
     * 扫一次当前窗口。只在支付宝仍在前台时读，
     * 否则补扫时用户已经切走了，`rootInActiveWindow` 会是别人的窗口。
     */
    private fun scan() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != DrinkBalance.ALIPAY_PACKAGE) return

        val value = DrinkBalance.parse(root) ?: return
        CampusStore.init(applicationContext)
        if (CampusStore.recordDrinkBalance(value)) {
            Log.d("CampusDrink", "balance=$value")
        }
    }

    /** 这个服务是 App 唯一常驻的东西，超级岛的倒计时循环挂它上面保活。 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        CampusStore.init(applicationContext)
        IslandHub.attach(applicationContext)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
