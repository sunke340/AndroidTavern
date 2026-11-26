package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole

import app.tavern.core.card.CharacterCard
import app.tavern.core.lorebook.LorebookScanResult
import app.tavern.core.preset.PresetDefaults
import app.tavern.core.preset.PromptOrderEntry

/**
 * 提示词拼装。产出必须与夹具逐字相等——夹具是原版 1.19.1 实际发给 API 的请求体。
 *
 * **顺序由 `prompt_order` 决定**（原版预设里的那张表），不再写死在代码里：
 * 每个 `identifier` 对应一段内容，`enabled = false` 的整段不发。默认顺序见
 * [PresetDefaults.ORDER]：
 *
 * ```
 * main → worldInfoBefore → charDescription → charPersonality → scenario
 *      → worldInfoAfter → dialogueExamples → chatHistory → jailbreak
 * ```
 *
 * 其中 `chatHistory` 这一段 = `[Start a new Chat]` 标记 + 全部历史消息（原版是
 * `chatCompletion.insertAtStart(newChatMessage, 'chatHistory')`，即标记属于这一段的开头，
 * 不是独立的一段）；`dialogueExamples` 同理，块首是 `[Example Chat]`。
 *
 * 世界书内容由 `:core:lorebook` 扫出来（[LorebookScanResult]），这里只负责**按位置插进去**。
 * 刻意不做的事：不读文件、不碰设置、不算 token。
 * 还没做的注入点：`enhanceDefinitions` 之外的扩展、群聊。
 * 人设（`personaDescription`）已接：原版把它当**独立的一条 system 消息**，位置由 `prompt_order`
 * 决定（默认在 `worldInfoBefore` 之后、`charDescription` 之前）。夹具 `P1-8-persona-prompt`。
 */
