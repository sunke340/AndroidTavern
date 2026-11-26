# 第二轮审查：聊天列表重构方案的技术可行性（2026-11-26 午后二轮）

> 对象：`c917c58` 的 [plan-chat-list-flash.md](plan-chat-list-flash.md) + 另一位 AI 的补充意见。
> 纪律：以 `c917c58` 的实际代码为准；不因"上一轮已经这么写了"就认可；标【证】= 代码/日志可读，
> 【推】= 由代码推出，【验】= 必须真机。
> 本轮结论对上一轮方案有 **4 处修订**（§3.6 / §4.3 / §6.3 / §10）和 **1 处事实更正**（§1"缺少的关键问题"第 1 条）。

## 1. 总体评价（先给结论，再给证据）

| 另一位的判断 | 我的判定 | 依据 |
|---|---|---|
| "AI 消息直到生成完成才写库 → Pending/Streaming 没有 DB ID" | **正确【证】** | 用户消息在 `beginCompletion` 的协程里请求**之前**落库（`ChatViewModel.kt:2995-3001`）；回复在 `finish()`（`:2918`）或 `stop()`（`:2122`/`:2163`）。生成期间库里没有这一行 |
| "Pending 用 `key="inflight"`、落库后变 `key="msg-123"` 仍会换 key" | **前提不成立，方向正确** | `c917c58` 的方案已规定"落库后**沿用同一个 key**，只补 `dbId`"（§6.3 生命周期表）。但它指出"需要 UI 层自己的 key"是对的 —— 上一轮把这件事写成 `"m{dbId}"`/`"u{seq}"`/`"p{seq}"` **三种形状**，没有命名为 `uiKey`、也没列不变量，属于**文档缺陷**，必须改（§2.8） |
| 三方案分类（A 预分配主键 / B uiKey / C 提前插临时行） | **分类正确，A 与 C 在本项目不可行【证】** | A：手工指定 id 会破坏 `ORDER BY id ASC`（`SqliteChatRepository.kt:104-110`）与 `MAX(id)` 会话预览（`:35-38`）；C：每个 token 一次写盘、崩了留半截"幽灵行"、`messageCount`/预览/`send_date` 全被污染 |
| `InflightMessage` 带 `generationId` | **正确且必要【证】** | 有具体路径：`stop()` **先 `cancel()` 再 CAS**（`:2079-2082`）→ 取消后 `withContext(IO)` 提交时抛 `CancellationException` → 已落库的回复**不进 UI**；而 `stop()` 已把 `isStreaming` 置 false → 下一轮可以起跑 → 旧轮的 `_state.update` 迟到会把新轮按回 `isStreaming=false` |
| `targetDbId` 足以识别重新生成目标 | **正确【证】** | `messages.id` 是 PK，稳定唯一 |
| `originalContent` 需要放进 inflight | **不必要【推】** | 行模型里旧正文**从未离开 `rows[i].content`**，显示层只在 `inflight.text` 非空时覆盖 → 多存一份就是多一个要同步的真相 |
| 策略 A（先持久化再提交 UI）更易保证一致 | **本项目应选 A（含用户消息）【证】** | 本地 SQLite、单进程、写入毫秒级；没有"网络写"这种必须乐观更新的理由。选 A 可**删掉** "用户消息落库失败 → 回滚 transcript" 这条分支 |
| 不要把主线程同步写库 | **正确（现状已是 IO）** | 全部 `withContext(Dispatchers.IO)` |
| 不要把生成失败与保存失败合并成一个 `fail()` | **正确且必要【证】** | 现在 `finish()` 的 DB 异常会逃到外层 `catch` → `fail()`，而 `fail()` **不检查 `written`**（`:2944`）→ 删掉用户消息 + 还原输入框，但回复可能已写进库 |
| 重新生成开场硬切 | **正确【推】+【验】** | 见 §8 |
| 缺的关键问题（它没提到的） | —— | ①**插入本身会把 `atBottom` 翻成 false**（这才是"AI 气泡不跟底"的机制，见 §6.2）；②`stop()` 的 `cancel()` 会吃掉 `finish()` 的提交（§4.3 R-1）；③`swipe_id` **不落库**，重载时靠 `swipes.indexOf(content)` 恢复（`ChatViewModel.kt:1049`）—— 内容被当成身份用；④`rewindToMessage` 现在是"从库里重建 transcript"（`:3354`），行模型落地后这是**唯一会破坏 key 稳定性**的路径；⑤`key(selectionEpoch)`（`ChatScreen.kt:515`）整层重建与身份键的相互作用；⑥它把"UI 没有 id"和"UI 需要 id"混为一谈 —— 需要的是**组合期内**的稳定 key，不是全局唯一 id |

---

## 2. UI Key 与 DB ID

### 2.1 主键怎么来的
`id INTEGER PRIMARY KEY AUTOINCREMENT`（`TavernDatabase.kt:74`）。自增、不重用、插入时分配。

### 2.2 能否预分配
**不能【证】**，两个理由：
1. 没有 API。`append()` 用 `db.insertOrThrow(...)` 拿返回值（`SqliteChatRepository.kt:262-274`）。要提前拿 id 只能先插一行（= 方案 C）。
2. 就算手工指定 id，也会**破坏两处语义**：`messages()` 的 `ORDER BY id ASC` 就是时间线（`:104-110`）；会话列表用 `MAX(id)` 当"最后一条"（`:35-38`）。id 一旦不再是单调递增，时间线与预览都会乱。

