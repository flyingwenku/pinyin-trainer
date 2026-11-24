package com.fred.pinyin.sync

/**
 * 与 PC 单应用字节级一致的合并实现。
 *
 * 刻意不用 JSONObject 做值序列化：它的键顺序不保证，会改变同时间戳的定序键，
 * 导致两端各选各的、永远收敛不了。这里直接保留原始 JSON 片段，
 * 只解析顶层键，按汉字升序拼接。
 */
object Merge {
    data class Entry(val value: String, val updatedAt: Long, val deleted: Boolean)

    fun parse(json: String): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        var i = 0
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '{') return out
        i++

        while (i < json.length) {
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length || json[i] != '"') break
            i++
            val key = StringBuilder()
            while (i < json.length && json[i] != '"') {
                if (json[i] == '\\' && i + 1 < json.length) {
                    i++
                    key.append(json[i])
                } else {
                    key.append(json[i])
                }
                i++
            }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') break
            i++
            while (i < json.length && json[i].isWhitespace()) i++

            val start = i
            if (i >= json.length) break
            when (json[i]) {
                '"' -> {
                    i++
                    while (i < json.length) {
                        if (json[i] == '\\' && i + 1 < json.length) {
                            i += 2
                            continue
                        }
                        if (json[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                }
                '{', '[' -> {
                    val open = json[i]
                    val close = if (open == '{') '}' else ']'
                    var depth = 0
                    var inStr = false
                    while (i < json.length) {
                        val c = json[i]
                        if (inStr) {
                            if (c == '\\') {
                                i++
                                continue
                            }
                            if (c == '"') inStr = false
                            i++
                            continue
                        }
                        if (c == '"') inStr = true
                        else if (c == open) depth++
                        else if (c == close) {
                            depth--
                            i++
                            if (depth == 0) break
                            continue
                        }
                        i++
                    }
                }
                else -> while (i < json.length && json[i] != ',' && json[i] != '}') i++
            }

            val value = json.substring(start, i)
            out[key.toString()] = Entry(
                value = value,
                updatedAt = numberField(value, "updatedAt"),
                deleted = boolField(value, "deleted")
            )
        }
        return out
    }

    fun serialize(map: Map<String, Entry>): String =
        map.keys.sorted().joinToString(",", "{", "}") { key -> "\"$key\":${map[key]!!.value}" }

    /** 最后写入者胜；同时间戳按原始记录内容序数取较大者（与 PC 端一致） */
    fun merge(local: String, remote: String): String {
        val out = LinkedHashMap(parse(local))
        parse(remote).forEach { (key, theirs) ->
            val mine = out[key]
            if (mine == null || newer(theirs, mine)) out[key] = theirs
        }
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val iterator = out.entries.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next().value
            if (e.deleted && e.updatedAt > 0 && e.updatedAt < cutoff) iterator.remove()
        }
        return serialize(out)
    }

    private fun newer(a: Entry, b: Entry): Boolean =
        if (a.updatedAt != b.updatedAt) a.updatedAt > b.updatedAt else a.value > b.value

    private fun numberField(value: String, name: String): Long =
        Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun boolField(value: String, name: String): Boolean =
        Regex("\"$name\"\\s*:\\s*true").containsMatchIn(value)
}
