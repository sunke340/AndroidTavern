# P0 定点设计：Stop / Finish 提交权与保存失败语义（2026-11-26）

> 范围：**只做 P0**（[plan-chat-list-flash-r2.md](plan-chat-list-flash-r2.md) §10 的第一批）。
> 不改行模型、不改渲染、不改动画。目标是把"谁有权提交这一轮"变成一个可测的状态机，
> 并让四类失败各自有明确语义。
> 证据级别沿用：**【证】代码可读** /**【推】由代码推出** /**【验】真机或 CI**。

## 0. 结论（三句话）

1. **提交权 = 每轮一个 `AtomicReference<CommitPhase>`**：`Open →(CAS)→ Writing →(set)→ Closed`。
   谁能 CAS 成功谁写库，输家**什么都不做**（连"已停止"都不写）。
2. **`cancel()` 只能在拿到提交权之后调用**。这是 R1 的根因：现在 `stop()` 先 `cancel` 再 CAS
   （`ChatViewModel.kt:2079-2082`），取消会让 `finish()` 已经写完 DB 的 `withContext` 抛
   `CancellationException`，于是 UI 提交被跳过 → 回复在库里、界面没有。
3. **`NonCancellable` 只包"写库 → 提交 UI"这一段**（几毫秒），请求/流式/刷新会话列表全部保持可取消。

---

## 1. 状态转换设计

### 1.1 每轮三个量

| 量 | 类型 | 职责 | 明确不负责 |
|---|---|---|---|
| `CommitPhase` | `AtomicReference<Open/Writing/Closed>` | **提交权归属 + 一次性**（互斥 + 幂等，一个 CAS 同时给出这两件事） | 不知道写什么、不知道界面还归不归它 |
| `roundId` | `Long` | **归属**：UI 事实（状态栏文案、清 `inflight`）只在"我还是当前那一轮"时允许 | 不参与互斥 |
| `sessionId` | `String` | **路由**：行只进它自己那条会话的列表 | 不参与互斥 |
| `SettleReason` | 枚举 `Completed/Stopped/Failed/Cancelled` | **意图**：决定写什么（补一条 / 改写 / 不写 / 删用户消息） | 不是权限 |
| `WriteResult` | `Skipped/Ok(dbid)/Failed(reason)` | **结果**：决定 UI 怎么收 | 不是权限 |

`written` **删除**。它今天同时干三件事（互斥 / 幂等 / "已落库"标记），是 R1、R2 的共同来源。

### 1.2 相位图

```
                        ┌──────────── finish(Completed) ────────────┐
                        │             stop(Stopped)                 │
   beginCompletion      │             fail(Failed)                  │
   ───────────────►  Open ──CAS──► Writing ──写库──► [提交 UI] ──► Closed
                        │                                          
                        └──（同一时刻只有一个线程能 CAS 成功）──────┘
   输家：CAS 失败 → 立刻返回，不写库、不改 UI、不 cancel
```

- `Open`：这一轮还没收尾，任何人可以来抢。
- `Writing`：有人拿到权、正在写库。**此时不允许 `cancel()` 这个 job**（否则 R1 复现）。
- `Closed`：收尾已完成（成功/失败/放弃），再抢一律失败。
- 没有 `Abandoned` 相位：**"放弃"也是一种收尾**（`reason=Cancelled` + `planWrite=Nothing`），
  这样"谁收尾"永远只有一个答案，不会出现"两个都没有"的空档。

### 1.3 本轮四种结局

| 结局 | `reason` | `planWrite` | 写库 | 行 | `inflight` |
|---|---|---|---|---|---|
| 正常完成 | `Completed` | 新消息→`AppendReply`；重新生成→`RewriteReply` | 一次 | 新行 / 改原行 | 清 |
| 停止（有内容） | `Stopped` | 同上（内容=半截） | 一次 | 同上 | 清 |
| 停止（0 字） | `Stopped` | `Nothing` | **不写** | 无变化 | 清 |
| 生成失败 | `Failed` | 有本轮用户消息→`DeleteUserRow`；否则 `Nothing` | 一次删除 | 移除用户行（删除成功才移除） | 清 |
| 切会话取消 | `Cancelled` | `Nothing`（现状：半截丢弃，记日志） | 不写 | 无变化 | 清（旧会话的） |
| **回复保存失败** | `Completed`/`Stopped` + `WriteResult.Failed` | —— | 失败 | **不写行**，旧正文不动 | 清 + 提示 |

> **关键区分**：生成失败走 `reason=Failed`（会删用户消息）；保存失败是"写字没成功"，走
> `reason=Completed/Stopped` + `WriteResult.Failed`（**绝不删任何东西**）。两条路在类型上分开。

---

## 2. 提交权机制：为什么是 `AtomicReference` 而不是 Mutex / AtomicBoolean

| 方案 | 判定 | 理由 |
|---|---|---|
| `AtomicBoolean written`（现状） | ❌ | 只有一个位，无法表达"正在写"。`stop()` 必须在 CAS **之前** `cancel()` 才能立刻停流，于是取消吃掉了写者的后续代码（R1）。它也把"互斥/幂等/已落库标记"混成一个位 |
| `Mutex` | ❌ | ①它是 suspend 的，而 `stop()`/`onClick` 不是；②它的语义是"第二个等待"，而这里第二个必须**立刻失败退出**；③它保护不了"跨越 `withContext(IO)` 的归属"，仍要额外一个标记 |
| `synchronized` | ⚠️ 可行但多余 | 所有入口都在主线程（`viewModelScope` = `Main.immediate`），加锁只会掩盖"以为有并发"的事实；而且它不能表达"已收尾"这个持久相位 |
| **`AtomicReference<CommitPhase>` + CAS** | ✅ | 一个原子字同时给出"互斥 + 一次性 + 可读相位"；非 suspend，任何入口都能调；输家零副作用 |

```kotlin
public enum class CommitPhase { Open, Writing, Closed }

private val phase = AtomicReference(CommitPhase.Open)

/** 唯一入口：谁能写库。CAS 成功 = 这一轮的唯一提交者。 */
public fun claimCommitRight(): Boolean =
    phase.compareAndSet(CommitPhase.Open, CommitPhase.Writing)

