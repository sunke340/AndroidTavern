package app.tavern.core.lorebook

/**
 * sticky / cooldown 的计时记录。原版把它存在 `chat_metadata.timedWorldInfo` 里
 * （`world-info.js:604`），也就是**跟着聊天一起持久化**的状态，所以这里做成可进可出的值对象。
 *
 * `start` / `end` 都是"聊天条数"（不是消息 id）：效果从 `start` 条开始，到 `end` 条为止。
 */
public class LorebookTimedEffect(
    public val start: Int,
    public val end: Int,
    public val protected: Boolean = false,
)

/**
 * 一次扫描要用到的、跨轮持久化的状态。为空表示没有任何 sticky/cooldown 在生效。
 *
 * key 是 [LorebookEntry.id]（`world.uid`），与原版存的 map key 一致。
 */
public class LorebookScanState(
    public val sticky: Map<String, LorebookTimedEffect> = emptyMap(),
    public val cooldown: Map<String, LorebookTimedEffect> = emptyMap(),
)

/** 按深度注入聊天的条目（原版 `WIDepthEntries`）。 */
public class LorebookDepthEntry(
    public val depth: Int,
    /** 0=system，1=user，2=assistant。 */
    public val role: Int,
    public val entries: List<String>,
)

/**
 * 扫描结果。`beforeChar` / `afterChar` 已经按插入序号拼好（`\n` 分隔），可直接塞进提示词。
 */
public class LorebookScanResult(
    public val beforeChar: String = "",
    public val afterChar: String = "",
    public val depthEntries: List<LorebookDepthEntry> = emptyList(),
    public val exampleTop: List<String> = emptyList(),
    public val exampleBottom: List<String> = emptyList(),
    public val authorNoteTop: List<String> = emptyList(),
    public val authorNoteBottom: List<String> = emptyList(),
    /** 本轮真正进了提示词的条目，按激活顺序。 */
    public val activated: List<LorebookEntry> = emptyList(),
    /** 更新后的计时状态，下一轮要原样传回来。 */
    public val state: LorebookScanState = LorebookScanState(),
)
