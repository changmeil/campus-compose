package com.bhu.campus

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.DrinkBalance

/**
 * 只干一件事：支付宝里出现社慧通首页时，把「余额 ￥36.85」那个数字记下来。
 *
 * 不做任何点击、不注入事件、不读输入框——纯读文本。
 * 事件被 `accessibility_service.xml` 里的 packageNames 限制在支付宝一个包里。
 */
class DrinkBalanceService : AccessibilityService() {

    private var lastScanAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != DrinkBalance.ALIPAY_PACKAGE) return

        // 小程序加载是一串连续的内容变化事件，节流一下，别把主线程刷爆
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAt < 700) return
        lastScanAt = now

        val value = DrinkBalance.parse(rootInActiveWindow) ?: return
        CampusStore.init(applicationContext)
        if (CampusStore.recordDrinkBalance(value)) {
            Log.d("CampusDrink", "balance=$value")
        }
    }

    override fun onInterrupt() = Unit
}