/** 只有拿到权的人调用；放在 finally 里，保证任何异常后也关得上。 */
public fun close() { phase.set(CommitPhase.Closed) }
```

**四条调用纪律**（写进注释，评审时逐条对）：

| # | 纪律 | 防的是 |
|---|---|---|
| C1 | `cancel()` 只在 `claimCommitRight()` 成功之后调用 | R1（取消吃掉提交） |
| C2 | 输家**不改 UI**（连"已停止"都不写） | 新轮被旧轮按回 `isStreaming=false` |
| C3 | 清 `inflight` 必须 `state.inflight?.roundId == roundId` | 旧轮清掉新轮的 `inflight` |
| C4 | 写行必须 `state.sessionId == round.sessionId` | 旧会话的行被塞进新会话列表 |

---

## 3. 关键函数执行顺序

所有入口最终都走**同一个** `settle()`（一段代码、一张决策表）。

```
beginCompletion()
  1. roundId = ++roundSeq
  2. round = GenerationRound(roundId, sessionId, targetDbId, userRowDbId, userInput, baseSwipes)
     currentRound = round;  UiState.activeRoundId = roundId
  3. _state.update { transcript = history; streamingText=""; isStreaming=true; … }   // 现状不变
  4. launch { 准备请求 → 流式收集：
         Delta    -> _state.update { streamingText = buffer }        // 可取消，无副作用
         Completed-> settle(round, buffer.toString(), Completed)
         Failed   -> settle(round, "", Failed) }
       流结束但没有终止事件：buffer 空 → settle(…, Failed)；否则 settle(…, Completed)
  }

