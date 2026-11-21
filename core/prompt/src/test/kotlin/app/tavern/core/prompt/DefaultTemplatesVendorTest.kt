package app.tavern.core.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 反向核对：代码里抄的默认模板必须与 vendor 里的原版一致。
 *
 * CI 上没有 vendor（不入库），所以用 assumeTrue 跳过；本机开发时它一定会跑——
 * 这就是防止"抄错一个标点、之后所有断言都建立在错误基准上"的那道闸。
 */
class DefaultTemplatesVendorTest {
    private val repoRoot: Path = Path.of(System.getProperty("repo.root")!!)
    private val preset: Path = repoRoot.resolve("vendor/SillyTavern/default/content/presets/openai/Default.json")

    @Test
    fun `代码里的默认模板与 vendor 原版一致`() {
        assumeTrue(Files.exists(preset), "没有 vendor/SillyTavern，跳过（CI 常态）")

        val json = Json.parseToJsonElement(Files.readString(preset)).jsonObject

        fun promptContent(identifier: String): String = json["prompts"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["identifier"]?.jsonPrimitive?.content == identifier }
            .getValue("content").jsonPrimitive.content

        assertEquals(promptContent("main"), SillyTavernDefaults.MAIN, "main 模板被改过或抄错了")

        val topLevel = mapOf(
            "new_chat_prompt" to SillyTavernDefaults.NEW_CHAT,
            "new_example_chat_prompt" to SillyTavernDefaults.EXAMPLE_CHAT,
            "personality_format" to SillyTavernDefaults.PERSONALITY_FORMAT,
            "scenario_format" to SillyTavernDefaults.SCENARIO_FORMAT,
        )
        for ((key, expected) in topLevel) {
            assertEquals(
                json.getValue(key).jsonPrimitive.content,
                expected,
                "$key 被改过或抄错了",
            )
        }
    }
}
