package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 世界书 outlet：`position = outlet` 的条目**不进系统提示**，只被 `{{outlet::名字}}` 宏取走
 * （原版 `world-info.js` 收成 `WIOutletEntries`，`macros.js` 里 `{{outlet::(.+?)}}` 去取）。
 *
 * 夹具 `P1-3-outlet` 是原版 1.19.1 的实拍请求：书里一条 `constant = true`、`position = 7`、
 * `outletName = "OUT"` 的条目，main 里写着 `{{outlet::OUT}}` —— 结果宏被替换成条目正文，
 * 并且**没有**多出一条 worldInfoBefore 消息。
 */
class WorldBookOutletFixtureTest {
    private val fixture = Fixtures.load("P1-3-outlet")

    @Test
    fun `出口条目只被宏取走，不进系统提示`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA")),
            outlets = mapOf("OUT" to listOf("OUTLET-ALPHA 这段是出口内容。")),
            prompts = mapOf("main" to "Write {{char}} next. Outlet: {{outlet::OUT}}"),
            order = app.tavern.core.preset.PresetDefaults.ORDER,
        )
        assertEquals(fixture.messages, actual)
        assertEquals(
            "Write Synthetic next. Outlet: OUTLET-ALPHA 这段是出口内容。",
            actual.first().content,
            "宏没被替换成出口正文",
        )
    }

    @Test
    fun `没有出口时不报错也不留空壳`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = "User",
            prompts = mapOf("main" to "A {{outlet::MISSING}} B"),
        )
        assertEquals("A  B", actual.first().content)
    }
}
