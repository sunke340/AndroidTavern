package app.tavern.core.generation

import java.util.concurrent.atomic.AtomicReference

/**
 * 一轮生成的**提交权**与收尾决策。
 *
 * 为什么单开一个模块：这里的每一条都是纯逻辑（谁能写库、写什么、界面怎么收），
 * 放进 `:app` 就只能等 CI；放这里，`tools/test-core.sh` 在本机就能跑并发竞争与决策表。
 *
 * 四条纪律（改这里之前先读，评审逐条对）：
 * 1. **`cancel()` 只能在 [claimCommitRight] 成功之后调用**。先取消再抢权，会让已经写完库的
 *    收尾协程在 `withContext` 返回时抛 `CancellationException`，界面更新被跳过 —— 表现是
 *    "回复在库里、界面没有，重进会话又出现"。
 * 2. **抢不到权的一方什么都不做**（不写库、不改界面、不取消 job）。
 * 3. 清"生成中"、写状态栏这类**界面事实**，只在 `UiFacts.activeRoundId == roundId` 时允许。
 * 4. 往会话里写行，只在 `UiFacts.sessionId == sessionId` 时允许。
 *
 * 纪律 1/2 保证"一轮恰好一次提交"，3/4 保证"旧轮碰不到新轮的界面"。
 */
