package app.tavern.core.prompt

/**
 * 提示词拼装。当前只覆盖 P0-5 的第一条链路：main prompt + new chat 标记。
 *
 * 刻意不做的事：不读文件、不碰设置、不计算 token——这些分别属于 data / feature / tokenizer。
 * 这个类的唯一职责是"给定输入，产出与夹具逐字相等的消息"。
 */
public class PromptBuilder(
    private val mainTemplate: String = SillyTavernDefaults.MAIN,
) {
    /**
     * 对应原版预设里 identifier = `main` 的提示词。
     *
     * 原版对它的处理是 `substituteParams()`，即把 `{{char}}` / `{{user}}` 换成当前角色与用户名；
     * 换完直接作为第一条 system 消息发出去。
     */
    public fun mainPrompt(characterName: String, userName: String): ChatMessage =
        ChatMessage(
            role = ChatRole.System,
            content = substitute(mainTemplate, characterName, userName),
        )

    /** 新聊天时插在角色定义之后、消息历史之前的标记。 */
    public fun newChatMarker(): ChatMessage =
        ChatMessage(role = ChatRole.System, content = SillyTavernDefaults.NEW_CHAT)

    private fun substitute(template: String, characterName: String, userName: String): String =
        template
            .replace("{{char}}", characterName)
            .replace("{{user}}", userName)
}
