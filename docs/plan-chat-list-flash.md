# 聊天列表的动画与消息生命周期：架构审查与重构方案（2026-11-26 午后版）

> ⚠️ **第二轮审查已完成：[plan-chat-list-flash-r2.md](plan-chat-list-flash-r2.md)**。
> 本页的根因分析与模型仍然成立，但 **5 处以 r2 为准**：①`uiKey` 的命名与 K1–K5 不变量（本页 §6.2）；
> ②`inflight` 增加 `roundId`/`sessionId`（本页 §6.2）；③回底方式分档（本页 §5.4）；
> ④实施批次改为 **P0 数据一致性 → P1 → P2 → P3 → P4**（本页 §8 少了 P0）；
> ⑤新增一条根因：**插入本身会把 `atBottom` 翻成 false**（本页 §2.2 没有，见 r2 §6.2）。

> 状态：**审查完成、仍等拍板，未开工**。本页是**重写**，不是叠加：
> 上午版基于"真机现象 + 局部代码"做的推断，午后这一版逐行核对了
> `ChatScreen.kt`（1623 行）、`ChatViewModel.kt`（3384 行）、`SqliteChatRepository` / `TavernDatabase`、
> `PageStack.kt`、`Motion.kt`，**推翻了上午版的两条结论**（见下面"三处更正"）。
> 证据级别贯穿全页：**【证】**= 代码/日志直接可读；**【推】**= 由代码推出、未真机量测；**【验】**= 需真机实验。
> 本机编不了 `:app`（OOM），所以涉及 Compose 内部行为的断言一律标【验】，不冒充结论。
>
> 相关：接手 → [dev/handoff.md](dev/handoff.md)；必须成立什么 → [ui-invariants.md](ui-invariants.md)；
> 词表 → [ui-map.md](ui-map.md)；踩坑 → [dev/pitfalls.md](dev/pitfalls.md)。

## 与上午版的三处更正

| # | 上午版的说法 | 核对代码后 | 依据 |
|---|---|---|---|
| 1 | "2520px 的气泡换成 80px 的‘生成中’，**必须**动画改高度（`animateContentSize`）" | **不对**。2440px 的位移在 250ms 内走完 = 约 10 屏/秒；本项目的整页转场才 280ms/一屏。更要紧的是 `animateContentSize` 一旦装上，串流期间每一次换行都会被动画包一层，与既有的"追高桥接"（`translationY`）**抢同一个位移**；中途撤掉它又会把剩余高度一次性 snap。所以它**不是**第一批该用的东西。真正能消掉"闪"的是**条目身份 + 滚动策略**，高度动画最多是实验项。 | §4.2 / §4.3 |
| 2 | "占位气泡用它将来的下标做 key 就行" | 可行但**不该**。位置键的代价在"中间删除/撤回"：后续每一条的下标都变 → key 全变 → `animateItem` 的淡入对所有条目重播（这正是 v161 "整屏闪一下"的病根，只是被 append 场景掩盖了）。项目**已经有身份**（`messages.id`），只是在投影进 UI 时被丢掉（`openSessionInternal` 的 `ChatMessage(role, content)`）。改成身份键还顺手消掉审计 P1-5 的"下标 ↔ 行号强耦合"。 | §3.2 / §6 |
| 3 | 根因只列了三条机制（M1/M2/M3） | 漏了**第四条粒子通道**：`contentPadding.bottom`（输入区实测高度 + IME）在发送那一刻**连续**变化。发送时"插新条目 + 回底 + 键盘收起 + 输入区收回一行"四件事同帧起步，这才是"发送时历史闪"的复合来源。 | §2.3-L1 |

**结论摘要（六条）**

1. **建议实施**，但按 §8 的**四批**走，不是一次大改；每批一个 APK + 一份验证文件（M23…）。
2. **最值得先解决的根因是"落库换代"**：流式气泡是 `item(key="stream")`、落库后是 `item(key="msg-N")`，两者是**不同的组合节点**。这一帧要重建节点、重排 20k 字长文本、丢 `translationY` 残值 —— 补丁只能盖住其中两项。
3. **"身份"用 DB 已有的 `messages.id`，不加 schema、不加 `clientMessageId`**；本次新造的行（用户消息、占位行）用客户端 key，落库后只补 `dbId`、**不换 key**。
4. **"正在生成"是一个单例字段，不是每行的 status**。每行挂 `MessageStatus` 会允许"两行同时 streaming""streaming 的不是最后一行"这类非法态，用 `UiState.inflight` 让它们无法表达。
5. **落地时删掉三样东西**：`ReplaceTarget.restore`、`lastSwipes` 的"先摘掉再放回"、`justSettled`/`suppressNewestFade`。它们都是"条目会换代 / 内容会丢"的补偿。
6. **重新生成的开场收缩是物理位移，删不掉**（反排列表底部锚定 + 最后一条缩水 Δ = 整段历史下移 Δ）。第一批先**硬切**，把"收缩要不要动画"降级为实验项 E1。

---

## 一、现状架构

### 1.1 数据源与身份

| 层 | 事实 | 位置 |
|---|---|---|
| DB | 原生 SQLite，v4。`messages(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id, is_user, is_system, content, send_date)`；`message_swipes(message_id, seq, content)`；`sessions(card_id, card_name, title, created_at, updated_at, pinned)` | `TavernDatabase.kt` |
| 仓库 | `messages(id)` 按 `id ASC`（= 时间线）；`append` 返回带自增 id 的 `StoredMessage`；`updateMessage` / `deleteMessage` / `swipes` / `setSwipes` 全按 id | `SqliteChatRepository.kt` |
| UI 模型 | `core.model.ChatMessage(role, content)` —— **只有两个字段，没有 id** | `core/model/ChatMessage.kt` |
| 投影 | `openSessionInternal` 把 `StoredMessage` 拍成 `ChatMessage(role, content)`，**id 在这一行丢掉**；`rewindToMessage` 重建时同样丢 | `ChatViewModel.kt:1058`、`:3354` |
| 状态 | `UiState.transcript: List<ChatMessage>` + `streamingText` + `isStreaming` + `streamingRegenerate` + `lastSwipes/lastSwipeId` | `ChatViewModel.kt:159-387` |
| 下标耦合 | `deleteMessageAt(index)` → `stored.getOrNull(index)`；`rewindToMessage(index)` → `stored.drop(index)`；`selectSwipe` / `regenerateLast` / `continueLast` → `stored.lastOrNull()` | 审计 P1-5 |

**这意味着**：UI 层的"第几条"与 DB 层的"第几行"是隐式 1:1。任何"UI 多一行"（例如凭空造一个"生成中"气泡）都会把这个 1:1 打断 —— 这就是 Pending 不能进 `transcript` 的真正原因，不是审美问题。

### 1.2 渲染结构（`ChatScreen.kt:515-691`）

```
LazyColumn(reverseLayout = true, state = listState,
           modifier = graphicsLayer{ translationY = … }  // 串流桥接位移（三种模式）
           contentPadding = (top = 骨架给的, bottom = 输入区 + IME + 12dp))
    item(key = "stream")              ← 仅在 streamingText 非空时存在
        MessageRow(showCursor=true) + Spacer(34dp [+40dp 若重新生成])
    item(key = "empty")               ← 空会话提示
    itemsIndexed(transcript.asReversed(),
                 key = { i, _ -> "msg-" + (transcript.lastIndex - i) })
        Column(itemAnim.fillMaxWidth()) { MessageRow + [版本条] + [开场白翻页条] + [动作行] }
```

