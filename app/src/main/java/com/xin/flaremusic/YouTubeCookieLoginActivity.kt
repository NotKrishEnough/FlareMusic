package com.xin.flaremusic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class YouTubeCookieLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val target = "https://music.youtube.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sign in to YouTube Music"
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val message = TextView(this).apply {
            text = "Sign in with Google. FlareMusic only reads the YouTube Music session cookies needed to connect."
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setPadding(20, 16, 20, 16)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url?.startsWith(target) == true) checkCookies(false)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F")
        }
        val check = Button(this).apply {
            text = "I've signed in — continue"
            setOnClickListener { checkCookies(true) }
        }
        root.addView(message)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(check)
        setContentView(root)
    }

    private fun checkCookies(showError: Boolean) {
        CookieManager.getInstance().flush()
        val cookieHeader = CookieManager.getInstance().getCookie(target).orEmpty()
        val names = cookieHeader.split(';').map { it.trim().substringBefore('=') }.toSet()
        val hasSession = names.any { it == "SAPISID" || it == "__Secure-3PAPISID" } &&
            names.any { it == "SID" || it == "__Secure-3PSID" }
        if (hasSession) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COOKIE_HEADER, cookieHeader))
            finish()
        } else if (showError) {
            android.widget.Toast.makeText(this, "YouTube Music session cookies weren't found. Finish signing in and try again.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COOKIE_HEADER = "com.xin.flaremusic.YOUTUBE_COOKIE_HEADER"
    }
}
