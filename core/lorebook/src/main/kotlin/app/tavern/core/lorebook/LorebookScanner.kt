package app.tavern.core.lorebook

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 世界书激活扫描器。行为逐条对照原版 `public/scripts/world-info.js`：
 *
 * - 缓冲与匹配：[WorldInfoBuffer]（原版 199–474 行）→ 本文件的 `depthText` / `matchKeys`
 * - 主循环：[checkWorldInfo]（4709–5282 行）
 * - 包含组：`filterGroupsByTimedEffects` / `filterGroupsByScoring` / `filterByInclusionGroups`（5292–5475）
 * - 计时效果（sticky/cooldown/delay）：`WorldInfoTimedEffects`（479–793）
 *
 * 刻意没做的（都在矩阵里记着，不假装做了）：
 * - `@@` 装饰器（原版 `parseDecorators`，4652）还没解析，带装饰器的条目会原样带出去；
 * - 条目内容在注入前的正则改写（原版 `getRegexedString`，属正则脚本特性，P1-11）；
 * - outlet 位置只记录、不注入；
 * - token 计数默认是 4 字符≈1 token 的估值，接上 `:core:tokenizer` 之前预算只能算近似。
 */
public class LorebookScanner(private val random: Random = Random.Default) {

    /**
     * 扫一轮。
     *
     * @param entries 参与扫描的条目（卡内嵌书 + 全局书，调用方先合并好）
     * @param chat 聊天内容，**从旧到新**顺序，元素是已经带上名字前缀的字符串
     *   （原版 `chatForWI = coreChat.map(x => `${x.name}: ${x.mes}`).reverse()`，`script.js:4626`）
     * @param state 上一轮留下的计时状态，原样传回来
     */
    public fun scan(
        entries: List<LorebookEntry>,
        chat: List<String>,
        settings: LorebookScanSettings = LorebookScanSettings(),
        state: LorebookScanState = LorebookScanState(),
        global: LorebookGlobalScanData = LorebookGlobalScanData(),
        dryRun: Boolean = false,
    ): LorebookScanResult {
        if (entries.isEmpty()) return LorebookScanResult(state = state)

        // getSortedEntries()：按 order 降序（原版 sortFn 是 (a,b) => b.order - a.order，稳定排序）
        val sorted = entries.sortedWith(compareByDescending { it.order })

        val chatLength = chat.size
        val timed = TimedEffects(chatLength, sorted, state, dryRun)
        timed.check()

        val depthBuffer = chat.asReversed().take(MAX_SCAN_DEPTH).map { it.trim() }
        val recurseBuffer = mutableListOf<String>()

        val allActivated = LinkedHashMap<String, LorebookEntry>()
        val contentCache = HashMap<LorebookEntry, String>()
        val failedProbability = HashSet<String>()

        var budget = (settings.budgetPercent.toDouble() * settings.maxContext / 100.0).roundToInt()
        if (budget <= 0) budget = 1
        if (settings.budgetCap > 0 && budget > settings.budgetCap) budget = settings.budgetCap

        // delayUntilRecursion 的层级列表（原版 4754–4762）
        val availableDelayLevels = sorted
            .map { it.delayUntilRecursion }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toMutableList()
        var currentDelayLevel = availableDelayLevels.removeFirstOrNull() ?: 0

        var scanState = ScanState.Initial
        var tokenBudgetOverflowed = false
        var loops = 0
        var allActivatedText = ""
        var skew = 0

        while (scanState != ScanState.None) {
            if (settings.maxRecursionSteps > 0 && loops >= settings.maxRecursionSteps) break
            loops++

            var nextScanState = ScanState.None
            val activatedNow = LinkedHashSet<LorebookEntry>()

            for (entry in sorted) {
                if (failedProbability.contains(entry.id) || allActivated.containsKey(entry.id)) continue
                if (entry.disabled) continue
                if (entry.triggers.isNotEmpty() && settings.trigger !in entry.triggers) continue

                val isSticky = timed.isActive(TimedEffectType.Sticky, entry)
                val isCooldown = timed.isActive(TimedEffectType.Cooldown, entry)
                val isDelay = timed.isActive(TimedEffectType.Delay, entry)

                if (isDelay) continue
                if (isCooldown && !isSticky) continue
                if (scanState != ScanState.Recursion && entry.delayUntilRecursion > 0 && !isSticky) continue
                if (scanState == ScanState.Recursion && entry.delayUntilRecursion > currentDelayLevel && !isSticky) continue
                if (scanState == ScanState.Recursion && settings.recursive && entry.excludeRecursion && !isSticky) continue

                if (entry.constant) {
                    activatedNow += entry
                    continue
                }

                if (isSticky) {
                    activatedNow += entry
                    continue
                }

                if (entry.keys.isEmpty()) continue

                val textToScan = depthText(entry, scanState, depthBuffer, recurseBuffer, global, skew, settings)

                val primaryKeyMatch = entry.keys.firstOrNull { key ->
                    val substituted = substitute(key, settings).trim()
                    substituted.isNotEmpty() && matchKeys(textToScan, substituted, entry, settings)
                }
                if (primaryKeyMatch == null) continue

                val hasSecondaryKeywords =
                    entry.selective && entry.secondaryKeys.isNotEmpty()

                if (!hasSecondaryKeywords) {
                    activatedNow += entry
                    continue
                }

                if (matchSecondaryKeys(textToScan, entry, settings)) {
                    activatedNow += entry
                }
            }

            // 概率与预算检查前先排序：sticky 优先，其余保持 getSortedEntries 的顺序（原版 4995–5006）
            val orderIndex = sorted.withIndex().associate { it.value to it.index }
            val newEntries: MutableList<LorebookEntry> = if (activatedNow.size > 1) {
                activatedNow.sortedWith(
                    compareByDescending<LorebookEntry> { if (timed.isActive(TimedEffectType.Sticky, it)) 1 else 0 }
                        .thenBy { orderIndex[it] ?: -1 },
                ).toMutableList()
            } else {
                activatedNow.toMutableList()
            }

            filterByInclusionGroups(newEntries, allActivated, timed, orderIndex, scanState, settings, depthBuffer, global, skew)

            var newContent = ""
            val textToScanTokens = settings.tokenCounter.count(allActivatedText)

            var ignoresBudget = newEntries.count { it.ignoreBudget }

            for (entry in newEntries.toList()) {
                ignoresBudget -= if (entry.ignoreBudget) 1 else 0
                if (tokenBudgetOverflowed && !entry.ignoreBudget) {
                    if (ignoresBudget > 0) continue
                    break
                }

                val probabilityOk = if (!entry.useProbability || entry.probability == 100) {
                    true
                } else if (timed.isActive(TimedEffectType.Sticky, entry)) {
                    true
                } else {
                    random.nextDouble() * 100.0 <= entry.probability
                }
                if (!probabilityOk) {
                    failedProbability += entry.id
                    continue
                }

                val content = substitute(entry.content, settings)
                contentCache[entry] = content
                newContent += "$content\n"

                if (!entry.ignoreBudget &&
                    textToScanTokens + settings.tokenCounter.count(newContent) >= budget
                ) {
                    tokenBudgetOverflowed = true
                    continue
                }

                allActivated[entry.id] = entry
            }

            val successfulForRecursion = newEntries.filter {
                !failedProbability.contains(it.id) && !it.preventRecursion
            }

            if (settings.recursive && !tokenBudgetOverflowed && successfulForRecursion.isNotEmpty()) {
                nextScanState = ScanState.Recursion
            }
            if (settings.recursive && !tokenBudgetOverflowed &&
                scanState == ScanState.MinActivations && recurseBuffer.isNotEmpty()
            ) {
                nextScanState = ScanState.Recursion
            }

            val minActivationsNotSatisfied =
                settings.minActivations > 0 && allActivated.size < settings.minActivations
            if (nextScanState == ScanState.None && !tokenBudgetOverflowed && minActivationsNotSatisfied) {
                val depth = settings.scanDepth + skew
                val overMax = (settings.minActivationsDepthMax > 0 && depth > settings.minActivationsDepthMax) ||
                    depth > chatLength
                if (!overMax) {
                    nextScanState = ScanState.MinActivations
                    skew++
                }
            }

            if (nextScanState == ScanState.None && availableDelayLevels.isNotEmpty()) {
                nextScanState = ScanState.Recursion
                currentDelayLevel = availableDelayLevels.removeAt(0)
            }

            scanState = nextScanState
            if (scanState != ScanState.None) {
                val text = successfulForRecursion.joinToString("\n") { contentCache[it] ?: substitute(it.content, settings) }
                if (text.isNotEmpty()) {
                    recurseBuffer += text
                    allActivatedText = "$text\n$allActivatedText"
                }
            }
        }

        timed.setTimedEffects(allActivated.values)
        return buildPrompt(allActivated, contentCache, timed, settings)
    }

