package app.tavern.core.preset

import app.tavern.core.model.ConfigOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 三级继承的生效值（`resolveEffectiveConfig`）。
 *
 * 为什么这些测试存在：这套规则原来散在 `ChatViewModel.UiState` 的 14 个 getter 里，
 * `:app` 本机编不了、一条断言都没有 —— 而它正是"界面显示 A、请求发 B"那类 bug 的根源。
 * 搬进 core 之后，规则必须被**逐条**钉住（判据见 docs/plan-chatviewmodel-split.md 的 B1）。
 *
 * 最后一组 [oldFormula] 是"新旧公式对照"：把搬家前的 `?:` 链原样重写一遍，逐项比对 —— 这是
 * "行为不变"的直接证据，而不是"我看了一遍"。
 */
class EffectiveConfigTest {

    @Test
    fun `三层继承 - 会话优先于卡、卡优先于全局`() {
        val cfg = resolveEffectiveConfig(
            session = ConfigOverride(personaId = "p-session", profileId = "prof-session", presetName = "preset-session"),
            card = ConfigOverride(personaId = "p-card", profileId = "prof-card", presetName = "preset-card"),
            globals = GlobalDefaults(personaId = "p-global", profileId = "prof-global", presetName = "preset-global"),
        )
        assertEquals("p-session", cfg.personaId)
        assertEquals("prof-session", cfg.profileId)
        assertEquals("preset-session", cfg.presetName)
    }

    @Test
    fun `三层继承 - 会话空缺时走卡，卡也空走全局`() {
        val viaCard = resolveEffectiveConfig(
            session = ConfigOverride(),
            card = ConfigOverride(personaId = "p-card"),
            globals = GlobalDefaults(personaId = "p-global"),
        )
        assertEquals("p-card", viaCard.personaId)

        val viaGlobal = resolveEffectiveConfig(
            session = ConfigOverride(),
            card = ConfigOverride(),
            globals = GlobalDefaults(personaId = "p-global"),
        )
        assertEquals("p-global", viaGlobal.personaId)
    }

    @Test
    fun `两层继承 - 思考强度没有全局层，都空就是 null`() {
        assertNull(
            resolveEffectiveConfig(
                session = ConfigOverride(),
                card = ConfigOverride(),
                globals = GlobalDefaults(personaId = "有全局人设也不影响思考强度"),
            ).reasoningEffort,
        )
        assertEquals(
            "off",
            resolveEffectiveConfig(session = ConfigOverride(), card = ConfigOverride(reasoningEffort = "off")).reasoningEffort,
        )
    }

    @Test
    fun `空集不等于 null - 世界书与正则`() {
        // 空集 = 这一层明确"全都不要"；null = 跟随上级
        val explicitNone = resolveEffectiveConfig(
            session = ConfigOverride(worldBookNames = emptySet(), regexIds = emptySet()),
            card = ConfigOverride(worldBookNames = setOf("卡里的书")),
        )
        assertEquals(emptySet(), explicitNone.worldBookNames)
        assertEquals(emptySet(), explicitNone.regexIds)

        val follow = resolveEffectiveConfig(session = ConfigOverride(), card = ConfigOverride(worldBookNames = setOf("卡里的书")))
        assertEquals(setOf("卡里的书"), follow.worldBookNames)
        assertNull(follow.regexIds)
    }

    @Test
    fun `内置正则 - 空集是全开，null 才跟随全局`() {
        val explicitAll = resolveEffectiveConfig(
            session = ConfigOverride(builtinDisabled = emptySet()),
            card = ConfigOverride(),
            globals = GlobalDefaults(builtinDisabled = setOf("clean-tags")),
        )
        assertEquals(emptySet(), explicitAll.builtinDisabled)

        val follow = resolveEffectiveConfig(
            session = ConfigOverride(),
            card = ConfigOverride(),
            globals = GlobalDefaults(builtinDisabled = setOf("clean-tags")),
        )
        assertEquals(setOf("clean-tags"), follow.builtinDisabled)
    }

