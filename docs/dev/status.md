# 项目状态快照（2026-11-24 15:56）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `7235d7f`（tag `apk-m138`；结构整理收尾版是 `apk-m118`） |
| 已交付 APK | `artifacts/app-v108-7235d7f.apk`（签名固定，覆盖安装已验证多次） |
| CI | 三个 job 全绿：铁律检查 / 纯 JVM 单测 / 出 debug APK |
| 本机测试 | `tools/test-core.sh` 全绿（9 个纯 JVM 模块，`:core:preset` 已含在内） |
| 规模 | `app` 34 个 kt 文件 / 8590 行；AGENTS 指令链 21 KB / 64 KB |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 现在能用的

| 块 | 状态 |
|---|---|
| 卡：PNG 导入、网格、详情四标签、内嵌书勾选、删卡连带会话 | ✅ 真机可用 |
| 聊天：SSE 流式、开场白、气泡/整行卡片、停止保留半截 | ✅ |
| 持久化：SQLite 会话/消息、会话列表、新建、长按删除 | ✅ |
| 设置：模型档案（多套、Key 加密）、采样、外观、世界书、正则、预设、关于 | ✅ |
| 导航：`Destination` + 自写 `PageStack`，返回时深页盖在上面滑走 | ✅ 用户验收过 |
| 列表：行系统 v2（分组面板 + 内缩分隔线 + 按下即亮）、会话分级时间 | 🟨 已出包，未见明确反馈 |
| 角色卡：满幅图 + 56dp 磨砂条 + 长按抽屉 | 🟨 同上 |
| 弹层 / 底栏滑块 / 底栏 tint / 组件预览页 | 🟨 同上 |
| 世界书引擎（关键字/递归/sticky/预算）、正则引擎 | 🟨 引擎夹具全绿，App 侧待真机 |
| **预设（P1-5）**：解析 + `prompt_order` + 导入/选择/编辑页拖动换位 | ✅ 用户验收（2026-11-24 下午） |

## 未见明确反馈的改动（真机点一遍即可）

| 项 | 怎么看 | 不对时改哪 |
|---|---|---|
| 会话行副行 = 最后一条消息 | 第二行是自己/角色说的最后一句，自己发的带「你：」 | `SessionsScreen.buildSessionItems` |
| 会话时间分级 + 今天/更早分组 | 今天给时刻、昨天、一周内周几、更早月-日 | `SessionTime.kt` |
| 模型页点行=切换 | 点整行立刻切默认，长按出编辑/删除 | `ModelPage` |
| 角色卡磨砂条 | 名字压在图上、底下 56dp 磨砂条（0.72）；看深色模式 | `CharactersScreen.CardBand` |
| 弹层"实不实" / 真不真糊 | **必须在彩色内容上看**（角色卡页长按一张卡）；日志 grep `液态档` | `LiquidGlassSpec.Sheet` / `LiquidGlassSpec` |
| 底栏：滑块不再从左侧滑来 | 二级页返回主页时滑块应在当前 tab 原位出现 | `TavernBottomBar.pillMeasured` |
| 底栏点按 tint / 页头下沿影子 | 6% 主色；聊天页页头影子 0.08 | `TavernBottomBar` / `DetailPage` |
| 删除确认钮是红的 | 长按会话/卡 → 删除 | `ConfirmDeleteDialog.kt` |
| 组件预览页 | 设置→关于→组件预览；拖滑杆松手才变 | `GalleryScreen.kt` |
| 设置里的导入在右上圆钮 | 预设 / 世界书 / 正则 三页：右上「+」，页面里不再有底部导入条 | `SettingsPages.kt` |
| 世界书条目页改用通用行 | 设置→世界书→点一本书：分组面板 + 行尾开关 + 两行预览 | `WorldBookEntriesScreen.kt` |
| 内嵌世界书与全局同形状 | 角色详情→配置→内嵌世界书：正文两行预览 + 行尾开关，页头带「N 条」（原来左侧复选框、一行关键字） | `WorldBookEntryRow.kt` |
| 世界书三态开关 + 批量菜单 | 两个条目页右上「⋮」→ 全部开启 / 全部关闭 / 恢复默认；强制开启的行整行淡红 + 开关变红，列表顶部一行小字「红色条目为强制开启」（只在有强制条目时出现） | `WorldBookEntryRow.kt` / `ChatViewModel` 的 `setAll*` |
| 卡里没写 `enabled` 的条目现在显示为关 | 以前显示开着但从不生效；现在就是关着的开关，打开即强制开启（标红） | `CardBookEntriesTest` |
| 两个世界书条目页的页头都是半透明的 | 列表从页头底下滑过、6% 透出内容 —— **这是设计**，别当 bug 修（v105 误修过一次） | `WorldBookEntriesScreen.kt` / `CardConfigPageScreen.kt` |
| 启动不再"先轻量后玻璃" | 冷启动第一帧就应该是玻璃（外观同步预读）；导出日志看 `tag=startup` 第一行的 ms | `ChatViewModel.applyAppearanceSync` |
| 启动时列表不再"空白半秒" | 会话/卡列表就绪前不显示"还没有会话/卡"；日志里「会话就绪」应早于「配置解密」 | `SessionsScreen` / `CharactersScreen` |
| 条目多的内嵌书进页不再卡 | 日志 `tag=nav` 的帧数应从 2–5 回到 30±、最长帧 ≤60ms；开书不再是 448ms 一帧 | `CardConfigPageScreen.CardWorldBookPage` |

