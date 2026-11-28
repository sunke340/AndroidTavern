package app.tavern.core.lorebook

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `scanWorldInfo(WorldInfoInputs)` —— 从 `ChatViewModel` 里抽出来的那一套（B2）。
 *
 * 钉的是**兼容行为**：卡书在前、全局书在后；本机禁用/强制开启；三级书选择；
 * 预算；以及"概率条目 = 随机"这件事必须显式（同 seed 可复现）。
 */
class WorldInfoPassTest {

    private fun entry(
        uid: Int,
        content: String,
        keys: List<String> = listOf("alpha"),
        order: Int = 100,
        probability: Int = 100,
        constant: Boolean = false,
    ) = LorebookEntry(
        world = "book",
        uid = uid,
        keys = keys,
        content = content,
        order = order,
        probability = probability,
        constant = constant,
    )

    private fun base(
        cardEntries: List<LorebookEntry> = emptyList(),
        globalBooks: List<GlobalBook> = emptyList(),
        globalEntriesByBook: Map<String, List<LorebookEntry>> = emptyMap(),
        onlyBookNames: Set<String>? = null,
        history: List<WorldInfoMessage> = listOf(WorldInfoMessage(isUser = true, text = "alpha 你好")),
    ) = WorldInfoInputs(
        cardEntries = cardEntries,
        globalBooks = globalBooks,
        globalEntriesByBook = globalEntriesByBook,
        onlyBookNames = onlyBookNames,
        charName = "角色",
        userName = "玩家",
        history = history,
        random = Random(11),
    )

    @Test
    fun `卡书在前、全局书在后（character_first）`() {
        val result = scanWorldInfo(
            base(
                cardEntries = listOf(entry(1, "卡里的")),
                globalBooks = listOf(GlobalBook("book", enabled = true)),
                globalEntriesByBook = mapOf("book" to listOf(entry(2, "全局的"))),
            ).copy(random = Random(11)),
        )
        assertEquals(listOf(1, 2), result.activated.map { it.uid })
    }

    @Test
    fun `本机禁用与强制开启`() {
        val disabled = scanWorldInfo(base(cardEntries = listOf(entry(1, "A"))).copy(disabledUids = setOf(1)))
        assertTrue(disabled.activated.isEmpty())

        // 卡里没开（解析时被标 disabled）+ 本机强制开启 → 翻回来
        val off = entry(2, "B").withDisabled(true)
        val forced = scanWorldInfo(base(cardEntries = listOf(off)).copy(forcedUids = setOf(2)))
        assertEquals(listOf(2), forced.activated.map { it.uid })
    }

    @Test
    fun `全局书按三级选择 - null 看自己的开关、空集一本都不带`() {
        val books = listOf(GlobalBook("on", enabled = true), GlobalBook("off", enabled = false))
        val entries = mapOf("on" to listOf(entry(1, "ON")), "off" to listOf(entry(2, "OFF")))

        val byOwnSwitch = scanWorldInfo(base(globalBooks = books, globalEntriesByBook = entries).copy(globalBooks = books))
        assertEquals(listOf(1), byOwnSwitch.activated.map { it.uid })

        val explicitNone = scanWorldInfo(base(globalBooks = books, globalEntriesByBook = entries, onlyBookNames = emptySet()))
        assertTrue(explicitNone.activated.isEmpty())

        val explicitOne = scanWorldInfo(base(globalBooks = books, globalEntriesByBook = entries, onlyBookNames = setOf("off")))
        assertEquals(listOf(2), explicitOne.activated.map { it.uid })
    }

    @Test
    fun `聊天串带名字前缀（原版 chatForWI）`() {
        // 条目只认 "角色: 内容" 这种带前缀的行 —— 没有前缀就匹配不到
        val result = scanWorldInfo(
            base(
                cardEntries = listOf(entry(1, "命中", keys = listOf("角色: 你好"))),
                history = listOf(WorldInfoMessage(isUser = false, text = "你好")),
            ),
        )
        assertEquals(listOf(1), result.activated.map { it.uid })
    }

    @Test
    fun `概率条目依赖随机源 - 同 seed 可复现、不同 seed 可出现差异`() {
        val inputs = base(cardEntries = listOf(entry(1, "半概率", probability = 50)))
        val a = scanWorldInfo(inputs.copy(random = Random(3)))
        val b = scanWorldInfo(inputs.copy(random = Random(3)))
        assertEquals(a.activated.map { it.uid }, b.activated.map { it.uid })

        // 抽号次数必须 > 0（否则"概率"根本没生效）
        val counting = CountingRandom(3)
        scanWorldInfo(inputs.copy(random = counting))
        assertTrue(counting.bitsCalls > 0, "概率条目没有消耗随机源")
    }

    @Test
    fun `预算 - maxContext 很小时只留得下一部分`() {
        val many = (1..8).map { entry(it, "长正文".repeat(40)) }
        val roomy = scanWorldInfo(base(cardEntries = many).copy(maxContext = 100_000))
        val tight = scanWorldInfo(base(cardEntries = many).copy(maxContext = 400))
        assertEquals(8, roomy.activated.size)
        assertTrue(tight.activated.size < roomy.activated.size, "预算没生效：tight=${tight.activated.size}")
    }

    @Test
    fun `空条目直接返回空结果`() {
        val result = scanWorldInfo(base())
        assertTrue(result.activated.isEmpty())
        assertEquals("", result.beforeChar)
    }

    @Test
    fun `不是同一份输入就说明测试没错（防假绿）`() {
        val a = scanWorldInfo(base(cardEntries = listOf(entry(1, "A"))))
        val b = scanWorldInfo(base(cardEntries = listOf(entry(2, "B"))))
        assertNotEquals(a.activated.map { it.uid }, b.activated.map { it.uid })
    }

    /** 记抽号次数：既证明"概率真的在抽"，也能在回归时发现抽号顺序变了。 */
    private class CountingRandom(seed: Int) : Random() {
        private val inner = Random(seed)
        var bitsCalls = 0
            private set

        override fun nextBits(bitCount: Int): Int {
            bitsCalls++
            return inner.nextBits(bitCount)
        }
    }
}
