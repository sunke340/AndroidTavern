# 接手卡（2026-11-26 10:35）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**：
> 现状 → [status.md](status.md)；规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)；
> **聊天列表闪烁的诊断与改造方案（当前主线）→ [plan-chat-list-flash.md](../plan-chat-list-flash.md)
> + 第二轮技术审查（**5 处修订、以它为准**）→ [plan-chat-list-flash-r2.md](../plan-chat-list-flash-r2.md)**；
> 需求批口径 → [plan-req-2026-11-25.md](../plan-req-2026-11-25.md) / [plan-req-2026-11-26.md](../plan-req-2026-11-26.md)；
> 词表 → [ui-map.md](../ui-map.md)；必须成立什么 → [ui-invariants.md](../ui-invariants.md)；
> 真机验证 → [verification/](verification/)（**一批一个文件**，索引在 README）；
> M0–M16 归档在 [device-verification.md](device-verification.md)（已冻结）；历史细节 → [pitfalls.md](pitfalls.md)。

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容它的文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)；其它决策见 `docs/adr/`）。
- **代码基线**：`2b1c101`（v165：**P0 收尾提交权**，`apk-m196`）。
- **真机跑到**：v164；**v165 待 M23**（P0 只动收尾状态机，观感应与 v164 完全一致）。
  v164 回执：自己发消息的跟底**已好**，**AI 气泡跟底 / 重新生成闪 / 发送时历史闪 /
  继续长消息闪**仍未解决 —— 已确认是**结构性**问题。**2026-11-26 午后已按代码逐行重审**，
  方案在 [plan-chat-list-flash.md](../plan-chat-list-flash.md)（重写版）+ [plan-chat-list-flash-r2.md](../plan-chat-list-flash-r2.md)
  （**二轮审查：`uiKey` 不变量 / `roundId` / 先出 P0 数据一致性 / 回底分档**）。
  **P0 是数据 bug（会丢回复、会删用户的提问），可以不等拍板先修；P1 起仍要拍板。**
- **在做的活**：2026-11-25 的 16 条需求（A/B/C 确认、D 待验、**E/F 未开工**）+
  2026-11-26 的七条（已出 v160）+ 聊天列表结构性改造（等拍板）。
- **铁律**：core 纯 JVM；兼容行为必须带 fixture；不猜原版；本机不构建；能复用先复用；
  **说界面上的东西用 `docs/ui-map.md` 的名字，对不上就先问、不许猜**。

## 一、当前基线（2026-11-26 一天出了六个包）

| 版本 | 提交 | tag | 交付物 | 内容 |
|---|---|---|---|---|
| v159 | `c984f71` | `apk-m190` | `artifacts/app-v159-c984f71.apk` | 止血重构：发送路径后台化 / 流式任务所有权 / 双写窗口 |
| v160 | `dcb0742` | `apk-m191` | `artifacts/app-v160-dcb0742.apk` | 2026-11-26 七条需求 + M16 修复 |
| v161 | `410a713` | `apk-m192` | `artifacts/app-v161-410a713.apk` | 停止/swipe 底本/列表整屏闪 |
| v162 | `5d8266e` | `apk-m193` | `artifacts/app-v162-5d8266e.apk` | 「继续」改为落新气泡（与原版的有意差异） |
| v163 | `9bbf3a6` | `apk-m194` | `artifacts/app-v163-9bbf3a6.apk` | 版本条挂错气泡 + 落库淡入 |
| v164 | `8c5c5d9` | `apk-m195` | `artifacts/app-v164-8c5c5d9.apk` | 新消息回底 + 落库高度占位 |
| v165 | `2b1c101` | `apk-m196` | `artifacts/app-v165-2b1c101.apk` | **P0 收尾提交权**：Stop/Finish/失败/切会话收敛成一个提交权，`NonCancellable` 只包"写库→提交界面"，四类失败分开，`replaceMessage` 单事务（设计 → [plan-p0-settle.md](../plan-p0-settle.md)）|

**真机验证记录**：`docs/dev/verification/2026-11-26-M17…M22.md`（一批一个文件）。
M16（v158）的"卡内嵌书条目页保位"已在 v160 修掉；其余 D 批项用户口头确认正常。

## 二、现在悬着的（按重要性）

1. **聊天列表闪烁（主线，等拍板）**：审查、根因分级、目标模型、四批拆分、验证清单全在
   [plan-chat-list-flash.md](../plan-chat-list-flash.md)（**2026-11-26 午后重写版**，上午版的两条结论
   已被推翻：`animateContentSize` 不是第一批该用的东西；位置键"够用"是错的）。
   **拍板前不要改聊天列表渲染**。待拍板只剩两点：
   ①重新生成开场接受"硬切"（推荐）还是必须"平滑收缩"（首 token 最多晚 250ms）；
   ②占位文案静态"生成中…/重新生成中…"（推荐）还是带呼吸点。
