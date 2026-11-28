package app.tavern

import android.app.Application
import android.os.SystemClock
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tavern.core.ai.ChatRequest
import app.tavern.core.ai.OpenAiCompatibleClient
import app.tavern.core.ai.StreamEvent
import app.tavern.core.card.CardBookEntry
import app.tavern.core.card.CharacterCard
import app.tavern.core.chat.ChatFile
import app.tavern.core.generation.GenerationRound
import app.tavern.core.generation.RowAction
import app.tavern.core.generation.SettleEffects
import app.tavern.core.generation.SettleReason
import app.tavern.core.generation.SettleStatus
import app.tavern.core.generation.UiFacts
import app.tavern.core.generation.WritePlan
import app.tavern.core.generation.WriteResult
import app.tavern.core.chat.ChatMessage as ChatFileMessage
import app.tavern.core.card.PngCard
import app.tavern.core.card.WorldBookFile
import app.tavern.core.card.bookEntries
import app.tavern.core.card.lorebookEntries
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import app.tavern.core.model.ModelProfile
import app.tavern.core.model.ModelProfiles
import app.tavern.core.lorebook.GlobalBook
import app.tavern.core.lorebook.LorebookEntry
import app.tavern.core.lorebook.LorebookScanResult
import app.tavern.core.lorebook.LorebookScanState
import app.tavern.core.lorebook.LorebookScanStateCodec
import app.tavern.core.lorebook.LorebookTokenCounter
import app.tavern.core.lorebook.WorldInfoInputs
import app.tavern.core.lorebook.WorldInfoMessage
import app.tavern.core.lorebook.withDisabled
import app.tavern.core.preset.PresetDefaults
import app.tavern.core.preset.PromptOrderCompat
import app.tavern.core.prompt.PromptBuilder
import app.tavern.core.regex.RegexEngine
import app.tavern.core.regex.RegexPlacement
import app.tavern.core.tokenizer.Tokens
import app.tavern.data.cards.ImportResult
import app.tavern.data.chat.ImportMessage
import app.tavern.data.log.AppLog
import app.tavern.data.settings.Persona
import app.tavern.data.sessionstate.SessionScanStateRepository
import app.tavern.core.model.ConfigOverride
import app.tavern.data.config.ConfigOverrideRepository
import app.tavern.data.settings.CardBookOverride
import app.tavern.data.presets.PresetRepository
import app.tavern.data.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Locale

/** 世界书条目页右上角菜单的三个批量动作（全局书与卡内嵌书共用）。 */
enum class BookBulkMode(
    /** 菜单里的文字。 */
    val label: String,
) {
    AllOn("全部开启"),
    AllOff("全部关闭"),
    Default("恢复默认"),
}

