package app.tavern

import android.app.Application
import android.net.Uri
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
import app.tavern.data.cards.CardRef
import app.tavern.data.cards.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天页的 ViewModel。
 *
 * 两个刻意保留的设计，都是为了"在手机上就能自证链路通不通"：
 * - 能把**这次实际发出去的消息**原样打出来，不用猜卡片注入有没有生效；
 * - 表层状态里带上卡名、消息条数，读卡失败会直接说出来而不是默默空白。
 *
 * 刻意简陋的地方：Key 只在内存、没有多会话、没有 swipe——那是后续里程碑。
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
        val cards: List<CardRef> = emptyList(),
        val cardId: String? = null,
        val cardName: String = "",
        val requestPreview: String = "",
        val showSettings: Boolean = true,
        val showRequest: Boolean = false,
        val importing: Boolean = false,
    )

    private val cardRepository = AppGraph.cards(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var card: CharacterCard? = null
    private var job: Job? = null

    init {
        viewModelScope.launch { reloadCards(select = null) }
    }

    /** 重新枚举卡；`select` 为空时优先回到上次选中的那张，再不行就第一张。 */
    private suspend fun reloadCards(select: String?) {
        val cards = withContext(Dispatchers.IO) { cardRepository.list() }
        _state.update { it.copy(cards = cards) }

        val target = select ?: cardRepository.selectedId ?: cards.firstOrNull()?.id
        if (target == null) {
            _state.update { it.copy(status = "没有可用的卡：assets/cards 下至少要有一张") }
            return
        }
        applyCard(target)
    }

    fun selectCard(id: String) {
        viewModelScope.launch { applyCard(id) }
    }

    private suspend fun applyCard(id: String) {
        val loaded = withContext(Dispatchers.IO) {
            val bytes = cardRepository.load(id) ?: return@withContext null
            runCatching { PngCard.read(bytes) }.getOrNull()
        }
        if (loaded == null) {
            _state.update { it.copy(status = "读卡失败：$id（文件损坏或不是角色卡）") }
            return
        }

        card = loaded
        cardRepository.selectedId = id
        _state.update {
            it.copy(
                cardId = id,
                cardName = loaded.name.ifBlank { id },
                transcript = emptyList(),
                streamingText = "",
                status = "卡：${loaded.name.ifBlank { id }}｜描述 ${loaded.description.length} 字" +
                    "／示例 ${loaded.messageExample.length} 字" +
                    "／世界书 ${if (loaded.characterBook != null) "有（未实现）" else "无"}",
            )
        }
    }

    /** 系统文件选择器返回后调用。导入成功后立刻切到这张卡。 */
    fun importCard(uri: Uri) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true, status = "正在导入…") }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { cardRepository.import(uri) }
            _state.update { it.copy(importing = false) }

            when (result) {
                is ImportResult.Ok -> {
                    val cards = withContext(Dispatchers.IO) { cardRepository.list() }
                    _state.update { it.copy(cards = cards, status = "已导入：${result.card.name}") }
                    applyCard(result.card.id)
                }

                is ImportResult.Failed ->
                    _state.update { it.copy(status = "导入失败：${result.reason}") }
            }
        }
    }

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value) }
    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value) }
    fun onModelChange(value: String) = _state.update { it.copy(model = value) }
    fun onInputChange(value: String) = _state.update { it.copy(input = value) }
    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }
    fun toggleRequest() = _state.update { it.copy(showRequest = !it.showRequest) }

    /**
     * 停止生成。已收到的半截内容**保留**成一条消息——原版就是这样，
     * 用户按停止往往是因为"已经够用了，别再烧 token"。
     */
    fun stop() {
        val partial = _state.value.streamingText
        job?.cancel()
        job = null
        _state.update {
            it.copy(
                transcript = if (partial.isBlank()) it.transcript
                else it.transcript + ChatMessage(ChatRole.Assistant, partial),
                streamingText = "",
                isStreaming = false,
                status = if (partial.isBlank()) "已停止" else "已停止（保留了已收到的 ${partial.length} 字）",
            )
        }
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