要点：

- 串流气泡**只在首个 token 到达后**才存在（`if (shownStream.isNotEmpty())`，`:556`）。点发送到首 token 之间，列表里**没有**任何代表"AI 正在写"的条目。
- 串流气泡与落库后的气泡是**两个 key、两个组合节点**；落库那一帧是"删一个 + 加一个"。
- `itemAnim` = `Modifier.animateItem(fadeInSpec = 180ms, placementSpec = **null**, fadeOutSpec = 120ms)`（`:625`）—— **位移动画是刻意关掉的**，条目入场淡入**默认对每条都装**。
- 例外：`suppressItemFade`（离场重建时）与 `(index==0 && (justSettled || suppressNewestFade))`（落库那一帧）会把它摘掉。这两个例外是"条目换代会重播淡入"的补丁。
- `key(selectionEpoch)`（`:515`）会让整层 LazyColumn 随离场重建一次 —— 重建会**重播所有条目的淡入**（注释里已写明，`f8`/v157 踩过）。
- 光标是 `Row { SelectionContainer { Text } ; StreamingCursor() }` 里的**一个 `Text("▍")`**（`:1358-1368`），**参与布局**。
- 动作行 34dp（`ActionRowHeight`）在串流气泡里用 `Spacer` 预留；版本条 40dp（`SwipeBarBlockHeight`）只在 `isStreaming && streamingRegenerate` 时预留。
- `streamingRegenerate` **只在 `beginCompletion` 里被赋值，`finish`/`stop` 都不复位**（grep 三处：`UiState:301`、`ChatViewModel:2846`、`ChatScreen:581`）。目前在串流气泡消失后它不产生效果，属于**遗留死状态**，但"预留多少高度"依赖它 —— 与后面"新的那一轮是什么类型"耦合，是脆的。

### 1.3 滚动与动画机制（现状清单）

| 机制 | 现状职责 | 位置 |
|---|---|---|
| `reverseLayout = true` | 底部锚定；index 0 = 最新 | `:521` |
| `rememberLazyListState()` | 列表状态**在页面内**，页面离场即销毁 | `:206` |
| `atBottom` | `firstVisibleItemIndex==0 && firstVisibleItemScrollOffset==0` | `:231` |
| `LaunchedEffect(transcript.size)` | 贴底 **或 自己发的** → `scrollToItem(0)`（**瞬时**） | `:241` |
| `scrollToBottomSignal` | 撤回后 `animateScrollToItem(0)` | `:223` |
| 串流跟随（场景 A） | 量气泡高 → `smoothHeight` 追它 → `translationY = h - smooth`（**绘制层滞后**） | `:263-270`、`:534` |
| 阅读锚点保护（场景 C） | `dispatchRawDelta(长高量)` 把被顶走的那一段补回来 | `:296-343` |
| `NoFocusScrollSpec` | 选字引起的 `bringIntoView` 一律返回 0 | `:1267` |
| `animateItem` | 入场淡入 + 出场淡出；**无位移动画** | `:625` |
| `key(selectionEpoch)` | 离场销毁 `SelectionContainer`（手柄是窗口级弹层） | `:515` |
| `contentPadding.bottom` | 输入区**实测**高度 + IME + 12dp；抽屉期间冻结 | `:482-553` |
| `JumpToBottomButton` | `!atBottom` 时淡入；不进输入区测量 | `:407-435` |

### 1.4 三条独立的"整列位移"通道（这是本次审查最重要的结构发现）

同一个观感"列表动了"，背后有**三个互不相干**的来源，现在它们在同帧一起动：

| 通道 | 谁在动 | 量级 | 现在有没有治理 |
|---|---|---|---|
| **C1 布局** | 条目增删 / 条目高度变化 → 底部锚定 → 历史整体平移 | 0 – 3800px（整屏级） | 高度差靠 `Spacer` 预留；增删没有动画 |
| **C2 滚动** | `scrollToItem(0)` / `dispatchRawDelta` / 手指 | 同上 | 瞬时跳（不是动画） |
| **C3 绘制** | `translationY`（串流桥接）、`rubberOverscroll` 拉伸 | 0 – 数千 px | 已按模式分三段 |
| **C4 让位** | `contentPadding.bottom`（IME 250ms + 输入区 300ms + 抽屉冻结） | 0 – 1100px | 抽屉冻结；**发消息时未治理** |

把 C4 单列出来是本轮的更正：`SendStopButton.onSend` 是 `viewModel.send(); focusManager.clearFocus()`（`:926-929`）→ 一帧之内 C1（插行）、C2（回底）、C4（输入区收回一行 46dp + 键盘落下 ~1000px）同时起步。**用户说的"发送时历史闪"，很可能主要是 C4 那一段连续位移被误读成"闪"，或者三者叠加的复合运动。**

---

## 二、闪动根因（按影响排序）

### 2.1 逐条核对任务单里的五条判断

| # | 判断 | 结论 | 依据 |
|---|---|---|---|
| 1 | 串流时文本增长 → 高度变化 → 反向列表里其他 item 位置持续变化 | **成立（限贴底场景）**。贴底时新气泡下沿被钉住，长高多少，上面全部条目上移多少。用户读历史（下标>0）时条目在视口外长，视口不动；停在气泡自己身上时走 `dispatchRawDelta` 补偿。 | 日志 `气泡高=2520 顶Y=-99`【证】；`ChatScreen:296-343`【证】 |
| 2 | `placementSpec`/`animateItem` 因位置持续变化而不断重启 → 抖动 | **作为"现象根因"不成立**：`placementSpec = null` 是显式关掉的（`:628`）。**作为"设计约束"成立**：串流期间每帧长高，位移动画必然每帧重放。所以真正的祸首是**淡入**（`fadeInSpec`），而它只在**条目换代/换 key** 时可见。 | `:625-630`【证】 |
| 3 | 离散结构变化与连续尺寸变化应分开处理 | **成立，且现在被违反**。流式气泡的存在与否受 `streamingText` 控制（离散），它内部的高度受文本控制（连续），而它旁边那块 `Spacer` 的高度受 `isStreaming && streamingRegenerate` 控制（**半离散**：一个布尔量决定 40dp）。 | `:556-589`【证】 |
| 4 | 气泡身份不稳定 / UI 临时条目与 DB 条目分离 → Pending→Streaming→Completed 删旧建新 | **成立**（虽然现在还没有 Pending）：streaming 与 completed 是 `key="stream"` ↔ `key="msg-N"` 两个节点；而且 `msg-N` 是**位置键**，不是身份键 —— 中间删一条，后续全换 key。 | `:560`、`:608`【证】 |
| 5 | 重新生成删掉大气泡 → 高度骤失 → 整列大幅位移 | **成立，且这是所有现象里最大的一笔**（2520px / 2940px / 3850px）。 | 日志 `气泡高=2520/2940/3850`【证】 |

### 2.2 遗漏的根因（任务单里没有、但代码里确实存在）

