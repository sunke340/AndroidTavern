# 项目状态快照（2026-11-25 00:45）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 接手先看 [handoff.md](handoff.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `0631bff`（tag `apk-m148`） |
| 已交付 APK | `artifacts/app-v117-0631bff.apk`（签名/`BUILD_ID` 已核；v116/v115/v114 也都在 `artifacts/` 里可回退） |
| CI | 三个 job 全绿：铁律 / 纯 JVM 单测 / 出 debug APK（v117：main 45434548076、tag 45434548682） |
| 本机测试 | `tools/test-core.sh` 全绿（9 个纯 JVM 模块）+ 三个 `check-*.sh` |
| 规模 | `app` 35 个 kt 文件 / 约 9.0k 行；AGENTS 指令链约 24 KB / 64 KB |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 现在能用的

| 块 | 状态 |
|---|---|
| 卡：PNG 导入、网格、详情四标签、内嵌书勾选、删卡连带会话 | ✅ 真机可用 |
| 聊天：SSE 流式、开场白、气泡/整行卡片、停止保留半截 | ✅ |
| 聊天：**串流跟随 + 阅读锚点补偿 + A↔C 接缝** | ✅ 真机验过（用户 2026-11-25："确实正常了"） |
| 导航：`Destination` + 自写 `PageStack`，**起动画前预热新页** | ✅ 真机验过（转场 `帧 35 / 最长帧 8–16ms`） |
| 橡皮筋：拉伸位移裁在自己槽位里 | ✅ 真机验过（Tab 栏不再被内容盖住） |
| 持久化：SQLite 会话/消息、会话列表、新建、长按删除 | ✅ |
| 设置：模型档案（多套、Key 加密）、采样、外观、世界书、正则、预设、关于 | ✅ |
| **预设（P1-5）**：解析 + `prompt_order` + 导入/选择/编辑页拖动换位 | ✅ 用户验收（2026-11-24） |
| **世界书条目**：三态开关（跟随/强关/强制开启）、批量菜单、两页半透明页头与懒加载 | ✅ 用户反馈已修（2026-11-24 晚） |
| **启动**：外观预读、会话并行、头像预热、卡面缓存、整屏遮罩一次浮现 | ✅ 用户反馈已修 |
| 列表行系统 v2、角色卡磨砂条、弹层、底栏细节、组件预览页 | 🟨 已出包，未见明确反馈 |

## 聊天跟随（A/B/C 三场景，最终形态）

| 场景 | 判据（`reverseLayout`） | 行为 |
|---|---|---|
| **A 贴底** | `firstVisibleItemIndex == 0 && offset == 0` | 文本照常长；Animatable 追实测高度差，`translationY` 平滑抬升 |
| **B 看更早的消息** | `firstVisibleItemIndex > 0` | 气泡根本没被布局 → 什么都不做 |
| **C 读气泡自己** | `firstVisibleItemIndex == 0 && offset > 0` | 文本照常长（**不冻结**）；每笔长高 `dispatchRawDelta(Δh)` 顶回去；**手势期间照补**；桥接一帧 |

- 边界：离开贴底把"追高欠账"折成滚动量；回到贴底把平滑高度对齐。
- 日志 `tag=scroll`：`模式=A/B/C`、`补偿 +Npx(drift=N)`、`拖动中`、`补偿失败`、`边界 …`、每轮总量。

## 转场（`PageStack`）

- 起动画**之前**先 `warming` 两帧：新页入栈但只画旧页（新页停在 p=0 起跑位），等新页自己的
  第一帧跑完再开动画 → 转场满帧（实测 35 帧 / 最长帧 8–16ms）。
- 日志 `tag=nav` 每次两行：`转场预热 … 新页首帧 Xms（等 Yms）`（页面成本，9–107ms）+
  `转场 … 帧 N，最长帧 Mms`（动画期间，应 ≤20ms）。
- 详情页首帧减负：描述先用卡列表里的 `description` 顶上、会话 tab 已改 `LazyColumn`。

## 待真机验（已出包，但没人逐项点过）

### 启动与性能

| 项 | 怎么看 | 不对时改哪 |
|---|---|---|
| 启动不再"先轻量后玻璃" | 第一帧就是玻璃；日志 `tag=startup` 第一行是个位 ms | `ChatViewModel.applyAppearanceSync` |
| 启动时列表不再"空白半秒" | 会话/卡未读完不显示"还没有…"；「会话就绪」应早于「配置解密」 | `SessionsScreen` / `CharactersScreen` |
| 启动只浮现一次 | 先整屏底色（吃掉触摸），就绪后 180ms 淡出 | `MainScreen.StartupCurtain` |
| 启动第一眼不是白色 | 窗口/splash 底色跟主题 | `themes.xml` / `MainActivity` |
| 启动头像不闪 | 会话行头像出现即成品；日志 `头像预热 N 张 Xms` | `CardAvatar.kt` |
| 卡列表快 | 升级后第一次全量解析，之后走 `cards/.meta` 缓存 | `CardStore.list` |

