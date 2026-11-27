package app.tavern.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 快进速率的三条判据（`docs/plan-reveal-pacing.md` §F.4）：
 * 按点下去那一刻的欠账算固定速率、大欠账封顶、没有欠账不启动。
 *
 * 这里要守住的是**那个反馈环**：速率只能由「点下去那一刻的欠账」决定，
 * 不能每帧拿"当前欠账"重算 —— 模型还在吐字时那样会退化成实时打印（v183 真机）。
 */
class RevealCatchUpTest {
    @Test
    fun `小欠账按时长算，不做成瞬移`() {
        // 20 个字 / 0.5s = 40cp/s —— 比档位快，但仍然是"一个一个来"
        assertEquals(40f, RevealCatchUp.rateFor(20))
    }

    @Test
    fun `速率随欠账线性增长，但封顶`() {
        val mid = RevealCatchUp.rateFor(500)
        assertTrue(mid > RevealCatchUp.rateFor(100))
        // 5000 个字不该按 10000cp/s 糊上去：封顶在 MaxRate
        assertEquals(RevealCatchUp.MaxRate, RevealCatchUp.rateFor(5000))
        assertEquals(RevealCatchUp.MaxRate, RevealCatchUp.rateFor(100_000))
    }

    @Test
    fun `没有欠账就不启动`() {
        assertEquals(0f, RevealCatchUp.rateFor(0))
        assertEquals(0f, RevealCatchUp.rateFor(-3))
    }
}
