package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `forbid_overrides`：预设里给某几条提示词打上这个旗标之后，**角色卡里同名的正文顶不掉它**
 * （原版 `openai.js:1498` / `:1508` 的 `systemPrompt.forbid_overrides !== true` 判定）。
 *
 * 两份夹具都是原版 1.19.1 对同一张卡（`SysPrompt`，带 `system_prompt` 与
 * `post_history_instructions`）的实拍请求，只差一个旗标：
 * - `P0-5-sysprompt`：没有旗标 → main 变成 `SYSPROMPT-OMEGA for SysPrompt`，jailbreak 变成 `PHI-DELTA …`；
 * - `P0-5-forbid-overrides`：main + jailbreak 打了旗标 → 两段都回落到**预设正文**
 *   （jailbreak 在默认预设里是空串，于是整条消息消失）。
 */
class ForbidOverridesFixtureTest {
    private val card = Fixtures.card("P0-5-sysprompt.png")
    private val history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-SYS"))

    @Test
    fun `打了旗标之后卡里的两段指令都顶不掉预设`() {
        val fixture = Fixtures.load("P0-5-forbid-overrides")
        val actual = PromptBuilder().build(
            character = card,
            userName = fixture.userName,
            history = history,
            forbidOverrideIds = setOf("main", "jailbreak"),
        )
        assertEquals(fixture.messages, actual)
        assertTrue(actual.none { it.content.contains("SYSPROMPT-OMEGA") }, "卡里的 system_prompt 不该出现")
        assertTrue(actual.none { it.content.contains("PHI-DELTA") }, "卡里的 post_history 不该出现")
    }

    @Test
    fun `不打旗标时行为不变（和原来的夹具一致）`() {
        val fixture = Fixtures.load("P0-5-sysprompt")
        val actual = PromptBuilder().build(card, fixture.userName, history)
        assertEquals(fixture.messages, actual)
    }
}