### 2.3 Insert 的真实时机【证】
| 消息 | 何时写 | 位置 |
|---|---|---|
| 用户消息 | `beginCompletion` 里、请求之前（UI 先出现、库里后到，几十毫秒窗口） | `:2995-3015` |
| 助手回复（正常） | `finish()` 的 IO 块 | `:2916-2921` |
| 助手回复（停止） | `stop()` 之后另起协程写 | `:2119-2126`、`:2161-2167` |
| 助手回复（重新生成） | `finish()` 的 `updateMessage` + `setSwipes` | `:2878-2881` |

**所以 Pending / Streaming 阶段，那条回复确实没有 DB id —— 另一位的观察成立。** 但结论不是"要提前拿 id"，而是"**UI key 从一开始就不该由 DB id 决定**"。

### 2.4 ID 在哪丢的
`openSessionInternal`：`stored.map { ChatMessage(role, content) }`（`:1058`）；`rewindToMessage` 同样（`:3354`）。DB 层明明有 id，UI 模型 `ChatMessage(role, content)` 只有两个字段（`core/model/ChatMessage.kt`）。

### 2.5 当前 key 生成
`"msg-" + (state.transcript.lastIndex - index)`（`ChatScreen.kt:608`）—— **位置键**。追加消息时旧 key 不变（所以 v161 修好了"整屏闪"），但中间删一条/撤回后，后续每一条的 key 全变 → `animateItem` 的淡入对它们全部重播。

### 2.6 其它依赖 id / 下标的地方【证】
| 位置 | 依赖 | 备注 |
|---|---|---|
| `deleteMessageAt(index)` | UI 下标 → `stored.getOrNull(index)` | 审计 P1-5 的核心 |
| `rewindToMessage(index)` | UI 下标 → `stored.drop(index)` | 且**以库里剩下的为准重建** |
| `selectSwipe` / `regenerateLast` / `continueLast` | `stored.lastOrNull()` | 这里不按下标，相对安全 |
| `exportChat` | `stored.lastOrNull()?.id` | 给最后一条挂 swipes |
| `openSessionInternal` | `swipes.indexOf(last.content)` | **内容当身份**：两版正文完全相同就恢复错版本（`swipe_id` 根本没落库，属于既有数据模型缺口） |
| `ChatScreen` | `realIndex`（删/撤回入参）、`isLast` | 反排下标换算 |

### 2.7 三方案裁决

| 方案 | 判定 | 理由 |
|---|---|---|
| A 预分配 DB 主键 | ❌ | §2.2：要么先插行（=C），要么手工指定 id 破坏排序/预览语义 |
| B UI 层稳定 key | ✅ **唯一可行** | 不需要 DB 配合 |
| C 生成前插临时行 | ❌ | 每个 token 一次 `update`（写放大）；进程被杀留半截幽灵行；`messageCount`、会话预览、`send_date` 全被污染；失败还要删行 |

### 2.8 要不要 `uiKey`
**要，但它只该是"一个字段"，不是"一套 ID 系统"。**

上一轮的 `ChatRow.key` 实质上**已经是** uiKey，问题只是文档把它描述成 `"m{dbId}"`（像是从 dbId 推出来的）。修订：

```kotlin
data class ChatRow(
    /** **UI 身份**：行创建时分配，此后永不改变。不是 dbId 的函数。 */
    val uiKey: String,        // "m{dbId}"（库里读出来的）/ "u{seq}"（本次发的用户消息）/ "p{seq}"（占位行）
    val role: ChatRole,
    val content: String,      // 已定稿正文
    val dbId: Long? = null,   // 落库后才有；只用于 DB 操作与"是否已落库"的判断
)
```

**五条不变量（写进代码注释，日后改这里必须先读）：**

| # | 不变量 | 为什么 |
|---|---|---|
| K1 | `uiKey` 在行的存活期内**永不改变**（补 `dbId`、换正文、切 swipe、定稿都不变） | 否则 LazyColumn 视作新条目 → 重播淡入 |
| K2 | `uiKey` 只要求在**一次 ChatScreen 组合的存活期内**唯一 | 跨组合不要求 → 不需要持久化 |
| K3 | 从库里读出来的行用 `"m{dbId}"`；本次新建的用 `"u{seq}"`/`"p{seq}"`；**落库后继续用原来的"p{seq}"** | 三条命名空间不重叠，永不撞 key |
| K4 | **组合存活期内不得从库里重建 `rows`**；撤回/删除改成"按 `dbId` 删 + 就地移除行" | 重建会让本次新建的行换 key（K1 的唯一破坏点）。当前 `rewindToMessage` 正是重建（`:3354`），必须改 |
| K5 | `dbId` 不参与 key；`dbId == null` 只表示"还没落库" | 让"占位行不可被删/撤回/翻版本"成为**类型事实**而非约定 |

### 2.9 内存还是持久化
**内存【推】**。理由：K2 —— 需求只是"组合期内稳定"；页面离场时整层销毁、回来时从库重建，此时按 `"m{dbId}"` 重新命名是**无观感代价**的（首帧本来就没有入场动画）。持久化会引入导入/导出/迁移三处新问题，纯负债。

