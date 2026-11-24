package com.fred.pinyin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.fred.pinyin.data.ProgressStore
import com.fred.pinyin.data.SyncPrefs
import com.fred.pinyin.sync.SyncService

class MainActivity : Activity() {
    private lateinit var store: ProgressStore
    private lateinit var prefs: SyncPrefs
    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable { doSync() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ProgressStore(this)
        prefs = SyncPrefs(this)

        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.addJavascriptInterface(JsBridge(), "PinyinBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
        }
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
    }

    /** 30 分钟兜底同步：从后台回到前台时补一次，避免用户一直不点「同步」 */
    override fun onResume() {
        super.onResume()
        if (prefs.ready && System.currentTimeMillis() - prefs.lastSyncAt > 30 * 60 * 1000L) {
            doSync()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add("同步设置")?.setOnMenuItemClickListener {
            showSyncDialog()
            true
        }
        menu?.add("立即同步")?.setOnMenuItemClickListener {
            doSync()
            true
        }
        return true
    }

    private fun doSync() {
        Thread {
            val message = SyncService(store, prefs).syncNow()
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun showSyncDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val url = EditText(this).apply { setText(prefs.url); hint = "WebDAV 地址" }
        val user = EditText(this).apply { setText(prefs.user); hint = "坚果云账号" }
        val pass = EditText(this).apply {
            setText(prefs.password)
            hint = "应用密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(url)
        box.addView(user)
        box.addView(pass)

        AlertDialog.Builder(this)
            .setTitle("同步设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                prefs.url = url.text.toString().trim()
                prefs.user = user.text.toString().trim()
                prefs.password = pass.text.toString()
                doSync()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class JsBridge {
        @JavascriptInterface
        fun loadProgress(): String = store.all()

        @JavascriptInterface
        fun saveProgress(json: String) {
            store.replaceAll(json)
            handler.removeCallbacks(syncRunnable)
            handler.postDelayed(syncRunnable, 10_000)
        }

        @JavascriptInterface
        fun syncNow() {
            doSync()
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { showSyncDialog() }
        }
    }
}
