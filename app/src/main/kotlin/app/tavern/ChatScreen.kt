package app.tavern

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import app.tavern.core.regex.InlineMarkup
import app.tavern.core.regex.RegexEngine
import app.tavern.core.regex.RegexPlacement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import app.tavern.data.log.AppLog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * 聊天页（全屏二级页）。
 *
 * 动效只做三件有信息量的事：新消息入场、流式光标、发送↔停止的按钮形变。
 *
 * 串流跟随的规矩（2026-11-24 定，晚上把"冻结"换成了阅读锚点保护）：
 * - **贴底**（场景 A）：文本长高多少就"滑"多少（动画追高度差，而不是每行跳一下）；
 * - **看更早的消息**（场景 B，第一可见项下标 > 0）：气泡在视口外长，视口本来就不动，
 *   什么都不做；
 * - **停在串流气泡自己身上**（场景 C，下标 == 0 且滚出去 > 0）：文本照常长，长高多少就把
 *   滚动偏移加多少，让读到的那一段留在原处。补偿只在真的量到高度变化时做，**手指/惯性期间
 *   一律让位**；不做冻结（用户 2026-11-24 晚明确不要"内容卡住"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val palette = LocalTavernPalette.current
    // 玻璃档要 API 33+（液态玻璃的 AGSL）；低于它只开放轻量，所以这里直接压成 false
    val glassOn = state.visualEffects.effective() == VisualEffects.Glass

    // 模型输出侧的正则脚本：原版只改显示、不改存档，所以在这里做，不碰 transcript。
    val overscroll = rememberRubberOverscroll()

    // 输入区的实测高度（像素）：列表底部让位跟着它走 —— 聚焦时输入区会向上翻倍
    var inputBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // 消息操作（用户 2026-11-25 第 11 条）：不再用长按出抽屉 —— 回复底下常驻一排小按钮，
    // 长按交给文字选择（正文在外面包了 SelectionContainer）。
    val clipboard = LocalClipboardManager.current
    var deletingIndex by remember { mutableStateOf<Int?>(null) }

    // 离场页还在组合树里（转场需要），但输入框光标与"选文字"手柄是**独立弹层**，
    // 不会跟 graphicsLayer 一起位移 —— 页面滑走了它们还钉在屏幕上（用户 2026-11-25 报的）。
    //
    // 处理：离场时收焦点，并把消息列表**整体重建一次**（key 变化）。SelectionContainer 被销毁时
    // 会连带清掉选区与手柄；不能用"给每个气泡传 selectable=false"的办法 —— 那样列表项会拿到
    // 陈旧的 false，返回后最新一条气泡一直不可选（用户 2026-11-25 报的"要划走最新气泡才能选字"）。
    val pageIsCurrent = LocalPageIsCurrent.current
    val focusManager = LocalFocusManager.current
    var selectionEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(pageIsCurrent) {
        if (!pageIsCurrent) {
            focusManager.clearFocus(force = true)
            selectionEpoch++
            AppLog.i("ui", "离场：收掉输入焦点与选区 epoch=$selectionEpoch")
        }
    }
    // 页面级确认框的控制器：本页面在 GlassDetailScaffold **之前**，直接读 CompositionLocal
    // 会拿到没有宿主的实例，确认框静默不弹（2026-11-25 真机清单的根因）。自己建一个传进去。
    val confirm = remember { ConfirmController() }

    // 列表状态提到这里：串流时要判断"是不是贴在底部"（只在底部才跟着抬画面）
    val listState = rememberLazyListState()
    // 选字诊断（2026-11-25 临时）：按下点到底落在列表 / 气泡正文的哪个矩形上，见 SelectionProbe.kt
    val listProbe = remember { ProbeBounds() }

    // 撤回会把列表削短：视口可能停在中段、下面留一片空白。这里收到一次性信号就贴底。
    // 反排列表的 index 0 就是"最新一条"（视觉最底部），所以 scrollToItem(0) 即可。
    val scrollToBottomSignal = state.scrollToBottomSignal
    LaunchedEffect(scrollToBottomSignal) {
        if (scrollToBottomSignal > 0 && state.transcript.isNotEmpty()) {
            // 用 animate 而不是瞬间跳：撤掉一大段后，滑回底部能看清"现在剩到哪"
            listState.animateScrollToItem(0)
            AppLog.i("scroll", "回到列表底部 signal=$scrollToBottomSignal")
        }
    }
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // 串流气泡永远显示最新文本：冻结已撤掉（用户 2026-11-24 晚：卡住的观感不是我要的）。
    val shownStream = state.streamingText

    // 场景 C 的判据（不能只看 isStreaming）：滚离了底部，但"第一可见项"仍是串流气泡自己。
    // reverseLayout 下这意味着气泡下沿已经沉到视口外，正在读的是它上半截 —— 只有这种情形，
    // 气泡长高才会把读到的那一段顶走。看更早的消息时下标 > 0，气泡在视口外长，视口不动。
    // 用 derivedState 而不是直接读：绘制里也要用这个判据，derivedState 在绘制时总是最新。
    val readingBubble by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 0
        }
    }
    val readingStreamBubble = readingBubble && shownStream.isNotEmpty()

    // 串流气泡的高度：量出来以后用**一条动画去追它**，追不上差多少就往下平移多少。
    // 于是每换一行是"滑"上去的，而不是"啪"地跳一行（用户 2026-11-24）。
    // 旧写法是"每次长高就把补偿加上去、再收回"，文字到得快时补偿越积越多 → 画面抽搐。
    var streamHeight by remember { mutableIntStateOf(0) }
    val smoothHeight = remember { Animatable(0f) }
    LaunchedEffect(streamHeight) {
        val target = streamHeight.toFloat()
        // 气泡刚出现的那一次不产生位移：直接对齐（否则会从半空滑下来）
        if (smoothHeight.value == 0f) smoothHeight.snapTo(target)
        else smoothHeight.animateTo(target, tween(Motion.Base, easing = Motion.Decelerate))
    }

    // ── 阅读锚点保护（场景 C）──
    //
    // reverseLayout 的滚动位置 = "第一可见项 + 它被滚过去的距离"。气泡长高 delta 时，Compose
    // 只把这条锚点钉在气泡的**下沿**，于是整屏内容被顶上去 delta（旧轮次记过这个"瞬移一行"）。
    // 贴底时用动画追上去就顺眼了；读气泡自己时不能追 —— 抬上去再滑回来就是把读到的那段拽走。
    // 所以这里把**滚动量**补上 delta：内容整体往下让 delta，气泡上沿停回原处。
    //
    // 为什么用 dispatchRawDelta 而不是 requestScrollToItem（v115，真机实测后改）：
    // 1. requestScrollToItem 内部会 `itemAnimator.reset()` —— 所有挂了 `animateItem` 的**旧气泡**
    //    每补一次就重播一次淡入（180ms），长回复里就是"旧气泡一直闪"（v114 报的）；
    // 2. 它还会取消进行中的拖动/惯性（`scroll{}` 抢 mutex），所以只能"手势期间不补" ——
    //    可那段长高照样会把内容顶走，手感就是"拖动时文字一直往上顶、不跟手"（v114 报的）。
    // dispatchRawDelta 走嵌套滚动的低层通道：不动动画器、不抢 mutex、不打断手势，只把这一笔
    // 长高顶回去 —— 手指的位移照常生效，这才是"跟手"。
    //
    // 布局与滚动仍是两趟：差量这一帧先由 translationY 抹平，等补偿落地时两边同时归零
    // （恒等于原位置）—— 所以既不是用绘制位移长期掩盖布局，也不会闪。
    var anchoredHeight by remember { mutableIntStateOf(0) }
    val streamProbe = remember { StreamProbe() }
    val anchorDiag = remember { AnchorDiag() }
    // 贴底那套"追高欠账"（`平滑差`）是**绘制量**，只在贴底时起作用。跨边界时它会凭空丢掉
    // （离开贴底 → 画面向上收一下）或凭空补上（回到贴底 → 向下沉一下），实测 8–151px。
    // 所以边界上做两件事：离开贴底把欠账折成滚动量；回到贴底把平滑高度对齐。
    var wasAtBottom by remember { mutableStateOf(atBottom) }
    LaunchedEffect(streamHeight, readingStreamBubble, state.isStreaming, atBottom) {
        val justEnteredBottom = !wasAtBottom && atBottom
        val leftBottom = wasAtBottom && !atBottom
        wasAtBottom = atBottom
        if (justEnteredBottom) {
            // 回到贴底：这笔欠账在场景 C 里已经消化过（内容本来就在正确位置），
            // 直接对齐，否则会先向下沉一下再滑上来。
            val pending = (streamHeight - smoothHeight.value).toInt()
            smoothHeight.snapTo(streamHeight.toFloat())
            anchoredHeight = streamHeight
            if (pending != 0) anchorDiag.edge("回到贴底", pending, 0)
            return@LaunchedEffect
        }
        if (atBottom) {
            // 贴底期间的基线跟着高度走；**不要**动 smoothHeight（那是跟底动画自己管的）
            anchoredHeight = streamHeight
            return@LaunchedEffect
        }
        if (leftBottom) {
            // 离开贴底：把当前欠账折成滚动量，画面上就不会"收一下"。
            // 折不完的部分留给 anchoredHeight 当桥接，下一笔补偿接着消化。
            val pending = (streamHeight - smoothHeight.value).toInt()
            if (pending > 0) {
                val moved = listState.dispatchRawDelta(pending.toFloat()).roundToInt()
                anchoredHeight = streamHeight - pending + moved
                anchorDiag.edge("离开贴底", pending, moved)
            }
        }
        // 不在场景 C，或者这会儿没在长：只把基线推平，绝不补偿
        if (!state.isStreaming || !readingStreamBubble) {
            anchoredHeight = streamHeight
            return@LaunchedEffect
        }
        val drift = streamHeight - anchoredHeight
        if (drift <= 0) {
            anchoredHeight = streamHeight
            return@LaunchedEffect
        }
        // reverseLayout 里正 delta = "往前滚 = 内容往下让"，正好抵掉长高顶上去的量
        val moved = listState.dispatchRawDelta(drift.toFloat()).roundToInt()
        if (moved <= 0) {
            // 滚不动（到边界）：这帧先由桥接顶住，基线不动，下一笔再试
            anchorDiag.blocked(drift, streamProbe.topY)
            return@LaunchedEffect
        }
        anchoredHeight += moved.coerceAtMost(drift)
        anchorDiag.compensated(moved, drift, streamHeight, streamProbe.topY, listState.isScrollInProgress)
    }

    // 滚动模式 A/B/C 的变化各记一条（只记切换，不记每帧；脚本/日志里靠它对齐上下文）
    LaunchedEffect(state.isStreaming, state.sessionId) {
        if (!state.isStreaming) return@LaunchedEffect
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            ScrollMode(
                mode = when {
                    index == 0 && offset == 0 -> ScrollMode.AT_BOTTOM
                    index == 0 -> ScrollMode.READING_BUBBLE
                    else -> ScrollMode.READING_HISTORY
                },
                index = index,
                offset = offset,
            )
        }
            .distinctUntilChanged { old, new -> old.mode == new.mode }
            .collect { mark ->
                // 平滑差：从贴底切出去时"追高度"的那点欠账。它不是 0 就说明切换那一帧
                // 内容会往上收一点点（贴底跟随的既有行为，这里先量出来，别和补偿混为一谈）。
                val pending = (streamHeight - smoothHeight.value).toInt()
                anchorDiag.mode(mark.mode, mark.index, mark.offset, streamHeight, pending, streamProbe.topY)
            }
    }

    // 新一轮串流 / 换会话：高度归零，下一轮气泡重新量；上一轮收尾时把补偿总量写进日志
    LaunchedEffect(state.isStreaming, state.sessionId) {
        if (state.isStreaming) {
            streamHeight = 0
            smoothHeight.snapTo(0f)
            anchoredHeight = 0
            anchorDiag.reset()
        } else {
            anchorDiag.finish()
        }
    }
    // 显示用的脚本 = 生效中的用户脚本 + 没关掉的内置规则（清包裹标签 / 动作强调）。
    // 只走显示：存档、导出、看请求、发给模型的提示词都是原文（用户 2026-11-25 定的方案）。
    // remember 住脚本列表：内置规则每次 getter 都会新建实例，直接当 key 会每帧重算
    val regexScripts = remember(state.builtinRegexDisabled, state.effectiveRegexScripts) {
        state.displayRegexScripts
    }
    val displayText: (String) -> String = remember(regexScripts) {
        { text ->
            if (regexScripts.isEmpty()) text
            else RegexEngine().applyAll(regexScripts, text, RegexPlacement.AiOutput)
        }
    }

    GlassDetailScaffold(
        title = state.cardName.ifBlank { "聊天" },
        subtitle = state.status,
        onBack = viewModel::closeChat,
        glass = glassOn,
        // 页面顶层（动作行、确认框）也要拿到同一个宿主实例
        confirmController = confirm,
        // 聊天页是唯一一个"内容一直在标题下面滚"的页头 —— 只有它上磨砂
        frostedHeader = true,
        // 聊天要能滚到玻璃页头下面，所以让位交给列表的 contentPadding
        contentTopInset = 0.dp,
        // 输入区画在录制之外：可以采磨砂，消息从胶囊后面滚过去
        bottomOverlay = { backdrop ->
            ChatInputBar(
                state = state,
                viewModel = viewModel,
                glass = glassOn,
                backdrop = backdrop,
                onHeightChanged = { inputBarHeightPx = it },
            )
        },
        actions = {
            var menuOpen by remember { mutableStateOf(false) }
            CapsuleIcon(icon = Icons.Filled.MoreVert, contentDescription = "更多") { menuOpen = true }
            // 导出用系统文件选择器（SAF）：不需要存储权限，用户自己挑位置
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/jsonl"),
            ) { uri ->
                if (uri != null) viewModel.exportChat(uri)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // 原来是个**不可点的占位**，现在就是「会话配置」的入口（用户 2026-11-25 第 2 条）。
                DropdownMenuItem(
                    // 只写入口名，不带模型名（用户 2026-11-25 第 5 条：太长且每会话都在变）
                    text = { Text("会话配置") },
                    onClick = {
                        menuOpen = false
                        viewModel.openSessionConfig()
                    },
                )
                DropdownMenuItem(
                    text = { Text("查看本次请求") },
                    onClick = {
                        menuOpen = false
                        viewModel.toggleRequest()
                    },
                )
                DropdownMenuItem(
                    text = { Text("导出聊天记录") },
                    onClick = {
                        menuOpen = false
                        // 文件名给个默认值：卡名 + 后缀，用户可以在选择器里改
                        exportLauncher.launch("${state.cardName.ifBlank { "chat" }}.jsonl")
                    },
                )
            }
        },
    ) { padding ->
        // 列表铺满整页（连输入区底下也算）：消息能滚到输入胶囊后面，胶囊才是真玻璃。
        // 顶部/底部让位一律用 contentPadding —— 它不裁内容，滚动时会从玻璃底下过。
        // reverseLayout：列表的"锚点"是底部，新消息天然出现在眼前，
        // 完全不需要滚动跟随——串流时也就不再有"每 60ms 跳一次"造成的整屏抽动。
        //
        // key(selectionEpoch)：离场时 epoch +1，这一整层重建 —— SelectionContainer 随旧组合销毁，
        // 选文字手柄/工具栏立刻消失（它们是窗口级弹层，不跟页面滑动）。
        key(selectionEpoch) {
        LazyColumn(
            reverseLayout = true,
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Space.m)
                // 位移只落在绘制上，两者都是"追上布局"用的短命差量：
                // - 贴底：布局已经跑到前面去了，差多少就下移多少，再平滑追上去；
                // - 读气泡自己：长高的差量先在这里顶住，等上面那笔滚动补偿落地，两边同时归零。
                //
                // ⚠️ **只有串流期间才允许有这笔位移**：这两个差量都是"布局与滚动还没对齐"的中间态，
                // 串流一结束它们就该归零。留着非零残值等于让内容和命中区长期错位 ——
                // 用户 2026-11-25 报的"最新气泡长按没反应、滑一下才好"就是这一类症状，
                // 所以非串流时一律写死 0（真机日志 tag=sel 里能看到这行的实时值）。
                .graphicsLayer {
                    translationY = if (!state.isStreaming) {
                        0f
                    } else if (atBottom) {
                        streamHeight - smoothHeight.value
                    } else if (readingBubble) {
                        (streamHeight - anchoredHeight).toFloat().coerceAtLeast(0f)
                    } else {
                        0f
                    }
                }
                .rubberOverscroll(overscroll)
                .trackProbe(listProbe)
                .selectionProbe(
                    tag = "列表",
                    ref = listProbe,
                    extra = {
                        "atBottom=$atBottom 下标=${listState.firstVisibleItemIndex} " +
                            "偏移=${listState.firstVisibleItemScrollOffset} " +
                            "轴移=${streamHeight - smoothHeight.value} 输入区高=$inputBarHeightPx"
                    },
                ),
            contentPadding = PaddingValues(
                // 页头铺到状态栏后面，列表顶部让位要带上状态栏高度
                // 8 → 16：磨砂页头下面再多留一点，首条消息不贴着头（用户 2026-11-24）
                top = DetailHeaderHeight +
                    statusBarTop() +
                    Space.l,
                // 输入区高度 + 键盘高度：键盘弹出时列表底部让位跟着长，被挡的消息才拖得出来。
                // 注意这里不能只写固定值，否则键盘一开最后几条就永远在键盘下面（用户 2026-11-23 报）。
                bottom = padding.calculateBottomPadding() +
                    if (inputBarHeightPx > 0) {
                        with(density) { inputBarHeightPx.toDp() }
                    } else {
                        ChatInputAreaHeight
                    } +
                    // 再让 12dp：输入胶囊的投影会往上铺一点，最后一条消息底下那排图标
                    // 会"少一截"贴在胶囊边上（用户 2026-11-25 第 5 条）
                    12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
                if (shownStream.isNotEmpty()) {
                    item {
                        MessageRow(
                            message = ChatMessage(ChatRole.Assistant, shownStream),
                            style = state.effectiveMessageStyle,
                            showCursor = true,
                            displayText = displayText,
                            probeTag = "气泡[串流]",
                            // 量气泡高度（贴底跟随与锚点补偿都靠它），顺带记一下上沿在窗口里的 Y：
                            // 日志里的"顶Y"就是它，用来验证读到的那一段有没有被顶走。
                            modifier = Modifier
                                .onSizeChanged { streamHeight = it.height }
                                .onGloballyPositioned { streamProbe.topY = it.positionInWindow().y.toInt() },
                        )
                    }
                }
                if (state.transcript.isEmpty() && state.streamingText.isEmpty()) {
                    item { EmptyHint("还没有消息。发一句开始；点右上「请求」可以看到实际发出去的提示词。") }
                }
                // 开场白翻页（用户 2026-11-25 第 3 条）：只在"还没说过话 + 不止一条"时出现。
                // 箭头贴在第一屏最上面那条气泡的**上沿**，和气泡同一个 item —— 翻页时一起动，不会脱节。
                val canSwitchGreeting = state.greetings.size > 1 &&
                    state.transcript.none { it.role == ChatRole.User }
                val shownGreeting = greetingIndexOf(
                    state.greetings,
                    state.transcript.firstOrNull()?.content,
                )
                itemsIndexed(state.transcript.asReversed()) { index, message ->
                    // 只做淡入/淡出，不做位移动画：串流时列表每帧都在长高，
                    // 位移动画会不断重放，整屏看起来在闪。
                    //
                    // 动作入口从"长按出抽屉"改成了气泡底下一排按钮（用户 2026-11-25 第 11 条）：
                    // 长按现在交给文字选择，按钮常驻可见，不用猜哪里能按。
                    val realIndex = state.transcript.lastIndex - index
                    val isLast = realIndex == state.transcript.lastIndex
                    val isAssistant = message.role == ChatRole.Assistant
                    val itemAnim = Modifier
                        .animateItem(
                            fadeInSpec = tween(Motion.Base, easing = Motion.Decelerate),
                            placementSpec = null,
                            fadeOutSpec = tween(Motion.Fast),
                        )
                    val isGreetingSlot = canSwitchGreeting && index == state.transcript.lastIndex
                    // 最后一条助手消息有多个 swipe 时，气泡下面挂一条 ‹ 2/3 ›（和开场白那条同款）
                    val showSwipeBar = !isGreetingSlot && isLast && isAssistant &&
                        state.lastSwipes.size > 1
                    Column(modifier = itemAnim.fillMaxWidth()) {
                        MessageRow(
                            message = message,
                            style = state.effectiveMessageStyle,
                            displayText = displayText,
                            probeTag = "气泡[i=$index ${if (isAssistant) "助手" else "用户"}]",
                        )
                        if (showSwipeBar) {
                            Spacer(Modifier.height(Space.xs))
                            GreetingSwitchBar(
                                current = state.lastSwipeId.coerceIn(
                                    0,
                                    (state.lastSwipes.size - 1).coerceAtLeast(0),
                                ),
                                total = state.lastSwipes.size,
                                onSelect = viewModel::selectSwipe,
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                        // 开场白翻页：贴在气泡**下沿右侧**（用户 2026-11-25 第 8 条）。
                        // 原来贴在上沿：反排列表的锚点是底部，换一条时气泡往上长，
                        // 上沿那条控件就跟着跳 —— 挪到底部它就不动了。
                        if (isGreetingSlot) {
                            Spacer(Modifier.height(Space.xs))
                            GreetingSwitchBar(
                                current = if (shownGreeting >= 0) shownGreeting else 0,
                                total = state.greetings.size,
                                onSelect = viewModel::selectGreeting,
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                        // 开场白不是"一条回复"，底下不挂动作（用户 2026-11-25 第 7 条）
                        if (!isGreetingSlot) {
                            MessageActionRow(
                                isUser = !isAssistant,
                                canRegenerate = isAssistant && isLast,
                                canContinue = isAssistant && isLast,
                                onRewind = { viewModel.rewindToMessage(realIndex) },
                                onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                                onRegenerate = viewModel::regenerateLast,
                                onContinue = viewModel::continueLast,
                                onDelete = { deletingIndex = realIndex },
                                modifier = Modifier.align(
                                    if (isAssistant) Alignment.Start else Alignment.End,
                                ),
                            )
                        }
                    }
                }
        }
        }
    }

    // 删除这条：走统一的玻璃确认框（和删会话/删卡同一个材质与措辞规则）
    deletingIndex?.let { index ->
        ConfirmDeleteDialog(
            controller = confirm,
            title = "删除这条消息？",
            message = "只删这一条，不能撤销。",
            onConfirm = {
                deletingIndex = null
                viewModel.deleteMessageAt(index)
            },
            onDismiss = { deletingIndex = null },
        )
    }

    // 请求面板：全屏弹层，可选中复制。以前内嵌在消息上方，白占一块地方。
    if (state.showRequest) {
        RequestDialog(state = state, onClose = viewModel::toggleRequest)
    }
}

/** 输入区总高：**首帧的兜底值**，实测到真实高度之后就用实测的（聚焦会翻倍）。 */
private val ChatInputAreaHeight: Dp = 80.dp

/** 输入区里圆钮的直径：发送与模型两个钮同尺寸、同材质，才是"对称"。 */
private val ChatBarButtonSize: Dp = 40.dp

/**
 * 聊天输入区：一个玻璃输入胶囊，发送钮**装在胶囊里**（用户 2026-11-25 第 5 条）。
 *
 * 结构（同一天第 6–8 条定的）：
 * - **收起 = 单行**：文字 + 右侧发送钮，同一个胶囊；发送钮不再独立挂在胶囊外面；
 * - **聚焦 = 向上加一行**：上行只放输入，下行左模型、右发送 —— 相当于高度翻倍，
 *   真正写字的那一行永远在最上面，不会被下面的按钮压住；
 * - 文字超过一行继续长高（最多 5 行），发送后收起。
 *
 * 为什么不把模型钮也放在收起态：收起态只有一行，两个钮挤在右边会把手写区压窄；
 * 而且用户明确要的是"展开后左下角出现模型按钮，与发送按钮对称"。
 */
@Composable
private fun ChatInputBar(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    glass: Boolean,
    backdrop: GlassBackdrop,
    onHeightChanged: (Int) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // 键盘收起后输入框会缩回一行（但焦点还在），再点它要能**主动把键盘叫回来**
    val keyboard = LocalSoftwareKeyboardController.current
    val sheet = LocalSheetController.current
    var focused by remember { mutableStateOf(false) }
    // 键盘是否真的在：只靠 focus 不行 —— 按返回键收起键盘后输入框仍然有焦点，
    // 于是它会一直停在两行的高度（用户 2026-11-25 第 9 条）。
    // 规则：有焦点 且（键盘在 或 已经有字）才展开；键盘收起且没字就回到一行（带动画）。
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val expanded = focused && (imeVisible || state.input.isNotEmpty())
    // 真机上"键盘收了但输入框没缩"这类问题只能靠日志判断：展开条件三个量都记上
    LaunchedEffect(expanded, imeVisible) {
        AppLog.i("ui", "输入区展开=$expanded 键盘=$imeVisible 字数=${state.input.length}")
    }
    // 展开/收起现在是逐帧动画：高度日志按"变了这么多才记一行"节流，
    // 否则一次展开刷十几行，把真正有用的日志挤掉。
    var lastLoggedHeight by remember { mutableIntStateOf(0) }
    // 生效档案：会话指定 → 卡指定 → 全局默认（三级继承的解析在 UiState 里）
    val currentProfile = state.profiles.firstOrNull { it.id == state.effectiveProfileId }
    // 选字诊断（临时）：如果按下点落在输入区这块矩形里，日志会明说是"输入区"而不是气泡
    val barProbe = remember { ProbeBounds() }

    val openModelSheet = {
        // 抽屉从屏幕底部上来，键盘会把它盖掉一半：先收键盘
        focusManager.clearFocus()
        showModelSheet(sheet, viewModel)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .trackProbe(barProbe)
            .selectionProbe(
                tag = "输入区",
                ref = barProbe,
                extra = { "展开=$expanded 键盘=$imeVisible" },
            )
            .padding(horizontal = Space.m, vertical = Space.s)
            // 列表底部让位跟着**实测高度**走：聚焦翻倍后若还用常量 80dp，最后一条会被盖住。
            // 高度也记一行：真机上"最后一条被盖住/列表跳"这类问题，第一件事就是看这个数
            .onSizeChanged {
                onHeightChanged(it.height)
                if (kotlin.math.abs(it.height - lastLoggedHeight) >= 40) {
                    lastLoggedHeight = it.height
                    AppLog.i("ui", "输入区高度=${it.height}px")
                }
            },
        verticalAlignment = Alignment.Bottom,
    ) {
        GlassSurface(
            backdrop = backdrop,
            glass = glass,
            shape = RoundedCornerShape(Radius.pill),
            // 输入区压在消息正文上，投影用半强度（用户 2026-11-24：阴影存在感有点强）
            shadowScale = 0.5f,
            modifier = Modifier.weight(1f),
        ) {
            // 布局说明（2026-11-25 第四轮）：膨胀出来的**那一行只负责高度**，
            // 按钮全部是浮层。之前把发送钮放在会逐帧变矮的行里 —— expand/shrink 会裁剪内容，
            // 变形过程中它就被切掉一块（用户报的"变形时对发送按钮有遮挡"）。
            // 现在发送钮钉在胶囊右下：胶囊向上长、下沿不动，所以它在屏幕上几乎不动，也不会被裁。
            Box(Modifier.fillMaxWidth()) {
                val extraHeight by animateDpAsState(
                    targetValue = if (expanded) 46.dp else 0.dp,
                    animationSpec = if (expanded) {
                        tween(Motion.InputExpand, easing = Motion.Decelerate)
                    } else {
                        tween(Motion.InputCollapse, easing = Motion.Standard)
                    },
                    label = "input-extra-height",
                )
                // 收起时文字右边给发送钮让位；展开后文字独占上行
                val sendReserve by animateDpAsState(
                    targetValue = if (expanded) Space.s else 46.dp,
                    animationSpec = tween(Motion.InputExpand, easing = Motion.Decelerate),
                    label = "input-send-reserve",
                )
                // 模型钮：展开后从 0 淡入 + 轻微放大；它也在动画行之外，不会被裁
                val modelAlpha by animateFloatAsState(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = Motion.Fast + 60,
                        delayMillis = if (expanded) Motion.InputExpand - 180 else 0,
                    ),
                    label = "input-model-alpha",
                )
                val modelScale by animateFloatAsState(
                    targetValue = if (expanded) 1f else 0.86f,
                    animationSpec = tween(Motion.InputCollapse, easing = Motion.Decelerate),
                    label = "input-model-scale",
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 兜底高度：发送钮要 40 + 6 的贴边留白，不能比它矮
                        .heightIn(min = 48.dp)
                        // 点胶囊空白处也是"点输入框"（不然只有文字那一小条能点）
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            focusRequester.requestFocus()
                            // 焦点还在、但键盘是被用户收起来的：requestFocus 不会重新弹键盘
                            keyboard?.show()
                        },
                ) {
                    BasicTextField(
                        value = state.input,
                        onValueChange = viewModel::onInputChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // 最多 5 行：再多就让文字在自己那一行里滚（输入区不能无限长）
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = Space.l,
                                end = sendReserve,
                                top = 14.dp,
                                bottom = 14.dp,
                            )
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused },
                        decorationBox = { inner ->
                            if (state.input.isEmpty()) {
                                Text(
                                    "说点什么",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                    // 展开多出来的那一行：不装任何东西，只让胶囊长高
                    Spacer(Modifier.height(extraHeight))
                }

                // 发送 / 停止：常驻右下，**不参与高度动画**
                SendStopButton(
                    streaming = state.isStreaming,
                    glass = glass,
                    backdrop = backdrop,
                    onSend = {
                        viewModel.send()
                        focusManager.clearFocus()
                    },
                    onStop = viewModel::stop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 6.dp),
                )

                // 左下模型钮：展开后出现（淡入 + 放大），位置固定
                if (modelAlpha > 0.01f) {
                    ModelButton(
                        badge = modelBadge(currentProfile?.name),
                        glass = glass,
                        backdrop = backdrop,
                        onClick = openModelSheet,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 6.dp, bottom = 6.dp)
                            .graphicsLayer {
                                alpha = modelAlpha
                                scaleX = modelScale
                                scaleY = modelScale
                            },
                    )
                }
            }
        }
    }
}

/**
 * 发送 ↔ 停止：同一个按钮位只换形态，不换位置。
 *
 * 从"独立挂在胶囊右边"搬进胶囊以后，尺寸跟着 [ChatBarButtonSize]（40dp）：
 * 44dp 的钮塞进一行 44dp 的胶囊会顶满，边缘就没有玻璃厚度了。
 */
@Composable
private fun SendStopButton(
    streaming: Boolean,
    glass: Boolean,
    backdrop: GlassBackdrop,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 系统「移除动画」：发送↔停止只淡化，不做缩放
    val reduceMotion = rememberReduceMotion()
    val press = remember { MutableInteractionSource() }
    AnimatedContent(
        targetState = streaming,
        modifier = modifier.size(ChatBarButtonSize),
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(Motion.Fast)) togetherWith fadeOut(tween(Motion.Fast))
            } else {
                (fadeIn(tween(Motion.Fast)) + scaleIn(tween(Motion.Fast), initialScale = 0.8f)) togetherWith
                    (fadeOut(tween(Motion.Fast)) + scaleOut(tween(Motion.Fast), targetScale = 0.8f))
            }
        },
        label = "send-stop",
    ) { isStreaming ->
        GlassSurface(
            backdrop = backdrop,
            glass = glass,
            shape = CircleShape,
            modifier = Modifier.fillMaxSize(),
            // 与输入胶囊同一个强度，否则一行里两个影子深浅不一
            shadowScale = 0.5f,
            interactionSource = press,
            // 液态玻璃：整颗钮就是一块小透镜，背后的消息会在边缘被弯进来
            liquid = LiquidGlassSpec.Default,
            // 发送：淡紫磨砂。浓度是**高光能不能被看见**的决定因素，不是审美：
            // 页面底色是 (250)；内部 16% 时亮到 (232,229,244)、22% 时 (214,208,234) ——
            // 高光最多叠到 250 左右，还是压不过底色，两次都"看不出来"（2026-11-23 用户连报）。
            // 32% 时内部 ≈ (202,192,229)，亮带 (234,229,244) 高出 30 级，才真的读成"边缘受光"。
            // 停止态仍是原来的浅色面。
            fillColor = if (isStreaming) null else MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            // 高光环走**纯白**（默认值）。用户指出得对：这颗钮外面包着一圈投影，
            // 边缘的"环境色"是被压暗的灰蓝(≈240)，不是页面底色(250) —— 纯白细线在那里就是亮的。
            // 之前"白高光压不过底色"的判断错在拿页面底色当了背景。
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = press,
                        indication = null,
                        onClick = if (isStreaming) onStop else onSend,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isStreaming) {
                    // 停止用一小块方角，和"发送"的形状区分开，不依赖额外图标
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        // 图标也是玻璃：半透明，让钮里那层磨砂透上来（纯色会像印上去的）
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        // 纸飞机是三角形，几何居中会显得偏左；往右挪一点才是视觉居中
                        modifier = Modifier.size(20.dp).offset(x = 1.dp),
                    )
                }
            }
        }
    }
}