### 2.10 重载后的身份映射
**不需要映射表。** 重载 = 整批换 key（`"m{dbId}"`），只要重载发生在"页面重建"时，就没人在看旧 key。需要守住的不是"映射"，而是 K4（不得在组合存活期重载）。

> `swipe_id` 不落库、重载靠 `indexOf(content)` 恢复（§2.6）是**另一条既有缺陷**，与本轮重构无关；建议单独记债，不要塞进 P1。

---

## 3. `rows + inflight` 单例

### 3.1 只有一个生成任务吗
**是【证】**：`beginCompletion` 是唯一入口（`send`/`regenerateLast`/`continueLast` 三处调用）；`send`（`:2778`）、`regenerateLast`（`:3169`）、`continueLast`（`:3230`）、`deleteMessageAt`（`:3295`）、`rewindToMessage`（`:3333`）、`selectSwipe`（`:3269`）在 `isStreaming` 时早退。

### 3.2 约束在哪保证
`isStreaming`（**UI 状态**）+ `streaming`（**任务所有权**）两处，但两者**不同步**：
`stop()` 把 `isStreaming` 置 false 是**立刻**的，而 `active.job` 可能还在 `finish()` 的 IO 里。所以"一次只有一轮"是**软约束**。

### 3.3 快速 Stop → Regenerate
**可以并发出竞态【证】**（路径见 §4.3 R-1）。这是必须加 `roundId` 的唯一理由，不是"理论上更安全"。

### 3.4 旧 Token 延迟到达
**不会在 stop 之后更新 UI【推】**：`viewModelScope` 默认 `Main.immediate`，token 回调与 `stop()` 都在主线程串行，取消发生在 `collect` 的下一次挂起点。真正的风险不是 token，是**旧轮的收尾提交**（§4.3）。

### 3.5 需要 `generationId` 吗
**需要**，但叫 `roundId: Long` 更贴合本项目（`StreamingTask` 里已有 `sessionId`/`written`，再加一个自增轮次号）。用途只有一条：

> **一切 `_state.update` 与"提交行"的操作，先校验 `state.inflight?.roundId == myRoundId`。**

### 3.6 `targetDbId` 够不够 / 要不要 `originalContent`
- `targetDbId` **够**（PK 稳定）。额外语义用 `targetDbId != null` 表达"这一轮是就地改写"。
- `originalContent` **不要**（§5）。
- **本轮修订 §3.6**：`inflight` 必须同时带 `sessionId`（切会话后到达的收尾不该污染新会话）—— 上一轮只写了 `key/target/text`，漏了这条。

```kotlin
data class Inflight(
    val roundId: Long,          // 本轮唯一；旧轮的迟到提交一律丢弃
    val sessionId: String,      // 归属会话
    val uiKey: String,          // targetDbId == null 时，这一轮将来的行 key
    val targetDbId: Long?,      // 非 null = 就地改写这一行（重新生成）
    val text: String,           // 已收到的正文（空 = 还没吐字）
)
```

### 3.7 inflight 归谁
**ViewModel（`UiState` 字段）【推】**。Repository 是纯存储，不该知道"正在生成"；生成任务层只有 job，不该持有 UI 语义。放 UiState 还顺带解决"切走后切回同一会话要保留生成态"（`openSessionInternal` 已经为此写了 `streaming?.sessionId == id` 的判断，可删）。

### 3.8 合成顺序
`rows` 是时间线（旧→新）；合成行只可能在**末尾**：
- `targetDbId == null`：`displayRows = rows + 合成行`；
- `targetDbId != null`：`displayRows = rows`（正文在行内按 `shownText` 覆盖）。
渲染永远 `displayRows.asReversed()`。**永不插入中间、永不排序** → 顺序错误在类型上不可能发生。

### 3.9 风险
| 风险 | 是否可能 | 防法 |
|---|---|---|
| inflight 指向不存在的行 | 可能（K4 被破坏时） | 合成时 `rows.any { it.dbId == targetDbId }`，否则降级为"新行"并打 `tag=row` 警告 |
| 重复显示 | 不可能 | K3 + 只在末尾合成 |
| 顺序错 | 不可能 | §3.8 |
| 两个 inflight | 不可能（单字段） | —— |

### 3.10 "单例只是把非法态搬到别处？"
**正面回答：不是等价搬迁，但也不是免费。**
- 消除的：两行同时生成、生成的不是最后一行、`isRegenerating=true` 却 `Completed` —— 这些**表达不出来**了。
- 新引入的（必须显式校验的）：`inflight` 与 `sessionId` 不匹配、`targetDbId` 悬空、`inflight` 还在但 job 已死（`stop` 之后忘了清）。
- 净判断：**单例 + 3 条校验** 比 "每行 status + 不变式靠约定" 强。校验点只有三个：起轮（写）、收轮（清）、合成（读）。

---

## 4. Stop / Finish / Regenerate 的一致性

### 4.1 十个问题

