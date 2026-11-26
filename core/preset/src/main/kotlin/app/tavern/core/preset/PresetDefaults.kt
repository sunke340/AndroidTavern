package app.tavern.core.preset

/**
 * 原版 1.19.1 的**默认预设**（`default/content/presets/openai/Default.json`）。
 *
 * 为什么抄进代码而不是运行时读文件：这是兼容性契约的一部分，必须能被单测逐字断言，
 * 也必须随 APK 发出去（用户手机上不会有 vendor 目录）。抄写处都标注了出处，
 * `PresetDefaultsVendorTest` 会在本机有 vendor 时反向核对。
 */
public object PresetDefaults {
    /** `prompts` 里 identifier = `main` 那条的内容（`{{charIfNotGroup}}` 在原版里对单人卡等于 `{{char}}`）。 */
    public const val MAIN: String =
        "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}."

    /**
     * 默认预设的 `prompt_order`（全局那一条，`character_id = 100000`）。
     *
     * 原版 Default.json 里有两张表：文本补全那张没有 `personaDescription`，聊天补全那张有
     * （在 `worldInfoBefore` 与 `charDescription` 之间）。我们走的是聊天补全这条线，
     * 所以按第二张表抄；人设为空时那一段不发，输出与之前完全一致。
     */
    public val ORDER: List<PromptOrderEntry> = listOf(
        PromptOrderEntry("main"),
        PromptOrderEntry("worldInfoBefore"),
        PromptOrderEntry("personaDescription"),
        PromptOrderEntry("charDescription"),
        PromptOrderEntry("charPersonality"),
        PromptOrderEntry("scenario"),
        // 默认关着：原版把它做成"想让模型补设定时再打开"的可选项
        PromptOrderEntry("enhanceDefinitions", enabled = false),
        PromptOrderEntry("nsfw"),
        PromptOrderEntry("worldInfoAfter"),
        PromptOrderEntry("dialogueExamples"),
        PromptOrderEntry("chatHistory"),
        PromptOrderEntry("jailbreak"),
    )

    /**
     * 默认预设里**有内容**的那几条提示词。
     *
     * `nsfw` / `jailbreak` 默认是空串（原版里那两条叫 Auxiliary Prompt / Post-History Instructions，
     * 用户自己填才有内容），空串由调用方按"不发"处理。
     */
    public val PROMPTS: Map<String, String> = mapOf(
        "main" to MAIN,
        "nsfw" to "",
        "jailbreak" to "",
        "enhanceDefinitions" to
            "If you have more knowledge of {{char}}, add to the character's lore and personality to " +
            "enhance them but keep the Character Sheet's definitions absolute.",
    )
}