    private fun buildPrompt(
        allActivated: LinkedHashMap<String, LorebookEntry>,
        contentCache: Map<LorebookEntry, String>,
        timed: TimedEffects,
        settings: LorebookScanSettings,
    ): LorebookScanResult {
        val before = mutableListOf<String>()
        val after = mutableListOf<String>()
        val exampleTop = mutableListOf<String>()
        val exampleBottom = mutableListOf<String>()
        val authorNoteTop = mutableListOf<String>()
        val authorNoteBottom = mutableListOf<String>()
        val depthEntries = mutableListOf<LorebookDepthEntry>()

        // 按插入序号升序落位（原版用 sortFn 降序遍历 + unshift 得到升序，5203 行）
        allActivated.values.sortedWith(compareByDescending { it.order }).forEach { entry ->
            val content = contentCache[entry] ?: substitute(entry.content, settings)
            if (content.isEmpty()) return@forEach

            when (entry.position) {
                LorebookPosition.BeforeChar -> before.add(0, content)
                LorebookPosition.AfterChar -> after.add(0, content)
                LorebookPosition.ExampleTop -> exampleTop.add(0, content)
                LorebookPosition.ExampleBottom -> exampleBottom.add(0, content)
                LorebookPosition.AuthorNoteTop -> authorNoteTop.add(0, content)
                LorebookPosition.AuthorNoteBottom -> authorNoteBottom.add(0, content)
                LorebookPosition.AtDepth -> {
                    val depth = entry.depth
                    val role = entry.role ?: 0
                    val existing = depthEntries.indexOfFirst { it.depth == depth && it.role == role }
                    if (existing >= 0) {
                        val old = depthEntries[existing]
                        depthEntries[existing] = LorebookDepthEntry(depth, role, listOf(content) + old.entries)
                    } else {
                        depthEntries += LorebookDepthEntry(depth, role, listOf(content))
                    }
                }
                LorebookPosition.Outlet -> Unit // 出口要别的扩展消费，本实现不注入
            }
        }

        return LorebookScanResult(
            beforeChar = before.joinToString("\n"),
            afterChar = after.joinToString("\n"),
            depthEntries = depthEntries,
            exampleTop = exampleTop,
            exampleBottom = exampleBottom,
            authorNoteTop = authorNoteTop,
            authorNoteBottom = authorNoteBottom,
            activated = allActivated.values.toList(),
            state = timed.toState(),
        )
    }

