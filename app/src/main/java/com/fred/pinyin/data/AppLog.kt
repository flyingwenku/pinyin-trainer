package com.fred.pinyin.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地调试日志：供页面埋点和同步链路写入，用户可从界面复制导出。 */
class AppLog(context: Context) {
    private val file = File(context.filesDir, "debug-log.txt")
    private val sp = context.getSharedPreferences("log", Context.MODE_PRIVATE)
    private val lock = Any()
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val maxChars = 200_000

    /** 默认关闭：只有用户在设置里打开后才写日志，平时不产生任何 IO */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) { sp.edit().putBoolean("enabled", value).apply() }

    fun add(message: String) {
        if (!enabled) return
        val line = "${formatter.format(Date())} $message\n"
        synchronized(lock) {
            try {
                file.appendText(line, Charsets.UTF_8)
                val text = file.readText(Charsets.UTF_8)
                if (text.length > maxChars) file.writeText(text.takeLast(maxChars), Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    fun all(): String = synchronized(lock) {
        try {
            if (file.exists()) file.readText(Charsets.UTF_8) else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {
            }
        }
    }
}
