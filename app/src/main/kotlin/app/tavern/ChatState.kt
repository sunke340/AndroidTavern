package app.tavern

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import app.tavern.core.model.ConfigOverride
import app.tavern.core.model.ModelProfile
import app.tavern.core.model.ModelProfiles
import app.tavern.core.preset.EffectiveConfig
import app.tavern.core.preset.GlobalDefaults
import app.tavern.core.preset.PresetDefaults
import app.tavern.core.preset.PromptOrderEntry
import app.tavern.core.preset.SamplingPreset
import app.tavern.core.preset.SamplingTexts
import app.tavern.core.preset.resolveEffectiveConfig
import app.tavern.core.prompt.AuthorNote
import app.tavern.core.regex.BuiltinRegex
import app.tavern.core.regex.RegexScript
import app.tavern.data.cards.CardRef
import app.tavern.data.chat.ChatSession
import app.tavern.data.presets.PresetRef
import app.tavern.data.settings.Persona
import app.tavern.data.worldinfo.WorldBookRef

/**
 * 界面状态与它周边的值类型。
 *
 * 为什么单独成文件（2026-11-28，B5-0 的第一步）：这些类型原来是 `ChatViewModel` 的嵌套类，
 * 结果"看一个状态字段要翻 3,700 行的类"。它们本质是**数据**（不可变 UI 状态与其行/占位/草稿），
 * 和 ViewModel 的编排职责不是一回事。搬出来是纯搬迁：字段、默认值、getter 语义一律不动。
 *
 * 注意：`UiState` 的生效值 getter 只做"取用"，规则在 `core/preset/EffectiveConfig.kt`（B1）。
 */
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
    /** 搜索词（空 = 不过滤）。条目页顶部的搜索框读它。 */
    val query: String = "",
    /** 这本书原本有多少条（搜索时用来显示"N / M"）。 */
    val total: Int = 0,
)

