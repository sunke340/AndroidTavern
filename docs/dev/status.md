# 项目状态快照（2026-11-27 凌晨 3 点）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 决策看 [docs/adr/](../adr/)，界面叫什么看 [ui-map.md](../ui-map.md)，
> 必须成立什么看 [ui-invariants.md](../ui-invariants.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | 见 `git log -1`；本轮最后一个功能提交是胶囊菜单，回退点 tag `apk-m215` |
| 最新交付 | 本轮**没有真机回执**（用户明确说这一轮不做真机测试），能装的是 `apk-m212`…`apk-m215` 四个回退点 |
| 真机确认到哪 | **v179 通过 / v180 驳回**（"不满一屏抽搐"仍在），此后的改动全部**未经真机** |
| CI | 铁律（含文档相对链接、UI 词表）/ 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` **10 模块 154 条**全绿（今晚新增：人设 2、作者注 3、`forbid_overrides` 2、出口 2、示例注入 1、文本边界 6、推进器 7） |
| 规模 | `app` 约 50 个 kt / 17k 行；`data` 22 个 kt / 2.6k 行；`core` 60+ 个 kt / 6.3k 行；DB v4 |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 本轮（2026-11-27 凌晨）做完的

> 真机怎么验：照 [M29 验证清单](verification/2026-11-27-M29.md) 走一遍（24 条，风险和判据都写好了）。

| 事项 | 状态 |
|---|---|
| **流式回复揭示节奏**（用户第 3 条「整屏抽搐」的定稿方案） | ✅ 实施完（`docs/plan-reveal-pacing.md`），**待真机观感** |
| **人设 / 用户名 / 名称宏**（P1-8，需求批 F） | 🟨 多人设 + 三级 + `{{user}}` + personaDescription；夹具 `P1-8-persona-prompt` |
| **作者注**（P1-7） | 🟨 深度/角色/间隔注入聊天历史；夹具 `P1-7-an-prompt`、`P1-7-an-depth-2` |
| **胶囊 → 液态玻璃菜单**（需求批 E 原型） | ✅ 两处（聊天页 ⋮ / 卡详情 ⋮），宿主在骨架里、采真磨砂；**待真机看观感** |
| 系统 TTS 朗读（P2-3 一半） | 🟨 动作行「朗读」，离场即 shutdown；语音输入未做 |
| 分享菜单收卡（P2-4 一半） | 🟨 `ACTION_SEND` → `SharedIntake` → 正常导入链路 |
| 设置 → 关于「开源许可」页 | ✅ 读 `assets/notices/` 原文（声明 / AGPL / Apache / 两份 MIT） |
| `core/` 注释改署名式 | ✅ 9 处"照抄"改成"移植自 SillyTavern 1.19.1（AGPL-3.0，上游文件）" |
| P4 的"落库前 snap" | ✅ 挪到"打印结束"这一刻（比挂在落库上更准） |
| 默认 `prompt_order` 用 100001 那张表 | ✅ 原版聊天补全那张才有 `personaDescription`（100000 是文本补全那张） |
| **`forbid_overrides`** | ✅ 预设里打了旗标的提示词，卡里的 system_prompt / post_history 顶不掉；夹具 `P0-5-forbid-overrides` |
| **世界书 outlet** | ✅ `{{outlet::名字}}` 宏；出口条目不再"只记录不注入"；夹具 `P1-3-outlet` |
| 卡详情 ⋮ →「保存卡图」 | ✅ MediaStore 存相册（29+ 不要权限；26–28 按需申请） |
| **世界书示例对话顶部/底部**（EMTop/EMBottom） | ✅ 并进示例块（每块自带 `[Example Chat]`）；夹具 `P1-3-example-injection` |
| **语音输入**（P2-3 另一半） | ✅ 从聊天页 ⋮ 进，走系统识别器（不要麦克风权限、不动输入区版式） |
| 揭示节奏的推进数学 | ✅ 抽成 `core/model/RevealPacer` + 7 条单测（追平不累计 / 停顿丢余量 / 单帧夹 50ms） |
| 结构检查 | ✅ INV-11：胶囊菜单只能由骨架画（自测会红） |
| 文档面 | ✅ README 中英双语 + 5 张截图、第三方声明收紧、矩阵更新、M29 验证清单、方案 §E 实施记录 |

## 还没做（按下一轮的优先级）

1. **群聊**（P2-1，需求批 E 之外最大的一件）：要"多角色激活策略 + 群聊 UI + 群聊夹具"。
   **夹具先行**：得先用 playwright 在 ST 里建一个群、录出 natural/list/manual/pool 四种激活的请求体。
2. **后台生成 + 通知**（P2-2）：要前台服务 + `POST_NOTIFICATIONS` 运行时权限 + Android 14 的
   `foregroundServiceType`。**没有真机时不要盲上** —— 权限/类型配错就是启动即崩，
   而这一轮没人能验。
3. **桌面小组件**（P2-4 最后一项）：要 RemoteViews + 深色适配，且必须真机看。
4. **作者注顶部/底部（ANTop/ANBottom）**：要先支持 AN 的 `TOP_AN` / `BOTTOM_AN` 两种位置 ——
   现在只做了 `IN_CHAT`（按深度插进聊天历史）。
5. **`data` 层 JVM 测试**：`ConfigOverride` 编解码目前挂在 `SharedPreferences` 上，
   要测得先把纯编解码抽出来（小重构），否则本机跑不了。
6. 许可批已经全部清空（APK 内「开源许可」页 + `core/` 署名都做了）。

## 已知风险（这一轮新加的）

- **本轮所有改动都没上过真机**。最需要真机看的三件：揭示节奏的观感（尤其慢档 15cp/s）、
  胶囊菜单的定位与磨砂、TTS 朗读的音色与打断行为。
- 揭示节奏的**排版开销**只在 CI 上过了编译，`打印帧` 日志要真机跑一段长回复才有数。
- 胶囊菜单的定位用 `boundsInWindow()` 算，**没有在折叠屏/分屏上验证**。
- 分享收卡依赖系统 `ACTION_SEND` 的读权限授予；某些文件管理器给的是不可读的 content URI（要真机试）。
- 老风险照旧：本机编不了 `:app`（缺 import / 类型错只能 CI 抓，本轮因此废了 4 次 CI）；
  超长气泡不能整段一个节点；覆盖保存卡无备份。

## 一个已作废的口径

矩阵 P0-8 原来写着"分组（原版的文件夹）未做"—— **原版 1.19.1 并没有角色文件夹**
（`src/endpoints/characters.js` 里只有按角色名建素材/聊天目录）。该项作废，卡片归类靠标签。
