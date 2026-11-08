package com.bhu.campus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.ChaoxingClient
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
        setContent {
            CampusTheme {
                CampusApp()
            }
        }
    }
}
