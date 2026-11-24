package app.tavern.core.preset

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 解析器断言：拿原版自带的 `Default.json`（本机有 vendor 时）与几个手写的边角料验证。
 *
 * 为什么要手写那份边角料：真实预设里字段类型并不统一（`character_id` 有的存数字有的存字符串），
 * 而且存盘时会把 `prompts` 里的注入点写成 `content: null`。这些都不是"理论上"，是原版实际存出来的形状。
 */
class PresetParserTest {
    private val repoRoot: Path = Path.of(System.getProperty("repo.root")!!)
    private val defaultPreset: Path =
        repoRoot.resolve("vendor/SillyTavern/default/content/presets/openai/Default.json")

    @Test
    fun `原版默认预设的采样参数与顺序`() {
        if (!Files.exists(defaultPreset)) return // CI 上没有 vendor

        val preset = PresetParser.parse("Default", Files.readString(defaultPreset))

        assertEquals(1.0, preset.sampling.temperature)
        assertEquals(1.0, preset.sampling.topP)
        assertEquals(300, preset.sampling.maxTokens)
        assertEquals(0.0, preset.sampling.frequencyPenalty)
        assertEquals(0.0, preset.sampling.presencePenalty)

        // 与代码里抄的那张表逐条相等（反向核对：改错一个字都会红）
        assertEquals(PresetDefaults.ORDER, preset.order)
        assertEquals(PresetDefaults.PROMPTS, preset.prompts)
    }

    @Test
    fun `character_id 写成字符串也能认出全局那一条`() {
        val json = """
            {
              "temperature": 0.7,
              "prompt_order": [
                {"character_id": "100000", "order": [
                  {"identifier": "scenario", "enabled": true},
                  {"identifier": "main", "enabled": false}
                ]}
              ]
            }
        """.trimIndent()

        val preset = PresetParser.parse("Inline", json)

        assertEquals(0.7, preset.sampling.temperature)
        assertEquals(
            listOf(PromptOrderEntry("scenario", true), PromptOrderEntry("main", false)),
            preset.order,
        )
        assertTrue(preset.prompts.isEmpty())
    }

    @Test
    fun `没有 prompt_order 时给空表（调用方回落到默认顺序）`() {
        val preset = PresetParser.parse("Bare", """{"temperature": 1.2}""")

        assertTrue(preset.order.isEmpty())
        assertEquals(1.2, preset.sampling.temperature, "其它字段照常解析")
    }

    @Test
    fun `不认识的字段一律忽略`() {
        val json = """
            {
              "chat_completion_source": "openai",
              "reverse_proxy": "https://example.invalid",
              "prompt_order": [{"character_id": 100000, "order": [{"identifier": "main", "enabled": true}]}]
            }
        """.trimIndent()

        val preset = PresetParser.parse("Unknown", json)

        assertEquals(1, preset.order.size)
        assertEquals("main", preset.order.single().identifier)
    }

    @Test
    fun `不是 JSON 时抛出可读的错误`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PresetParser.parse("Broken", "这不是 json")
        }
        assertTrue(error.message!!.contains("Broken"))
    }

    @Test
    fun `多个角色的顺序表时只取全局那一条`() {
        val json = """
            {
              "prompt_order": [
                {"character_id": 7, "order": [{"identifier": "main", "enabled": true}]},
                {"character_id": 100000, "order": [{"identifier": "scenario", "enabled": true}]}
              ]
            }
        """.trimIndent()

        val preset = PresetParser.parse("Multi", json)

        assertEquals(listOf(PromptOrderEntry("scenario", true)), preset.order)
    }
}