2. **`‹N/M›` 只能翻最后一条**：原版 `isMessageSwipeable` 就只允许最后一条（v165 之前的既定行为）。
   用户要的"任意消息都能翻版本"是**扩展**，2026-11-26 说**先待定**。
3. **E 批**：聊天页 ⋮ 与卡详情 ⋮ 两处的"胶囊变形成液态玻璃菜单"原型（口径见 plan-req-2026-11-25）。
   新组件要同步 `ui-map.md` + INV-05 白名单。
4. **F 批**：用户人设（多人设 + 三级 + `{{user}}`）。**夹具先行**：`capture.mjs` 要能设人设，
   没有 fixture 不许开工（AGENTS 铁律 #2/#4）。
5. **审计 P0-3**：`data` 层 JVM 测试（三级配置编解码 / `.meta` 容错 / DB 迁移 / JSONL 往返）。
6. 老账：世界书 outlet（缺夹具）→ 卡库分组 → `forbid_overrides` → 角色详情页页头。
   **明确不做**：MVU / 酒馆助手的 HTML 状态栏（无 WebView，ADR 0002）。

## 三、下一步优先级

0. **P0 已出包，等 M23 回执**：[plan-p0-settle.md](../plan-p0-settle.md) 是设计与实施记录；
   清单在 [verification/2026-11-26-M23.md](verification/2026-11-26-M23.md)（12 条，判据全在
   `tag=chat` 的 `收尾 round=… reason=… 计划=… 结果=…`）。**这一批的验收前提是"观感零变化"**，
   真机若看到任何布局/动画变化都算回归。四条纪律：`cancel()` 只在拿到提交权之后；
   输家不改 UI；清"生成中"校验 `roundId`；写行校验 `sessionId`。
1. **P1 行模型（下一批，等拍板）**：先拿 [plan-chat-list-flash.md](../plan-chat-list-flash.md) /
   [r2](../plan-chat-list-flash-r2.md) 的两个决策点去问用户，
   然后按那份文档的第四~六节**一批做完**（行模型 + 占位气泡 + 重新生成就地替换 + 离散动画），
   配一条专门验证清单（发送 / 首 token / 停止 / 继续 / 重新生成 / 撤回 / 切会话）。
2. 收 v164 之后那一批的真机回执；三包（v162/v163/v164）都还有没复验的条目。
3. E 批 → F 批（F 先补夹具）。
4. 技术债：data 测试（P0-3）→ 重组成本 P1-3（等真机清单清空再做）。

## 四、这台机器与出包（每次都要看）

- **本机编不了 `:app` / `:data`**，只能跑 `tools/test-core.sh` 与 `tools/check-*.sh`。
  提交前至少跑：`check-core-purity` / `check-kotlin-comments` / `check-kotlin-imports` /
  `check-ui-map` / `check-ui-invariants` + `test-core.sh`。
- **出包循环**：
  1. `git commit`（消息里有引号/反引号就写文件 + `-F`）→ `git push`；
  2. 等 CI：**优先用 release 页面判断**（`releases/tag/apk-latest` 里 grep 提交 sha），
     API 只在额度内用；
  3. 下载 `releases/download/apk-latest/app-debug.apk`（**加 `?cb=时间戳` 免缓存**）；
  4. **验 dex 里的 40-hex `BUILD_ID`**（`classes17.dex`，python `zipfile`）——**唯一判据**；
  5. `tools/verify-apk.sh <apk> 55:E0:C6:E2:…:0B:E7`（完整指纹见 runbook / CI 日志）；
  6. `cp` 成 `artifacts/app-vNNN-<sha7>.apk`，打 tag `apk-mN`，`git push origin <tag>`。
- ⚠️ `apk-latest` 的发布步骤可能因"同名资产覆盖"失败 → 给同一 commit 打 `apk-mN` tag 走 tag release。
- ⚠️ CI 失败日志在 `ci-log-android` / `ci-log-jvm` 两个 release，命名 `build-<sha7>.log`。
- ⚠️ `poll-ci.sh` 会假 READY（只看 release body）；限制流时改用"release 页面 grep sha + 验 BUILD_ID"。
- **真机日志**：设置 → 关于 → 导出日志（SAF）。AppLog 只写应用内，journald 里没有。

## 五、日志 tag 速查

| tag | 看什么 |
|---|---|
| `nav` | 转场预热首帧、转场帧数/最长帧、页面移除间隔 |
| `scroll` | **串流跟随模式 A/B/C**（A=贴底 idx0/off0、B=读历史、C=读气泡自身）、补偿、`回到贴底`、`串流结束` |
| `chat` | 发送 / 完成 / 失败 / 重新生成 / 继续 / 停止 / swipe / 撤回（**排查闪烁先看这些时间点**） |
| `config` | 三级配置写入（模型/思考强度/预设/采样五项/内置正则/气泡字号） |
| `ui` | 输入区展开与高度、离场收焦点、抽屉冻结让位、确认框 |
| `wi` | 世界书：开书耗时、条目数、全文 |
| `rubber` / `glass` | 橡皮筋回弹；玻璃档参数与逐像素校验 |

