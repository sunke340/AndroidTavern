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
import app.tavern.data.chat.ChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 聊天页的 ViewModel。
 *
 * 刻意保留的"自证"设计：能把**这次实际发出去的消息**原样打出来，不用猜卡片注入有没有生效；
 * 状态栏会直说读卡失败、写库失败，而不是默默空白。
 *
 * 持久化策略（见 ADR 0003）：会话与消息都在 SQLite 里，**DB 是唯一真相**；
 * 流式过程中只有内存里的半截文本，收到完成/停止才落库。
 *
 * 还没做的：Key 持久化（只在内存）、swipe 切换 UI、世界书。
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
        val sessions: List<ChatSession> = emptyList(),
        val sessionId: String? = null,
        val requestPreview: String = "",
        val showRequest: Boolean = false,
        val importing: Boolean = false,
        // ---- 界面结构（导航） ----
        val screen: Screen = Screen.Sessions,
        val chatOpen: Boolean = false,
        // ---- 采样参数（文本框保留原始输入，避免输入半截被强转） ----
        val temperatureText: String = "1.0",
        val maxTokensText: String = "0",
        val messageStyle: MessageStyle = MessageStyle.Card,
    )

    private val cardRepository = AppGraph.cards(application)
    private val chatRepository = AppGraph.chats(application)
    private val settingsRepository = AppGraph.settings(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var card: CharacterCard? = null
    private var job: Job? = null
    private var keySaveJob: Job? = null

    init {
        viewModelScope.launch {
            // 先把连接配置读回来：Key 存在 Keystore 加密存储里，重开 App 不该再要你重填
            val saved = withContext(Dispatchers.IO) { settingsRepository.load() }
            _state.update {
                it.copy(
                    baseUrl = saved.baseUrl,
                    apiKey = saved.apiKey,
                    model = saved.model,
                    temperatureText = saved.temperature.toString(),
                    maxTokensText = saved.maxTokens.toString(),
                    messageStyle = runCatching { MessageStyle.valueOf(saved.messageStyle) }
                        .getOrDefault(MessageStyle.Card),
                )
            }

            val cards = withContext(Dispatchers.IO) { cardRepository.list() }
            _state.update { it.copy(cards = cards) }

            val sessions = withContext(Dispatchers.IO) { chatRepository.sessions() }
            _state.update { it.copy(sessions = sessions) }

            // 冷启动直接回到最近一次会话（标题里带卡名与时间，一眼能认出来）
            val latest = sessions.firstOrNull()
            if (latest != null) {
                // 冷启动停在会话列表，不直接钻进聊天：从哪里来回哪里去
                openSessionInternal(latest.id, navigate = false)
                return@launch
            }

            val firstCard = cards.firstOrNull()
            if (firstCard == null) {
                _state.update { it.copy(status = "没有可用的卡：assets/cards 下至少要有一张") }
                return@launch
            }
            startNewSession(firstCard.id, firstCard.name)
        }
    }

    // ---- 卡 ----------------------------------------------------------------

    fun selectCard(id: String) {
        viewModelScope.launch {
            val loaded = loadCard(id)
            if (loaded == null) {
                _state.update { it.copy(status = "读卡失败：$id（文件损坏或不是角色卡）") }
                return@launch
            }
            card = loaded
            cardRepository.selectedId = id

            // 切卡 = 切到这张卡最近的会话；没有就新建（原版切角色也是这个行为）
            val recent = withContext(Dispatchers.IO) { chatRepository.sessions() }
                .firstOrNull { it.cardId == id }
            if (recent != null) {
                openSessionInternal(recent.id)
            } else {
                startNewSession(id, loaded.name.ifBlank { id })
            }
        }
    }

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
                    selectCard(result.card.id)
                }

                is ImportResult.Failed ->
                    _state.update { it.copy(status = "导入失败：${result.reason}") }
            }
        }
    }

    private suspend fun loadCard(id: String): CharacterCard? = withContext(Dispatchers.IO) {
        val bytes = cardRepository.load(id) ?: return@withContext null
        runCatching { PngCard.read(bytes) }.getOrNull()
    }

    // ---- 会话 --------------------------------------------------------------

    fun newSession() {
        val snapshot = _state.value
        val cardId = snapshot.cardId
        val cardName = snapshot.cardName
        if (cardId == null) {
            _state.update { it.copy(status = "还没有卡，先选一张卡") }
            return
        }
        viewModelScope.launch { startNewSession(cardId, cardName) }
    }

    fun openSession(id: String) {
        viewModelScope.launch { openSessionInternal(id, navigate = true) }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { chatRepository.deleteSession(id) }
            val sessions = withContext(Dispatchers.IO) { chatRepository.sessions() }
            _state.update { it.copy(sessions = sessions) }

            if (_state.value.sessionId != id) return@launch

            val next = sessions.firstOrNull()
            if (next != null) {
                openSessionInternal(next.id, navigate = false)
            } else {
                val cardId = _state.value.cardId
                if (cardId != null) {
                    startNewSession(cardId, _state.value.cardName)
                } else {
                    _state.update { it.copy(sessionId = null, transcript = emptyList(), status = "会话已删除") }
                }
            }
        }
    }

    private suspend fun startNewSession(cardId: String, cardName: String) {
        val title = "$cardName ${stamp()}"
        val session = withContext(Dispatchers.IO) {
            chatRepository.createSession(cardId = cardId, cardName = cardName, title = title)
        }
        refreshSessions()
        openSessionInternal(session.id)
    }

    private suspend fun openSessionInternal(id: String, navigate: Boolean = true) {
        val session = withContext(Dispatchers.IO) {
            chatRepository.sessions().firstOrNull { it.id == id }
        } ?: run {
            _state.update { it.copy(status = "会话已不存在：$id") }
            return
        }

        val stored = withContext(Dispatchers.IO) { chatRepository.messages(id) }
        val loadedCard = loadCard(session.cardId)
        card = loadedCard
        if (loadedCard != null) cardRepository.selectedId = session.cardId

        _state.update {
            it.copy(
                sessionId = id,
                cardId = session.cardId,
                cardName = loadedCard?.name?.ifBlank { session.cardName } ?: session.cardName,
                transcript = stored.map { message ->
                    ChatMessage(
                        role = if (message.isUser) ChatRole.User else ChatRole.Assistant,
                        content = message.content,
                    )
                },
                streamingText = "",
                isStreaming = false,
                chatOpen = navigate,
                status = buildString {
                    append("会话：${session.title}｜${stored.size} 条")
                    if (loadedCard == null) append("｜这张卡已不在，只能看，不能聊")
                },
            )
        }
    }

    private suspend fun refreshSessions() {
        val sessions = withContext(Dispatchers.IO) { chatRepository.sessions() }
        _state.update { it.copy(sessions = sessions) }
    }

    // ---- 输入与请求 --------------------------------------------------------

    fun onBaseUrlChange(value: String) {
        _state.update { it.copy(baseUrl = value) }
        persistConnection()
    }

    fun onModelChange(value: String) {
        _state.update { it.copy(model = value) }
        persistConnection()
    }

    /**
     * Key 逐字符变化，不能每敲一下就做一次 Keystore 加密（还浪费 IO），
     * 停手 600ms 再落盘。
     */
    fun onApiKeyChange(value: String) {
        _state.update { it.copy(apiKey = value) }
        keySaveJob?.cancel()
        keySaveJob = viewModelScope.launch {
            delay(600)
            withContext(Dispatchers.IO) { settingsRepository.saveApiKey(value) }
        }
    }

    private fun persistConnection() {
        val current = _state.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                settingsRepository.saveConnection(current.baseUrl, current.model)
            }
        }
    }
    fun onInputChange(value: String) = _state.update { it.copy(input = value) }
    fun showScreen(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun closeChat() = _state.update { it.copy(chatOpen = false) }

    fun toggleRequest() = _state.update { it.copy(showRequest = !it.showRequest) }

    fun onTemperatureChange(value: String) {
        _state.update { it.copy(temperatureText = value) }
        persistSampling(value, _state.value.maxTokensText)
    }

    fun onMaxTokensChange(value: String) {
        _state.update { it.copy(maxTokensText = value) }
        persistSampling(_state.value.temperatureText, value)
    }

    private fun persistSampling(temperatureText: String, maxTokensText: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 解析不了就先不存，等用户把数字打完
                settingsRepository.saveSampling(
                    temperature = temperatureText.toDoubleOrNull(),
                    maxTokens = maxTokensText.toIntOrNull(),
                )
            }
        }
    }

    fun onMessageStyleChange(style: MessageStyle) {
        _state.update { it.copy(messageStyle = style) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveMessageStyle(style.name) }
        }
    }

    /**
     * 停止生成。已收到的半截内容**保留**并落库——原版就是这样，
     * 用户按停止往往是因为"已经够用了，别再烧 token"。
     */
    fun stop() {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId
        val partial = snapshot.streamingText
        job?.cancel()
        job = null

        _state.update {
            it.copy(
                transcript = if (partial.isBlank()) it.transcript
                else it.transcript + ChatMessage(ChatRole.Assistant, partial),
                streamingText = "",
                isStreaming = false,
                status = if (partial.isBlank()) "已停止" else "已停止（保留了 ${partial.length} 字）",
            )
        }

        if (partial.isNotBlank() && sessionId != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { chatRepository.append(sessionId, isUser = false, content = partial) }
                refreshSessions()
            }
        }
    }

    fun send() {
        val snapshot = _state.value
        val characterCard = card ?: return
        val sessionId = snapshot.sessionId
        if (sessionId == null) {
            _state.update { it.copy(status = "还没有会话：先选一张卡或新建会话") }
            return
        }
        if (snapshot.input.isBlank() || snapshot.isStreaming) return
        if (snapshot.apiKey.isBlank()) {
            // 以前这里直接发出去，用户只会看到服务端 401，摸不着头脑
            _state.update { it.copy(status = "先填 API Key（设置里填一次就会加密记住）") }
            return
        }

        val text = snapshot.input.trim()
        val userMessage = ChatMessage(ChatRole.User, text)
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
            temperature = snapshot.temperatureText.toDoubleOrNull() ?: 1.0,
            // 0 = 不限：不往请求里放 max_tokens（以前硬编码 300，长回复会被截）
            maxTokens = snapshot.maxTokensText.toIntOrNull()?.takeIf { it > 0 },
            stream = true,
        )

        job = viewModelScope.launch {
            val buffer = StringBuilder()
            // 用户消息落库后的行号：失败时要撤回，所以得在下面的本地函数之前声明
            var userMessageId: Long? = null

            suspend fun finish(text: String, status: String) {
                if (text.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        chatRepository.append(sessionId, isUser = false, content = text)
                    }
                    refreshSessions()
                }
                _state.update { current ->
                    // 用户可能已经切到别的会话：那就只收尾全局状态，别把消息塞进新会话
                    if (current.sessionId != sessionId) {
                        current.copy(streamingText = "", isStreaming = false, status = status)
                    } else {
                        current.copy(
                            transcript = if (text.isEmpty()) {
                                current.transcript
                            } else {
                                current.transcript + ChatMessage(ChatRole.Assistant, text)
                            },
                            streamingText = "",
                            isStreaming = false,
                            status = status,
                        )
                    }
                }
            }

            suspend fun fail(message: String) {
                // 刚写进库的用户消息撤回，输入框还原：否则用户重试一次就多一条
                withContext(Dispatchers.IO) {
                    userMessageId?.let { runCatching { chatRepository.deleteMessage(it) } }
                }
                // 撤回后条数/排序要跟着变，否则会话列表里还显示着这条没发出去的消息
                refreshSessions()
                _state.update { current ->
                    if (current.sessionId != sessionId) {
                        current.copy(streamingText = "", isStreaming = false, status = message)
                    } else {
                        current.copy(
                            transcript = current.transcript.dropLast(1),
                            streamingText = "",
                            isStreaming = false,
                            input = snapshot.input,
                            status = message,
                        )
                    }
                }
            }

            // 用户消息先落库：进程被杀也不会丢"我说了什么"
            userMessageId = withContext(Dispatchers.IO) {
                runCatching { chatRepository.append(sessionId, isUser = true, content = text) }
                    .getOrNull()
                    ?.id
            }
            if (userMessageId == null) {
                _state.update {
                    it.copy(
                        transcript = it.transcript.dropLast(1),
                        streamingText = "",
                        isStreaming = false,
                        input = snapshot.input,
                        status = "写库失败：这条消息没有发出去",
                    )
                }
                return@launch
            }
            refreshSessions()

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
                            val hint = if (event.httpCode == 401) "｜Key 不对或已失效" else ""
                            fail("失败（HTTP ${event.httpCode}）$hint：${event.message}")
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

    private fun stamp(): String =
        formatter.format(Instant.ofEpochMilli(System.currentTimeMillis()))

    private companion object {
        val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)
            .withZone(ZoneId.systemDefault())
    }
}