public class GenerationRound(
    /** 轮次号：自增，只用于判断"我还是不是当前那一轮"。 */
    public val roundId: Long,
    /** 这一轮归属的会话。 */
    public val sessionId: String,
    /**
     * 这一轮在界面上的**行身份**（组合期稳定）。核心不解释它，只在收尾时原样交回给调用方。
     *
     * 占位 / 流式 / 定稿三态用同一个 key，LazyColumn 才不会在落库那一帧换条目。
     */
    public val replyUiKey: String,
    /** 重新生成的目标行（`messages.id`）；null = 这一轮落一条**新**消息。 */
    public val targetDbId: Long? = null,
    /** 这一轮刚写进库的用户消息行号；发送时为 null（还没落库），落库成功后由调用方补一次。 */
    public var userRowDbId: Long? = null,
    /** 这一轮之前输入框里的原文（生成失败时还原）。继续 / 重新生成为 null。 */
    public val userInput: String? = null,
    /** 重新生成前的 swipe 备选（新的那条接在后面）。 */
    public val baseSwipes: List<String> = emptyList(),
    /**
     * **P0 专用**：重新生成开场时把原来那条从界面撤下去了，收尾不成功要放回来。
     * P1 换成行模型后就地改写，这个字段删除。
     */
    public val removedContent: String? = null,
) {

    private val phase: AtomicReference<CommitPhase> = AtomicReference(CommitPhase.Open)

    /** 当前相位（只读，给日志与断言用；**不要**拿它当锁）。 */
    public val currentPhase: CommitPhase
        get() = phase.get()

    /**
     * 抢提交权。**同一轮只有一个线程能成功**（CAS 一次性）。
     *
     * @return true = 你是这一轮唯一的提交者，必须由你在 `finally` 里 [close]。
     */
    public fun claimCommitRight(): Boolean =
        phase.compareAndSet(CommitPhase.Open, CommitPhase.Writing)

    /** 收尾结束（成功、失败、放弃都算）。只有拿到权的一方调用。 */
    public fun close() {
        phase.set(CommitPhase.Closed)
    }

    /**
     * 这一轮该写什么库。**纯函数**，不碰 IO。
     *
     * 关键：重新生成（[targetDbId] 非空）只会产出 [WritePlan.RewriteReply] ——
     * "改写原来那一行"在**类型上**就不可能退化成"插一条新消息"。
     */
    public fun planWrite(text: String, reason: SettleReason): WritePlan {
        if (reason == SettleReason.Failed) {
            // 生成失败：把这一轮刚写的用户消息撤掉（没有就什么都不写）。
            // 注意：**保存失败**不走这条（那是 reason=Completed/Stopped + WriteResult.Failed）。
            return userRowDbId?.let { WritePlan.DeleteUserRow(it) } ?: WritePlan.Nothing
        }
        if (text.isBlank()) {
            // 0 字停止 / 空完成：不落库。重新生成时旧正文原样（库里那一行根本没动）。
            return WritePlan.Nothing
        }
        return if (targetDbId != null) {
            WritePlan.RewriteReply(
                targetDbId = targetDbId,
                content = text,
                swipes = baseSwipes + text,
            )
        } else {
            WritePlan.AppendReply(text)
        }
    }

    /**
     * 落库之后界面怎么收。**纯函数**，不碰 IO，也不相信调用方"还是当前轮"——
     * 归属一律用传进来的 [facts] 现算（写完库到提交界面之间，用户可能已经切会话或开了新一轮）。
     *
     * @param okStatus 成功时状态栏的文案（失败/0 字时用 [SettleStatus] 表达，文案留在界面层）。
     */
    public fun planEffects(
        facts: UiFacts,
        plan: WritePlan,
        result: WriteResult,
        okStatus: String,
    ): SettleEffects {
        val mine = facts.activeRoundId == roundId
        val sameSession = facts.sessionId == sessionId

        fun status(kind: SettleStatus?): SettleStatus? = if (mine) kind else null
        fun row(action: RowAction): RowAction = if (sameSession) action else RowAction.None

        return when (plan) {
            WritePlan.Nothing -> SettleEffects(
                // 0 字停止 / 空完成：没有行要写。重新生成失败过（撤下过原行）就放回来。
                row = row(restoreRemovedOrNone()),
                clearInflight = mine,
                status = status(SettleStatus.Ok(okStatus)),
            )

            is WritePlan.AppendReply -> when (result) {
                is WriteResult.Ok -> SettleEffects(
                    row = row(appendAction(plan.content)),
                    clearInflight = mine,
                    status = status(SettleStatus.Ok(okStatus)),
                    refreshSessions = true,
                )
                // 保存失败：**绝不删任何东西**，界面停在旧状态 + 一句提示。
                else -> SettleEffects(
                    clearInflight = mine,
                    status = status(SettleStatus.SaveFailed),
                )
            }

            is WritePlan.RewriteReply -> when (result) {
                is WriteResult.Ok -> SettleEffects(
                    row = row(
                        RowAction.Rewrite(
                            dbId = plan.targetDbId,
                            content = plan.content,
                            swipes = plan.swipes,
                            swipeId = plan.swipes.lastIndex.coerceAtLeast(0),
                        ),
                    ),
                    clearInflight = mine,
                    status = status(SettleStatus.Ok(okStatus)),
                    refreshSessions = true,
                )
                // 保存失败：旧正文**原样**（库里那一行没动过），P0 把撤下去的那条放回界面。
                else -> SettleEffects(
                    row = row(restoreRemovedOrNone()),
                    clearInflight = mine,
                    status = status(SettleStatus.SaveFailed),
                )
            }

            is WritePlan.DeleteUserRow -> when (result) {
                is WriteResult.Ok -> SettleEffects(
                    row = row(RowAction.RemoveUserRow(plan.dbId)),
                    restoreInput = if (sameSession) userInput else null,
                    clearInflight = mine,
                    status = status(SettleStatus.Ok(okStatus)),
                    refreshSessions = true,
                )
                // 撤销失败：**不许假装撤掉**（重进会话它还会出现），行留着、输入照样还原、如实提示。
                else -> SettleEffects(
                    restoreInput = if (sameSession) userInput else null,
                    clearInflight = mine,
                    status = status(SettleStatus.RollbackFailed),
                )
            }
        }
    }

    private fun appendAction(content: String): RowAction = RowAction.Append(
        content = content,
        swipes = listOf(content),
        swipeId = 0,
    )

    /** P0：重新生成撤下过原行、这一轮又没写成 → 放回来。P1 起 `removedContent` 恒为 null。 */
    private fun restoreRemovedOrNone(): RowAction {
        val removed = removedContent ?: return RowAction.None
        return RowAction.RestoreRemoved(
            content = removed,
            swipes = baseSwipes,
            swipeId = baseSwipes.lastIndex.coerceAtLeast(0),
        )
    }
}

