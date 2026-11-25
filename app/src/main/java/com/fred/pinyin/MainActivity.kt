package com.fred.pinyin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import com.fred.pinyin.data.AppLog
import com.fred.pinyin.data.ProgressStore
import com.fred.pinyin.data.SyncPrefs
import com.fred.pinyin.sync.SyncService

class MainActivity : Activity() {
    private lateinit var store: ProgressStore
    private lateinit var prefs: SyncPrefs
    private lateinit var logStore: AppLog
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable { doSync() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ProgressStore(this)
        prefs = SyncPrefs(this)
        logStore = AppLog(this)
        logStore.add("app start")

        web = WebView(this)
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
            val message = SyncService(store, prefs) { logStore.add(it) }.syncNow()
            val ok = message == "同步完成"
            runOnUiThread {
                if (ok) web.evaluateJavascript("window.onProgressSynced && window.onProgressSynced();", null)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
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

    private fun showLogsDialog() {
        val box = EditText(this).apply {
            setText(logStore.all())
            setTextIsSelectable(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minHeight = 360
        }
        AlertDialog.Builder(this)
            .setTitle("调试日志")
            .setView(box)
            .setPositiveButton("复制") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.primaryClip = ClipData.newPlainText("pinyin-debug-log", box.text.toString())
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空") { _, _ ->
                logStore.clear()
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    inner class JsBridge {
        @JavascriptInterface
        fun log(message: String) {
            logStore.add("js $message")
        }

        @JavascriptInterface
        fun loadProgress(): String {
            val json = store.all()
            logStore.add("bridge loadProgress bytes=${json.length}")
            return json
        }

        @JavascriptInterface
        fun saveProgress(json: String) {
            val t0 = System.currentTimeMillis()
            store.replaceAll(json)
            logStore.add("bridge saveProgress bytes=${json.length} ms=${System.currentTimeMillis() - t0}")
            handler.removeCallbacks(syncRunnable)
            handler.postDelayed(syncRunnable, 10_000)
        }

        @JavascriptInterface
        fun syncNow() {
            logStore.add("bridge syncNow")
            doSync()
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { showSyncDialog() }
        }

        @JavascriptInterface
        fun showLogs() {
            runOnUiThread { showLogsDialog() }
        }
    }
}
