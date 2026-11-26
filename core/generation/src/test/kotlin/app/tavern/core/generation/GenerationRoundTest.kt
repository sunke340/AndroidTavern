package app.tavern.core.generation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 提交权与收尾决策的纯 JVM 用例（T1–T21）。
 *
 * 这些用例守的是**数据一致性**，不是观感：谁能写库、写什么、界面怎么收。
 * 观感（布局/尺寸/滚动）不在这里，也不该在这里。
 */
class GenerationRoundTest {

    private fun round(
        roundId: Long = 1L,
        targetDbId: Long? = null,
        userRowDbId: Long? = null,
        userInput: String? = null,
        baseSwipes: List<String> = emptyList(),
        removedContent: String? = null,
    ) = GenerationRound(
        roundId = roundId,
        sessionId = "s1",
        targetDbId = targetDbId,
        userRowDbId = userRowDbId,
        userInput = userInput,
        baseSwipes = baseSwipes,
        removedContent = removedContent,
    )

    private fun facts(activeRoundId: Long? = 1L, sessionId: String? = "s1") =
        UiFacts(sessionId = sessionId, activeRoundId = activeRoundId)

    // ---- A. 提交权 ----------------------------------------------------------

    /** T1：finish 先抢，stop 输。 */
    @Test
    fun `T1 完成先抢到提交权时停止抢不到`() {
        val r = round()
        assertTrue(r.claimCommitRight(), "完成应该拿到提交权")
        assertFalse(r.claimCommitRight(), "停止不该拿到第二轮提交权")
    }

    /** T2：stop 先抢，finish 输。 */
    @Test
    fun `T2 停止先抢到提交权时完成抢不到`() {
        val r = round()
        assertTrue(r.claimCommitRight(), "停止应该拿到提交权")
        assertFalse(r.claimCommitRight(), "完成不该拿到第二轮提交权")
    }