    @Test
    fun `气泡字号 - 会话 大于 卡 大于 全局`() {
        val g = GlobalDefaults(bubbleFontSize = 14f)
        assertEquals(22f, resolveEffectiveConfig(ConfigOverride(bubbleFontSize = 22f), ConfigOverride(bubbleFontSize = 18f), globals = g).bubbleFontSize)
        assertEquals(18f, resolveEffectiveConfig(ConfigOverride(), ConfigOverride(bubbleFontSize = 18f), globals = g).bubbleFontSize)
        assertEquals(14f, resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), globals = g).bubbleFontSize)
    }

    @Test
    fun `采样五项 - 四层顺序 会话 大于 卡 大于 预设 大于 全局文本`() {
        val g = SamplingTexts(temperature = "0.9", topP = "0.5")
        val preset = SamplingPreset(temperature = 0.7, maxTokens = 800, topP = 0.8, frequencyPenalty = 0.1)

        val session = resolveEffectiveConfig(ConfigOverride(temperature = 0.3), ConfigOverride(), preset, globals = GlobalDefaults(), texts = g)
        assertEquals(0.3, session.temperature)

        val card = resolveEffectiveConfig(ConfigOverride(), ConfigOverride(temperature = 0.4), preset, texts = g)
        assertEquals(0.4, card.temperature)

        val fromPreset = resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), preset, texts = g)
        assertEquals(0.7, fromPreset.temperature)
        assertEquals(800, fromPreset.maxTokens)
        assertEquals(0.8, fromPreset.topP)
        assertEquals(0.1, fromPreset.frequencyPenalty)

        // 预设给了 topP=0.8 就轮不到全局文本的 0.5（顺序没反）
        assertEquals(0.8, fromPreset.topP)
    }

    @Test
    fun `maxTokens 为 0 或负数表示不限`() {
        assertNull(resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), texts = SamplingTexts(maxTokens = "0")).maxTokens)
        assertNull(resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), texts = SamplingTexts(maxTokens = "-1")).maxTokens)
        assertNull(resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), texts = SamplingTexts(maxTokens = "abc")).maxTokens)
        assertEquals(512, resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), texts = SamplingTexts(maxTokens = "512")).maxTokens)
        // ⚠️ 既有行为（本批不动）：`takeIf { it > 0 }` 只作用在**文本框**那一层；
        // 会话/卡/预设里显式存的 0 会**原样**传下去（`ChatRequest.maxTokens = 0`）。
        // 能不能产生 0 取决于 `withSampling` 的解析 —— 这是**另案**（要改请求体语义），B1 只保证不变。
        assertEquals(0, resolveEffectiveConfig(ConfigOverride(maxTokens = 0), ConfigOverride(), texts = SamplingTexts(maxTokens = "512")).maxTokens)
    }

    @Test
    fun `temperature - 全局文本解析失败兜 1点0`() {
        assertEquals(1.0, resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), texts = SamplingTexts(temperature = "")).temperature)
        assertEquals(1.0, resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), texts = SamplingTexts(temperature = "abc")).temperature)
        assertEquals(0.25, resolveEffectiveConfig(ConfigOverride(), ConfigOverride(), texts = SamplingTexts(temperature = "0.25")).temperature)
    }

    @Test
    fun `作者注正文 - 空串归一，不许截断继承`() {
        // 会话层写了空串：按归一规则它等于"这一层没有"，于是卡里的正文生效
        assertEquals(
            "卡里的作者注",
            resolveEffectiveConfig(ConfigOverride(authorNote = ""), ConfigOverride(authorNote = "卡里的作者注")).authorNoteText,
        )
        // 会话层有正文就优先
        assertEquals(
            "会话的作者注",
            resolveEffectiveConfig(ConfigOverride(authorNote = "会话的作者注"), ConfigOverride(authorNote = "卡里的作者注")).authorNoteText,
        )
        // 两层都没有 / 都空 → null
        assertNull(resolveEffectiveConfig(ConfigOverride(authorNote = "  "), ConfigOverride()).authorNoteText)
    }

    @Test
    fun `新旧公式对照 - 一批组合下解析结果与搬家前的 ? 链完全一致`() {
        val sessions = listOf(
            ConfigOverride(),
            ConfigOverride(temperature = 0.2, maxTokens = 0, topP = 0.1),
            ConfigOverride(personaId = "p", reasoningEffort = "off", worldBookNames = emptySet()),
            ConfigOverride(builtinDisabled = emptySet(), bubbleFontSize = 19f),
        )
        val cards = listOf(
            ConfigOverride(),
            ConfigOverride(temperature = 0.6, maxTokens = 300, frequencyPenalty = 0.2, authorNote = "卡注"),
            ConfigOverride(personaId = "pc", worldBookNames = setOf("w"), regexIds = emptySet()),
        )
        val samplings = listOf(null, SamplingPreset(), SamplingPreset(temperature = 0.5, maxTokens = 900, topP = 0.9))
        val texts = listOf(
            SamplingTexts(),
            SamplingTexts(temperature = "0.8", maxTokens = "256", topP = "0.7", frequencyPenalty = "0.3", presencePenalty = "0.4"),
            SamplingTexts(temperature = "bad", maxTokens = "-5"),
        )
        val globals = listOf(GlobalDefaults(), GlobalDefaults(personaId = "g", profileId = "gp", presetName = "gpre", bubbleFontSize = 15f, builtinDisabled = setOf("x")))

        for (s in sessions) for (c in cards) for (sp in samplings) for (t in texts) for (g in globals) {
            val got = resolveEffectiveConfig(s, c, sp, g, t)
            val want = oldFormula(s, c, sp, g, t)
            assertEquals(want, got, "组合不匹配：session=$s card=$c sampling=$sp texts=$t globals=$g")
        }
    }

    /** 搬家前 `UiState` 里那 14 个 getter 的**原样公式**（不含 messageStyle：那个依赖 app 侧枚举）。 */
    private fun oldFormula(
        s: ConfigOverride,
        c: ConfigOverride,
        p: SamplingPreset?,
        g: GlobalDefaults,
        t: SamplingTexts,
    ) = EffectiveConfig(
        personaId = s.personaId ?: c.personaId ?: g.personaId,
        profileId = s.profileId ?: c.profileId ?: g.profileId,
        presetName = s.presetName ?: c.presetName ?: g.presetName,
        reasoningEffort = s.reasoningEffort ?: c.reasoningEffort,
        worldBookNames = s.worldBookNames ?: c.worldBookNames,
        regexIds = s.regexIds ?: c.regexIds,
        builtinDisabled = s.builtinDisabled ?: c.builtinDisabled ?: g.builtinDisabled,
        bubbleFontSize = s.bubbleFontSize ?: c.bubbleFontSize ?: g.bubbleFontSize,
        temperature = s.temperature ?: c.temperature ?: p?.temperature ?: t.temperature.toDoubleOrNull() ?: 1.0,
        maxTokens = s.maxTokens ?: c.maxTokens ?: p?.maxTokens ?: t.maxTokens.toIntOrNull()?.takeIf { it > 0 },
        topP = s.topP ?: c.topP ?: p?.topP ?: t.topP.toDoubleOrNull(),
        frequencyPenalty = s.frequencyPenalty ?: c.frequencyPenalty ?: p?.frequencyPenalty ?: t.frequencyPenalty.toDoubleOrNull(),
        presencePenalty = s.presencePenalty ?: c.presencePenalty ?: p?.presencePenalty ?: t.presencePenalty.toDoubleOrNull(),
        // 旧行为：先 `?:` 再整体归一（空串会截断继承）。矩阵里不含"某层写空串"的组合 ——
        // 那是本批**有意**不同的不可达状态，由专门的测试覆盖。
        authorNoteText = (s.authorNote ?: c.authorNote)?.takeIf { it.isNotBlank() },
    )
}
