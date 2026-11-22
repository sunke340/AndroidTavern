package app.tavern.core.regex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 正则脚本的兼容断言：`fixtures/expected/P1-11-regex.json` 里的每条 `output` 都是
 * **原版 `getRegexedString()` 算出来的**，输入与脚本也一并记在夹具里。
 *
 * 覆盖：全局/首次替换、捕获组重排、`{{match}}`、`trimStrings`、disabled、
 * 以及 `promptOnly` / `markdownOnly` 的"哪一侧生效"（这个最容易做错）。
 */
class RegexEngineFixtureTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val repoRoot: Path = Path.of(System.getProperty("repo.root") ?: error("缺少 repo.root"))

    private class Case(
        val text: String,
        val placement: Int,
        val isMarkdown: Boolean,
        val isPrompt: Boolean,
        val output: String,
    )

    private fun load(): Pair<List<RegexScript>, List<Case>> {
        val file = repoRoot.resolve("fixtures/expected/P1-11-regex.json")
        require(Files.exists(file)) { "夹具不存在：$file（跑 tools/fixture/capture-regex.mjs）" }

        val root = json.parseToJsonElement(Files.readString(file)).jsonObject
        val scriptsJson = root["scripts"] as JsonArray
        val scripts = parseRegexScripts(scriptsJson.toString())

        val cases = root["cases"]!!.jsonArray.map { element ->
            val obj = element.jsonObject
            Case(
                text = obj["text"]!!.jsonPrimitive.content,
                placement = obj["placement"]!!.jsonPrimitive.content.toInt(),
                isMarkdown = obj["isMarkdown"]!!.jsonPrimitive.content.toBoolean(),
                isPrompt = obj["isPrompt"]!!.jsonPrimitive.content.toBoolean(),
                output = obj["output"]!!.jsonPrimitive.content,
            )
        }
        return scripts to cases
    }

    @Test
    fun `每种 placement 与显示或提示词组合都与原版一致`() {
        val (scripts, cases) = load()
        val engine = RegexEngine()

        for (case in cases) {
            val placement = RegexPlacement.fromValue(case.placement)
                ?: throw AssertionError("夹具里有未知 placement=${case.placement}")
            val actual = engine.applyAll(
                scripts = scripts,
                input = case.text,
                placement = placement,
                isMarkdown = case.isMarkdown,
                isPrompt = case.isPrompt,
            )
            assertEquals(
                case.output,
                actual,
                "placement=$placement markdown=${case.isMarkdown} prompt=${case.isPrompt} 文本「${case.text}」",
            )
        }
    }

    @Test
    fun `解析设置文件里的 regex 数组`() {
        val scripts = parseRegexScripts(
            """{"regex":[{"scriptName":"s1","findRegex":"/a/g","replaceString":"b","placement":[1]}]}""",
        )
        assertEquals(1, scripts.size)
        assertEquals("s1", scripts[0].name)
        assertEquals(setOf(RegexPlacement.UserInput), scripts[0].placement)
    }

    @Test
    fun `坏输入不会炸：非法正则原样返回`() {
        val engine = RegexEngine()
        val script = RegexScript(name = "bad", findRegex = "/[unclosed/", replaceString = "x", placement = setOf(RegexPlacement.UserInput))
        assertEquals("keep me", engine.applyAll(listOf(script), "keep me", RegexPlacement.UserInput))
    }
}
