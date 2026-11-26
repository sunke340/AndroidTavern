# 接手卡（2026-11-26 14:00）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**，设计细节全在链接里。
> 现状 → [status.md](status.md)｜规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)
> ｜主线方案 → [plan-chat-list-flash.md](../plan-chat-list-flash.md) + **第二轮审查（5 处修订，以它为准）**
> [plan-chat-list-flash-r2.md](../plan-chat-list-flash-r2.md)｜P0 设计与实施记录 → [plan-p0-settle.md](../plan-p0-settle.md)
> ｜真机验证 → [verification/](verification/)（**一批一个文件**，索引在 README）｜词表 → [ui-map.md](../ui-map.md)
> ｜必须成立什么 → [ui-invariants.md](../ui-invariants.md)｜历史细节 → [pitfalls.md](pitfalls.md)
> ｜需求批口径 → [plan-req-2026-11-25.md](../plan-req-2026-11-25.md) / [plan-req-2026-11-26.md](../plan-req-2026-11-26.md)

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)；其它决策见 `docs/adr/`）。
- **代码基线**：`85bccc2`（v167）。
- **主线状态**：聊天列表重构的四批里 **P0 / P1 / P2 已交付且真机全通过**（M23 / M24 / M25）。
  **下一步 P3**（重新生成就地替换 + 平滑收缩），然后 P4（动画收口）。
- **用户的节奏**：一次一批 → 一个 APK → 真机回执 → 下一批。**不要跨批混改**，也不要在回执前叠补丁。
- **铁律**：core 纯 JVM；兼容行为必须带 fixture；不猜原版；本机不构建；能复用先复用；
  界面上东西一律用 `docs/ui-map.md` 的名字，对不上先问。

## 一、主线做完了什么（都给过真机回执）

| 批 | 包 | 一句话 | 详细 |
|---|---|---|---|
| **P0 收尾提交权** | v165 `2b1c101` | Stop/Finish/失败/切会话收敛成 `GenerationRound` 的一个 `AtomicReference<CommitPhase>`；`cancel()` 只在拿到权之后；`NonCancellable` 只包"写库→提交界面"；四类失败语义分开；`replaceMessage` 单事务 | [plan-p0-settle.md](../plan-p0-settle.md) + [M23](verification/2026-11-26-M23.md) |
| **P1 行模型** | v166 `806a0db` | `ChatRow(uiKey, role, content, dbId)` + `Inflight` 顶替 `transcript`/`streamingText`/`isStreaming`；渲染收敛成**一个** `itemsIndexed(key = uiKey)`；删除/撤回/翻版本按 `uiKey` 定位、按 `dbId` 落库（审计 P1-5 的下标耦合解除） | [r2](../plan-chat-list-flash-r2.md) §10 + [M24](verification/2026-11-26-M24.md) |
| **P2 占位气泡** | v167 `85bccc2` | 点发送/继续/重新生成**当帧**出 `生成中`/`重新生成中` + 呼吸点（`PendingLine`/`TypingDots`）；首 token 只改正文 → 顺带修掉「AI 气泡掉到输入框下面」；回底分档（贴底瞬时 / 读历史动画）；测量状态按轮分开；键盘可见性不再每帧重组 | [r2](../plan-chat-list-flash-r2.md) §13.2 + [M25](verification/2026-11-26-M25.md) |

**已被否决的方案**（别再提一遍）：

- ❌ **预分配 DB 主键**：手工指定 id 会破坏 `messages()` 的 `ORDER BY id ASC`（= 时间线）和会话预览的 `MAX(id)`。
- ❌ **生成前插一条临时 DB 行**：每个 token 一次写盘；崩了留半截幽灵行；`messageCount`/预览/`send_date` 全被污染。
- ❌ **用 `Mutex` 做提交权**：它是 suspend-only，而且语义是"第二个排队等待"，这里第二个必须**立刻失败退出**。
- ❌ **每行挂 `MessageStatus`**：会允许"两行同时 streaming""streaming 的不是最后一行"这类非法态；单例 `inflight` 让它们表达不出来。
- ❌ **把占位行写进 `rows` / `transcript` / DB**：行身份与"是否已落库"必须分开（`dbId == null` 是类型事实）。
- ❌ **`animateContentSize` 常驻**：会包住串流每一次换行，与"布局自然增长 + `translationY` 桥接"抢同一位移。
- ⚠️ 我原建议"重新生成开场硬切""占位用静态文案"，**被用户否决**：要做平滑收缩（P3）、要做呼吸点（P2 已完成）。

## 二、下一步（按顺序，别跳）

1. **P3 重新生成就地替换 + 平滑收缩**（唯一还没做的"看得见"的一批）。两件事：
   - **就地替换**：不再"开场 trim 掉原行、收尾 append 新行"，而是保留同一行、用 `inflight.targetDbId` 指向它；
     删掉 `ReplaceTarget` / `removedContent` / `lastSwipes` 的摘放 / `streamingRegenerate` 这些 P0/P1 的适配器残留。
   - **平滑收缩**：严格按 [r2 §13.1](../plan-chat-list-flash-r2.md) 的**四条硬约束**（只挂在收缩阶段 /
     token 攒到收缩完再推 / 桥接三件套全关 / 0 字停止等收尾落地才清 flag）。代价已接受：开场约 260ms 看不到新字。