| # | 问题 | 答案 |
|---|---|---|
| 1 | `finish()` 与 `stop()` 会同时执行吗 | **会【证】**：`finish` 在 `withContext(IO)` 挂起期间主线程可跑 `stop()`。两者靠 CAS 抢"谁写库"，但 `stop` **先 `cancel()` 后 CAS**（`:2079-2082`）→ 见 R-1 |
| 2 | Stop 之后迟到的 token 会更新 UI 吗 | **不会【推】**（主线程串行）；但**旧轮的收尾提交**会（R-1） |
| 3 | 旧 round 会改新 round 吗 | **会【证】**：`stop` 失败分支把 `isStreaming=false` 写死（`:2084-2090`）→ 新轮起跑 → 旧轮 `finish` 的 `_state.update`（`:2922`）迟到，把 `isStreaming` 又置 false 并把旧正文 append 进 `transcript` |
| 4 | 同一条消息会重复写入吗 | **不会**（CAS）；但 `retry-able` 的用户消息会因"失败删除没成功"在重载后复活（R-3） |
| 5 | DB 异常会触发错误撤回吗 | **会【证】**（R-2） |
| 6 | 重新生成的旧正文会丢吗 | 现状不会（`restore` 兜着）；行模型后**结构上不可能丢**（正文没离开 `rows`） |
| 7 | 用户消息与回复要同一事务吗 | **不要【推】**。用户消息先落库的价值是"进程被杀也保住我说了什么"；合并事务就得把回复攒在内存里到写完，反而丢耐久性。保持两次写，但要**幂等 + 失败语义分开** |
| 8 | 写库失败后保留 UI 还是回滚 | **保存失败：保留 UI、不删任何已落库的东西、提示"未保存"**（回复丢了可以重生成；删掉用户的提问是数据损失）。生成失败：维持现有"当作没发过"（删用户消息 + 还原输入框），但**删除失败要如实提示** |
| 9 | 进程被杀 | 用户消息在库里（已保证）；半截回复丢（现状；不要为此做定时 checkpoint，写放大不值）。**重新生成期间库一行都没动**（若不再提前写 `setSwipes`）→ 崩溃后旧回复完好 |
| 10 | 要不要显式提交状态 | **不要引入 `Committing` 状态**。`written` + `roundId` + "先落库后提交 UI" 已经让"提交"是一个**同步点**，再加一个状态就是第四份真相 |

### 4.2 策略 A vs B（按本项目选）

| | 策略 A 先持久化再提交 UI | 策略 B 乐观 UI + 异步落库 |
|---|---|---|
| 本项目成本 | 本地 SQLite，`append` 一个事务 + 一次 update，毫秒级；用户感知不到 | 需要回滚、需要"待保存"状态、崩溃后可能与库不一致 |
| 删掉的代码 | 用户消息失败回滚分支（`:3001-3013`）、回复的"先把界面摆好再写库"顺序（`:2100` 起） | —— |
| 选它 | ✅ **用户消息 + 回复都选 A** | ❌ |

唯一保留的"补偿"是**生成失败时撤销用户消息**（产品语义：当作没发过），它必须写成"删除成功才移除 UI 行，删除失败保留行并提示"。

### 4.3 三个真 bug（本轮新发现）

**R-1 `stop()` 会吃掉 `finish()` 的提交【证】**
```
stop(): streaming=null → active.job.cancel() → CAS 失败 → 界面置"已停止"
finish(): 已 CAS 成功 → withContext(IO){ append(...) } ← 写入已完成
          → withContext 返回时检查到已取消 → 抛 CancellationException
          → refreshSessions() / _state.update 都不再执行
结果：回复在库里，界面没有；用户看到"已停止 + 一条回复凭空消失"，重进会话又出现
```
修法（三条一起）：① `stop()` **先看 `written`，已写就不 cancel**；② `finish()`/`stop()` 的**提交块包 `withContext(NonCancellable)`**；③ 提交前校验 `roundId`。

**R-2 `finish()` 的 DB 异常落进 `fail()`，会删掉用户的提问【证】**
`fail()` 不检查 `written`（`:2944`）。修法：`fail` 首行 `if (written.get()) return`；`finish` 内部 `runCatching` 后走"保存失败"分支（§4.1-#8）。

**R-3 失败撤销是"尽力而为"却当成功用【证】**
`fail()` 里 `runCatching { chatRepository.deleteMessage(it) }`（`:2967`）吞掉异常，但界面照样 `dropLast(1)` + 还原输入框 → 重载后这条用户消息又会回来。

---

## 5. 重新生成时旧正文的保存 / 替换 / 恢复 / 提交

| 问题 | 答案 |
|---|---|
| 1 旧正文现在存在哪 | 三处：`transcript` 的 `ChatMessage`、`messages.content`、`message_swipes`；`ReplaceTarget.restore` 是**第四份**（内存快照） |
| 2 能否完全移除 `restore` | **能，且应该**（行模型落地后） |
| 3 那 0 token 停止怎么办 | **什么都不用做**：`inflight = null`，那一行本来就还显示着 `content`（旧正文）。现状要 restore 是因为它把旧行从列表里删了 |
| 4 新 token 何时可以丢弃旧正文 | **永不丢弃**（在内存里保留到定稿那一刻才 `content = 新文本`）；旧版本仍留在 `message_swipes` |
| 5 DB 保存失败恢复旧还是保留新 | **保留旧正文 + 提示"保存失败"**（新正文丢了可以重生成；把库里已有的旧正文覆盖成未保存的内容，是主动制造不一致）。因为提交在写库成功之后，这一步天然成立 |
| 6 生成中再点 Stop / Regenerate | Stop 正常（锁此轮）；Regenerate 被 `isStreaming` 挡住。注意 P3 之后**不要**放宽这条 |
| 7 inflight 要不要 `originalContent` | **不要**（§3.6） |
| 8 要不要在 DB 留版本记录 | **已经有** `message_swipes`。还缺的是 `swipe_id` 落库（§2.10），单独记债 |