    /** 原版 `WorldInfoBuffer.get()`（279 行）：取深度缓冲 + 全局上下文 + 递归缓冲。 */
    private fun depthText(
        entry: LorebookEntry,
        scanState: ScanState,
        depthBuffer: List<String>,
        recurseBuffer: List<String>,
        global: LorebookGlobalScanData,
        skew: Int,
        settings: LorebookScanSettings,
    ): String {
        var depth = entry.scanDepth ?: (settings.scanDepth + skew)
        if (depth <= 0) return ""
        if (depth > MAX_SCAN_DEPTH) depth = MAX_SCAN_DEPTH

        val matcher = "\u0001"
        val joiner = "\n$matcher"
        val slice = depthBuffer.subList(0, minOf(depth, depthBuffer.size))
        val parts = mutableListOf<String>()

        if (slice.isNotEmpty()) parts += matcher + slice.joinToString(joiner)

        if (entry.matchPersonaDescription && global.personaDescription.isNotEmpty()) {
            parts += joiner + global.personaDescription
        }
        if (entry.matchCharacterDescription && global.characterDescription.isNotEmpty()) {
            parts += joiner + global.characterDescription
        }
        if (entry.matchCharacterPersonality && global.characterPersonality.isNotEmpty()) {
            parts += joiner + global.characterPersonality
        }
        if (entry.matchCharacterDepthPrompt && global.characterDepthPrompt.isNotEmpty()) {
            parts += joiner + global.characterDepthPrompt
        }
        if (entry.matchScenario && global.scenario.isNotEmpty()) {
            parts += joiner + global.scenario
        }
        if (entry.matchCreatorNotes && global.creatorNotes.isNotEmpty()) {
            parts += joiner + global.creatorNotes
        }
        if (recurseBuffer.isNotEmpty() && scanState != ScanState.MinActivations) {
            parts += joiner + recurseBuffer.joinToString(joiner)
        }

        return parts.joinToString("")
    }

