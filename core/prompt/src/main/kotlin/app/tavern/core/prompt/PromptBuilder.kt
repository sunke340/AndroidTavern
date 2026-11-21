package app.tavern.core.prompt

import app.tavern.core.card.CharacterCard

/**
 * 提示词拼装。产出必须与夹具逐字相等——夹具是原版 1.19.1 实际发给 API 的请求体。
 *
 * 当前覆盖的链路（对应矩阵 P0-5 的主干）：
 *
 * ```
 * main → charDescription → charPersonality → scenario → dialogueExamples → newChat → 历史
 * ```
 *
 * 刻意不做的事：不读文件、不碰设置、不算 token —— 分别属于 data / feature / tokenizer。
 * 也还没做：世界书注入（P1）、作者注、名称前缀（names_behavior）、token 预算裁剪。
 */
public class PromptBuilder {
    public fun build(
        character: CharacterCard,
        userName: String,
        history: List<ChatMessage> = emptyList(),
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        messages += mainPrompt(character.name, userName)

        if (character.description.isNotBlank()) {
            messages += system(substitute(character.description, character.name, userName))
        }

        if (character.personality.isNotBlank()) {
            val formatted = SillyTavernDefaults.PERSONALITY_FORMAT
                .replace("{{personality}}", character.personality)
            messages += system(substitute(formatted, character.name, userName))
        }

        if (character.scenario.isNotBlank()) {
            val formatted = SillyTavernDefaults.SCENARIO_FORMAT
                .replace("{{scenario}}", character.scenario)
            messages += system(substitute(formatted, character.name, userName))
        }

        val examples = ExampleDialogue.parse(character.messageExample, character.name, userName)
        if (examples.isNotEmpty()) {
            messages += system(SillyTavernDefaults.EXAMPLE_CHAT)
            examples.forEach { messages += system(it) }
        }

        messages += newChatMarker()

        // 历史消息原版也会逐条去掉 \r（"waste of tokens"，见 openai.js 的 content.replace(/\r/gm, '')），
        // 这里照做，否则带 CRLF 的老聊天会与原版差字符。
        messages += history.map { it.copy(content = it.content.replace("\r", "")) }

        return messages
    }

    /** 对应原版预设里 identifier = `main` 的提示词。 */
    public fun mainPrompt(characterName: String, userName: String): ChatMessage =
        system(substitute(SillyTavernDefaults.MAIN, characterName, userName))

    /** 新聊天时插在角色定义之后、消息历史之前的标记。 */
    public fun newChatMarker(): ChatMessage =
        ChatMessage(role = ChatRole.System, content = SillyTavernDefaults.NEW_CHAT)

    private fun system(content: String): ChatMessage = ChatMessage(role = ChatRole.System, content = content)

    /**
     * 原版 `substituteParams()` 的最小实现：`{{char}}` / `{{user}}`，外加把 CRLF 去掉。
     *
     * CRLF 那条不是可选项：默认卡里描述是 CRLF，原版发出去的是 LF —— 已在
     * `P0-5-prompt-basic` 的夹具 diff 里逐字确认过。
     */
    private fun substitute(text: String, characterName: String, userName: String): String =
        text
            .replace("{{char}}", characterName)
            .replace("{{user}}", userName)
            .replace("\r", "")
}
