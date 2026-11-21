package app.tavern.core.ai

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiCompatibleClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OpenAiCompatibleClient(
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(vararg payloads: String): MockResponse {
        val body = payloads.joinToString("") { "data: $it\n\n" }
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    @Test
    fun `流式：增量按序到达，最后是 Completed`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"role":"assistant"},"index":0}]}""",
                """{"choices":[{"delta":{"content":"Hel"},"index":0}]}""",
                """{"choices":[{"delta":{"content":"lo"},"index":0}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop","index":0}]}""",
                "[DONE]",
            ),
        )

        val events = client.stream(request()).toList()

        val deltas = events.filterIsInstance<StreamEvent.Delta>().map { it.text }
        assertEquals(listOf("Hel", "lo"), deltas, "只有 role 的 chunk 不该产出一条空增量")
        val completed = events.filterIsInstance<StreamEvent.Completed>()
        assertEquals(1, completed.size, "finish_reason 与 [DONE] 只该给出一次结束")
        assertEquals("stop", completed.single().finishReason)
    }

    @Test
    fun `非流式：拿到完整文本与 finish_reason`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"完整回复"},"finish_reason":"stop"}]}""",
                ),
        )

        val result = client.complete(request(stream = false))

        assertEquals("完整回复", result.text)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `HTTP 错误：把 provider 的 message 交给调用方`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Invalid API key","type":"invalid_request_error"}}"""),
        )

        val events = client.stream(request()).toList()

        val failed = events.filterIsInstance<StreamEvent.Failed>().single()
        assertEquals("Invalid API key", failed.message)
        assertEquals(401, failed.httpCode)
    }

    @Test
    fun `请求体：字段名与夹具里原版发的一致`() = runBlocking {
        server.enqueue(sse("[DONE]"))

        val fixture = fixtureRequest()
        client.stream(fixture).take(1).toList()

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))

        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        // 只比我们声称兼容的那部分：字段名、类型、取值
        assertEquals("gpt-4-turbo", sent["model"]?.jsonPrimitive?.content)
        assertEquals(true, sent["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(1.0, sent["temperature"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(1.0, sent["top_p"]?.jsonPrimitive?.content?.toDouble())

        val sentMessages = sent["messages"] as JsonArray
        assertEquals(fixture.messages.size, sentMessages.size, "消息条数应与夹具一致")
        val first = sentMessages[0].jsonObject
        assertEquals("system", first["role"]?.jsonPrimitive?.content)
        assertTrue(first["content"]?.jsonPrimitive?.content?.startsWith("Write ") == true)
    }

    @Test
    fun `取消：只取一条就返回，不会挂住`() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"a"},"index":0}]}"""))

        val first = client.stream(request()).take(1).toList()

        assertEquals(1, first.size)
        assertIs<StreamEvent.Delta>(first.single())
        // 必须显式返回 Unit：`assertIs` 有返回值，若它是方法体最后一个表达式，
        // JUnit 5 会因为"测试方法非 void"而**静默跳过**这个方法（我就这么丢过一个测试）。
        Unit
    }

    private fun request(stream: Boolean = true) = ChatRequest(
        model = "gpt-4-turbo",
        messages = listOf(ChatMessage(ChatRole.User, "hi")),
        temperature = 1.0,
        topP = 1.0,
        maxTokens = 300,
        stream = stream,
    )

    /** 从夹具里还原出"原版那次请求"，用来对拍我们构造的请求体。 */
    private fun fixtureRequest(): ChatRequest {
        val root = Path.of(System.getProperty("repo.root")!!)
        val element = Json.parseToJsonElement(
            Files.readString(root.resolve("fixtures/expected/P0-5-prompt-basic.json")),
        ).jsonObject["request"]!!.jsonObject

        val messages = (element["messages"] as JsonArray).map { item ->
            val obj = item.jsonObject
            ChatMessage(
                role = ChatRole.fromWire(obj["role"]!!.jsonPrimitive.content),
                content = obj["content"]!!.jsonPrimitive.content,
            )
        }

        fun number(key: String): Double? = (element[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

        return ChatRequest(
            model = element["model"]!!.jsonPrimitive.content,
            messages = messages,
            temperature = number("temperature"),
            topP = number("top_p"),
            maxTokens = number("max_tokens")?.toInt(),
            frequencyPenalty = number("frequency_penalty"),
            presencePenalty = number("presence_penalty"),
            stream = true,
        )
    }
}