/**
 * 聊天页的 ViewModel。
 *
 * 刻意保留的"自证"设计：能把**这次实际发出去的消息**原样打出来，不用猜卡片注入有没有生效；
 * 状态栏会直说读卡失败、写库失败，而不是默默空白。
 *
 * 持久化策略（见 ADR 0003）：会话与消息都在 SQLite 里，**DB 是唯一真相**；
 * 流式过程中只有内存里的半截文本，收到完成/停止才落库。
 *
 * 还没做的：swipe 切换 UI、全局世界书（现在只吃卡内嵌书）、世界书的 sticky/cooldown
 * 计时状态还没落库（只在内存里跟会话走，杀进程会重置）。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * 当前打开那本书的解析结果：条目页搜索（正文命中）与"看全文"都从这里取，
     * 不重复读文件，也不把整本正文塞进 UiState（那会让每次重组都拖着几 MB）。
     */
    private var openBook: WorldBookFile? = null
    private var openBookName: String? = null
    private var openBookEntries: List<WorldBookEntryView> = emptyList()

    private val cardRepository = AppGraph.cards(application)
    private val profileRepository: ProfileRepository = AppGraph.profiles(application)
    private val chatRepository = AppGraph.chats(application)
    private val settingsRepository = AppGraph.settings(application)
    private val worldBookRepository = AppGraph.worldBooks(application)
    private val regexRepository = AppGraph.regex(application)
    private val presetRepository: PresetRepository = AppGraph.presets(application)
    private val configOverrides: ConfigOverrideRepository = AppGraph.configOverrides(application)
    private val sessionScanStates: SessionScanStateRepository = AppGraph.sessionScanStates(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var noticeSeq = 0L

    /**
     * **当前会话**那张卡的原始 JSON。发请求（提示词、世界书）只认它。
     *
     * ⚠️ 详情页浏览**不能**覆盖它：那是另一张卡的事，覆盖了就会出现"聊 A、请求里却是 B"。
     */
    private var card: CharacterCard? = null

    /**
     * **详情页当前打开**那张卡的原始 JSON，卡编辑保存的底本。
     *
     * 为什么要单独存：以前编辑保存用的是 [card]（会话那张），而 [loadDetail] 又不刷新它 ——
     * 先聊 Seraphina、再打开 Synthetic 的详情去编辑，保存时会把 Seraphina 的整份 JSON
     * （连同内嵌世界书）写进 Synthetic（2026-11-25 真机报的正是这个）。
     * 现在详情一打开就记下"这份 JSON 是哪张卡的"，保存前再核对一次。
     */
    private var detailRaw: CharacterCard? = null
    private var detailRawId: String? = null

    /**
     * 当前这一轮生成**归谁**（审计 P1-1；P0 换成 `core/generation` 的提交权）。
     *
     * 以前只有一个 `job: Job?` + 一个 `AtomicBoolean written`：
     * - `written` 同时干三件事（互斥 / 幂等 / "已落库"标记），而且 `stop()` 只能在 CAS **之前**
     *   `cancel()` 才能立刻停流 —— 取消会让已经写完库的收尾协程在 `withContext` 返回时抛
     *   `CancellationException`，界面更新被跳过（"回复在库里、界面没有，重进会话又出现"）。
     * 现在提交权是 `GenerationRound` 里的一个 `AtomicReference<CommitPhase>`：
     * **抢到权的那一方才可以 cancel，也只有它可以写库、可以收界面。**
     */
    private var currentRound: GenerationRound? = null

    /** 这一轮的 job（和 [currentRound] 分开：核心模块不认识协程类型）。 */
    private var roundJob: Job? = null

    /** 轮次号：只增不减，用来判断"我还是不是当前那一轮"。 */
    private var roundSeq: Long = 0L

    /**
     * 行身份序号（K3：命名空间不重叠）。
     *
     * `m{dbId}` 从库里读出来的 / `u{seq}` 本次发的用户消息 / `p{seq}` 生成行 / `r{seq}` 还原。
     * K2：只要求在一次 ChatScreen 组合内唯一，所以**不持久化**。
     */
    private var uiKeySeq: Long = 0L

    private fun newUiKey(prefix: String): String = prefix + (++uiKeySeq)
    private var keySaveJob: Job? = null

    init {
        // 首帧就要用的外观项：同步读偏好（**不碰 Keystore**）。否则第一帧只能按默认值画
        // ——「一进来是轻量主题、一瞬间后变玻璃」就是这么来的（用户 2026-11-24 报）。
        val startupAt = SystemClock.elapsedRealtime()
        // 内置显示正则的开关是同步读偏好：显示路径一进来就要用，别等异步
        _state.update { it.copy(builtinRegexDisabled = settingsRepository.builtinRegexDisabled()) }
        applyAppearanceSync()
        AppLog.i("startup", "外观预读 ${SystemClock.elapsedRealtime() - startupAt}ms")

        refreshWorldBooks()
        refreshRegexScripts()
        refreshPresets()

        // 会话列表**单独先发**：它是一级页首屏的内容，不该排在 Key 解密、档案种子后面。
        viewModelScope.launch {
            val sessionsAt = SystemClock.elapsedRealtime()
            val sessions = withContext(Dispatchers.IO) {
                runCatching { chatRepository.sessions() }.getOrDefault(emptyList())
            }
            // 首屏要用的头像先解好，再发布会话列表：否则每行都是"占位块 → 淡入图片"，
            // 十几行各解各的，看着就是进去时头像一块块闪（用户 2026-11-24 报）。
            runCatching {
                preloadAvatars(getApplication<Application>(), sessions.map { it.cardId })
            }.onFailure { AppLog.w("startup", "头像预热失败：${it.message}") }
            // 列表与头像都就绪了，才允许界面露出来（见 [UiState.booted]）
            _state.update { it.copy(sessions = sessions, sessionsLoaded = true, booted = true) }
            AppLog.i("startup", "首屏就绪 +${SystemClock.elapsedRealtime() - startupAt}ms（界面开始淡入）")
            AppLog.i(
                "startup",
                "会话就绪 +${SystemClock.elapsedRealtime() - startupAt}ms" +
                    "（本步 ${SystemClock.elapsedRealtime() - sessionsAt}ms，${sessions.size} 条）",
            )
            // 冷启动只把最近一个会话打开，停在列表（不钻进聊天）
            sessions.firstOrNull()?.let { openSessionInternal(it.id, navigate = false) }
        }

        viewModelScope.launch {
            // 连接配置：Key 存在 Keystore 加密存储里，重开 App 不该再要你重填。
            // 解密慢（冷启动几十~几百 ms），所以排在会话后面；外观项上面已经摆好了。
            val settingsAt = SystemClock.elapsedRealtime()
            val saved = withContext(Dispatchers.IO) { settingsRepository.load() }
            AppLog.i(
                "startup",
                "配置解密 +${SystemClock.elapsedRealtime() - startupAt}ms" +
                    "（本步 ${SystemClock.elapsedRealtime() - settingsAt}ms）",
            )

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
                    topPText = saved.topP?.toString().orEmpty(),
                    frequencyPenaltyText = saved.frequencyPenalty?.toString().orEmpty(),
                    presencePenaltyText = saved.presencePenalty?.toString().orEmpty(),
                    maxTokensText = saved.maxTokens.toString(),
                    // 外观已经由 applyAppearanceSync 摆好；这里以同一份偏好再写一次（幂等），
                    // 免得"预读"和"正式读"哪天不一致。
                    messageStyle = runCatching { MessageStyle.valueOf(saved.messageStyle) }
                        .getOrDefault(MessageStyle.Card),
                    revealSpeed = RevealSpeed.fromName(saved.revealSpeed),
                    personas = saved.personas,
                    personaDefaultId = saved.personaDefaultId,
                    themePreset = runCatching { ThemePreset.valueOf(saved.themePreset) }
                        .getOrDefault(ThemePreset.Violet),
                    themeMode = runCatching { ThemeMode.valueOf(saved.themeMode) }
                        .getOrDefault(ThemeMode.Follow),
                    navAnimation = runCatching { NavAnimation.valueOf(saved.navAnimation) }
                        .getOrDefault(NavAnimation.Slide),
                    visualEffects = runCatching { VisualEffects.valueOf(saved.visualEffects) }
                        .getOrDefault(VisualEffects.Off),
                    bubbleFontSize = saved.bubbleFontSize ?: DefaultBubbleFontSize,
                )
            }

            // 首屏会话是先于档案打开的（会话列表不等 Key 解密），那一趟拿不到档案表，
            // 会话级"指定模型"会被跳过。档案摆好之后补一次，别让用户看到"切了模型没生效"。
            _state.value.sessionId?.let { openedId ->
                val wanted = withContext(Dispatchers.IO) {
                    configOverrides.load(ConfigOverrideRepository.session(openedId)).profileId
                }
                if (wanted != null) applySessionProfileOverride(wanted)
            }

            val cardsAt = SystemClock.elapsedRealtime()
            val cards = withContext(Dispatchers.IO) { cardRepository.list() }
            _state.update { it.copy(cards = cards, cardsLoaded = true) }
            AppLog.i(
                "startup",
                "卡就绪 +${SystemClock.elapsedRealtime() - startupAt}ms（本步 ${SystemClock.elapsedRealtime() - cardsAt}ms，${cards.size} 张）",
            )

            // 会话已经由上面那一发读过，这里不再查库；只补一句空状态说明
            val current = _state.value
            if (current.sessionsLoaded && current.sessions.isEmpty()) {
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
    }

    /**
     * 首帧外观同步落地（主题 / 深浅 / 转场 / 视觉档 / 对话样式）。
     *
     * 只读 SharedPreferences，微秒级；**故意绕开** [SettingsRepository.load] 里的 Keystore 解密。
     * 这几项决定第一帧长什么样，等解密回来再设置就是"闪一下"。
     */
    private fun applyAppearanceSync() {
        runCatching {
            val look = settingsRepository.uiAppearance()
            _state.update {
                it.copy(
                    themePreset = runCatching { ThemePreset.valueOf(look.themePreset) }
                        .getOrDefault(ThemePreset.Violet),
                    themeMode = runCatching { ThemeMode.valueOf(look.themeMode) }
                        .getOrDefault(ThemeMode.Follow),
                    navAnimation = runCatching { NavAnimation.valueOf(look.navAnimation) }
                        .getOrDefault(NavAnimation.Slide),
                    visualEffects = runCatching { VisualEffects.valueOf(look.visualEffects) }
                        .getOrDefault(VisualEffects.Off),
                    messageStyle = runCatching { MessageStyle.valueOf(look.messageStyle) }
                        .getOrDefault(MessageStyle.Card),
                    bubbleFontSize = look.bubbleFontSize ?: DefaultBubbleFontSize,
                )
            }
        }.onFailure { AppLog.w("ui", "外观预读失败：${it.message}") }
    }

    // ---- 卡 ----------------------------------------------------------------

    fun openDetail(cardId: String) {
        _state.update {
            it.copy(
                detailCardId = cardId,
                detail = null,
                // 换一张卡才回到第一个标签；同一张卡（比如从二级页返回）保持原标签
                detailTab = if (it.detailCardId == cardId) it.detailTab else DetailTab.Description,
            )
        }
        viewModelScope.launch {
            // 卡级覆盖（模型/预设/世界书/正则）跟着详情页一起进来：卡配置页要显示它
            val stored = withContext(Dispatchers.IO) {
                runCatching { configOverrides.load(ConfigOverrideRepository.card(cardId)) }
                    .getOrDefault(ConfigOverride())
            }
            _state.update { it.copy(cardOverride = stored) }
            loadDetail(cardId)
        }
    }

    /** 详情页切标签。 */
    fun selectDetailTab(tab: DetailTab) = _state.update { it.copy(detailTab = tab) }

    fun closeDetail() {
        _state.update {
            it.copy(
                detailCardId = null,
                detail = null,
                cardConfigPage = null,
                edit = it.edit.copy(cardEditOpen = false),
                configPicker = null,
            )
        }
    }

    private suspend fun loadDetail(cardId: String) {
        val startedAt = SystemClock.elapsedRealtime()
        val card = loadCard(cardId) ?: run {
            _state.update { it.copy(status = "读卡失败：$cardId") }
            return
        }
        val imported = _state.value.cards.firstOrNull { it.id == cardId }?.imported ?: false
        val entries = card.bookEntries()
        val override = withContext(Dispatchers.IO) { bookOverrideOf(cardId, entries) }
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
            systemPrompt = card.systemPrompt,
            postHistoryInstructions = card.postHistoryInstructions,
            alternateGreetings = card.alternateGreetings,
            creator = card.creator,
            characterVersion = card.characterVersion,
            spec = card.spec,
            specVersion = card.specVersion,
            tags = card.tags,
            bookEntries = entries,
            offUids = override.off,
            forcedUids = override.forcedOn,
            messageStyle = style,
        )

        _state.update { current ->
            if (current.detailCardId == cardId) current.copy(detail = detail) else current
        }
        // 原始 JSON 跟着"当前打开的详情"走（卡编辑的底本，见 [detailRaw]）
        if (_state.value.detailCardId == cardId) {
            detailRaw = card
            detailRawId = cardId
        }
        // 详情是在**转场动画期间**到达的：这次状态变更会再触发一次组合。把耗时记下来，
        // 才能判断"转场卡"是页面首帧贵，还是详情到达太晚（tag=nav 的转场帧数对时间戳）。
        AppLog.i(
            "card",
            "读详情 ${cardId.take(8)} ${SystemClock.elapsedRealtime() - startedAt}ms 条目=${entries.size}",
        )
    }

    /**
     * 逐条改内嵌世界书的开关。界面上仍是两态开关，但语义由**卡里怎么写**决定：
     * - 卡里开着 → 关掉就是本地覆盖（[CardDetail.offUids]）；
     * - 卡里关着 → 打开就是**强制开启**（[CardDetail.forcedUids]，整行标红）。
     *
     * 原版在 Character Lore 里也能这么改，只是它改的是卡文件；我们不写回卡，所以存在本机。
     */
    fun setBookEntryEnabled(cardId: String, uid: Int, enabled: Boolean) {
        val detail = _state.value.detail ?: return
        val fileEnabled = detail.bookEntries.firstOrNull { it.uid == uid }?.enabled ?: return
        val override = if (fileEnabled) {
            CardBookOverride(
                off = if (enabled) detail.offUids - uid else detail.offUids + uid,
                forcedOn = detail.forcedUids - uid,
            )
        } else {
            CardBookOverride(
                off = detail.offUids - uid,
                forcedOn = if (enabled) detail.forcedUids + uid else detail.forcedUids - uid,
            )
        }
        applyCardBookOverride(cardId, override)
    }

    /** 卡内嵌书的批量开关（配置页右上角菜单）。一次落盘，不逐条走。 */
    fun setAllCardBookEntries(mode: BookBulkMode) {
        val detail = _state.value.detail ?: return
        val fileOn = detail.bookEntries.filter { it.enabled }.map { it.uid }.toSet()
        val fileOff = detail.bookEntries.filter { !it.enabled }.map { it.uid }.toSet()
        val override = when (mode) {
            BookBulkMode.AllOn -> CardBookOverride(off = emptySet(), forcedOn = fileOff)
            BookBulkMode.AllOff -> CardBookOverride(off = fileOn, forcedOn = emptySet())
            BookBulkMode.Default -> CardBookOverride()
        }
        applyCardBookOverride(detail.id, override)
        notify(
            when (mode) {
                BookBulkMode.AllOn -> "已全部开启（${detail.bookEntries.size} 条，强制 ${fileOff.size} 条）"
                BookBulkMode.AllOff -> "已全部关闭（${detail.bookEntries.size} 条）"
                BookBulkMode.Default -> "已恢复卡里的默认开关"
            },
        )
    }

    private fun applyCardBookOverride(cardId: String, override: CardBookOverride) {
        _state.update {
            val current = it.detail
            it.copy(
                detail = if (current != null && current.id == cardId) {
                    current.copy(offUids = override.off, forcedUids = override.forcedOn)
                } else {
                    current
                },
                // 聊天页拼提示词要用它过滤，不只是详情页好看
                bookDisabled = if (it.cardId == cardId) override.off else it.bookDisabled,
                bookForced = if (it.cardId == cardId) override.forcedOn else it.bookForced,
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveCardBookOverride(cardId, override) }
        }
    }

    /**
     * 读这张卡的开关覆盖；顺带把老版本**按下标**存的"关掉"集合迁到 uid。
     *
     * 只迁一次（迁完删键）。下标对不上的直接丢掉 —— 与其把旧覆盖套到错的条目上，
     * 不如回到卡里的默认值。
     */
    private fun bookOverrideOf(cardId: String, entries: List<CardBookEntry>): CardBookOverride {
        val current = settingsRepository.cardBookOverride(cardId)
        val legacy = settingsRepository.legacyBookDisabledIndexes(cardId)
        if (legacy.isEmpty()) return current
        val byIndex = entries.associateBy { it.index }
        val migrated = legacy.mapNotNull { index -> byIndex[index]?.uid }.toSet()
        val next = current.copy(off = current.off + migrated)
        settingsRepository.saveCardBookOverride(cardId, next)
        settingsRepository.clearLegacyBookDisabledIndexes(cardId)
        AppLog.i("card", "迁移内嵌书覆盖（下标→uid）：旧 ${legacy.size} 条，命中 ${migrated.size} 条")
        return next
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
    /**
     * 用某张卡开一个新会话并进聊天。
     *
     * @param leaveDetail 从卡详情页点「新建会话」时传 true：**关聊天时**连详情页一起退掉、
     * 落回主页的会话栏，而不是又回到那张卡的详情（用户 2026-11-24 报）。
     * 注意不是"开聊天的那一刻就清详情"：那会让转场里的退场页变成空壳（2026-11-27 报）。
     */
    fun startSessionForCard(cardId: String, leaveDetail: Boolean = false) {
        viewModelScope.launch {
            val loaded = loadCard(cardId)
            if (loaded == null) {
                _state.update { it.copy(status = "读卡失败：$cardId（文件损坏或不是角色卡）") }
                return@launch
            }
            card = loaded
            cardRepository.selectedId = cardId
            // 卡内嵌书的开关覆盖（send 时据此过滤/强制开启）
            val bookOverride = withContext(Dispatchers.IO) { bookOverrideOf(cardId, loaded.bookEntries()) }
            _state.update {
                it.copy(
                    bookDisabled = bookOverride.off,
                    bookForced = bookOverride.forcedOn,
                )
            }
            val displayName = _state.value.cards.firstOrNull { it.id == cardId }?.name
                ?: loaded.name.ifBlank { cardId }
            // 卡级默认开场白：detail 页里选过哪一条，就从哪一条开始
            val cardConfig = withContext(Dispatchers.IO) {
                runCatching { configOverrides.load(ConfigOverrideRepository.card(cardId)) }
                    .getOrDefault(ConfigOverride())
            }
            val greetings = greetingsOf(loaded)
            val firstMessage = greetings.getOrNull(cardConfig.greetingIndex ?: 0)
                ?: loaded.firstMessage
            startNewSession(
                cardId = cardId,
                cardName = displayName,
                firstMessage = firstMessage,
                navigate = true,
                // 返回目标是主页「会话」，不是这张卡的详情 —— 但**不在这一刻**清详情页，
                // 见 UiState.returnToSessionsOnClose 的注释（转场快照）。
                returnToSessions = leaveDetail,
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
                    rows = if (currentGone) emptyList() else it.rows,
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
                    _state.update { it.copy(cards = cards) }
                    // 走**正规的打开详情**这条路：它会先把 `detail` 清空再异步读回来。
                    // 以前只写 `detailCardId`，于是详情页拿的是**上一次的 detail**（或者 null）——
                    // 观感就是"刚导入的卡没有开场白、没有内嵌世界书"，退出重进又好了
                    // （2026-11-27 真机，日志里导入后那次 push 没有 `读详情`）。
                    openDetail(result.card.id)
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

    /**
     * 置顶 / 取消置顶（用户 2026-11-25 第 13 条）。
     *
     * 只改会话行的 `pinned` 标记，不碰消息与 `updated_at`；排序由仓库的 ORDER BY 决定
     * （`pinned DESC, updated_at DESC`），所以这里改完要重读一次列表。
     */
    fun setSessionPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { chatRepository.setSessionPinned(id, pinned) }
            refreshSessions()
            AppLog.i("chat", "会话置顶 id=$id pinned=$pinned")
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                chatRepository.deleteSession(id)
                // 会话没了，它的配置覆盖与计时状态也不该留着（按 sessionId 存，不清就是垃圾数据）
                runCatching { configOverrides.clear(ConfigOverrideRepository.session(id)) }
                runCatching { sessionScanStates.clear(id) }
            }
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
                        rows = emptyList(),
                        inflight = null,
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
        returnToSessions: Boolean = false,
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
        openSessionInternal(session.id, navigate = navigate, returnToSessions = returnToSessions)
    }

    private suspend fun openSessionInternal(
        id: String,
        navigate: Boolean = true,
        /** 从卡详情页新建会话时传 true：关聊天时连详情页一起退掉（见 `returnToSessionsOnClose`）。 */
        returnToSessions: Boolean = false,
    ) {
        // 切到另一条会话时把上一轮的流停掉：否则它会继续往**旧会话**落库，而这边又能再发一条，
        // 两路并发（审计 P1-1）。切回**同一条**会话则保留生成态（下面 isStreaming 会按归属算）。
        currentRound?.let { active ->
            // 切到**别的**会话：放弃这一轮（半截丢弃，与"停止"不同 —— 这是既有语义）。
            // 纪律 1：只有抢到提交权才取消；抢不到说明收尾那边正在写库，让它写完。
            if (active.sessionId != id) {
                if (active.claimCommitRight()) {
                    roundJob?.cancel()
                    roundJob = null
                    currentRound = null
                    AppLog.i("chat", "切会话：放弃上一轮 round=${active.roundId} ${active.sessionId.take(8)} → ${id.take(8)}")
                } else {
                    AppLog.i("chat", "切会话：上一轮已在收尾，不取消 round=${active.roundId}")
                }
            }
        }
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
        val bookOverride = withContext(Dispatchers.IO) {
            bookOverrideOf(session.cardId, loadedCard?.bookEntries().orEmpty())
        }
        // 会话级覆盖（现在只放了模型与思考强度；三级继承的其余项在这一层接着加）
        val sessionConfig = withContext(Dispatchers.IO) {
            configOverrides.load(ConfigOverrideRepository.session(id))
        }
        // sticky / cooldown 的计时状态跟着会话走（原版存在 chat_metadata.timedWorldInfo）：
        // 不读回来的话，App 一重启，还在生效的 sticky 条目就提前失效了
        val scanStateText = withContext(Dispatchers.IO) {
            runCatching { sessionScanStates.load(id) }.getOrNull()
        }
        worldInfoStates[id] = LorebookScanStateCodec.decode(scanStateText)
        val cardConfig = withContext(Dispatchers.IO) {
            configOverrides.load(ConfigOverrideRepository.card(session.cardId))
        }

        // 最后一条助手消息的 swipe 备选（老库里没有这张表时按"只有当前这一条"处理）
        val lastAssistant = stored.lastOrNull()?.takeIf { !it.isUser }
        val savedSwipes = if (lastAssistant != null) {
            withContext(Dispatchers.IO) { chatRepository.swipes(lastAssistant.id) }
        } else {
            emptyList()
        }
        val swipes = when {
            lastAssistant == null -> emptyList()
            savedSwipes.isEmpty() -> listOf(lastAssistant.content)
            else -> savedSwipes
        }
        val swipeId = lastAssistant
            ?.let { last -> swipes.indexOf(last.content) }
            ?.coerceAtLeast(0)
            ?: 0

        _state.update {
            it.copy(
                sessionId = id,
                cardId = session.cardId,
                cardName = loadedCard?.name?.ifBlank { session.cardName } ?: session.cardName,
                rows = stored.map { message ->
                    ChatRow(
                        uiKey = "m" + message.id,
                        role = if (message.isUser) ChatRole.User else ChatRole.Assistant,
                        content = message.content,
                        dbId = message.id,
                    )
                },
                // 生成中状态按**归属**算：切回还在跑的那条会话时别把"生成中"清掉
                // （以前一律清掉 → 用户能再发一条，旧流永远停不掉）
                inflight = if (currentRound?.sessionId == id) it.inflight else null,
                chatOpen = navigate,
                // 每次开会话都重写这个标记：只有"从详情页新建"这一次是 true，其余一律清掉，
                // 免得下一次从别处打开的聊天被上一次的标记带偏。
                returnToSessionsOnClose = returnToSessions,
                cardMessageStyle = cardStyle,
                bookDisabled = bookOverride.off,
                bookForced = bookOverride.forcedOn,
                sessionOverride = sessionConfig,
                cardOverride = cardConfig,
                greetings = loadedCard?.let { greetingsOf(it) }.orEmpty(),
                lastSwipes = swipes,
                lastSwipeId = swipeId,
                status = buildString {
                    append("会话：${session.title}｜${stored.size} 条")
                    if (loadedCard == null) append("｜这张卡已不在，只能看，不能聊")
                },
            )
        }
        // 连接参数跟着会话走：会话指定 → 卡指定 → 默认档案
        applySessionProfileOverride(sessionConfig.profileId ?: cardConfig.profileId)
        // 预设同理：会话/卡指定了就加载那一份，否则回到全局选中的那份
        loadPresetIntoState(sessionConfig.presetName ?: cardConfig.presetName)
    }

    /**
     * 把"本会话指定的档案"落到实际连接参数上（发送时读的是 baseUrl / model / apiKey 这三个）。
     *
     * 为什么不是发送时再解析：Key 在仓储里、解密有成本，整套设计是"选档案时就把连接参数镜像进状态"。
     * 换会话时按覆盖重放一次，切换才不串。
     */
    private suspend fun applySessionProfileOverride(overrideId: String?) {
        val snapshot = _state.value
        // 指定的档案被删了：退回默认，别让会话停在"选了一个不存在的档案"上
        val target = overrideId?.let { wanted -> snapshot.profiles.firstOrNull { it.id == wanted } }
        val fallback = snapshot.profiles.firstOrNull { it.isDefault }
        val profile = target ?: fallback ?: return
        val key = if (profile.id == snapshot.activeProfileId && snapshot.apiKey.isNotBlank()) {
            snapshot.apiKey
        } else {
            withContext(Dispatchers.IO) {
                runCatching { profileRepository.apiKey(profile.id) }.getOrDefault("")
            }
        }
        _state.update {
            it.copy(
                // 指定的档案没了：会话层回到"跟随"，别让界面显示一个不存在的档案
                sessionOverride = if (target != null) it.sessionOverride
                else it.sessionOverride.copy(profileId = null),
                baseUrl = profile.baseUrl,
                model = profile.model,
                apiKey = key,
            )
        }
    }

    // ---- 导出（P0-7 的导出侧） ---------------------------------------------

    /**
     * 把当前会话导成原版 `.jsonl`（写到用户选的文件里）。
     *
     * 格式由 `:core:chat-format` 负责（首行表头 + 逐行消息，写出不带结尾换行）；
     * 这里只做"库里的行 → 原版消息"的映射：名字用卡名 / `User`，最后一条助手消息带上
     * swipe 备选（这样 PC 版打开还能「换一个」）。
     */
    fun exportChat(uri: Uri) {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        val cardName = snapshot.cardName.ifBlank { "Character" }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stored = chatRepository.messages(sessionId)
                    val swipes = snapshot.lastSwipes
                    val lastId = stored.lastOrNull()?.id
                    val messages = stored.map { row ->
                        when {
                            row.isUser -> ChatFileMessage.user("User", row.content, row.sendDate)
                            row.id == lastId && swipes.size > 1 -> ChatFileMessage.assistantWithSwipes(
                                name = cardName,
                                swipes = swipes,
                                swipeId = snapshot.lastSwipeId,
                                sendDate = row.sendDate,
                            )

                            else -> ChatFileMessage.assistant(cardName, row.content, row.sendDate)
                        }
                    }
                    val text = ChatFile.of(messages).serialize()
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("拿不到写入流")
                    messages.size
                }
            }
            result.onSuccess { count ->
                AppLog.i("chat", "导出聊天 $count 条")
                notify("已导出 $count 条消息")
            }.onFailure { error ->
                AppLog.w("chat", "导出失败：${error.message}")
                notify("导出失败：${error.message}")
            }
        }
    }

    /**
     * 导入原版 `.jsonl` 聊天到某张卡下（P0-7 的导入侧）。
     *
     * 解析交给 `:core:chat-format`（首行表头、坏行丢弃都是原版行为）；这里只负责
     * "原版消息 → 库行"：`send_date` 原样保留（导入的历史不该变成今天），
     * 最后一条的 swipe 备选也一起写进 `message_swipes`。
     */
    fun importChat(cardId: String, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = readTextOf(uri)
                    val file = ChatFile.parse(text)
                    if (file.messages.isEmpty()) error("文件里没有消息")
                    val imported = file.messages.map { message ->
                        ImportMessage(
                            isUser = message.isUser,
                            isSystem = message.isSystem,
                            content = message.text,
                            sendDate = message.sendDate ?: "1970-01-01T00:00:00.000Z",
                            swipes = if (message.isUser) emptyList() else message.swipes,
                            swipeId = message.swipeId,
                        )
                    }
                    val cardName = _state.value.cards.firstOrNull { it.id == cardId }?.name
                        ?: error("这张卡已经不在列表里")
                    val sessionId = chatRepository.importSession(
                        cardId = cardId,
                        cardName = cardName,
                        title = "导入 $cardName ${stamp()}",
                        messages = imported,
                    )
                    sessionId to imported.size
                }
            }
            result.onSuccess { (sessionId, count) ->
                AppLog.i("chat", "导入聊天 $count 条 card=${cardId.take(8)}")
                refreshSessions()
                openSessionInternal(sessionId, navigate = false)
                notify("已导入 $count 条消息")
            }.onFailure { error ->
                AppLog.w("chat", "导入失败：${error.message}")
                notify("导入失败：${error.message}")
            }
        }
    }

    // ---- 开场白 ------------------------------------------------------------

    /** 卡的全部开场白：第一条是 `first_mes`，后面是备选（空串剔除，否则会切到一条空气泡）。 */
    private fun greetingsOf(card: CharacterCard): List<String> =
        (listOf(card.firstMessage) + card.alternateGreetings)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * 聊天页换开场白：**只在会话还没有用户消息时允许**（用户 2026-11-25 定的 B 策略）。
     *
     * 换法就是改会话里的第一条消息 —— 原版 ST 里换开场白本来就是" swipe 第一条消息"，
     * 所以不新增消息、不动时间线。
     */
    fun selectGreeting(index: Int) {
        val sessionId = _state.value.sessionId ?: return
        val snapshot = _state.value
        val target = snapshot.greetings.getOrNull(index) ?: return
        if (snapshot.rows.any { it.role == ChatRole.User }) {
            notify("这条会话已经聊过了：换开场白只能开新会话")
            return
        }
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatRepository.messages(sessionId) }
            val first = stored.firstOrNull()
            if (first != null && !first.isUser) {
                withContext(Dispatchers.IO) { chatRepository.updateMessage(first.id, target) }
                _state.update { current ->
                    if (current.sessionId != sessionId) current
                    else current.copy(
                        // 第一条换掉正文，key / dbId 不动（K1：行身份不因正文变化而变）
                        rows = current.rows.mapIndexed { i, r ->
                            if (i == 0) r.copy(content = target) else r
                        },
                    )
                }
            } else if (stored.isEmpty()) {
                val saved = withContext(Dispatchers.IO) {
                    chatRepository.append(sessionId, isUser = false, content = target)
                }
                _state.update { current ->
                    if (current.sessionId != sessionId) current
                    else current.copy(
                        rows = listOf(
                            ChatRow("m" + saved.id, ChatRole.Assistant, target, saved.id),
                        ),
                    )
                }
            }
            AppLog.i("chat", "换开场白 index=$index (${snapshot.greetings.size} 条)")
        }
    }

    /** 卡级默认开场白（详情页「设为默认」）：以后用这张卡新开的会话从这一条开始。 */
    fun useCardGreeting(index: Int) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(greetingIndex = index) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(greetingIndex = index)) }
            AppLog.i("config", "卡级默认开场白 card=${cardId.take(8)} index=$index")
            notify("这张卡新会话的默认开场白：第 ${index + 1} 条")
        }
    }

    // ---- 采样三级（用户 2026-11-25 第 2 条） --------------------------------

    /** 把一行输入写进 override：空串 = 跟随上级（null），解析不了也按跟随处理。 */
    private fun ConfigOverride.withSampling(field: SamplingField, text: String): ConfigOverride {
        val raw = text.trim().takeIf { it.isNotEmpty() }
        return when (field) {
            SamplingField.Temperature -> copy(temperature = raw?.toDoubleOrNull())
            SamplingField.MaxTokens -> copy(maxTokens = raw?.toIntOrNull())
            SamplingField.TopP -> copy(topP = raw?.toDoubleOrNull())
            SamplingField.FrequencyPenalty -> copy(frequencyPenalty = raw?.toDoubleOrNull())
            SamplingField.PresencePenalty -> copy(presencePenalty = raw?.toDoubleOrNull())
        }
    }

    fun useSessionSampling(field: SamplingField, text: String) {
        val sessionId = _state.value.sessionId ?: return
        val next = _state.value.sessionOverride.withSampling(field, text)
        viewModelScope.launch {
            persistSessionConfig(sessionId) {
                it.copy(
                    temperature = next.temperature,
                    maxTokens = next.maxTokens,
                    topP = next.topP,
                    frequencyPenalty = next.frequencyPenalty,
                    presencePenalty = next.presencePenalty,
                )
            }
            _state.update {
                it.copy(
                    sessionOverride = it.sessionOverride.copy(
                        temperature = next.temperature,
                        maxTokens = next.maxTokens,
                        topP = next.topP,
                        frequencyPenalty = next.frequencyPenalty,
                        presencePenalty = next.presencePenalty,
                    ),
                )
            }
            AppLog.i("config", "会话采样 ${field.name}=${text.ifBlank { "跟随" }}")
        }
    }

    fun useCardSampling(field: SamplingField, text: String) {
        val cardId = _state.value.detailCardId ?: return
        val next = _state.value.cardOverride.withSampling(field, text)
        viewModelScope.launch {
            persistCardConfig(cardId) {
                it.copy(
                    temperature = next.temperature,
                    maxTokens = next.maxTokens,
                    topP = next.topP,
                    frequencyPenalty = next.frequencyPenalty,
                    presencePenalty = next.presencePenalty,
                )
            }
            _state.update {
                it.copy(
                    cardOverride = it.cardOverride.copy(
                        temperature = next.temperature,
                        maxTokens = next.maxTokens,
                        topP = next.topP,
                        frequencyPenalty = next.frequencyPenalty,
                        presencePenalty = next.presencePenalty,
                    ),
                )
            }
            AppLog.i("config", "卡级采样 card=${cardId.take(8)} ${field.name}=${text.ifBlank { "跟随" }}")
        }
    }

    /** 采样这一层恢复默认 = 五项都清掉（跟随上级）。 */
    fun clearSessionSampling() {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) {
                it.copy(
                    temperature = null,
                    maxTokens = null,
                    topP = null,
                    frequencyPenalty = null,
                    presencePenalty = null,
                )
            }
            _state.update {
                it.copy(
                    sessionOverride = it.sessionOverride.copy(
                        temperature = null,
                        maxTokens = null,
                        topP = null,
                        frequencyPenalty = null,
                        presencePenalty = null,
                    ),
                )
            }
            AppLog.i("config", "会话采样恢复默认（跟随上级）")
        }
    }

    fun clearCardSampling() {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) {
                it.copy(
                    temperature = null,
                    maxTokens = null,
                    topP = null,
                    frequencyPenalty = null,
                    presencePenalty = null,
                )
            }
            _state.update {
                it.copy(
                    cardOverride = it.cardOverride.copy(
                        temperature = null,
                        maxTokens = null,
                        topP = null,
                        frequencyPenalty = null,
                        presencePenalty = null,
                    ),
                )
            }
            AppLog.i("config", "卡级采样恢复默认 card=${cardId.take(8)}")
        }
    }

    // ---- 会话级配置（三级继承的最内层） -------------------------------------

    /** 会话级换模型：只影响这一条会话。 */
    fun useSessionProfile(profileId: String) {
        val sessionId = _state.value.sessionId ?: return
        val profile = _state.value.profiles.firstOrNull { it.id == profileId } ?: return
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) {
                runCatching { profileRepository.apiKey(profileId) }.getOrDefault("")
            }
            persistSessionConfig(sessionId) { it.copy(profileId = profileId) }
            _state.update {
                it.copy(
                    sessionOverride = it.sessionOverride.copy(profileId = profileId),
                    baseUrl = profile.baseUrl,
                    model = profile.model,
                    apiKey = key,
                )
            }
            AppLog.i("config", "会话模型 id=$profileId model=${profile.model}")
        }
    }

    /** 回到"跟随默认档案"。 */
    fun clearSessionProfile() {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(profileId = null) }
            val snapshot = _state.value
            applySessionProfileOverride(snapshot.cardOverride.profileId)
            AppLog.i("config", "会话模型回到跟随默认")
        }
    }

    /** 会话级思考强度：null = 跟随档案，`"off"` = 关掉，其余原样发。 */
    fun useSessionReasoning(effort: String?) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(reasoningEffort = effort) }
            _state.update {
                it.copy(sessionOverride = it.sessionOverride.copy(reasoningEffort = effort))
            }
            AppLog.i("config", "会话思考强度=${effort ?: "跟随"}")
        }
    }

    private suspend fun persistSessionConfig(sessionId: String, edit: (ConfigOverride) -> ConfigOverride) {
        withContext(Dispatchers.IO) {
            runCatching {
                val scope = ConfigOverrideRepository.session(sessionId)
                configOverrides.save(scope, edit(configOverrides.load(scope)))
            }.onFailure { AppLog.w("config", "会话配置写入失败：${it.message}") }
        }
    }

    /** 会话级预设：null = 跟随（卡 → 全局）。 */
    fun useSessionPreset(name: String?) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(presetName = name) }
            _state.update { it.copy(sessionOverride = it.sessionOverride.copy(presetName = name)) }
            loadPresetIntoState(name ?: _state.value.cardOverride.presetName)
            AppLog.i("config", "会话预设=${name ?: "跟随"}")
        }
    }

    /** 会话级世界书：null = 跟随；空集 = 这场对话不带任何全局书。 */
    fun useSessionWorldBooks(names: Set<String>?) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(worldBookNames = names) }
            _state.update { it.copy(sessionOverride = it.sessionOverride.copy(worldBookNames = names)) }
            AppLog.i("config", "会话世界书=${names?.size ?: "跟随"} 本")
        }
    }

    /** 会话级正则：null = 跟随（全部启用）；空集 = 全部关掉。 */
    /** 会话级：用户脚本集合 + 内置开关一起写（选择页右上「全部开启/关闭/恢复默认」用）。 */
    fun useSessionRegexAndBuiltin(ids: Set<String>?, builtin: Set<String>?) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(regexIds = ids, builtinDisabled = builtin) }
            _state.update {
                it.copy(
                    sessionOverride = it.sessionOverride.copy(regexIds = ids, builtinDisabled = builtin),
                )
            }
            AppLog.i("config", "会话正则=${ids?.size ?: "跟随"} 内置关=${builtin?.size ?: "跟随"}")
        }
    }

    /** 会话级：内置正则的开关（null = 跟随卡/全局）。 */
    fun useSessionBuiltin(disabled: Set<String>?) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(builtinDisabled = disabled) }
            _state.update { it.copy(sessionOverride = it.sessionOverride.copy(builtinDisabled = disabled)) }
            AppLog.i("config", "会话内置正则=${disabled?.size ?: "跟随"} 项关掉")
        }
    }

    fun useSessionRegex(ids: Set<String>?) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(regexIds = ids) }
            _state.update { it.copy(sessionOverride = it.sessionOverride.copy(regexIds = ids)) }
            AppLog.i("config", "会话正则=${ids?.size ?: "跟随"} 条")
        }
    }

    // ---- 角色卡级覆盖（中间层） --------------------------------------------

    private suspend fun persistCardConfig(cardId: String, edit: (ConfigOverride) -> ConfigOverride) {
        withContext(Dispatchers.IO) {
            runCatching {
                val scope = ConfigOverrideRepository.card(cardId)
                configOverrides.save(scope, edit(configOverrides.load(scope)))
            }.onFailure { AppLog.w("config", "卡配置写入失败：${it.message}") }
        }
    }

    /**
     * 卡级换模型：这张卡以后开的会话默认用它。
     *
     * 如果当前开着的会话正跟着这张卡（会话级没指定），连接参数要立刻跟着变 ——
     * 否则用户改完回聊天页，看到的还是旧模型。
     */
    fun useCardProfile(profileId: String?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(profileId = profileId) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(profileId = profileId)) }
            val snapshot = _state.value
            if (snapshot.sessionOverride.profileId == null) {
                applySessionProfileOverride(profileId)
            }
            AppLog.i("config", "卡级模型 card=${cardId.take(8)} profile=${profileId ?: "跟随"}")
        }
    }

    /** 卡级思考强度：null = 跟随全局档案。 */
    fun useCardReasoning(effort: String?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(reasoningEffort = effort) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(reasoningEffort = effort)) }
            AppLog.i("config", "卡级思考强度 card=${cardId.take(8)} ${effort ?: "跟随"}")
        }
    }

    fun useCardPreset(name: String?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(presetName = name) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(presetName = name)) }
            val snapshot = _state.value
            if (snapshot.sessionOverride.presetName == null) loadPresetIntoState(name)
            AppLog.i("config", "卡级预设 card=${cardId.take(8)} preset=${name ?: "跟随"}")
        }
    }

    // ---- 人设（需求批 F）----------------------------------------------------

    /**
     * 保存一个人设（同 id 覆盖）。名字就是 `{{user}}`，描述会进 `personaDescription` 那一格。
     * 只改本地列表 + 落盘，不动已经发出的请求。
     */
    fun savePersona(persona: Persona) {
        _state.update { st ->
            st.copy(personas = (st.personas.filterNot { it.id == persona.id } + persona).sortedBy { it.name })
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.savePersona(persona) }
            AppLog.i("config", "人设保存 id=${persona.id.take(6)} 名字=${persona.name}")
        }
    }

    fun deletePersona(id: String) {
        _state.update { st ->
            st.copy(
                personas = st.personas.filterNot { it.id == id },
                personaDefaultId = if (st.personaDefaultId == id) null else st.personaDefaultId,
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.deletePersona(id) }
            AppLog.i("config", "人设删除 id=${id.take(6)}")
        }
    }

    /** 全局默认人设；null = 没有默认（`{{user}}` 退回 "User"）。 */
    fun setDefaultPersona(id: String?) {
        _state.update { it.copy(personaDefaultId = id) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.savePersonaDefaultId(id) }
            AppLog.i("config", "默认人设=${id?.take(6) ?: "无"}")
        }
    }

    /** 会话级人设：null = 跟随（卡 → 全局）。 */
    fun useSessionPersona(id: String?) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(personaId = id) }
            _state.update { it.copy(sessionOverride = it.sessionOverride.copy(personaId = id)) }
            AppLog.i("config", "会话人设=${id?.take(6) ?: "跟随"}")
        }
    }

    /**
     * 会话级作者注。四项一起写：正文清空 = 这一层没有作者注（回到"跟随上级"）。
     */
    fun setSessionAuthorNote(text: String?, depth: Int, role: String, interval: Int, position: Int) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) {
                it.copy(
                    authorNote = text?.takeIf { t -> t.isNotBlank() },
                    authorNoteDepth = depth,
                    authorNoteRole = role,
                    authorNoteInterval = interval,
                    authorNotePosition = position,
                )
            }
            _state.update {
                it.copy(
                    sessionOverride = it.sessionOverride.copy(
                        authorNote = text?.takeIf { t -> t.isNotBlank() },
                        authorNoteDepth = depth,
                        authorNoteRole = role,
                        authorNoteInterval = interval,
                        authorNotePosition = position,
                    ),
                )
            }
            AppLog.i(
                "config",
                "会话作者注 depth=$depth role=$role interval=$interval position=$position 正文=${text?.length ?: 0}字",
            )
        }
    }

    /** 卡级人设：null = 跟随全局默认。 */
    fun useCardPersona(id: String?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(personaId = id) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(personaId = id)) }
            AppLog.i("config", "卡级人设 card=${cardId.take(8)} persona=${id?.take(6) ?: "跟随"}")
        }
    }

    fun useCardWorldBooks(names: Set<String>?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(worldBookNames = names) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(worldBookNames = names)) }
            AppLog.i("config", "卡级世界书 card=${cardId.take(8)} ${names?.size ?: "跟随"} 本")
        }
    }

    /** 卡级：用户脚本集合 + 内置开关一起写（批量菜单用）。 */
    fun useCardRegexAndBuiltin(ids: Set<String>?, builtin: Set<String>?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(regexIds = ids, builtinDisabled = builtin) }
            _state.update {
                it.copy(cardOverride = it.cardOverride.copy(regexIds = ids, builtinDisabled = builtin))
            }
            AppLog.i("config", "卡级正则 card=${cardId.take(8)}=${ids?.size ?: "跟随"} 内置关=${builtin?.size ?: "跟随"}")
        }
    }

    /** 卡级：内置正则的开关（null = 跟随全局）。 */
    fun useCardBuiltin(disabled: Set<String>?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(builtinDisabled = disabled) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(builtinDisabled = disabled)) }
            AppLog.i("config", "卡级内置正则 card=${cardId.take(8)} ${disabled?.size ?: "跟随"} 项关掉")
        }
    }

    fun useCardRegex(ids: Set<String>?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(regexIds = ids) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(regexIds = ids)) }
            AppLog.i("config", "卡级正则 card=${cardId.take(8)} ${ids?.size ?: "跟随"} 条")
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

    // ---- 卡编辑（写回 PNG）--------------------------------------------------

    fun openCardEditor() {
        if (_state.value.detailCardId == null) return
        _state.update { it.copy(edit = it.edit.copy(cardEditOpen = true)) }
        AppLog.i("card", "打开卡编辑")
    }

    fun closeCardEditor() = _state.update { it.copy(edit = it.edit.copy(cardEditOpen = false)) }

    /**
     * 把编辑后的字段写回卡文件（原子替换；2026-11-26 起不再留 `.bak.png` 备份）。
     *
     * 只改这六个文本字段；内嵌世界书、扩展字段、图片数据都原样保留
     * （[app.tavern.core.card.PngCard.write] 只换卡片 JSON，IDAT 逐字节不动 —— P0-3 有单测）。
     */
    fun saveCardFields(
        name: String,
        description: String,
        personality: String,
        scenario: String,
        firstMessage: String,
        messageExample: String,
        alternateGreetings: List<String> = emptyList(),
        systemPrompt: String = "",
        postHistoryInstructions: String = "",
        /** true = 以这张卡的图新建一张卡（原卡不动）；false = 覆盖原卡（先备份）。 */
        asNew: Boolean = false,
    ) {
        val cardId = _state.value.detailCardId ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            notify("名字不能为空")
            return
        }
        viewModelScope.launch {
            // 原始 JSON 必须属于**这张卡**（见 [detailRaw]）：详情打开时记下的那份，
            // id 对不上就现读一次 —— 绝不能用 [card]（那是会话的卡）。
            val source = if (detailRawId == cardId) {
                detailRaw
            } else {
                withContext(Dispatchers.IO) { loadCard(cardId) }
            }
            if (source == null) {
                notify("读卡失败：先返回再进来一次")
                return@launch
            }
            val updated = source
                .withField("name", JsonPrimitive(trimmed))
                .withField("description", JsonPrimitive(description))
                .withField("personality", JsonPrimitive(personality))
                .withField("scenario", JsonPrimitive(scenario))
                .withField("first_mes", JsonPrimitive(firstMessage))
                .withField("mes_example", JsonPrimitive(messageExample))
                .withField("system_prompt", JsonPrimitive(systemPrompt))
                .withField("post_history_instructions", JsonPrimitive(postHistoryInstructions))
                // 空行去掉：界面上"加一条"会先塞一个空串占位，保存时不该写进卡里
                .withField(
                    "alternate_greetings",
                    JsonArray(alternateGreetings.map { it.trim() }.filter { it.isNotEmpty() }.map {
                        JsonPrimitive(it)
                    }),
                )

            if (asNew) {
                val newId = withContext(Dispatchers.IO) {
                    runCatching { cardRepository.saveCardAs(cardId, trimmed, updated) }.getOrNull()
                }
                if (newId == null) {
                    notify("新建失败：卡文件可能已被移动或损坏")
                    return@launch
                }
                detailRaw = updated
                detailRawId = newId
                // 先关编辑页，再按"打开一张新卡"走（卡级覆盖、详情、标签页都按新卡重来）
                _state.update { it.copy(edit = it.edit.copy(cardEditOpen = false)) }
                val cards = withContext(Dispatchers.IO) { cardRepository.list() }
                _state.update { it.copy(cards = cards) }
                refreshSessions()
                openDetail(newId)
                AppLog.i("card", "卡编辑另存为新卡 name=${trimmed.take(20)} id=${newId.take(12)}")
                notify("已新建「$trimmed」")
                return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                runCatching { cardRepository.saveCard(cardId, updated) }.getOrDefault(false)
            }
            if (!ok) {
                notify("写回失败：卡文件可能已被移动或损坏")
                return@launch
            }
            detailRaw = updated
            detailRawId = cardId
            // 改的就是当前会话那张卡：会话侧的原始 JSON 也要跟着换，否则下一句还用旧设定
            if (_state.value.cardId == cardId) card = updated
            _state.update { it.copy(edit = it.edit.copy(cardEditOpen = false)) }
            val cards = withContext(Dispatchers.IO) { cardRepository.list() }
            // 会话列表显示的是会话行里冗余的 card_name：卡改名后一并刷新，否则列表还挂着旧名
            withContext(Dispatchers.IO) {
                runCatching { chatRepository.updateCardName(cardId, trimmed) }
            }
            _state.update { it.copy(cards = cards) }
            refreshSessions()
            loadDetail(cardId)
            AppLog.i("card", "卡编辑保存 name=${trimmed.take(20)}")
            notify("已保存")
        }
    }

    fun closeCardConfigPage() = _state.update { it.copy(cardConfigPage = null) }

    fun openCardPicker() = _state.update { it.copy(showCardPicker = true) }

    fun closeCardPicker() = _state.update { it.copy(showCardPicker = false) }

    fun openGallery() = _state.update { it.copy(galleryOpen = true) }

    fun closeGallery() = _state.update { it.copy(galleryOpen = false) }

    /** 选卡弹层里点了某张卡：关弹层 + 用它开新会话。 */
    fun pickCardAndStart(cardId: String) {
        _state.update { it.copy(showCardPicker = false) }
        startSessionForCard(cardId)
    }

    fun openSettingsPage(page: SettingsPage) = _state.update {
        it.copy(settingsPage = page, worldBookView = null)
    }

    fun closeSettingsPage() = _state.update { it.copy(settingsPage = null, worldBookView = null) }

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
                    edit = it.edit.copy(profileDraft = ProfileDraft(
                        id = existing?.id,
                        name = existing?.name.orEmpty(),
                        baseUrl = existing?.baseUrl ?: ModelProfiles.FALLBACK_BASE_URL,
                        model = existing?.model ?: ModelProfiles.FALLBACK_MODEL,
                        apiKey = key,
                        headersText = existing?.headers
                            ?.let { headers -> ModelProfiles.formatHeaders(headers) }
                            .orEmpty(),
                        // 新建时默认打开"保存后设为默认"：建一条档案通常就是马上要用它
                        isDefault = existing?.isDefault ?: true,
                    )),
                )
            }
        }
    }

    fun closeProfileEditor() = _state.update { it.copy(edit = it.edit.copy(profileDraft = null)) }

    /** 库里存的原始值 → 界面上的预设；认不出来的（`max` 等）落到「自定义」。 */

    /** 改草稿里的某个字段（界面上每个输入框都调这一条，不再各开一个 setter）。 */
    fun editProfileDraft(transform: (ProfileDraft) -> ProfileDraft) = _state.update { current ->
        current.copy(edit = current.edit.copy(profileDraft = current.edit.profileDraft?.let(transform)))
    }

    fun saveProfileDraft() {
        val draft = _state.value.edit.profileDraft ?: return
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
                    "头=${profile.headers.size}",
            )
            refreshProfiles()
            _state.update { it.copy(edit = it.edit.copy(profileDraft = null)) }
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
                val draft = current.edit.profileDraft
                current.copy(edit = current.edit.copy(profileDraft = draft?.copy(isDefault = draft.id == id)))
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
            _state.update { it.copy(edit = it.edit.copy(profileDraft = null)) }
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

    fun closeChat() {
        // 离开聊天页时流**照常跑完**（用户可能只是回去看一眼列表），但它归 [streaming] 管：
        // 回到同一会话能看到"生成中"，切到别的会话会被取消（审计 P1-1）。
        _state.update {
            val backToSessions = it.returnToSessionsOnClose
            it.copy(
                chatOpen = false,
                sessionConfigOpen = false,
                configPicker = null,
                // 从详情页「新建会话」进来的这一次：关聊天时连详情页一起退掉，落回会话栏。
                // 放在**这一刻**做，是因为退场页读的是快照 —— 提前清会把它渲染成空壳。
                detailCardId = if (backToSessions) null else it.detailCardId,
                detail = if (backToSessions) null else it.detail,
                cardConfigPage = if (backToSessions) null else it.cardConfigPage,
                screen = if (backToSessions) Screen.Sessions else it.screen,
                returnToSessionsOnClose = false,
            )
        }
        currentRound?.let { AppLog.i("chat", "离开聊天页：流仍在跑 round=${it.roundId} session=${it.sessionId.take(8)}") }
    }

    /** 打开会话配置（三级继承最内层的那一页）。 */
    fun openSessionConfig() {
        if (_state.value.sessionId == null) {
            notify("还没有会话")
            return
        }
        _state.update { it.copy(sessionConfigOpen = true) }
        AppLog.i("config", "开会话配置")
    }

    fun closeSessionConfig() = _state.update {
        it.copy(sessionConfigOpen = false, configPicker = null)
    }

    /** 打开"选一项"页（模型 / 世界书 / 正则 / 预设）：像设置页那样整页选，不再用底部抽屉。 */
    fun openConfigPicker(scope: ConfigScope, kind: ConfigKind) {
        _state.update { it.copy(configPicker = ConfigPickerTarget(scope, kind)) }
        AppLog.i("config", "打开配置页 ${scope.name}/${kind.name}")
    }

    fun closeConfigPicker() = _state.update { it.copy(configPicker = null) }

    fun toggleRequest() = _state.update { it.copy(showRequest = !it.showRequest) }

    fun onTemperatureChange(value: String) {
        _state.update { it.copy(temperatureText = value) }
        persistSampling(value, _state.value.maxTokensText)
    }

    fun onMaxTokensChange(value: String) {
        _state.update { it.copy(maxTokensText = value) }
        persistSampling(_state.value.temperatureText, value)
    }

    fun onTopPChange(value: String) {
        _state.update { it.copy(topPText = value) }
        persistSampling(_state.value.temperatureText, _state.value.maxTokensText)
    }

    fun onFrequencyPenaltyChange(value: String) {
        _state.update { it.copy(frequencyPenaltyText = value) }
        persistSampling(_state.value.temperatureText, _state.value.maxTokensText)
    }

    fun onPresencePenaltyChange(value: String) {
        _state.update { it.copy(presencePenaltyText = value) }
        persistSampling(_state.value.temperatureText, _state.value.maxTokensText)
    }

    private fun persistSampling(temperatureText: String, maxTokensText: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 解析不了就先不存，等用户把数字打完
                settingsRepository.saveSampling(
                    temperature = temperatureText.toDoubleOrNull(),
                    maxTokens = maxTokensText.toIntOrNull(),
                    topP = _state.value.topPText.toDoubleOrNull(),
                    frequencyPenalty = _state.value.frequencyPenaltyText.toDoubleOrNull(),
                    presencePenalty = _state.value.presencePenaltyText.toDoubleOrNull(),
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

    /** 全局气泡字号（设置 · 外观）；只影响聊天页气泡正文。 */
    fun setBubbleFontSize(size: Float) {
        _state.update { it.copy(bubbleFontSize = size) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveBubbleFontSize(size) }
        }
        AppLog.i("config", "全局气泡字号=${size}sp")
    }

    /** 会话级气泡字号：null = 跟随上级。 */
    fun useSessionBubbleFontSize(size: Float?) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            persistSessionConfig(sessionId) { it.copy(bubbleFontSize = size) }
            _state.update { it.copy(sessionOverride = it.sessionOverride.copy(bubbleFontSize = size)) }
            AppLog.i("config", "会话气泡字号=${size?.toString() ?: "跟随"}")
        }
    }

    /** 卡级气泡字号：null = 跟随上级。 */
    fun useCardBubbleFontSize(size: Float?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(bubbleFontSize = size) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(bubbleFontSize = size)) }
            AppLog.i("config", "卡级气泡字号 card=${cardId.take(8)} ${size?.toString() ?: "跟随"}")
        }
    }

    fun onMessageStyleChange(style: MessageStyle) {
        _state.update { it.copy(messageStyle = style) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveMessageStyle(style.name) }
        }
    }

    /**
     * 揭示节奏换档。**运行中改也立刻生效**：推进循环每个 tick 都从最新状态读速率，
     * 所以这里只写状态、不重启任何循环（重启会重置计时基准，表现是一帧抖动）。
     */
    fun onRevealSpeedChange(speed: RevealSpeed) {
        _state.update { it.copy(revealSpeed = speed) }
        AppLog.i("chat", "揭示节奏换档=${speed.name}(${speed.cps}cp/s)")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settingsRepository.saveRevealSpeed(speed.name) }
        }
    }

    /**
     * 停止生成。已收到的半截内容**保留**并落库 —— 原版就是这样
     * （用户按停止往往是"已经够用了，别再烧 token"）。
     *
     * 与流式收尾共用提交权：谁先 [GenerationRound.claimCommitRight] 成功，谁写库、谁收界面；
     * **输家什么都不做**（连"已停止"都不写 —— 那会把刚起跑的新一轮按回去）。
     * 注意顺序：**先抢权、再取消**。反过来的话，取消会让已经写完库的收尾在 `withContext`
     * 返回时抛 `CancellationException`，界面更新被跳过（回复在库里、界面没有）。
     */
    fun stop() {
        val round = currentRound ?: return
        if (!claimFor(round, SettleReason.Stopped)) return
        val job = roundJob
        viewModelScope.launch {
            settleClaimed(
                round = round,
                reason = SettleReason.Stopped,
                text = null,
                okStatus = "",
                cancelJob = job,
            )
        }
    }

    /**
     * 世界书的 sticky / cooldown 计时状态，按会话存。
     *
     * 原版把它写进聊天元数据（`chat_metadata.timedWorldInfo`）跟着聊天落盘；我们还没加表，
     * 所以先挂在 ViewModel 上：同一次运行内行为正确，杀进程就重置（矩阵里记着这条差异）。
     */
    private val worldInfoStates = HashMap<String, LorebookScanState>()

    /** 全局世界书名字 → 解析好的条目。send() 在主线程，读文件与解析必须提前在 IO 里做好。 */
    private val globalBookEntries = HashMap<String, List<LorebookEntry>>()

    /** 重新读全局世界书列表，顺便把启用中的那些书解析进缓存。 */
    fun refreshWorldBooks() {
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val books = withContext(Dispatchers.IO) { worldBookRepository.list() }
            val listMs = SystemClock.elapsedRealtime() - started
            val entries = withContext(Dispatchers.IO) {
                books.filter { it.enabled }.mapNotNull { ref ->
                    worldBookRepository.load(ref.name)?.let { book ->
                        val disabled = worldBookRepository.disabledEntries(ref.name)
                        val forced = worldBookRepository.forcedEntries(ref.name)
                        ref.name to book.entries.map { entry ->
                            when {
                                entry.uid in disabled -> entry.withDisabled(true)
                                entry.uid in forced -> entry.withDisabled(false)
                                else -> entry
                            }
                        }
                    }
                }.toMap()
            }
            globalBookEntries.clear()
            globalBookEntries.putAll(entries)
            _state.update { it.copy(worldBooks = books) }
            AppLog.i(
                "wi",
                "刷新全局书 ${books.size} 本（数条目 ${listMs}ms，总 ${SystemClock.elapsedRealtime() - started}ms），" +
                    "启用 ${entries.size} 本",
            )
        }
    }

    /** 导入一本全局世界书（SAF 选来的 `.json`）。导入后默认停用。 */
    fun importWorldBook(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayNameOf(uri)
                    val json = readTextOf(uri)
                    worldBookRepository.import(name, json)
                }
            }
            result.onSuccess { ref ->
                AppLog.i("worldinfo", "导入世界书 ${ref.name}（${ref.entryCount} 条）")
                _state.update {
                    it.copy(notice = Notice(System.nanoTime(), "已导入「${ref.name}」（${ref.entryCount} 条），默认停用"))
                }
                refreshWorldBooks()
            }.onFailure { error ->
                AppLog.w("worldinfo", "导入世界书失败：${error.message}")
                _state.update { it.copy(notice = Notice(System.nanoTime(), "导入失败：${error.message}")) }
            }
        }
    }

    /** 点进一本书：先切到条目页（空列表 + 读取中），再异步填条目。 */
    fun openWorldBook(name: String) {
        _state.update { it.copy(worldBookView = WorldBookView(name = name, entries = emptyList())) }
        viewModelScope.launch { loadWorldBook(name) }
    }

    private suspend fun loadWorldBook(name: String) {
        val started = SystemClock.elapsedRealtime()
        val loaded = withContext(Dispatchers.IO) {
            val parseAt = SystemClock.elapsedRealtime()
            val book = worldBookRepository.load(name) ?: return@withContext null
            val parseMs = SystemClock.elapsedRealtime() - parseAt
            val overrides = worldBookRepository.disabledEntries(name)
            val forced = worldBookRepository.forcedEntries(name)
            val buildAt = SystemClock.elapsedRealtime()
            val entries = book.entries.map { entry ->
                val forcedOn = entry.disabled && entry.uid in forced
                WorldBookEntryView(
                    uid = entry.uid,
                    title = entry.keys.takeIf { it.isNotEmpty() }?.joinToString(" / ")
                        ?: "条目 ${entry.uid}",
                    keys = entry.keys,
                    preview = entry.content.replace("\n", " ").take(90),
                    enabled = forcedOn || (!entry.disabled && entry.uid !in overrides),
                    disabledInFile = entry.disabled,
                    forcedOn = forcedOn,
                )
            }
            val view = WorldBookView(
                name = name,
                entries = entries,
                loaded = true,
                total = entries.size,
            )
            AppLog.i(
                "wi",
                "开书 $name：解析 ${parseMs}ms，建条目 ${SystemClock.elapsedRealtime() - buildAt}ms，" +
                    "${view.entries.size} 条",
            )
            book to view
        }
        if (loaded == null) {
            _state.update { it.copy(status = "这本书读不出来了：$name", worldBookView = null) }
        } else {
            val (book, view) = loaded
            // 缓存解析结果：条目页搜索（正文命中）与"看全文"都从这里取，不重复读文件
            openBook = book
            openBookName = name
            openBookEntries = view.entries
            // 读的过程中用户可能已经返回了，别把页面又推回来
            _state.update { if (it.worldBookView?.name == name) it.copy(worldBookView = view) else it }
            AppLog.i("wi", "开书 $name 落地 +${SystemClock.elapsedRealtime() - started}ms")
        }
    }

    /**
     * 条目页搜索：标题 / 关键词先过滤，正文命中从缓存里比（用户 2026-11-25 第 16 条）。
     *
     * 正文不在 UiState 里，所以不能纯 UI 侧过滤 —— 这里拿 `openBook` 的原文比对。
     */
    fun onWorldBookQuery(query: String) {
        val view = _state.value.worldBookView ?: return
        _state.update { it.copy(worldBookView = view.copy(query = query, entries = filteredWorldBookEntries(query))) }
    }

    private fun filteredWorldBookEntries(query: String): List<WorldBookEntryView> {
        if (query.isBlank()) return openBookEntries
        val q = query.trim().lowercase()
        val contents = openBook?.entries?.associate { it.uid to it.content.lowercase() }.orEmpty()
        return openBookEntries.filter { entry ->
            entry.title.lowercase().contains(q) ||
                entry.keys.any { it.lowercase().contains(q) } ||
                entry.preview.lowercase().contains(q) ||
                contents[entry.uid]?.contains(q) == true
        }
    }

    /** 打开全局世界书的某一条全文。 */
    fun openWorldBookEntry(uid: Int) {
        val view = _state.value.worldBookView ?: return
        val full = openBook?.entries?.firstOrNull { it.uid == uid } ?: return
        val row = openBookEntries.firstOrNull { it.uid == uid } ?: return
        _state.update {
            it.copy(
                worldBookEntry = WorldBookEntryDetail(
                    title = row.title,
                    keys = full.keys,
                    content = full.content,
                    enabled = row.enabled,
                    source = "书：${view.name}",
                ),
            )
        }
        AppLog.i("wi", "看条目全文 ${view.name}#$uid（${full.content.length} 字）")
    }

    /** 打开卡内嵌书的某一条全文（内容已经在 CardDetail 里，不用再读卡）。 */
    fun openCardBookEntry(uid: Int) {
        val detail = _state.value.detail ?: return
        val entry = detail.bookEntries.firstOrNull { it.uid == uid } ?: return
        val enabled = when {
            entry.uid in detail.forcedUids -> true
            entry.uid in detail.offUids -> false
            else -> entry.enabled
        }
        _state.update {
            it.copy(
                worldBookEntry = WorldBookEntryDetail(
                    title = entry.keys.takeIf { it.isNotEmpty() }?.joinToString(" / ")
                        ?: "条目 ${entry.uid}",
                    keys = entry.keys,
                    content = entry.content,
                    enabled = enabled,
                    source = "卡内嵌书：${detail.name}",
                ),
            )
        }
        AppLog.i("wi", "看卡内嵌书条目 ${detail.name}#$uid（${entry.content.length} 字）")
    }

    fun closeWorldBookEntry() {
        _state.update { it.copy(worldBookEntry = null) }
    }

    fun closeWorldBook() {
        _state.update { it.copy(worldBookView = null) }
        openBook = null
        openBookName = null
        openBookEntries = emptyList()
    }

    /**
     * 逐条开关。只写"应用里的覆盖"，不改导入的原始文件（书还要能原样导回 PC 版）。
     *
     * 和卡内嵌书同一套三态：文件里开着 → 关掉是本地覆盖；文件里关着 → 打开是**强制开启**。
     */
    fun setWorldBookEntryEnabled(name: String, uid: Int, enabled: Boolean) {
        viewModelScope.launch {
            val fileOff = _state.value.worldBookView
                ?.takeIf { it.name == name }
                ?.entries
                ?.firstOrNull { it.uid == uid }
                ?.disabledInFile
                ?: false
            withContext(Dispatchers.IO) {
                if (fileOff) {
                    worldBookRepository.setEntryForced(name, uid, enabled)
                    worldBookRepository.setEntryDisabled(name, uid, false)
                } else {
                    worldBookRepository.setEntryDisabled(name, uid, !enabled)
                    worldBookRepository.setEntryForced(name, uid, false)
                }
            }
            loadWorldBook(name)
            refreshWorldBooks()
        }
    }

    /** 全局世界书的批量开关（页头右上角菜单）。 */
    fun setAllWorldBookEntries(name: String, mode: BookBulkMode) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val book = worldBookRepository.load(name) ?: return@withContext null
                val fileOn = book.entries.filter { !it.disabled }.map { it.uid }.toSet()
                val fileOff = book.entries.filter { it.disabled }.map { it.uid }.toSet()
                when (mode) {
                    BookBulkMode.AllOn -> {
                        worldBookRepository.saveEntryOverrides(name, emptySet(), fileOff)
                        "已全部开启（${book.entries.size} 条，强制 ${fileOff.size} 条）"
                    }

                    BookBulkMode.AllOff -> {
                        worldBookRepository.saveEntryOverrides(name, fileOn, emptySet())
                        "已全部关闭（${book.entries.size} 条）"
                    }

                    BookBulkMode.Default -> {
                        worldBookRepository.saveEntryOverrides(name, emptySet(), emptySet())
                        "已恢复文件里的默认开关"
                    }
                }
            } ?: return@launch
            AppLog.i("worldinfo", "批量开关 $name mode=$mode")
            _state.update { it.copy(notice = Notice(System.nanoTime(), result)) }
            loadWorldBook(name)
            refreshWorldBooks()
        }
    }

    fun setWorldBookEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { worldBookRepository.setEnabled(name, enabled) }
            refreshWorldBooks()
        }
    }

    fun deleteWorldBook(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { worldBookRepository.delete(name) }
            AppLog.i("worldinfo", "删除世界书 $name")
            refreshWorldBooks()
        }
    }

    // ---- 预设（P1-5） ------------------------------------------------------

    /**
     * 读回预设列表 + 当前选中的那份，顺带把**生效的 prompt_order** 算出来：
     * 用户在 App 里改过就用改过的，否则用文件里的，再否则用内置默认表。
     */
    fun refreshPresets() {
        viewModelScope.launch {
            val presets = withContext(Dispatchers.IO) { presetRepository.list() }
            val activeName = withContext(Dispatchers.IO) { presetRepository.activeName() }
            val loaded = withContext(Dispatchers.IO) {
                activeName?.let { presetRepository.load(it) }
            }
            val order = withContext(Dispatchers.IO) {
                activeName?.let { presetRepository.orderOverride(it) }
            } ?: loaded?.order

            _state.update {
                it.copy(
                    presets = presets,
                    activePreset = activeName,
                    // 旧预设/旧顺序覆盖里可能没有「人设描述」那一格：补上，否则人设会被静默丢弃
                    promptOrder = PromptOrderCompat.ensurePersonaSlot(
                        order.orEmpty().ifEmpty { PresetDefaults.ORDER },
                    ),
                    presetPrompts = loaded?.prompts ?: PresetDefaults.PROMPTS,
                    presetForbidOverrides = loaded?.forbidOverrideIds ?: emptySet(),
                    presetSampling = loaded?.sampling,
                )
            }
        }
    }

    /**
     * 把某一份预设灌进状态（**不改"全局选中的那份"**）。
     *
     * 会话/卡指定预设时用它：`promptOrder` / 提示词文本 / 采样参数才是发送时真正读的三个东西，
     * 只改 `activePreset` 不重算它们等于没换。
     */
    private suspend fun loadPresetIntoState(name: String?) {
        // 传 null = 回到**全局选中的那份**（不是"内置默认"）：会话跟着全局时，读的必须是同一份
        val target = name ?: _state.value.activePreset
        val loaded = withContext(Dispatchers.IO) { target?.let { presetRepository.load(it) } }
        val order = withContext(Dispatchers.IO) { target?.let { presetRepository.orderOverride(it) } }
            ?: loaded?.order
        _state.update {
            it.copy(
                // 同上：旧预设里可能缺「人设描述」那一格
                promptOrder = PromptOrderCompat.ensurePersonaSlot(
                    order.orEmpty().ifEmpty { PresetDefaults.ORDER },
                ),
                presetPrompts = loaded?.prompts ?: PresetDefaults.PROMPTS,
                presetForbidOverrides = loaded?.forbidOverrideIds ?: emptySet(),
                presetSampling = loaded?.sampling,
            )
        }
    }

    /** 导入一份 ST 预设（`.json`）。导入后自动选中它。 */
    fun importPreset(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayNameOf(uri)
                    val json = readTextOf(uri)
                    presetRepository.import(name, json)
                }
            }
            result.onSuccess { ref ->
                AppLog.i("preset", "导入预设 ${ref.name}（${ref.promptCount} 段，关 ${ref.disabledCount}）")
                withContext(Dispatchers.IO) { presetRepository.setActive(ref.name) }
                _state.update {
                    it.copy(notice = Notice(System.nanoTime(), "已导入「${ref.name}」并选中"))
                }
                refreshPresets()
                applySamplingOf(ref.name)
            }.onFailure { error ->
                AppLog.w("preset", "导入预设失败：${error.message}")
                _state.update { it.copy(notice = Notice(System.nanoTime(), "导入失败：${error.message}")) }
            }
        }
    }

    /** 选中某个预设；传 null = 回到内置默认。 */
    fun selectPreset(name: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { presetRepository.setActive(name) }
            AppLog.i("preset", "选中预设 ${name ?: "默认"}")
            refreshPresets()
            if (name != null) applySamplingOf(name)
        }
    }

    /** 预设里的温度 / max_tokens 灌进采样页那两个输入框（我们只有这两个控件）。 */
    private fun applySamplingOf(name: String) {
        viewModelScope.launch {
            val preset = withContext(Dispatchers.IO) { presetRepository.load(name) } ?: return@launch
            preset.sampling.temperature?.let { onTemperatureChange(it.toString()) }
            preset.sampling.maxTokens?.let { onMaxTokensChange(it.toString()) }
        }
    }

    /**
     * 打开"编辑预设"页（二级）。
     *
     * 顺手把它设为当前：顺序编辑必须对着**真正会发出去的那一份**，否则改完忘了选中，
     * 用户会以为顺序没生效。
     */
    fun openPresetEditor(name: String) {
        selectPreset(name)
        _state.update { it.copy(edit = it.edit.copy(presetEditName = name)) }
        AppLog.i("preset", "打开预设编辑 $name")
    }

    fun closePresetEditor() = _state.update {
        it.copy(edit = it.edit.copy(presetEditName = null, presetPromptDetail = null))
    }

    /** 预设编辑页点某一段 → 看这一段的全文（只读）。 */
    fun openPresetPrompt(identifier: String) {
        _state.update { it.copy(edit = it.edit.copy(presetPromptDetail = identifier)) }
        AppLog.i("preset", "看提示词全文 $identifier")
    }

    fun closePresetPrompt() = _state.update { it.copy(edit = it.edit.copy(presetPromptDetail = null)) }

    fun deletePreset(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { presetRepository.delete(name) }
            AppLog.i("preset", "删除预设 $name")
            refreshPresets()
        }
    }

    /** 开关某一段提示词（改的是本机的顺序覆盖，不动预设文件）。 */
    fun setPromptEnabled(identifier: String, enabled: Boolean) {
        val current = _state.value.promptOrder
        if (current.none { it.identifier == identifier }) return
        _state.update { it.copy(promptOrder = current.map { entry -> if (entry.identifier == identifier) entry.copy(enabled = enabled) else entry }) }
        persistPromptOrder()
    }

    /**
     * 拖动换位：**只改内存**。
     *
     * 拖的过程里每秒会调好几次，每次都落盘太浪费；松手时调 [commitPromptOrder]。
     */
    fun movePrompt(from: Int, to: Int) {
        val current = _state.value.promptOrder
        if (from !in current.indices || to !in current.indices || from == to) return
        val next = current.toMutableList()
        next.add(to, next.removeAt(from))
        _state.update { it.copy(promptOrder = next) }
    }

    /** 松手落盘。 */
    fun commitPromptOrder() {
        persistPromptOrder()
    }

    /** 回到预设文件里的原始顺序。 */
    fun resetPromptOrder() {
        // 内置默认预设没有覆盖可清（activePreset 为 null 时什么也不做）
        val name = _state.value.activePreset ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { presetRepository.setOrderOverride(name, null) }
            refreshPresets()
        }
    }

    private fun persistPromptOrder() {
        val snapshot = _state.value
        val name = snapshot.activePreset
        if (name == null) {
            // 内置默认预设：也不许改顺序（没有文件可对；要改就先导入一份）
            _state.update { it.copy(notice = Notice(System.nanoTime(), "内置默认预设不能改顺序，先导入一份预设")) }
            refreshPresets()
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { presetRepository.setOrderOverride(name, snapshot.promptOrder) }
        }
    }

    // ---- 正则脚本 ------------------------------------------------------------

    fun refreshRegexScripts() {
        viewModelScope.launch {
            val scripts = withContext(Dispatchers.IO) { regexRepository.scripts() }
            _state.update { it.copy(regexScripts = scripts) }
            AppLog.i("regex", "正则脚本 ${scripts.size} 条，启用 ${scripts.count { !it.disabled }} 条")
        }
    }

    fun importRegexScripts(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = readTextOf(uri)
                    regexRepository.import(json)
                }
            }
            result.onSuccess { count ->
                _state.update { it.copy(notice = Notice(System.nanoTime(), "已导入正则脚本，共 $count 条")) }
                refreshRegexScripts()
            }.onFailure { error ->
                _state.update { it.copy(notice = Notice(System.nanoTime(), "导入失败：${error.message}")) }
            }
        }
    }

    /** 内置显示正则的开关（只影响气泡显示；关掉 = 原文）。 */
    fun setBuiltinRegexEnabled(id: String, enabled: Boolean) {
        val next = if (enabled) _state.value.builtinRegexDisabled - id
        else _state.value.builtinRegexDisabled + id
        _state.update { it.copy(builtinRegexDisabled = next) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { settingsRepository.saveBuiltinRegexDisabled(next) }
        }
        AppLog.i("config", "内置正则 ${if (enabled) "开" else "关"} id=$id")
    }

    fun setRegexScriptDisabled(name: String, disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { regexRepository.setDisabled(name, disabled) }
            refreshRegexScripts()
        }
    }

    /**
     * 按 placement 跑正则脚本。
     *
     * 原版对用户输入是**在进聊天记录之前**改写（所以历史里存的就是改写后的文本），
     * 对模型输出只改显示、不改存档。宏（`{{char}}` / `{{user}}`）在这里注入。
     */
    private fun applyRegex(text: String, placement: RegexPlacement, card: CharacterCard): String {
        // 用"生效中"的那份：会话/卡可能把某些脚本关了，显示与输入必须读同一个集合
        val scripts = _state.value.effectiveRegexScripts
        if (scripts.isEmpty() || text.isEmpty()) return text
        val engine = RegexEngine(
            substitute = { value ->
                value.replace("{{char}}", card.name).replace("{{user}}", "User")
            },
        )
        return engine.applyAll(scripts, text, placement)
    }

    /**
     * 读一个 SAF 选来的文本文件。四处导入（聊天 / 世界书 / 预设 / 正则）原先各抄一遍
     * `contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error(…)`。
     *
     * 只统一**这一段**：取文件名（`displayNameOf`）只有世界书与预设需要，导入后的处理
     * （设默认、解析、逐条映射）四处本来就不一样 —— 那些差异是真实的，不抹平。
     */
    private fun readTextOf(uri: Uri): String =
        getApplication<Application>().contentResolver
            .openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("读不到文件")

    private fun displayNameOf(uri: Uri): String {
        runCatching {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            getApplication<Application>().contentResolver.query(uri, projection, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index)?.let { return it }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "worldinfo.json"
    }

    /**
     * 扫一遍世界书。数据来源 = 卡内嵌书 + 启用中的全局书；两边的本机覆盖
     * （关掉 / 强制开启）都已经在这一步算成"生效中的条目"了。
     *
     * 传给扫描器的聊天串要带名字前缀（原版 `chatForWI` 就是 `${name}: ${mes}`），
     * 否则 `matchPersonaDescription` 这类开关、以及"人类说话里点名"的条目会匹配不到。
     *
     * **纯函数**（审计 P0-2）：计时状态与全局书条目都从参数传进来，函数里不碰
     * [worldInfoStates] / [globalBookEntries] 这两张 HashMap，也不写偏好 ——
     * 这样它可以安全地在后台线程跑，调用方回到主线程再落状态、在 IO 里落盘。
     */
    /**
     * 组出世界书扫描的输入，交给 `core/lorebook` 的纯函数（B2，2026-11-28）。
     *
     * 以前这整套（过滤/选择/预算/前缀/扫描）写在本地，而且形参是**整个 `UiState`** ——
     * 一个纯计算却依赖整个界面状态，既测不了也看不出它到底依赖什么。现在规则与测试都在 core，
     * 这里只负责"从状态里取那几样东西"。
     *
     * 未接的一项：原版从预设的 `openai_max_context` 取预算，我们暂用 core 里的固定值（3795）。
     */
    private fun scanWorldInfo(
        card: CharacterCard,
        snapshot: UiState,
        history: List<ChatMessage>,
        state: LorebookScanState,
        globalEntriesByBook: Map<String, List<LorebookEntry>>,
    ): LorebookScanResult = app.tavern.core.lorebook.scanWorldInfo(
        WorldInfoInputs(
            cardEntries = card.lorebookEntries(),
            disabledUids = snapshot.bookDisabled,
            forcedUids = snapshot.bookForced,
            globalBooks = snapshot.worldBooks.map { GlobalBook(it.name, it.enabled) },
            onlyBookNames = snapshot.effectiveWorldBookNames,
            globalEntriesByBook = globalEntriesByBook,
            charName = card.name,
            userName = snapshot.effectiveUserName,
            characterDescription = card.description,
            characterPersonality = card.personality,
            scenario = card.scenario,
            history = history.map { WorldInfoMessage(it.role == ChatRole.User, it.content) },
            scanState = state,
            tokenCounter = LorebookTokenCounter { text -> Tokens.countAsMessage(snapshot.model, text) },
        ),
    )

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

        val rawText = snapshot.input.trim()
        val text = applyRegex(rawText, RegexPlacement.UserInput, characterCard)
        // 用户消息行与"生成中"必须在**同一次状态更新**里出现：拆成两次会让列表多一次插入，
        // 反排列表的 atBottom 就会多翻一次（发送时"历史闪一下"的来源之一）。
        beginCompletion(
            rows = snapshot.rows + ChatRow(newUiKey("u"), ChatRole.User, text),
            userText = text,
        )
    }

    /** 原版"继续"在请求末尾追加的那条 system 提示（证据：fixtures/expected/P0-6-continue.json）。 */
    private val continueHint =
        ChatMessage(ChatRole.System, "[Continue your last message without repeating its original content.]")

    /**
     * 真正发一次请求。三条路共用：`send()`（新用户消息）、`continueLast()`（落新气泡）、
     * `regenerateLast()`（**就地改写**最后一条）。
     *
     * 为什么抽出来：三条路在"扫世界书 → 拼提示词 → 建请求 → 流式收尾"这一段完全一样，
     * 只有三处不同：要不要把用户消息写进库（[userText]）、要不要追加 continue 提示
     * （[continuation]）、这一轮落**新行**还是**改写已有行**（[replace]）。
     * 各抄一份两百行会立刻分叉 —— 世界书、正则、思考强度改一处忘一处。
     */
    private fun beginCompletion(
        /** 这一轮开始时的时间线（已含本轮的用户消息；重新生成时**含**被改写那一行）。 */
        rows: List<ChatRow>,
        userText: String?,
        /**
         * 非 null = **就地改写**这一行（重新生成）。
         *
         * 行留在 [rows] 里不动，`inflight.uiKey` 直接用它的 key —— 那是同一个 LazyColumn 条目：
         * 不删不建、不重播淡入、不重排长文本。P1 的"撤下原行 + 收尾追加新行"是开场闪的根。
         */
        replace: ChatRow? = null,
        /** 重新生成前的 swipe 备选（新的那条会接在后面）。 */
        baseSwipes: List<String> = emptyList(),
        continuation: Boolean = false,
    ) {
        val snapshot = _state.value
        val characterCard = card ?: return
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.apiKey.isBlank()) {
            _state.update { it.copy(status = "先填 API Key（设置里填一次就会加密记住）") }
            return
        }
        // 一个会话只能有一路流（审计 P1-1）。起新流之前先把上一轮放弃 ——
        // **只有抢得到提交权才能取消**（纪律 1：否则会把正在写库的收尾打断）。
        currentRound?.let { previous ->
            if (previous.claimCommitRight()) {
                roundJob?.cancel()
                previous.close()
                AppLog.i("chat", "起新流：放弃上一轮 round=${previous.roundId}")
            } else {
                AppLog.i("chat", "起新流：上一轮已在收尾，不取消 round=${previous.roundId}")
            }
        }
        roundJob = null

        // 这一轮的身份与提交权。**所有收尾判断都走它**，不再有"谁都可以覆盖的 job 字段"。
        val round = GenerationRound(
            roundId = ++roundSeq,
            sessionId = sessionId,
            // 就地改写时沿用被改写那一行的 key（K1：行身份在存活期内不变）；
            // 新消息才自己建一个（占位 / 流式 / 定稿三态共用它，LazyColumn 才不换条目）。
            replyUiKey = replace?.uiKey ?: newUiKey("p"),
            targetDbId = replace?.dbId,
            userInput = if (userText != null) snapshot.input else null,
            baseSwipes = baseSwipes,
        )
        currentRound = round
        val collapsing = replace != null

        // 点下去**立刻**进"准备中"：分词与世界书扫描已经挪到后台（审计 P0-2），
        // 但再快也得先有反馈，否则长会话里点发送像没反应
        _state.update {
            it.copy(
                // 继续现在也落新气泡：旧气泡留在原地，续写从下面长出来（用户 2026-11-26）。
                // 重新生成**不从这里删行**（P3：就地改写，那一行一直在 rows 里）。
                rows = rows,
                inflight = Inflight(
                    roundId = round.roundId,
                    sessionId = sessionId,
                    uiKey = round.replyUiKey,
                    targetDbId = round.targetDbId,
                    text = "",
                ),
                // 重新生成：这一行先平滑收缩（正文按住不放，见 [Collapse] 与 r2 §13.1）
                collapse = if (collapsing) Collapse(round.roundId, round.replyUiKey) else null,
                input = "",
                status = "准备请求…",
                requestPreview = "",
            )
        }

        // 收缩窗口里到达的 token 先攒在这里（C2：模型常在 100–300ms 吐第一个字，
        // 中途把正文推上去 = 收缩动画被打断 + Modifier 被摘 = 剩下那截高度一次性 snap，
        // 比硬切还难看）。两个协程都在主线程，不需要加锁。
        val buffer = StringBuilder()
        if (collapsing) {
            viewModelScope.launch {
                delay(Motion.Collapse.toLong())
                _state.update { st ->
                    // 已经收尾了（0 字停止 / 失败）：窗口留给收尾那一支放行（C4）；
                    // 攒下的半截就地丢掉 —— 用户眼里一直是占位，没有"新正文"要保全。
                    val live = st.inflight ?: return@update st
                    if (live.roundId != round.roundId) st
                    else st.copy(collapse = null, inflight = live.copy(text = buffer.toString()))
                }
            }
        }

        // 提示词只认**已提交**的正文（生成中的那一轮不在 rows 里，天然进不了历史）；
        // 重新生成要把**被改写那一行**从历史里去掉（原版语义：当作它还没生成过）。
        val history: List<ChatMessage> = rows
            .filterNot { replace != null && it.uiKey == replace.uiKey }
            .map { ChatMessage(it.role, it.content) }
        // 两张 HashMap（世界书计时状态 / 全局书条目）在主线程取值，后台只读快照 ——
        // 后台扫描因此不碰任何共享可变状态
        val scanState = worldInfoStates[sessionId] ?: LorebookScanState()
        val globalEntries = HashMap(globalBookEntries)

        val task = viewModelScope.launch {
            // 用户消息先落库：进程被杀也不会丢"我说了什么"。
            // 重新生成没有新用户消息（userText == null），这一段整块跳过。
            if (userText != null) {
                val userMessageId = withContext(Dispatchers.IO) {
                    runCatching { chatRepository.append(sessionId, isUser = true, content = userText) }
                        .getOrNull()
                        ?.id
                }
                if (userMessageId == null) {
                    // 连提问都没写进去：这条根本没发出去，直接把这一轮作废。
                    abandonRound(round)
                    _state.update {
                        it.copy(
                            rows = it.rows.dropLast(1),
                            inflight = null,
                            input = snapshot.input,
                            status = "写库失败：这条消息没有发出去",
                        )
                    }
                    return@launch
                }
                // 记进这一轮：生成失败时按它撤销（**只删这一条**，绝不碰更早的消息）。
                round.userRowDbId = userMessageId
                // 行身份不变（K1），只把 dbId 补上 —— 界面这一帧也不重排（同一个 key、同一个节点）。
                val userInputKey = _state.value.rows.lastOrNull()?.uiKey
                _state.update { st ->
                    if (st.sessionId != sessionId) st
                    else st.copy(
                        rows = st.rows.map { row ->
                            if (row.uiKey == userInputKey) row.copy(dbId = userMessageId) else row
                        },
                    )
                }
                refreshSessions()
            }

            // 世界书扫描 + 提示词组装 + 逐条分词：整块在后台跑（审计 P0-2）。
            // 以前它在 `viewModelScope.launch` **之前**，也就是点发送那一下的主线程里；
            // 长会话下 `Tokens.countAsMessage` 要按历史逐条编码，卡顿几百毫秒是常态。
            val worldInfo: LorebookScanResult
            val messages: List<ChatMessage>
            try {
                val prepared = withContext(Dispatchers.Default) {
                    val scan = scanWorldInfo(characterCard, snapshot, history, scanState, globalEntries)
                    val built = PromptBuilder().build(
                        characterCard,
                        userName = snapshot.effectiveUserName,
                        history = history,
                        worldInfo = scan,
                        // 顺序与提示词内容都跟着当前预设走；没选预设时是原版默认预设那张表
                        order = snapshot.promptOrder.ifEmpty { PresetDefaults.ORDER },
                        prompts = snapshot.presetPrompts,
                        // 人设描述：原版把它插进 prompt_order 的 `personaDescription` 那一格
                        // （夹具 P1-8-persona-prompt 钉住位置与形态）
                        personaDescription = snapshot.effectivePersona?.description.orEmpty(),
                        // 作者注：按"每 N 条用户消息"插进聊天历史（夹具 P1-7-an-prompt / -depth-2）
                        authorNote = snapshot.effectiveAuthorNote,
                        // 世界书里 ANTop / ANBottom 的条目会拼进作者注那一条（原版 ANWithWI）
                        authorNoteTop = scan.authorNoteTop,
                        authorNoteBottom = scan.authorNoteBottom,
                        // 预设里打了 forbid_overrides 的段：卡里的 system_prompt 顶不掉它
                        forbidOverrideIds = snapshot.presetForbidOverrides,
                        // 世界书 outlet：`{{outlet::名字}}` 的取值表
                        outlets = scan.outletEntries,
                    )
                    // 继续：原版在 messages 末尾追加一条固定的 system 提示（不是我们发明的措辞）
                    scan to if (continuation) built + continueHint else built
                }
                worldInfo = prepared.first
                messages = prepared.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("chat", "准备请求失败", error)
                failRound(round, "准备请求失败：${error.message ?: error::class.simpleName}")
                return@launch
            }
            // 计时状态回主线程落（HashMap 只在主线程碰），存盘走 IO
            worldInfoStates[sessionId] = worldInfo.state
            withContext(Dispatchers.IO) {
                runCatching {
                    sessionScanStates.save(sessionId, LorebookScanStateCodec.encode(worldInfo.state))
                }
            }
            _state.update { st ->
                // 这一轮可能已经被停止/放弃：别用"已发 N 条"盖掉"已停止"。
                if (st.activeRoundId != round.roundId) st
                else st.copy(
                    status = "已发 ${messages.size} 条消息（${snapshot.model}）",
                    // 首行放请求参数：真机上「查看请求」是唯一不靠日志的证据，
                    // 采样五项有没有按三级生效，只能在这里看见（2026-11-26 用户要验采样）
                    requestPreview = buildString {
                        append("model=").append(snapshot.model)
                        append("  temperature=").append(snapshot.effectiveTemperature)
                        append("  max_tokens=").append(snapshot.effectiveMaxTokens ?: "不限")
                        append("  top_p=").append(snapshot.effectiveTopP ?: "不指定")
                        append("  frequency_penalty=")
                            .append(snapshot.effectiveFrequencyPenalty ?: "不指定")
                        append("  presence_penalty=")
                            .append(snapshot.effectivePresencePenalty ?: "不指定")
                        // 人设：名字不会单独出现在请求里（它只用于展开 {{user}}），
                        // 但"有没有选中 / 描述多长"必须能在这里看出来（2026-11-27 用户验人设时问过）
                        append("  人设=")
                            .append(snapshot.effectivePersona?.name?.ifBlank { "(无名)" } ?: "无")
                            .append("(描述=")
                            .append(snapshot.effectivePersona?.description?.length ?: 0)
                            .append("字)")
                        messages.forEach { message ->
                            append("\n\n[${message.role.wire}] ").append(message.content)
                        }
                    },
                )
            }
            AppLog.i(
                "chat",
                "发送 model=${snapshot.model} messages=${messages.size} " +
                    "repeat=${history.count { it.role == ChatRole.User }} " +
                    "wi=${worldInfo.activated.size}(before=${worldInfo.beforeChar.length}字 after=${worldInfo.afterChar.length}字) " +
                    // 人设有没有生效、描述是不是空 —— 2026-11-27 真机"本次请求里没有人设项"，
                    // 光看请求面板分不清"没选人设"和"选了但描述空"（描述空时原版也不发这一段）
                    "人设=${snapshot.effectivePersona?.name?.ifBlank { "(无名)" } ?: "无"}" +
                    "(描述=${snapshot.effectivePersona?.description?.length ?: 0}字) " +
                    "预设=${snapshot.activePreset ?: "内置"} 顺序=${snapshot.promptOrder.size}格 " +
                    "人设格=" +
                    if (snapshot.promptOrder.any { it.identifier == PromptOrderCompat.PERSONA_DESCRIPTION }) {
                        "有"
                    } else {
                        "无"
                    },
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
                // 五项统一走 effective*：会话 > 卡 > 预设 > 全局，别在这里再写一层 ?: 链
                temperature = snapshot.effectiveTemperature,
                maxTokens = snapshot.effectiveMaxTokens,
                topP = snapshot.effectiveTopP,
                frequencyPenalty = snapshot.effectiveFrequencyPenalty,
                presencePenalty = snapshot.effectivePresencePenalty,
                stream = true,
                // 思考强度只有两级：会话 → 卡。都为 null 就不发这个字段；
                // `"off"` = 明确不发（档案级不再参与，用户 2026-11-25 第 2 条）。
                extraBody = when (val effort = snapshot.effectiveReasoningEffort) {
                    null, "off" -> null
                    else -> effort
                }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { mapOf("reasoning_effort" to JsonPrimitive(it)) }
                    .orEmpty(),
            )

            try {
                var settled = false
                client.stream(request).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            buffer.append(event.text)
                            // 收缩窗口里**只攒不推**（r2 §13.1 C2）：布局正被动画改，
                            // 这时候推正文会把收缩打断。
                            if (_state.value.collapse?.roundId == round.roundId) return@collect
                            _state.update { st ->
                                // 收尾已经清掉了这一轮：迟到的 token **不许**把气泡又点亮
                                if (st.activeRoundId != round.roundId) st
                                else st.copy(
                                    inflight = st.inflight?.copy(text = buffer.toString()),
                                )
                            }
                        }

                        is StreamEvent.Completed -> {
                            settled = true
                            AppLog.i(
                                "chat",
                                "完成 finish=${event.finishReason ?: "null"} chars=${buffer.length}",
                            )
                            finishRound(round, buffer.toString(), "完成（finish_reason=${event.finishReason ?: "null"}）")
                        }

                        is StreamEvent.Failed -> {
                            settled = true
                            AppLog.e(
                                "chat",
                                "失败 http=${event.httpCode} msg=${event.message.take(200)}",
                            )
                            val hint = if (event.httpCode == 401) "｜Key 不对或已失效" else ""
                            failRound(round, "失败（HTTP ${event.httpCode}）$hint：${event.message}")
                        }
                    }
                }
                // 流结束了却没有任何终止事件：别让界面卡在"生成中"
                if (!settled) {
                    if (buffer.isEmpty()) {
                        failRound(round, "流意外结束：没有收到任何内容")
                    } else {
                        finishRound(round, buffer.toString(), "流意外结束（保留了已收到的内容）")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("chat", "客户端异常", error)
                failRound(round, "客户端异常：${error.message ?: error::class.simpleName}")
            }
        }
        // 上面那个 launch 在 Main.immediate 下会**立刻跑第一段**：如果它已经自我作废
        // （用户消息没写进去），这里不要再把一个死轮次的 job 挂上去。
        roundJob = if (currentRound === round) task else null
    }

    // ---- 收尾（P0：提交权 → 写库 → 提交界面）---------------------------------

    /**
     * 抢提交权。**同一轮只有一个赢家**；输家只记一行日志 —— 不写库、不改界面、不取消 job。
     */
    private fun claimFor(round: GenerationRound, reason: SettleReason): Boolean {
        val won = round.claimCommitRight()
        if (!won) AppLog.i("chat", "收尾 被抢先 round=${round.roundId} reason=$reason")
        return won
    }

    /** 正常情况下做废一轮（连提问都没写进去）。 */
    private fun abandonRound(round: GenerationRound) {
        round.close()
        if (currentRound === round) {
            currentRound = null
            roundJob = null
        }
    }

    /** 流式正常收尾。 */
    private suspend fun finishRound(round: GenerationRound, text: String, status: String) {
        if (!claimFor(round, SettleReason.Completed)) return
        settleClaimed(round, SettleReason.Completed, text = text, okStatus = status)
    }

    /** 生成失败（**不是**保存失败：这条只负责撤掉本轮提问）。 */
    private suspend fun failRound(round: GenerationRound, message: String) {
        if (!claimFor(round, SettleReason.Failed)) return
        settleClaimed(round, SettleReason.Failed, text = "", okStatus = message)
    }

    /**
     * 收尾的唯一实现：**写库 → 提交界面**。调用前必须已经拿到提交权。
     *
     * - `cancelJob`：停止那条路要在**拿到权之后**才停止流（纪律 1）。
     * - `text == null`：从界面上的 `streamingText` 取（用户点停止时屏幕上是哪截就用哪截）。
     * - `NonCancellable` **只包这一段**：请求、流式、刷新会话列表都保持可取消。
     *   以前取消会把"已经写完库、还没提交界面"的收尾打断 → 回复在库里、界面没有。
     */
    private suspend fun settleClaimed(
        round: GenerationRound,
        reason: SettleReason,
        text: String?,
        okStatus: String,
        cancelJob: Job? = null,
    ) {
        cancelJob?.cancel()
        var refresh = false
        var openCollapse: Collapse? = null
        try {
            withContext(NonCancellable) {
                val body = text ?: _state.value.streamingText
                val plan = round.planWrite(body, reason)
                val result = executeWrite(round, plan)
                // 停止的文案跟"保留了几个字"走，只能在这里算（它是唯一一个调用方给不出文案的收尾）
                val status = okStatus.ifEmpty {
                    if (body.isBlank()) "已停止" else "已停止（保留了 ${body.length} 字）"
                }
                val now = _state.value
                val facts = UiFacts(sessionId = now.sessionId, activeRoundId = now.activeRoundId)
                val effects = round.planEffects(facts, plan, result, status)
                // 收缩窗口还开着（0 字停止 / 失败时正文是**涨回**来的）：留着它，等收尾落地
                // 之后再关一整个动画时长 —— 让"缩下去"和"涨回来"走同一条动画（C4）。
                openCollapse = now.collapse
                    ?.takeIf { it.roundId == round.roundId && it.uiKey == round.replyUiKey }
                // 窗口**已经关了**（收缩 380ms 早就走完，界面停在"重新生成中"占位上）时点停止：
                // 旧正文一样要"长回来"，否则就是"缩得平滑、涨得瞬跳"。这里补开一个涨回窗口
                // （`growBack` 告诉界面：起点是占位高度，不是收缩前的高度）。
                val growBack =
                    if (round.targetDbId != null && plan is WritePlan.Nothing && openCollapse == null) {
                        Collapse(round.roundId, round.replyUiKey, growBack = true)
                    } else {
                        null
                    }
                // 一次 update 落地全部效果：不露出"行已加、生成中还没清"的中间帧
                _state.update { st ->
                    val next = applyEffects(
                        st,
                        effects,
                        round.replyUiKey,
                        (result as? WriteResult.Ok)?.dbId,
                    )
                    // 换会话之后不许给别人的会话开窗口
                    if (growBack != null && st.sessionId == round.sessionId) {
                        next.copy(collapse = growBack)
                    } else {
                        next
                    }
                }
                if (growBack != null) openCollapse = growBack
                refresh = effects.refreshSessions
                AppLog.i(
                    "chat",
                    "收尾 round=${round.roundId} reason=$reason 计划=${plan.logName()} " +
                        "结果=${result.logName()} 正文=${body.length}字",
                )
            }
        } catch (cancelled: CancellationException) {
            // 整轮被取消（例如 ViewModel 被清）时不要假装"保存失败"：界面已经不在了。
            throw cancelled
        } catch (error: Exception) {
            // 不该发生（plan/apply 都是纯计算）。真发生就按"保存失败"收界面，别把界面卡在生成中。
            AppLog.e("chat", "收尾异常 round=${round.roundId}", error)
            _state.update { st ->
                if (st.activeRoundId != round.roundId) st
                else st.copy(
                    inflight = null,
                    status = "保存失败：${error.message ?: error::class.simpleName}",
                )
            }
        } finally {
            round.close()
            if (currentRound === round) {
                currentRound = null
                roundJob = null
            }
            openCollapse?.let { releaseCollapse(it) }
        }
        if (refresh) viewModelScope.launch { runCatching { refreshSessions() } }
    }

    /**
     * 真正落库。**任何异常都变成 [WriteResult.Failed]** —— 不抛给调用方，
     * 否则界面那一笔就提交不了（而库可能已经写进去了）。
     */
    private suspend fun executeWrite(round: GenerationRound, plan: WritePlan): WriteResult =
        runCatching {
            when (plan) {
                WritePlan.Nothing -> WriteResult.Skipped

                is WritePlan.AppendReply -> withContext(Dispatchers.IO) {
                    chatRepository.append(round.sessionId, isUser = false, content = plan.content)
                }.let { WriteResult.Ok(it.id) }

                is WritePlan.RewriteReply -> withContext(Dispatchers.IO) {
                    // 正文 + 版本表一次事务写完（分开写会出现"正文换了、版本表没换"）
                    chatRepository.replaceMessage(plan.targetDbId, plan.content, plan.swipes)
                }.let { ok ->
                    if (ok) WriteResult.Ok(plan.targetDbId) else WriteResult.Failed("目标行不存在")
                }

                is WritePlan.DeleteUserRow -> withContext(Dispatchers.IO) {
                    chatRepository.deleteMessage(plan.dbId)
                }.let { WriteResult.Ok(plan.dbId) }
            }
        }.getOrElse { error ->
            AppLog.e("chat", "写库失败 round=${round.roundId} 计划=${plan.logName()}", error)
            WriteResult.Failed(error.message ?: error::class.simpleName ?: "写库异常")
        }

    /**
     * 关掉收缩窗口：`animateContentSize` 必须**真的摘下去**（r2 §13.1 C1）。
     *
     * 这里是"晚一个动画时长"才关（C4）：0 字停止 / 保存失败时那一行是**涨回来**的，
     * 窗口得留到动画走完，否则"缩得平滑、涨得瞬跳"一对比更刺眼。
     * 守卫用整个 [Collapse]：同一行上一轮留下的定时器不许关掉这一轮的窗口。
     */
    private fun releaseCollapse(window: Collapse) {
        viewModelScope.launch {
            delay(Motion.Collapse.toLong())
            _state.update { st -> if (st.collapse == window) st.copy(collapse = null) else st }
        }
    }

    /** 把收尾效果落到 UI 状态上（一次 update）。语义见 `core/generation` 的 `RowAction`。 */
    private fun applyEffects(
        st: UiState,
        effects: SettleEffects,
        replyUiKey: String,
        /** 刚落库那一行的行号：追加成功才有（就地改写用的还是原行号）。 */
        savedDbId: Long?,
    ): UiState {
        var next = st
        when (val action = effects.row) {
            RowAction.None -> Unit

            // 新回复定稿：**沿用这一轮的 uiKey** —— 流式那条与定稿这条是同一个 LazyColumn item
            //（不删、不建、不重播淡入、不重排长文本）。
            is RowAction.Append -> next = next.copy(
                rows = next.rows + ChatRow(replyUiKey, ChatRole.Assistant, action.content, savedDbId),
                lastSwipes = action.swipes,
                lastSwipeId = action.swipeId,
            )

            // 就地改写（重新生成）：**同一个 uiKey 那一行**换正文。不新增行、不换 key ——
            // 这也是"开场闪"消失的原因：列表自始至终是同一个条目。
            is RowAction.Rewrite -> next = next.copy(
                rows = next.rows.map { row ->
                    if (row.uiKey == replyUiKey) row.copy(content = action.content) else row
                },
                lastSwipes = action.swipes,
                lastSwipeId = action.swipeId,
            )

            is RowAction.RemoveUserRow -> next = next.copy(
                // 按 dbId 精确移除这一轮刚写的那条用户消息（不碰更早的任何一行）
                rows = next.rows.filterNot { it.dbId == action.dbId },
            )
        }
        effects.restoreInput?.let { input -> next = next.copy(input = input) }
        if (effects.clearInflight) next = next.copy(inflight = null)
        effects.status?.let { kind ->
            next = next.copy(
                status = when (kind) {
                    is SettleStatus.Ok -> kind.text
                    SettleStatus.SaveFailed -> "保存失败：这条回复没有入库（可以重新生成）"
                    SettleStatus.RollbackFailed -> "取消失败：这条提问可能已经在库里"
                },
            )
        }
        return next
    }


    private fun WritePlan.logName(): String = when (this) {
        WritePlan.Nothing -> "无"
        is WritePlan.AppendReply -> "追加"
        is WritePlan.RewriteReply -> "改写#$targetDbId"
        is WritePlan.DeleteUserRow -> "撤销提问#$dbId"
    }

    private fun WriteResult.logName(): String = when (this) {
        WriteResult.Skipped -> "跳过"
        is WriteResult.Ok -> "成功"
        is WriteResult.Failed -> "失败($reason)"
    }

    // ---- 消息操作（P0-6 的一部分：重新生成 / 编辑 / 删除）---------------------

    /**
     * 重新生成：**就地改写**最后一条助手消息，用剩下的历史再请求一次。
     *
     * 原版是"swipe 出新的一条，旧的一条存进 `swipes[]`"。P3 起我们也是同一个位置改写
     * （旧版本进 swipe 备选，能用 `‹N/M›` 翻回去），区别只在于：界面上**那一行从头到尾没动过**
     * —— 不删行、不追加行、不换 key。开场那一下是 `animateContentSize` 的平滑收缩（r2 §13.1）。
     */
    fun regenerateLast() {
        val snapshot = _state.value
        if (snapshot.isStreaming) return
        val last = snapshot.rows.lastOrNull()
        if (last == null || last.role != ChatRole.Assistant) {
            notify("最后一条不是角色的回复，没得重新生成")
            return
        }
        if (snapshot.apiKey.isBlank()) {
            _state.update { it.copy(status = "先填 API Key（设置里填一次就会加密记住）") }
            return
        }
        val rowId = last.dbId ?: run {
            notify("这一条还没有落库，不能重新生成")
            return
        }
        viewModelScope.launch {
            // swipe 备选只对"当前显示的那条"有效：被打断 / 换过一条之后可能已经过期，
            // 拿它当底本会把上一条的旧文本写到这一条上（2026-11-26 真机：打断后重新生成没有 2/2）
            val base = snapshot.lastSwipes.takeIf {
                it.isNotEmpty() && it.getOrNull(snapshot.lastSwipeId) == last.content
            } ?: listOf(last.content)
            withContext(Dispatchers.IO) { chatRepository.setSwipes(rowId, base) }
            AppLog.i(
                "chat",
                "重新生成：就地改写 row=$rowId（已有 ${base.size} 条备选），收缩窗口 ${Motion.Collapse}ms",
            )
            // **不 trim `rows`**：那一行留在时间线里，回头收尾按原行号改写。
            // 也不再动 `lastSwipes`（版本表）—— 那一行没离开界面，删它反而会让空白期里
            // "上一条助手消息"顶上来显示别人的版本（v163 那一类 bug 的根源）。
            beginCompletion(
                rows = snapshot.rows,
                userText = null,
                replace = last,
                baseSwipes = base,
            )
        }
    }

    /**
     * 继续：让角色顺着最后一条往下写（原版 `Generate('continue')`）。
     *
     * **请求**与原来一致：末尾多一条 system 提示
     * `[Continue your last message without repeating its original content.]`
     * （这条是从原版夹具读出来的：fixtures/expected/P0-6-continue.json）。
     *
     * **落库形态与原版不同**（用户 2026-11-26 拍板）：原版把续写 `mes +=` 拼回同一条消息，
     * 我们存成**一条新的助手消息**，界面上就是新气泡。理由：气泡 = 一次发言，
     * 删除/重新生成/撤回的粒度落在这一小段上；而且不用"生成期间把旧气泡撤下去"，
     * 停止与失败路径都少一整支。提示词行为不变（那一轮的 messages 逐字相同）。
     *
     * 与「重新生成」的区别：重新生成不新增消息，改写同一行并追加一条 swipe 备选。
     */
    fun continueLast() {
        val snapshot = _state.value
        if (snapshot.isStreaming) return
        val last = snapshot.rows.lastOrNull()
        if (last == null || last.role != ChatRole.Assistant) {
            notify("最后一条不是角色的回复，没法继续")
            return
        }
        if (snapshot.apiKey.isBlank()) {
            _state.update { it.copy(status = "先填 API Key（设置里填一次就会加密记住）") }
            return
        }
        // 继续 = 让角色接着说，**结果落成一条新消息**（用户 2026-11-26 定的形态）。
        // 所以它和普通发送走同一条路：旧气泡留在界面上不动，续写从下面长出来。
        // 原版 ST 是把 `mes +=` 拼回同一条；这条差异记在 compat-matrix 的 P0-6。
        AppLog.i(
            "chat",
            "继续：历史 ${snapshot.rows.size} 条（末尾带 continue 提示），落新气泡 row=${last.dbId}",
        )
        beginCompletion(
            rows = snapshot.rows,
            userText = null,
            continuation = true,
        )
    }

    /**
     * 翻到第 [index] 条 swipe（原版对最后一条消息做 swipe 的语义）。
     *
     * 只改这一条消息的正文与 `swipe_id`，不动时间线：备选列表里存的就是全部版本。
     */
    fun selectSwipe(index: Int) {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.isStreaming) return
        val text = snapshot.lastSwipes.getOrNull(index) ?: return
        if (index == snapshot.lastSwipeId) return
        val lastRow = snapshot.rows.lastOrNull() ?: return
        viewModelScope.launch {
            lastRow.dbId?.let { id ->
                withContext(Dispatchers.IO) { chatRepository.updateMessage(id, text) }
            }
            _state.update { current ->
                if (current.sessionId != sessionId) current
                else current.copy(
                    // 只换这一行的正文：key 与 dbId 不动（K1），其余行原样
                    rows = current.rows.map { row ->
                        if (row.uiKey == lastRow.uiKey) row.copy(content = text) else row
                    },
                    lastSwipeId = index,
                )
            }
            AppLog.i("chat", "swipe 切到 ${index + 1}/${snapshot.lastSwipes.size}")
        }
    }

    /**
     * 删一条消息（入参是**行身份** `uiKey`，不是下标）。
     *
     * 以前按 UI 下标记到库里的行号，中间删一条之后两边就错位；现在按 `dbId` 删，
     * 界面按 `uiKey` 就地移除（K4：不在组合存活期内重建 rows）。
     */
    fun deleteMessageAt(uiKey: String) {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.isStreaming) {
            notify("正在生成，等它写完再删")
            return
        }
        val target = snapshot.rows.firstOrNull { it.uiKey == uiKey } ?: return
        val wasLast = snapshot.rows.lastOrNull()?.uiKey == uiKey
        viewModelScope.launch {
            target.dbId?.let { id ->
                withContext(Dispatchers.IO) { chatRepository.deleteMessage(id) }
            }
            _state.update { current ->
                if (current.sessionId != sessionId) current
                else current.copy(
                    rows = current.rows.filterNot { it.uiKey == uiKey },
                    // 删掉最后一条 = swipe 备选也一起没了（库里是 ON DELETE CASCADE）
                    lastSwipes = if (wasLast) emptyList() else current.lastSwipes,
                    lastSwipeId = if (wasLast) 0 else current.lastSwipeId,
                )
            }
            refreshSessions()
            AppLog.i("chat", "删除单条 key=$uiKey role=${target.role}")
            notify("已删除 1 条")
        }
    }

    /**
     * 撤回：回到某条**用户**消息，把它和它之后的所有消息一起删掉，正文放回输入框。
     *
     * 用户 2026-11-25 第 11 条要的"像编程工具 rewind"：不是删一条，而是把时间线退到那次提问，
     * 改完再发 —— 所以这条用户消息本身也删掉（发送时会重新追加，不会出现两条一样的）。
     */
    fun rewindToMessage(uiKey: String) {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        val index = snapshot.rows.indexOfFirst { it.uiKey == uiKey }
        if (index < 0) return
        val target = snapshot.rows[index]
        // 先把"点了哪一个"记下来：真机上这类操作看不出到底走了哪条分支
        AppLog.i("chat", "撤回请求 index=$index key=$uiKey role=${target.role} streaming=${snapshot.isStreaming}")
        if (snapshot.isStreaming) {
            notify("正在生成：先点停止，再撤回")
            return
        }
        if (target.role != ChatRole.User) {
            notify("只能撤回到自己发的消息")
            return
        }
        viewModelScope.launch {
            // 删的是"这条用户消息 + 它之后的所有消息"（**双方都算**：角色回复一起撤）。
            // 按 dbId 逐条删，不再靠"UI 下标 == 库行号"。
            val removed = snapshot.rows.drop(index)
            var failed = 0
            withContext(Dispatchers.IO) {
                removed.asReversed().forEach { row ->
                    val id = row.dbId ?: return@forEach
                    if (runCatching { chatRepository.deleteMessage(id) }.isFailure) failed++
                }
            }
            // K4：组合存活期内**不从库里重建** —— 那就地移除这一段，其余行的 key 一动不动。
            // 只有真的全删掉了才动界面；删失败就保留原样并如实提示（重进会话会看到实际结果）。
            val done = failed == 0
            _state.update { current ->
                if (current.sessionId != sessionId) current
                else current.copy(
                    rows = if (done) current.rows.take(index) else current.rows,
                    input = if (done) target.content else current.input,
                    // 被删掉的那批里可能有最后一条助手消息，它的 swipe 备选也跟着没了
                    lastSwipes = if (done) emptyList() else current.lastSwipes,
                    lastSwipeId = if (done) 0 else current.lastSwipeId,
                    // 列表削短后要回到最底部（用户 2026-11-25：撤回后停在半空）
                    scrollToBottomSignal = if (done) current.scrollToBottomSignal + 1
                    else current.scrollToBottomSignal,
                )
            }
            refreshSessions()
            if (done) {
                AppLog.i(
                    "chat",
                    "撤回 index=$index：删 ${removed.size} 条（我方的这条 + 之后双方全部），剩 ${snapshot.rows.size - removed.size} 条",
                )
                notify("已撤回 ${removed.size} 条消息，正文回到输入框")
            } else {
                AppLog.e("chat", "撤回失败：$failed 条没删掉（界面保持原样）")
                notify("撤回没完成：$failed 条没能删除，重进会话会看到实际结果")
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