    /**
     * 原版 `WorldInfoBuffer.matchKeys()`（337 行）。**斜杠包的 key 当正则**，这会覆盖大小写/整词设置。
     */
    private fun matchKeys(
        haystackRaw: String,
        needleRaw: String,
        entry: LorebookEntry,
        settings: LorebookScanSettings,
    ): Boolean {
        val keyRegex = parseRegexKey(needleRaw)
        if (keyRegex != null) return keyRegex.containsMatchIn(haystackRaw)

        val caseSensitive = entry.caseSensitive ?: settings.caseSensitive
        val haystack = if (caseSensitive) haystackRaw else haystackRaw.lowercase()
        val needle = if (caseSensitive) needleRaw else needleRaw.lowercase()
        val matchWholeWords = entry.matchWholeWords ?: settings.matchWholeWords

        if (matchWholeWords) {
            val words = needle.split(WHITESPACE)
            if (words.size > 1) return haystack.contains(needle)
            return Regex("(?:^|\\W)(${Regex.escape(needle)})(?:$|\\W)").containsMatchIn(haystack)
        }

        return haystack.contains(needle)
    }

    /** 原版 `matchSecondaryKeys()`（4943 行）：四种逻辑都在这里。 */
    private fun matchSecondaryKeys(
        textToScan: String,
        entry: LorebookEntry,
        settings: LorebookScanSettings,
    ): Boolean {
        var hasAnyMatch = false
        var hasAllMatch = true

        for (rawKey in entry.secondaryKeys) {
            val key = substitute(rawKey, settings).trim()
            val matched = key.isNotEmpty() && matchKeys(textToScan, key, entry, settings)
            if (matched) hasAnyMatch = true
            if (!matched) hasAllMatch = false

            if (entry.selectiveLogic == SelectiveLogic.AndAny && matched) return true
            if (entry.selectiveLogic == SelectiveLogic.NotAll && !matched) return true
        }

        if (entry.selectiveLogic == SelectiveLogic.NotAny && !hasAnyMatch) return true
        if (entry.selectiveLogic == SelectiveLogic.AndAll && hasAllMatch) return true
        return false
    }