**最简单可靠的生命周期**：
```
Completed(旧正文在 content 与 swipes[0..n-1])
  → Regenerating: inflight{targetDbId=row, text=""}   // DB 一行不动，显示"重新生成中…"
  → Streaming:    inflight{text=…}                    // 显示 inflight.text
  → 定稿: 先 updateMessage + setSwipes(base+text) 成功 → 再 content=text, inflight=null
  → 0 字/失败/停止0字: inflight=null                    // 旧正文原样，DB 从未变
```
崩溃安全性：**重新生成期间不改 DB** → 杀进程后旧回复完好，用户只是"重生成没发生"。

---

## 6. 滚动跟随与 `atBottom`

### 6.1 当前判据到底表示什么【证】
```
atBottom      = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
readingBubble = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset > 0
（其余）      = firstVisibleItemIndex > 0
```
反排语义：`offset` 是"越过最新一条下沿、滚进它内部的量"；`index > 0` 是"整条滚过头、在看更早的"。四种状态（气泡底部/中间/顶部/历史）落到这三档，语义**是对的**。

### 6.2 上一轮漏了一个机制：**插入本身就把 `atBottom` 变成 false**
```
t0 发送：transcript.size+1（用户消息插到 index 0）
        → 稳定 key 锚住旧条目 → firstVisibleItemIndex 变 1 → atBottom=false
然后 LaunchedEffect(transcript.size) 触发（size 变了）→ mine=true → scrollToItem(0) → 回到 A
t1 首 token：item(key="stream") 插入到 index 0
        → 又变 index 1 → atBottom=false
        → 但 transcript.size 没变 → 那个 LaunchedEffect 不会重跑 → 没有任何人把它滚回去
结果：整个串流期间都是模式 B（不跟随、不补偿），气泡在输入框下面长
```
**这就是"AI 气泡不跟底 / 掉到输入框下面"的完整机制**（日志 `模式=B idx=1 off=0` 正是 t1），也是"回到底部"钮在生成时冒出来的原因（`AnimatedVisibility(!atBottom)`，`ChatScreen.kt:413`）。

**这不是判据错，是"用布局事实当用户意图"的结构错误**：`atBottom` 会被我们自己的插入翻转。修正有两条，**必须选一条**（本轮修订）：

| 方案 | 做法 | 评价 |
|---|---|---|
| ① 让插入不改变"是否跟随" | 加 `followBottom`（用户意图）与 `atBottom`（布局事实）两个量：只有**用户滚动**才改 `followBottom`；插入/追加一律按 `followBottom` 决定是否回底 | 最正确，但要能区分"用户滚动"和"程序滚动" → 需要 `listState.isScrollInProgress` + 记录最后一次程序滚动的时刻，复杂 |
| ② **让插入发生在"自己发的"那一刻** | 占位行与用户消息**在同一次状态更新里**出现 → 插入只有一次，且落在 `mine` 分支 → 已经会回底；首 token 不再是插入（同一行）→ 不翻转 | ✅ **随行模型自然获得**，不需要新状态 |

**选 ②**。代价是一条硬约束：**用户行与占位行必须在同一次 `_state.update` 里出现**（拆成两次会多一次插入 → 多一次翻转）。

### 6.3 回底的方式（本轮修订）
| 场景 | 方式 |
|---|---|
| 发送/继续/重新生成（新占位行，且插入前贴底） | `scrollToItem(0)` **瞬时**（距离只有一行高，动画反而多一次倒手） |
| 同上，但用户正在翻历史 | `animateScrollToItem(0)` |
| 落库定稿 | 不滚（同一行，高度守恒） |
| 撤回/删除 | `animateScrollToItem(0)`（沿用 `scrollToBottomSignal`） |
| 首 token / 生长 | 不滚，交给桥接 |

### 6.4 `dispatchRawDelta` 用得对不对
- 符号：**已被真机验证**（v112–v120 反复调过，`pitfalls` 有记录），不是推测。
- 累计误差：`acked += moved.coerceAtMost(drift)`，`drift = 高度增量 - 已补`，`moved <= 0` 时不动基线下一笔再试 → 有界、不会越积越多【推】。
- 到边界（offset 已 0）：会切回 `justEnteredBottom` 分支 snap，`translationY` 归零 → 不会留下长期绘制偏差【推】+【验】。
- 只补"量到的高度变化"，手势期间照补 —— 这条是对的，别改。

### 6.5 键盘会不会影响判据
**不会【推】**：IME 改变的是 `contentPadding.bottom`（内容让位），不改变滚动偏移；贴底时 offset 仍是 0。

