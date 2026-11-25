# 项目状态快照（2026-11-25 20:05）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 接手先看 [handoff.md](handoff.md)，界面叫什么看 [ui-map.md](../ui-map.md)，
> 必须成立什么看 [ui-invariants.md](../ui-invariants.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `e19678f`（第二批不变量 + 补三处橡皮筋；tag `apk-m184`）；上游 `f41303c` / v152 已真机确认 |
| 最新交付 | `artifacts/app-v153-e19678f.apk`（BUILD_ID 已验 = `e19678f…`，签名与 debug 指纹一致） |
| 真机确认到哪 | `v152`（2026-11-25 晚，M11 通过：页头收口没带歪观感）；**v153 待验**（M12：三处补挂的橡皮筋） |
| CI | 铁律（含 `check-ui-map.sh` / `check-ui-invariants.sh`）/ 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` 全绿（9 个模块，98 条）+ `check-core-purity` / `check-kotlin-comments` / `check-kotlin-imports` / `check-ui-map` / `check-ui-invariants` |
| 规模 | `app` 约 41 个 kt 文件 / 约 11k 行；`data` 有 config + message_swipes（DB v3） |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 最近这几轮改了什么

| 主题 | 状态 |
|---|---|
| **第二批不变量**：INV-04（玻璃三铁律）/ INV-06（每个可滚面挂橡皮筋）/ INV-08（不许写字面色值）判据进脚本；顺带补掉三处真漏（档案编辑、组件预览、查看请求正文） | ✅ v153，待验（M12） |
| **页头收口（INV-01 第一批）**：`DetailHeaderMode { Frosted, Translucent }` + `contentSpacing`；骨架交出最终 top；删 `contentTopInset` / `frostedHeader` / `DetailScrollUnderTop`；12 处调用对齐 | ✅ v152 真机确认（M11） |
| INV-01 脚本闸门（旧参数名 / `calculateTopPadding() +` / 未显式传 `headerMode`），三条判据都自测会红 | ✅ |
| 卡配置的「对话样式」「关于这张卡」被内嵌世界书页顶掉（`6f4dd53` 常量分发）→ 改回按页判据；本轮又拆成两个骨架调用 | ✅ v151 真机确认 |
| 页头材质与首项留白（`8ee1b72` → `df71772`：Surface 裁剪、焦点滚动、半透明页头、12dp/64dp 两次间距） | ✅ v150 真机确认，这一系列结案 |
| 思考强度只留会话 / 角色卡两级；两级滑块加「自定义」；档案层整项移除 | ✅ v150 确认 |
| 模型抽屉收回后输入栏展开 + 键盘弹出 | ✅ v150 确认 |
| 默认正则：标签名可选 → `</>` / `<>` 也清，`<foo>` 保留 | ✅ v150 确认 |
| 模型钮淡紫 12% + 满色主色字；流式停止钮淡红 20% + 红方块 | ✅ v150 确认 |
| 开场白气泡不挂动作行（判据 `isGreetingMessage`） | ✅ v150 确认 |
| UI 词表 `docs/ui-map.md` + `check-ui-map.sh` + `gen-ui-map.sh` | ✅ 进 CI |
| 不变量规格 `docs/ui-invariants.md` + `check-ui-invariants.sh`（**十条里八条脚本管**：INV-01/02/03/04/05/06/07/08） | ✅ |

## 悬着的事

1. **v153 待真机复验**：M12 —— 档案编辑页 / 组件预览页 / 「查看请求」正文三处的**回弹**；
   其余页面的回弹手感不应变化（本轮没动它们）。玻璃相关代码完全没动。
2. **十条不变量里还剩两条只有半自动**：INV-09（由 `test-core.sh` 的单测管）、
   INV-10（由 `check-ui-map.sh` 管）—— 脚本都在 CI 里，没有待做项。
3. **角色详情页页头**保持"内容在页头下面开始"（卡头是常驻海报），是唯一具名例外；
   要不要也让它穿过 → 单独一轮（卡头 + TabRow 要一起重排）。
4. 世界书 **outlet** 未做且缺夹具；卡库**分组**未做；`forbid_overrides`、"按角色禁用某段"未做
   （现在顶替无条件生效）。

## 已知风险

- 超长消息（单条 269 行）是本项目的常态输入。任何"整段正文一个节点"或"会生成本地渲染层"的
  做法都要先算高度上限；`MessagePanel` 与 `SelectionProbe` 的教训都在
  [pitfalls.md](pitfalls.md) 与 [ui-invariants.md](../ui-invariants.md) 的 INV-02。
- **`if (常量)` 包两套内容 = 静默死代码**：`6f4dd53` 的 `val scrollUnderHeader = true` 让卡配置
  另两页在 v149 / v150 里显示错内容，编译、脚本、回执三套防线都没挡住（M10）。
  分发条件写页面判据；真机清单要点**入口行**，不能只点"这次改到的那一行"。
- 真机视觉无法在 CI 验证（无 KVM / 模拟器），所以"看起来对不对"只能靠 `device-verification.md`
  的编号条目 + 用户点一次；脚本只管结构与名字（`check-ui-map.sh` / `check-ui-invariants.sh`）。
- 词表里"一句话外观"这类人写的描述仍会过期：它只用来**对词**，不用来判断"现在是不是这个样子"。
- **grep 判据的白名单必须按文件名写清理由**，而且判据要按"它约束的是谁"划范围：INV-04 的
  `Modifier.blur` 只在 `Glass.kt` 外才违规（自研玻璃内部合法地糊录好的图层）；INV-04 的
  `record(` 要数**顶层**逗号（嵌套括号里的逗号会误报），第一次写完就误报了 `Glass.kt:122`。
