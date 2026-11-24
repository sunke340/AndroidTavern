package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import app.tavern.core.preset.PromptOrderEntry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 兼容性断言之二：**按预设 `prompt_order` 拼**，必须与原版实际发的逐条相等。
 *
 * 夹具 [fixtures/expected/P1-5-prompt-order.json] 是让原版用一张改过的顺序表生成的：
 *
 * ```
 * scenario → main → worldInfoBefore → charDescription → charPersonality(关)
 *          → enhanceDefinitions(关) → nsfw(空) → worldInfoAfter(空)
 *          → chatHistory → dialogueExamples → jailbreak(空) → personaDescription(空)
 * ```
 *
 * 它同时钉住三件事：**重排生效**（scenario 跑到 main 前面）、**关掉的不发**（charPersonality 消失）、
 * **示例对话可以排在历史之后**（`[Example Chat]` 出现在 `FIRST-OMEGA` 后面）。
 */
class PromptOrderFixtureTest {
    private val repoRoot: Path = Path.of(System.getProperty("repo.root")!!)
    private val raw: JsonObject = Json
        .parseToJsonElement(
            Files.readString(repoRoot.resolve("fixtures/expected/P1-5-prompt-order.json")),
        )
        .jsonObject

    private val order: List<PromptOrderEntry> = raw["_meta"]!!.jsonObject["promptOrder"]!!.jsonArray.map {
        val entry = it.jsonObject
        PromptOrderEntry(
            identifier = entry["identifier"]!!.jsonPrimitive.content,
            enabled = entry["enabled"]!!.jsonPrimitive.boolean,
        )
    }

    private val expected: List<ChatMessage> = raw["request"]!!.jsonObject["messages"]!!.jsonArray.map {
        val message = it.jsonObject
        ChatMessage(
            role = ChatRole.fromWire(message["role"]!!.jsonPrimitive.content),
            content = message["content"]!!.jsonPrimitive.content,
        )
    }

    private val fixture = Fixtures.load("P1-5-prompt-order")

    private val history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA"))

    @Test
    fun `按预设顺序拼出的消息链与原版逐条相等`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = history,
            order = order,
        )

        if (actual != expected) {
            val report = buildString {
                appendLine("消息数与夹具不一致：actual=${actual.size} expected=${expected.size}")
                for (i in 0 until maxOf(actual.size, expected.size)) {
                    val a = actual.getOrNull(i)
                    val e = expected.getOrNull(i)
                    if (a != e) {
                        appendLine("--- [$i] 不一致")
                        appendLine("  actual  : ${a?.role} ${a?.content}")
                        appendLine("  expected: ${e?.role} ${e?.content}")
                    }
                }
            }
            throw AssertionError(report)
        }
    }

    @Test
    fun `关掉的那一条不会出现在结果里`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = history,
            order = order,
        )

        assertTrue(
            actual.none { it.content.contains("PERS-BETA") },
            "charPersonality 在夹具里是关掉的，结果里不该有它的内容",
        )
    }

    @Test
    fun `示例对话可以排在历史之后`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = history,
            order = order,
        )

        val historyIndex = actual.indexOfFirst { it.content == "FIRST-OMEGA" }
        val exampleIndex = actual.indexOfFirst { it.content == "[Example Chat]" }
        assertTrue(historyIndex >= 0 && exampleIndex > historyIndex, "示例块应该在历史之后")
    }
}