| # | 根因 | 说明 | 级别 |
|---|---|---|---|
| **L1** | **C4 让位通道未治理** | 发送时输入区收回 + 键盘落下是**连续**位移（46dp + IME 高度，300/250ms），与 C1/C2 同帧起步。观感上是"整个历史在动"。 | 【证】+【验】（哪一份占比多少要量） |
| **L2** | **首 token 才插入气泡** | `if (shownStream.isNotEmpty())`：发送后到首 token 之间没有任何条目。于是"气泡出现"这件事发生在**最不该动的时刻**（模型刚开始吐字），且它是一次**插入**：稳定 key 会锚住旧条目 → 新条目落在视口外（日志 `模式=B idx=1 off=0` 就是它）。 | 【证】 |
| **L3** | **回底是瞬时的** | `scrollToItem(0)`（不是 animate）。哪怕判定对了，观感也是"先错位再瞬移"。 | 【证】 |
| **L4** | **光标参与布局** | `▍` 是 Row 里一个 `Text`。落库后它被移除 → 若最后一行刚好排满，会少一整行；而预留的 34dp 是固定值，两者相加**不守恒**。 | 【推】+【验】 |
| **L5** | **`translationY` 残值在 `isStreaming=false` 当帧归零** | 同一行代码里写死 0（`:535`）。日志出现过 22–140px 的残值 → 落库那一帧那点内容会"跳一下"。v164 之前是主因之一，现在被压到几十~百来 px。 | 【证】 |
| **L6** | **落库要重建长文本节点** | 新条目是新组合节点，20k 字正文要重新排版（显示正则 + `InlineMarkup` + `Text` 布局）。几十 ms 的掉帧在被动的瞬间最刺眼。 | 【推】+【验】 |
| **L7** | **页面离场即丢列表状态** | `rememberLazyListState()` 在页面内、页面栈在转场后 `removeAt(0)`（`PageStack.kt:174`）→ 从「会话配置」返回聊天页时**总在最底部**、且整层重建。与"闪烁"不是一类，但同属"列表位置不稳定"。 | 【推】+【验】 |

### 2.3 排序（按"用户能看见"的贡献）

| 排名 | 根因 | 现象 | 能否靠身份/滚动解决 |
|---|---|---|---|
| 1 | **落库换代（2.2-L5/L6 + 判断 4）** | 每次回复写完都闪一下 | ✅ 身份稳定后**整段消失** |
| 2 | **首 token 插入 + 视口外落点（L2）** | AI 气泡"跑到输入框下面"、不跟底 | ✅ 占位行 + 离散 `animateScrollToItem` |
| 3 | **重新生成删大气泡（判断 5）** | 重新生成开场整屏位移 | ⚠️ 只能"变短/变平滑"，**消不掉**（§4.2） |
| 4 | **回底瞬时跳（L3）** | 发送时历史先错位再瞬移 | ✅ 换 `animateScrollToItem` |
| 5 | **C4 让位（L1）** | 发送时历史整体上/下移 | ⚠️ 只能"分离职责"，键盘动画本身就该动 |
| 6 | **光标/预留不守恒（L4）** | 落库 ±1 行微跳 | ✅ 光标改非布局、预留改"行内常驻" |
| 7 | **中间删除/撤回换 key** | 删一条后整列淡入 | ✅ 身份键 |
| 8 | **页面返回丢位置（L7）** | 从会话配置返回回到底部 | ⭕ 可选（列表状态上提） |

---

## 三、方案 A 评估：提前建 Pending 消息

### 3.1 "同一个 item" 能不能做到

**能，但前提不是"提前建"，而是"键一致 + 渲染入口一致 + 类别一致"。** 三条都要：

1. **key 一致**：占位行与将来那条消息必须用**同一个字符串**做 key；
2. **入口一致**：占位行必须走**同一个 `items(...)` 调用、同一个行 composable**（放进同一个 `LazyColumn` 的 items 序列里），不能是插在外面的 `item { }`；
3. **类别一致**：两者的 Modifier 链不能变（例如占位行外面多包一层 `AnimatedVisibility` 或换一个 composable 类型，就会重建节点）。

只要这三条成立，`Pending → Streaming → Completed` 只是**同一节点上的数据变化**：不删、不建、不重排、不重播淡入。第 3 条最容易漏，实现时要写成"一行一个 `RowItem(...)`，占位/流式/定稿都调它"。

### 3.2 身份怎么建：位置键 vs 身份键

| 维度 | 位置键 `"msg-$i"`（现状） | 身份键 `"m{dbId}"` / `"u{seq}"` |
|---|---|---|
| 追加一条消息 | 旧 key 全部不变 ✅ | 不变 ✅ |
| 中间删一条 | 后续 key **全部变** → 全员淡入重播 ❌ | 只有被删那条消失 ✅ |
| 撤回（削掉尾巴） | 剩余不变（都是前缀）✅ | 不变 ✅ |
| 换会话 / 从库里重建 | key 集合完全相同 → **不重播** ✅ | 全变 → **全员淡入** ❌（需要显式抑制） |
| 未来分页 / 局部加载 | 下标会漂 ❌ | 天然支持 ✅ |
| 与 DB 的耦合 | 强（下标 ↔ 行号）❌ | 无（按 id 操作）✅ |
| 需要 schema 变更 | 否 | **否**（`messages.id` 已存在） |
| 需要 `clientMessageId` 落库 | 否 | **否**（UI 层 key 只在本会话内存里活） |

**结论：用身份键，但必须配一条"入场动画规则"**（否则换会话会整屏淡入，比现在还差）：

> **只给"上一帧不存在、这一帧新出现"的 key 播入场淡入；其余一律不装 `fadeInSpec`。**
> 换会话 / 从库重建（key 集合整体换掉）时只更新基线，不播动画。
> 这条规则一行等价于现在的 `suppressItemFade` + `sawStreaming` + `justSettled` + `suppressNewestFade` **四个补丁**。

实现上是这样的（约 10 行）：在 `key(selectionEpoch)` **外**用 `remember` 存 `(sessionId, prevKeys)`，`SideEffect` 里更新；渲染时 `animateItem(fadeInSpec = if (newKey) tween(...) else null, placementSpec = …, fadeOutSpec = …)`。

**`clientMessageId` 要不要落库？不要。** 理由：
- UI key 只需要"在一次会话生命周期内稳定"；页面重建/换会话时列表本来就重建（L7 既存行为），没有跨进程的身份需求；
- 一旦落库就要考虑"导入的历史没有 clientId""导出 JSONL 时写不写""旧库升级时怎么补"——都是白送给自己的债；
- 真正需要的"稳定引用"是 **`dbId`**（已有），用来做 `deleteMessageAt` / `rewindToMessage` / `selectSwipe` / 重新生成的目标。

### 3.3 Pending 放在哪：四个候选

