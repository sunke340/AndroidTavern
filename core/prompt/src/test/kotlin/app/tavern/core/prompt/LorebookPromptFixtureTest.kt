package app.tavern.core.prompt

import app.tavern.core.card.CharacterCard
import app.tavern.core.card.lorebookEntries
import app.tavern.core.lorebook.LorebookGlobalScanData
import app.tavern.core.lorebook.LorebookScanner
import app.tavern.core.lorebook.LorebookScanSettings
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 世界书注入的兼容断言：用 [fixtures/expected/P1-1-prompt-lorebook.json]——原版 1.19.1 真实发出的请求体——
 * 逐字比对我们扫出来的 `worldInfoBefore` 和整条消息链。
 *
 * 卡数据来自 [fixtures/worldinfo/P1-1-seraphina-book.json]（原版默认卡 Seraphina 的 ccv3，
 * 只留提示词相关的字段）。录夹具时的聊天只有开场白一条，所以扫描输入就是
 * `"Seraphina: <first_mes>"`（原版 `script.js:4626` 的 `name: mes` 前缀）。
 *
 * 预算按原版默认预设算：`openai_max_context 4095 - max_tokens 300 = 3795`，
 * `budget = round(25% * 3795) = 949` token。三条条目合计约 790 token，所以全部留下——
 * 真正的预算边界由 P1-4 的夹具管。
 */
class LorebookPromptFixtureTest {
    private val fixture = Fixtures.load("P1-1-prompt-lorebook")

    private val card: CharacterCard by lazy {
        val root = Path.of(System.getProperty("repo.root") ?: error("缺少 repo.root"))
        val file = root.resolve("fixtures/worldinfo/P1-1-seraphina-book.json")
        require(Files.exists(file)) { "缺少夹具卡数据：$file" }

        val data = Json.parseToJsonElement(Files.readString(file)).jsonObject["data"]!!.jsonObject
        val cardJson = JsonObject(
            mapOf(
                "spec" to JsonPrimitive("chara_card_v3"),
                "spec_version" to JsonPrimitive("3.0"),
                "data" to data,
            ),
        )
        CharacterCard.fromJson(cardJson.toString())
    }

    private val history = listOf(ChatMessage(role = ChatRole.Assistant, content = card.firstMessage))

    private fun scan() = LorebookScanner().scan(
        entries = card.lorebookEntries(),
        chat = listOf("${card.name}: ${card.firstMessage}"),
        settings = LorebookScanSettings(
            charName = card.name,
            userName = fixture.userName,
            maxContext = 3795,
        ),
        global = LorebookGlobalScanData(
            characterDescription = card.description,
            characterPersonality = card.personality,
            scenario = card.scenario,
        ),
    )

    @Test
    fun `worldInfoBefore 与原版逐字相等`() {
        val entries = card.lorebookEntries()
        assertEquals(4, entries.size, "这张卡内嵌书有 4 条条目")

        val result = scan()
        val expected = fixture.messages[1].content

        if (result.beforeChar != expected) {
            throw AssertionError(
                buildString {
                    appendLine("worldInfoBefore 不一致：actual=${result.beforeChar.length} expected=${expected.length}")
                    appendLine("激活条目：${result.activated.map { it.id + "/" + it.keys }}")
                    val index = result.beforeChar.zip(expected).indexOfFirst { (a, b) -> a != b }
                    if (index >= 0) {
                        appendLine("首个差异 @$index")
                        appendLine("  actual  : ${result.beforeChar.take(index + 80).takeLast(120)}")
                        appendLine("  expected: ${expected.take(index + 80).takeLast(120)}")
                    }
                },
            )
        }
    }

    @Test
    fun `整条提示词链与原版逐条相等`() {
        val actual = PromptBuilder().build(card, fixture.userName, history, scan())

        if (actual != fixture.messages) {
            throw AssertionError(
                buildString {
                    appendLine("消息数不一致：actual=${actual.size} expected=${fixture.messages.size}")
                    for (i in 0 until maxOf(actual.size, fixture.messages.size)) {
                        val a = actual.getOrNull(i)
                        val e = fixture.messages.getOrNull(i)
                        if (a != e) {
                            appendLine("--- [$i]")
                            appendLine("  actual  : ${a?.role} ${a?.content?.take(160)}")
                            appendLine("  expected: ${e?.role} ${e?.content?.take(160)}")
                        }
                    }
                },
            )
        }
    }
}
