package app.tavern.core.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 卡内嵌世界书的条目解析。字段名/缺省值都照 ST 的 `character_book.entries`。 */
class CardBookEntriesTest {

    private fun card(book: String): CharacterCard = CharacterCard.fromJson(
        """
        {
          "spec": "chara_card_v2",
          "data": {
            "name": "T",
            "description": "d",
            "character_book": $book
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `没有内嵌书时返回空列表`() {
        val plain = CharacterCard.fromJson("""{"spec":"chara_card_v2","data":{"name":"T"}}""")
        assertEquals(0, plain.bookEntries().size)
        Unit
    }

    @Test
    fun `解析 comment、keys 与 enabled`() {
        val book = """
            {
              "name": "测试书",
              "entries": [
                {"comment": "第一条", "keys": ["龙", "drago"], "enabled": true},
                {"name": "第二条（只有 name）", "key": ["剑"], "enabled": false}
              ]
            }
        """.trimIndent()

        val entries = card(book).bookEntries()
        assertEquals(2, entries.size)

        assertEquals("第一条", entries[0].comment)
        assertEquals(listOf("龙", "drago"), entries[0].keys)
        assertTrue(entries[0].enabled)

        // 老格式用单数 key；comment 缺失时退回 name
        assertEquals("第二条（只有 name）", entries[1].comment)
        assertEquals(listOf("剑"), entries[1].keys)
        assertFalse(entries[1].enabled)

        Unit
    }

    @Test
    fun `enabled 缺省为 true，空 keywords 不会报错`() {
        val book = """{"entries": [{"comment": "没有 enabled 和 keys"}]}"""
        val entry = card(book).bookEntries().single()

        assertTrue(entry.enabled, "ST 新建条目默认启用")
        assertEquals(0, entry.keys.size)
        Unit
    }
}
