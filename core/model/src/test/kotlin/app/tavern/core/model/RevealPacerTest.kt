package app.tavern.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 推进器的三条硬规则（`docs/plan-reveal-pacing.md` §3.3）：
 * 追平不累计时间、停顿恢复丢余量、单帧最多按 50ms 计。
 */
class RevealPacerTest {
    private val ms = 1_000_000L

    @Test
    fun `第一帧只立基准不推进`() {
        val pacer = RevealPacer()
        assertEquals(0, pacer.step(nowNanos = 1_000 * ms, ratePerSec = 70f, revealed = 0, received = 100))
        assertTrue(!pacer.needsBaseline())
    }

    @Test
    fun `按速率推进且不越过已收到的数量`() {
        val pacer = RevealPacer()
        pacer.markBaseline(0)
        // 每一帧最多按 50ms 计：中档 35cp/s → 每帧 1.75 个码点，小数余量留着
        var shown = pacer.step(50 * ms, 35f, revealed = 0, received = 200)
        assertEquals(1, shown)
        shown = pacer.step(100 * ms, 35f, shown, received = 200)
        assertEquals(3, shown) // 0.75 + 1.75 = 2.5 → 再吐 2 个
        // 只收到 5 个：不能越过它
        // 一帧最多 50ms × 70cp/s = 3 个，但只收到 2 个 → 夹到 2
        val capped = RevealPacer().apply { markBaseline(0) }
        assertEquals(2, capped.step(1_000 * ms, 70f, revealed = 0, received = 2))
    }

    @Test
    fun `追平之后不累计时间，新字到达也不暴吐`() {
        val pacer = RevealPacer()
        pacer.markBaseline(0)
        var shown = pacer.step(1_000 * ms, 70f, revealed = 0, received = 3)
        assertEquals(3, shown) // 追平（1 秒按 50ms 计 → 3 个）
        // 在追平状态待 5 秒（每帧都调），然后一次来 10 个新字
        var t = 1_000L
        repeat(50) {
            t += 100
            shown = pacer.step(t * ms, 70f, shown, received = 3)
        }
        assertEquals(3, shown)
        val afterArrival = pacer.step((t + 20) * ms, 70f, shown, received = 13)
        // 20ms × 70cp/s ≈ 1.4 个码点 —— 远不是"10 个一次吐出来"
        assertTrue(afterArrival - shown in 1..2, "追平期攒了额度：${afterArrival - shown}")
    }

    @Test
    fun `停顿超过 1 秒当暂停恢复，不补齐`() {
        val pacer = RevealPacer()
        pacer.markBaseline(0)
        var shown = pacer.step(50 * ms, 35f, revealed = 0, received = 500)
        assertEquals(1, shown)
        // 停了 3 秒
        shown = pacer.step(3_050 * ms, 35f, shown, received = 500)
        assertEquals(1, shown, "暂停后的那一帧不该补")
        // 恢复后的下一帧照常按 35cp/s（50ms → 1.75，余量 0.75+1.75=2.5 → 吐 2 个）
        val next = pacer.step(3_100 * ms, 35f, shown, received = 500)
        assertEquals(2, next) // 暂停时余量已经丢掉，这一帧只吐 1.75 → 1 个
    }

    @Test
    fun `单帧最多按 50ms 计（掉帧时宁可慢也不暴吐）`() {
        val pacer = RevealPacer()
        pacer.markBaseline(0)
        // 500ms 后才来下一帧，但仍按 50ms 算：70cp/s × 0.05s = 3.5 → 3
        val shown = pacer.step(500 * ms, 70f, revealed = 0, received = 500)
        assertEquals(3, shown)
    }

    @Test
    fun `换档立即生效（速率每帧现读）`() {
        val pacer = RevealPacer()
        pacer.markBaseline(0)
        var shown = pacer.step(50 * ms, 15f, revealed = 0, received = 500)
        assertEquals(0, shown) // 15cp/s × 0.05s = 0.75（不足一个，留着）
        shown = pacer.step(100 * ms, 70f, shown, received = 500)
        assertEquals(4, shown) // 0.75 + 70×0.05 = 4.25 → 4
    }

    @Test
    fun `看门狗能看出"有账但没前进"`() {
        val pacer = RevealPacer()
        pacer.markBaseline(0)
        pacer.idle(5_000 * ms)
        assertEquals(5_000 * ms, pacer.stalledFor(5_000 * ms))
        val shown = pacer.step(5_100 * ms, 35f, revealed = 0, received = 100)
        assertTrue(shown > 0)
        assertEquals(0, pacer.stalledFor(5_100 * ms))
    }
}