| 方案 | 做法 | 风险 |
|---|---|---|
| ① 进 `transcript` | 直接在 `List<ChatMessage>` 里 append 一条空消息 | **最差**：`transcript` 下标 = DB 行号（§1.1），立刻错位；`deleteMessageAt` / `rewind` / `lastSwipes` / 提示词历史全部被污染；还要在落库那一刻"替换"，等于把问题挪个地方 |
| ② 先进 DB 再更新 | 立刻插一行空内容，边生成边 update | 每个 token 一次写盘（不可接受）；进程被杀留下一行半截"幽灵消息"；`send_date` 语义被破坏 |
| ③ 独立 `UiState.pending` 字段 | `val pending: Pending?`，渲染时合成一条"虚拟行" | 可行，但要处理"伪行与真行在 items 里切换"—— 只要 key 接得上，就是同一个节点；**跨会话/返回同一会话时天然保住**（字段在 UiState 里） |
| ④ 统一 `rows` 行模型 | `UiState.rows: List<ChatRow>` + `UiState.inflight` | 最彻底；`transcript` 退化成 getter（`rows.map{...}`），提示词、导出、正则全不动 |

**推荐 ④（配 ③ 的形状）**：`rows` 装"已提交的行"，`inflight` 装"正在生成的那一轮"。占位行不是 `rows` 的成员，而是渲染时由 `inflight` 合成的 —— 这样：
- `transcript`（给提示词/导出/`ConfigSheets` 用）永远是**已提交**的内容，永不污染；
- 换会话时 `rows` 从库里重建、`inflight` 不参与，语义干净；
- 重新生成（就地替换）不需要"删行"，只是 `inflight.replaceTargetId` 指向某行。

### 3.4 数据层风险与必须成立的不变式

| 不变式 | 怎么保证 |
|---|---|
| 占位行不进 `transcript` / 不进 DB | `inflight` 不在 `rows` 里；`transcript = rows.map{…}` |
| 占位行不能被误认为已保存消息 | 只有 `dbId != null` 的行能被删除/撤回/翻版本；`inflight` 行不可操作（`isStreaming` 时这些入口本来就早退） |
| 占位→流式→定稿不重复 | 定稿时把 `inflight.key` **原样**交给新 `ChatRow`，`rows` 只 append 一次；DB 只 `append` 一次（`AtomicBoolean written` 抢票保留） |
| 0 token 停止能干净移除 | `inflight = null`，`rows` 不动；**没有会残留的行** |
| 部分 token 停止保留内容 | 沿用现有语义：普通发送/继续 = 落成一条新助手消息；重新生成 = 更新原行 + 追加 swipe |
| 失败不留孤儿 | `inflight = null` + 撤回刚落的用户消息（按 `dbId`，不按下标） |
| 切会话 | `inflight` 必须与 `sessionId` 绑定；切走 = 取消并丢弃半截（现状，记日志）；切回同一会话 = 恢复 |

---

## 四、方案 B 评估：重新生成就地替换

### 4.1 三种策略

| 策略 | 开场 | 落库/结束 | UX | 架构复杂度 | 动画稳定性 |
|---|---|---|---|---|---|
| **A 保留旧文本 + 遮罩/角标** | 高度不变（不闪） | 新文本到位时**还是要换稿** → 同样的高度突变 | 用户看不到进展（只有角标"重新生成中"），长回复要等十几秒黑箱 | 要引入遮罩层与"何时揭开"的时机 | 开场稳、收尾抖 |
| **B 就地替换（同一行，正文换成占位/流式）** | 若要"立刻看到占位"，高度从 2520 → 80 仍然突变 | 结束只是"正文定稿"，**无增删、无换节点** | 看得到进展；语义与原版一致（旧版本留在 `swipes` 里，可翻回） | **最低**（反而删掉了 `ReplaceTarget.restore`、`lastSwipes` 摘/放两套补丁） | 开场是唯一突变点；其余全稳 |
| **C 现状（删旧行 + 建流式行）** | 高度突变 **+ 节点换代 + 淡入抑制补丁** | 又换一次节点 | 两处突变 | 最高（三个补丁叠着） | 最差 |

**推荐 B**，但要接受它的开场突变（§4.2 说明为什么消不掉）。

### 4.2 为什么"开场收缩"是物理位移、删不掉

反排列表的**底部**是唯一稳定锚点（`firstVisibleItemIndex==0 && offset==0`，也就是"贴底"）。最后一条从 2520px 变 80px：

- 它的**下沿**必须留在视口底部（否则列表底部会露白）；
- 于是它的**上沿**下移 2440px；
- 底部锚定意味着**它上面的一切都跟着下移 2440px**。

要让历史"不动"，只有两个办法：把上沿钉住（那就变成气泡上沿固定在屏幕上方，新气泡沉到屏幕外，读不到）；或者给列表加 2440px 的额外滚动余量（把 `contentPadding.bottom` 临时撑大 —— 那等于把"键盘弹出来"演一遍，观感更怪）。

> 所以：**重新生成的开场位移是结构性的，只能选择"怎么变"，不能选择"不变"。** 上午版"必须动画改高度"的说法在这里被推翻 —— 见 §4.3。

### 4.3 `animateContentSize()` 的适用性（结论：不是第一批该用的）

**它确实能做"高度平滑变化"，但在这个项目里同时踩三条：**

1. **它会包住全部增长**。`animateContentSize` 是持续性的：装上以后**每一次**换行都会变成一段最短 ~100ms 的尺寸动画。而串流跟随的既有设计是"**布局立刻到位、绘制滞后追赶**"（`:263-270`）；两者都改高度 → 同一帧出现两个位移源（一个改布局、一个改 `translationY`），互相打架。**这就是项目已经明令禁止的"多个机制同时控同一种位移"。**
2. **中途撤掉会 snap**。最自然的写法是"只在占位阶段装，首 token 一到就撤"。可是首 token 常在 100–300ms 内到达，而 2440px 的收缩要走 250ms+ —— 撤掉的那一刻剩余高度会**一次性跳完**，比不装还难看。
3. **收缩期间内容是被裁剪的**。`animateContentSize` 只改容器高度，子内容按自然高度测量 → 长文本从下方被裁掉（"卷上去"），不是淡出。这个观感是否可接受，**必须真机看**，不能靠推断。

**它适合的地方（推荐保留为后续小改动）**：**离散的正文替换、且不处于串流状态**——
- `swipe` 切换（2520px ↔ 300px 的两个版本互换）、失败恢复；这些时刻 `isStreaming == false`，桥接不工作，**不会打架**。

**分层位置（如果做实验 E1）**：只加在**该行气泡的容器**（`MessagePanel` 外层那个 `Column`/`Box`），**不要加在 `LazyColumn` 的 item 外层**（那会把"行高"和"条目位置"两件事混在一起），也**不要挂在整列上**（整列没有高度可动画）。并且必须：
- 只在 `inflight.replaceTargetId != null && inflight.text.isEmpty()` 时挂；
- 同一时间把串流桥接**硬关**（`translationY` 强制 0、`smoothHeight` snap、跳过 `dispatchRawDelta`）；
- 首 token 到达时**等收缩动画结束**再开始更新正文（把 token 攒在既有的 `buffer` 里，最多等 ~250ms），否则第 2 条必然发生。

第 4 条代价不小 —— 这就是为什么把它放实验项，而不是第一批。

### 4.4 推荐

**B + 硬切**（第一批）：
- 不删行、不换 key、不换节点（`inflight.replaceTargetId` 指向原行）；
- 开场把正文换成占位（`重新生成中…`），高度**瞬间**到 80px；
- 结束只改正文 + 版本表，`showSwipeBar` 从"隐藏但占位"变"显示"，高度不变；
- 0 token 停止 / 失败 → `inflight = null`，旧正文**从来就没被删过**，所以"放回去"这个动作不存在。

