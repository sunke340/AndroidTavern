package app.tavern.core.preset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 补"人设描述"那一格的三条口径（见 [PromptOrderCompat] 的 KDoc）。
 *
 * 背景：旧预设/旧顺序覆盖里没有这一格时，人设会被**静默丢弃**（原版判据是"表里没有就不发"），
 * 2026-11-27 真机就是这么丢的。
 */
class PromptOrderCompatTest {
    @Test
    fun `没有这一格时插在 worldInfoBefore 之后`() {
        val order = listOf(
            PromptOrderEntry("main"),
            PromptOrderEntry("worldInfoBefore"),
            PromptOrderEntry("charDescription"),
        )
        val fixed = PromptOrderCompat.ensurePersonaSlot(order)
        assertEquals(
            listOf("main", "worldInfoBefore", "personaDescription", "charDescription"),
            fixed.map { it.identifier },
        )
        // 原有的条目一个都不许丢/改
        assertEquals(PromptOrderEntry("main"), fixed[0])
        assertEquals(PromptOrderEntry("charDescription"), fixed[3])
    }

    @Test
    fun `已经有这一格时原样返回（包括被用户关掉的）`() {
        val order = listOf(
            PromptOrderEntry("main"),
            PromptOrderEntry("personaDescription", enabled = false),
        )
        // 关掉是用户的显式选择：不许强行打开，也不许重复插入
        assertSame(order, PromptOrderCompat.ensurePersonaSlot(order))
    }

    @Test
    fun `找不到 worldInfoBefore 就放最前；空表原样返回`() {
        val order = listOf(PromptOrderEntry("main"), PromptOrderEntry("chatHistory"))
        assertEquals(
            listOf("personaDescription", "main", "chatHistory"),
            PromptOrderCompat.ensurePersonaSlot(order).map { it.identifier },
        )
        assertEquals(emptyList(), PromptOrderCompat.ensurePersonaSlot(emptyList()))
    }
}