/** 提交相位。`Open → Writing → Closed`，单向、一次性。 */
public enum class CommitPhase {
    /** 还没人收尾：谁都可以抢。 */
    Open,

    /** 有人抢到了、正在写库：**不许**取消这一轮的 job。 */
    Writing,

    /** 收尾已完成（成功或失败）：再抢一律失败。 */
    Closed,
}

/** 这一轮为什么收尾。决定"写什么"，不决定"谁写"。 */
public enum class SettleReason {
    /** 正常写完（收到终止事件）。 */
    Completed,

    /** 用户点了停止（可能只有半截）。 */
    Stopped,

    /** 生成失败（HTTP / 网络 / 异常）。**不是**保存失败。 */
    Failed,
}

/** 要写库的动作。 */
public sealed interface WritePlan {
    /** 不写库（0 字停止、空完成、继续/重新生成失败）。 */
    public data object Nothing : WritePlan

    /** 落一条新的助手消息。 */
    public data class AppendReply(public val content: String) : WritePlan

    /** 改写**原来那一行**（重新生成）：正文与 swipe 备选一次写完。 */
    public data class RewriteReply(
        public val targetDbId: Long,
        public val content: String,
        public val swipes: List<String>,
    ) : WritePlan

    /** 撤掉这一轮刚写的用户消息（生成失败）。 */
    public data class DeleteUserRow(public val dbId: Long) : WritePlan
}

/** 写库结果。 */
public sealed interface WriteResult {
    /** 没有写库动作。 */
    public data object Skipped : WriteResult

    /** 写成功（追加时有新行号）。 */
    public data class Ok(public val dbId: Long? = null) : WriteResult

    /** 写失败（抛异常 / 目标行不存在）。**不许**当成成功，也**不许**触发任何删除。 */
    public data class Failed(public val reason: String) : WriteResult
}

/** 界面这一行该怎么办。P0 由 `transcript` 承接，P1 换成行模型；语义不变。 */
public sealed interface RowAction {
    /** 行不变。 */
    public data object None : RowAction

    /** 追加一条助手消息。 */
    public data class Append(
        public val content: String,
        public val swipes: List<String>,
        public val swipeId: Int,
    ) : RowAction

    /** 就地改写原来那一行（重新生成成功）。 */
    public data class Rewrite(
        public val dbId: Long,
        public val content: String,
        public val swipes: List<String>,
        public val swipeId: Int,
    ) : RowAction

    /** 移除这一轮的用户消息（生成失败且撤销成功）。 */
    public data class RemoveUserRow(public val dbId: Long) : RowAction

    /** **P0 专用**：重新生成开场撤下过原行、这一轮没写成 → 放回来。P1 删除。 */
    public data class RestoreRemoved(
        public val content: String,
        public val swipes: List<String>,
        public val swipeId: Int,
    ) : RowAction
}

/** 判定归属用的界面快照（只有这两个量参与判断）。 */
public data class UiFacts(
    public val sessionId: String?,
    public val activeRoundId: Long?,
)

/** 收尾时状态栏该显示什么。文案留在界面层，核心只给类别。 */
public sealed interface SettleStatus {
    /** 正常收尾（文案由调用方给）。 */
    public data class Ok(public val text: String) : SettleStatus

    /** 回复没入库：界面停在旧状态，绝不回滚或删除。 */
    public data object SaveFailed : SettleStatus

    /** 用户消息撤销失败：行留着，如实提示。 */
    public data object RollbackFailed : SettleStatus
}

/**
 * 一次收尾要对界面做的**全部**改动。
 *
 * 为什么要一次算完：分几次 `_state.update` 会露出"半新半旧"的中间帧
 * （例如行已经加上、`streamingText` 还没清），在长气泡上就是一闪。
 */
public data class SettleEffects(
    public val row: RowAction = RowAction.None,
    public val restoreInput: String? = null,
    public val clearInflight: Boolean = false,
    public val status: SettleStatus? = null,
    public val refreshSessions: Boolean = false,
)