### 世界书 / 设置

| 项 | 怎么看 | 不对时改哪 |
|---|---|---|
| 设置里的导入在右上圆钮 | 预设 / 世界书 / 正则三页：右上「+」，底部没有导入条 | `SettingsPages.kt` |
| 条目页用通用行 | 设置→世界书→点一本书：分组面板 + 行尾开关 + 两行预览 | `WorldBookEntriesScreen.kt` |
| 三态开关 + 批量菜单 | 右上「⋮」→ 全部开启/全部关闭/恢复默认；强制开启整行淡红 + 红开关 | `WorldBookEntryRow.kt` |
| 卡里没写 `enabled` 的条目显示为关 | 打开即强制开启（标红） | `CardBookEntriesTest` |
| 两个条目页页头都是半透明的 | 列表从页头底下滑过、6% 透出内容 —— **这是设计**，别当 bug 修 | `WorldBookEntriesScreen.kt` / `CardConfigPageScreen.kt` |

### 详情页 / 导航 / 其他

| 项 | 怎么看 | 不对时改哪 |
|---|---|---|
| 详情页标签"从哪进回哪" | 会话标签进聊天、配置标签进二级页，返回停原标签 | `UiState.detailTab` |
| 详情页新建会话后返回落主页会话 | 卡详情点「新建会话」→ 返回「会话」栏 | `startSessionForCard(leaveDetail=true)` |
| 详情页四标签有橡皮筋 | 拉到头能继续拖、松手回弹 | `CharacterDetailScreen` 各 tab |
| 删除确认钮是红的 | 长按会话/卡 → 删除 | `ConfirmDeleteDialog.kt` |
| 组件预览页 | 设置→关于→组件预览；拖滑杆松手才变 | `GalleryScreen.kt` |
| 会话时间分级 / 副行最后一句 | 今天给时刻、昨天、一周内周几；第二行是最后一句 | `SessionTime.kt` / `SessionsScreen` |
| 模型页点行=切换 | 点整行切默认，长按编辑/删除 | `ModelPage` |
| 底栏滑块 / tint / 页头影子 | 滑块在当前 tab 原位出现；6% 主色；聊天页影子 0.08 | `TavernBottomBar` / `DetailPage` |

## 下一步

1. **P0-6 消息操作**：swipe / 重新生成 / 继续 / 编辑 / 删除单条。先录 `P0-6-swipes` 夹具；
   原版只有最后一条助手消息能 swipe，存储 `swipes[] + swipe_id + swipe_info[]`。
2. 世界书剩余：sticky 计时落库、`@@` 装饰器、outlet 位置。
3. **C 批模型档案**真机验收；**D 批三级继承**（会话 > 卡 > 全局，每项可跟随）。

## 已知问题 / 风险（当前）

- **串流中往 transcript 加消息会插入一项**（发新消息那一下），`reverseLayout` 的锚点按下标走、
  会整体错位一条 —— 与串流增长无关，属既有行为；要修得给消息加稳定 key。
- **详情页 Greeting tab**：备选开场白要等 `detail` 到齐才第一次排版；内容特别多时可能落在转场里
  掉一帧。要治就按 pitfalls「新页首帧」那套（先占位再替换）。
- **世界书开关会重解析**：`setWorldBookEntryEnabled` 会 `loadWorldBook` + `refreshWorldBooks`，
  大书点一下要重读写两遍。
- **世界书的开关覆盖只在本机**：不写回卡文件/书文件；卡内嵌书覆盖用 uid。
- **批量「全部开启」可能撑爆提示词**：预算裁剪兜底，但注入量变长。
- **玻璃帧率从未量过**；发黏就给最贵的那块（底栏）单独传 `liquid = null`。
- 弹层**没有拖拽关闭**；世界书 sticky 状态不落库；预设只做全局顺序。
- 日志只到事件级，不含响应原文；进程被杀时抓不到。
- **未接预测式返回**（拖动时看不到上一级预览）。
- `apk-latest` 跟**最后一次 main 提交**重建（含纯文档提交），资产时间戳不可靠；
  **以 `artifacts/` 里的文件 + dex `BUILD_ID` 为准**；APK 大小经常撞车（v116/v117 同尺寸）。
- **本机编不了 `:app` / `:data`**：Android 侧编译错误只有 CI 会暴露（一轮约 4 分钟）。

## 这台机器的现实

- 能跑：`tools/test-core.sh`、`tools/check-*.sh`、Playwright 录原版夹具、HTML 出设计样张
- 不能跑：`assembleDebug`（内存不足必挂）、模拟器（无 KVM）、`:app` 编译
- 出包：`git tag apk-mN && git push origin HEAD apk-mN` → `bash tools/poll-ci.sh <sha>` → 验 dex `BUILD_ID` + 签名
- 细节见 [runbook.md](runbook.md) 与 [environment.md](../environment.md)