public class PromptBuilder {
    /**
     * @param order 发送顺序（来自预设的 `prompt_order`；默认是原版默认预设那张表）
     * @param prompts 带内容的提示词（`main` / `nsfw` / `jailbreak` …；空串＝不发）
     */
    public fun build(
        character: CharacterCard,
        userName: String,
        history: List<ChatMessage> = emptyList(),
        worldInfo: LorebookScanResult = LorebookScanResult(),
        order: List<PromptOrderEntry> = PresetDefaults.ORDER,
        prompts: Map<String, String> = PresetDefaults.PROMPTS,
        /**
         * 生效人设的描述。空串 = 没写 / 没选人设 —— 那一段就不发（原版同样只在有值时插）。
         * 位置由 `prompt_order` 里的 `personaDescription` 决定，不在这里写死。
         */
        personaDescription: String = "",
        /** 作者注；null = 没设（那就不插）。插不插还要看 [AuthorNote.activeAt]。 */
        authorNote: AuthorNote? = null,
        /** 世界书里 `position = ANTop`（2）的条目：拼在作者注**前面**（`\n` 分隔）。 */
        authorNoteTop: List<String> = emptyList(),
        /** 世界书里 `position = ANBottom`（3）的条目：拼在作者注**后面**。 */
        authorNoteBottom: List<String> = emptyList(),
        /**
         * 这些 identifier 的正文**不许**被卡里的 `system_prompt` / `post_history_instructions` 顶掉。
         * 来自预设里每条提示词的 `forbid_overrides`（夹具 `P0-5-forbid-overrides`）。
         */
        forbidOverrideIds: Set<String> = emptySet(),
        /**
         * 出口条目（世界书 `position = outlet`）：`{{outlet::名字}}` 会被替换成这些正文，
         * `\n` 分隔。原版这一条走 `macros.js` 的 `{{outlet::(.+?)}}`（夹具 `P1-3-outlet`）。
         */
        outlets: Map<String, List<String>> = emptyMap(),
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val charName = character.name

        for (entry in order) {
            if (!entry.enabled) continue
            when (entry.identifier) {
                Id.MAIN, Id.NSFW, Id.JAILBREAK, Id.ENHANCE_DEFINITIONS -> {
                    // 卡里的 system_prompt / post_history_instructions 会**顶替**预设里
                    // 对应那一段的正文（原版 openai.js:1498 与 :1507，两处都先看
                    // forbid_overrides 与"该提示词是否被禁用"；我们这两条默认都不成立）。
                    // `forbid_overrides` 的段：卡里的覆盖值直接不用（正文回落到预设模板）
                    val forbidden = entry.identifier in forbidOverrideIds
                    val override = when {
                        forbidden -> ""
                        entry.identifier == Id.MAIN -> character.systemPrompt
                        entry.identifier == Id.JAILBREAK -> character.postHistoryInstructions
                        else -> ""
                    }
                    val content = override.ifBlank { prompts[entry.identifier].orEmpty() }
                    if (content.isBlank()) continue
                    messages += system(substitute(content, charName, userName, outlets))
                }

                Id.PERSONA_DESCRIPTION ->
                    if (personaDescription.isNotBlank()) {
                        messages += system(substitute(personaDescription, charName, userName, outlets))
                    }

                Id.WORLD_INFO_BEFORE ->
                    if (worldInfo.beforeChar.isNotBlank()) messages += system(worldInfo.beforeChar)

                Id.CHAR_DESCRIPTION ->
                    if (character.description.isNotBlank()) {
                        messages += system(substitute(character.description, charName, userName, outlets))
                    }

                Id.CHAR_PERSONALITY ->
                    if (character.personality.isNotBlank()) {
                        val formatted = SillyTavernDefaults.PERSONALITY_FORMAT
                            .replace("{{personality}}", character.personality)
                        messages += system(substitute(formatted, charName, userName, outlets))
                    }

                Id.SCENARIO ->
                    if (character.scenario.isNotBlank()) {
                        val formatted = SillyTavernDefaults.SCENARIO_FORMAT
                            .replace("{{scenario}}", character.scenario)
                        messages += system(substitute(formatted, charName, userName, outlets))
                    }

                Id.WORLD_INFO_AFTER ->
                    if (worldInfo.afterChar.isNotBlank()) messages += system(worldInfo.afterChar)

                Id.DIALOGUE_EXAMPLES -> {
                    // 每一"块"示例都自带一个 `[Example Chat]` 标记：世界书里 position=EMTop/EMBottom
                    // 的条目各算一块，卡里的 mes_example 算一块（夹具 P1-3-example-injection）。
                    fun block(raw: String) {
                        val parsed = ExampleDialogue.parse(raw, charName, userName)
                        if (parsed.isEmpty()) return
                        messages += system(SillyTavernDefaults.EXAMPLE_CHAT)
                        parsed.forEach { messages += system(it) }
                    }
                    worldInfo.exampleTop.forEach { block(it) }
                    block(character.messageExample)
                    worldInfo.exampleBottom.forEach { block(it) }
                }

                Id.CHAT_HISTORY -> {
                    val blockStart = messages.size
                    messages += newChatMarker()
                    // 历史消息原版也会逐条去掉 \r（"waste of tokens"，见 openai.js），这里照做
                    messages += history.map { it.copy(content = it.content.replace("\r", "")) }
                    // 作者注：插进**这一段历史**里（原版是往倒序的历史数组里 splice(depth)）。
                    // 位置 = 末尾往回 [AuthorNote.depth] 条；最少落在标记之后（夹具里它从不出现在
                    // `[Start a new Chat]` 之前）。
                    val note = authorNote
                    if (note != null) {
                        val userTurns = history.count { it.role == ChatRole.User }
                        // 原版把 ANTop / 作者注 / ANBottom 直接拼成 **一条** 注入文本
                        // （`world-info.js` 的 `ANWithWI`，只削掉首尾各一个换行），
                        // 再按 interval 决定插不插。夹具 P1-7-an-with-wi。
                        val merged = mergeAuthorNote(authorNoteTop, note.text, authorNoteBottom)
                        if (note.copy(text = merged).activeAt(userTurns)) {
                            val blockLen = messages.size - blockStart
                            val offset = (blockLen - note.depth).coerceIn(1, blockLen)
                            messages.add(
                                blockStart + offset,
                                ChatMessage(
                                    role = note.role,
                                    content = substitute(merged, charName, userName, outlets),
                                ),
                            )
                        }
                    }
                }

                // 还没实现的注入点（未知 identifier）：不静默插到别处，
                // 直接不发。宁可少一条，也不要打乱原版的顺序语义。
                else -> Unit
            }
        }

        return messages
    }

