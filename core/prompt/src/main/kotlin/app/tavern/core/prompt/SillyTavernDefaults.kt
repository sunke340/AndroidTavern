package app.tavern.core.prompt

/**
 * 原版 SillyTavern 1.19.1 的默认提示词模板（取自 `default/content/presets/openai/Default.json`
 * 与 `public/scripts/openai.js` 里的 `default_*` 常量）。
 *
 * 为什么把这些字符串抄进代码而不是运行时读文件：它们是**兼容性契约**的一部分，必须能被单测
 * 逐字断言，也必须随 APK 一起发出去（用户手机上不会有 vendor 目录）。抄写处一律标注出处，
 * `DefaultTemplatesVendorTest` 会在本机存在 vendor 时反向核对，防止抄错。
 */
public object SillyTavernDefaults {
    /**
     * 预设里 identifier = `main` 的那条。
     *
     * 内容定义在 [app.tavern.core.preset.PresetDefaults.MAIN]（预设模块是最底层，
     * 那张默认表属于它），这里只是一个别名，避免同一段模板抄两份。
     */
    public const val MAIN: String = app.tavern.core.preset.PresetDefaults.MAIN

    /** 预设顶层 `new_chat_prompt`，插在聊天历史最前面（`chatHistory` 块的首条）。 */
    public const val NEW_CHAT: String = "[Start a new Chat]"

    /** 预设顶层 `new_example_chat_prompt`，插在每块示例对话前面。 */
    public const val EXAMPLE_CHAT: String = "[Example Chat]"

    /** 预设顶层 `personality_format`。 */
    public const val PERSONALITY_FORMAT: String = "{{personality}}"

    /** 预设顶层 `scenario_format`。 */
    public const val SCENARIO_FORMAT: String = "{{scenario}}"
}
