package app.tavern.core.regex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 强调语义与原版对齐：夹具 `P1-12-inline-markup.json` 是**原版 SillyTavern 1.19.1** 的
 * `showdown` + `showdown-underscore` 对同一段文本渲染出的 HTML（`tools/fixture` 之外的
 * 一次性录制，来源写在夹具的"来源"字段里）。
 *
 * 我们不追求 HTML 逐字节一致（原版还会渲染列表/标题等我们没做的排版），只断言
 * **斜体/粗体/换行的位置**：把夹具 HTML 里的 `<em>/<strong>/<br>` 映射成同样的
 * [InlineRun] 列表，再和"内置正则 → [InlineMarkup]"的结果逐段比。
 */
class InlineMarkupFixtureTest {

    private val cases: List<Pair<String, String>> by lazy {
        val file = repoRoot.resolve("fixtures/expected/P1-12-inline-markup.json")
        require(Files.exists(file)) { "夹具不存在：$file" }
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        root["cases"]!!.jsonArray.map { element ->
            val obj = element.jsonObject
            obj["input"]!!.jsonPrimitive.content to obj["html"]!!.jsonPrimitive.content
        }
    }

    @Test
    fun `内置强调规则渲染出的样式与原版一致`() {
        val engine = RegexEngine()
        for ((input, html) in cases) {
            val rendered = engine.applyAll(
                scripts = BuiltinRegex.rules(emptySet()),
                input = input,
                placement = RegexPlacement.AiOutput,
            )
            val actual = InlineMarkup.parse(rendered)
            val expected = htmlToRuns(html)
            assertEquals(expected, actual, "输入：$input")
        }
    }

    /** 夹具 HTML → 片段：只映射我们认的那几种标签（`<p>` 这种包裹层直接跳过）。 */
    private fun htmlToRuns(html: String): List<InlineRun> {
        val out = mutableListOf<InlineRun>()
        var italic = false
        var bold = false
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isEmpty()) return
            out += InlineRun(buffer.toString(), italic = italic, bold = bold)
            buffer.setLength(0)
        }

        var i = 0
        while (i < html.length) {
            val ch = html[i]
            if (ch != '<') {
                buffer.append(ch)
                i++
                continue
            }
            val close = html.indexOf('>', i + 1)
            if (close < 0) {
                buffer.append(html, i, html.length)
                break
            }
            val raw = html.substring(i + 1, close).trim()
            val closing = raw.startsWith("/")
            val name = raw.trimStart('/').substringBefore(' ').lowercase()
            when (name) {
                "em", "i" -> {
                    flush()
                    italic = !closing
                }

                "strong", "b" -> {
                    flush()
                    bold = !closing
                }

                "br" -> {
                    flush()
                    out += InlineRun("", lineBreak = true)
                }

                else -> Unit // <p> / </p> 之类直接忽略
            }
            i = close + 1
        }
        flush()
        return out
    }

    private companion object {
        val repoRoot: Path = Path.of(
            System.getProperty("repo.root")
                ?: error("缺少 repo.root 系统属性，检查 core/regex/build.gradle.kts 的 tasks.test"),
        )
    }
}