    /**
     * 包含组过滤（原版 5388 行 `filterByInclusionGroups`）：同一组每轮最多留一条，
     * 且如果这一组之前已经激活过，本轮新激活的整组都要让位。
     */
    private fun filterByInclusionGroups(
        newEntries: MutableList<LorebookEntry>,
        allActivated: Map<String, LorebookEntry>,
        timed: TimedEffects,
        orderIndex: Map<LorebookEntry, Int>,
        scanState: ScanState,
        settings: LorebookScanSettings,
        depthBuffer: List<String>,
        global: LorebookGlobalScanData,
        skew: Int,
    ) {
        val grouped = LinkedHashMap<String, MutableList<LorebookEntry>>()
        newEntries.filter { it.group.isNotBlank() }.forEach { entry ->
            entry.group.split(GROUP_SEPARATOR).filter { it.isNotEmpty() }.forEach { group ->
                grouped.getOrPut(group) { mutableListOf() } += entry
            }
        }
        if (grouped.isEmpty()) return

        // 每个组里有没有 sticky 条目；有的话其余条目已经在计时过滤里被踢掉
        val hasSticky = HashMap<String, Boolean>()
        for ((key, group) in grouped) {
            hasSticky[key] = false
            val stickyEntries = group.filter { timed.isActive(TimedEffectType.Sticky, it) }
            if (stickyEntries.isNotEmpty()) {
                group.filter { it !in stickyEntries }.forEach { newEntries.remove(it) }
                hasSticky[key] = true
            }
            group.filter { timed.isActive(TimedEffectType.Cooldown, it) }.forEach { newEntries.remove(it) }
            group.filter { timed.isActive(TimedEffectType.Delay, it) }.forEach { newEntries.remove(it) }
        }

        // 组内按分数淘汰（filterGroupsByScoring，5292 行）
        for ((key, group) in grouped) {
            if (!settings.useGroupScoring && group.none { it.useGroupScoring == true }) continue
            if (hasSticky[key] == true) continue

            val scores = group.map { groupScore(it, settings, scanState, depthBuffer, global, skew) }.toMutableList()
            val maxScore = scores.maxOrNull() ?: continue
            var i = 0
            while (i < group.size) {
                val scored = group[i].useGroupScoring ?: settings.useGroupScoring
                if (scored && scores[i] < maxScore) {
                    newEntries.remove(group[i])
                    group.removeAt(i)
                    scores.removeAt(i)
                    continue
                }
                i++
            }
        }

        for ((key, group) in grouped) {
            if (hasSticky[key] == true) continue
            if (allActivated.values.any { it.group == key }) {
                // 这一组已经在这一轮更早的递归里激活过，本轮新来的整组让位
                group.forEach { newEntries.remove(it) }
                continue
            }
            if (group.size <= 1) continue

            val priorityWinners = group.filter { it.groupOverride }
            if (priorityWinners.isNotEmpty()) {
                val winner = priorityWinners.sortedWith(compareByDescending { it.order }).first()
                group.filter { it !== winner }.forEach { newEntries.remove(it) }
                continue
            }

            val totalWeight = group.sumOf { it.groupWeight }
            if (totalWeight <= 0) {
                group.forEach { newEntries.remove(it) }
                continue
            }
            var roll = random.nextDouble() * totalWeight
            var winner: LorebookEntry? = null
            for (entry in group) {
                roll -= entry.groupWeight
                if (roll <= 0) {
                    winner = entry
                    break
                }
            }
            if (winner == null) {
                group.forEach { newEntries.remove(it) }
                continue
            }
            group.filter { it !== winner }.forEach { newEntries.remove(it) }
        }
    }

    /** 原版 `WorldInfoBuffer.getScore()`（428 行）：主键命中数 + 次键命中数（两种正向逻辑）。 */
    private fun groupScore(
        entry: LorebookEntry,
        settings: LorebookScanSettings,
        scanState: ScanState,
        depthBuffer: List<String>,
        global: LorebookGlobalScanData,
        skew: Int,
    ): Int {
        val text = depthText(entry, scanState, depthBuffer, emptyList(), global, skew, settings)

        val primary = entry.keys.count { key ->
            val substituted = substitute(key, settings).trim()
            substituted.isNotEmpty() && matchKeys(text, substituted, entry, settings)
        }
        if (entry.keys.isEmpty()) return 0

        if (entry.secondaryKeys.isNotEmpty()) {
            val secondary = entry.secondaryKeys.count { key ->
                val substituted = substitute(key, settings).trim()
                substituted.isNotEmpty() && matchKeys(text, substituted, entry, settings)
            }
            when (entry.selectiveLogic) {
                SelectiveLogic.AndAny -> return primary + secondary
                SelectiveLogic.AndAll ->
                    return if (secondary == entry.secondaryKeys.size) primary + secondary else primary
                else -> Unit
            }
        }

        return primary
    }

    private fun substitute(text: String, settings: LorebookScanSettings): String {
        if (text.isEmpty()) return text
        var result = text.replace("\r", "")
        if (settings.charName != CHAR_MACRO) result = result.replace(CHAR_MACRO, settings.charName)
        if (settings.userName != USER_MACRO) result = result.replace(USER_MACRO, settings.userName)
        return result
    }

    /** 复刻 `parseRegexFromString`（2901 行）：`/pattern/flags`，不合规就当普通文本。 */
    private fun parseRegexKey(input: String): Regex? {
        val match = SLASH_REGEX.matchEntire(input) ?: return null
        var pattern = match.groupValues[1]
        val flags = match.groupValues[2]
        if (pattern.contains(UNESCAPED_SLASH)) return null
        pattern = pattern.replace("\\/", "/")

        var options = emptySet<RegexOption>()
        if ('i' in flags) options += RegexOption.IGNORE_CASE
        if ('m' in flags) options += RegexOption.MULTILINE
        if ('s' in flags) options += RegexOption.DOT_MATCHES_ALL
        return runCatching { Regex(pattern, options) }.getOrNull()
    }

