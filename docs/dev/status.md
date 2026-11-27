# 项目状态快照（2026-11-27 上午，凌晨批量之后、待真机回执）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 决策看 [docs/adr/](../adr/)，界面叫什么看 [ui-map.md](../ui-map.md)，
> 必须成立什么看 [ui-invariants.md](../ui-invariants.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | HEAD 见 `git log -1`；最后一个**功能**提交是 `ccc6f1c`（群聊提示词组装），最后几个 `data`/`tools` 提交是 `b267635`（`data` 层测试开个头）|
| 最新交付 | `apk-m221`（修 v181 报的三条）；再往前 `apk-m212` … `apk-m220` 九个回退点 |
| 真机确认到哪 | **v179 通过 / v180 驳回 / v181 报 3 条**（⋮ 点不动、首条回复退回旧动画、版本条提前出现）→ 已在 `apk-m221` 修，**待 M30 复验**。M29 其余 26 条仍未验 |
| CI | 铁律（含文档相对链接、UI 词表）/ 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` **10 模块 161 条**全绿；`data` 的 4 条本机编不了（无 SDK），由 CI 的 `:data:testDebugUnitTest` 跑 |
| 规模 | `app` 50 个 kt / 17.3k 行；`data` 23 个 kt / 2.5k 行；`core` 73 个 kt / 7.0k 行；`feature/` 仍是空目录；DB **v4** |
| 夹具 | `fixtures/expected/` **36 份**，其中群聊 7 份（四种激活策略 + 继续 + 两种 swipe）|
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 这一批（2026-11-27）做到哪

> 真机怎么验：照 [M29 验证清单](verification/2026-11-27-M29.md) 走（24 条 + m216–m220 后补的 25–29，
> 共 29 条；前 10 条风险最高，末尾有"不过关先 grep 哪行日志"对照表）。

| 事项 | 状态 |
|---|---|
| **流式回复揭示节奏**（用户第 3 条「整屏抽搐」的定稿方案） | ✅ 实施完（[plan-reveal-pacing.md](../plan-reveal-pacing.md) 含 §E 实施记录）；推进数学抽成 `core/model/RevealPacer` + 7 条单测；**待真机观感** |
| **人设 / 用户名 / 名称宏**（P1-8，需求批 F） | 🟨 多人设 + 三级（会话 > 卡 > 全局）+ `{{user}}` + personaDescription 独立 system 消息；夹具 `P1-8-persona-prompt` |
| **作者注**（P1-7） | 🟨 深度/角色/间隔 + **位置三档**（`IN_PROMPT` / `IN_CHAT` / `BEFORE_PROMPT`）；夹具 `P1-7-an-*` 5 份 |
| **作者注顶部/底部条目**（世界书 `ANTop`/`ANBottom`） | ✅ 拼进作者注那一条，只削首尾各一个换行；夹具 `P1-7-an-with-wi` |
| **`forbid_overrides`** | ✅ 逐条提示词的旗标，卡里的 system_prompt / post_history 顶不掉；夹具 `P0-5-forbid-overrides` |
| **世界书 outlet** | ✅ `{{outlet::名字}}` 宏取走 `position = outlet` 的条目，未激活替空串；夹具 `P1-3-outlet` |
| **示例对话顶部/底部**（EMTop/EMBottom） | ✅ 并进示例块，**每块自带 `[Example Chat]`**；夹具 `P1-3-example-injection` |
| **胶囊 → 液态玻璃菜单**（需求批 E 两处原型） | ✅ 聊天页 ⋮ 与卡详情 ⋮；宿主在骨架里、采真磨砂；INV-11 结构守卫；**待真机看落点与观感** |
| TTS 朗读 + 语音输入（P2-3） | ✅ 朗读在动作行（再点停、离场 shutdown）；语音输入在聊天页 ⋮（系统识别器，不要权限）；**待真机听** |
| 分享收卡 + 保存卡图（P2-4 一半） | ✅ `SharedIntake`（`StateFlow`）接正常导入链路；`CardImageSaver` 走 MediaStore（29+ 免权限）；**待真机试** |
| 设置 → 关于「开源许可」页 | ✅ 读 `assets/notices/` 原文（声明 / AGPL / Apache / 两份 MIT），构建期 `syncNotices` 同步 |
| `core/` 注释改署名式 | ✅ 9 处"照抄"改成"移植自 SillyTavern 1.19.1（AGPL-3.0，上游文件）" |
| 默认 `prompt_order` 走 100001 | ✅ 原版 `Default.json` 有两张表，聊天补全那张（100001）才有 `personaDescription` |
| **群聊**（P2-1） | 🔶 **夹具 7 份 + 提示词组装完成**（`PromptBuilder.build(group = …)`：只取说话人那张卡、历史加 `名字: `、组标记、末尾 `[Write the next reply only as X.]`）；**数据模型 / 接线 / 界面未做** |
| `data` 层 JVM 测试 | 🔶 开了个头（`ConfigOverrideTest` 4 条）；顺带修了 `check-tests-ran.sh`（Android 报告在 `testDebugUnitTest/`）|
| 结构守卫 | ✅ INV-11（胶囊菜单只能由骨架画 + **INV-11b：页头 actions 的 provider 必须提供 `LocalCapsuleMenu`**）、INV-12（揭示节奏两条结构约束），都自测会红 |
| **真机 v181 三条回执的修正**（`apk-m221`） | ✅ ① ⋮ 点不动 = 页头 provider 漏了 `LocalCapsuleMenu`；② 首条回复退回旧动画 = 看门狗把"等首字"算成停滞（`RevealPacer` 追平态洗计时器）；③ 版本条提前出现 = 打印期动作行/版本条只占位不显示。口径 [plan-reveal-pacing.md §F](../plan-reveal-pacing.md)，清单 [M30](verification/2026-11-27-M30.md) |

## 还没做（按下一轮优先级）

1. **群聊的落地**（P2-1 剩下的全部）：`ChatSession.groupMembers`、消息 `speaker`、**DB v5 迁移**、
   应用接线、群聊界面，以及验收那 7 条。**这一段要改数据库，没有用户明确同意与真机回滚路径不动**，
   口径在 [plan-group-chat.md](../plan-group-chat.md)。附带疑点：两份 swipe 夹具里 `char_name` 都指向
   **下一位**说话人，语义没分清（§6.2），**先别照抄**。
2. **后台生成 + 通知**（P2-2）：前台服务 + `POST_NOTIFICATIONS` 运行时权限 + Android 14 的
   `foregroundServiceType`。权限/类型配错就是启动即崩，**必须真机在线才做**。
3. **桌面小组件**（P2-4 最后一项）：RemoteViews + 深色适配，必须真机看。
4. **`data` 层测试补齐**：`ConfigOverride` 的编解码（现在埋在 `SharedPreferences` 实现里，要先抽成纯函数）、
   DB 迁移、`.meta` 容错。抽取编解码不碰 schema，可以先做；迁移测试要等 DB v5 一起。
5. **人设那条轴剩下的**：人设描述的 `TOP_AN` / `BOTTOM_AN` 位置（"人设描述跟作者注拼在一起"）、
   人设头像、按角色锁定人设（原版 `locked.character`）。
6. **作者注剩下的**：参与世界书扫描（`allowWIScan`）、按角色的 AN（character notes）。
7. **P4 收尾**：删除 / 撤回的位移动画 —— 判据要真机看（V7），没法在 CI 上验。

## 已知风险

- **这一批基本没上过真机**（v181 只报了 3 条，M29 其余 26 条没验）。最需要真机看的三件：
  揭示节奏的观感（尤其慢档 15cp/s）、胶囊菜单的定位与磨砂、TTS 朗读的音色与打断行为。
- `apk-m221` 的第 3 条修法改的是**布局**（打印期动作行只占位不显示）：打完那一刻"不跳"只在纸面上成立，
  必须真机看（M30 #5/#6）。
- 揭示节奏的**排版开销**只过了 CI 编译，`打印帧` 日志要真机跑一段长回复才有数。
- 胶囊菜单的定位用 `boundsInWindow()` 算，**折叠屏 / 分屏没验**。
- 分享收卡依赖系统 `ACTION_SEND` 的读权限授予；某些文件管理器给的是不可读的 content URI（要真机试）。
- 老风险照旧：本机编不了 `:app`（缺 import / 类型错只能 CI 抓，这一批因此废了 5 次 CI）；
  超长气泡不能整段一个节点；覆盖保存卡无备份。

## 已作废的口径（别再重新提）

- 卡库"分组"—— **原版 1.19.1 没有角色文件夹**（`src/endpoints/characters.js` 只按角色名建目录），
  矩阵 P0-8 该项作废，卡片归类靠标签。
- 揭示节奏的「用滞后换平滑」（追平时间 T / 弹簧 / px/s 高度匀速）—— 实测就是走走停停，v179/v180 两次都死在这；
  「不跟（浏览器式）」—— 攒包后整块上跳 4–7 行。都已否。
