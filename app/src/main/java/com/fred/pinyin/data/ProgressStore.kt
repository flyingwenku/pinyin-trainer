package com.fred.pinyin.data

import android.content.Context
import com.fred.pinyin.sync.Merge
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 本地学习记录：filesDir/progress.json，与 PC 端同一扁平数据契约。 */
class ProgressStore(context: Context) {
    private val file = File(context.filesDir, "progress.json")
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val pendingLock = Any()
    private var pending: String? = null
    private var workerScheduled = false

    @Volatile
    private var cache: String = read()

    @Volatile
    var logger: ((String) -> Unit)? = null

    fun all(): String = cache

    /** 网页保存只入队，避免 136KB 全量合并阻塞 WebView 主线程。 */
    fun saveAsync(json: String) {
        val text = quickAccepted(json) ?: return
        synchronized(pendingLock) {
            pending = text
            if (workerScheduled) return
            workerScheduled = true
        }
        io.execute {
            while (true) {
                val next = synchronized(pendingLock) {
                    val n = pending
                    pending = null
                    if (n == null) workerScheduled = false
                    n
                } ?: return@execute
                writeAnnotated(next)
            }
        }
    }

    /** 同步前等后台写入完成，确保读到最新本地记录。 */
    fun flush(timeoutMs: Long = 5000) {
        try {
            io.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logger?.invoke("store flush timeout: ${e.message ?: "unknown"}")
        }
    }

    /** 同步合并结果直接落盘，保留远端原有时间戳 */
    fun replaceSynced(json: String) {
        val text = normalized(json) ?: return
        flush()
        synchronized(lock) {
            file.writeText(text, Charsets.UTF_8)
            cache = text
        }
        logger?.invoke("store synced bytes=${text.length}")
    }

    private fun writeAnnotated(text: String) {
        val t0 = System.currentTimeMillis()
        logger?.invoke("store write start bytes=${text.length}")
        val tJson = System.currentTimeMillis()
        try {
            JSONObject(text)
        } catch (e: Exception) {
            logger?.invoke("store write invalid json: ${e.message ?: "unknown"}")
            return
        }
        val jsonMs = System.currentTimeMillis() - tJson

        synchronized(lock) {
            // 网页提交的记录必须打本地时间戳，否则手机上的修改永远合并不回去
            val tAnnotate = System.currentTimeMillis()
            val result = Merge.annotateDetailed(cache, text)
            val annotated = result.json
            val annotateMs = System.currentTimeMillis() - tAnnotate

            val tWrite = System.currentTimeMillis()
            file.writeText(annotated, Charsets.UTF_8)
            val writeMs = System.currentTimeMillis() - tWrite

            cache = annotated
            logger?.invoke(
                "store write done bytes=${annotated.length} jsonMs=$jsonMs" +
                    " annotateMs=$annotateMs writeMs=$writeMs changed=${result.changed}" +
                    " tombstoned=${result.tombstoned} totalMs=${System.currentTimeMillis() - t0}"
            )
        }
    }

    private fun quickAccepted(json: String): String? {
        val text = json.trim()
        if (text.length > 8 * 1024 * 1024 || !text.startsWith("{")) return null
        return text
    }

    private fun normalized(json: String): String? {
        val text = quickAccepted(json) ?: return null
        return try {
            JSONObject(text)
            text
        } catch (e: Exception) {
            null
        }
    }

    private fun read(): String = try {
        if (file.exists()) file.readText(Charsets.UTF_8) else "{}"
    } catch (e: Exception) {
        "{}"
    }
}
