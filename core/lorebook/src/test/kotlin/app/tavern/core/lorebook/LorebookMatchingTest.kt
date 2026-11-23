package app.tavern.core.lorebook

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 匹配规则的单元测试。夹具（`:core:prompt` 的 P1-x）证明的是"整条提示词对得上原版"，
 * 这里补的是**单个判定**的边界：整词、大小写、斜杠正则、常数。
 *
 * 期望值来自原版 `WorldInfoBuffer.matchKeys()`（`world-info.js:337-366`），
 * 不是"觉得应该这样"。
 */
class LorebookMatchingTest {

    private fun scanOne(entry: LorebookEntry, chatText: String, settings: LorebookScanSettings = LorebookScanSettings()) =
        LorebookScanner().scan(
            entries = listOf(entry),
            chat = listOf("User: $chatText"),
            settings = settings.copy(charName = "Bot", userName = "User"),
        )

    @Test
    fun `整词匹配不会命中单词内部`() {
        val entry = LorebookEntry(world = "w", uid = 0, keys = listOf("cat"), content = "HIT")

        val inside = scanOne(entry, "concatenate the strings", LorebookScanSettings(matchWholeWords = true))
        assertEquals("", inside.beforeChar, "cat 不该在 concatenate 里被命中")

        val standalone = scanOne(entry, "a cat sat", LorebookScanSettings(matchWholeWords = true))
        assertEquals("HIT", standalone.beforeChar)
    }

    @Test
    fun `默认不区分大小写，条目可单独开启`() {
        val entry = LorebookEntry(world = "w", uid = 0, keys = listOf("alpha"), content = "HIT")
        assertEquals("HIT", scanOne(entry, "ALPHA").beforeChar)

        val sensitive = entry.copyForTest(caseSensitive = true)
        assertEquals("", scanOne(sensitive, "ALPHA").beforeChar, "开了大小写敏感后 ALPHA 不该命中")
        assertEquals("HIT", scanOne(sensitive, "alpha").beforeChar)
    }

    @Test
    fun `斜杠包起来的 key 当正则，覆盖大小写与整词设置`() {
        val entry = LorebookEntry(
            world = "w",
            uid = 0,
            keys = listOf("/wombat\\d/i"),
            content = "HIT",
        )
        // 整词设置开着也不影响正则 key（原版 matchKeys 里正则分支最先返回）
        val result = scanOne(entry, "see WOMBAT7 here", LorebookScanSettings(matchWholeWords = true))
        assertEquals("HIT", result.beforeChar)
    }

    @Test
    fun `常数条目不看关键字`() {
        val entry = LorebookEntry(world = "w", uid = 0, keys = emptyList(), content = "ALWAYS", constant = true)
        assertEquals("ALWAYS", scanOne(entry, "nothing matches here").beforeChar)
    }

    @Test
    fun `没有关键字也不是常数就不激活`() {
        val entry = LorebookEntry(world = "w", uid = 0, keys = emptyList(), content = "NEVER")
        assertEquals("", scanOne(entry, "anything").beforeChar)
    }
}

/** `LorebookEntry` 字段都是 val，改一个要重建。 */
private fun LorebookEntry.copyForTest(caseSensitive: Boolean?): LorebookEntry = LorebookEntry(
    world = world,
    uid = uid,
    keys = keys,
    secondaryKeys = secondaryKeys,
    content = content,
    constant = constant,
    selective = selective,
    order = order,
    position = position,
    disabled = disabled,
    probability = probability,
    useProbability = useProbability,
    depth = depth,
    selectiveLogic = selectiveLogic,
    group = group,
    groupOverride = groupOverride,
    groupWeight = groupWeight,
    scanDepth = scanDepth,
    caseSensitive = caseSensitive,
    matchWholeWords = matchWholeWords,
    useGroupScoring = useGroupScoring,
    excludeRecursion = excludeRecursion,
    preventRecursion = preventRecursion,
    delayUntilRecursion = delayUntilRecursion,
    sticky = sticky,
    cooldown = cooldown,
    delay = delay,
    triggers = triggers,
    ignoreBudget = ignoreBudget,
    role = role,
)