### 6.6 需要更可靠的判定方案吗
**不需要新状态机**。唯一要补的是 §6.2 的"插入时机"，而不是"更多状态"。真机只需验两件事：①超长气泡长高时 `offset` 是否保持 0（现有日志支持）；②`animateScrollToItem(0)` 落地后 `atBottom` 是否恢复（日志里 `边界 回到贴底` 是现成的观测点）。

---

## 7. IME 与 `contentPadding.bottom`

### 7.1 会不会同帧发生
**同一个事件起点，不同帧展开【证】**：`onSend = { viewModel.send(); focusManager.clearFocus() }`（`ChatScreen.kt:926-929`）→ 第 N 帧：状态更新（插行 + isStreaming）+ 键盘开始下落；N+1…N+20 帧：输入区 300ms 收回一行、IME ~250ms 落下。

### 7.2 会不会多次重新布局
**每帧一次，两个来源**（padding 变化 + 滚动动画）。另外**一处可优化**【证】：`imeVisibleNow` 是在 `GlassDetailScaffold` 的 content lambda 里读的（`:493`），键盘动画期间**每帧都会让整个 content lambda 重组**，进而重建 LazyColumn 的 DSL。建议把它挪进 `snapshotFlow`/`LaunchedEffect` 内部读，别让它成为 content 的重组依赖。

### 7.3 哪些变化真正影响视口
| 变化 | 影响 |
|---|---|
| `contentPadding.bottom` = `max(nav, ime) + 输入区实测高 + 12dp` | ✅ 直接改内容区底部 |
| `inputBarHeightPx`（`onSizeChanged`，`ChatScreen.kt:814-826`） | ✅ 同上（已含在上一行里） |
| `JumpToBottomButton` 的淡入淡出 | ❌ 不进输入区测量（`:407-435`），**故意** |
| 抽屉冻结 `frozenListBottom` | ✅（已治理） |

### 7.4 有没有重复计算
**没有【证】**：输入区被 `windowInsetsPadding(systemBars.bottom ∪ ime)` 抬起（`DetailPage.kt:211-221`），列表底部让位 = `max(nav, ime) + 输入区高`（`:196-199`）—— 一个算"输入区的顶在哪"，一个算"列表内容到哪为止"，相加正确，不是两次 IME。

### 7.5 有没有 IME 重复消费
两处读同一个 inset（列表 + 输入区偏移），这是**设计**（输入区浮在键盘上、列表给它让位）。要防的是两者**不同步**：现在都读 `WindowInsets.ime`，同步。

### 7.6 发送时要不要分阶段协调
**不要。** 两个运动方向一致（键盘落下 = 内容下移；回底 = 内容上移一点点），量级差 20 倍，视觉上只有一个运动。分阶段（等键盘落定再滚）反而会让占位气泡在输入框下面滞留 300ms。

### 7.7 能不能只调 Insets/布局，不加动画补偿
**能，而且应该什么都不加。** "发送后收起输入区"是用户 2026-11-25 明确要的（`app/AGENTS.md`），所以 C4 是**预期内的运动，不是 bug**。最小改动只有两处：
1. `imeVisibleNow` 从 content lambda 里挪走（`ChatScreen.kt:493`，纯性能，无观感变化）；
2. 回底按 §6.3 的方式做（瞬时/动画分档），**不要**给让位变化加任何补偿。

---

## 8. 重新生成硬切

| 问题 | 答案 |
|---|---|
| 1 `animateContentSize` 真会与 `translationY` 冲突吗 | **会【推】**：`animateContentSize` 让**布局**逐帧变化，`translationY` 是"布局已经到位、绘制滞后追赶"。两者同时生效 = 同一个位移被算两次（`translationY = h - smooth` 里的 `h` 本身还在动）|
| 2 有没有局部尺寸动画不影响串流 | **没有**。尺寸动画必然改布局；能"局部且不动布局"的只有绘制（alpha）。而开场收缩的问题恰恰是布局 |
| 3 要不要把收缩与串流分两阶段 | 要，但代价是**首 token 前要等收缩结束（≤250ms）**，而且模型可能 100ms 就吐完 —— 得处理"收缩还没完就已经 Completed"。复杂度不值 |
| 4 硬切真的更稳吗 | **是【推】**。硬切只有一次布局跳变、没有中途打断、不与桥接抢；动画有 3 个失败面（打断/撤销/与桥接叠加）|
| 5 有没有低复杂度替代 | 只有一个：**新气泡（占位）用 120ms 淡入**，高度仍然硬切。纯粹让"小气泡出现"不生硬，3 行代码，不动布局。可选 |

**结论：硬切，不做尺寸动画。** `animateContentSize` 只保留给"非串流状态下的离散正文替换"（`swipe` 切换、失败恢复），作为**实验 E2**，不进第一批。

---

## 9. 四批计划的裁决（本轮修订为五批）

