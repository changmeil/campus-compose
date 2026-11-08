package com.bhu.campus

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.bhu.campus.data.AlipayMiniApp
import com.bhu.campus.data.CampusStore
import com.bhu.campus.data.Express
import com.bhu.campus.data.Shortcuts

/**
 * 桌面长按菜单的落点。没有界面，收下参数就转给支付宝 / 淘宝，然后自己退出。
 */
class ShortcutActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CampusStore.init(this)

        val kind = intent.getStringExtra(Shortcuts.EXTRA_KIND)
        val key = intent.getStringExtra(Shortcuts.EXTRA_KEY)

        when (kind) {
            Shortcuts.KIND_WATER -> openWater(key)
            Shortcuts.KIND_CAINIAO -> {
                if (!Express.openCainiao(this)) {
                    Toast.makeText(this, "没能打开淘宝，请确认已安装", Toast.LENGTH_SHORT).show()
                }
            }
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun openWater(key: String?) {
        val qr = CampusStore.waterQrs.firstOrNull { it.content == key }
        if (qr == null) {
            Toast.makeText(this, "这台设备已经删掉了，进 App 重新加一下", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AlipayMiniApp.openQrCode(this, qr.content)) {
            Toast.makeText(this, "没能打开支付宝，请确认已安装", Toast.LENGTH_SHORT).show()
        }
    }
}