然后（可选，实验 E1）再决定开场收缩要不要动画。**先把"身份 + 状态语义"做对，再谈这一帧好不好看** —— 顺序反过来会像 v161-v164 那样补丁打补丁。

---

## 五、动画与滚动职责

### 5.1 目标职责表（一条位移只有一个主人）

| 位移种类 | 唯一负责人 | 允许的时机 | 明确禁止 |
|---|---|---|---|
| **离散条目插入/移除/重排** | `Modifier.animateItem(placementSpec = …)` | `inflight == null`（没有正在生成的行） | 串流期间挂 `placementSpec` |
| **条目入场/出场** | `animateItem(fadeInSpec/fadeOutSpec)` | 仅"上一帧不存在的新 key"；出场总是允许 | 给"换了 key 的老条目"播淡入（换会话、从库重建、离场重建） |
| **单条正文高度变化（串流）** | 布局自然增长 + **绘制桥接**（`translationY` + `smoothHeight`） | 贴底跟随 / 读气泡自身（`dispatchRawDelta`） | 给串流期间的正文挂 `animateContentSize` |
| **单条正文高度变化（离散替换）** | 布局瞬时完成（第一批）；`animateContentSize` 仅在实验 E1 / swipe 切换 | `inflight == null` 或收缩专用阶段 | 与桥接同时启用 |
| **"该看见最新一条了"** | `LazyListState.animateScrollToItem(0)` | 新行插入且（贴底 或 是自己发的 或 是新一轮生成） | 用 `scrollToItem`（瞬时）替代；在用户读历史时强拉 |
| **撤回/删除后的回底** | `animateScrollToItem(0)`（沿用 `scrollToBottomSignal`） | 一次性信号 | 每次 transcript 变化都回底 |
| **底部让位变化** | `contentPadding.bottom`（输入区/IME/抽屉） | 系统行为，不额外动画 | 由条目逻辑去"补偿"它 |
| **选字焦点滚动** | `NoFocusScrollSpec`（恒 0） | 永远 | 恢复默认 `BringIntoViewSpec` |

### 5.2 三条叠加禁令（写进代码注释，防止下次又叠）

1. **同一帧只允许一条通道改"可见位移"**：`placementSpec` ≠ `animateContentSize` ≠ `translationY` ≠ `scroll*`，串流期间只留 `translationY` + `dispatchRawDelta`。
2. **`animateContentSize` 与桥接互斥**：`animateContentSize` 生效期间，`translationY` 必须为 0 且跳过补偿（因为布局已经在动画，桥接会重复计一次差量）。
3. **`fadeOutSpec` 与"删完立刻加"互斥**：如果一帧内删一条又加一条（今天的落库场景），必须**改成同一 key 的不删不加**；退而求其次（做不到时）把两边的淡入淡出都关掉 —— 不能一边淡出一边淡入。

### 5.3 `reverseLayout` 语义核对（避免下次又猜错）

| 说法 | 核对结果 |
|---|---|
| index 0 = 视觉最底部 | ✅ |
| `firstVisibleItemIndex==0 && offset==0` = 贴底 | ✅（串流时条目长高**不会**改变 offset，日志 `模式=A idx=0 off=0` 可证）【证】 |
| 条目变高 → 上面的条目上移 | ✅（跟随桥接就是为此存在） |
| 条目变矮（最后一条）→ 上面的条目下移 | ✅（§4.2） |
| 插到 index 0 且 key 稳定 → **锚住旧条目**，新条目落到视口外 | ✅（日志 `模式=B idx=1 off=0`）【证】 |
| `scrollToItem(0)` 在反排里 = 回到底部 | ✅ |
| `contentPadding.bottom` 变大 → 内容整体上移 | ✅（C4 通道） |
| 条目高过视口时 `firstVisibleItemIndex` 仍是 0、offset 变正 | 【验】当前日志里没抓到这种样本；如果成立，`atBottom` 会误判成"在读气泡"，跟随会切到场景 C（补偿而非跟随）—— 需真机打点确认 |

### 5.4 阅读位置保护（用户读历史时不许被拉走）

现在的规则是 `if (atBottom || mine) scrollToItem(0)`，其中 `mine` = 最后一条是用户消息。目标修订：

| 事件 | 用户贴底 | 用户读历史 |
|---|---|---|
| 自己发送 / 继续 / 重新生成 | `animateScrollToItem(0)` | **也回底**（"我按了按钮，我要看见结果"）—— 与现状一致，但改成动画 |
| 首 token | 无动作（占位行已在视口内） | 无动作 |
| 串流增长 | 桥接跟随 | 场景 C 补偿 / 场景 B 不动 |
| 落库定稿 | 无动作（同一行） | 无动作 |
| 停止（部分内容） | 无动作 | 无动作 |
| 0 token 停止（移除占位行） | 无动作（行很小） | 无动作 |
| 撤回 | `animateScrollToItem(0)` | 同（现有 `scrollToBottomSignal`） |
| 新会话 / 换会话 | 回底（新 composition，天然） | 同 |

---

## 六、推荐的 UI 行模型与状态机

### 6.1 为什么不采用"每行一个 `MessageStatus`"

任务单里的参考模型（`MessageUi(id, role, content, status, isRegenerating)`）有一条**结构性缺陷**：它允许

- 两行同时 `Streaming`（本应用一个会话只能有一路流）；
- `status=Streaming` 的不是最后一行（串流永远只发生在最新一条）；
- `isRegenerating=true` 但 `status=Completed`（组合非法）。

把"正在生成"表达成**每行的属性**，就等于把"至多一个、且必须是最后一条"这条不变式交给调用方去维护。用**一个单例字段 + 渲染时合成**，非法态根本表达不出来。这是"约束优于约定"。

### 6.2 具体结构（`:app` 层，不进 `core`、不进 DB）

```kotlin
// app/src/main/kotlin/app/tavern/ChatViewModel.kt（UiState 旁边）
/** 一条**已提交**的消息行。key 是稳定身份；dbId 为 null 只在"刚落库、还没拿到 id"的一瞬。 */
data class ChatRow(
    val key: String,          // "m{dbId}"（库里来的）/ "u{seq}"（本次新建的用户消息）/ "p{seq}"（占位定稿后沿用）
    val role: ChatRole,
    val content: String,      // 已定稿正文（提示词、导出、正则都读它）
    val dbId: Long? = null,
)

/**
 * 正在生成的那一轮（**至多一个**）。占位/流式/定稿都靠它 + rows 合成，
 * 所以占位行不需要进 rows、也不需要进 transcript。
 */
data class Inflight(
    val key: String,               // replaceTargetId == null 时，这一轮将来的行 key
    val replaceTargetId: Long?,    // 非 null = 就地改写这条 DB 行（重新生成）
    val text: String,              // 已收到的正文（空 = 还没吐字 → 显示占位）
)

// UiState 里
val rows: List<ChatRow> = emptyList(),
val inflight: Inflight? = null,
// 兼容层：所有读 transcript 的地方（提示词、导出、ConfigSheets、开场白判据）一行都不用改
val transcript: List<ChatMessage> get() = rows.map { ChatMessage(it.role, it.content) },
val isStreaming: Boolean get() = inflight != null,
```