settle(round, text?, reason, cancelJob = null)          // suspend，唯一收尾路径
  1. if (!round.claimCommitRight()) { log("收尾 被抢先 reason=…"); return }   // ← 输家：什么都不做
  2. cancelJob?.cancel()                                  // ← C1：拿到权之后才停流
  3. try {
       withContext(NonCancellable) {                       // ← 唯一必须完成的区段
           val body = text ?: _state.value.streamingText   // 停止时读当前已显示的正文
           val plan = round.planWrite(body, reason)        // 纯函数
           val result = when (plan) {
               Nothing                -> WriteResult.Skipped
               is AppendReply         -> withContext(IO) { repo.append(sessionId, false, plan.content) }
                                            .let { WriteResult.Ok(it.id) }
               is RewriteReply        -> withContext(IO) { repo.replaceMessage(plan.targetDbId, plan.content, plan.swipes) }
                                            .let { if (it) WriteResult.Ok(plan.targetDbId) else WriteResult.Failed("行不存在") }
               is DeleteUserRow       -> withContext(IO) { repo.deleteMessage(plan.dbId) }
                                            .let { WriteResult.Ok(plan.dbId) }
           }
           _state.update { st -> applyEffects(st, round.planUi(snapshot(st), plan, result, okStatus)) }
           //  ↑ 一次 update 落地全部效果：不出现"半新半旧"的中间帧
           log("收尾 round=$roundId reason=$reason plan=$plan result=$result effects=$effects")
       }
     } catch (t: Throwable) { log("收尾异常 round=$roundId", t); status = "保存失败" }
     finally {
       round.close()                                        // ← 一定要关
       if (currentRound === round) currentRound = null
     }
  4. viewModelScope.launch { runCatching { refreshSessions() } }   // 可取消、失败无害

stop()    = settle(round, null, Stopped, cancelJob = round.job)
finish()  = settle(round, buffer.toString(), Completed)
fail()    = settle(round, "", Failed)
切会话    = currentRound?.takeIf { it.sessionId != id }?.let {
              if (it.claimCommitRight()) { it.job.cancel(); it.close() }   // ← 只取消拿得到权的
           }
