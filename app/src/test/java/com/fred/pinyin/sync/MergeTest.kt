package com.fred.pinyin.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定与 PC 端一致的合并规则：幂等、最后写入者胜、墓碑、按字升序。 */
class MergeTest {
    private fun rec(c: String, at: Long, deleted: Boolean = false): String =
        "{\"c\":\"$c\",\"level\":0,\"updatedAt\":$at" +
            (if (deleted) ",\"deleted\":true" else "") + "}"

    private fun doc(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { "\"${it.first}\":${it.second}" }

    @Test
    fun `keeps additions from both sides`() {
        val mine = doc("甲" to rec("甲", 100), "乙" to rec("乙", 110))
        val theirs = doc("丙" to rec("丙", 120))
        val merged = Merge.parse(Merge.merge(mine, theirs))
        assertEquals(setOf("甲", "乙", "丙"), merged.keys)
    }

    @Test
    fun `newer timestamp wins`() {
        val mine = doc("甲" to rec("电脑", 900))
        val theirs = doc("甲" to rec("手机", 500))
        assertTrue(Merge.parse(Merge.merge(mine, theirs))["甲"]!!.value.contains("\"c\":\"电脑\""))
        assertTrue(Merge.parse(Merge.merge(theirs, mine))["甲"]!!.value.contains("\"c\":\"电脑\""))
    }

    @Test
    fun `is order independent`() {
        val mine = doc("甲" to rec("甲", 100), "乙" to rec("乙", 300))
        val theirs = doc("甲" to rec("甲改", 200))
        assertEquals(Merge.merge(mine, theirs), Merge.merge(theirs, mine))
    }

    @Test
    fun `tombstone survives merge`() {
        val mine = doc("甲" to rec("删了", 900, deleted = true))
        val theirs = doc("甲" to rec("没删", 500))
        assertTrue(Merge.parse(Merge.merge(mine, theirs))["甲"]!!.deleted)
    }

    @Test
    fun `edit after deletion resurrects it`() {
        val mine = doc("甲" to rec("删了", 500, deleted = true))
        val theirs = doc("甲" to rec("又想留着", 900))
        val entry = Merge.parse(Merge.merge(mine, theirs))["甲"]!!
        assertFalse(entry.deleted)
        assertTrue(entry.value.contains("又想留着"))
    }

    @Test
    fun `old tombstone expires`() {
        val old = System.currentTimeMillis() - 31L * 24 * 3600 * 1000
        val merged = Merge.merge(doc("甲" to rec("删了", old, deleted = true)), "{}")
        assertTrue(merged == "{}")
    }

    @Test
    fun `equal timestamp picks greater value`() {
        val a = doc("甲" to rec("乙", 1000))
        val b = doc("甲" to rec("甲", 1000))
        val expected = if (rec("乙", 1000) > rec("甲", 1000)) "乙" else "甲"
        val actual = Merge.parse(Merge.merge(a, b))["甲"]!!.value
        assertTrue(actual.contains("\"c\":\"$expected\""))
    }

    @Test
    fun `merged keys are sorted ascending`() {
        val mine = doc("c" to rec("c", 100), "a" to rec("a", 100))
        val theirs = doc("b" to rec("b", 100))
        assertEquals("""{"a":${rec("a", 100)},"b":${rec("b", 100)},"c":${rec("c", 100)}}""", Merge.merge(mine, theirs))
    }
}