2. **P4 动画收口**：`prevKeys` 入场规则（取代 `justSettled`/`suppressNewestFade`/`suppressItemFade` 三个补丁）、
   `placementSpec` 只在非串流时挂、落库前 `smoothHeight` snap、删除/撤回的位移动画。
3. 实验项（不进批次）：**E2** `swipe` 切换的 `animateContentSize`、**E4** 超长气泡的 `atBottom` 真机打点。
4. 之后回到需求批：**E 批**（胶囊→液态玻璃菜单）→ **F 批**（用户人设，**夹具先行**）。
5. 技术债：审计 P0-3（`data` 层 JVM 测试）；`swipe_id` 不落库、重载靠 `indexOf(content)` 恢复；
   `‹N/M›` 只能翻最后一条（"任意消息可翻版本"用户说**先待定**）；老账（世界书 outlet / 卡库分组 / `forbid_overrides`）。

## 三、当前包（最近的三个）

| 版本 | 提交 | tag | 交付物 |
|---|---|---|---|
| v165 | `2b1c101` | `apk-m196` | `artifacts/app-v165-2b1c101.apk` |
| v166 | `806a0db` | `apk-m197` | `artifacts/app-v166-806a0db.apk` |
| v167 | `85bccc2` | `apk-m198` | `artifacts/app-v167-85bccc2.apk` |

更早的 v159–v164 在同目录；历史记录 M0–M16 归档在 [device-verification.md](device-verification.md)（冻结）。

## 四、这台机器与出包（每次都要看）

- **本机编不了 `:app` / `:data`**（2 核 / 1.7 GiB，`assembleDebug` 必挂 swap）。提交前至少跑：
  `bash tools/check-core-purity.sh`、`check-kotlin-comments.sh`、`check-kotlin-imports.sh`、
  `check-ui-map.sh`、`check-ui-invariants.sh`、`bash tools/test-core.sh`（十个 core 模块 129 条）。
- **语法自查回路（2026-11-26 新发现，值得用）**：本机有 Gradle 缓存里的 Kotlin 编译器，
  可以对单个 `:app` 文件做**解析级**检查（找不到 android/androidx 的符号是正常的，只看
  `expecting / unexpected / syntax / @Composable invocations` 这几类错误）：

  ```bash
  JDK=$(ls -d .toolchain/jdk-17* | head -1)
  CJ=$(find .toolchain/gradle-home -name 'kotlin-compiler-embeddable-2.1.20.jar' | head -1)
  SL=$(find .toolchain/gradle-home -name 'kotlin-stdlib-2.1.20.jar' | head -1)
  CO=$(find .toolchain/gradle-home -name 'kotlinx-coroutines-core-jvm-1.9.0.jar' | head -1)
  TR=$(find .toolchain/gradle-home -name 'trove4j-1.0.20200330.jar' -not -path '*transforms*' | head -1)
  AN=$(find .toolchain/gradle-home -name 'annotations-13.0.jar' | head -1)
  $JDK/bin/java -cp "$CJ:$SL:$CO:$TR:$AN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -cp "$SL:$CO" -d /tmp/kchk app/src/main/kotlin/app/tavern/ChatScreen.kt 2>&1 \
      | grep -E 'expecting|unexpected|syntax|Composable invocations'
  ```

  它抓不到类型错误（`unresolved` 里混着正常的 android 缺失），**但能抓错位括号、`@Composable`
  位置错、when 少分支** —— P2 那次 `WindowInsets.ime` 就是靠它之外的 CI 抓到的。
- **出包循环**：`git commit`（消息里有引号/反引号就写文件 + `-F`）→ `git push` → 等 CI（约 5–8 分钟）
  → 下载 `releases/download/apk-latest/app-debug.apk?cb=<时间戳>`（**很慢，10–20 分钟，放后台 job**）
  → **验 dex 里的 40-hex `BUILD_ID`**（`classes17.dex`，python `zipfile`；**唯一判据**）
  → `bash tools/verify-apk.sh <apk> 55:E0:C6:E2:19:20:61:92:51:12:04:E8:F8:AC:80:C6:19:44:91:54:26:5B:50:FA:D3:3A:B4:72:8B:96:0B:E7`
  → `cp` 成 `artifacts/app-vNNN-<sha7>.apk` → `git tag apk-mN <sha>` → `git push origin apk-mN`。
- ⚠️ 失败日志在 `ci-log-jvm` / `ci-log-android` 两个 release，命名 `build-<sha7>.log`（下载也慢）。
- ⚠️ 文档提交也会触发 CI，`apk-latest` 会被后来的提交覆盖 —— 交付以 `artifacts/` + tag 为准。
- **真机日志**：设置 → 关于 → 导出日志（SAF）。AppLog 只写应用内，journald 里没有。

## 五、日志 tag 速查

