package app.tavern

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tavern.core.ai.ChatRequest
import app.tavern.core.ai.OpenAiCompatibleClient
import app.tavern.core.ai.StreamEvent
import app.tavern.core.card.CharacterCard
import app.tavern.core.card.PngCard
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import app.tavern.core.prompt.PromptBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * M0 的真机验证用 ViewModel：随包带的卡 → 拼提示词 → 真发一次请求 → 流式显示。
 *
 * 两个刻意加的设计，都是为了"在手机上就能自证链路通不通"：
 * - 可以切换随包的卡（占位卡 / 原版默认卡），对比模型的表现；
 * - 能把**这次实际发出去的消息**原样打出来，不用去猜卡片注入有没有生效。
 *
 * 刻意简陋的地方：Key 只在内存、没有持久化、没有 swipe、没有多会话——那是 M1/M3。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val baseUrl: String = "https://api.deepseek.com/v1",
        val apiKey: String = "",
        val model: String = "deepseek-chat",
        val input: String = "你好，自我介绍一下？",
        val transcript: List<ChatMessage> = emptyList(),
        val streamingText: String = "",
        val isStreaming: Boolean = false,
        val status: String = "",
        val cardName: String = "",
        val requestPreview: String = "",
        val showSettings: Boolean = true,
        val showRequest: Boolean = false,
    )

    private val bundledCards = linkedMapOf(
        "Seraphina（真卡）" to "cards/seraphina.png",
        "Synthetic（占位卡）" to "cards/synthetic.png",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var card: CharacterCard? = null
    private var job: Job? = null

    init {
        selectCard("Seraphina（真卡）")
    }

    fun selectCard(name: String) {
        val asset = bundledCards[name] ?: return
        card = runCatching {
            getApplication<Application>().assets.open(asset).use { PngCard.read(it.readBytes()) }
        }.getOrNull()

        _state.update {
            it.copy(
                cardName = name,
                transcript = emptyList(),
                streamingText = "",
                status = card?.let { c ->
                    "卡片 $name：描述 ${c.description.length} 字 / 示例 ${c.messageExample.length} 字 / 世界书 ${if (c.characterBook != null) "有（未实现）" else "无"}"
                } ?: "读卡失败：$asset",
            )
        }
    }

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value) }
    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value) }
    fun onModelChange(value: String) = _state.update { it.copy(model = value) }
    fun onInputChange(value: String) = _state.update { it.copy(input = value) }
    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }
    fun toggleRequest() = _state.update { it.copy(showRequest = !it.showRequest) }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(isStreaming = false, status = "已停止") }
    }

    fun send() {
        val snapshot = _state.value
        val characterCard = card ?: return
        if (snapshot.input.isBlank() || snapshot.isStreaming) return

        val userMessage = ChatMessage(ChatRole.User, snapshot.input.trim())
        val history = snapshot.transcript + userMessage
        val messages = PromptBuilder().build(characterCard, userName = "User", history = history)

        _state.update {
            it.copy(
                transcript = history,
                streamingText = "",
                isStreaming = true,
                input = "",
                status = "已发 ${messages.size} 条消息（${snapshot.model}）",
                requestPreview = messages.joinToString("\n\n") { message ->
                    "[${message.role.wire}] ${message.content}"
                },
            )
        }

        val client = OpenAiCompatibleClient(baseUrl = snapshot.baseUrl, apiKey = snapshot.apiKey)
        val request = ChatRequest(
            model = snapshot.model,
            messages = messages,
            temperature = 1.0,
            maxTokens = 300,
            stream = true,
        )

        job = viewModelScope.launch {
            val buffer = StringBuilder()

            fun finish(text: String, status: String) {
                _state.update {
                    it.copy(
                        transcript = if (text.isEmpty()) {
                            it.transcript
                        } else {
                            it.transcript + ChatMessage(ChatRole.Assistant, text)
                        },
                        streamingText = "",
                        isStreaming = false,
                        status = status,
                    )
                }
            }

            fun fail(message: String) {
                // 把刚发出去的那条用户消息撤回来，输入框还原：否则用户重试一次就多一条
                _state.update {
                    it.copy(
                        transcript = it.transcript.dropLast(1),
                        streamingText = "",
                        isStreaming = false,
                        input = snapshot.input,
                        status = message,
                    )
                }
            }

            try {
                var settled = false
                client.stream(request).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            buffer.append(event.text)
                            _state.update { it.copy(streamingText = buffer.toString()) }
                        }

                        is StreamEvent.Completed -> {
                            settled = true
                            finish(buffer.toString(), "完成（finish_reason=${event.finishReason ?: "null"}）")
                        }

                        is StreamEvent.Failed -> {
                            settled = true
                            fail("失败（HTTP ${event.httpCode}）：${event.message}")
                        }
                    }
                }
                // 流结束了却没有任何终止事件：别让界面卡在"生成中"
                if (!settled) {
                    if (buffer.isEmpty()) {
                        fail("流意外结束：没有收到任何内容")
                    } else {
                        finish(buffer.toString(), "流意外结束（保留了已收到的内容）")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail("客户端异常：${error.message ?: error::class.simpleName}")
            }
        }
    }
}