**改动面**：`transcript` 的 19 处**写**点（`ChatViewModel.kt` 里 `transcript = …`）全部改成 `rows = …`（编译器强制，不会漏）；15 处**读**点（`ChatScreen`）改成读 `displayRows`。`ConfigSheets.kt:141` 一处读，走 getter 不用动。

**渲染侧合成（`ChatScreen`，约 15 行）**

```kotlin
val displayRows: List<ChatRow> = remember(state.rows, state.inflight) {
    val f = state.inflight
    when {
        f == null -> state.rows
        f.replaceTargetId != null -> state.rows        // 就地：正文在行内按 shownText 决定
        else -> state.rows + ChatRow(f.key, ChatRole.Assistant, "")
    }
}
items(displayRows.asReversed(), key = { it.key }) { row -> RowItem(row, inflight = …) }
```

`RowItem` 内部：

```kotlin
val isThisInflight = inflight != null &&
    (inflight.key == row.key || (row.dbId != null && row.dbId == inflight.replaceTargetId))
val shownText = when {
    !isThisInflight -> row.content
    inflight.text.isEmpty() && inflight.replaceTargetId != null -> "重新生成中…"
    inflight.text.isEmpty() -> "生成中…"
    else -> inflight.text
}
```

**渲染入口只有这一个**：占位/流式/定稿/重新生成全走 `RowItem`，**不允许**再出现 `item(key="stream")` 这种"另一个出口"。

### 6.3 生命周期表（数据面）

| 事件 | `rows` | `inflight` | key | 内容 | DB |
|---|---|---|---|---|---|
| 发送 | `+` 用户行（`dbId` 暂空，append 成功后补） | `Inflight(key="p{n}", target=null, text="")` | 新行新 key | 用户正文 | `append(user)` 成功后补 `dbId` |
| 继续 | 不动 | `Inflight(new)` | 新 key | — | 不动 |
| 重新生成 | 不动（**不再 dropLast**） | `Inflight(key=原行 key?, target=row.dbId, text="")` | 不变 | 旧正文**保留**（`row.content`） | 不动（**不再提前 `setSwipes`**） |
| 首 token | 不动 | `text += ` | 不变 | 显示 `inflight.text` | 不动 |
| 逐字增长 | 不动 | `text += ` | 不变 | 同上 | 不动 |
| 正常完成（新消息） | `+` 助手行（`key = inflight.key`，`dbId = 新行 id`） | `null` | **沿用** | = 最终文本 | `append(assistant)` |
| 正常完成（重新生成） | 该行 `content = 新文本` | `null` | 不变 | 新文本 | `updateMessage` + `setSwipes(base + text)` |
| 停止（部分，新消息） | `+` 助手行 | `null` | 沿用 | 半截文本 | `append(assistant, partial)` |
| 停止（部分，重新生成） | 该行 `content = 半截` | `null` | 不变 | 半截 | `updateMessage` + `setSwipes(base + partial)` |
| 停止（0 字，新消息） | 不动（用户行留着） | `null` | — | — | 不写 |
| 停止（0 字，重新生成） | **完全不动** | `null` | 不变 | 旧正文自动回来 | **不写**（旧代码要 `refreshSessions` + restore） |
| 完成/失败，0 字 | 不动 | `null` | — | — | 不写 |
| 失败（新消息） | `-` 刚加的**用户行** | `null` | — | — | `deleteMessage(userId)` + 还原输入框 |
| 失败（重新生成） | **完全不动** | `null` | 不变 | 旧正文自动回来 | 不写 |
| 删除单条 | `-` 该行（按 `dbId`） | `null`（不允许在生成中） | 其余不变 | — | `deleteMessage(dbId)` |
| 撤回 | `-` 该行及之后（按 `dbId` 列表） | `null` | 其余不变 | — | 逐个 `deleteMessage` |
| swipe 切换 | 末行 `content` 换 | `null` | 不变 | 另一版 | `updateMessage` |
| 换会话 / 重载 | 从库里重建 | 保留（同一会话）/ 丢弃（别的会话） | `m{dbId}` | — | — |
| 导入会话 | 从库里重建 | 无 | `m{dbId}` | — | — |

### 6.4 生命周期表（表现面）

| 事件 | 高度 | 动画 | 滚动 | 备注 |
|---|---|---|---|---|
| 发送/继续 | `+` 一条 48dp 占位 | `fadeIn`（新 key）+ `animateScrollToItem(0)` | 必回底 | C4 通道同时起步（见 §2.2-L1） |
| 重新生成开场 | **2520 → 80**（突变） | **无**（第一批硬切） | 无（已在底部） | 实验 E1 再谈动画 |
| 首 token | 80 → 自然（≈80 起） | 无 | 无 | 渲染入口不换 |
| 逐字 | 自然增长 | 桥接 `translationY` | 贴底跟随 | 禁 placement |
| 落库定稿 | **不变**（行内动作行 34dp + 版本条 40dp **已占位**） | 无 | 无 | 光标改非布局（见 6.5） |
| 停止（0 字） | `-` 48dp | `fadeOut` | 无 | 行本来就小 |
| 停止（部分） | 不变 | 无 | 无 | |
| 失败（新消息） | `-` 用户行 + 占位 | `fadeOut` | 无 | 已删的消息不淡出更稳，可接受 |
| 删除 / 撤回 | `-` 若干行 | `placementSpec`（非串流）+ `fadeOut` | 撤回后回底 | 身份键保证其余行不动 |
| swipe 切换 | 变（可能很大） | 实验 E2：`animateContentSize` | 无 | 非串流，桥接不工作 → 安全 |
| 换会话 | 全换 | **零动画**（key 集合整体换掉） | 回底 | 见 §3.2 的入场规则 |

### 6.5 落库那一帧"高度守恒"清单（必须逐项对上）

| 项 | 串流中 | 定稿后 | 怎么守恒 |
|---|---|---|---|
| 正文 | `inflight.text` | 同一字符串 | 同一个 `Text` 节点、同一字符串 → **不重排** |
| 流式光标 | 有 | 无 | 改成**固定尺寸的 `Box`**（Canvas 画竖条，非激活时不画）→ 布局宽度恒定 |
| 动作行 | 空占位 34dp | 真动作行 34dp | 行内常驻，只是"空 ↔ 内容" |
| 版本条（重新生成） | 空占位 40dp | `‹2/2›` 40dp | 同上；是否出现由 `lastSwipes.size > 1` 决定，**生成中就已知** |
| 开场白翻页条 | 不适用 | 不适用 | 只在"没有用户消息"时出现，与串流互斥 |
| `translationY` 残值 | 可能非 0 | 必须 0 | 落库那一帧**先 snap** `smoothHeight = streamHeight` 再归零，别让残值跳 |

### 6.6 非法状态与竞态（含两个现有真 bug）

