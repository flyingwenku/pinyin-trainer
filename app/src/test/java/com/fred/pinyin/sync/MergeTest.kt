package com.fred.pinyin.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定与 PC 端一致的合并规则：幂等、最后写入者胜、墓碑、按字升序。 */
class MergeTest {
    // 必须用相对当前时间：merge 会清掉 30 天前的墓碑，1970 年假时间戳会被误判为过期
    private val now = System.currentTimeMillis()

    private fun rec(c: String, at: Long, deleted: Boolean = false): String =
        "{\"c\":\"$c\",\"level\":0,\"updatedAt\":$at" +
            (if (deleted) ",\"deleted\":true" else "") + "}"

    private fun doc(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { "\"${it.first}\":${it.second}" }

    @Test
    fun `keeps additions from both sides`() {
        val mine = doc("甲" to rec("甲", now - 20), "乙" to rec("乙", now - 10))
        val theirs = doc("丙" to rec("丙", now))
        val merged = Merge.parse(Merge.merge(mine, theirs))
        assertEquals(setOf("甲", "乙", "丙"), merged.keys)
    }

    @Test
    fun `newer timestamp wins`() {
        val mine = doc("甲" to rec("电脑", now))
        val theirs = doc("甲" to rec("手机", now - 400))
        assertTrue(Merge.parse(Merge.merge(mine, theirs))["甲"]!!.value.contains("\"c\":\"电脑\""))
        assertTrue(Merge.parse(Merge.merge(theirs, mine))["甲"]!!.value.contains("\"c\":\"电脑\""))
    }

    @Test
    fun `is order independent`() {
        val mine = doc("甲" to rec("甲", now - 900), "乙" to rec("乙", now - 700))
        val theirs = doc("甲" to rec("甲改", now - 800))
        assertEquals(Merge.merge(mine, theirs), Merge.merge(theirs, mine))
    }

    @Test
    fun `tombstone survives merge`() {
        val mine = doc("甲" to rec("删了", now, deleted = true))
        val theirs = doc("甲" to rec("没删", now - 400))
        assertTrue(Merge.parse(Merge.merge(mine, theirs))["甲"]!!.deleted)
    }

    @Test
    fun `edit after deletion resurrects it`() {
        val mine = doc("甲" to rec("删了", now - 400, deleted = true))
        val theirs = doc("甲" to rec("又想留着", now))
        val entry = Merge.parse(Merge.merge(mine, theirs))["甲"]!!
        assertFalse(entry.deleted)
        assertTrue(entry.value.contains("又想留着"))
    }

    @Test
    fun `old tombstone expires`() {
        val old = now - 31L * 24 * 3600 * 1000
        val merged = Merge.merge(doc("甲" to rec("删了", old, deleted = true)), "{}")
        assertTrue(merged == "{}")
    }

    @Test
    fun `equal timestamp picks greater value`() {
        val a = doc("甲" to rec("乙", now))
        val b = doc("甲" to rec("甲", now))
        val expected = if (rec("乙", now) > rec("甲", now)) "乙" else "甲"
        val actual = Merge.parse(Merge.merge(a, b))["甲"]!!.value
        assertTrue(actual.contains("\"c\":\"$expected\""))
    }

    @Test
    fun `merged keys are sorted ascending`() {
        val mine = doc("c" to rec("c", now), "a" to rec("a", now))
        val theirs = doc("b" to rec("b", now))
        assertEquals("""{"a":${rec("a", now)},"b":${rec("b", now)},"c":${rec("c", now)}}""", Merge.merge(mine, theirs))
    }
}