## 下一步

1. **P0-6 消息操作**：swipe / 重新生成 / 编辑 / 删除单条。要先用原版录 swipe 夹具
   （确认 `chatMetadata` 与 `swipe_id` 到底怎么存），会动 `ChatViewModel` 这条最热的状态机。
2. **世界书剩下的**：sticky 计时状态落库、`@@` 装饰器、outlet 位置。
3. **C 批模型档案**：数据层与界面都完成，待真机验收。
4. **D 批三级继承**：会话 > 角色卡 > 全局，每项可"跟随"；解析器放 `:core` 带单测。

结构整理四轮（抽两个工具件 / AGENTS 瘦身 / 组件预览 / `ListRow.kt`）已收尾；
包分层与拆 `ChatViewModel` 已评估决定**不做**，理由见 [structure-audit.md](structure-audit.md) 顶部。

## 已知问题 / 风险（当前）

- **世界书的开关覆盖只在本机**：卡里 `enabled` 与服务端/PC 版仍是原样，不写回卡文件、不写回书文件。
- **批量「全部开启」可能撑爆提示词**：一本几百条的书全开，预算裁剪（P1-4）会兜底，但注入内容会明显变长。
- 卡内嵌书覆盖从**下标**换成了 **uid**，老版本那份覆盖在首次打开卡详情时一次性迁移，对不上的下标直接丢弃。
- **玻璃帧率从未量过**。发黏就给最贵的那块（底栏）单独传 `liquid = null`。
- **卡列表启动 394ms**：`CardStore.list()` 逐张 PNG 全量解析（`卡就绪 +740ms`）。
  下一轮按"字节数 + mtime 缓存名字/简介/条目数"处理。
- **世界书开关会重解析**：`setWorldBookEntryEnabled` 会 `loadWorldBook` + `refreshWorldBooks`，
  大书点一下要重读写两遍（改动前是常态；现在 `list()` 只数条目不算解析了）。
- **橡皮筋 `flingEaten` 修复未真机验**：拉到底 → 猛甩向上，撞顶后应立即回弹；
  日志 tag `rubber` 里 `回弹 start` 只该出现一次。
- 弹层**没有拖拽关闭**（只能点遮罩或返回键）。
- 世界书 **sticky 计时状态没落库**（App 重启重置）；`@@` 装饰器与 outlet 位置未做。
- 预设只做**全局顺序**；逐角色顺序、提示词正文编辑不做（见 compat-matrix P1-5）。
- 预设编辑页**没有每行的外框描边**：四角全圆 + 贴合 + 每行描边在几何上不能同时成立，
  取舍与替代方案见 pitfalls 最后一节。要外框就得改成"整组一圈"或"行间留缝"。
- 日志只到事件级，不含响应原文；进程被系统杀掉时抓不到。
- **未接预测式返回**（手势松手后才播动画，拖动时看不到上一级预览）。
- `apk-latest` 永远跟**最后一次 main 提交**重建（含纯文档提交），它的资产时间戳也不可靠；
  **以 `artifacts/` 里的文件 + dex 里的 `BUILD_ID` 为准**。
- APK 大小经常撞车（v96–v101 全是 15,703,923 字节）→ 只能核对 dex `BUILD_ID`。
- **本机编不了 `:app` / `:data`**：Android 侧的编译错误只有 CI 会暴露，一轮 4 分钟。

## 这台机器的现实

- 能跑：`tools/test-core.sh`、`tools/check-*.sh`、Playwright 录原版夹具、HTML 出设计样张
- 不能跑：`assembleDebug`（1.7 GiB 内存，必挂）、模拟器（无 KVM）、`:app` 编译
- 出包：`git tag apk-mN && git push origin HEAD apk-mN` → 轮询 `apk-latest` → 验 dex `BUILD_ID` + 签名
- 细节见 [runbook.md](runbook.md) 与 [environment.md](../environment.md)
