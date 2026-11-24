package com.fred.pinyin.data

import android.content.Context
import com.fred.pinyin.sync.Merge
import org.json.JSONObject
import java.io.File

/** 本地学习记录：filesDir/progress.json，与 PC 端同一扁平数据契约。 */
class ProgressStore(context: Context) {
    private val file = File(context.filesDir, "progress.json")
    private val lock = Any()

    @Volatile
    private var cache: String = read()

    fun all(): String = cache

    fun replaceAll(json: String) {
        val text = json.trim()
        if (text.length > 8 * 1024 * 1024 || !text.startsWith("{")) return
        try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        synchronized(lock) {
            // 网页提交的记录必须打本地时间戳，否则手机上的修改永远合并不回去
            val annotated = Merge.annotate(cache, text)
            file.writeText(annotated, Charsets.UTF_8)
            cache = annotated
        }
    }

    /** 同步合并结果直接落盘，保留远端原有时间戳 */
    fun replaceSynced(json: String) {
        val text = json.trim()
        if (text.length > 8 * 1024 * 1024 || !text.startsWith("{")) return
        try { JSONObject(text) } catch (e: Exception) { return }
        synchronized(lock) {
            file.writeText(text, Charsets.UTF_8)
            cache = text
        }
    }

    private fun read(): String = try {
        if (file.exists()) file.readText(Charsets.UTF_8) else "{}"
    } catch (e: Exception) {
        "{}"
    }
}
