package app.tavern.core.prompt

/**
 * 角色卡里参与提示词组装的字段（V2 `data` 的子集）。
 *
 * 只放这一层真正用到的：描述、人格、场景、示例对话。其余字段（creator_notes、alternate_greetings…）
 * 不参与「发给模型的消息」，属于卡片模型自己的事，等 `:core:card-format` 建起来再搬过去。
 */
public data class CharacterDefinition(
    public val name: String,
    public val description: String = "",
    public val personality: String = "",
    public val scenario: String = "",
    public val mesExample: String = "",
)
