package app.tavern.core.lorebook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@@` 装饰器。期望值来自原版 `parseDecorators()`（`world-info.js:4652`）与激活逻辑
 * （`:4875`），包括它剥掉"不认识的装饰器行"这种怪行为。
 */
class WorldBookDecoratorsTest {

    @Test
    fun `已知装饰器被剥掉且正文从第一行非装饰器开始`() {
        val (decorators, content) = WorldBookDecorators.parse(
            "@@activate\n@@dont_activate\n正文第一行\n正文第二行",
        )
        assertEquals(listOf("@@activate", "@@dont_activate"), decorators)
        assertEquals("正文第一行\n正文第二行", content)
    }

    @Test
    fun `不认识的装饰器也会被剥掉但不记录`() {
        val (decorators, content) = WorldBookDecorators.parse("@@whatever\n正文")
        assertTrue(decorators.isEmpty())
        assertEquals("正文", content, "原版把未知装饰器行一起剥掉")
    }

    @Test
    fun `没有装饰器的正文原样返回`() {
        val text = "第一行\n@@not-at-top"
        val (decorators, content) = WorldBookDecorators.parse(text)
        assertTrue(decorators.isEmpty())
        assertEquals(text, content, "装饰器只认开头连续的那几行")
    }

    @Test
    fun `三只 @ 是转义 不激活也不留在正文里`() {
        val (decorators, content) = WorldBookDecorators.parse("@@@activate\n正文")
        assertTrue(decorators.isEmpty())
        assertEquals("正文", content)
    }

    @Test
    fun `整段都是装饰器时正文保持原样`() {
        // 原版 newContent 初始化为 content，找不到非 @@ 行就不改 —— 这里也与原版一致
        val (decorators, content) = WorldBookDecorators.parse("@@activate")
        assertEquals(listOf("@@activate"), decorators)
        assertEquals("@@activate", content)
    }

    @Test
    fun `扫描器里 activate 不看关键字 dont_activate 一律不激活`() {
        val activated = entry(content = "@@activate\n激活", keys = listOf("不出现的词"))
        val suppressed = entry(content = "@@dont_activate\n不该出现", keys = listOf("命中"), constant = false)

        val result = LorebookScanner().scan(
            entries = listOf(activated, suppressed),
            chat = listOf("User: 命中"),
            settings = LorebookScanSettings(charName = "Bot", userName = "User"),
        )

        assertTrue(result.beforeChar.contains("激活"), "@@activate 应该不看关键字就进来")
        assertTrue(!result.beforeChar.contains("不该出现"), "@@dont_activate 命中关键字也不该进来")
    }

    @Test
    fun `dont_activate 盖过 constant`() {
        val always = entry(content = "@@dont_activate\n别进来", constant = true)
        val result = LorebookScanner().scan(
            entries = listOf(always),
            chat = listOf("User: 随便说点什么"),
            settings = LorebookScanSettings(charName = "Bot", userName = "User"),
        )
        assertTrue(!result.beforeChar.contains("别进来"), "原版把装饰器判断放在 constant 之前")
    }

    private fun entry(
        content: String,
        keys: List<String> = emptyList(),
        constant: Boolean = false,
    ): LorebookEntry {
        // 模拟加载时的处理：正文从文件里读出来就先过一遍装饰器解析
        val (decorators, body) = WorldBookDecorators.parse(content)
        return LorebookEntry(
            world = "w",
            uid = content.hashCode(),
            keys = keys,
            content = body,
            constant = constant,
            decorators = decorators,
        )
    }
}