| 问题 | 答案 |
|---|---|
| 1 顺序合理吗 | **基本合理，但少了一批**：DB 一致性修复必须**先做**，它和 UI 架构无关，且当前就在丢数据（R-1/R-2）|
| 2 DB ID / UI Key 哪一批 | **P1**（地基）。但"不改 dbId 当 key"这条决定要在 P0 就写进注释，免得 P0 又拿 `lastIndex` 写新代码 |
| 3 Stop/Finish 一致性哪一批 | **P0**（独立一批，先出包）|
| 4 IME/contentPadding 哪一批 | **P2** 只做"`imeVisibleNow` 挪出 content lambda"这一行；**其余不动**（§7.7）。不单独开批 |
| 5 数据库一致性要不要与 UI 重构分开 | **要**。P0 是 bug 修复（可立即验证：日志 + 反复 stop/regenerate），P1 是结构重写（观感必须不变）|
| 6 每批能否独立编译/运行/回归 | 能。P0 只动 `ChatViewModel` 的 4 个函数；P1 动 UiState 与渲染入口；P2/P3 各自独立 |
| 7 有没有要提前处理的依赖 | 有两条：① P1 之前把 K1–K5 写成注释 + 一个 `newUiKey()` 生成器；② P2 的"用户行与占位行同一次 `_state.update`"必须在 P1 的 `beginCompletion` 改造里就留好位置（否则 P2 要再改一次）|
| 8 要不要独立并发测试 | **要，但只能是"日志驱动的压测"**（本机编不了 `:app`，没有 instrumentation 环境）：连点发送/停止/重新生成 20 轮、断网、切会话，靠 `tag=chat`/`tag=row` 断言"每轮恰好一次提交、没有孤儿行、没有晚到提交" |

### 最终批次

| 批 | 内容 | 观感变化 | 依赖 |
|---|---|---|---|
| **P0 数据一致性** | 提交权仲裁（`AtomicReference<CommitPhase>`，取代 `written`）+ 四类失败语义 + `replaceMessage` 单事务。**定稿设计见 [plan-p0-settle.md](plan-p0-settle.md)**（含 21 条纯 JVM 用例） | 无 | — |
| **P1 行模型地基** | `ChatRow(uiKey, role, content, dbId)` + `Inflight(roundId, sessionId, uiKey, targetDbId, text)`；`rows`/`transcript` getter；`displayRows`；单一 `RowItem` 渲染入口；删除/撤回/翻版本改按 `dbId`；K1–K5 注释 + `newUiKey()` | **零回归**（口径见下） | P0 |
| **P2 占位行 + 回底** | 用户行与占位行同一帧；`scrollToItem`/`animateScrollToItem` 分档；`imeVisibleNow` 挪位；`ui-map.md` 词条 | 发送/继续立刻出气泡并回底；占位气泡带**呼吸点**（§13.2） | P1 |
| **P3 重新生成就地替换 + 高度守恒** | 删 `ReplaceTarget`/`restore`/`lastSwipes` 摘放/`streamingRegenerate`；行内动作行与版本条常驻占位；光标改固定尺寸 `Box` | 同一个气泡**平滑收缩**（`animateContentSize`，§13.1）再长出来 | P1 |
| **P4 动画收口** | `prevKeys` 入场规则（取代 3 个抑制补丁）；`placementSpec` 门控；落库前 `smoothHeight` snap；删除/撤回位移动画；`key(selectionEpoch)` 与身份键的兼容 | 删/撤回滑动归位 | P1–P3 |

实验项（不进批次）：**E2** `swipe` 切换的 `animateContentSize`、**E4** 超长气泡的 `atBottom` 真机打点。
（原 E1 开场收缩、E3 呼吸点**已拍板进批次**：E1 → P3 §13.1，E3 → P2 §13.2。）

**P1「零回归」的口径（2026-11-26 定）**：正常**布局、尺寸、条目顺序、滚动位置、既有功能**无回归。
**不要求**保留旧版本里本来就不正确的行为 —— 落库帧的重播淡入、离场重建的整屏淡入、
"AI 气泡掉到输入框下面" 这些若因身份稳定而消失，算**修掉 bug**，不算回归；
但每条都必须写进 P1 的 verification 文件，并附一行日志证据。

---

## 10. 必须真机验证的事项

| # | 事项 | 步骤 | 判据 |
|---|---|---|---|
| V1 | 超长气泡长高时 offset 保持 0 | 发一条会超过一屏的回复，贴底不动 | `tag=scroll` 始终 `模式=A idx=0 off=0`；`顶Y` 不变 |
| V2 | 插入会把 `atBottom` 翻成 false（§6.2） | 现状包：发消息后不动手指，等首 token | 应该看到 `模式=B idx=1 off=0` + "回到底部"钮出现（**证明诊断**）|
| V3 | 回底落点完整可见 | P2 包：发送后不动 | 占位气泡完整露出、不被输入胶囊压住 |
| V4 | 落库帧高度守恒 | 任意一次回复写完 | `气泡高` 前后差 < 8px；`顶Y` 不变 |
| V5 | 落库帧没有淡入 | 同上，慢放/长按截图 | 新旧气泡之间没有"消失再出现" |
| V6 | 同 key 的 `animateItem` 不重播 | P4 包：连点选字→退出→返回 | 列表不整屏淡入 |
| V7 | 删中间一条只动被删的 | 三条以上，删中间 | 其余气泡**滑动**归位，不淡入 |
| V8 | 停止/落库/键盘 Concurrent | 生成中点停止（趁键盘还在动） | 回复不丢（重进会话仍在）+ 不出现两条 |
| V9 | 20k 字落库不卡帧 | 长回复写完 | 目视无掉帧；`tag=row` 定稿行时间戳与上一帧差 < 32ms |
| V10 | 切会话再切回 | 生成中切走 → 切回 | 气泡与正文都在、不重复、不丢 |

