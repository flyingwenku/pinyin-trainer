package com.fred.pinyin.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地调试日志：供页面埋点和同步链路写入，用户可从界面复制导出。 */
class AppLog(context: Context) {
    private val file = File(context.filesDir, "debug-log.txt")
    private val lock = Any()
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val maxChars = 200_000

    fun add(message: String) {
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