> 排查"闪"的固定套路：在 `chat` 的一次状态变更前后各看一行 `scroll` 的模式与 `气泡高/顶Y`；
> 顶 Y 是负的等价于"这条气泡比屏幕还高"，这种条目任何增删都是整屏级位移。

## 六、文件地图（想改什么去哪）

| 想改什么 | 去哪 |
|---|---|
| **聊天列表渲染 / 闪烁 / 跟随** | `ChatScreen.kt`（`itemsIndexed` 的 key、`itemAnim`、`justSettled`/`suppressNewestFade`、`topOfStream`、`translationY` 补偿）+ `ChatViewModel` 的 `beginCompletion`/`finish`/`stop` |
| 流式任务所有权 | `ChatViewModel.StreamingTask` / `stop()` / `openSessionInternal()` 的取消逻辑 |
| 继续 / 重新生成 / swipe | `continueLast` / `regenerateLast` / `selectSwipe`（`ReplaceTarget` 只服务重新生成） |
| 提交与气泡样式 | `DisplayText.kt`（`markupText` / `displayPlain`）、`core/regex/BuiltinRegex.kt` |
| 三级配置生效值 | `UiState.effective*`（采样五项、`builtinDisabled`、`bubbleFontSize` …） |
| 气泡字号三级 | `Screen.kt`（`BubbleFontSizes` / `ConfigKind.FontSize`）+ 三处入口 |
| 点触反馈 | `Style.kt` 的 `rememberPressTint` |
| 回到底部钮 | `ChatScreen.kt` 的 `JumpToBottomButton`（不参与输入区测量） |
| 开场白「设为默认」 | `CharacterDetailScreen.kt` 的 `DefaultGreetingChip` |
| 预设「提示词全文」 | `PresetPromptScreen` + `Destination.PresetPrompt` |
| 选择页（模型/采样/世界书/正则/预设/字号） | `ConfigPickerScreen.kt` |
| 词表 / 不变量 | `docs/ui-map.md`、`docs/ui-invariants.md`、两个 `check-ui-*.sh` |

## 七、这轮踩的坑（一句话一条，细节在 pitfalls.md）

1. **动画只对"离散事件"有效**：串流时列表每帧长高，开 `placementSpec` 会每帧重放 → 抖动。
2. **稳定 key 会改变"跟底"语义**：插到头部时列表锚住旧条目，新气泡落在视口外；要显式回 index 0。
3. **`translationY` 余量在 `isStreaming=false` 当帧归零**：这是"停止/写完闪一下"的一小笔来源。
4. **流式条目与落库条目是两个 key**：落库一帧"删+加"，高度差要预留，否则整列跳。
5. **文本一换高度就突变**：2520px 的气泡换成 80px 的"生成中"，同款整屏位移。
   ⚠️ **这条是结构性的（反排列表底部锚定 + 最后一条缩水 Δ = 历史下移 Δ），不要指望"加个高度动画"解决**：
   2500px 在 250ms 内走完 = 10 屏/秒，且 `animateContentSize` 会包住串流每一次换行、与既有的
   `translationY` 追高桥接抢同一位移。详见 plan-chat-list-flash.md §4.2–4.3。
6. **占位气泡不能进 `transcript`**：transcript 下标 ↔ DB 行号强耦合（审计 P1-5）。
7. **`collectIsPressedAsState()` 会吞掉同帧按下+抬起**：要吃 `interactions` 流 + 最短可视 90ms。
8. **键盘收起不能等 `imeVisible == false`**：盯 `snapshotFlow { ime.getBottom(density) }` 的"开始下降"。
9. **底部浮动控件不许进被测量的那一层**（列表让位读输入区实测高度）。
10. **`cancel()` 只能在拿到提交权之后**：先 cancel 再抢权，会让"已经写完库、还没提交界面"的
    收尾在 `withContext` 返回时抛 `CancellationException` → 回复在库里、界面没有（v164 及之前的老 bug）。
11. **纯 JVM 新模块要在三处登记**：`settings.gradle.kts` + `tools/test-core.sh` + **`app/build.gradle.kts`
    的 project 依赖**（漏了第三处，CI 报 107 处 Unresolved reference，本机 `:core` 跑得再绿也发现不了）。
12. 其它照旧：引号/反引号会撕提交消息（用 `-F`）；本机编不了 → 缺 import 只能 CI 抓；
    `if (常量) A else B` 会静默杀死一支。
