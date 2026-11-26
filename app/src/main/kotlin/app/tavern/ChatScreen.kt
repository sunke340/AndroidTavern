package app.tavern

import android.os.SystemClock
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
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import app.tavern.data.log.AppLog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val palette = LocalTavernPalette.current
    // 玻璃档要 API 33+（液态玻璃的 AGSL）；低于它只开放轻量，所以这里直接压成 false
    val glassOn = state.visualEffects.effective() == VisualEffects.Glass

    // 模型输出侧的正则脚本：原版只改显示、不改存档，所以在这里做，不碰 transcript。
    val overscroll = rememberRubberOverscroll()
    // 减动效统一入口（系统「移除动画」）：重新生成的收缩在那一档退成瞬切。
    val reduceMotion = rememberReduceMotion()

    // 输入区的实测高度（像素）：列表底部让位跟着它走 —— 聚焦时输入区会向上翻倍
    var inputBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // 抽屉开合期间把**列表底部让位**冻住（用户 2026-11-25："抽屉期间列表还是动"）。
    // 会动的有两个来源，缺一个都还会跳：① 抽屉一开要先收键盘，IME 让位整块消失；
    // ② 输入区可能改高。这两笔都直接进列表的 contentPadding.bottom，而反排列表锚在底部
    // → 背后气泡整片往下掉（真机日志：展开 301px → 198px → 键盘=false）。
    // 冻的是"打开那一刻的最终值"，收起后等键盘落定再解冻（此时实时值已回到同一水平）。
    // 注意：宿主实例只能在骨架**内部**读（下面的 content lambda 里）。页面顶层读 `Local` 拿到的是
    // 没有宿主的默认实例，它的 `spec` 永远是 null —— 这就是"抽屉开了但列表照样动"的坑。
    var frozenListBottom by remember { mutableStateOf<Dp?>(null) }

    // 消息操作（用户 2026-11-25 第 11 条）：不再用长按出抽屉 —— 回复底下常驻一排小按钮，
    // 长按交给文字选择（正文在外面包了 SelectionContainer）。
    val clipboard = LocalClipboardManager.current
    // 删除确认：记的是**行身份**（`uiKey`），不是下标 —— 下标会随增删漂
    var deletingKey by remember { mutableStateOf<String?>(null) }

    // 离场页还在组合树里（转场需要），但输入框光标与"选文字"手柄是**独立弹层**，
    // 不会跟 graphicsLayer 一起位移 —— 页面滑走了它们还钉在屏幕上（用户 2026-11-25 报的）。
    //
    // 处理：离场时收焦点，并把消息列表**整体重建一次**（key 变化）。SelectionContainer 被销毁时
    // 会连带清掉选区与手柄；不能用"给每个气泡传 selectable=false"的办法 —— 那样列表项会拿到
    // 陈旧的 false，返回后最新一条气泡一直不可选（用户 2026-11-25 报的"要划走最新气泡才能选字"）。
    val pageIsCurrent = LocalPageIsCurrent.current
    val focusManager = LocalFocusManager.current
    var selectionEpoch by remember { mutableIntStateOf(0) }
    // 重建会连带重播每个气泡的 `animateItem` 淡入 —— 页面正滑走时那一下就是"闪"（用户 2026-11-25）。
    // 所以离场这一帧起关掉淡入，回来时再打开（见 itemsIndexed 的 itemAnim）。
    var suppressItemFade by remember { mutableStateOf(false) }
    LaunchedEffect(pageIsCurrent) {
        if (pageIsCurrent) {
            suppressItemFade = false
        } else {
            focusManager.clearFocus(force = true)
            suppressItemFade = true
            selectionEpoch++
            AppLog.i("ui", "离场：收掉输入焦点与选区 epoch=$selectionEpoch 关淡入")
        }
    }
    // 页面级确认框的控制器：本页面在 GlassDetailScaffold **之前**，直接读 CompositionLocal
    // 会拿到没有宿主的实例，确认框静默不弹（2026-11-25 真机清单的根因）。自己建一个传进去。
    val confirm = remember { ConfirmController() }

    // 列表状态提到这里：串流时要判断"是不是贴在底部"（只在底部才跟着抬画面）
    val listState = rememberLazyListState()

    // 串流刚结束的那一帧：落库出来的气泡**就是**刚才那个流式气泡。它若走淡入，
    // 观感是"先消失再淡出来"。而且这个抑制要**保持到下一轮流开始** ——
    // 只压一帧的话，下一帧 itemAnim 又被装上，动画器会把同一颗气泡当成"新出现"再淡入一次
    // （用户 2026-11-26：修过一次，停止时还在闪）。
    var sawStreaming by remember { mutableStateOf(state.isStreaming) }
    var suppressNewestFade by remember { mutableStateOf(false) }
    val justSettled = sawStreaming && !state.isStreaming
    SideEffect {
        if (justSettled) suppressNewestFade = true
        if (state.isStreaming) suppressNewestFade = false
        sawStreaming = state.isStreaming
    }

    // 撤回会把列表削短：视口可能停在中段、下面留一片空白。这里收到一次性信号就贴底。
    // 反排列表的 index 0 就是"最新一条"（视觉最底部），所以 scrollToItem(0) 即可。
    val scrollToBottomSignal = state.scrollToBottomSignal
    LaunchedEffect(scrollToBottomSignal) {
        if (scrollToBottomSignal > 0 && state.rows.isNotEmpty()) {
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

    // 新消息进列表：列表用了稳定 key，插到头部时会**锚住原来那条** —— 新气泡于是停在视口外，
    // 观感就是"掉到输入框下面、不会自动滚到底部"（用户 2026-11-26 报的）。
    // 所以贴底时显式回 index 0；自己发的那条无论如何都回底（发了就该看见），
    // 而读历史时到达的回复不动阅读位置。
    // 新一轮 / 新消息进列表：列表用了稳定 key，插到头部时会**锚住原来那条**，新气泡于是停在
    // 视口外（"掉到输入框下面"）。所以由我们自己回底，并且分档：
    //   - 贴底附近（第一可见项 ≤ 2 且没滚进气泡）→ **瞬时**对齐：距离只有一行，动画反而多一次倒手；
    //   - 正在读更早的消息 → **动画**滑回去（用户按了发送/继续/重新生成，就该看见结果）。
    // 判据用"第一可见项 ≤ 2 && offset == 0"而不是"插入前 atBottom"：插入本身会把 atBottom 翻成
    // false，而这两个数在"插入前"与"插入后"两种时序下结论一致（插入在最前面的只有 1–2 行）。
    LaunchedEffect(state.rows.size, state.inflight?.roundId) {
        val mine = state.rows.lastOrNull()?.role == ChatRole.User
        val roundStarted = state.inflight != null
        if (!mine && !roundStarted && !atBottom) return@LaunchedEffect
        val near = listState.firstVisibleItemIndex <= 2 &&
            listState.firstVisibleItemScrollOffset == 0
        if (near) listState.scrollToItem(0) else listState.animateScrollToItem(0)
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
    // 测量状态按**轮**分开（`remember(roundId)`）：新一轮天然从 0 开始，
    // 就不需要"进流时清零"—— 那个清零会和同一帧的首次测量打架（先测到 48dp、再被清成 0，
    // 于是平滑高度永远追 0，整轮都不跟底）。现在占位气泡与 isStreaming 同帧出现，这条更要紧。
    val roundKey = state.inflight?.roundId
    var streamHeight by remember(roundKey) { mutableIntStateOf(0) }
    val smoothHeight = remember(roundKey) { Animatable(0f) }
    LaunchedEffect(streamHeight, state.collapse) {
        // C3：收缩窗口里**不追高度**。这里把平滑高度贴着实测高度走（snap），
        // 窗口一关它就是一个干净起点 —— 下一笔增长照常由"追高度差"那条动画消化。
        if (state.collapse != null) {
            smoothHeight.snapTo(streamHeight.toFloat())
            return@LaunchedEffect
        }
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
    var anchoredHeight by remember(roundKey) { mutableIntStateOf(0) }
    val streamProbe = remember { StreamProbe() }
    val anchorDiag = remember { AnchorDiag() }
    // 贴底那套"追高欠账"（`平滑差`）是**绘制量**，只在贴底时起作用。跨边界时它会凭空丢掉
    // （离开贴底 → 画面向上收一下）或凭空补上（回到贴底 → 向下沉一下），实测 8–151px。
    // 所以边界上做两件事：离开贴底把欠账折成滚动量；回到贴底把平滑高度对齐。
    var wasAtBottom by remember { mutableStateOf(atBottom) }
    LaunchedEffect(streamHeight, readingStreamBubble, state.isStreaming, atBottom, state.collapse) {
        // C3：收缩窗口里不补滚动、不追基线（布局正被动画改）。基线跟到当前高度，
        // 窗口一关从这里接着算。
        if (state.collapse != null) {
            wasAtBottom = atBottom
            anchoredHeight = streamHeight
            return@LaunchedEffect
        }
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

    // 上一轮收尾把补偿总量写进日志（高度不再清零：它随 `remember(roundKey)` 换新的一套）
    LaunchedEffect(roundKey) {
        if (roundKey == null) anchorDiag.finish() else anchorDiag.reset()
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
        headerMode = DetailHeaderMode.Frosted,
        // 聊天要能滚到玻璃页头下面，所以让位交给列表的 contentPadding；
        // 首条消息离页头底边 16dp（磨砂页头下面多留一点，用户 2026-11-24）
        contentSpacing = Space.l,
        // 输入区画在录制之外：可以采磨砂，消息从胶囊后面滚过去
        bottomOverlay = { backdrop ->
            val scrollScope = rememberCoroutineScope()
            Column(modifier = Modifier.fillMaxWidth()) {
                // 回到底部（用户 2026-11-26 第 3 条）：只在"不在底部"时出现，浮在输入胶囊上方、
                // 右对齐。它**不进 `ChatInputBar` 的测量** —— 列表底部让位读的是输入区实测高度，
                // 按钮若参与那笔测量，出现/消失就会让整屏气泡上下跳一下。
                AnimatedVisibility(
                    visible = !atBottom,
                    enter = fadeIn(tween(Motion.Fast)),
                    exit = fadeOut(tween(Motion.Fast)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = Space.m, bottom = Space.xs),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        JumpToBottomButton(
                            glass = glassOn,
                            backdrop = backdrop,
                            onClick = {
                                scrollScope.launch {
                                    listState.animateScrollToItem(0)
                                    AppLog.i("scroll", "回到底部：点了浮动钮")
                                }
                            },
                        )
                    }
                }
                ChatInputBar(
                    state = state,
                    viewModel = viewModel,
                    glass = glassOn,
                    backdrop = backdrop,
                    onHeightChanged = { inputBarHeightPx = it },
                )
            }
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
        // 列表底部让位 = IME/导航让位 + 输入区实测高度 + 12dp 投影余量（下面 contentPadding 用）。
        // 抽屉开着时换成冻结值：那是打开那一刻的同一个算式，所以背后气泡一动不动。
        val sheetHost = LocalSheetController.current
        val sheetOpen = sheetHost.spec != null
        val inputBarDp =
            if (inputBarHeightPx > 0) with(density) { inputBarHeightPx.toDp() } else ChatInputAreaHeight
        val liveListBottom = padding.calculateBottomPadding() + inputBarDp + 12.dp
        val listBottom = frozenListBottom ?: liveListBottom
        // 只在"打开时键盘还在"才冻：那种情况下收键盘会让让位整块消失。
        // 键盘本来就没开（输入区是一行）时，打开抽屉什么都没变 —— 冻住反而会把"收起后键盘回来"
        // 那一段变成延迟的硬跳，不如让它跟着键盘自然长。
        // 键盘可见性**不要**读成 composition 依赖：那样整个 content lambda 会在键盘动画期间
        // 每帧重组（连带重建 LazyColumn 的 DSL）。这里组合期只取一次 `WindowInsets` 句柄
        //（取句柄不读值，不会每帧失效），值等到真要判断时再量。
        // 注意 `WindowInsets.ime` 本身是 @Composable 属性，不能在 LaunchedEffect 里读。
        val imeInsets = WindowInsets.ime
        LaunchedEffect(sheetOpen) {
            if (sheetOpen) {
                if (imeInsets.getBottom(density) > 0) {
                    frozenListBottom = liveListBottom
                    AppLog.i("ui", "抽屉打开：冻结列表底部让位 ${liveListBottom.value.toInt()}dp")
                }
            } else if (frozenListBottom != null) {
                // 收起后键盘要回来（约 250ms）：等它落定再解冻，否则解冻那一下就是"键盘回来了"的跳。
                delay(500)
                frozenListBottom = null
                AppLog.i("ui", "抽屉收起：解冻列表底部让位（实时 ${liveListBottom.value.toInt()}dp）")
            }
        }

        // 列表铺满整页（连输入区底下也算）：消息能滚到输入胶囊后面，胶囊才是真玻璃。
        // 顶部/底部让位一律用 contentPadding —— 它不裁内容，滚动时会从玻璃底下过。
        // reverseLayout：列表的"锚点"是底部，新消息天然出现在眼前，
        // 完全不需要滚动跟随——串流时也就不再有"每 60ms 跳一次"造成的整屏抽动。
        //
        // key(selectionEpoch)：离场时 epoch +1，这一整层重建 —— SelectionContainer 随旧组合销毁，
        // 选文字手柄/工具栏立刻消失（它们是窗口级弹层，不跟页面滑动）。
        key(selectionEpoch) {
        // ── 唯一的行数据源（必须在 composable 里算，LazyColumn 的 DSL 不是 composable）──
        // 显示行 = 已提交的行 + （可能）正在生成的那一行。
        // 生成行在这里**合成**，不进 `state.rows`：所以它不会被当成"已保存的消息"，
        // 也不会进提示词/导出。占位/流式/定稿三态用**同一个 uiKey**，
        // 落库那一帧只是数据变化 —— 不删条目、不建条目、不重播淡入、不重排长文本。
        val inflightNow = state.inflight
        val collapse = state.collapse
        val collapseKey = collapse?.uiKey
        // 收缩窗口挂上来的那一帧，正文还必须是**旧正文**：`animateContentSize` 1.7.6 在节点
        // 刚挂上时把"当前量到的尺寸"当起点（`AnimData` 初始化成 targetSize，不播首帧动画）。
        // 所以先过一个帧回调让 Modifier 用旧正文量一次，下一帧再换成占位，收缩才真的从旧高度开始。
        var collapseShown by remember { mutableStateOf(false) }
        LaunchedEffect(collapseKey) {
            if (collapseKey == null) {
                collapseShown = false
                return@LaunchedEffect
            }
            withFrameNanos { }
            collapseShown = true
        }
        val displayRows: List<ChatViewModel.ChatRow> = remember(state.rows, inflightNow) {
            // 占位行与用户行在**同一次状态更新**里出现（生成行一直在这里合成，正文可能还是空）：
            // 列表只插入一次，反排列表的 atBottom 也只翻一次 —— 这是"发送时历史不闪"的前提。
            when {
                inflightNow == null -> state.rows
                // 就地改写（重新生成）：`rows` 原样交出去 —— 那一行本来就是时间线里的一条。
                // 正文由 item 里按收缩窗口决定（窗口开窗前那一帧还得是旧正文）。
                inflightNow.targetDbId != null -> state.rows
                else -> state.rows + ChatViewModel.ChatRow(
                    uiKey = inflightNow.uiKey,
                    role = ChatRole.Assistant,
                    content = inflightNow.text,
                )
            }
        }
        // 选字要拿焦点才能出手柄/工具栏，而焦点系统会请求 bringIntoView ——
        // 消息节点比视口高得多时，默认算法会把列表挪一小段（用户 2026-11-25 报的"长按一下列表就跳"）。
        // 这里把焦点滚动关掉：这个列表的阅读位置只由三种东西决定 —— 手指滚动、串流跟随、撤回回底。
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScrollSpec) {
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
                    // C3：收缩窗口里布局正被动画改，桥接再补就是把同一个位移算两遍 —— 一律 0。
                    translationY = if (!state.isStreaming || state.collapse != null) {
                        0f
                    } else if (atBottom) {
                        streamHeight - smoothHeight.value
                    } else if (readingBubble) {
                        (streamHeight - anchoredHeight).toFloat().coerceAtLeast(0f)
                    } else {
                        0f
                    }
                }
                .rubberOverscroll(overscroll),
            contentPadding = PaddingValues(
                // 页头 + 状态栏 + 首项那 16dp 都由骨架算进 padding（contentSpacing = Space.l）
                top = padding.calculateTopPadding(),
                // 输入区高度 + 键盘高度：键盘弹出时列表底部让位跟着长，被挡的消息才拖得出来。
                // 注意这里不能只写固定值，否则键盘一开最后几条就永远在键盘下面（用户 2026-11-23 报）。
                // 抽屉开着时这里是**冻结值**（见上面 listBottom）：那段时间让位一变，气泡就跟着动。
                bottom = listBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
                if (displayRows.isEmpty() && state.streamingText.isEmpty()) {
                    item(key = "empty") {
                        EmptyHint("还没有消息。发一句开始；点右上「请求」可以看到实际发出去的提示词。")
                    }
                }
                // 开场白翻页（用户 2026-11-25 第 3 条）：只在"还没说过话 + 不止一条"时出现。
                // 箭头贴在第一屏最上面那条气泡的**上沿**，和气泡同一个 item —— 翻页时一起动，不会脱节。
                val canSwitchGreeting = state.greetings.size > 1 &&
                    state.rows.none { it.role == ChatRole.User }
                val shownGreeting = greetingIndexOf(
                    state.greetings,
                    state.rows.firstOrNull()?.content,
                )
                // key = 行的**身份**（`uiKey`），不是下标：
                // 下标键在中间删一条之后会把后面每条都换 key → 整列重播淡入（v161 的病根）。
                itemsIndexed(
                    items = displayRows.asReversed(),
                    key = { _, row -> row.uiKey },
                ) { index, row ->
                    val isInflightRow = inflightNow != null && row.uiKey == inflightNow.uiKey
                    val isLastSettled = row.uiKey == state.rows.lastOrNull()?.uiKey
                    val isFirstSettled = row.uiKey == state.rows.firstOrNull()?.uiKey
                    // 只做淡入/淡出，不做位移动画：串流时列表每帧都在长高，
                    // 位移动画会不断重放，整屏看起来在闪。
                    //
                    // 动作入口从"长按出抽屉"改成了气泡底下一排按钮（用户 2026-11-25 第 11 条）：
                    // 长按现在交给文字选择，按钮常驻可见，不用猜哪里能按。
                    val isLast = isLastSettled
                    val isAssistant = row.role == ChatRole.Assistant
                    val itemAnim = if (isInflightRow || suppressItemFade ||
                        (index == 0 && (justSettled || suppressNewestFade))
                    ) {
                        // 离场重建（销毁 SelectionContainer）这一趟不要淡入：否则整屏闪一下；
                        // 串流落库那一帧同理（那颗气泡刚从流式状态换过来）
                        Modifier
                    } else {
                        Modifier.animateItem(
                            fadeInSpec = tween(Motion.Base, easing = Motion.Decelerate),
                            placementSpec = null,
                            fadeOutSpec = tween(Motion.Fast),
                        )
                    }
                    val isGreetingSlot = canSwitchGreeting && isFirstSettled
                    // 「这条是不是开场白」和「能不能翻页」是两件事（用户 2026-11-25：
                    // 单开场白的会话里开场白气泡底下还挂着动作行，多开场白时才没有 —— 前后不一致）。
                    // 判据只看"这条是不是那张卡的开场白"：会话第一条 + 还没人说过话 + 内容对得上。
                    val isGreetingMessage = isFirstSettled &&
                        shownGreeting >= 0 &&
                        state.rows.none { it.role == ChatRole.User }
                    // 最后一条助手消息有多个 swipe 时，气泡下面挂一条 ‹ 2/3 ›（和开场白那条同款）
                    val showSwipeBar = !state.isStreaming && !isGreetingMessage &&
                        isLast && isAssistant && state.lastSwipes.size > 1
                    // 这一行显示的正文：就地改写时正文来自 `inflight`，但**收缩窗口挂上来的
                    // 第一帧仍要显示旧正文**（见 collapseShown 的注释）。
                    val shownContent = when {
                        !isInflightRow -> row.content
                        collapseKey == row.uiKey && !collapseShown -> row.content
                        else -> inflightNow?.text.orEmpty()
                    }
                    // 还没吐字 → 占位气泡（`生成中` / `重新生成中` + 呼吸点）；
                    // 吐了字就退回正文 + 光标。两者都在**同一行、同一个 item** 里换内容。
                    val pendingKind = when {
                        !isInflightRow || shownContent.isNotEmpty() -> null
                        inflightNow?.targetDbId != null -> PendingKind.Regenerating
                        else -> PendingKind.Generating
                    }
                    // 平滑收缩只挂在**重新生成那一行**上（r2 §13.1 C1）：正文一开始长就摘掉，
                    // 常驻的话串流每换一次行都会被包一层动画，与"布局自然增长 + 桥接"抢位移。
                    val collapseAnim = if (collapseKey == row.uiKey) {
                        Modifier.animateContentSize(
                            // 减动效：不留动画，直接换尺寸（项目规矩：统一走 rememberReduceMotion）
                            animationSpec = if (reduceMotion) {
                                snap()
                            } else {
                                tween(Motion.Collapse, easing = Motion.Standard)
                            },
                            // 反排列表的锚点在底部：收的时候内容贴着下沿，观感才是"收回输入框"
                            alignment = Alignment.BottomStart,
                        )
                    } else {
                        Modifier
                    }
                    Column(modifier = itemAnim.fillMaxWidth().then(collapseAnim)) {
                        MessageRow(
                            message = ChatMessage(row.role, shownContent),
                            style = state.effectiveMessageStyle,
                            // 收缩窗口里连光标也不给：窗口开着时这一行还是**旧正文**或占位，
                            // 光标一出现会挤动旧正文（一帧的抽动）
                            showCursor = isInflightRow && pendingKind == null && collapseKey != row.uiKey,
                            pending = pendingKind,
                            displayText = displayText,
                            fontSizeSp = state.effectiveBubbleFontSize,
                            // 量气泡高度（贴底跟随与锚点补偿都靠它），顺带记一下上沿在窗口里的 Y：
                            // 日志里的"顶Y"就是它，用来验证读到的那一段有没有被顶走。
                            modifier = if (isInflightRow) {
                                Modifier
                                    .onSizeChanged { streamHeight = it.height }
                                    .onGloballyPositioned {
                                        streamProbe.topY = it.positionInWindow().y.toInt()
                                    }
                            } else {
                                Modifier
                            },
                        )
                        // 生成中这一行：先把落库后会多出来的高度占住
                        //（动作行 +34dp；重新生成还会多出「版本条」+40dp），
                        // 否则定稿那一帧整条列表会往上跳一截（用户 2026-11-26）。
                        if (isInflightRow) {
                            Spacer(
                                Modifier.height(
                                    ActionRowHeight + if (inflightNow?.targetDbId != null) {
                                        SwipeBarBlockHeight
                                    } else {
                                        0.dp
                                    },
                                ),
                            )
                        }
                        if (!isInflightRow && showSwipeBar) {
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
                        // 开场白不是"一条回复"，底下不挂动作（用户 2026-11-25 第 7 条）；
                        // 判据用 isGreetingMessage，和"有几条开场白"无关。
                        if (!isInflightRow && !isGreetingMessage) {
                            MessageActionRow(
                                isUser = !isAssistant,
                                canRegenerate = isAssistant && isLast,
                                canContinue = isAssistant && isLast,
                                onRewind = { viewModel.rewindToMessage(row.uiKey) },
                                onCopy = { clipboard.setText(AnnotatedString(row.content)) },
                                onRegenerate = viewModel::regenerateLast,
                                onContinue = viewModel::continueLast,
                                onDelete = { deletingKey = row.uiKey },
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
    }

    // 删除这条：走统一的玻璃确认框（和删会话/删卡同一个材质与措辞规则）
    deletingKey?.let { key ->
        ConfirmDeleteDialog(
            controller = confirm,
            title = "删除这条消息？",
            message = "只删这一条，不能撤销。",
            onConfirm = {
                deletingKey = null
                viewModel.deleteMessageAt(key)
            },
            onDismiss = { deletingKey = null },
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

/** 气泡底下动作行的高度（32dp 图标 + 顶部 2dp）。串流气泡要预留它，落库那一帧高度才不变。 */
private val ActionRowHeight: Dp = 34.dp

/** 版本条整块高度（上方 4dp 间距 + 条本身 36dp）。重新生成的串流气泡要连它一起预留。 */
private val SwipeBarBlockHeight: Dp = 40.dp

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
    // 抽屉开着的那段时间输入区要**保持展开**：它一缩，列表底部让位（读实测高度）就跟着缩，
    // 背后的气泡会上下动（用户 2026-11-25 第 11 条）。焦点真的回来了才解除冻结。
    var keepExpanded by remember { mutableStateOf(false) }
    // 键盘是否真的在：只靠 focus 不行 —— 按返回键收起键盘后输入框仍然有焦点，
    // 于是它会一直停在两行的高度（用户 2026-11-25 第 9 条）。
    // 规则：有焦点 且（键盘在 或 已经有字）才展开；键盘收起且没字就回到一行（带动画）。
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val imeBottomPx = ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    // 键盘**开始**收起就跟着缩，不等它完全落下（用户 2026-11-26 第 5 条）：
    // 以前只认 `imeVisible == false`，而它在 IME 的 250ms 退场动画跑完才变假 ——
    // 观感就是"键盘先滑下去，滑完了输入框才慢半拍缩回一行"。
    var imeHiding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var last = imeBottomPx
        snapshotFlow { ime.getBottom(density) }.collect { now ->
            when {
                now < last -> imeHiding = true
                now > last -> imeHiding = false
            }
            last = now
        }
    }
    val expanded = keepExpanded ||
        (focused && (imeVisible || state.input.isNotEmpty()) && !imeHiding)
    LaunchedEffect(focused, imeVisible) {
        // 什么时候解冻：**只有"解冻后高度不会变"时**才解。
        // - 焦点回来了但键盘还没弹出来（键盘是异步的）：继续冻着，否则会先缩一行再撑开，列表跟着跳；
        // - 键盘真的在了 / 已经有字：解冻也还是展开态（expanded 不变）；
        // - 焦点丢了（用户点去别处）：正常收起。
        if (!focused || imeVisible || state.input.isNotEmpty()) keepExpanded = false
    }
    // 真机上"键盘收了但输入框没缩"这类问题只能靠日志判断：展开条件三个量都记上
    LaunchedEffect(expanded, imeVisible) {
        AppLog.i("ui", "输入区展开=$expanded 键盘=$imeVisible 字数=${state.input.length}")
    }
    // 展开/收起现在是逐帧动画：高度日志按"变了这么多才记一行"节流，
    // 否则一次展开刷十几行，把真正有用的日志挤掉。
    var lastLoggedHeight by remember { mutableIntStateOf(0) }
    // 生效档案：会话指定 → 卡指定 → 全局默认（三级继承的解析在 UiState 里）
    val currentProfile = state.profiles.firstOrNull { it.id == state.effectiveProfileId }

    val openModelSheet = {
        // 抽屉从屏幕底部上来，键盘会把它盖掉一半：先收键盘。
        // 输入区高度冻在**当前状态**（不要强制展开：从收起态打开时凭空长高会盖住列表）。
        keepExpanded = expanded
        focusManager.clearFocus()
        showModelSheet(
            sheet = sheet,
            viewModel = viewModel,
            onDismiss = {
                // 收回后把输入栏叫回来：恢复成弹出态并呼出键盘（用户 2026-11-25 第 3 条）。
                // 先 requestFocus 再 show()：焦点到位后键盘才会真的弹出来。
                focusRequester.requestFocus()
                keyboard?.show()
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            // 停止用**淡红**（用户 2026-11-25 第 6 条）：红色在这里不是"危险"，是"正在跑、可以打断"。
            // 浓度和发送态的主色 20% 对齐，两颗钮才不会一颗重一颗轻。
            fillColor = if (isStreaming) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
            },
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
                                MaterialTheme.colorScheme.error,
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
        // 和发送钮同一套配色、低一档浓度（用户 2026-11-25 第 5 条：原来是透明的，太弱）：
        // 发送 20% 主色 = 主操作；模型 12% 主色 = 次操作。两颗钮并排时才读成一组。
        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
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
                // 满色：底色已经是淡紫，字再压到 72% 就只剩一层灰
                color = MaterialTheme.colorScheme.primary,
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
 * 消息渲染的唯一入口：样式切换只在这里分叉。
 * 气泡是默认样式，整行卡片保留为可选项（设置 → 外观里能切）。
 */
@Composable
private fun MessageRow(
    message: ChatMessage,
    style: MessageStyle,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
    /** 非 null = 这一条还没吐字，气泡里显示占位行（见 [PendingLine]）。 */
    pending: PendingKind? = null,
    displayText: (String) -> String = { it },
    /** 气泡正文的字号（sp）：三级继承的生效值，只有聊天页气泡用它。 */
    fontSizeSp: Float = DefaultBubbleFontSize,
) {
    when (style) {
        MessageStyle.Bubble -> BubbleRow(message, modifier, showCursor, pending, displayText, fontSizeSp)
        MessageStyle.Card -> CardRow(message, modifier, showCursor, pending, displayText, fontSizeSp)
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

/**
 * 焦点滚动关掉：选字引起的 `bringIntoView` **不允许**移动列表。
 *
 * 为什么需要（2026-11-25 真机，v147 之后）：选字能用了，但长按会让列表跳一下。
 * `SelectionContainer` 拿焦点才能出手柄/工具栏，焦点系统随即请求 `bringIntoView`；
 * 消息节点是个 **18900px 高**的节点，默认算法 `defaultCalculateScrollDistance` 落在
 * "对齐某条边"那一支：
 *
 *     leadingEdge = 气泡容器上沿 = -16457
 *     trailingEdge = 上沿 + 高 = 2373，容器（视口）高 2800
 *     → 不走"完全可见"、也不走"两端都超出"，于是取 abs 较小的那侧对齐
 *     → trailingEdge - containerSize = 2373 - 2800 = **-427**
 *
 * 日志里正是 `偏移 0 → 427`。按在消息上方时，它改选 leadingEdge 那一侧，于是往上跳一大段 ——
 * 用户看到的就是"最上方长按往上滑、最下方长按往下滑"。
 *
 * 返回 0 表示"这条请求已经满足"，动画立刻停，位置一点不动。
 */
@OptIn(ExperimentalFoundationApi::class)
private val NoFocusScrollSpec = object : BringIntoViewSpec {
    // 一律 0：这个列表里唯一的焦点目标是消息气泡（`SelectionContainer` 要焦点才能出手柄），
    // 而"因为选中了一段文字就把阅读位置挪走"在任何情况下都是错的 —— 普通长度的气泡同样是跳一下。
    // `BringIntoViewSpec.defaultCalculateScrollDistance` 是 internal，拿不到，所以不回落默认实现。
    private var lastLogAt = 0L

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        // 真机上只能靠日志确认"这条 spec 真的生效了"：被拦下的请求记一行（节流，不刷屏）。
        val now = SystemClock.elapsedRealtime()
        if (size > containerSize && now - lastLogAt > 2_000) {
            lastLogAt = now
            AppLog.i(
                "scroll",
                "焦点滚动被拦下 节点高=${size.toInt()} 视口高=${containerSize.toInt()} " +
                    "偏移=${offset.toInt()}",
            )
        }
        return 0f
    }
}

/**
 * 消息底板：**背景 + 描边**，刻意不用 Material3 的 `Surface`。
 *
 * 为什么不能用 `Surface`（2026-11-25 真机定位）：它内部会加一层 `Modifier.clip(shape)`。
 * 落在一条 **18900px 高**的气泡上时，那层裁剪会让**整棵子树在离气泡上沿约 8k px 之后不再参与
 * 命中测试** —— 手指按在正文上，事件只到气泡这一层就停了，正文的选字手势根本没启动，
 * 于是"长按没反应、连高亮都没有"，而画面照旧有字（渲染不受影响，所以更难发现）。
 *
 * 真机日志 `tag=sel` 的对照（同一个气泡、同一份内容）：
 * 气泡内局部 y=5100 → 气泡 / 选区容器 / 正文 三层都有 `按下`；
 * 局部 y=9910 / 14620 / 16888 → **只有气泡有** `按下`。
 *
 * 背景与描边的视觉和 `Surface(color=…, shape=…, border=…)` 等价（它当时没有 elevation），
 * 所以替换不改变观感；换来的是子树在任何高度都还能被按到。
 *
 * 以后要加投影/抬升，别退回 `Surface` —— 用 `Modifier.drawWithContent` 或给外层加，
 * 任何会生成本地渲染层的 modifier 都要先想一遍"这条消息可能有多高"。
 */
@Composable
private fun MessagePanel(
    backgroundColor: Color,
    contentColor: Color,
    shape: Shape,
    border: BorderStroke?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(backgroundColor, shape)
            .then(if (border == null) Modifier else Modifier.border(border, shape)),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
private fun BubbleRow(
    message: ChatMessage,
    modifier: Modifier,
    showCursor: Boolean,
    pending: PendingKind?,
    displayText: (String) -> String,
    fontSizeSp: Float,
) {
    val palette = LocalTavernPalette.current
    val isUser = message.role == ChatRole.User
    Row(modifier = modifier.fillMaxWidth()) {
        if (isUser) Spacer(Modifier.weight(0.14f))
        Box(
            modifier = Modifier
                .weight(0.86f)
                .wrapContentWidth(if (isUser) Alignment.End else Alignment.Start),
        ) {
            MessagePanel(
                backgroundColor = if (isUser) palette.userBubble else MaterialTheme.colorScheme.surface,
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
                    if (pending != null) {
                        // 还没吐字：占位行（不可选字 —— 没有正文可选）
                        PendingLine(kind = pending, fontSizeSp = fontSizeSp)
                    } else {
                        // 长按气泡 = 选文字（用户 2026-11-25 第 11 条）：操作都在底下那排按钮里。
                        SelectionContainer {
                            Text(
                                markupText(displayText(message.content), MaterialTheme.colorScheme.primary),
                                // 只改字号：行高跟着字号走，气泡自己会跟着长高
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSizeSp.sp),
                            )
                        }
                        if (showCursor) StreamingCursor()
                    }
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
    pending: PendingKind?,
    displayText: (String) -> String,
    fontSizeSp: Float,
) {
    val palette = LocalTavernPalette.current
    MessagePanel(
        backgroundColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, palette.cardBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Space.m)) {
            Text(
                text = if (message.role == ChatRole.User) "我" else "角色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                if (pending != null) {
                    PendingLine(kind = pending, fontSizeSp = fontSizeSp)
                } else {
                    SelectionContainer {
                        Text(
                            markupText(displayText(message.content), MaterialTheme.colorScheme.primary),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSizeSp.sp),
                        )
                    }
                    if (showCursor) StreamingCursor()
                }
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
    // 每个可滚动面都要挂橡皮筋（INV-06）：请求正文这一段也一样
    val overscroll = rememberRubberOverscroll()

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
                        .rubberOverscroll(overscroll)
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

/**
 * 聊天页的「回到底部」浮动钮（用户 2026-11-26 第 3 条）。
 *
 * 只在**离开底部**时出现，贴在输入胶囊上方右侧；点了用 `animateScrollToItem(0)` 滑回去
 * （反排列表的 0 就是视觉最底部，也是串流跟随的锚点）。
 *
 * 为什么放在 `bottomOverlay` 的 Column 里、而不参与输入区高度：列表底部让位读的是
 * `ChatInputBar` 的实测高度，这枚钮一旦进那笔测量，它出现/消失就会让整屏气泡跳一下。
 * 现在它只是"长在输入区上方的一段额外高度"，输入栏本身仍在屏幕底部不动。
 */
@Composable
private fun JumpToBottomButton(
    glass: Boolean,
    backdrop: GlassBackdrop,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    GlassSurface(
        backdrop = backdrop,
        glass = glass,
        shape = CircleShape,
        interactionSource = press,
        // 浮在正文上：投影用半强度，别比输入胶囊还重
        shadowScale = 0.6f,
        modifier = Modifier
            .size(36.dp)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = "回到底部",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.Center).size(22.dp),
        )
    }
}
