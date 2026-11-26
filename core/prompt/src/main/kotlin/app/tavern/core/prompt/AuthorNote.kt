package app.tavern.core.prompt

import app.tavern.core.model.ChatRole

/**
 * 作者注（原版叫 Author's Note / 浮动提示）。
 *
 * 它是**按间隔插进聊天历史**的一段话（不是系统提示里的固定一段）：原版把它塞进
 * `extension_prompts['2_floating_prompt']`，每一轮由 `authors-note.js` 的 `setFloatingPrompt()`
 * 按 `interval / position / depth / role` 重新决定插不插、插哪儿。这里只实现最常用的那一档
 * （`position = IN_CHAT`，按深度插进历史），位置规则由夹具 `P1-7-an-prompt` /
 * `P1-7-an-depth-2` 钉住。
 *
 * @param text 正文（支持 `{{char}}` / `{{user}}` 宏）
 * @param depth 从**末尾**往回数几条：插进去以后，它后面正好有 [depth] 条历史消息
 * @param role 插入消息的角色（原版 `note_role`：system / user / assistant）
 * @param interval 每 N 条用户消息插一次；1 = 每轮都插
 */
public data class AuthorNote(
    public val text: String,
    public val depth: Int = 4,
    public val role: ChatRole = ChatRole.System,
    public val interval: Int = 1,
    /**
     * 放哪（原版 `extension_prompt_types`）：
     * `0` = IN_PROMPT（主提示词之后、角色描述之前，夹具 `P1-7-an-pos-0`）、
     * `1` = IN_CHAT（按 [depth] 插进聊天历史，默认，夹具 `P1-7-an-depth-2`）、
     * `2` = BEFORE_PROMPT（**主提示词之前**，夹具 `P1-7-an-pos-2`）。
     */
    public val position: Int = IN_CHAT,
) {
    public companion object {
        public const val IN_PROMPT: Int = 0
        public const val IN_CHAT: Int = 1
        public const val BEFORE_PROMPT: Int = 2
    }

    /**
     * 这一轮插不插。规则照原版 `setFloatingPrompt()`：
     * `messagesTillInsertion = lastMessageNumber % interval`（用户消息条数），等于 0 才插。
     */
    public fun activeAt(userMessageCount: Int): Boolean {
        if (text.isBlank()) return false
        if (interval <= 0) return false
        // 原版对 interval == 1 有特判：`if (interval === 1) lastMessageNumber = 1;`
        // —— 也就是"每轮都插"，连一句用户消息都没有时也插（夹具 P1-7-an-prompt 就是这种：
        // 历史里只有一条助手消息，作者注照样在）。
        if (interval == 1) return true
        if (userMessageCount <= 0) return false
        return userMessageCount % interval == 0
    }
}