| tag | 看什么 |
|---|---|
| `chat` | 发送 / 完成 / 失败 / 停止 / 继续 / 重新生成 / swipe / 撤回；**P0 起每轮收尾固定一行** `收尾 round=… reason=… 计划=… 结果=… 正文=N字` |
| `scroll` | 串流跟随模式 A/B/C（A=贴底 idx0/off0、B=读历史、C=读气泡自身）、补偿、`边界 回到贴底`、`串流结束` |
| `nav` | 转场预热首帧、转场帧数/最长帧、页面移除间隔 |
| `ui` | 输入区展开与高度、离场收焦点、抽屉冻结让位 |
| `config` / `wi` / `rubber` / `glass` | 三级配置写入 / 世界书 / 橡皮筋 / 玻璃参数 |

> 排查"闪"的固定套路：一次状态变更前后各看一行 `scroll` 的 `模式` 与 `气泡高/顶Y`；
> `顶Y` 是负的等价于"这条气泡比屏幕还高"，这种条目任何增删都是整屏级位移。
> 看"有没有重复落库"就看 `收尾 … 结果=成功` 出现了几次。

## 六、文件地图（想改什么去哪）

| 想改什么 | 去哪 |
|---|---|
| **聊天列表渲染 / 跟随 / 闪烁** | `ChatScreen.kt`：`displayRows` 合成、`itemsIndexed(key = uiKey)`、`itemAnim`、`translationY` 桥接、`dispatchRawDelta` 锚点补偿 |
| **消息身份 / 生成轮** | `ChatViewModel` 的 `ChatRow` / `Inflight` / `newUiKey()`（K1–K5 注释在 `ChatRow` 上）+ `core/generation/GenerationRound.kt` |
| 收尾（停止/完成/失败） | `ChatViewModel.settleClaimed` / `claimFor` / `executeWrite` / `applyEffects` |
| 占位气泡 / 呼吸点 | `PendingLine.kt`（`PendingKind` / `PendingLine` / `TypingDots`） |
| 继续 / 重新生成 / swipe | `continueLast` / `regenerateLast` / `selectSwipe`（`ReplaceTarget` 仍是 P1 的适配器，P3 删） |
| 气泡样式与显示正则 | `DisplayText.kt`、`core/regex/BuiltinRegex.kt` |
| 三级配置生效值 | `UiState.effective*`（采样五项、`builtinDisabled`、`bubbleFontSize`…） |
| 点触反馈 / 回到底部钮 | `Style.kt` 的 `rememberPressTint` / `ChatScreen.kt` 的 `JumpToBottomButton` |
| 词表 / 不变量 | `docs/ui-map.md`、`docs/ui-invariants.md`、两个 `check-ui-*.sh` |

## 七、坑（一句话一条，细节在 pitfalls.md）

1. **`cancel()` 只能在拿到提交权之后**：先 cancel 再抢权，会让已写完库、还没提交界面的收尾被
   `CancellationException` 打断 → 回复在库里、界面没有（P0 修的就是这个）。
2. **纯 JVM 新模块要在三处登记**：`settings.gradle.kts` + `tools/test-core.sh` + **`app/build.gradle.kts`
   的 project 依赖**（漏第三处，CI 报一堆 Unresolved reference，本机 `:core` 再绿也看不见）。
3. **`WindowInsets.ime` 是 `@Composable` 属性**，不能在 `LaunchedEffect` 里读；要组合期取句柄、值用时再量。
4. **插入本身会把 `atBottom` 翻成 false**（稳定 key 锚住旧条目）。所以回底判据用
   "第一可见项 ≤ 2 且 `offset == 0`"，**不要**用"插入前 atBottom"。
5. **测量状态要按轮分开**（`remember(roundId)`）：占位气泡与 `isStreaming` 同帧出现，
   "进流清零"会和同帧的首次测量打架（清成 0 → 整轮不跟底）。
6. **`animateContentSize` 只能挂在"非串流"的离散替换上**，且与桥接互斥（见 r2 §4.3/§13.1）。
7. **稳定 key 会改变"跟底"语义**：插到头部时列表锚住旧条目，新气泡落在视口外 —— 必须显式回 index 0。
8. **占位行不能进 `rows`/`transcript`/DB**：行身份与"是否已落库"必须分开（`dbId == null` 是类型事实）。
9. **`collectIsPressedAsState()` 会吞掉同帧按下+抬起**：要吃 `interactions` 流 + 最短可视 90ms。
10. **提交消息里有引号/反引号就写文件 + `git commit -F`**；本机编不了 → 缺 import 只能 CI 抓。

## 八、建议加载的 skills

| skill | 为什么 |
|---|---|
| `verification-before-completion` | 这个项目的"完成"必须带本轮新鲜证据（`test-core.sh` 的输出、CI 绿、`BUILD_ID` 校验）。说"好了"之前先跑它。 |
| `diagnosing-bugs` | 真机问题全靠日志：它那套"先造红/绿反馈环再进假设"正好对得上 `tag=chat`/`tag=scroll` 的排障方式。 |
| `handoff` | 下次交接再写一页（这一页就是按它写的）。 |