| # | 情况 | 现状 | 目标 |
|---|---|---|---|
| R1 | `finish()` 里 DB 写抛异常 | `finish` 已经用 `written` 抢过票，异常逃到外层 `catch` → `fail()`（**`fail` 不检查 `written`**）→ 走"撤回用户消息 + 还原输入框"，regenerate 分支还会把 `restore` 塞回界面 → **UI 与 DB 不一致**（库里是新文本，界面是旧的） | `fail` 增加 `if (written.get()) return`；`finish` 内部 `runCatching` 后按"已完成的正文保留在界面上、只把 DB 失败记日志 + 提示"处理 |
| R2 | `stop()` 非重新生成分支：**先改 UI，再异步写 DB** | 写库失败（或进程在中间被杀）→ 界面上有这条、库里没有；重启后它消失 | 反过来：先 DB 后 UI（或 UI 先上、失败则回滚并提示），并打日志 |
| R3 | 切会话时取消上一轮 | 半截内容**被丢弃**（与 stop 语义不同），且若取消发生在 `Completed` 与 `finish` 之间，旧轮可能仍写一条 | 保留"丢弃半截"的产品决定，但 `finish`/`stop` 前检查 `job.isActive` 与本轮 id；切走时明确 `inflight = null` 并记 `tag=chat` |
| R4 | `beginCompletion` 取消上一轮 | `streaming = null` 后旧 job 仍可能写库 | 每轮带 `roundId`；所有 `_state.update` 与 DB 写都校验 `roundId == current` |
| R5 | 生成中删除/撤回/翻版本/发送 | 都有早退，安全 | 保持；额外把"操作入口"改成按 `dbId` 而不是下标，杜绝"行数变了但是按下标找行" |

---

## 七、最终方案（做什么 / 不做什么）

### 7.1 做

1. **行模型**：`UiState.rows: List<ChatRow>` + `UiState.inflight: Inflight?`，`transcript` 降级为 getter。
2. **身份键**：`"m{dbId}"` / `"u{seq}"` / `"p{seq}"`；落库后**不换 key**；配"只给新 key 播淡入"规则。
3. **占位行**：发送/继续/重新生成当场进 `inflight`，由渲染侧合成一条行；文案 `生成中…` / `重新生成中…`。
4. **重新生成就地替换**：不 drop、不删、不提前写 `setSwipes`；0 字/失败 = `inflight = null`，旧正文从没离开过。
5. **滚动策略**：新行出现 → `animateScrollToItem(0)`；落库 → 不滚；读历史 → 不滚（自己发的除外）。
6. **行内占位**：动作行 34dp / 版本条 40dp 在生成中就**常驻占位**，不用 `Spacer` 技巧、不用 `streamingRegenerate` 布尔量。
7. **光标改非布局**：固定尺寸 `Box` + Canvas，激活与否只改绘制。
8. **删掉**：`ReplaceTarget`、`lastSwipes` 的摘/放、`justSettled`/`suppressNewestFade`/`suppressItemFade`、`streamingRegenerate`、`item(key="stream")`。
9. **按 `dbId` 操作**：`deleteMessageAt` / `rewindToMessage` / `selectSwipe` / 重新生成目标 → 全部改成按 id，彻底解除"下标 ↔ 行号"耦合（顺带解决审计 P1-5 的一半）。

### 7.2 不做（明确）

| 不做 | 理由 |
|---|---|
| 给 DB 加 `client_message_id` 列 | UI key 只需会话内稳定；加列要迁移、要处理导入历史、要处理导出，纯负债 |
| 串流期间开 `placementSpec` | 每帧重放，必然抖（v161 已验证） |
| 用 `animateContentSize` 承载串流增长 | 与桥接抢同一位移（§4.3） |
| 把占位行写进 `transcript` 或 DB | 立刻破坏下标/行号 1:1，且提示词历史被污染 |
| 在 `LazyColumn` 外层包 `AnimatedContent` / `Crossfade` 换列表 | 会重新量整列（`ui-invariants` INV 与 `PageStack` 头部都记着 AnimatedContent 的坑） |
| 改 `reverseLayout` 或改成"正序 + 手动跟底" | AGENTS 明令禁止；每 token 触发重排 → 整屏抽动 |
| 引入新的动画/滚动框架 | 现有四件套（`animateItem` / 桥接 / `dispatchRawDelta` / `animateScrollToItem`）已经够用，缺的是职责划分 |
| 现在就把"任意消息可翻版本"一起做 | 用户已定"待定"，且它会把 swipe 的作用域从"最后一条"扩大到每行，与本重构叠加风险 |

---

## 八、实施拆分（四批，每批一个 APK + 一份验证文件）

> 依赖是**串行**的：P1 是地基（纯结构，不改观感），P2/P3 各自独立但都在 P1 之上，P4 收口。
> 每批都要求：`check-core-purity` / `check-kotlin-comments` / `check-kotlin-imports` / `check-ui-map` /
> `check-ui-invariants` / `test-core.sh` 全绿 + CI 出包 + 真机回执，**中途不插小补丁**。

| 批 | 目标 | 主要改动 | 行数估计 | 回归风险 | 回滚 |
|---|---|---|---|---|---|
| **P1 行模型地基** | `rows`/`inflight` 顶替 `transcript`/`streamingText`/`isStreaming`；**观感不变** | `ChatViewModel`（19 处写点 + `stop`/`finish`/`fail` 三分支重写）、`ChatScreen`（渲染入口收敛成一个 `RowItem`）、新增 `tag=row` 日志（key/状态迁移） | 200–280 | **高**（发送/停止/继续/重新生成的每一条路径都被摸到） | 整包回退（无 schema 变更，可原地回退） |
| **P2 占位行** | 发送/继续/重新生成立刻出气泡；`animateScrollToItem` 回底 | `ChatScreen`（合成 `displayRows`、占位文案、滚动策略）、`ChatViewModel`（`inflight` 建立点）、`ui-map.md` 新词条 | 60–100 | 中（首次出现"空行"路径） | 独立提交，可单独回退 |
| **P3 重新生成就地替换 + 高度守恒** | 去掉 `ReplaceTarget`/摘放 swipe；动作行与版本条常驻占位；光标改非绘制 | `ChatViewModel`（`regenerateLast`/`stop`/`finish`）、`ChatScreen`（`RowItem` 内部预留、`StreamingCursor`） | 80–140 | 中（停止语义是用户最敏感的地方） | 独立提交 |
| **P4 动画收口** | `placementSpec` 只在非串流时挂；删除/撤回走位移动画；入场规则上线；`translationY` 落库前 snap | `ChatScreen`（`itemAnim` 计算、`prevKeys`/`sessionId` 基线）、`ChatViewModel`（落库帧对齐） | 40–80 | 低 | 独立提交 |

**每批的真机验收只看一件事**（其余是回归）：

| 批 | 唯一新增观感 |
|---|---|
| P1 | 什么都不该变（若变了，就是 P1 引入了 bug） |
| P2 | 点发送/继续/重新生成，**立刻**出一个气泡并滚到底 |
| P3 | 重新生成时**同一个气泡**变回小尺寸再长出来；停止/落库不跳 |
| P4 | 删一条/撤回到某处，其余气泡**滑动**归位而不是闪 |

**日志（唯一判据）**：新增 `tag=row`，每轮只记**状态迁移**，不记每帧：

```
row 轮次=r12 target=new key=p7 起 → 首字(text=1) → 定稿(row=p7 text=1382) 
row 轮次=r13 target=m42(重新生成) 起 → 首字 → 停止(0字, 旧正文回位)
row 会话=s1 重建 rows=37 keys=m11..m47（入场动画=关）
scroll 回底 原因=新行 key=p7 距离=612px（动画）
```

