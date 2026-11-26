# 项目状态快照（2026-11-26 21:2x）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 决策看 [docs/adr/](../adr/)，界面叫什么看 [ui-map.md](../ui-map.md)，
> 必须成立什么看 [ui-invariants.md](../ui-invariants.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `a090008`（许可批 + 演示卡；其上是 `9ab1dd5` = 聊天列表 P3 + v177 修复） |
| 最新交付 | `artifacts/app-v178-a090008.apk`（tag `apk-m209`）；上一个 `app-v177-9ab1dd5.apk`（`apk-m208`，M27 通过） |
| 真机确认到哪 | **M27 通过**（v177：点发送后列表不再先往下滑）；M23–M26 全通过（P0–P3）；v178 待回执 |
| 许可 | **AGPL-3.0 已落地**（[ADR 0009](../adr/0009-agpl-license.md)）：根 `LICENSE` + `THIRD_PARTY_NOTICES.md` + `licenses/`，构建期同步进 APK 的 `assets/notices/` |
| CI | 铁律（含文档相对链接）/ 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` 全绿（**10 个模块 131 条**）+ 五个 `check-*` + 文档链接全过 |
| 规模 | `app` 45 个 kt / 15.5k 行；`data` 22 文件 / 2.4k 行；`core` 32 文件 / 3.4k 行（+28 个测试文件）；DB v4 |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 主线：聊天列表重构（四批）

| 批 | 内容 | 状态 |
|---|---|---|
| P0 收尾提交权 | 一个 `GenerationRound` 提交权；`cancel()` 只在拿到权之后（`:core:generation`） | ✅ v165 / M23 |
| P1 行模型 | `ChatRow(uiKey)` + `Inflight`，渲染收敛成一个 `itemsIndexed(key=uiKey)` | ✅ v166 / M24 |
| P2 占位气泡 | 点发送/继续/重新生成当帧出 `生成中` + 呼吸点；回底分档 | ✅ v167 / M25 |
| P3 就地改写 + 平滑收缩 | 重新生成同一行改写；收缩/涨回显式驱动高度；入场按出厂序号 | ✅ v176 / M26 |
| **P4 动画收口** | `placementSpec` 只在非串流时挂；落库前 `smoothHeight` snap；**删除/撤回的位移动画** | ⬜ **下一批**（口径见 [r2 §13](../plan-chat-list-flash-r2.md)） |

> P3 之间那个 bug（点发送后列表先往下滑 140px）已在 v177 修掉，根因与判据见
> [M27](verification/2026-11-26-M27.md)：**基线没建立之前，`smoothHeight` 的 0 不是"上一个高度"**。

## 许可批（2026-11-26，按 [第三方审计](third-party-audit.md) 第 8 节执行）

| 项 | 状态 |
|---|---|
| 接受 AGPL-3.0、承认衍生 | ✅ [ADR 0009](../adr/0009-agpl-license.md) |
| `LICENSE` / `THIRD_PARTY_NOTICES.md` / `licenses/` | ✅ 全文取自上游，不手打 |
| APK 带许可文本（`assets/notices/`） | ✅ 构建期 `syncNotices` 同步，出货后可直接 `unzip -l` 验 |
| 删 ST 默认内容卡 + 自制演示卡 | ✅ `assets/cards/qinghe.png`（`tools/cards/make-demo-card.py` 生成）+ `BundledCardTest` 守解析 |
| APK 里「开源许可」页（设置 → 关于） | ⬜ 下一批 |
| `core/` 注释改成署名式表述 | ⬜ 下一批（**写清出处**，不是删掉，见 ADR 0009 第 2 条） |

## 悬着的事

1. **P4 还没动**：删除/撤回的位移动画（P3 撤了 `animateItem`，这一笔欠着）、`placementSpec` 门控、
   落库前 snap。**第 3 项要先拿真机 `tag=scroll` 落库那一帧的实测差值再改**，别盲改。
2. **需求批 E / F**：E = 胶囊→液态玻璃菜单（先做聊天页 ⋮ 与卡详情 ⋮ 两处原型）；
   F = 用户人设（**夹具先行**）。口径 [plan-req-2026-11-25.md](../plan-req-2026-11-25.md)。
3. **`fixtures/worldinfo/P1-1-seraphina-book.json` 仍是原版默认卡转出来的夹具**：随源码公开，
   但不随 APK 分发。要彻底处理得重录该链路夹具（需要原版在跑），成本大于收益，暂留。
4. **`‹N/M›` 只能翻最后一条**：原版只允许最后一条；"任意消息可翻版本"用户说**先待定**。
5. **审计 P0-3（`app` / `data` 零测试）仍未动**：建议补 `data` 层 JVM 测试（三级配置编解码、
   `.meta` 容错、DB 迁移、JSONL 往返），投入产出最高。
6. **P1-3 全树重组（`@Immutable` / 气泡文本 remember）刻意推迟**：等观感问题清空再做。
7. **世界书 outlet** 未做且缺夹具；**卡库分组**未做；`forbid_overrides`、"按角色禁用某段"未做。
8. **技术债**：`swipe_id` 不落库，重载靠 `indexOf(content)` 恢复位置。
9. **实验项（不进批次）**：E2 `swipe` 切换的 `animateContentSize`；E4 超长气泡的 `atBottom` 真机打点。

## 已知风险

- 超长消息（单条 269 行）是常态输入。任何"整段正文一个节点"或"会生成本地渲染层"的做法都要先算高度上限
  （`MessagePanel` / `SelectionProbe` 的教训在 pitfalls）。
- 本机编不了 `:app` / `:data`：改完必跑五个 `check-*` + `test-core.sh`，**缺 import / 类型错只能 CI 抓**
  （v177 第一版就是这么废的：`Int` 减 `Float` 返回 `Float` 却声明成 `Int`）。
- 聊天列表的位移/闪烁是**结构性的**，4 个通道（布局增删 / 滚动 / 绘制桥接 / 底部让位 IME）。
  桥接那条现在的硬约束：**基线没建立（`smoothHeight <= 0`）时差量恒为 0**，别再拿 0 当"上一个高度"。
- 覆盖保存卡**没有备份**（用户 2026-11-26 定的）：写坏了只能"新建一张"。
- `animateContentSize` 只允许挂在"非串流"的离散替换上，且与桥接互斥（r2 §4.3 / §13.1）。
