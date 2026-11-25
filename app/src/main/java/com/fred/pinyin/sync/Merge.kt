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
    data class AnnotateResult(val json: String, val changed: Int, val tombstoned: Int)

    // 避免每条记录都重新编译正则；这是全量标注的主要热点。
    private val UPDATED_AT_FIELD = Regex("\"updatedAt\"\\s*:\\s*(-?\\d+)")
    private val DELETED_FIELD = Regex("\"deleted\"\\s*:\\s*true")
    private val UPDATED_AT_ANY = Regex("\"updatedAt\"\\s*:\\s*-?\\d+")
    private val UPDATED_AT_WITH_COMMA = Regex(",\\s*\"updatedAt\"\\s*:\\s*-?\\d+")

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
                updatedAt = numberField(value),
                deleted = boolField(value)
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

    /**
     * 落盘前标注本地变更：变化的记录打当前时间戳，消失的记录转墓碑。
     * 与 PC 端 annotate_local 完全对齐。注意同步下来的merged不能走这里，
     * 否则远端记录的 updatedAt 会被改成“现在”，另一端再也合并不回来。
     */
    fun annotate(oldJson: String, newJson: String): String =
        annotateDetailed(oldJson, newJson).json

    fun annotateDetailed(oldJson: String, newJson: String): AnnotateResult {
        val old = parse(oldJson)
        val fresh = parse(newJson)
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, Entry>()
        var changed = 0
        var tombstoned = 0
        for ((key, e) in fresh) {
            val prior = old[key]
            if (prior == null || !sameContent(prior.value, e.value) || prior.deleted) {
                val value = withUpdatedAt(e.value, now)
                out[key] = Entry(value, now, false)
                changed++
            } else {
                out[key] = prior
            }
        }
        for ((key, e) in old) {
            if (!fresh.containsKey(key) && !e.deleted) {
                val value = "{\"deleted\":true,\"updatedAt\":$now}"
                out[key] = Entry(value, now, true)
                tombstoned++
            }
        }
        return AnnotateResult(serialize(out), changed, tombstoned)
    }

    private fun sameContent(a: String, b: String): Boolean =
        UPDATED_AT_WITH_COMMA.replace(a, "") == UPDATED_AT_WITH_COMMA.replace(b, "")

    private fun withUpdatedAt(value: String, now: Long): String {
        val match = UPDATED_AT_ANY.find(value)
        if (match != null) return value.replaceRange(match.range, "\"updatedAt\":$now")
        val end = value.lastIndexOf('}')
        return if (end < 0) value else
            value.substring(0, end) + ",\"updatedAt\":$now" + value.substring(end)
    }

    private fun newer(a: Entry, b: Entry): Boolean =
        if (a.updatedAt != b.updatedAt) a.updatedAt > b.updatedAt else a.value > b.value

    private fun numberField(value: String): Long =
        UPDATED_AT_FIELD.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun boolField(value: String): Boolean =
        DELETED_FIELD.containsMatchIn(value)
}
