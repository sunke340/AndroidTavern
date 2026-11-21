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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * M0 的真机验证用 ViewModel：一张随包带的卡 → 拼提示词 → 真的发一次请求 → 流式显示。
 *
 * 刻意简陋的地方：
 * - API Key 只在内存里，退出即失（铁律：不落明文）。真要持久化得走 Keystore。
 * - 没有多会话、没有 swipe、没有持久化——这些分别是 M1/M3 的事。
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
        val showSettings: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val card: CharacterCard? = runCatching {
        getApplication<Application>().assets.open("cards/synthetic.png").use { PngCard.read(it.readBytes()) }
    }.onFailure { error ->
        _state.update { it.copy(status = "读卡片失败：${error.message}") }
    }.getOrNull()

    private var job: Job? = null

    init {
        _state.update {
            it.copy(status = card?.let { c -> "卡片：${c.name}（${c.description.length} 字描述）" } ?: "没有卡片")
        }
    }

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value) }
    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value) }
    fun onModelChange(value: String) = _state.update { it.copy(model = value) }
    fun onInputChange(value: String) = _state.update { it.copy(input = value) }
    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }

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

        // 这里就是完整链路：卡 → 提示词 → 请求
        val messages = PromptBuilder().build(characterCard, userName = "User", history = history)

        _state.update {
            it.copy(
                transcript = history,
                streamingText = "",
                isStreaming = true,
                input = "",
                status = "已发 ${messages.size} 条消息（${snapshot.model}）",
            )
        }

        val client = OpenAiCompatibleClient(
            baseUrl = snapshot.baseUrl,
            apiKey = snapshot.apiKey,
        )
        val request = ChatRequest(
            model = snapshot.model,
            messages = messages,
            temperature = 1.0,
            maxTokens = 300,
            stream = true,
        )

        job = viewModelScope.launch {
            val buffer = StringBuilder()
            var failure: String? = null

            client.stream(request).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        buffer.append(event.text)
                        _state.update { it.copy(streamingText = buffer.toString()) }
                    }

                    is StreamEvent.Completed -> {
                        _state.update {
                            it.copy(
                                transcript = it.transcript + ChatMessage(ChatRole.Assistant, buffer.toString()),
                                streamingText = "",
                                isStreaming = false,
                                status = "完成（finish_reason=${event.finishReason ?: "null"}）",
                            )
                        }
                    }

                    is StreamEvent.Failed -> {
                        failure = event.message
                        _state.update {
                            it.copy(
                                streamingText = "",
                                isStreaming = false,
                                status = "失败（HTTP ${event.httpCode}）：${event.message}",
                            )
                        }
                    }
                }
            }

            if (failure != null) {
                _state.update { it.copy(input = snapshot.input) }
            }
        }
    }
}