/** 条目全文页（世界书条目与卡内嵌书条目共用）。 */
data class WorldBookEntryDetail(
    val title: String,
    val keys: List<String>,
    val content: String,
    val enabled: Boolean,
    /** 页头副标题：「书：名字」/「卡内嵌书：卡名」。 */
    val source: String,
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
    /** 「一行一条 Name: value」的原始文本。 */
    val headersText: String = "",
    val isDefault: Boolean = false,
) {
    val isNew: Boolean get() = id == null

    val canSave: Boolean
        get() = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

/**
 * 一条**已提交**的消息行。
 *
 * 身份不变量（K1–K5，改这里之前先读）：
 * - **K1**：`uiKey` 在行的存活期内**永不改变**（补 `dbId`、换正文、切 swipe、定稿都不变）；
 * - **K2**：只要求在**一次 ChatScreen 组合的生命周期内**唯一 → 不持久化、不落库；
 * - **K3**：命名空间不重叠 —— `m{dbId}`（库里读出来的）/ `u{seq}`（本次发的用户消息）/
 *   `p{seq}`（这一轮新生成的行）。重新生成**不新开 key**：它复用被改写那一行的 `m{dbId}`。
 * - **K4**：组合存活期内**不得从库里重建 `rows`**（撤回改成"按 dbId 删 + 就地移除行"）；
 *   重建会让本次新建的行换 key → LazyColumn 当新条目、重播淡入；
 * - **K5**：`dbId` **不参与 key**，它只用于 DB 操作与"是否已落库"。
 */
data class ChatRow(
    val uiKey: String,
    val role: ChatRole,
    val content: String,
    val dbId: Long? = null,
)

/**
 * 正在生成的那一轮（**至多一个**）。
 *
 * 它不进 [UiState.rows]：占位/流式/定稿都是"rows + 它"合成出来的，
 * 所以生成中的内容永远不会被误当成"已保存的消息"（也不进提示词、不进导出）。
 */
data class Inflight(
    val roundId: Long,
    val sessionId: String,
    /**
     * 这一轮在界面上的**行身份**。
     *
     * 新消息：这一轮自己新建的 key（`p{seq}`）；重新生成：**被改写那一行原来的 key**
     * （`m{dbId}`）—— 那一行从没离开 `rows`，这是一次性改写同一个条目，不是"换一条"。
     */
    val uiKey: String,
    /** 非 null = 就地改写这一行（重新生成）。 */
    val targetDbId: Long?,
    /** 已收到的正文（空 = 还没吐字）。收缩窗口里它一直是空的（见 [UiState.collapse]）。 */
    val text: String,
)

/**
 * 重新生成的**平滑收缩窗口**（r2 §13.1）。
 *
 * 非 null = 那一行的高度正被窗口的动画值夹着：这段时间新 token 只攒不推（C2）、
 * 桥接三件套全关（C3）。为什么窗口要有自己的一份状态而不是从 `inflight` 推：
 * 0 字停止 / 失败时 `inflight` 已经清掉了，可"旧正文涨回来"还得走完同一条动画（C4）。
 *
 * [roundId] 是守卫：同一行上一轮留下的定时器不许把这一轮的动画掐掉。
 *
 * [growBack] = 这个窗口是**收尾之后补开的**：起点是占位高度、终点是收缩前的高度
 * （涨回来）；否则是开场那个窗口（收缩前的高度 → 占位高度）。两者是相反方向的同一条动画。
 */
data class Collapse(
    val roundId: Long,
    val uiKey: String,
    val growBack: Boolean = false,
)

data class UiState(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val input: String = "",
    /** 已提交的消息行（时间线，旧→新）。生成中的那一轮**不在里面**。 */
    val rows: List<ChatRow> = emptyList(),
    /** 正在生成的那一轮；null = 没在生成。 */
    val inflight: Inflight? = null,
    /** 重新生成的收缩窗口；非 null = 那一行还挂着 `animateContentSize`（见 [Collapse]）。 */
    val collapse: Collapse? = null,
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
    /**
     * 这一次聊天是"从卡详情页新建会话"进来的：关掉它时**直接落回会话栏**。
     *
     * 为什么不在开聊天那一刻就把详情页关掉（2026-11-27 真机）：页面栈转场期间，退场页渲染的是
     * "它还是当前页时"的状态快照，而快照每帧跟着当前页写 —— 详情页的数据一被清空，
     * 那 280ms 的退场页就成了空壳/别的页，观感是"翻页动画还没跑，背后的详情页已经变成会话页"。
     * 所以改成：进聊天时什么都不动，**关聊天时**再连详情页一起退掉。
     */
    val returnToSessionsOnClose: Boolean = false,
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
    /**
     * 预设里打了 `forbid_overrides` 的提示词：这些段的正文不许被角色卡顶掉
     * （夹具 `P0-5-forbid-overrides`）。空集 = 都可以被顶掉（原版默认）。
     */
    val presetForbidOverrides: Set<String> = emptySet(),
    /** 选中的预设带来的 top_p / 两个 penalty（温度与 max_tokens 仍走采样页那两个输入框）。 */
    val presetSampling: SamplingPreset? = null,
    /** 非 null 时打开"编辑预设"二级页（顺序开关与拖动都在那一页里）。 */
    val presetEditName: String? = null,
    /** 非 null 时显示「提示词全文」（从预设编辑页点进来）：值是 prompt identifier。 */
    val presetPromptDetail: String? = null,
    /** 正则脚本（导入自 ST 的脚本数组）。 */
    val regexScripts: List<RegexScript> = emptyList(),
    /**
     * 内置显示正则里**关掉**的 id（`core/regex` 的 `BuiltinRegex`）。
     *
     * 内置规则是"默认正则"：只影响显示，关掉就等于没这条规则（原文照出）。
     */
    val builtinRegexDisabled: Set<String> = emptySet(),
    /** 非 null 时显示某本全局书的条目列表。 */
    val worldBookView: WorldBookView? = null,
    /** 世界书条目全文页；非空 = 目的地是那一页。 */
    val worldBookEntry: WorldBookEntryDetail? = null,
    // ---- 采样参数（文本框保留原始输入，避免输入半截被强转） ----
    val temperatureText: String = "1.0",
    val maxTokensText: String = "0",
    /** top_p / 两个 penalty：空串 = 不指定（请求里不发）。用户 2026-11-25 第 2 条补齐五项。 */
    val topPText: String = "",
    val frequencyPenaltyText: String = "",
    val presencePenaltyText: String = "",
    val messageStyle: MessageStyle = MessageStyle.Card,
    /** 流式回复的揭示节奏（码点/秒）。只影响显示，见 `docs/plan-reveal-pacing.md`。 */
    val revealSpeed: RevealSpeed = RevealSpeed.Default,
    /** 人设列表（需求批 F）。[effectivePersona] 决定 `{{user}}` 与 `personaDescription` 那一段。 */
    val personas: List<Persona> = emptyList(),
    /** 全局默认人设；null = 没有默认，`{{user}}` 退回 "User"。 */
    val personaDefaultId: String? = null,
    /** 全局气泡字号（sp，只影响聊天页气泡正文）。会话/卡级可在它之上覆盖。 */
    val bubbleFontSize: Float = DefaultBubbleFontSize,
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
    /**
     * 让聊天列表"跳回最底部"的一次性信号（每次 +1）。
     *
     * 撤回会把列表削短：如果用户当时正翻着旧消息，视口停在中段、下面留一片空白。
     * 视图层收到新信号就 `scrollToItem(0)`（反排列表的 0 就是最底部）。
     */
    val scrollToBottomSignal: Long = 0,
    val notice: Notice? = null,
) {

    /** 已提交的正文（提示词、导出、开场白判据都读它）。生成中的那一轮不在里面。 */
    val transcript: List<ChatMessage>
        get() = rows.map { ChatMessage(it.role, it.content) }

    /** 有在跑的生成轮次。 */
    val isStreaming: Boolean
        get() = inflight != null

    /** 屏幕上正在长的正文（没有生成时为空串）。 */
    val streamingText: String
        get() = inflight?.text.orEmpty()

    /**
     * 这一轮生成的轮次号；没有在跑时为 null。
     *
     * 收尾时"这条消息/这个状态栏还归不归我"全看它：写完库到提交界面之间，用户可能已经
     * 切了会话或开了新一轮，**旧轮不许碰新轮的界面**（见 `core/generation` 的纪律 3）。
     */
    val activeRoundId: Long?
        get() = inflight?.roundId
    /** 聊天页实际用的样式：卡级覆盖优先，否则全局。 */
    val effectiveMessageStyle: MessageStyle
        get() = cardMessageStyle ?: messageStyle

    /** 生效人设；null = 一个都没选（或选的那个被删了）。 */
    val effectivePersona: Persona?
        get() = effectivePersonaId?.let { id -> personas.firstOrNull { it.id == id } }

    /**
     * 生效作者注：会话 > 卡（正文为空 = 这一层没有，继续往上找）。
     * 原版是按"每 N 条用户消息插一次"插进聊天历史的一段话，不是系统提示里的固定一段。
     */
    val effectiveAuthorNote: AuthorNote?
        get() {
            // 正文由 core 解析（空串归一、逐层继承，规则与测试都在 `core/preset`）；
            // 深度/角色/间隔/位置是**复合**（带默认值 + 角色名映射），留在这一层。
            val text = effective.authorNoteText ?: return null
            val depth = sessionOverride.authorNoteDepth
                ?: cardOverride.authorNoteDepth
                ?: 4
            val roleName = sessionOverride.authorNoteRole ?: cardOverride.authorNoteRole
            val role = when (roleName) {
                "User" -> ChatRole.User
                "Assistant" -> ChatRole.Assistant
                else -> ChatRole.System
            }
            val interval = sessionOverride.authorNoteInterval
                ?: cardOverride.authorNoteInterval
                ?: 1
            val position = sessionOverride.authorNotePosition
                ?: cardOverride.authorNotePosition
                ?: AuthorNote.IN_CHAT
            return AuthorNote(text = text, depth = depth, role = role, interval = interval, position = position)
        }

    /**
     * `{{user}}` 展开成什么。**只认生效人设的名字**；没人设时保持原来的 "User"
     * （原版未设置用户名时也是这个值，所以夹具里的旧断言不受影响）。
     */
    val effectiveUserName: String
        get() = effectivePersona?.name?.takeIf { it.isNotBlank() } ?: "User"

    // ---- 生效值（三级继承）：规则在 core/preset/EffectiveConfig.kt，这里只取用 ----
    // lazy(NONE)：copy() 会跑构造，两种写法语义都对；lazy 只对"真读过它"的实例算一次。
    // 新实例的 lazy 是全新的，所以不会拿着旧解析结果（UI 单线程，不需要锁）。
    private val effective: EffectiveConfig by lazy(LazyThreadSafetyMode.NONE) {
        resolveEffectiveConfig(
            session = sessionOverride,
            card = cardOverride,
            sampling = presetSampling,
            globals = GlobalDefaults(
                personaId = personaDefaultId,
                profileId = activeProfileId,
                presetName = activePreset,
                bubbleFontSize = bubbleFontSize,
                builtinDisabled = builtinRegexDisabled,
            ),
            texts = SamplingTexts(
                temperature = temperatureText,
                maxTokens = maxTokensText,
                topP = topPText,
                frequencyPenalty = frequencyPenaltyText,
                presencePenalty = presencePenaltyText,
            ),
        )
    }

    // 语义（null = 跟随、空集 ≠ null、maxTokens 0/负数 = 不限）都写在 core 的 EffectiveConfig 里，这里不重复。
    val effectivePersonaId: String? get() = effective.personaId
    val effectiveProfileId: String? get() = effective.profileId
    val effectivePresetName: String? get() = effective.presetName
    val effectiveReasoningEffort: String? get() = effective.reasoningEffort
    val effectiveWorldBookNames: Set<String>? get() = effective.worldBookNames
    val effectiveRegexIds: Set<String>? get() = effective.regexIds
    val effectiveBuiltinDisabled: Set<String> get() = effective.builtinDisabled
    val effectiveBubbleFontSize: Float get() = effective.bubbleFontSize
    val effectiveTemperature: Double get() = effective.temperature
    val effectiveMaxTokens: Int? get() = effective.maxTokens
    val effectiveTopP: Double? get() = effective.topP
    val effectiveFrequencyPenalty: Double? get() = effective.frequencyPenalty
    val effectivePresencePenalty: Double? get() = effective.presencePenalty

    val effectiveRegexScripts: List<RegexScript>
        get() {
            val only = effectiveRegexIds ?: return regexScripts
            return regexScripts.filter { it.name in only }
        }

    /**
     * 聊天正文**显示**用的脚本 = 用户脚本（三级过滤后）+ 没关掉的内置规则。
     *
     * 内置放在最后：用户脚本先跑，再做"清包裹标签 → 强调"。内置**不受**
     * 会话/卡级 `regexIds` 过滤（它们不是用户脚本，老会话里存的 id 集合里没有它们）。
     */
    val displayRegexScripts: List<RegexScript>
        get() = effectiveRegexScripts + BuiltinRegex.rules(effectiveBuiltinDisabled)
}
