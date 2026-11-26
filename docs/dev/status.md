# 项目状态快照（2026-11-26 14:35）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 接手先看 [../handoff/2026-11-26-chat-list-refactor.md](../handoff/2026-11-26-chat-list-refactor.md)，界面叫什么看 [ui-map.md](../ui-map.md)，
> 必须成立什么看 [ui-invariants.md](../ui-invariants.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `e37ded3`（**P3 就地改写 + 平滑收缩**）；前几包 `85bccc2`（P2）、`806a0db`（P1）、`2b1c101`（P0） |
| 最新交付 | **十包**：`app-v159…` → `app-v167-85bccc2.apk`（`apk-m198`）→ **`app-v168-e37ded3.apk`（`apk-m199`）**；BUILD_ID 与签名都已验 |
| 真机确认到哪 | v157（M15）已确认；v158–v164 已真机跑过。v164 回执：自己发消息跟底已好；AI 气泡跟底 / 重新生成闪 / 发送时历史闪 / 继续长消息闪仍未解决 → 结构性问题，见 [plan-chat-list-flash.md](../plan-chat-list-flash.md)。**v165 M23 全通过；v166 M24 核心两条通过；v167 M25 全通过；v168 待 M26**。主线剩 P3 回执 / P4 |
| CI | 铁律（含 `check-ui-map.sh` / `check-ui-invariants.sh`）/ 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` 全绿（**10 个模块 129 条**）+ 五个 `check-*` 全过 |
| 规模 | `app` 约 45 个 kt / 约 12k 行；`data` 22 文件 / 2.4k 行；DB v4 |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 主线：聊天列表重构（四批）

用户 2026-11-26 12:2x 已把两个决策点拍板（见 [r2 §13](../plan-chat-list-flash-r2.md)）：
重新生成要做**平滑收缩**（不是硬切）、占位气泡要**呼吸点**（不是静态文案）。

| 批 | 内容 | 状态 |
|---|---|---|
| **P0 收尾提交权** | Stop/Finish/生成失败/切会话收敛成一个 `GenerationRound` 提交权（新纯 JVM 模块 `:core:generation`，21 条用例）；`cancel()` 只在拿到权之后；`NonCancellable` 只包「写库→提交界面」；四类失败语义分开；`replaceMessage` 单事务 | ✅ v165（`2b1c101` / `apk-m196`），**M23 全通过**（其中「快速停止」「半秒竞态」两条做不到人工测试，由 T1–T4 的并发用例覆盖） |
| **P1 行模型** | `ChatRow(uiKey)` + `Inflight` 顶替 `transcript`/`streamingText`/`isStreaming`；渲染收敛成一个 `itemsIndexed(key=uiKey)`；删除/撤回/翻版本按 uiKey 定位、按 dbId 落库（审计 P1-5 的下标耦合解除） | ✅ v166（`806a0db` / `apk-m197`），**M24 核心两条通过** |
| **P2 占位气泡** | 点发送/继续/重新生成**当帧**出 `生成中`/`重新生成中` + 呼吸点（`PendingLine`/`TypingDots`）；首 token 只改正文 → 顺带修掉「AI 气泡掉到输入框下面」；回底分档（贴底瞬时 / 读历史动画）；测量状态按轮分开；键盘可见性不再每帧重组 | ✅ v167（`85bccc2` / `apk-m198`），**M25 全通过** |
| **P3 就地替换 + 平滑收缩** | 重新生成**同一行改写**（`inflight.uiKey` 用 `m{dbId}`）；删掉 `ReplaceTarget`/`removedContent`/`Inflight.removed`/`RestoreRemoved`/`streamingRegenerate`；开场 `animateContentSize` 按 r2 §13.1 四条硬约束收缩 260ms | ✅ v168（`e37ded3` / `apk-m199`），**待 M26 真机** |
| **P4 动画收口** | `prevKeys` 入场规则（取代 `justSettled`/`suppressNewestFade`/`suppressItemFade` 三个补丁）、`placementSpec` 只在非串流时挂、落库前 `smoothHeight` snap、删除/撤回的位移动画 | ⬜ **下一批**（P3 之后） |

## 最近这几轮改了什么

| 主题 | 状态 |
|---|---|
| 交接卡迁到 `docs/handoff/`（一次交接一份），`docs/dev/handoff.md` 只留跳转 | ✅ `fa255e1` / `a0a3b7a`，纯文档 |
| P2 占位气泡（v167） | ✅ 真机全通过（M25） |
| P1 行模型（v166） | ✅ M24 核心两条通过 |
| P0 收尾提交权（v165） | ✅ M23 全通过 |
| v164 两条修复（日志定位）：稳定 key 会把新消息锚在视口外（不自动回底）→ 贴底/自己发送时显式回 index 0；落库那一帧多出动作行(34dp)/版本条(40dp) → 串流气泡先占位 | ✅ 真机跑过（M22） |
| v163 两条修复：重新生成时版本表先摘掉（`‹2/2›` 不再跑到上一条气泡）、串流落库那一帧不淡入 | ✅ 真机跑过（M21） |
| 「继续」落新气泡（v162）：与原版的有意差异（原版 `mes +=` 拼回同一条）；旧气泡全程不动，续写是新助手消息 | ✅ 真机跑过（M20） |
| M17 三条修复（v161）：停止时认得"这一轮在改哪条"、swipe 底本过期不串到上一条、聊天列表用原下标做 key、查看请求首行带 model 与采样五项 | ✅ 真机跑过（M19） |
| 止血重构（审计 P0-2 / P1-1 / P1-2）：分词+世界书+提示词组装移入 `Dispatchers.Default`；`StreamingTask`；切会话取消上一轮；`stop()`/`finish()` 抢同一个 `AtomicBoolean` | ✅ 真机跑过（M17） |
| 需求批 2026-11-26 七条：点触反馈、默认胶囊变薄、回到底部钮、取消 `.bak.png`、键盘收起即缩、气泡字号三级、预设页全文 | ✅ 真机跑过（M18） |
| 需求批 D：采样五项三级、默认正则整行删+占位符、内置正则三级；条目页返回保位、置顶组配色、行尾时间 | ✅ 真机确认 |
| 需求批 A / B / C | ✅ v155 / v156 / v157 真机确认（M13 / M14 / M15） |
| 页头收口（INV-01）、第二批不变量（INV-04/06/08） | ✅ v152 / v153 真机确认（M11 / M12） |
| 架构审计（只读，2026-11-26） | ✅ 已入库：[architecture-audit.md](architecture-audit.md) |

## 悬着的事

1. **主线剩 P3 回执 + P4**（见上表）。P3 已出包 v168，等 M26 真机回执；P4 排在它后面。设计口径以
   [r2](../plan-chat-list-flash-r2.md) 为准（第一轮 [plan-chat-list-flash.md](../plan-chat-list-flash.md) 已被它修订）。
2. **`‹N/M›` 只能翻最后一条**：原版 `isMessageSwipeable` 只允许最后一条；
   "任意消息可翻版本"是扩展，用户 2026-11-26 说**先待定**。
3. **需求批还剩 E / F 两批**（#12 胶囊→玻璃菜单、#3 用户人设）：口径见
   [plan-req-2026-11-25.md](../plan-req-2026-11-25.md)。E 先做聊天页 ⋮ 与卡详情 ⋮ 两处原型；
   F 必须先补夹具。
4. **审计 P0-3（`app` / `data` 零测试）仍未动**：建议补 `data` 层 JVM 测试
   （三级配置编解码、`.meta` 容错、DB 迁移 v1→v4、JSONL 往返），投入产出最高。
5. **P1-3 全树重组（`@Immutable` / 气泡文本 remember）刻意推迟**：等真机清单清空再做，
   否则会和观感问题混在一起。
6. 世界书 **outlet** 未做且缺夹具；卡库**分组**未做；`forbid_overrides`、"按角色禁用某段"未做。
   MVU/酒馆助手的**状态栏渲染**（HTML/JS 界面）明确不做（无 WebView），只清占位符。
7. **实验项（不进批次）**：E2 `swipe` 切换的 `animateContentSize`、E4 超长气泡的 `atBottom` 真机打点。
8. **技术债**：`swipe_id` 不落库，重载靠 `indexOf(content)` 恢复位置。

## 已知风险

- 超长消息（单条 269 行）是本项目的常态输入。任何"整段正文一个节点"或"会生成本地渲染层"的
  做法都要先算高度上限；`MessagePanel` 与 `SelectionProbe` 的教训都在 pitfalls。
- 本机编不了 `:app` / `:data`：改完必跑五个 `check-*` + `test-core.sh`，缺 import 只能 CI 抓。
- 重构包（v159）改的是发送路径：**任何"发不出去 / 停不下来 / 两条重复"** 都优先怀疑它，
  日志看 `tag=chat`（`准备请求失败` / `切会话：取消上一轮流` / `停止：保留 N 字`）。
- 覆盖保存卡现在**没有备份**（用户 2026-11-26 定的）：写坏了只能靠「新建一张」那条老路。
- **聊天列表的位移/闪烁是结构性的**，代码级根因有 **4 个位移通道**（布局增删 / 滚动 / 绘制桥接 /
  底部让位 IME），不是一条。再打零散补丁会互相打脸 —— 改之前先读 r2（§6.2 插入会把 `atBottom`
  翻成 false；§13.1 平滑收缩的四条硬约束）。
- **`animateContentSize` 的边界**：它只允许挂在"非串流"的离散替换上，且与桥接互斥
  （r2 §4.3 / §13.1）。挂错位置 = 串流每换一次行就动一次画，比不挂更晃。