/**
 * 左下角的模型钮（与发送钮对称）：显示当前档案的首字，点开"模型"抽屉。
 *
 * 为什么用首字而不是图标：图标库里没有"模型"这种语义的图标（`material-icons-core` 只有
 * 齿轮/星星这类），而且**当前用的是哪个模型**这件事本身就该看得见 —— 首字比一个抽象图标多带信息。
 */
@Composable
private fun ModelButton(
    badge: String,
    glass: Boolean,
    backdrop: GlassBackdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val press = remember { MutableInteractionSource() }
    GlassSurface(
        backdrop = backdrop,
        glass = glass,
        shape = CircleShape,
        modifier = modifier.size(ChatBarButtonSize),
        shadowScale = 0.5f,
        interactionSource = press,
        liquid = LiquidGlassSpec.Default,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = press, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            )
        }
    }
}

/** 档案名的首字（ASCII 取大写字母，中文取第一个字）；没有档案时给一个"模"。 */
private fun modelBadge(name: String?): String {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return "模"
    val first = text.first()
    return if (first.code < 128) first.uppercase() else first.toString()
}

/**
 * 把正则输出里的内联标签（`<i>` / `<b>` / `<br>`）变成带样式的文本。
 *
 * 只认 `core/regex` 的 [InlineMarkup] 支持的那一小撮：不做 HTML 渲染，
 * 用户脚本里其他标签照旧按正文显示。
 */
