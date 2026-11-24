package app.tavern.core.lorebook

/**
 * `LorebookScanState` 的文本编解码（sticky / cooldown 的跨启动持久化）。
 *
 * 为什么要它：计时状态原本只活在内存里（`ChatViewModel.worldInfoStates`），App 一重启，
 * 还在生效的 sticky 条目就提前失效了。原版把它写进 `chat_metadata.timedWorldInfo`
 * （`world-info.js:604`），跟着聊天走；我们在本地库旁边存一份文本，语义一致。
 *
 * 格式：一行一条 `S<US>id<US>start<US>end<US>protected`（`C` 开头的是 cooldown）。
 * `US` 是 `\u001F`：条目 id 是 `world.uid` 这种字符串，理论上含分隔符的概率为零，
 * 用控制字符连转义都不用。空串 / 坏行一律跳过 —— 计时状态丢了只是效果提前结束，
 * **绝不能因为它把整场聊天读崩**。
 */
public object LorebookScanStateCodec {

    private const val SEP = '\u001F'
    private const val STICKY = "S"
    private const val COOLDOWN = "C"

    public fun encode(state: LorebookScanState): String = buildString {
        state.sticky.forEach { (id, effect) -> appendLine(line(STICKY, id, effect)) }
        state.cooldown.forEach { (id, effect) -> appendLine(line(COOLDOWN, id, effect)) }
    }

    public fun decode(text: String?): LorebookScanState {
        if (text.isNullOrBlank()) return LorebookScanState()
        val sticky = LinkedHashMap<String, LorebookTimedEffect>()
        val cooldown = LinkedHashMap<String, LorebookTimedEffect>()
        text.lineSequence().forEach { raw ->
            val parts = raw.split(SEP)
            if (parts.size != 5) return@forEach
            val start = parts[2].toIntOrNull() ?: return@forEach
            val end = parts[3].toIntOrNull() ?: return@forEach
            val effect = LorebookTimedEffect(start = start, end = end, protected = parts[4] == "1")
            when (parts[0]) {
                STICKY -> sticky[parts[1]] = effect
                COOLDOWN -> cooldown[parts[1]] = effect
                else -> Unit
            }
        }
        return LorebookScanState(sticky = sticky, cooldown = cooldown)
    }

    private fun line(kind: String, id: String, effect: LorebookTimedEffect): String = buildString {
        append(kind).append(SEP)
        append(id).append(SEP)
        append(effect.start).append(SEP)
        append(effect.end).append(SEP)
        append(if (effect.protected) "1" else "0")
    }
}
