package app.tavern.core.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * 任何 OpenAI 兼容端点（OpenAI、DeepSeek、Ollama、LM Studio、OpenRouter、一票中转站…）。
 *
 * 三个刻意的设计：
 *
 * 1. **失败也走事件**（[StreamEvent.Failed]），不抛异常。聊天界面需要把"Key 无效"渲染成一条
 *    可重试的提示，而不是让协程崩掉。
 * 2. **取消 = 取消底层 HTTP**。`callbackFlow` 的 `awaitClose` 里 `eventSource.cancel()`，
 *    否则用户点了"停止"之后那条连接还在慢慢烧 token。
 * 3. **流式请求不设读超时**。LLM 首字可能要几十秒，OkHttp 默认 10s 会直接掐掉。
 */
public class OpenAiCompatibleClient private constructor(
    baseUrl: String,
    private val apiKey: String?,
    private val httpClient: OkHttpClient,
    private val extraHeaders: Map<String, String>,
) : ChatCompletionClient {

    /**
     * 公开构造刻意**不**暴露 OkHttp：`implementation` 依赖不该出现在公开签名里，
     * 否则每个用到本模块的模块都得自己去引 OkHttp（Android 上就是
     * "Cannot access class OkHttpClient. Check your module classpath"）。
     */
    public constructor(
        baseUrl: String,
        apiKey: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ) : this(baseUrl, apiKey, OkHttpClient(), extraHeaders)

    /** 测试用：注入自定义 client（同模块可见）。 */
    internal constructor(baseUrl: String, apiKey: String?, httpClient: OkHttpClient) :
        this(baseUrl, apiKey, httpClient, emptyMap())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val endpoint: String = baseUrl.trimEnd('/') + "/chat/completions"

    override fun stream(request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        val streamingClient = httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val httpRequest = buildRequest(request.copy(stream = true))
        val listener = object : EventSourceListener() {
            private var finished = false

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (finished) return
                if (data.trim() == "[DONE]") {
                    finished = true
                    trySend(StreamEvent.Completed())
                    close()
                    return
                }
                when (val event = parseStreamChunk(data)) {
                    is StreamEvent.Delta -> trySend(event)
                    is StreamEvent.Completed -> {
                        finished = true
                        trySend(event)
                        close()
                    }

                    else -> Unit // parseStreamChunk 不会产出 Failed，失败走 onFailure
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (finished) return
                finished = true
                val message = response?.let { readErrorMessage(it) } ?: t?.message ?: "网络错误"
                trySend(StreamEvent.Failed(message, response?.code))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                // 有些 provider 既不发 finish_reason 也不发 [DONE]，直接关连接。
                // 这里必须补一条 Completed，否则上层永远等不到终止事件，界面卡在"生成中"。
                if (!finished) {
                    finished = true
                    trySend(StreamEvent.Completed(null))
                    close()
                }
            }
        }

        val source = EventSources.createFactory(streamingClient).newEventSource(httpRequest, listener)
        awaitClose { source.cancel() }
    }

    override suspend fun complete(request: ChatRequest): ChatResult {
        val httpRequest = buildRequest(request.copy(stream = false))
        httpClient.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ChatCompletionException(readErrorMessage(response, body), response.code)
            }
            val root = json.parseToJsonElement(body) as? JsonObject
                ?: throw ChatCompletionException("响应不是合法 JSON：${body.take(200)}", response.code)
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw ChatCompletionException("响应里没有 choices：${body.take(200)}", response.code)
            val text = ((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }?.content.orEmpty()
            val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            return ChatResult(text = text, finishReason = finishReason)
        }
    }

    private fun buildRequest(request: ChatRequest): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .post(buildBody(request).toRequestBody(JSON_MEDIA_TYPE))
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer $apiKey")
        for ((name, value) in extraHeaders) builder.header(name, value)
        return builder.build()
    }

    internal fun buildBody(request: ChatRequest): String = buildJsonObject {
        put("model", request.model)
        putJsonArray("messages") {
            for (message in request.messages) {
                addJsonObject {
                    put("role", message.role.wire)
                    put("content", message.content)
                }
            }
        }
        request.temperature?.let { put("temperature", it) }
        request.topP?.let { put("top_p", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        request.frequencyPenalty?.let { put("frequency_penalty", it) }
        request.presencePenalty?.let { put("presence_penalty", it) }
        if (request.stop.isNotEmpty()) {
            putJsonArray("stop") { request.stop.forEach { add(it) } }
        }
        put("stream", request.stream)
        for ((key, value) in request.extraBody) put(key, value)
    }.toString()

    private fun parseStreamChunk(data: String): StreamEvent? {
        val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null

        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val content = ((choice["delta"] as? JsonObject)?.get("content") as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }?.content

        return when {
            !content.isNullOrEmpty() -> StreamEvent.Delta(content)
            finishReason != null -> StreamEvent.Completed(finishReason)
            else -> null
        }
    }

    private fun readErrorMessage(response: Response, body: String? = null): String {
        val text = body ?: runCatching { response.body?.string() }.getOrNull().orEmpty()
        val parsed = runCatching {
            ((json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonObject)
                ?.get("message")?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() }
            ?: text.take(300).takeIf { it.isNotBlank() }
            ?: "HTTP ${response.code} ${response.message}"
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