    /** T3：真并发下每轮恰好一个赢家（CAS 竞争回归）。 */
    @Test
    fun `T3 八线程并发抢权每轮恰好一个赢家`() {
        repeat(500) { iteration ->
            val r = round(roundId = iteration.toLong())
            val winners = AtomicInteger(0)
            val start = CountDownLatch(1)
            val threads = (1..8).map {
                Thread {
                    start.await()
                    if (r.claimCommitRight()) winners.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, winners.get(), "第 $iteration 轮出现了 ${winners.get()} 个赢家")
        }
    }

    /** T4：close 之后一律抢不到（一次性）。 */
    @Test
    fun `T4 收尾关闭后不能再抢`() {
        val r = round()
        assertTrue(r.claimCommitRight())
        r.close()
        assertFalse(r.claimCommitRight(), "关闭后必须拒绝")
        assertEquals(CommitPhase.Closed, r.currentPhase)
    }

    // ---- B. planWrite：意图 → 写什么 ---------------------------------------

    /** T5：重新生成写非空正文 → 只可能是 Rewrite。 */
    @Test
    fun `T5 重新生成产出改写动作用原行号`() {
        val r = round(targetDbId = 42L, baseSwipes = listOf("旧正文"))
        val plan = r.planWrite("新正文", SettleReason.Completed)
        assertIs<WritePlan.RewriteReply>(plan)
        assertEquals(42L, plan.targetDbId)
        assertEquals("新正文", plan.content)
        assertEquals(listOf("旧正文", "新正文"), plan.swipes)
    }

    /** T6：重新生成 + 0 字 → 不写库（**不得**退化成追加一条新消息）。 */
    @Test
    fun `T6 重新生成零字时绝不追加新消息`() {
        val r = round(targetDbId = 42L, baseSwipes = listOf("旧正文"))
        assertEquals(WritePlan.Nothing, r.planWrite("", SettleReason.Stopped))
        assertEquals(WritePlan.Nothing, r.planWrite("   ", SettleReason.Completed))
    }

    /** T7：新消息 → 追加。 */
    @Test
    fun `T7 新消息产出追加动作`() {
        val plan = round().planWrite("你好", SettleReason.Completed)
        assertIs<WritePlan.AppendReply>(plan)
        assertEquals("你好", plan.content)
    }

    /** T8：0 字停止 / 空完成 → 不写库。 */
    @Test
    fun `T8 零字不写库`() {
        val r = round()
        assertEquals(WritePlan.Nothing, r.planWrite("", SettleReason.Stopped))
        assertEquals(WritePlan.Nothing, r.planWrite("\n\t", SettleReason.Completed))
    }

    /** T9：生成失败且本轮写过用户消息 → 撤那一条。 */
    @Test
    fun `T9 生成失败撤掉本轮用户消息`() {
        val plan = round(userRowDbId = 7L, userInput = "原文").planWrite(
            "",
            SettleReason.Failed,
        )
        assertIs<WritePlan.DeleteUserRow>(plan)
        assertEquals(7L, plan.dbId)
    }

    /** T10：生成失败但没有本轮用户消息（继续 / 重新生成）→ 不写库。 */
    @Test
    fun `T10 继续或重新生成失败时不写库`() {
        assertEquals(WritePlan.Nothing, round().planWrite("", SettleReason.Failed))
        assertEquals(
            WritePlan.Nothing,
            round(targetDbId = 9L, baseSwipes = listOf("旧")).planWrite("半截", SettleReason.Failed),
        )
    }

    /** T11：停止且有半截内容 → 与正常完成同一条路。 */
    @Test
    fun `T11 停止有内容时按正常路径落库`() {
        assertIs<WritePlan.AppendReply>(round().planWrite("半截", SettleReason.Stopped))
        assertIs<WritePlan.RewriteReply>(
            round(targetDbId = 3L, baseSwipes = listOf("旧")).planWrite("半截", SettleReason.Stopped),
        )
    }

    // ---- C. planEffects：结果 → 界面 ---------------------------------------

    /** T12：追加成功、仍是当前轮 → 行 + 清生成中 + 状态 + 刷会话列表。 */
    @Test
    fun `T12 追加成功的界面效果`() {
        val r = round()
        val plan = r.planWrite("你好", SettleReason.Completed)
        val effects = r.planEffects(facts(), plan, WriteResult.Ok(11L), "完成")
        val row = assertIs<RowAction.Append>(effects.row)
        assertEquals("你好", row.content)
        assertEquals(listOf("你好"), row.swipes)
        assertEquals(0, row.swipeId)
        assertTrue(effects.clearInflight)
        assertTrue(effects.refreshSessions)
        assertEquals(SettleStatus.Ok("完成"), effects.status)
        assertNull(effects.restoreInput)
    }

    /** T13：追加成功但用户已经切走 → 行不进别人的列表，会话列表照刷。 */
    @Test
    fun `T13 换会话后不往新会话塞行`() {
        val r = round()
        val plan = r.planWrite("你好", SettleReason.Completed)
        val effects = r.planEffects(
            facts(activeRoundId = null, sessionId = "s2"),
            plan,
            WriteResult.Ok(11L),
            "完成",
        )
        assertEquals(RowAction.None, effects.row)
        assertFalse(effects.clearInflight, "别人会话的生成中状态不归它清")
        assertNull(effects.status)
        assertTrue(effects.refreshSessions, "库里多了一条，会话列表必须刷")
    }

    /** T14：库里写完之后已经开了新一轮 → 行仍要写（库里有），但界面事实不许碰。 */
    @Test
    fun `T14 旧轮不碰新轮界面但仍提交行`() {
        val r = round(roundId = 1L)
        val plan = r.planWrite("你好", SettleReason.Completed)
        val effects = r.planEffects(facts(activeRoundId = 99L), plan, WriteResult.Ok(11L), "完成")
        assertIs<RowAction.Append>(effects.row)
        assertFalse(effects.clearInflight, "旧轮不许清新轮的生成中")
        assertNull(effects.status, "旧轮不许改新轮的状态栏")
    }

    /** T15：改写成功 → 只可能是就地改写，绝不追加。 */
    @Test
    fun `T15 重新生成成功只产出就地改写`() {
        val r = round(targetDbId = 42L, baseSwipes = listOf("旧正文"))
        val plan = r.planWrite("新正文", SettleReason.Completed)
        val effects = r.planEffects(facts(), plan, WriteResult.Ok(42L), "完成")
        val row = assertIs<RowAction.Rewrite>(effects.row)
        assertEquals(42L, row.dbId)
        assertEquals(listOf("旧正文", "新正文"), row.swipes)
        assertEquals(1, row.swipeId)
        assertTrue(effects.clearInflight)
    }

    /** T16：保存失败 → 不写行、不删任何东西、如实提示。 */
    @Test
    fun `T16 保存失败绝不删除也不写行`() {
        val r = round(userRowDbId = 7L)
        val plan = r.planWrite("你好", SettleReason.Completed)
        val effects = r.planEffects(
            facts(),
            plan,
            WriteResult.Failed("disk I/O"),
            "完成",
        )
        assertEquals(RowAction.None, effects.row)
        assertEquals(SettleStatus.SaveFailed, effects.status)
        assertTrue(effects.clearInflight)
        assertFalse(effects.refreshSessions, "没写成功就不该刷列表")
        assertNull(effects.restoreInput, "保存失败不该动输入框")
    }

    /** T17：重新生成保存失败 → 旧正文放回界面，库里那一行没动过。 */
    @Test
    fun `T17 重新生成保存失败时旧正文回到界面`() {
        val r = round(
            targetDbId = 42L,
            baseSwipes = listOf("旧正文"),
            removedContent = "旧正文",
        )
        val plan = r.planWrite("新正文", SettleReason.Completed)
        val effects = r.planEffects(facts(), plan, WriteResult.Failed("行不存在"), "完成")
        val row = assertIs<RowAction.RestoreRemoved>(effects.row)
        assertEquals("旧正文", row.content)
        assertEquals(listOf("旧正文"), row.swipes)
        assertEquals(SettleStatus.SaveFailed, effects.status)
    }

    /** T18：撤销用户消息成功 → 移除行 + 还原输入框。 */
    @Test
    fun `T18 生成失败撤销成功时移除用户行并还原输入`() {
        val r = round(userRowDbId = 7L, userInput = "原文")
        val plan = r.planWrite("", SettleReason.Failed)
        val effects = r.planEffects(facts(), plan, WriteResult.Ok(7L), "失败（HTTP 500）")
        val row = assertIs<RowAction.RemoveUserRow>(effects.row)
        assertEquals(7L, row.dbId)
        assertEquals("原文", effects.restoreInput)
        assertEquals(SettleStatus.Ok("失败（HTTP 500）"), effects.status)
        assertTrue(effects.refreshSessions)
    }

    /** T19：撤销失败 → **不许假装撤掉**，行留着，输入照样还原，如实提示。 */
    @Test
    fun `T19 撤销失败时保留用户行并提示`() {
        val r = round(userRowDbId = 7L, userInput = "原文")
        val plan = r.planWrite("", SettleReason.Failed)
        val effects = r.planEffects(facts(), plan, WriteResult.Failed("db locked"), "失败")
        assertEquals(RowAction.None, effects.row)
        assertEquals("原文", effects.restoreInput)
        assertEquals(SettleStatus.RollbackFailed, effects.status)
        assertFalse(effects.refreshSessions)
    }

    /** T20：重新生成 0 字停止 → 无行写入、旧正文回到界面、清生成中。 */
    @Test
    fun `T20 重新生成零字停止时旧正文原样回来`() {
        val r = round(
            targetDbId = 42L,
            baseSwipes = listOf("旧正文"),
            removedContent = "旧正文",
        )
        val plan = r.planWrite("", SettleReason.Stopped)
        val effects = r.planEffects(facts(), plan, WriteResult.Skipped, "已停止")
        val row = assertIs<RowAction.RestoreRemoved>(effects.row)
        assertEquals("旧正文", row.content)
        assertTrue(effects.clearInflight)
        assertEquals(SettleStatus.Ok("已停止"), effects.status)
    }

    /** T21：换会话之后撤销成功 → 不动新会话的输入框。 */
    @Test
    fun `T21 换会话后撤销不动新会话输入框`() {
        val r = round(userRowDbId = 7L, userInput = "原文")
        val plan = r.planWrite("", SettleReason.Failed)
        val effects = r.planEffects(
            facts(activeRoundId = null, sessionId = "s2"),
            plan,
            WriteResult.Ok(7L),
            "失败",
        )
        assertEquals(RowAction.None, effects.row)
        assertNull(effects.restoreInput)
        assertTrue(effects.refreshSessions)
    }
}
