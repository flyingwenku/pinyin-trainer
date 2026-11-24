package com.fred.pinyin.data

import android.content.Context

/** 同步配置；密码存私有 SharedPreferences，不暴露给网页。 */
class SyncPrefs(context: Context) {
    private val sp = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(value) { sp.edit().putString("url", value).apply() }

    var user: String
        get() = sp.getString("user", "") ?: ""
        set(value) { sp.edit().putString("user", value).apply() }

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(value) { sp.edit().putString("password", value).apply() }

    var lastSyncAt: Long
        get() = sp.getLong("lastSyncAt", 0L)
        set(value) { sp.edit().putLong("lastSyncAt", value).apply() }

    val ready: Boolean get() = user.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/pinyin/progress.json"
    }
}
