package app.tavern.core.prompt

import app.tavern.core.card.CharacterCard
import app.tavern.core.card.PngCard
import app.tavern.core.card.lorebookEntries
import app.tavern.core.lorebook.LorebookGlobalScanData
import app.tavern.core.lorebook.LorebookScanResult
import app.tavern.core.lorebook.LorebookScanner
import app.tavern.core.lorebook.LorebookScanSettings
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * 世界书行为夹具：每个 P1-2/P1-3 夹具都是一次真实的原版生成，卡与聊天由
 * `fixtures/cards/spec/` 下的 spec 与 `fixtures/chats/` 下的 seed 生成。
 *
 * 断言方式：**重放整段聊天**（原版的多次生成）再比最后一条请求。
 * 为什么必须重放：sticky / cooldown / delay 是**跨轮状态**，只扫最后一轮复现不出来；
 * 重放时把每轮的 [app.tavern.core.lorebook.LorebookScanState] 传进下一轮，就是原版的做法。
 */
class LorebookBehaviorFixtureTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val repoRoot: Path = Path.of(
        System.getProperty("repo.root") ?: error("缺少 repo.root 系统属性"),
    )

    @Test
    fun `P1-2 四种 selectiveLogic`() = assertFixture("P1-2-logic")

    @Test
    fun `P1-3 递归扫描与延后递归`() = assertFixture("P1-3-recursion")

    @Test
    fun `P1-3 包含组按关键字分数淘汰`() = assertFixture("P1-3-groups")

    @Test
    fun `P1-3 sticky 让条目在关键字消失后仍激活`() = assertFixture("P1-3-sticky")

    @Test
    fun `P1-3 cooldown 在冷却期内挡住再次激活`() = assertFixture("P1-3-cooldown")

    @Test
    fun `P1-3 delay 未到期时抑制`() = assertFixture("P1-3-delay")

    @Test
    fun `P1-3 delay 到期后激活`() = assertFixture("P1-3-delay-passed")

    private fun assertFixture(id: String) {
        val file = repoRoot.resolve("fixtures/expected/$id.json")
        require(Files.exists(file)) { "夹具不存在：$file" }

        val root = json.parseToJsonElement(Files.readString(file)).jsonObject
        val meta = root["_meta"]!!.jsonObject
        val request = root["request"]!!.jsonObject
        val expected = request["messages"]!!.jsonArray.map { element ->
            val obj = element.jsonObject
            ChatMessage(
                role = ChatRole.fromWire(obj["role"]!!.jsonPrimitive.content),
                content = obj["content"]!!.jsonPrimitive.content,
            )
        }

        val characterName = meta["character"]!!.jsonPrimitive.content
        val userName = meta["context"]!!.jsonObject["name1"]?.jsonPrimitive?.content ?: "User"

        val card = readCard(id)
        val history = historyFrom(expected)
        val chat = history.map { message ->
            val name = if (message.role == ChatRole.User) userName else characterName
            "$name: ${message.content}"
        }

        val wiSettings = (meta["wiSettings"] as? JsonObject)
        val settings = LorebookScanSettings(
            charName = card.name,
            userName = userName,
            maxContext = 3795,
            recursive = wiSettings?.get("world_info_recursive")?.jsonPrimitive?.booleanOrNull ?: false,
            budgetPercent = wiSettings?.get("world_info_budget")?.jsonPrimitive?.intOrNull ?: 25,
            budgetCap = wiSettings?.get("world_info_budget_cap")?.jsonPrimitive?.intOrNull ?: 0,
            scanDepth = wiSettings?.get("world_info_depth")?.jsonPrimitive?.intOrNull ?: 2,
            caseSensitive = wiSettings?.get("world_info_case_sensitive")?.jsonPrimitive?.booleanOrNull ?: false,
            matchWholeWords = wiSettings?.get("world_info_match_whole_words")?.jsonPrimitive?.booleanOrNull ?: false,
            useGroupScoring = wiSettings?.get("world_info_use_group_scoring")?.jsonPrimitive?.booleanOrNull ?: false,
            minActivations = wiSettings?.get("world_info_min_activations")?.jsonPrimitive?.intOrNull ?: 0,
            maxRecursionSteps = wiSettings?.get("world_info_max_recursion_steps")?.jsonPrimitive?.intOrNull ?: 0,
        )
        val global = LorebookGlobalScanData(
            characterDescription = card.description,
            characterPersonality = card.personality,
            scenario = card.scenario,
        )

        val scanner = LorebookScanner()
        val entries = card.lorebookEntries()
        var state = app.tavern.core.lorebook.LorebookScanState()
        var result: LorebookScanResult? = null

        for (end in 1..chat.size) {
            val scanned = scanner.scan(entries, chat.subList(0, end), settings, state, global)
            state = scanned.state
            result = scanned
        }

        val actual = PromptBuilder().build(card, userName, history, result ?: LorebookScanResult())

        if (actual != expected) {
            throw AssertionError(diffReport(id, actual, expected))
        }
    }

    private fun diffReport(id: String, actual: List<ChatMessage>, expected: List<ChatMessage>): String =
        buildString {
            appendLine("夹具 $id 的提示词与原版不一致：actual=${actual.size} expected=${expected.size}")
            for (i in 0 until maxOf(actual.size, expected.size)) {
                val a = actual.getOrNull(i)
                val e = expected.getOrNull(i)
                if (a != e) {
                    appendLine("--- [$i]")
                    appendLine("  actual  : ${a?.role} ${a?.content?.take(200)}")
                    appendLine("  expected: ${e?.role} ${e?.content?.take(200)}")
                }
            }
        }

    /** 请求体里 `[Start a new Chat]` 之后就是聊天历史（含开场白与每轮问答）。 */
    private fun historyFrom(messages: List<ChatMessage>): List<ChatMessage> {
        val marker = messages.indexOfFirst { it.content == "[Start a new Chat]" }
        require(marker >= 0) { "请求体里没有 [Start a new Chat]" }
        return messages.subList(marker + 1, messages.size)
    }

    private companion object {
        val CARD_FILES = mapOf(
            "P1-3-sticky" to "P1-3-timed",
            "P1-3-cooldown" to "P1-3-timed",
            "P1-3-delay-passed" to "P1-3-delay",
        )
    }

    private fun readCard(id: String): CharacterCard {
        // 卡文件按"第一个录它的夹具 id"命名；后面共用同一张卡的夹具在这里映射
        val file = repoRoot.resolve("fixtures/cards/${CARD_FILES[id] ?: id}.png")
        require(Files.exists(file)) { "缺少合成卡：$file（用 tools/fixture/make-test-card.py 按 spec 生成）" }
        return PngCard.read(Files.readAllBytes(file))
    }
}
