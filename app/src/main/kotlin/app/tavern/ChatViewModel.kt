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
import app.tavern.core.chat.ChatMessage as ChatFileMessage
import app.tavern.core.card.PngCard
import app.tavern.core.card.bookEntries
import app.tavern.core.card.lorebookEntries
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import app.tavern.core.model.ModelProfile
import app.tavern.core.model.ModelProfiles
import app.tavern.core.lorebook.LorebookEntry
import app.tavern.core.lorebook.withDisabled
import app.tavern.core.lorebook.LorebookGlobalScanData
import app.tavern.core.lorebook.LorebookScanResult
import app.tavern.core.lorebook.LorebookScanner
import app.tavern.core.lorebook.LorebookScanSettings
import app.tavern.core.lorebook.LorebookScanState
import app.tavern.core.lorebook.LorebookScanStateCodec
import app.tavern.core.lorebook.LorebookTokenCounter
import app.tavern.core.preset.PresetDefaults
import app.tavern.core.preset.PromptOrderEntry
import app.tavern.core.preset.SamplingPreset
import app.tavern.core.prompt.PromptBuilder
import app.tavern.core.regex.RegexEngine
import app.tavern.core.regex.RegexPlacement
import app.tavern.core.regex.RegexScript
import app.tavern.core.tokenizer.Tokens
import app.tavern.data.cards.CardRef
import app.tavern.data.cards.ImportResult
import app.tavern.data.chat.ChatSession
import app.tavern.data.chat.ImportMessage
import app.tavern.data.log.AppLog
import app.tavern.data.presets.PresetRef
import app.tavern.data.sessionstate.SessionScanStateRepository
import app.tavern.data.config.ConfigOverride
import app.tavern.data.config.ConfigOverrideRepository
import app.tavern.data.settings.CardBookOverride
import app.tavern.data.presets.PresetRepository
import app.tavern.data.profiles.ProfileRepository
import app.tavern.data.worldinfo.WorldBookRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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

    /** 一本全局世界书的条目列表（设置页点进去看）。 */
    data class WorldBookEntryView(
        val uid: Int,
        val title: String,
        val keys: List<String>,
        val preview: String,
        /** 生效状态 = 文件里的 disable **叠加** 应用里的手动开关。 */
        val enabled: Boolean,
        /** 文件里本来就是关的（界面要说明"文件里就是关的"）。 */
        val disabledInFile: Boolean,
        /** 应用内强制开启（文件里关着、被本机打开）。 */
        val forcedOn: Boolean,
    )

    data class WorldBookView(
        val name: String,
        val entries: List<WorldBookEntryView>,
        /** false = 还在读文件（界面显示"读取中"）。 */
        val loaded: Boolean = false,
    )

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
        /** 卡列表是否读完过一次（同 [sessionsLoaded]：没读完不能显示"还没有卡"）。 */
        val cardsLoaded: Boolean = false,
        val cardId: String? = null,
        val cardName: String = "",
        val sessions: List<ChatSession> = emptyList(),
        /** 会话列表是否**读完过一次**。false 时界面不能显示"还没有会话"（那只是还没读到）。 */
        val sessionsLoaded: Boolean = false,
        /**
         * 启动是否已经"可以看"了：首屏列表与头像都准备好之后才 true。
         *
         * 界面在 false 时整屏盖着背景色（什么都不画），true 时整块淡出 ——
         * 观感是"导航壳和列表一起浮现"，而不是壳先出现、列表再补上。
         */
        val booted: Boolean = false,
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
        /** 组件预览页（**只在 debug 包里能进**，入口在 设置 → 关于）。 */
        val galleryOpen: Boolean = false,
        /** 角色卡配置的二级页（作用域：当前详情页那张卡）。 */
        val cardConfigPage: CardConfigPage? = null,
        /** 非 null 时显示角色详情页（覆盖在三栏之上，返回键先退它）。 */
        val detailCardId: String? = null,
        /** 详情页的完整卡信息；打开详情后异步填上。 */
        val detail: CardDetail? = null,
        /**
         * 详情页停在哪个标签。存在这里而不是页面局部：进聊天/进配置二级页时详情页会被
         * 移出组合树，局部 `remember` 会归零（"从会话进来、返回停在简介"就是这么来的）。
         */
        val detailTab: DetailTab = DetailTab.Description,
        /** 当前会话所属卡的样式覆盖；null = 跟随全局。 */
        val cardMessageStyle: MessageStyle? = null,
        /** 当前这张卡里被关掉的内嵌世界书条目（按条目 uid）。 */
        val bookDisabled: Set<Int> = emptySet(),
        /** 当前这张卡里被**强制开启**的内嵌世界书条目（卡里关着的那些，按 uid）。 */
        val bookForced: Set<Int> = emptySet(),
        /** 已导入的全局世界书。 */
        val worldBooks: List<WorldBookRef> = emptyList(),
        // ---- 预设（设置→预设） ----
        /** 导入的预设文件。 */
        val presets: List<PresetRef> = emptyList(),
        /** 当前选中的预设名；null = 内置默认预设。 */
        val activePreset: String? = null,
        /** 当前生效的 prompt_order（拖动与开关改的就是这一份）。 */
        val promptOrder: List<PromptOrderEntry> = emptyList(),
        /** 选中的预设里那些"纯文本提示词"，拼提示词时按 identifier 取。 */
        val presetPrompts: Map<String, String> = PresetDefaults.PROMPTS,
        /** 选中的预设带来的 top_p / 两个 penalty（温度与 max_tokens 仍走采样页那两个输入框）。 */
        val presetSampling: SamplingPreset? = null,
        /** 非 null 时打开"编辑预设"二级页（顺序开关与拖动都在那一页里）。 */
        val presetEditName: String? = null,
        /** 正则脚本（导入自 ST 的脚本数组）。 */
        val regexScripts: List<RegexScript> = emptyList(),
        /** 非 null 时显示某本全局书的条目列表。 */
        val worldBookView: WorldBookView? = null,
        // ---- 采样参数（文本框保留原始输入，避免输入半截被强转） ----
        val temperatureText: String = "1.0",
        val maxTokensText: String = "0",
        val messageStyle: MessageStyle = MessageStyle.Card,
        val themePreset: ThemePreset = ThemePreset.Violet,
        val themeMode: ThemeMode = ThemeMode.Follow,
        /** 二级页转场风格；见 [NavAnimation]。 */
        val navAnimation: NavAnimation = NavAnimation.Slide,
        /** 视觉效果档位；见 [VisualEffects]。 */
        val visualEffects: VisualEffects = VisualEffects.Off,
        /** 全部模型档案（只在"模型"页与发送时用）。 */
        val profiles: List<ModelProfile> = emptyList(),
        /** 当前生效的档案 id；null = 一条都没有（老版本首次启动的瞬间）。 */
        val activeProfileId: String? = null,
        /** 会话级覆盖（三级继承最内层）。字段为 null = 跟随角色卡。 */
        val sessionOverride: ConfigOverride = ConfigOverride(),
        /** 角色卡级覆盖（中间层）。字段为 null = 跟随全局。 */
        val cardOverride: ConfigOverride = ConfigOverride(),
        /** 非 null 时显示「档案编辑」页（三级）；null = 不在编辑。 */
        val profileDraft: ProfileDraft? = null,
        /** 会话配置二级页（从聊天页 ⋮ 进）。 */
        val sessionConfigOpen: Boolean = false,
        /** 编辑角色卡（从详情页 ⋮ 进）。 */
        val cardEditOpen: Boolean = false,
        /**
         * 三级配置的"选一项"页（模型 / 世界书 / 正则 / 预设）。
         *
         * 以前这些都做成底部抽屉（用户 2026-11-25：应该像设置页那样**跳二级页面**）。
         * 卡片里带作用域：会话级从「会话配置」进，角色卡级从详情页「配置」进。
         */
        val configPicker: ConfigPickerTarget? = null,
        /** 当前这张卡的全部开场白（第一条是 first_mes，其后是备选）。换开场白只读它。 */
        val greetings: List<String> = emptyList(),
        /**
         * 最后一条助手消息的 swipe 备选（对齐原版 `swipes[]`：**当前显示的那条也在里面**）。
         * 只有最后一条能翻 —— 原版 `isMessageSwipeable` 也是这么判的。
         */
        val lastSwipes: List<String> = emptyList(),
        /** 当前显示的是 `lastSwipes` 里的哪一条。 */
        val lastSwipeId: Int = 0,
        val notice: Notice? = null,
    ) {
        /** 聊天页实际用的样式：卡级覆盖优先，否则全局。 */
        val effectiveMessageStyle: MessageStyle
            get() = cardMessageStyle ?: messageStyle

        // ---- 三级继承的生效值（会话 → 角色卡 → 全局）--------------------------
        // 全 App 只有这几个 getter 能回答"现在真正用的是什么"，界面与请求都读它们；
        // 各处自己写 `x ?: y ?: z` 迟早会有一处漏掉一层。

        /** 真正生效的档案 id：会话 → 卡 → 全局默认档案。 */
        val effectiveProfileId: String?
            get() = sessionOverride.profileId ?: cardOverride.profileId ?: activeProfileId

        /** 真正生效的思考强度：null = 跟随档案；`"off"` = 明确关掉。 */
        val effectiveReasoningEffort: String?
            get() = sessionOverride.reasoningEffort ?: cardOverride.reasoningEffort

        /** 真正生效的预设名（null = 内置默认）。 */
        val effectivePresetName: String?
            get() = sessionOverride.presetName ?: cardOverride.presetName ?: activePreset

        /** 参与的全局世界书：null = 按每本书自己的启用开关。 */
        val effectiveWorldBookNames: Set<String>?
            get() = sessionOverride.worldBookNames ?: cardOverride.worldBookNames

        /** 参与的正则脚本 id（脚本名）：null = 全部启用。 */
        val effectiveRegexIds: Set<String>?
            get() = sessionOverride.regexIds ?: cardOverride.regexIds

        /** 实际参与的正则脚本（显示与输入侧都读它，保证"看到的就是生效的"）。 */
        val effectiveRegexScripts: List<RegexScript>
            get() {
                val only = effectiveRegexIds ?: return regexScripts
                return regexScripts.filter { it.name in only }
            }
    }

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
    private var job: Job? = null
    private var keySaveJob: Job? = null

    init {
        // 首帧就要用的外观项：同步读偏好（**不碰 Keystore**）。否则第一帧只能按默认值画
        // ——「一进来是轻量主题、一瞬间后变玻璃」就是这么来的（用户 2026-11-24 报）。
        val startupAt = SystemClock.elapsedRealtime()
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
                    maxTokensText = saved.maxTokens.toString(),
                    // 外观已经由 applyAppearanceSync 摆好；这里以同一份偏好再写一次（幂等），
                    // 免得"预读"和"正式读"哪天不一致。
                    messageStyle = runCatching { MessageStyle.valueOf(saved.messageStyle) }
                        .getOrDefault(MessageStyle.Card),
                    themePreset = runCatching { ThemePreset.valueOf(saved.themePreset) }
                        .getOrDefault(ThemePreset.Violet),
                    themeMode = runCatching { ThemeMode.valueOf(saved.themeMode) }
                        .getOrDefault(ThemeMode.Follow),
                    navAnimation = runCatching { NavAnimation.valueOf(saved.navAnimation) }
                        .getOrDefault(NavAnimation.Slide),
                    visualEffects = runCatching { VisualEffects.valueOf(saved.visualEffects) }
                        .getOrDefault(VisualEffects.Off),
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
                cardEditOpen = false,
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
     * @param leaveDetail 从卡详情页点「新建会话」时传 true：顺手把详情页关掉并切到会话栏，
     * 这样返回时落在主页的会话页，而不是又回到那张卡的详情（用户 2026-11-24 报）。
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
                    // 从详情页新建会话：返回目标是主页「会话」，不是这张卡的详情
                    detailCardId = if (leaveDetail) null else it.detailCardId,
                    detail = if (leaveDetail) null else it.detail,
                    cardConfigPage = if (leaveDetail) null else it.cardConfigPage,
                    screen = if (leaveDetail) Screen.Sessions else it.screen,
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
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("读不到文件")
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
        if (snapshot.transcript.any { it.role == ChatRole.User }) {
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
                        transcript = listOf(ChatMessage(ChatRole.Assistant, target)) +
                            current.transcript.drop(1),
                    )
                }
            } else if (stored.isEmpty()) {
                withContext(Dispatchers.IO) {
                    chatRepository.append(sessionId, isUser = false, content = target)
                }
                _state.update { current ->
                    if (current.sessionId != sessionId) current
                    else current.copy(transcript = listOf(ChatMessage(ChatRole.Assistant, target)))
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
            notify("本会话用「${profile.name}」")
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
            notify("模型跟随默认档案")
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

    fun useCardWorldBooks(names: Set<String>?) {
        val cardId = _state.value.detailCardId ?: return
        viewModelScope.launch {
            persistCardConfig(cardId) { it.copy(worldBookNames = names) }
            _state.update { it.copy(cardOverride = it.cardOverride.copy(worldBookNames = names)) }
            AppLog.i("config", "卡级世界书 card=${cardId.take(8)} ${names?.size ?: "跟随"} 本")
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
        _state.update { it.copy(cardEditOpen = true) }
        AppLog.i("card", "打开卡编辑")
    }

    fun closeCardEditor() = _state.update { it.copy(cardEditOpen = false) }

    /**
     * 把编辑后的字段写回卡文件（备份在 `.bak.png`，原子替换）。
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
                _state.update { it.copy(cardEditOpen = false) }
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
            _state.update { it.copy(cardEditOpen = false) }
            val cards = withContext(Dispatchers.IO) { cardRepository.list() }
            // 会话列表显示的是会话行里冗余的 card_name：卡改名后一并刷新，否则列表还挂着旧名
            withContext(Dispatchers.IO) {
                runCatching { chatRepository.updateCardName(cardId, trimmed) }
            }
            _state.update { it.copy(cards = cards) }
            refreshSessions()
            loadDetail(cardId)
            AppLog.i("card", "卡编辑保存 name=${trimmed.take(20)}")
            notify("已保存（原文件备份为 .bak.png）")
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

    fun closeChat() = _state.update {
        it.copy(chatOpen = false, sessionConfigOpen = false, configPicker = null)
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
                    val json = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("读不到文件")
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
        val view = withContext(Dispatchers.IO) {
            val parseAt = SystemClock.elapsedRealtime()
            val book = worldBookRepository.load(name) ?: return@withContext null
            val parseMs = SystemClock.elapsedRealtime() - parseAt
            val overrides = worldBookRepository.disabledEntries(name)
            val forced = worldBookRepository.forcedEntries(name)
            val buildAt = SystemClock.elapsedRealtime()
            val view = WorldBookView(
                name = name,
                entries = book.entries.map { entry ->
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
                },
                loaded = true,
            )
            AppLog.i(
                "wi",
                "开书 $name：解析 ${parseMs}ms，建条目 ${SystemClock.elapsedRealtime() - buildAt}ms，" +
                    "${view.entries.size} 条",
            )
            view
        }
        if (view == null) {
            _state.update { it.copy(status = "这本书读不出来了：$name", worldBookView = null) }
        } else {
            // 读的过程中用户可能已经返回了，别把页面又推回来
            _state.update { if (it.worldBookView?.name == name) it.copy(worldBookView = view) else it }
            AppLog.i("wi", "开书 $name 落地 +${SystemClock.elapsedRealtime() - started}ms")
        }
    }

    fun closeWorldBook() {
        _state.update { it.copy(worldBookView = null) }
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
                    promptOrder = order.orEmpty().ifEmpty { PresetDefaults.ORDER },
                    presetPrompts = loaded?.prompts ?: PresetDefaults.PROMPTS,
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
                promptOrder = order.orEmpty().ifEmpty { PresetDefaults.ORDER },
                presetPrompts = loaded?.prompts ?: PresetDefaults.PROMPTS,
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
                    val json = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("读不到文件")
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
        _state.update { it.copy(presetEditName = name) }
        AppLog.i("preset", "打开预设编辑 $name")
    }

    fun closePresetEditor() = _state.update { it.copy(presetEditName = null) }

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
                    val json = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("读不到文件")
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
     */
    private fun scanWorldInfo(
        card: CharacterCard,
        snapshot: UiState,
        history: List<ChatMessage>,
    ): LorebookScanResult {
        val sessionKey = snapshot.sessionId ?: return LorebookScanResult()
        val cardEntries = card.lorebookEntries()
            .filter { it.uid !in snapshot.bookDisabled }
            // 强制开启的条目在解析时被标成 disabled（卡里没写 enabled 的那些），这里翻回来
            .map { if (it.uid in snapshot.bookForced) it.withDisabled(false) else it }
        // 全局书排在内嵌书之后（原版 character_first 策略：卡的书先，全局书后）
        // 三级继承：会话/卡指定了"这场对话带哪些全局书"就按它来，否则按书自己的启用开关
        val only = snapshot.effectiveWorldBookNames
        val globalEntries = snapshot.worldBooks
            .filter { if (only != null) it.name in only else it.enabled }
            .flatMap { globalBookEntries[it.name].orEmpty() }
        val entries = cardEntries + globalEntries
        if (entries.isEmpty()) {
            worldInfoStates.remove(sessionKey)
            return LorebookScanResult()
        }

        val userName = "User"
        val settings = LorebookScanSettings(
            charName = card.name,
            userName = userName,
            // 预算按"提示词能用的上下文"算，与 Max tokens 无关的那部分；先跟原版默认预设一致
            maxContext = 3795,
            tokenCounter = LorebookTokenCounter { text -> Tokens.countAsMessage(snapshot.model, text) },
        )
        val chat = history.map { message ->
            val name = if (message.role == ChatRole.User) userName else card.name
            "$name: ${message.content}"
        }
        val global = LorebookGlobalScanData(
            characterDescription = card.description,
            characterPersonality = card.personality,
            scenario = card.scenario,
        )

        val result = LorebookScanner().scan(
            entries = entries,
            chat = chat,
            settings = settings,
            state = worldInfoStates[sessionKey] ?: LorebookScanState(),
            global = global,
        )
        worldInfoStates[sessionKey] = result.state
        // 每轮都落一份：进程被杀也不会丢"正在生效的 sticky"
        runCatching {
            sessionScanStates.save(sessionKey, LorebookScanStateCodec.encode(result.state))
        }
        return result
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

        val rawText = snapshot.input.trim()
        val text = applyRegex(rawText, RegexPlacement.UserInput, characterCard)
        beginCompletion(snapshot.transcript + ChatMessage(ChatRole.User, text), userText = text)
    }

    /**
     * 真正发一次请求。两条路共用：`send()`（新用户消息）与 `regenerateLast()`（不追加用户消息）。
     *
     * 为什么抽出来：重新生成与发送在"扫世界书 → 拼提示词 → 建请求 → 流式收尾"这一段完全一样，
     * 唯一区别是**要不要把一条用户消息写进库**（[userText] 为 null 表示不写）。
     * 各抄一份两百行会立刻分叉 —— 世界书、正则、思考强度改一处忘一处。
     */
    private class ReplaceTarget(
        val rowId: Long,
        /** 已有的 swipe 备选（新的那条会接在它后面）。 */
        val base: List<String>,
        /** 失败时要还原到界面上那条（重新生成/继续把它从界面上先撤下来了）。 */
        val restore: ChatMessage,
        /**
         * true = **继续**（把新文本接在原来那条后面，改的是备选里的同一条）；
         * false = 重新生成（换一条备选，旧的那条留在列表里可以翻回去）。
         */
        val append: Boolean = false,
        /** 当前显示的是第几条备选（继续时要改这一条）。 */
        val swipeId: Int = 0,
    )

    /** 原版"继续"在请求末尾追加的那条 system 提示（证据：fixtures/expected/P0-6-continue.json）。 */
    private val continueHint =
        ChatMessage(ChatRole.System, "[Continue your last message without repeating its original content.]")

    private fun beginCompletion(
        history: List<ChatMessage>,
        userText: String?,
        replacing: ReplaceTarget? = null,
        continuation: Boolean = false,
    ) {
        val snapshot = _state.value
        val characterCard = card ?: return
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.apiKey.isBlank()) {
            _state.update { it.copy(status = "先填 API Key（设置里填一次就会加密记住）") }
            return
        }
        // 世界书：卡内嵌书扫一遍再拼提示词。没有条目时是空结果，不会多出一条消息。
        val worldInfo = scanWorldInfo(characterCard, snapshot, history)
        val built = PromptBuilder().build(
            characterCard,
            userName = "User",
            history = history,
            worldInfo = worldInfo,
            // 顺序与提示词内容都跟着当前预设走；没选预设时是原版默认预设那张表
            order = snapshot.promptOrder.ifEmpty { PresetDefaults.ORDER },
            prompts = snapshot.presetPrompts,
        )
        // 继续：原版在 messages 末尾追加一条固定的 system 提示（不是我们发明的措辞）
        val messages = if (continuation) built + continueHint else built

        _state.update {
            it.copy(
                // 继续：最后一条留在 history 里（提示词要用），界面上先撤下来，
                // 免得"原来的气泡 + 正在写的流式气泡"同屏出现两条
                transcript = if (replacing?.append == true) history.dropLast(1) else history,
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
            "发送 model=${snapshot.model} messages=${messages.size} repeat=${history.count { it.role == ChatRole.User }} " +
                "wi=${worldInfo.activated.size}(before=${worldInfo.beforeChar.length}字 after=${worldInfo.afterChar.length}字)",
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
            // 预设带来的 top_p / 两个 penalty：我们没有对应控件，但请求里该发就发
            topP = snapshot.presetSampling?.topP,
            frequencyPenalty = snapshot.presetSampling?.frequencyPenalty,
            presencePenalty = snapshot.presetSampling?.presencePenalty,
            stream = true,
            // 思考强度：会话级覆盖优先（"off" = 明确关掉，不发这个字段），否则跟随档案
            extraBody = when (val effort = snapshot.effectiveReasoningEffort) {
                null -> activeProfile?.reasoningEffort
                "off" -> null
                else -> effort
            }
                ?.takeIf { it.isNotBlank() }
                ?.let { mapOf("reasoning_effort" to JsonPrimitive(it)) }
                .orEmpty(),
        )

        job = viewModelScope.launch {
            val buffer = StringBuilder()
            // 用户消息落库后的行号：失败时要撤回，所以得在下面的本地函数之前声明
            var userMessageId: Long? = null

            suspend fun finish(text: String, status: String) {
                if (replacing != null) {
                    // 重新生成/继续：都不新增一行，改写原来那一行。
                    // 继续是把新文本接在后面（原版落盘结果就是 "ok ok" 这种拼接）
                    val combined = if (replacing.append) replacing.restore.content + text else text
                    if (text.isNotEmpty()) {
                        val next = if (replacing.append) {
                            replacing.base.toMutableList().also {
                                it[replacing.swipeId.coerceIn(0, it.lastIndex)] = combined
                            }
                        } else {
                            replacing.base + text
                        }
                        withContext(Dispatchers.IO) {
                            chatRepository.updateMessage(replacing.rowId, combined)
                            chatRepository.setSwipes(replacing.rowId, next)
                        }
                        refreshSessions()
                        _state.update { current ->
                            if (current.sessionId != sessionId) {
                                current.copy(streamingText = "", isStreaming = false, status = status)
                            } else {
                                current.copy(
                                    transcript = current.transcript +
                                        ChatMessage(ChatRole.Assistant, combined),
                                    lastSwipes = next,
                                    lastSwipeId = if (replacing.append) replacing.swipeId else next.lastIndex,
                                    streamingText = "",
                                    isStreaming = false,
                                    status = status,
                                )
                            }
                        }
                    } else {
                        // 一个字都没收到：把撤下去的那条放回来
                        _state.update { current ->
                            if (current.sessionId != sessionId) {
                                current.copy(streamingText = "", isStreaming = false, status = status)
                            } else {
                                current.copy(
                                    transcript = current.transcript + replacing.restore,
                                    streamingText = "",
                                    isStreaming = false,
                                    status = status,
                                )
                            }
                        }
                        refreshSessions()
                    }
                    return
                }
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
                if (replacing != null) {
                    // 重新生成失败：把撤下去的那条原样放回界面（库里那一行根本没动过）
                    _state.update { current ->
                        if (current.sessionId != sessionId) {
                            current.copy(streamingText = "", isStreaming = false, status = message)
                        } else {
                            current.copy(
                                transcript = current.transcript + replacing.restore,
                                streamingText = "",
                                isStreaming = false,
                                status = message,
                            )
                        }
                    }
                    refreshSessions()
                    return
                }
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
                            // 失败时撤回的只有"这次追加的那一条"：重新生成没有追加用户消息，
                            // 这里再去 dropLast 就会把上一句用户说的话删掉
                            transcript = if (userText != null) {
                                current.transcript.dropLast(1)
                            } else {
                                current.transcript
                            },
                            streamingText = "",
                            isStreaming = false,
                            input = snapshot.input,
                            status = message,
                        )
                    }
                }
            }

            // 用户消息先落库：进程被杀也不会丢"我说了什么"。
            // 重新生成没有新用户消息（userText == null），这一段整块跳过。
            if (userText != null) {
                userMessageId = withContext(Dispatchers.IO) {
                    runCatching { chatRepository.append(sessionId, isUser = true, content = userText) }
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

    // ---- 消息操作（P0-6 的一部分：重新生成 / 编辑 / 删除）---------------------

    /**
     * 重新生成：把最后一条助手消息撤掉，用剩下的历史再请求一次。
     *
     * 原版是"swipe 出新的一条，旧的一条存进 `swipes[]`"，我们**还没有 swipes 存储**
     * （那要动表结构 + 夹具），所以现在这一步是"替换"：撤掉再生成。
     * 会话内容与提示词的结果一致；差别只在于翻不回上一版。
     */
    fun regenerateLast() {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.isStreaming) return
        val last = snapshot.transcript.lastOrNull()
        if (last == null || last.role != ChatRole.Assistant) {
            notify("最后一条不是角色的回复，没得重新生成")
            return
        }
        if (snapshot.apiKey.isBlank()) {
            _state.update { it.copy(status = "先填 API Key（设置里填一次就会加密记住）") }
            return
        }
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatRepository.messages(sessionId) }
            val row = stored.lastOrNull()?.takeIf { !it.isUser } ?: run {
                notify("库里没有可重新生成的那一条")
                return@launch
            }
            // 老数据可能还没有 swipe 备选：先把"现在显示的这条"写进去，翻回去才有东西可翻
            val base = snapshot.lastSwipes.ifEmpty { listOf(row.content) }
            withContext(Dispatchers.IO) { chatRepository.setSwipes(row.id, base) }
            val trimmed = snapshot.transcript.dropLast(1)
            _state.update {
                it.copy(
                    transcript = trimmed,
                    streamingText = "",
                    status = "重新生成中…",
                    lastSwipes = base,
                    lastSwipeId = base.lastIndex,
                )
            }
            AppLog.i("chat", "重新生成：已有 ${base.size} 条备选，生成第 ${base.size + 1} 条")
            beginCompletion(
                history = trimmed,
                userText = null,
                replacing = ReplaceTarget(row.id, base, last),
            )
        }
    }

    /**
     * 继续：让角色顺着最后一条往下写（原版 `Generate('continue')`）。
     *
     * 与"重新生成"的区别（两者都不新增消息）：
     * - 继续：请求末尾多一条 system 提示 `[Continue your last message without repeating its original content.]`，
     *   结果**接在**最后一条后面；
     * - 重新生成：不带这条提示，结果作为**新的 swipe 备选**（旧的那条留着可翻回）。
     * 这两条都是从原版夹具里读出来的，不是我们猜的（fixtures/expected/P0-6-continue.json）。
     */
    fun continueLast() {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.isStreaming) return
        val last = snapshot.transcript.lastOrNull()
        if (last == null || last.role != ChatRole.Assistant) {
            notify("最后一条不是角色的回复，没法继续")
            return
        }
        if (snapshot.apiKey.isBlank()) {
            _state.update { it.copy(status = "先填 API Key（设置里填一次就会加密记住）") }
            return
        }
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatRepository.messages(sessionId) }
            val row = stored.lastOrNull()?.takeIf { !it.isUser } ?: run {
                notify("库里没有可继续的那一条")
                return@launch
            }
            val base = snapshot.lastSwipes.ifEmpty { listOf(row.content) }
            _state.update {
                it.copy(
                    transcript = it.transcript.dropLast(1),
                    streamingText = "",
                    status = "继续写…",
                    lastSwipes = base,
                )
            }
            AppLog.i("chat", "继续：历史 ${snapshot.transcript.size} 条（末尾带 continue 提示）")
            beginCompletion(
                history = snapshot.transcript,
                userText = null,
                replacing = ReplaceTarget(
                    rowId = row.id,
                    base = base,
                    restore = last,
                    append = true,
                    swipeId = snapshot.lastSwipeId.coerceIn(0, base.lastIndex),
                ),
                continuation = true,
            )
        }
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
        val lastIndex = snapshot.transcript.lastIndex
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatRepository.messages(sessionId) }
            stored.lastOrNull()?.let { row ->
                withContext(Dispatchers.IO) { chatRepository.updateMessage(row.id, text) }
            }
            _state.update { current ->
                if (current.sessionId != sessionId) current
                else current.copy(
                    transcript = current.transcript.mapIndexed { i, message ->
                        if (i == lastIndex) message.copy(content = text) else message
                    },
                    lastSwipeId = index,
                )
            }
            AppLog.i("chat", "swipe 切到 ${index + 1}/${snapshot.lastSwipes.size}")
        }
    }

    /** 删一条消息（index = 在 transcript 里的位置）。库里同下标的行一起删。 */
    fun deleteMessageAt(index: Int) {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.isStreaming) {
            notify("正在生成，等它写完再删")
            return
        }
        val target = snapshot.transcript.getOrNull(index) ?: return
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatRepository.messages(sessionId) }
            stored.getOrNull(index)?.let { row ->
                withContext(Dispatchers.IO) { chatRepository.deleteMessage(row.id) }
            }
            _state.update { current ->
                if (current.sessionId != sessionId) current
                else current.copy(
                    transcript = current.transcript.filterIndexed { i, _ -> i != index },
                    // 删掉最后一条 = swipe 备选也一起没了（库里是 ON DELETE CASCADE）
                    lastSwipes = if (index == current.transcript.lastIndex) emptyList()
                    else current.lastSwipes,
                    lastSwipeId = 0,
                )
            }
            refreshSessions()
            AppLog.i("chat", "删除单条 index=$index role=${target.role}")
            notify("已删除 1 条")
        }
    }

    /**
     * 撤回：回到某条**用户**消息，把它和它之后的所有消息一起删掉，正文放回输入框。
     *
     * 用户 2026-11-25 第 11 条要的"像编程工具 rewind"：不是删一条，而是把时间线退到那次提问，
     * 改完再发 —— 所以这条用户消息本身也删掉（发送时会重新追加，不会出现两条一样的）。
     */
    fun rewindToMessage(index: Int) {
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.isStreaming) {
            notify("正在生成，等它写完再撤回")
            return
        }
        val target = snapshot.transcript.getOrNull(index) ?: return
        if (target.role != ChatRole.User) {
            notify("只能撤回到自己发的消息")
            return
        }
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatRepository.messages(sessionId) }
            val removed = stored.drop(index)
            withContext(Dispatchers.IO) { removed.forEach { chatRepository.deleteMessage(it.id) } }
            _state.update { current ->
                if (current.sessionId != sessionId) current
                else current.copy(
                    transcript = current.transcript.take(index),
                    input = target.content,
                    // 被删掉的那批里可能有最后一条助手消息，它的 swipe 备选也跟着没了
                    lastSwipes = emptyList(),
                    lastSwipeId = 0,
                )
            }
            refreshSessions()
            AppLog.i("chat", "撤回到 index=$index，删 ${removed.size} 条")
            notify("已回退到这条，改完再发")
        }
    }

    /** 改一条消息的正文。只改库与内存，不重新请求（重新生成是另一件事）。 */
    fun editMessageAt(index: Int, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val snapshot = _state.value
        val sessionId = snapshot.sessionId ?: return
        if (snapshot.isStreaming) {
            notify("正在生成，等它写完再改")
            return
        }
        if (snapshot.transcript.getOrNull(index)?.content == text) return
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { chatRepository.messages(sessionId) }
            stored.getOrNull(index)?.let { row ->
                withContext(Dispatchers.IO) { chatRepository.updateMessage(row.id, text) }
            }
            _state.update { current ->
                if (current.sessionId != sessionId) current
                else current.copy(
                    transcript = current.transcript.mapIndexed { i, message ->
                        if (i == index) message.copy(content = text) else message
                    },
                    // 改的如果正好是最后一条，swipe 里那一份也要跟着改，否则翻一次就回退成旧文
                    lastSwipes = if (index == current.transcript.lastIndex &&
                        current.lastSwipes.isNotEmpty()
                    ) {
                        // 下标夹一下：删过消息或老数据可能让 swipeId 越界，越界就是崩
                        current.lastSwipes.toMutableList().also {
                            it[current.lastSwipeId.coerceIn(0, it.lastIndex)] = text
                        }
                    } else {
                        current.lastSwipes
                    },
                )
            }
            AppLog.i("chat", "改单条 index=$index ${text.length} 字")
        }
    }

    private fun stamp(): String =
        formatter.format(Instant.ofEpochMilli(System.currentTimeMillis()))

    private companion object {
        val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.US)
            .withZone(ZoneId.systemDefault())
    }
}
