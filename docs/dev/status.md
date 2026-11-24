# 项目状态快照（2026-11-24 14:15）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `93703e7`（tag `apk-m131`；结构整理收尾版是 `apk-m118`） |
| 已交付 APK | `artifacts/app-v101-93703e7.apk`（签名固定，可覆盖安装） |
| CI | 三个 job 全绿：铁律检查 / 纯 JVM 单测 / 出 debug APK |
| 本机测试 | `tools/test-core.sh` 全绿（9 个纯 JVM 模块） |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 现在能用的

| 块 | 状态 |
|---|---|
| 卡：PNG 导入、网格、详情四标签、内嵌书勾选、删卡连带会话 | ✅ 真机可用 |
| 聊天：SSE 流式、开场白、气泡/整行卡片、停止保留半截 | ✅ |
| 持久化：SQLite 会话/消息、会话列表、新建、长按删除 | ✅ |
| 设置：模型档案（多套、Key 加密）、采样、外观、世界书、正则、预设、关于 | ✅ |
| 导航：`Destination` + 自写 `PageStack`，返回时深页盖在上面滑走 | ✅ 用户验收过 |
| 列表：行系统 v2（分组面板 + 内缩分隔线 + 按下即亮）、会话分级时间 | 🟨 已出包待真机确认 |
| 角色卡：满幅图 + 56dp 磨砂条 + 长按抽屉 | 🟨 同上 |
| 弹层 / 底栏 / 组件预览 / 预设编辑页 | 🟨 同上 |
| 世界书引擎（关键字/递归/sticky/预算）、正则引擎 | 🟨 引擎夹具全绿，App 侧待真机 |
| 预设：解析 + `prompt_order`（导入/选择/编辑页拖动换位） | 🟨 内核夹具全绿，界面待真机 |

## 待真机确认（当前清单）

| 项 | 怎么看 | 不对时改哪 |
|---|---|---|
| 会话行副行 = 最后一条消息 | 第二行是自己/角色说的最后一句，自己发的带「你：」 | `SessionsScreen.buildSessionItems` |
| 会话时间分级 + 今天/更早分组 | 今天给时刻、昨天、一周内周几、更早月-日 | `SessionTime.kt` |
| 模型页点行=切换 | 点整行立刻切默认，长按出编辑/删除 | `ModelPage` |
| 角色卡磨砂条 | 名字压在图上、底下 56dp 磨砂条（0.72）；看深色模式 | `CharactersScreen.CardBand` |
| 弹层"实不实" | **必须在彩色内容上看**（角色卡页长按一张卡） | `LiquidGlassSpec.Sheet.faceWhite` |
| 弹层是否真糊 | 顶边有折射、面里是糊色块 | 日志 grep `液态档`，`着色器=false` = 没跑 |
| 底栏点按 tint | 点一下有 6% 主色，与 14% 滑动紫块叠加 | `TavernBottomBar` 的 `0.06f` |
| 页头下沿影子 | 聊天页页头下面那条，0.16 → 0.08 | `DetailPage` 的 `shadowScale = 0.5f` |
| 底栏让位再弹层 | 开弹层时底栏先滑走，弹层随后上来 | `SheetEnterDelayMillis` |
| 删除确认钮是红的 | 长按会话/卡 → 删除 | `ConfirmDeleteDialog.kt` |
| 组件预览页 | 设置→关于→组件预览；拖滑杆松手才变 | `GalleryScreen.kt` |
| **滑块不再从左侧滑来** | 任一二级页返回主页，滑块应在当前 tab **原位出现**；切 tab 才滑 | `TavernBottomBar.pillMeasured` |
| **预设页与编辑页（v101）** | 选中标记在**左侧**（同模型页的 `ActiveMark`）；长按预设 → 「编辑」；编辑页每行是**四角全圆的胶囊**（贴合、无描边，衔接处一条 1px 线，两端让出圆角），拖动只抬投影+放大、形状全程不变（落下也不跳）；拉过头有橡皮筋；右上圆钮 → 菜单「恢复默认顺序」 | `SettingsPages.PresetPage` / `PresetEditScreen.kt` |
| v94 是纯重构 | 抽验会话/模型/世界书三处列表外观与手势没变 | `ListRow.kt` |

## 下一步（按顺序）

1. **P0-6 消息操作**：swipe / 重新生成 / 编辑 / 删除单条。要先用原版录 swipe 夹具
   （看 `chatMetadata` 与 `swipe_id` 到底是哪种存法），会动 `ChatViewModel` 这条最热的状态机。
2. **世界书剩下的**：sticky 计时状态落库、`@@` 装饰器、outlet 位置。
3. **C 批模型档案**：数据层与界面都完成，待真机验收。
4. **D 批三级继承**：会话 > 角色卡 > 全局，每项可"跟随"；解析器放 `:core` 带单测。

结构整理四轮（抽两个工具件 / AGENTS 瘦身 / 组件预览 / `ListRow.kt`）已收尾；
包分层与拆 `ChatViewModel` 已评估决定**不做**，理由见 [structure-audit.md](structure-audit.md) 顶部。

## 已知问题 / 风险（当前）

- **玻璃帧率从未量过**。发黏就给最贵的那块（底栏）单独传 `liquid = null`。
- **橡皮筋 `flingEaten` 修复未真机验**：拉到底 → 猛甩向上，撞顶后应立即回弹；
  日志 tag `rubber` 里 `回弹 start` 只该出现一次。
- 弹层**没有拖拽关闭**（只能点遮罩或返回键）。
- 世界书 **sticky 计时状态没落库**（App 重启重置）；`@@` 装饰器与 outlet 位置未做。
- 预设只做**全局顺序**；逐角色顺序、提示词正文编辑不做（见 compat-matrix P1-5）。
- 日志只到事件级，不含响应原文；进程被系统杀掉时抓不到。
- **未接预测式返回**（手势松手后才播动画，拖动时看不到上一级预览）。
- `apk-latest` 永远跟**最后一次 main 提交**重建（含纯文档提交），它的 BUILD_ID 不等于交付包的 sha；
  **以 `artifacts/` 里的文件为准**。
- APK 大小经常撞车（v93 与 v94 都是 15,540,083 字节）→ 必须核对 dex 里的 `BUILD_ID`。
- **本机编不了 `:app` / `:data`**：Android 侧的编译错误只有 CI 会暴露，一轮 4 分钟。

## 这台机器的现实

- 能跑：`tools/test-core.sh`、`tools/check-*.sh`、Playwright 录原版夹具、HTML 出设计样张
- 不能跑：`assembleDebug`（1.7 GiB 内存，必挂）、模拟器（无 KVM）、`:app` 编译
- 出包：`git tag apk-mN && git push origin HEAD apk-mN`，然后轮询 `apk-latest`
- 细节见 [runbook.md](runbook.md) 与 [environment.md](../environment.md)
