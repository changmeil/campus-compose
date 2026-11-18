package com.bhu.campus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.ChaoxingClient
import com.bhu.campus.data.IslandHub
import com.bhu.campus.data.IslandNotify
import com.bhu.campus.data.Shortcuts
import com.bhu.campus.ui.CampusApp
import com.bhu.campus.ui.CampusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CampusStore.init(this)
        // 桌面长按菜单（打水设备 + 驿站），数据变了 CampusStore 里也会再刷一次
        Shortcuts.sync(this)
        ChaoxingClient.loadCookies(this)
        if (ChaoxingClient.hasCookie(this)) {
            CampusStore.updateXxt { it.copy(loggedIn = true) }
        } else {
            CampusStore.updateXxt { it.copy(loggedIn = false) }
        }
        // 调试：直接驱动真实的岛（不用真的下单）。
        // adb shell am start -n com.bhu.campus/.MainActivity --es island_demo wash_wait
        handleDebugIntent(intent)
        setContent {
            CampusTheme {
                CampusApp()
            }
        }
    }

    /** 已经在前台时再发一次 adb 指令也要生效，所以调试入口也挂在 onNewIntent 上。 */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: android.content.Intent?) {
        when (intent?.getStringExtra("island_demo")) {
            "wash_wait" -> IslandHub.washWaitPay(this, "3 号机 · 单脱水")
            "wash_run" -> IslandHub.washRunning(this, "3 号机 · 标准洗", 12)
            "wash_done" -> IslandHub.washDone(this, "10kg滚筒机 3 号 · 超强洗")
            "wash_paid" -> IslandHub.wash(this, IslandHub.WashPhase.PAID, "10kg滚筒机 3 号 · 超强洗")
            "bath" -> IslandHub.bathCode(this, "8374", "三楼东区 · 单间")
            "alert" -> IslandHub.alert(this, "作业快到期", "实验报告 · 还剩 5 小时")
            "dismiss" -> IslandHub.dismiss(this)
        }
        // 临时：超级岛验证入口。adb shell am start -n com.bhu.campus/.MainActivity --es island_test 1
        if (intent?.getStringExtra("island_test") != null) {
            Log.d(
                "CampusIsland",
                "supported=${IslandNotify.supported(this)} protocol=${IslandNotify.protocol(this)} " +
                    "focusPerm=${IslandNotify.hasFocusPermission(this)}",
            )
            // 允许 adb 直接传 JSON 试参数，不必每次重编译
            val params = intent.getStringExtra("island_json")
                ?: IslandNotify.params(
                    IslandNotify.Content(
                        kicker = "待支付",
                        headline = "01:52",
                        detail = "3 号机 · 单脱水",
                        accent = "#FF6A00",
                    ),
                )
            val ok = IslandNotify.show(
                this,
                9901,
                params,
                title = "洗衣机待支付",
                text = "3 号机 · 单脱水 · 01:52 后自动撤销",
                ongoing = true,
            )
            Log.d("CampusIsland", "notify sent=$ok")
        }
        // 探针：拿一个不存在的订单号打一下启动接口，只看路径通不通（不会动到真单）。
        // adb shell am start -n com.bhu.campus/.MainActivity --es ujing_probe start
        intent?.getStringExtra("ujing_probe")?.let { what ->
            val u = CampusStore.ujing
            if (u.loggedIn) {
                lifecycleScope.launch {
                    val s = com.bhu.campus.data.Ujing.Session(u.mobile, u.token)
                    val (ok, err) = when (what) {
                        "selfClean" -> com.bhu.campus.data.Ujing.startSelfClean(s, "1")
                        "stop" -> com.bhu.campus.data.Ujing.stopWash(s, "1")
                        else -> com.bhu.campus.data.Ujing.startWash(s, "1")
                    }
                    Log.w("CampusIsland", "probe $what ok=$ok err=$err")
                }
            }
        }
    }
}