private fun markupText(text: String): AnnotatedString = buildAnnotatedString {
    for (run in InlineMarkup.parse(text)) {
        if (run.lineBreak) {
            append('\n')
            continue
        }
        val start = length
        append(run.text)
        if (run.italic || run.bold) {
            addStyle(
                SpanStyle(
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                ),
                start,
                length,
            )
        }
    }
}

/**
 * 消息渲染的唯一入口：样式切换只在这里分叉。
 * 气泡是默认样式，整行卡片保留为可选项（设置 → 外观里能切）。
 */
@Composable
private fun MessageRow(
    message: ChatMessage,
    style: MessageStyle,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
    displayText: (String) -> String = { it },
    /** 选字诊断用：日志里认得出是哪一条（见 SelectionProbe.kt）。 */
    probeTag: String = "",
) {
    when (style) {
        MessageStyle.Bubble -> BubbleRow(message, modifier, showCursor, displayText, probeTag)
        MessageStyle.Card -> CardRow(message, modifier, showCursor, displayText)
    }
}

/**
 * 一条消息底下那排动作（用户 2026-11-25 第 11 条：长按改成"选文字"，操作改成按钮）。
 * 第 6 条又要求**用图标、不写文字** —— 这里是图标版。
 *
 * 用户气泡：撤回 / 复制 / 删除；
 * 助手气泡：复制 / 重新生成 / 继续 / 删除。后两个只对最后一条助手消息出现。
 *
 * **没有「编辑」**（用户 2026-11-25：撤回已经能改完重发，编辑单条没必要）。
 * 图标来源：`Refresh / Delete / ArrowForward` 用 `material-icons-core`
 * （`-extended` 多 10MB，不值得）；「复制」「撤回」core 里没有，用 Canvas 画两个简单字形。
 */
