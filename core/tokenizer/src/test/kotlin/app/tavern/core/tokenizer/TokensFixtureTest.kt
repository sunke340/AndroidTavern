package app.tavern.core.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 计数对齐原版：`fixtures/expected/P1-6-tokens.json` 是在原版页面里调
 * `getTokenCountAsync()` 实测出来的 (文本, 计数)，这里逐条比 jtokkit 的结果。
 *
 * 夹具的 `_meta` 没有记模型：录的时候页面停在 koboldhorde 上，原版对这种端点走的是
 * GPT-2/cl100k 的那条分支。断言用 cl100k 就是当时实际生效的那套词表；如果哪天换了模型
 * 重录，会先在这里红。
 */
class TokensFixtureTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val repoRoot: Path = Path.of(System.getProperty("repo.root") ?: error("缺少 repo.root"))

    @Test
    fun `与原版实测的 token 计数逐条相等`() {
        val file = repoRoot.resolve("fixtures/expected/P1-6-tokens.json")
        require(Files.exists(file)) { "夹具不存在：$file（跑 tools/fixture/capture-tokens.mjs）" }

        val root = json.parseToJsonElement(Files.readString(file)).jsonObject
        val cases = root["cases"]!!.jsonArray

        for (case in cases) {
            val obj = case.jsonObject
            val text = obj["text"]!!.jsonPrimitive.content
            val expected = obj["count"]!!.jsonPrimitive.content.toInt()
            // 夹具里录的是原版 getTokenCountAsync() 的结果，也就是"当一条消息算"的数（裸计数 + 6）
            val actual = Tokens.countAsMessage("gpt-4-turbo", text)

            assertEquals(expected, actual, "token 数不一致，文本前缀：「${text.take(40)}」")
        }
    }

    @Test
    fun `模型选词表：gpt-4 系走 cl100k，gpt-4o 走 o200k`() {
        assertEquals("CL100K_BASE", Tokens.encodingName("gpt-4-turbo"))
        assertEquals("CL100K_BASE", Tokens.encodingName("gpt-3.5-turbo"))
        assertEquals("O200K_BASE", Tokens.encodingName("gpt-4o"))
        assertEquals("O200K_BASE", Tokens.encodingName("o3-mini"))
        assertEquals("CL100K_BASE", Tokens.encodingName("unknown-model"))
    }

    @Test
    fun `裸计数与消息开销分开：一条消息比裸文本多 6`() {
        val raw = Tokens.count("gpt-4-turbo", "hello world")
        assertEquals(2, raw)
        assertEquals(raw + 6, Tokens.countAsMessage("gpt-4-turbo", "hello world"))
    }

    @Test
    fun `空串是 0`() {
        assertEquals(0, Tokens.count("gpt-4-turbo", ""))
    }
}