```

三层检查对应任务单的三条要求：

| 要求 | 落点 |
|---|---|
| 写库前确认提交权 | 步骤 1 的 CAS（唯一） |
| 写库后再确认 UI 权限 | `planUi` 用**最新** `UiState` 判定 `activeRoundId`/`sessionId`（步骤 3 的 `_state.update` 里） |
| 旧轮不能改新轮 UI | C2 + C3 + C4 |
| 不重复插入同一条回复 | CAS 一次性（一轮最多一次 `append`） |
| 重新生成不得误插新消息 | `planWrite` 的分支由 `targetDbId` 决定，`RewriteReply` 只能走 `replaceMessage`（**类型上不可能 append**） |

---

## 4. `NonCancellable` 的边界

| 步骤 | 可取消 | 说明 |
|---|---|---|
| 世界书扫描 / 提示词组装（`Dispatchers.Default`） | ✅ | 无副作用，取消即丢 |
| 流式收集 + `streamingText` 更新 | ✅ | 只在内存；半截由 `stop` 显式提交 |
| 请求前写用户消息（P1 才改成先落库） | ✅（现状） | 现状是 UI 先上、库后到，取消由 `fail` 兜 |
| **写回复 / 改写回复 / 删用户消息** | ❌ | SQLite 事务一旦开始就必须走完；`CancellationException` 若在 `withContext` 返回时抛出，我们连 `id` 都拿不到 |
| **`_state.update` 提交 UI** | ❌ | 只差这一句就会变成 R1（库里有、界面没有） |
| `refreshSessions()` | ✅ | 纯展示数据，另起一个 `viewModelScope.launch` 跑，失败只记日志 |

**最小写法**（一段，不包整个 `finish`）：

```kotlin
withContext(NonCancellable) {
    val result = withContext(Dispatchers.IO) { persist(plan) }   // 内层继承 NonCancellable
    _state.update { … }                                          // 回到 Main，仍然 NonCancellable
}
```

**事务中途被取消怎么办**：`SqliteChatRepository` 的方法都是**非 suspend 的阻塞调用**，
`beginTransaction/endTransaction` 都在同一个函数里、`finally` 里 `endTransaction`。
所以取消只能在"两个 repo 调用之间"生效，**进不到事务内部**。真正要补的是：

- `updateMessage + setSwipes` 现在是**两次独立调用**（`ChatViewModel.kt:2879-2880`），
  进程在中间死掉会出现"正文换了、版本表没换"。→ 新增
  `ChatRepository.replaceMessage(id, content, swipes): Boolean`（一条事务，`UPDATE` 影响 0 行返回 false）。
- 返回值用起来：`false` = 目标行不存在 → `WriteResult.Failed`，**不要**假装成功。

---

## 5. 数据库失败语义（四类，互不串味）

| 场景 | 触发 | 写库 | UI | 删不删已有消息 |
|---|---|---|---|---|
| **生成失败** | HTTP/网络/异常，`reason=Failed` | `DeleteUserRow(本轮用户消息 dbId)`（本轮有才删） | 移除该用户行 + 还原输入框；**删除失败则保留该行 + 仍还原输入 + 提示"取消失败：这条可能已在库里"** | **只删本轮刚写的那一条**，且删除成功才移除界面 |
| **回复保存失败** | `append` 抛错 / `replaceMessage` 返回 false 或抛错 | 无 | **不写回复行**；清 `inflight`；提示"保存失败（回复未入库）"；用户消息**保留**（它已在库里） | **绝不删**（没有任何删除动作） |
| **用户消息撤销失败** | `deleteMessage` 抛错 | 无（或部分） | 保留行 + 还原输入 + 提示；不假装已撤 | **绝不删**更早的消息 |
| **重新生成保存失败** | `replaceMessage` 抛错 / 0 行 | 无 | 旧正文**原样**（它从未离开列表/库）；清 `inflight`；提示 | **绝不删**（旧行就是那条） |

两条铁律：
1. **保存失败的路径上没有任何删除动作**；删除只出现在"生成失败且本轮有用户消息"这一条。
2. **界面只在写库成功后前进**（保存失败 = 界面停在旧状态 + 一句提示），不"先把界面摆好再补库"。

---

## 6. 纯 JVM 测试（不依赖 Android / 不需要真机）

### 6.1 放在哪、为什么

仲裁与决策表是**纯逻辑**，放进新模块 **`:core:generation`**（`explicitApi()`，零第三方依赖，
只用 `kotlin.test`）。这样 `tools/test-core.sh` 在本机就能跑 —— 这台机器唯一的快速回路
（`:app`/`:data` 本机编不了；CI 的 `./gradlew test` 要几分钟且要等包）。

成本：1 个 `build.gradle.kts` + `settings.gradle.kts` 一行 + `tools/test-core.sh` 一行 +
`core/AGENTS.md` 模块表一行。收益：并发竞争与决策表**可回归**。

### 6.2 用例（20 条）

**A. 提交权（并发）**

| # | 用例 | 断言 |
|---|---|---|
| T1 | `finish` 先 claim、`stop` 后 claim | 只有 finish 拿到；总写库次数 1 |
| T2 | `stop` 先 claim、`finish` 后 claim | 只有 stop 拿到 |
| T3 | 8 线程 × 500 轮同时 claim | **每轮恰好一个成功**（真实多线程，不是假并发） |
| T4 | claim 后 `close`，再 claim | 一律 false（一次性） |

**B. `planWrite`（意图 → 写什么）**

| # | 用例 | 断言 |
|---|---|---|
| T5 | 重新生成 + 非空正文 | `RewriteReply(targetDbId, text, base+text)` |
| T6 | 重新生成 + 空正文（0 字停止） | `Nothing`（**不得**退化成 Append） |
| T7 | 新消息 + 非空正文 | `AppendReply(text)` |
| T8 | 新消息 + 空白正文（0 字停止/空完成） | `Nothing` |
| T9 | `Failed` + 有用户消息 dbId | `DeleteUserRow(dbId)` |
| T10 | `Failed` + 无用户消息（继续/重新生成） | `Nothing` |
| T11 | `Cancelled` | `Nothing`（半截丢弃，与现状一致） |

**C. `planUi`（结果 → 界面，含归属判定）**

| # | 用例 | 断言 |
|---|---|---|
| T12 | Append 成功 + 同会话 + 仍是当前轮 | `appendRow=text`、`clearInflight=true`、`status=…`、`refreshSessions=true` |
| T13 | Append 成功 + **换了会话** | `appendRow=null`（不塞进别人列表）、`refreshSessions=true` |
| T14 | Append 成功 + **已有新轮**（`activeRoundId` 变了） | 行**仍然要写**（库里有），`clearInflight=false`、`status=null` |
| T15 | Rewrite 成功 + 同会话 | `rewriteRow(target, text, swipes)`；**`appendRow == null`** |
| T16 | Append 失败 | `appendRow=null`、`status=保存失败`、`removeUserRow=false`、**没有任何删除** |
| T17 | Rewrite 失败 | 同上；旧正文不动（效果里没有行写入） |
| T18 | DeleteUserRow 成功 | `removeUserRow=true`、`restoreInput=原输入`、`refreshSessions=true` |
| T19 | DeleteUserRow 失败 | `removeUserRow=**false**`、`restoreInput=原输入`、`status=取消失败` |
| T20 | 0 字停止 + 重新生成 | 无行写入、`clearInflight=true`、`status=已停止`；且 `removeUserRow=false` |
| T21 | 换会话后 DeleteUserRow 成功 | `restoreInput=null`（不许清掉新会话的输入框） |

（超过 20 条：T1–T21。）

### 6.3 测不到、必须靠日志/CI 的

| 项 | 为什么测不到 | 怎么验 |
|---|---|---|
| `NonCancellable` 是否真的跑完 | 需要真实取消时序 | 真机 `tag=chat`：连点"停止"20 次，每次都能看到 `收尾 … 结果=`；回复不丢（重进会话仍在） |
| `replaceMessage` 单事务 | `:data` 需要 Android SQLite | CI 编译 + 真机：重新生成后强杀 → 正文与版本表版本一致 |
| `_state.update` 不被跳过 | 需要 UI | 同上日志 + 界面 |

---

## 7. 涉及文件与改动量

| 文件 | 改动 | 行数 |
|---|---|---|
| `settings.gradle.kts` | `include(":core:generation")` | +1 |
| `core/generation/build.gradle.kts` | 新建（照 `core/regex` 模板，去掉序列化依赖） | +20 |
| `core/generation/src/main/kotlin/app/tavern/core/generation/GenerationRound.kt` | 新建：相位 + `planWrite` + `planUi` + 效果类型 | +180（含注释） |
| `core/generation/src/test/kotlin/…/GenerationRoundTest.kt` | 新建：T1–T21 | +260 |
| `tools/test-core.sh` | `:core:generation:test` | +1 |
| `core/AGENTS.md` | 模块表 +1 行 | +1 |
| `app/.../ChatViewModel.kt` | 删 `StreamingTask.written`/`replacing` → `GenerationRound`；`stop/finish/fail` 收敛到 `settle()`；`beginCompletion` 建轮；`openSessionInternal` 取消加 gate；`UiState.activeRoundId`；`tag=chat` 补字段 | ~150 |
| `data/.../ChatRepository.kt` + `SqliteChatRepository.kt` | 新增 `replaceMessage(id, content, swipes): Boolean`（单事务） | +30 |
| `docs/` | 本文件；r2 §10 的 P1 验收口径；handoff | +30 |

**不碰**：`ChatScreen.kt`（P0 零观感变化）、DB schema、`core` 其它模块。

---

## 8. P0 明确不修（留给后面）

| 项 | 留给 |
|---|---|
| 用户消息"先落库再上屏"（现在是 UI 先上、库后到） | P1（和行创建耦合，改两遍不划算） |
| 收尾行的**插入位置锚**（极端竞态：停止后 5ms 内又发一条，旧回复会排到新问题后面） | P1（`anchorUiKey`） |
| `streamingText`/`isStreaming` 换 `inflight`；`rows`；`uiKey` | P1 |
| 占位气泡、回底分档、IME 读点、光标、动画门控 | P2–P4 |

---

## 9. P1 验收口径（按用户要求修订）

**"零视觉变化"的定义**：**正常布局、尺寸、顺序、滚动与既有功能无回归**。
**不要求**保留旧版本里那些本来就不正确的行为——例如落库帧的重播淡入、离场重建时的整屏淡入、
"AI 气泡掉到输入框下面"。这些若因身份稳定而消失，**算修掉 bug，不算回归**。

但必须逐条记录：`docs/dev/verification/` 的 P1 文件里写清"哪一处观感变了 + 为什么它是 bug 被修掉
而不是引入回归"，并配一条对应日志证据。反过来说：任何**布局尺寸、条目顺序、滚动位置、既有功能**
的变化，仍然算 P1 失败。
