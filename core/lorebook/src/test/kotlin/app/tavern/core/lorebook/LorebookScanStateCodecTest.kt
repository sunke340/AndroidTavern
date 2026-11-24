package app.tavern.core.lorebook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** sticky/cooldown 计时状态的编解码：能原样回来，坏数据不许把聊天读崩。 */
class LorebookScanStateCodecTest {

    @Test
    fun `编码后解码能原样回来`() {
        val state = LorebookScanState(
            sticky = mapOf(
                "book.0" to LorebookTimedEffect(start = 3, end = 8),
                "book.7" to LorebookTimedEffect(start = 4, end = 4, protected = true),
            ),
            cooldown = mapOf("book.9" to LorebookTimedEffect(start = 0, end = 2)),
        )
        val restored = LorebookScanStateCodec.decode(LorebookScanStateCodec.encode(state))

        assertEquals(2, restored.sticky.size)
        assertEquals(8, restored.sticky.getValue("book.0").end)
        assertTrue(restored.sticky.getValue("book.7").protected)
        assertEquals(1, restored.cooldown.size)
        assertEquals(2, restored.cooldown.getValue("book.9").end)
    }

    @Test
    fun `空状态编码成空串`() {
        assertEquals("", LorebookScanStateCodec.encode(LorebookScanState()))
        assertEquals(emptyMap(), LorebookScanStateCodec.decode(null).sticky)
        assertEquals(emptyMap(), LorebookScanStateCodec.decode("").cooldown)
    }

    @Test
    fun `坏行被跳过而不是抛异常`() {
        val text = listOf(
            "S\u001Fbook.0\u001F1\u001F5\u001F0",   // 好的
            "S\u001Fbroken",                        // 字段不够
            "X\u001Fbook.1\u001F1\u001F5\u001F0",   // 未知类型
            "S\u001Fbook.2\u001Fabc\u001F5\u001F0", // 数字坏了
        ).joinToString("\n")

        val restored = LorebookScanStateCodec.decode(text)
        assertEquals(1, restored.sticky.size)
        assertTrue(restored.sticky.containsKey("book.0"))
    }
}
