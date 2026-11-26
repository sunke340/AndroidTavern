package app.tavern.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 揭示节奏的文本边界（`docs/plan-reveal-pacing.md` §3.2）。
 *
 * 这些用例存在的理由：真机上 emoji 被切成两半就是豆腐块，而本机编不了 `:app` ——
 * 只能把纯运算放到 `core/model` 里断言。
 */
class RevealTextTest {
    @Test
    fun `按码点取前缀，不会把代理对切开`() {
        // "👍🏽" = 2 个码点（基础 emoji + 肤色修饰符），4 个 UTF-16 单元
        val s = "a👍🏽b"
        assertEquals("a", takeCodePoints(s, 1))
        assertEquals("a👍", takeCodePoints(s, 2))
        assertEquals("a👍🏽", takeCodePoints(s, 3))
        assertEquals("a👍🏽b", takeCodePoints(s, 4))
        assertEquals("a👍🏽b", takeCodePoints(s, 99))
        assertEquals("", takeCodePoints(s, 0))
        assertEquals("", takeCodePoints(s, -3))
    }

    @Test
    fun `ZWJ 家族 emoji 也是按码点一个个来`() {
        // 👨‍👩‍👧 = 5 个码点
        val family = "👨\u200D👩\u200D👧x"
        assertEquals(6, family.codePointCount(0, family.length))
        assertEquals("👨", takeCodePoints(family, 1))
        assertEquals("👨\u200D", takeCodePoints(family, 2))
        assertEquals(family, takeCodePoints(family, 6))
    }

    @Test
    fun `增量计数：追加普通字符`() {
        var prev: String? = null
        var count = 0
        for (chunk in listOf("你", "你好", "你好世", "你好世界")) {
            count = countCodePointsIncrementally(chunk, prev, count)
            prev = chunk
            assertEquals(chunk.codePointCount(0, chunk.length), count)
        }
    }

    @Test
    fun `增量计数：代理对被 delta 切开也不会数两次`() {
        // 第一段以高位代理结尾（UTF-16 上是半个），第二段补上低位代理
        val high = "\uD83D" // 👍 的高位
        val low = "\uDC4D"  // 👍 的低位
        val first = "a$high"
        val second = "a$high$low"

        val firstCount = countCodePointsIncrementally(first, null, 0)
        assertEquals(2, firstCount) // 'a' + 未完成的代理（算 1）
        assertEquals(2, countCodePointsIncrementally(second, first, firstCount))
        assertEquals("a👍".codePointCount(0, "a👍".length), countCodePointsIncrementally(second, first, firstCount))
    }

    @Test
    fun `增量计数：非追加变化就全文重数`() {
        val prev = "旧正文很长很长"
        assertEquals("全新".codePointCount(0, 2), countCodePointsIncrementally("全新", prev, 7))
    }

    @Test
    fun `引用没变就不数`() {
        val same = "同样的字符串"
        assertEquals(42, countCodePointsIncrementally(same, same, 42))
    }
}