---

## 11. 最终架构（一句话版）

> **`rows`（已提交的行，身份是 `uiKey`，`dbId` 只用于 DB 操作）+ `inflight`（至多一轮，带 `roundId`/`sessionId`/`targetDbId`）+ 合成渲染（占位/流式/定稿同一节点）+ 先落库后提交 UI + 一次插入（发送那一帧）→ 回底 → 桥接跟随。**

四条不变式（其余都是它们的推论）：
1. 行身份在一次组合内不变（K1–K5）；
2. 至多一轮生成，旧轮的提交一律按 `roundId` 丢弃；
3. UI 只显示"已经落库"的行（占位行是唯一例外，它没有 `dbId`）；
4. 一帧只允许一个位移主人（布局增删 / 滚动 / 绘制桥接 / 让位，见上一版 §5）。

---

## 12. 最终决策

| 问题 | 决定 |
|---|---|
| 认可上一轮整体方案吗 | **认可方向，4 处修订**：①`uiKey` 必须独立命名并配 K1–K5；②`inflight` 加 `roundId`/`sessionId`；③新增 P0（一致性）；④回底方式分档（贴底瞬时/读历史动画） |
| 认可 `rows + inflight` 吗 | **认可**（单例 + 3 个校验点；不采用每行 status） |
| 要不要独立 `uiKey` | **要**：一个字段、内存态、不落库；不叫"另一套 ID 系统" |
| DB ID 是最终消息身份吗 | **不是 UI 身份，是持久化身份**。UI key 不能由它推出来（生成中它还不存在）；两者用 `dbId` 关联 |
| 要不要修 Stop/Finish 一致性 | **要，且先做（P0）**：R-1 会丢回复、R-2 会删用户的提问 —— 这是数据问题，不是观感问题 |
| 重新生成开场硬切吗 | **是**。尺寸动画惠而不费，反而有三个失败面 |
| 暂时禁用 `animateContentSize` 吗 | **是**（串流与开场都不用）；`swipe` 切换留作 E2 |
| 暂时不做呼吸动画吗 | **是**（静态 `生成中…` / `重新生成中…`） |
| P1 可以开始实施吗 | **P1 本身可以，但先出 P0**；P1 开工前先落 K1–K5 注释 + `newUiKey()`，且 P1 的验收标准是"**观感零变化**" |

---

## 13. 两个决策点已拍板（2026-11-26 12:2x）

用户的选择与我的推荐相反，按用户的来：

1. **重新生成开场要平滑收缩**（不做硬切）→ 进 **P3**，设计见 §13.1。
2. **占位气泡要呼吸点**（不是静态文案）→ 进 **P2**，设计见 §13.2。

### 13.1 平滑收缩（P3）

四条硬约束，缺一条就会退化成"更难看"：

| # | 约束 | 为什么 |
|---|---|---|
| C1 | `Modifier.animateContentSize(clip = true, animationSpec = tween(260, easing = Motion.Standard))` **只挂在"重新生成那一行"上，并且只在收缩阶段挂着**（`inflight.text.isEmpty() && inflight.targetDbId != null`）；正文一开始增长就**摘掉** | 常驻的话园会把串流期间每一次换行都包一层动画，与"布局自然增长 + 桥接跟随"抢同一个位移 |
| C2 | 收缩期间**新 token 只进 buffer，不推 UI**；收缩结束（≤260ms）后一次性推上去 | 首 token 常在 100–300ms 到达。动画被中途打断 + 摘掉 Modifier = 剩余高度一次性 snap，比硬切还难看 |
| C3 | 收缩期间**桥接三件套全关**（`translationY` 强制 0、不 `dispatchRawDelta`、`smoothHeight` 不追） | 布局正在被动画改，桥接再补就是把同一个位移算两遍 |
| C4 | 0 字停止 / 失败时，`collapsing` **等收尾落地才清**，让旧正文回来也走同一条动画 | 否则"缩下去"是平滑的、"涨回来"是瞬跳的，一对比更刺眼 |

`clip = true` 会生成本地裁剪层（与 INV-02 的 8k px 命中问题同类），但**它只存在 260ms**，
且这 260ms 里那条正文已经是占位（选字/命中都不需要），收尾后 Modifier 必须真的摘掉 ——
所以不构成 INV-02 回归。

**代价（已接受）**：重新生成开场约 260ms 内看不到新字；模型若在 260ms 内就吐完，内容会一次性出现。

### 13.2 呼吸点（P2）

- 新组件 `TypingDots`：三个 6dp 圆点、间距 4dp，透明度 0.25 → 1 交错呼吸（周期 900ms，
  `Motion.Standard`），与占位文案**同一行**：`生成中` + 三点 / `重新生成中` + 三点。
- **减动效时不动**：`rememberReduceMotion()` 读一次，静态三点（项目规矩：减动效统一走它）。
- 这是**状态指示**不是装饰（Motion 三条规矩里属于"帮助理解状态变化"），组件注释里写明。
- 同步 `docs/ui-map.md`：核心组件表 + 词表加"占位气泡 / 呼吸点"，并跑 `tools/check-ui-map.sh`。