配合既有的 `tag=scroll`（模式 A/B/C、`气泡高`、`顶Y`、`平滑差`），"落库那一帧高度有没有变"、"有没有第 2 次回底"就都能从日志判定，**不靠"我看着像不闪"**。

---

## 九、验证清单（实施后逐条打勾）

> 每条都写**判据**；能自动判的写日志/脚本判据，只能眼判的写"看什么"。

### A. 首次发送（P2）

- [ ] 点发送 → **同一帧**出现占位气泡（`生成中…`），并在 `Motion.Slow` 量级内滚到底（不是瞬移）
- [ ] 占位气泡在输入胶囊**上方**，没有沉到输入区下面（`tag=scroll` 里不应再出现"新行落在 idx=1 而 idx=0 是占位"）
- [ ] 首 token 到达 → 仍是同一气泡，正文开始增长；`tag=row` 只有一条"起"，没有第二次"起"
- [ ] 串流期间逐字增长时**没有**位移动画（`气泡高` 单调增，`顶Y` 在贴底时始终不变）
- [ ] 从发送到首 token 期间，键盘收起/输入区收回导致的位移**只发生一次**，且方向一致（不来回）

### B. 正常完成 / 落库（P1+P3+P4）

- [ ] 落库前后 **key 不变**（`tag=row` 的"定稿(row=p7)"与"起 key=p7"一致）
- [ ] 落库那一帧列表**不跳**（`气泡高` 前后差 < 8px；`顶Y` 不变）
- [ ] 落库那一帧没有"淡出再淡入"（没有第二条入场动画日志）
- [ ] 动作行出现时高度不变（生成中就有 34dp 空位）
- [ ] 重载（退出聊天再进）后消息条数、顺序、正文完全一致；**没有重复行**
- [ ] 会话列表条数与最后一条预览正确

### C. Stop / Failure（P1+P3）

- [ ] 0 字停止（首 token 前点停止）→ 占位气泡消失，用户消息留着，`tag=row` 记"停止(0字)"
- [ ] 0 字停止后**没有**空行残留；再发一次不会出现两条
- [ ] 部分字停止 → 半截内容成为一条消息，重启后仍在
- [ ] 重新生成中 0 字停止 → **旧正文原样回来**（不是"重新生成中…"），版本条仍可用
- [ ] 断网/坏 Key 失败 → 占位气泡消失、输入框还原、库里没有半条用户消息、会话列表条数不变
- [ ] 失败后立刻重发 → 不出现两条同样的用户消息
- [ ] `transcript` 与 DB 行数一致（导出一次 JSONL，条数对得上）

### D. 重新生成（P3）

- [ ] 生成中该气泡**不消失**、不换位置（`key` 不变，无放置动画）
- [ ] 开场从大篇幅收缩到 `重新生成中…`：**允许**突变，但**不允许**伴随淡入淡出/两次跳动
- [ ] 首 token 起在同一气泡内增长
- [ ] 完成后 `‹2/2›` 出现在**这条**气泡下沿（不是上一条）
- [ ] 翻回上一版能正常显示，正文与高度都对
- [ ] 长回复（>2000px）重新生成：过程中不出现"历史被拉走又弹回"（`顶Y` 只单调变化）
- [ ] 生成中「重新生成」按钮不可点（不能起第二轮）

### E. 列表与滚动边界

- [ ] 贴底时持续跟随（`tag=scroll` 模式=A，无 B/C 抖动）
- [ ] 读历史（滑到中段）时新回复到达**不**把视口拉到底
- [ ] 读历史时自己发送 → 回底（动画）
- [ ] `气泡高 > 视口高` 时贴底判据仍成立（**必测**，§5.3 最后一条是唯一未验证的语义）
- [ ] 连发 5 轮不累积错位、不累积动画残留
- [ ] 删除中间一条 → 其余气泡滑动归位，**没有**整列淡入
- [ ] 撤回 → 目标之后全部消失 + 回底 + 输入框拿到正文
- [ ] 版本条 / 开场白翻页条 / 动作行 三者在各种组合下高度不变
- [ ] 快速「停止 → 重新生成 → 停止」不乱序、不残留、不并发两路
- [ ] 生成中切到别的会话再切回来：气泡还在、正文还在、不重复

### F. 通道隔离（C1/C2/C4）

- [ ] 发送后 `tag=ui 输入区高度` 只出现一次收缩；`tag=scroll` 里没有额外的补偿日志
- [ ] 打开/关闭抽屉（会话配置、模型抽屉）时列表不动（沿用冻结逻辑）
- [ ] 长按选字不移动列表（`偏移=` 不变）
- [ ] 生成中把手机横竖屏切换/返回桌面再回来：不崩、不重复、不丢正文

---

## 十、最终决策

**1. 是否建议实施？**
建议。但不是"再修一次闪烁"，而是**把消息的身份和生命周期的表达方式换掉**：目前"正在生成"是 4 个并列字段（`streamingText`/`isStreaming`/`streamingRegenerate`/`streaming`）+"另一个条目"+"三个抑制补丁"，任何一处没对齐就出一次视觉 bug。这是 v161–v164 四包补丁互相打脸的根源。

**2. 最值得优先解决的根因？**
**落库换代**（每次回复都发生）＞ **首 token 落点在视口外**（几乎每次 AI 回复）＞ 重新生成开场位移（只在按重新生成时）。

**3. 确定要做的**：
行模型与身份键；占位行（`inflight`）；重新生成就地替换；`animateScrollToItem` 取代瞬时回底；动作行/版本条行内常驻占位；光标改非布局；删除/撤回/翻版本改按 `dbId`；入场动画规则取代三个抑制补丁。

**4. 需要实验后决定的**：
- **E1**：重新生成开场的 2520→80 要不要动画（`animateContentSize` 或专用收缩动画），代价是"首 token 前要等收缩结束（≤250ms）"或"收缩被 token 打断"。
- **E2**：`swipe` 切换两版之间的大高度差要不要 `animateContentSize`（非串流、无冲突，低风险高收益，可以先单独上）。
- **E3**：占位文案 `生成中…` vs 带呼吸点（后者违反 Motion 的"只做帮助理解状态变化的动画"，倾向不做）。
- **E4**：`气泡高 > 视口高` 时 `atBottom` 是否仍成立（唯一未验证的核心语义，值得单独打点测一次）。

**5. 现在不该做的**：
加 `client_message_id` 列；串流期间的任何 layout/placement 动画；把 `animateContentSize` 当万能药；把占位行塞进 `transcript`/DB；顺手做"任意消息可翻版本"；顺手做 P1-3 全树重组（`@Immutable` / 气泡文本 `remember`）—— 那会把这个改动的观感变量搅混。

**6. 要用户拍板的两点**（其余我按上面推荐执行）：
1. **重新生成开场**：接受"硬切"（先做 P3，之后再决定 E1），还是必须一次做到"平滑收缩"（接受首 token 最多晚 250ms 出现）？
2. **占位文案**：`生成中…`（静态）/ `重新生成中…`，还是带呼吸点（需要新增组件并同步 `ui-map.md`）？
