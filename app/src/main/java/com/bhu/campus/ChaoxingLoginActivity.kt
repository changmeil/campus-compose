package com.bhu.campus

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * 用官方登录页做登录：验证码、扫码、短信都由学习通自己处理，
 * 我们只盯住 CookieManager，一旦出现 _uid 就认为登录成功并回传。
 */
class ChaoxingLoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var ticks = 0

    private val poller = object : Runnable {
        override fun run() {
            if (finished) return
            ticks++
            if (detectLogin()) {
                succeed()
                return
            }
            if (ticks > 900) { // 约 7 分钟
                finishWithCancel()
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        cookieManager.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (detectLogin()) succeed()
            }
        }

        web.loadUrl("https://passport2.chaoxing.com/login?fid=&refer=https%3A%2F%2Fi.chaoxing.com")
        setContentView(web)
        webView = web
        handler.postDelayed(poller, 800)
    }

    private fun detectLogin(): Boolean {
        val cookie = runCatching {
            CookieManager.getInstance().getCookie("https://i.chaoxing.com")
        }.getOrNull() ?: return false
        return cookie.contains("_uid=")
    }

    private fun succeed() {
        if (finished) return
        finished = true
        runCatching { CookieManager.getInstance().flush() }
        setResult(RESULT_OK)
        finish()
    }

    private fun finishWithCancel() {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onBackPressed() {
        val web = webView
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            finishWithCancel()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            it.loadUrl("about:blank")
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
