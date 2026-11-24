package com.fred.pinyin.sync

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 极简 WebDAV 客户端：只做 GET / PUT。
 *
 * 沿用参考项目的踩坑约定：Basic 用坚果云应用密码；不带 ETag/If-Match；
 * GET 禁缓存（坚果云返回 max-age=5，命中缓存会读到旧内容）。
 * 云端目录需预先存在，或先用 PC 端创建。
 */
internal class WebDav(
    private val fileUrl: String,
    private val user: String,
    private val pass: String
) {
    private fun readErr(c: HttpURLConnection): String = try {
        (c.errorStream ?: c.inputStream).readBytes()
            .toString(Charset.forName("UTF-8"))
            .replace('\n', ' ')
            .take(200)
    } catch (e: Exception) {
        "(读不到响应体)"
    }

    data class Fetched(val exists: Boolean, val content: String?)

    fun fetch(): Fetched {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        return try {
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("Pragma", "no-cache")
            c.setRequestProperty("Authorization", auth())
            c.connectTimeout = 15_000
            c.readTimeout = 30_000

            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return Fetched(false, null)
            if (code !in 200..299) throw IOException("GET $code: ${readErr(c)}")

            Fetched(true, c.inputStream.readBytes().toString(Charset.forName("UTF-8")))
        } finally {
            c.disconnect()
        }
    }

    fun put(content: String) {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("Authorization", auth())
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.outputStream.use { it.write(content.toByteArray(Charset.forName("UTF-8"))) }

            val code = c.responseCode
            if (code !in 200..299) throw IOException("PUT $code: ${readErr(c)}")
        } finally {
            c.disconnect()
        }
    }

    private fun auth(): String = "Basic " + Base64.encodeToString(
        "$user:$pass".toByteArray(Charset.forName("UTF-8")),
        Base64.NO_WRAP
    )
}
