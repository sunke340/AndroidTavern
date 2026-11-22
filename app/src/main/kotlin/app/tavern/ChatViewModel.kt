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
import app.tavern.core.card.bookEntries
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import app.tavern.core.model.ModelProfile
import app.tavern.core.model.ModelProfiles
import app.tavern.core.prompt.PromptBuilder
import app.tavern.data.cards.CardRef
import app.tavern.data.cards.ImportResult
import app.tavern.data.chat.ChatSession
import app.tavern.data.log.AppLog
import app.tavern.data.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
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

    /** 一次性提示（导入/删除的结果），用 Snackbar 显示；`id` 保证同一条不会重复弹。 */
    data class Notice(val id: Long, val text: String)

    /**
     * 正在编辑的模型档案（草稿）。
     *
     * 为什么用草稿而不是直接改库里的那行：Key 与请求头是手打的，边打边落库会出现
     * "打了一半的 Key 已经把能用的 Key 覆盖掉"。只有点「保存」才写库。
     */
    data class ProfileDraft(
        /** null = 新建。 */
        val id: String? = null,
        val name: String = "",
        val baseUrl: String = ModelProfiles.FALLBACK_BASE_URL,
        val model: String = ModelProfiles.FALLBACK_MODEL,
        val apiKey: String = "",
        /**
         * 思考强度预设：`""` = 不设，`low` / `medium` / `high`；**null = 自定义**（用 [effortCustom]）。
         *
         * 拆成两段是因为各家的值不统一（DeepSeek 认 `max`），用一个下拉框穷举不完，
         * 又不想让"自定义"变成一个用户看不懂的自由文本框。
         */
        val effortPreset: String? = "",
        /** 「自定义」时真正发给 provider 的字符串。 */
        val effortCustom: String = "",
        /** 「一行一条 Name: value」的原始文本。 */
        val headersText: String = "",
        val isDefault: Boolean = false,
    ) {
        val isNew: Boolean get() = id == null

        val canSave: Boolean
            get() = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
    }

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
        /** 设置里的二级页面；null = 一级列表。 */
        val settingsPage: SettingsPage? = null,
        /** 新建会话前先选卡：true 时显示角色卡选择弹层。 */
        val showCardPicker: Boolean = false,
        /** 角色卡配置的二级页（作用域：当前详情页那张卡）。 */
        val cardConfigPage: CardConfigPage? = null,
        /** 非 null 时显示角色详情页（覆盖在三栏之上，返回键先退它）。 */
        val detailCardId: String? = null,
        /** 详情页的完整卡信息；打开详情后异步填上。 */
        val detail: CardDetail? = null,
        /** 当前会话所属卡的样式覆盖；null = 跟随全局。 */
        val cardMessageStyle: MessageStyle? = null,
        // ---- 采样参数（文本框保留原始输入，避免输入半截被强转） ----
        val temperatureText: String = "1.0",
        val maxTokensText: String = "0",
        val messageStyle: MessageStyle = MessageStyle.Card,
        val themePreset: ThemePreset = ThemePreset.Violet,
        val themeMode: ThemeMode = ThemeMode.Follow,
        /** 二级页转场风格；见 [NavAnimation]。 */
        val navAnimation: NavAnimation = NavAnimation.Slide,
        /** 视觉效果档位；见 [VisualEffects]。 */
        val visualEffects: VisualEffects = VisualEffects.Glass,
        /** 全部模型档案（只在"模型"页与发送时用）。 */
        val profiles: List<ModelProfile> = emptyList(),
        /** 当前生效的档案 id；null = 一条都没有（老版本首次启动的瞬间）。 */
        val activeProfileId: String? = null,
        /** 非 null 时显示「档案编辑」页（三级）；null = 不在编辑。 */
        val profileDraft: ProfileDraft? = null,
        val notice: Notice? = null,
    ) {
        /** 聊天页实际用的样式：卡级覆盖优先，否则全局。 */
        val effectiveMessageStyle: MessageStyle
            get() = cardMessageStyle ?: messageStyle
    }

    private val cardRepository = AppGraph.cards(application)
    private val profileRepository: ProfileRepository = AppGraph.profiles(application)
    private val chatRepository = AppGraph.chats(application)
    private val settingsRepository = AppGraph.settings(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var noticeSeq = 0L
    private var card: CharacterCard? = null
    private var job: Job? = null
    private var keySaveJob: Job? = null

    init {
        viewModelScope.launch {
            // 先把连接配置读回来：Key 存在 Keystore 加密存储里，重开 App 不该再要你重填
            val saved = withContext(Dispatchers.IO) { settingsRepository.load() }

            // 模型档案：表里还没有档案就用老的单套配置种一条（幂等），
            // 之后**档案才是真相**，老字段留着只为回退时数据还在。
            val seeded = withContext(Dispatchers.IO) {
                profileRepository.ensureSeeded(saved.baseUrl, saved.model, saved.apiKey)
            }
            val profiles = withContext(Dispatchers.IO) {
                ModelProfiles.normalizeDefault(profileRepository.list())
            }
            val active = profiles.firstOrNull { it.isDefault }
            val activeKey = active
                ?.let { profile -> withContext(Dispatchers.IO) { profileRepository.apiKey(profile.id) } }
                .orEmpty()
            if (seeded) AppLog.i("profile", "用旧的单套配置种了默认档案 id=${active?.id}")
            AppLog.i("profile", "档案=${profiles.size} 当前=${active?.id ?: "无"} model=${active?.model ?: "无"}")
            AppLog.i(
                "ui",
                "主题=${saved.themePreset}/${saved.themeMode} 转场=${saved.navAnimation} " +
                    "视觉=${saved.visualEffects}",
            )

            _state.update {
                it.copy(
                    baseUrl = active?.baseUrl ?: saved.baseUrl,
                    apiKey = activeKey.ifBlank { saved.apiKey },
                    model = active?.model ?: saved.model,
                    profiles = profiles,
                    activeProfileId = active?.id,
                    temperatureText = saved.temperature.toString(),
                    maxTokensText = saved.maxTokens.toString(),
                    messageStyle = runCatching { MessageStyle.valueOf(saved.messageStyle) }
                        .getOrDefault(MessageStyle.Card),
                    themePreset = runCatching { ThemePreset.valueOf(saved.themePreset) }
                        .getOrDefault(ThemePreset.Violet),
                    themeMode = runCatching { ThemeMode.valueOf(saved.themeMode) }
                        .getOrDefault(ThemeMode.Follow),
                    navAnimation = runCatching { NavAnimation.valueOf(saved.navAnimation) }
                        .getOrDefault(NavAnimation.Slide),
                    visualEffects = runCatching { VisualEffects.valueOf(saved.visualEffects) }
                        .getOrDefault(VisualEffects.Glass),
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

            // 会话改成按需创建（从角色详情点"新建会话"）：删到 0 个也不会被自动补回来
            _state.update {
                it.copy(
                    status = if (cards.isEmpty()) {
                        "没有可用的卡：assets/cards 下至少要有一张"
                    } else {
                        "还没有会话：去「角色」选一张卡"
                    },
                )
            }
        }
    }

    // ---- 卡 ----------------------------------------------------------------

    fun openDetail(cardId: String) {
        _state.update { it.copy(detailCardId = cardId, detail = null) }
        viewModelScope.launch { loadDetail(cardId) }
    }

    fun closeDetail() {
        _state.update { it.copy(detailCardId = null, detail = null, cardConfigPage = null) }
    }

    private suspend fun loadDetail(cardId: String) {
        val card = loadCard(cardId) ?: run {
            _state.update { it.copy(status = "读卡失败：$cardId") }
            return
        }
        val imported = _state.value.cards.firstOrNull { it.id == cardId }?.imported ?: false
        val disabled = withContext(Dispatchers.IO) { settingsRepository.disabledBookEntries(cardId) }
        val style = withContext(Dispatchers.IO) { settingsRepository.cardMessageStyle(cardId) }
            ?.let { name -> runCatching { MessageStyle.valueOf(name) }.getOrNull() }

        val detail = CardDetail(
            id = cardId,
            name = card.name.ifBlank { cardId },
            imported = imported,
            description = card.description,
            personality = card.personality,
            scenario = card.scenario,
            firstMessage = card.firstMessage,
            messageExample = card.messageExample,
            alternateGreetings = card.alternateGreetings,
            creator = card.creator,
            characterVersion = card.characterVersion,
            spec = card.spec,
            specVersion = card.specVersion,
            tags = card.tags,
            bookEntries = card.bookEntries(),
            disabledEntries = disabled,
            messageStyle = style,
        )

        _state.update { current ->
            if (current.detailCardId == cardId) current.copy(detail = detail) else current
        }
    }

    /** 勾选/取消卡内嵌世界书的某一条。世界书引擎（P1）还没做，这里先把选择存下来。 */
    fun setBookEntryEnabled(cardId: String, entryIndex: Int, enabled: Boolean) {
        val detail = _state.value.detail ?: return
        val next = if (enabled) detail.disabledEntries - entryIndex else detail.disabledEntries + entryIndex

        _state.update { it.copy(detail = it.detail?.copy(disabledEntries = next)) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveDisabledBookEntries(cardId, next) }
        }
    }

    /** 卡级消息样式；传 null 表示跟随全局默认。 */
    fun setCardMessageStyle(cardId: String, style: MessageStyle?) {
        _state.update {
            it.copy(
                detail = it.detail?.copy(messageStyle = style),
                cardMessageStyle = if (it.cardId == cardId) style else it.cardMessageStyle,
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveCardMessageStyle(cardId, style?.name) }
        }
    }

    /**
     * 用某张卡开一个新会话，并进入聊天。
     *
     * 开场白按原版行为写成第一条消息——夹具 `fixtures/chats/P0-8-first-mes-chat.json`
     * 证明原版新建聊天时 chat[0] 就是 `first_mes`，且它会作为 assistant 进提示词历史。
     */
    fun startSessionForCard(cardId: String) {
        viewModelScope.launch {
            val loaded = loadCard(cardId)
            if (loaded == null) {
                _state.update { it.copy(status = "读卡失败：$cardId（文件损坏或不是角色卡）") }
                return@launch
            }
            card = loaded
            cardRepository.selectedId = cardId
            val displayName = _state.value.cards.firstOrNull { it.id == cardId }?.name
                ?: loaded.name.ifBlank { cardId }
            startNewSession(
                cardId = cardId,
                cardName = displayName,
                firstMessage = loaded.firstMessage,
                navigate = true,
            )
        }
    }

    /** 删卡：连带删掉它的全部会话（用户在 2026-11-22 确认这个语义）。 */
    fun deleteCard(cardId: String) {
        viewModelScope.launch {
            val removedSessions = withContext(Dispatchers.IO) {
                chatRepository.deleteSessionsForCard(cardId)
            }
            val deletedFile = withContext(Dispatchers.IO) { cardRepository.delete(cardId) }
            val cards = withContext(Dispatchers.IO) { cardRepository.list() }
            val sessions = withContext(Dispatchers.IO) { chatRepository.sessions() }

            val currentGone = _state.value.sessionId?.let { id -> sessions.none { it.id == id } } == true

            _state.update {
                it.copy(
                    cards = cards,
                    sessions = sessions,
                    detailCardId = null,
                    sessionId = if (currentGone) null else it.sessionId,
                    transcript = if (currentGone) emptyList() else it.transcript,
                    chatOpen = if (currentGone) false else it.chatOpen,
                )
            }
            AppLog.i("card", "删除卡 id=$cardId 连带会话=$removedSessions file=$deletedFile")
            if (deletedFile) {
                notify("已删除角色卡（连带 $removedSessions 个会话）")
            } else {
                notify("删卡失败：文件可能已经不在了")
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
                    AppLog.i("card", "导入成功 id=${result.card.id} book=${result.card.bookEntryCount}")
                    val cards = withContext(Dispatchers.IO) { cardRepository.list() }
                    _state.update { it.copy(cards = cards, detailCardId = result.card.id) }
                    notify("已导入：${result.card.name}")
                }

                is ImportResult.Failed -> {
                    AppLog.w("card", "导入失败 ${result.reason.take(120)}")
                    notify("导入失败：${result.reason}")
                }
            }
        }
    }

    private suspend fun loadCard(id: String): CharacterCard? = withContext(Dispatchers.IO) {
        val bytes = cardRepository.load(id) ?: return@withContext null
        runCatching { PngCard.read(bytes) }.getOrNull()
    }

    // ---- 会话 --------------------------------------------------------------

    /**
     * 新建会话：**先选卡**，不再默认上次那张。
     *
     * 以前直接拿当前卡建会话，很容易在"想换人聊"时建错地方；
     * 现在先弹角色卡网格，必须点一张才建。
     */
    fun newSession() {
        if (_state.value.cards.isEmpty()) {
            notify("还没有角色卡：先去「角色」导入一张")
            return
        }
        openCardPicker()
    }

    fun openSession(id: String) {
        viewModelScope.launch { openSessionInternal(id, navigate = true) }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { chatRepository.deleteSession(id) }
            val sessions = withContext(Dispatchers.IO) { chatRepository.sessions() }
            _state.update { it.copy(sessions = sessions) }
            AppLog.i("chat", "删除会话 id=$id")
            notify("会话已删除")

            if (_state.value.sessionId != id) return@launch

            // 允许删到 0 个会话：以前会"立刻补一个"，看起来像删不掉
            val next = sessions.firstOrNull()
            if (next != null) {
                openSessionInternal(next.id, navigate = false)
            } else {
                _state.update {
                    it.copy(
                        sessionId = null,
                        transcript = emptyList(),
                        streamingText = "",
                        isStreaming = false,
                        chatOpen = false,
                    )
                }
            }
        }
    }

    private suspend fun startNewSession(
        cardId: String,
        cardName: String,
        firstMessage: String,
        navigate: Boolean = true,
    ) {
        val title = "$cardName ${stamp()}"
        val session = withContext(Dispatchers.IO) {
            chatRepository.createSession(cardId = cardId, cardName = cardName, title = title)
        }
        // 原版行为：新聊天的第一条就是卡里的 first_mes（见 P0-8 夹具）
        if (firstMessage.isNotBlank()) {
            withContext(Dispatchers.IO) {
                chatRepository.append(session.id, isUser = false, content = firstMessage)
            }
        }
        refreshSessions()
        openSessionInternal(session.id, navigate = navigate)
    }

    private suspend fun openSessionInternal(id: String, navigate: Boolean = true) {
        val session = withContext(Dispatchers.IO) {
            chatRepository.sessions().firstOrNull { it.id == id }
        } ?: run {
            _state.update { it.copy(status = "会话已不存在：$id") }
            return
        }

        val stored = withContext(Dispatchers.IO) { chatRepository.messages(id) }
        val cardStyle = withContext(Dispatchers.IO) {
            settingsRepository.cardMessageStyle(session.cardId)
        }?.let { name -> runCatching { MessageStyle.valueOf(name) }.getOrNull() }
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
                cardMessageStyle = cardStyle,
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
        val profileId = _state.value.activeProfileId
        keySaveJob?.cancel()
        keySaveJob = viewModelScope.launch {
            delay(600)
            withContext(Dispatchers.IO) {
                // 两处都写：档案里是真相，老字段留着只为回退版本时还能用
                settingsRepository.saveApiKey(value)
                profileId?.let { profileRepository.saveApiKey(it, value) }
            }
        }
    }

    private fun persistConnection() {
        val current = _state.value
        val profileId = current.activeProfileId
        val baseUrl = current.baseUrl
        val model = current.model
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                settingsRepository.saveConnection(baseUrl, model)
                if (profileId != null) {
                    runCatching {
                        profileRepository.find(profileId)?.let { existing ->
                            profileRepository.save(existing.copy(baseUrl = baseUrl, model = model))
                        }
                    }
                }
            }
        }
    }
    fun onInputChange(value: String) = _state.update { it.copy(input = value) }
    fun showScreen(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun openCardConfigPage(page: CardConfigPage) = _state.update { it.copy(cardConfigPage = page) }

    fun closeCardConfigPage() = _state.update { it.copy(cardConfigPage = null) }

    fun openCardPicker() = _state.update { it.copy(showCardPicker = true) }

    fun closeCardPicker() = _state.update { it.copy(showCardPicker = false) }

    /** 选卡弹层里点了某张卡：关弹层 + 用它开新会话。 */
    fun pickCardAndStart(cardId: String) {
        _state.update { it.copy(showCardPicker = false) }
        startSessionForCard(cardId)
    }

    fun openSettingsPage(page: SettingsPage) = _state.update { it.copy(settingsPage = page) }

    fun closeSettingsPage() = _state.update { it.copy(settingsPage = null) }

    // ---- 模型档案（C 批） ---------------------------------------------------

    /** 打开档案编辑页；[profileId] 为 null 表示新建（预填默认值）。 */
    fun openProfileEditor(profileId: String?) {
        viewModelScope.launch {
            val existing = profileId?.let { id ->
                withContext(Dispatchers.IO) { profileRepository.find(id) }
            }
            val key = profileId
                ?.let { id -> withContext(Dispatchers.IO) { profileRepository.apiKey(id) } }
                .orEmpty()
            _state.update {
                it.copy(
                    profileDraft = ProfileDraft(
                        id = existing?.id,
                        name = existing?.name.orEmpty(),
                        baseUrl = existing?.baseUrl ?: ModelProfiles.FALLBACK_BASE_URL,
                        model = existing?.model ?: ModelProfiles.FALLBACK_MODEL,
                        apiKey = key,
                        effortPreset = presetOf(existing?.reasoningEffort),
                        effortCustom = existing?.reasoningEffort
                            ?.takeIf { it.isNotBlank() && it !in ModelProfiles.EFFORTS }
                            .orEmpty(),
                        headersText = existing?.headers
                            ?.let { headers -> ModelProfiles.formatHeaders(headers) }
                            .orEmpty(),
                        // 新建时默认打开"保存后设为默认"：建一条档案通常就是马上要用它
                        isDefault = existing?.isDefault ?: true,
                    ),
                )
            }
        }
    }

    fun closeProfileEditor() = _state.update { it.copy(profileDraft = null) }

    /** 库里存的原始值 → 界面上的预设；认不出来的（`max` 等）落到「自定义」。 */
    private fun presetOf(effort: String?): String? = when {
        effort.isNullOrBlank() -> ""
        effort in ModelProfiles.EFFORTS -> effort
        else -> null
    }

    /** 改草稿里的某个字段（界面上每个输入框都调这一条，不再各开一个 setter）。 */
    fun editProfileDraft(transform: (ProfileDraft) -> ProfileDraft) = _state.update { current ->
        current.copy(profileDraft = current.profileDraft?.let(transform))
    }

    fun saveProfileDraft() {
        val draft = _state.value.profileDraft ?: return
        if (!draft.canSave) {
            notify("名称 / Base URL / 模型 ID 不能为空")
            return
        }
        viewModelScope.launch {
            val id = draft.id ?: UUID.randomUUID().toString()
            // 一条都没有的时候，新建的就该是默认，否则会出现"有档案但没有默认"
            val profile = ModelProfile(
                id = id,
                name = draft.name.trim(),
                baseUrl = draft.baseUrl.trim(),
                model = draft.model.trim(),
                reasoningEffort = (if (draft.effortPreset == null) draft.effortCustom else draft.effortPreset)
                    .trim()
                    .takeIf { it.isNotBlank() },
                headers = ModelProfiles.parseHeaders(draft.headersText),
                isDefault = draft.isDefault || _state.value.profiles.isEmpty(),
            )
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    profileRepository.save(profile)
                    profileRepository.saveApiKey(id, draft.apiKey)
                }.isSuccess
            }
            if (!saved) {
                notify("保存失败：写数据库出错")
                return@launch
            }
            AppLog.i(
                "profile",
                "保存 id=$id name=${profile.name} 默认=${profile.isDefault} " +
                    "头=${profile.headers.size} 思考=${profile.reasoningEffort ?: "无"}",
            )
            refreshProfiles()
            _state.update { it.copy(profileDraft = null) }
            notify("已保存「${profile.name}」")
        }
    }

    /** 把某条设为默认（同时把连接配置镜像同步过去）。 */
    fun setDefaultProfile(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { profileRepository.setDefault(id) } }
            AppLog.i("profile", "切换默认 id=$id")
            refreshProfiles()
            _state.update { current ->
                val draft = current.profileDraft
                current.copy(profileDraft = draft?.copy(isDefault = draft.id == id))
            }
        }
    }

    /** 删档案：连带删掉它的 Key（不可逆，界面侧必须二次确认）。 */
    fun deleteProfile(id: String) {
        viewModelScope.launch {
            val name = _state.value.profiles.firstOrNull { it.id == id }?.name ?: id
            val removed = withContext(Dispatchers.IO) {
                runCatching { profileRepository.delete(id) }.isSuccess
            }
            AppLog.i("profile", "删除 id=$id ok=$removed")
            refreshProfiles()
            _state.update { it.copy(profileDraft = null) }
            notify(if (removed) "已删除「$name」" else "删除失败")
        }
    }

    /** 重新读档案表，并把"当前档案"镜像到 baseUrl / model / apiKey（发送时用的就是这三个）。 */
    private suspend fun refreshProfiles() {
        val profiles = withContext(Dispatchers.IO) {
            ModelProfiles.normalizeDefault(profileRepository.list())
        }
        val active = profiles.firstOrNull { it.isDefault }
        val key = active
            ?.let { profile -> withContext(Dispatchers.IO) { profileRepository.apiKey(profile.id) } }
            .orEmpty()
        _state.update { current ->
            current.copy(
                profiles = profiles,
                activeProfileId = active?.id,
                baseUrl = active?.baseUrl ?: current.baseUrl,
                model = active?.model ?: current.model,
                apiKey = if (active != null) key else current.apiKey,
            )
        }
    }

    private fun notify(text: String) {
        noticeSeq += 1
        _state.update { it.copy(notice = Notice(noticeSeq, text)) }
    }

    fun consumeNotice(id: Long) {
        _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

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

    /** 换主题：预设与深浅模式各自独立保存，互不覆盖。 */
    fun setThemePreset(preset: ThemePreset) {
        AppLog.i("ui", "主题 preset=${preset.name}")
        _state.update { it.copy(themePreset = preset) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveTheme(preset = preset.name, mode = null) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        AppLog.i("ui", "深浅 mode=${mode.name}")
        _state.update { it.copy(themeMode = mode) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveTheme(preset = null, mode = mode.name) }
        }
    }

    /** 换视觉效果档位（低端机滚不动时关掉模糊）。 */
    fun setVisualEffects(effects: VisualEffects) {
        AppLog.i("ui", "视觉效果 effect=${effects.name}")
        _state.update { it.copy(visualEffects = effects) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveVisualEffects(effects.name) }
        }
    }

    /** 换二级页转场风格（返回观感是主观的，交给用户自己拨）。 */
    fun setNavAnimation(animation: NavAnimation) {
        AppLog.i("ui", "转场 animation=${animation.name}")
        _state.update { it.copy(navAnimation = animation) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveNavAnimation(animation.name) }
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

        AppLog.i(
            "chat",
            "发送 model=${snapshot.model} messages=${messages.size} repeat=${history.count { it.role == ChatRole.User }}",
        )
        // 自定义请求头与思考强度跟着当前档案走（中转站的 X-Title、推理模型的 reasoning_effort）
        val activeProfile = snapshot.profiles.firstOrNull { it.id == snapshot.activeProfileId }
        val client = OpenAiCompatibleClient(
            baseUrl = snapshot.baseUrl,
            apiKey = snapshot.apiKey,
            extraHeaders = activeProfile?.headers.orEmpty(),
        )
        val request = ChatRequest(
            model = snapshot.model,
            messages = messages,
            temperature = snapshot.temperatureText.toDoubleOrNull() ?: 1.0,
            // 0 = 不限：不往请求里放 max_tokens（以前硬编码 300，长回复会被截）
            maxTokens = snapshot.maxTokensText.toIntOrNull()?.takeIf { it > 0 },
            stream = true,
            extraBody = activeProfile?.reasoningEffort
                ?.takeIf { it.isNotBlank() }
                ?.let { mapOf("reasoning_effort" to JsonPrimitive(it)) }
                .orEmpty(),
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
                            AppLog.i(
                                "chat",
                                "完成 finish=${event.finishReason ?: "null"} chars=${buffer.length}",
                            )
                            finish(buffer.toString(), "完成（finish_reason=${event.finishReason ?: "null"}）")
                        }

                        is StreamEvent.Failed -> {
                            settled = true
                            AppLog.e(
                                "chat",
                                "失败 http=${event.httpCode} msg=${event.message.take(200)}",
                            )
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
                AppLog.e("chat", "客户端异常", error)
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