@Composable
private fun MessageActionRow(
    isUser: Boolean,
    canRegenerate: Boolean,
    canContinue: Boolean,
    onRewind: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 不 fillMaxWidth：整行要按在气泡哪一侧（调用方用 Column 的 align 定位）
    Row(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isUser) {
            MessageActionIcon("撤回", onRewind) { tint -> UndoGlyph(tint) }
            MessageActionIcon("复制", onCopy) { tint -> CopyGlyph(tint) }
            MessageActionIcon("删除", onDelete) { tint ->
                Icon(Icons.Filled.Delete, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
        } else {
            MessageActionIcon("复制", onCopy) { tint -> CopyGlyph(tint) }
            if (canRegenerate) {
                MessageActionIcon("重新生成", onRegenerate) { tint ->
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
            }
            if (canContinue) {
                MessageActionIcon("继续", onContinue) { tint ->
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            MessageActionIcon("删除", onDelete) { tint ->
                Icon(Icons.Filled.Delete, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 一个 32dp 的图标按钮：图标本身 18dp，按下时从 62% 提到满色。 */
@Composable
private fun MessageActionIcon(
    contentDescription: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (press.collectIsPressedAsState().value) 1f else 0.62f,
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(tint)
    }
}

/** 「复制」字形：两张错开的圆角纸（core 里没有 ContentCopy）。 */
@Composable
private fun CopyGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val stroke = Stroke(width = w * 0.09f)
        val radius = CornerRadius(w * 0.16f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.30f, w * 0.10f),
            size = Size(w * 0.56f, w * 0.62f),
            cornerRadius = radius,
            style = stroke,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.14f, w * 0.28f),
            size = Size(w * 0.56f, w * 0.62f),
            cornerRadius = radius,
            style = stroke,
        )
    }
}

/** 「撤回」字形：一条从左下绕上去的回头箭头（core 里没有 Undo）。 */
@Composable
private fun UndoGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val stroke = Stroke(width = w * 0.10f, cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(w * 0.82f, w * 0.78f)
            cubicTo(w * 0.96f, w * 0.30f, w * 0.62f, w * 0.10f, w * 0.30f, w * 0.24f)
        }
        drawPath(path, tint, style = stroke)
        val head = Path().apply {
            moveTo(w * 0.30f, w * 0.24f)
            lineTo(w * 0.48f, w * 0.10f)
            lineTo(w * 0.48f, w * 0.38f)
            close()
        }
        drawPath(head, tint)
    }
}

@Composable
private fun BubbleRow(
    message: ChatMessage,
    modifier: Modifier,
    showCursor: Boolean,
    displayText: (String) -> String,
    probeTag: String = "",
) {
    val palette = LocalTavernPalette.current
    val isUser = message.role == ChatRole.User
    // 选字诊断（临时）：气泡矩形 + 正文（Text）矩形分别记下来，按下时一起打一行日志。
    // 正文的矩形比气泡小一圈（气泡有 12dp/10dp 内边距），"按到气泡但没按到正文"就是选不中的死法之一。
    val bubbleProbe = remember { ProbeBounds() }
    val textProbe = remember { ProbeBounds() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .trackProbe(bubbleProbe)
            .selectionProbe(
                tag = probeTag.ifEmpty { "气泡" },
                ref = bubbleProbe,
                extra = {
                    val r = textProbe.inWindow
                    "正文窗口=(${r.left.toInt()},${r.top.toInt()})-(${r.right.toInt()},${r.bottom.toInt()})"
                },
            ),
    ) {
        if (isUser) Spacer(Modifier.weight(0.14f))
        Box(
            modifier = Modifier
                .weight(0.86f)
                .wrapContentWidth(if (isUser) Alignment.End else Alignment.Start),
        ) {
            Surface(
                color = if (isUser) palette.userBubble else MaterialTheme.colorScheme.surface,
                contentColor = if (isUser) palette.onUserBubble else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(
                    topStart = Radius.bubble,
                    topEnd = Radius.bubble,
                    bottomStart = if (isUser) Radius.bubble else 5.dp,
                    bottomEnd = if (isUser) 5.dp else Radius.bubble,
                ),
                border = if (isUser) null else BorderStroke(1.dp, palette.cardBorder),
            ) {
                Row(modifier = Modifier.padding(horizontal = Space.m, vertical = 10.dp)) {
                    // 长按气泡 = 选文字（用户 2026-11-25 第 11 条）：操作都在底下那排按钮里。
                    SelectionContainer {
                        Text(
                            markupText(displayText(message.content)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.trackProbe(textProbe),
                        )
                    }
                    if (showCursor) StreamingCursor()
                }
            }
        }
    }
}

/** 整行卡片样式（可选项）：信息密度低，适合长段落阅读。 */
@Composable
private fun CardRow(
    message: ChatMessage,
    modifier: Modifier,
    showCursor: Boolean,
    displayText: (String) -> String,
) {
    val palette = LocalTavernPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, palette.cardBorder),
    ) {
        Column(modifier = Modifier.padding(Space.m)) {
            Text(
                text = if (message.role == ChatRole.User) "我" else "角色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                SelectionContainer {
                    Text(
                        markupText(displayText(message.content)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (showCursor) StreamingCursor()
            }
        }
    }
}

/** 流式输出时的光标：慢速闪烁，告诉用户"还在写"。 */
@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor-alpha",
    )
    Text(
        "▍",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.alpha(alpha),
    )
}

/**
 * 「本次请求」全屏弹层：把实际发出去的 messages 原样列出来，可全选复制。
 *
 * 这是排查兼容性最常用的工具——卡有没有注入、世界书有没有进来，一眼能看出。
 * 放在弹层里而不是嵌在消息区，是因为它只在排查时需要，不该常驻占版面。
 */
@Composable
private fun RequestDialog(state: ChatViewModel.UiState, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = state.requestPreview.ifEmpty { "（还没发过请求）" }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(start = Space.s)) {
                        Text("本次请求", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "共 ${text.count { it == '\n' } + 1} 行 · ${text.length} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Text("复制")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }

                HorizontalDivider(color = LocalTavernPalette.current.divider)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Space.l),
                ) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 串流气泡上沿在窗口里的 Y：只给日志用，**故意不做成 state** ——
 * 它滚动时每帧都变，进 snapshot 只会给重组添乱。
 */
private class StreamProbe {
    var topY: Int = 0
}

/** 一次读数里的滚动模式：A 贴底 / B 看更早的消息 / C 读串流气泡自己。 */
private data class ScrollMode(val mode: String, val index: Int, val offset: Int) {
    companion object {
        const val AT_BOTTOM = "A"
        const val READING_HISTORY = "B"
        const val READING_BUBBLE = "C"
    }
}

/**
 * 阅读锚点保护的诊断日志（`tag=scroll`）。
 *
 * 为什么只记三类事件：一次长回复会让气泡长高几十上百次，每次都记一条会把 800 条的环形缓冲
 * 冲干净（那里面还有启动和请求的线索）。所以模式切换（罕见）原样记，补偿与跳过都按窗口合并。
 *
 * 字段（对照 [ChatScreen] 里的阅读锚点保护）：
 * - `+Npx`：这一笔实际滚出去的像素（= 被顶掉的量）；
 * - `drift`：这一笔要补的量 = 气泡这次长高多少（两者不等 = 只滚出去一部分）；
 * - `气泡高`：补偿后的气泡高度；`拖动中` = 补的时候手指正按着（正常，且必须照补）；
 * - `顶Y`：气泡上沿在窗口里的 Y —— **这是验收证据**，补偿有效时它应当基本不变。
 */
private class AnchorDiag {
    private var lastMode: String? = null
    private var lastAt = 0L
    private var mergedCount = 0
    private var mergedDrift = 0

    // 这一轮串流的累计量（收尾时打一行，方便把一次回复和它的补偿对上号）
    private var totalCount = 0
    private var totalDrift = 0

    fun reset() {
        lastMode = null
        lastAt = 0L
        mergedCount = 0
        mergedDrift = 0
        totalCount = 0
        totalDrift = 0
    }

    /** 一轮串流收尾：把补偿总量记一行（没有补偿就不记，别占缓冲）。 */
    fun finish() {
        if (totalCount > 0) {
            AppLog.i("scroll", "串流结束 补偿 $totalCount 笔 / 共 ${totalDrift}px")
        }
        reset()
    }

    fun mode(mode: String, index: Int, offset: Int, height: Int, pending: Int, topY: Int) {
        if (mode == lastMode) return
        lastMode = mode
        AppLog.i("scroll", "模式=$mode idx=$index off=$offset 气泡高=$height 平滑差=${pending}px 顶Y=$topY")
        mergedCount = 0
        mergedDrift = 0
    }

    fun compensated(moved: Int, drift: Int, height: Int, topY: Int, scrolling: Boolean) {
        totalCount++
        totalDrift += moved
        val now = System.currentTimeMillis()
        if (now - lastAt < 300L) {
            mergedCount++
            mergedDrift += moved
            return
        }
        lastAt = now
        val extra = if (mergedCount > 0) " 合并=$mergedCount 笔/${mergedDrift}px" else ""
        val drag = if (scrolling) " 拖动中" else ""
        AppLog.i("scroll", "补偿 +${moved}px(drift=$drift) 气泡高=$height 顶Y=$topY$drag$extra")
        mergedCount = 0
        mergedDrift = 0
    }

    /** 跨贴底边界：把"追高欠账"折成滚动（离开）或直接对齐（回到）。 */
    fun edge(label: String, pending: Int, moved: Int) {
        AppLog.i("scroll", "边界 $label 欠账=${pending}px 折成滚动=${moved}px")
    }

    /** 补偿没滚出去（到边界）：桥接先顶住，基线不动，下一笔再试。 */
    fun blocked(drift: Int, topY: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAt < 500L) return
        lastAt = now
        AppLog.i("scroll", "补偿失败 reason=roll drift=${drift}px 顶Y=$topY")
    }
}