    private enum class ScanState { None, Initial, Recursion, MinActivations }

    private enum class TimedEffectType { Sticky, Cooldown, Delay }

    private class TimedEffects(
        private val chatLength: Int,
        private val entries: List<LorebookEntry>,
        initialState: LorebookScanState,
        private val dryRun: Boolean,
    ) {
        private val stickyBuffer = mutableListOf<LorebookEntry>()
        private val cooldownBuffer = mutableListOf<LorebookEntry>()
        private val delayBuffer = mutableListOf<LorebookEntry>()

        private val stickyMap = LinkedHashMap(initialState.sticky)
        private val cooldownMap = LinkedHashMap(initialState.cooldown)

        fun check() {
            if (!dryRun) {
                checkOfType(stickyMap, stickyBuffer, TimedEffectType.Sticky)
                checkOfType(cooldownMap, cooldownBuffer, TimedEffectType.Cooldown)
            }
            checkDelay()
        }

        fun isActive(type: TimedEffectType, entry: LorebookEntry): Boolean = when (type) {
            TimedEffectType.Sticky -> stickyBuffer.any { it === entry }
            TimedEffectType.Cooldown -> cooldownBuffer.any { it === entry }
            TimedEffectType.Delay -> delayBuffer.any { it === entry }
        }

        fun setTimedEffects(activated: Collection<LorebookEntry>) {
            if (dryRun) return
            for (entry in activated) {
                addEffect(stickyMap, TimedEffectType.Sticky, entry)
                addEffect(cooldownMap, TimedEffectType.Cooldown, entry)
            }
        }

        fun toState(): LorebookScanState = LorebookScanState(stickyMap.toMap(), cooldownMap.toMap())

        private fun addEffect(
            map: MutableMap<String, LorebookTimedEffect>,
            type: TimedEffectType,
            entry: LorebookEntry,
        ) {
            val turns = effectTurns(entry, type) ?: return
            if (turns <= 0) return
            if (map.containsKey(entry.id)) return
            map[entry.id] = LorebookTimedEffect(start = chatLength, end = chatLength + turns)
        }

        private fun checkOfType(
            map: MutableMap<String, LorebookTimedEffect>,
            buffer: MutableList<LorebookEntry>,
            type: TimedEffectType,
        ) {
            for ((key, value) in map.entries.toList()) {
                if (chatLength <= value.start && !value.protected) {
                    map.remove(key)
                    continue
                }
                val entry = entries.firstOrNull { it.id == key }
                if (entry == null) {
                    if (chatLength >= value.end) map.remove(key)
                    continue
                }
                val turns = effectTurns(entry, type)
                if (turns == null || turns <= 0) {
                    map.remove(key)
                    continue
                }
                if (chatLength >= value.end) {
                    map.remove(key)
                    if (type == TimedEffectType.Sticky) {
                        // sticky 结束时会立刻挂上冷却（原版 518 行）
                        val cooldownTurns = entry.cooldown
                        if (cooldownTurns != null && cooldownTurns > 0) {
                            cooldownMap[key] = LorebookTimedEffect(
                                start = chatLength,
                                end = chatLength + cooldownTurns,
                                protected = true,
                            )
                            cooldownBuffer += entry
                        }
                    }
                    continue
                }
                buffer += entry
            }
        }

        private fun checkDelay() {
            for (entry in entries) {
                val delay = entry.delay
                if (delay != null && delay > 0 && chatLength < delay) delayBuffer += entry
            }
        }

        private fun effectTurns(entry: LorebookEntry, type: TimedEffectType): Int? = when (type) {
            TimedEffectType.Sticky -> entry.sticky
            TimedEffectType.Cooldown -> entry.cooldown
            TimedEffectType.Delay -> entry.delay
        }
    }

    private companion object {
        const val MAX_SCAN_DEPTH = 1000
        const val CHAR_MACRO = "{{char}}"
        const val USER_MACRO = "{{user}}"
        val WHITESPACE = Regex("\\s+")
        val GROUP_SEPARATOR = Regex(",\\s*")
        val SLASH_REGEX = Regex("^/([\\w\\W]+?)/([gimsuy]*)$")
        val UNESCAPED_SLASH = Regex("(^|[^\\\\])/")
    }
}