    /** 对应原版预设里 identifier = `main` 的提示词（默认模板）。 */
    public fun mainPrompt(characterName: String, userName: String): ChatMessage =
        system(substitute(PresetDefaults.MAIN, characterName, userName))

    /** 新聊天时插在聊天历史块最前面的标记。 */
    public fun newChatMarker(): ChatMessage =
        ChatMessage(role = ChatRole.System, content = SillyTavernDefaults.NEW_CHAT)

    private fun system(content: String): ChatMessage = ChatMessage(role = ChatRole.System, content = content)

    /**
     * 原版 `substituteParams()` 的最小实现：`{{char}}` / `{{charIfNotGroup}}` / `{{user}}`，
     * 外加拿掉 CRLF。
     *
     * `{{charIfNotGroup}}` 对单人卡就等于 `{{char}}`（群聊还没做）；预设里的 main 模板用的就是它。
     * CRLF 那条不是可选项：默认卡里描述是 CRLF，原版发出去的是 LF —— 已在夹具 diff 里逐字确认。
     */
    private fun substitute(
        text: String,
        characterName: String,
        userName: String,
        outlets: Map<String, List<String>> = emptyMap(),
    ): String {
        var out = text
            .replace("{{charIfNotGroup}}", characterName)
            .replace("{{char}}", characterName)
            .replace("{{user}}", userName)
            .replace("\r", "")
        // 出口：`{{outlet::名字}}` → 该出口激活的正文（`\n` 拼接）。原版在 macros.js 里做同一件事。
        // 原版对"没激活的出口"也是替换成空串（`getOutletPrompt(key) || ''`），所以这里不判 outlets 是否为空
        if (out.contains("{{outlet::")) {
            out = Regex("\\{\\{outlet::(.+?)\\}\\}").replace(out) { match ->
                outlets[match.groupValues[1].trim()]?.joinToString("\n").orEmpty()
            }
        }
        return out
    }

    /**
     * 拼作者注注入文本：`top… \n 正文 \n bottom…`，只削掉**首尾各一个**换行
     * （原版 `replace(/(^\n)|(\n$)/g, '')` 的语义 —— 不是 `trim()`，正文为空时中间的
     * 空行会留下，这一点在夹具里能看出来）。
     */
    private fun mergeAuthorNote(top: List<String>, text: String, bottom: List<String>): String {
        var merged = top.joinToString("\n") + "\n" + text + "\n" + bottom.joinToString("\n")
        if (merged.startsWith("\n")) merged = merged.substring(1)
        if (merged.endsWith("\n")) merged = merged.dropLast(1)
        return merged
    }

    /** `prompt_order` 里用到的 identifier。出处见原版 `PromptManager.js` 的默认提示词表。 */
    private object Id {
        const val MAIN = "main"
        const val NSFW = "nsfw"
        const val JAILBREAK = "jailbreak"
        const val ENHANCE_DEFINITIONS = "enhanceDefinitions"
        const val WORLD_INFO_BEFORE = "worldInfoBefore"
        const val WORLD_INFO_AFTER = "worldInfoAfter"
        const val PERSONA_DESCRIPTION = "personaDescription"
        const val CHAR_DESCRIPTION = "charDescription"
        const val CHAR_PERSONALITY = "charPersonality"
        const val SCENARIO = "scenario"
        const val DIALOGUE_EXAMPLES = "dialogueExamples"
        const val CHAT_HISTORY = "chatHistory"
    }
}
