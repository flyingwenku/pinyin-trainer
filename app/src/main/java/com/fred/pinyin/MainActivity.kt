package com.fred.pinyin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ProgressStore(this)
        prefs = SyncPrefs(this)
        logStore = AppLog(this)
        store.logger = { logStore.add(it) }
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
        val debug = CheckBox(this).apply {
            text = "记录调试日志（默认关闭）"
            isChecked = logStore.enabled
        }
        box.addView(url)
        box.addView(user)
        box.addView(pass)
        box.addView(debug)

        AlertDialog.Builder(this)
            .setTitle("同步设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val newUrl = url.text.toString().trim()
                val newUser = user.text.toString().trim()
                val newPass = pass.text.toString()
                val changed = newUrl != prefs.url || newUser != prefs.user || newPass != prefs.password
                prefs.url = newUrl
                prefs.user = newUser
                prefs.password = newPass
                logStore.enabled = debug.isChecked
                // 只改了日志开关时不必触发一次网络同步
                if (changed) doSync()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLogsDialog() {
        val body = if (logStore.enabled) logStore.all()
        else "调试日志已关闭，可在“同步设置”中勾选开启。\n\n" + logStore.all()
        val box = EditText(this).apply {
            setText(body)
            setTextIsSelectable(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minHeight = 360
        }
        AlertDialog.Builder(this)
            .setTitle("调试日志")
            .setView(box)
            .setPositiveButton("复制") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("pinyin-debug-log", box.text.toString()))
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
            store.saveAsync(json)
            logStore.add("bridge saveProgress queued bytes=${json.length} ms=${System.currentTimeMillis() - t0}")
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
