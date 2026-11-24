package com.fred.pinyin.sync

import com.fred.pinyin.data.ProgressStore
import com.fred.pinyin.data.SyncPrefs

/** 必须在后台线程调用：一次 GET → 合并 → 有变化才一次 PUT。 */
class SyncService(private val store: ProgressStore, private val prefs: SyncPrefs) {
    fun syncNow(): String {
        if (!prefs.ready) return "未配置同步账号"
        val dav = WebDav(prefs.url, prefs.user, prefs.password)
        return try {
            val fetched = dav.fetch()
            val remote = if (fetched.exists && fetched.content != null) fetched.content!! else "{}"
            val merged = Merge.merge(store.all(), remote)

            if (merged != remote) dav.put(merged)
            store.replaceSynced(merged)
            prefs.lastSyncAt = System.currentTimeMillis()
            "同步完成"
        } catch (e: Exception) {
            prefs.lastSyncAt = System.currentTimeMillis()
            "同步失败：${e.message ?: "未知错误"}"
        }
    }
}
