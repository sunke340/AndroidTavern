# 项目状态快照（2026-11-25 18:40）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 接手先看 [handoff.md](handoff.md)，界面叫什么看 [ui-map.md](../ui-map.md)，
> 必须成立什么看 [ui-invariants.md](../ui-invariants.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `df71772`（APK 那一版，tag `apk-m181`）；其后只有文档：`a437bc0` / `343804d` 词表、本页与接手卡 |
| 最新交付 | `artifacts/app-v150-df71772.apk`（还原二级页首项留白 + 世界书列表补回页头那 64dp） |
| 真机确认到哪 | `v148`（选字不拖动列表，用户："长按正常了"）；**`v150` 尚未回执** |
| CI | 铁律（含 `check-ui-map.sh` / `check-ui-invariants.sh`）/ 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` 全绿（9 个模块，98 条）+ `check-core-purity` / `check-kotlin-comments` / `check-kotlin-imports` / `check-ui-map` / `check-ui-invariants` |
| 规模 | `app` 约 41 个 kt 文件 / 约 11k 行；`data` 有 config + message_swipes（DB v3） |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 最近这三轮改了什么

| 主题 | 状态 |
|---|---|
| 选字真因：气泡底板用 Material3 `Surface`，其 `clip(shape)` 让超高气泡（18900px）在约 8k px 之后收不到触摸 → 换 `MessagePanel`（背景 + 描边） | ✅ v147 |
| 选字会拖动列表（焦点 `bringIntoView`，跳 427px = `trailingEdge - containerSize`）→ 聊天列表给 `NoFocusScrollSpec` | ✅ v148 |
| 思考强度只留会话 / 角色卡两级；两级滑块加「自定义」（默认串 `max`）；档案层整项移除 | ✅ v149，待验 |
| 模型抽屉收回后输入栏展开 + 键盘弹出（`SheetSpec.onDismiss`） | ✅ v149，待验 |
| 默认正则清标签规则：标签名改可选 → `</>` / `<>` 也清（补 3 条单测） | ✅ v149，待验 |
| 模型钮淡紫 12% 底 + 满色主色字；流式停止钮淡红 20% + 红方块 | ✅ v149，待验 |
| 二级页页头恢复"半透明"：`contentTopInset = 0.dp` + 让位挪进滚动容器（内容从页头底下穿过） | ✅ v149；两处间距问题在 v150 还原 |
| 首项留白还原：`DetailScrollUnderTop` = `DetailHeaderHeight`（不夹带呼吸值）；列表页补回 `+ DetailHeaderHeight + Space.l` | ✅ v150，待验 |
| 开场白气泡不挂动作行（判据 `isGreetingMessage`，与开场白条数无关） | ✅ v149，待验 |
| 选字诊断探针（`SelectionProbe.kt` / `tag=sel`） | ✅ 已整文件删除 |
| UI 词表 `docs/ui-map.md` + `check-ui-map.sh`（进 CI）+ `gen-ui-map.sh` | ✅ |
| 不变量规格 `docs/ui-invariants.md` + `check-ui-invariants.sh`（已覆盖 INV-02/03/05/07，进 CI） | ✅ 十条表已立，第一批重构未做 |

## 悬着的事

1. **v150 待真机回执**：M9 / M9.1 七条（页头间距、世界书列表留白、思考强度两级+自定义、
   抽屉收起后键盘、`</>`、两个圆钮配色、开场白动作行）—— 见 [device-verification.md](device-verification.md)。
2. **INV-01 没做**：页头的材质与让位还散在 11 个页面里。计划见 [ui-invariants.md](../ui-invariants.md)
   的"第一批"（收成 `DetailHeaderMode` 枚举，半天）。
3. **角色详情页页头**保持"内容在页头下面开始"（卡头是常驻海报，结构与其他二级页不同），
   要不要也让它穿过页头需要用户拍。
4. 世界书 **outlet** 未做且缺夹具；卡库**分组**未做；`forbid_overrides`、"按角色禁用某段"未做
   （现在顶替无条件生效）。

## 已知风险

- 超长消息（单条 269 行）是本项目的常态输入。任何"整段正文一个节点"或"会生成本地渲染层"的
  做法都要先算高度上限；`MessagePanel` 与 `SelectionProbe` 的教训都在
  [pitfalls.md](pitfalls.md) 与 [ui-invariants.md](../ui-invariants.md) 的 INV-02。
- 真机视觉无法在 CI 验证（无 KVM / 模拟器），所以"看起来对不对"只能靠 `device-verification.md`
  的编号条目 + 用户点一次；脚本只管结构与名字（`check-ui-map.sh` / `check-ui-invariants.sh`）。
- 词表里"一句话外观"这类人写的描述仍会过期：它只用来**对词**，不用来判断"现在是不是这个样子"。
