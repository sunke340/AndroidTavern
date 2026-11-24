# 接手卡（2026-11-25 00:45）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**：
> 现状 → [status.md](status.md)；规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)；
> 进度 → [compat-matrix.md](../compat-matrix.md)；历史与踩坑 → [pitfalls.md](pitfalls.md)。

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容它的文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)）。
- **基线**：代码 `0631bff` / tag `apk-m148` / 交付包 `artifacts/app-v117-0631bff.apk`。
  **用户 2026-11-25 00:40 真机确认："确实正常了"**（本轮三件事全部收口）。
- **没有悬着的事**：聊天跟随（含 A↔C 接缝）、页面转场掉帧、橡皮筋溢出——三条都已修并被日志/真机确认。
- **下一件事**（直接开工）：**P0-6 消息操作**，开工前先录 `P0-6-swipes` 夹具，见第五节。
- **铁律**：core 纯 JVM；兼容行为必须带 fixture；不猜原版；本机不构建；**能复用先复用**（第 10 条）。

## 一、最近三轮（v115 → v117）：结论 + 证据 + "别再回的方向"

| 问题 | 结论 | 证据（真机日志） | 别再做什么 |
|---|---|---|---|
| 串流时读气泡被顶走 / 旧气泡闪 / 拖动不跟手 | 阅读锚点补偿：量到长高 `Δh` 就 `dispatchRawDelta(Δh)` 把内容让回去 | 74 笔补偿全部 `+N == drift`、47 笔带 `拖动中`、0 笔失败；非拖动期间「顶Y」恒定 | 冻结文本（用户否掉）；`requestScrollToItem`（会 `itemAnimator.reset()` → 旧气泡重播淡入；还会取消拖动）；"手势期间不补" |
| A↔C 边界"收一下 / 沉一下" | 离开贴底把追高欠账折成滚动量；回到贴底对齐平滑高度 | `边界 离开贴底 欠账=33/117px 折成滚动=33/117px`、`回到贴底 欠账=53/12px 折成滚动=0` | 把 `平滑差` 当"没问题"（v114 我只凭 3px 样本就下过这个错结论） |
| 二级页转场丢几帧动画 | 起动画**之前**先预热新页首帧（`PageStack.warming`） | `帧 26 / 最长帧 74–83ms` → **`帧 35 / 最长帧 8–16ms`**；新页首帧 9–107ms | 为"点下去立刻动"去掉预热（那只是把成本挪回动画里） |
| 详情页各 tab 内容盖住 Tab 栏 | 橡皮筋的绘位移从来没裁剪；裁在滚动容器自己的槽位 | 同时间窗 `rubber 回弹 start raw=-444 visual=-224`（画高 81dp，Tab 栏 48dp） | 只给详情页打补丁 —— 根因在共享组件 `RubberOverscroll` |

## 二、聊天跟随（最终形态，别再改结构）

- **三场景**（`reverseLayout` 下）：贴底 `idx==0 && off==0`；读气泡自己 `idx==0 && off>0`；
  看更早的消息 `idx>0`（气泡**根本没被布局**，结构上就不动）。
- **贴底**：一个 Animatable 追实测高度差，`translationY` 平滑抬升（`Motion.Base`）。
- **读气泡**：文本照常长；每笔真实高度变化就滚回去同一笔量；**手势期间照补**（只抵掉长高那一笔，
  手指位移照常）；布局与滚动两趟，差量那一帧由 `translationY` 桥接。
- **边界**：离开贴底折成滚动量、回到贴底 snap 平滑高度。
- 诊断 `tag=scroll`：`模式=A/B/C`、`补偿 +Npx(drift=N)`、`拖动中`、`补偿失败`、`边界 …`、每轮总量。
- 细节与源码依据（`y_top = H + o - h`）在 [pitfalls](pitfalls.md) 最后三节。

## 三、转场与"首帧成本"

- `PageStack` 现在起动画前会 `warming` 两帧：新页已入栈但只画旧页（新页停在 p=0 的起跑位），
  等新页自己的第一帧跑完再 `running = true`。
- 日志 `tag=nav` 每次两行：`转场预热 … 新页首帧 Xms（等 Yms）` + `转场 … 帧 N，最长帧 Mms`。
  **X 是页面成本**（About 9–33 / 详情 41–91 / 内嵌世界书 83–107 / 聊天 90），**M 应 ≤20ms**。
- 减首帧的既有手段：能用列表里已有的字段先渲染就先渲染（详情页描述）、数据驱动的列表一律
  `LazyColumn`（详情页会话 tab 已改）。

## 四、待真机验（已出包，但没人逐项点过）

[status.md](status.md) 的「待真机验」三组：**启动与性能 / 世界书与设置 / 详情页与导航**。
新会话要做验收就照那张表从上到下点一遍。**聊天跟随 / 转场 / 橡皮筋不在其中**（已验）。

## 五、下一步优先级

1. **P0-6 消息操作**：swipe / 重新生成 / 继续 / 编辑 / 删除单条。原版**只有最后一条助手消息能
   swipe**（`script.js:9183 isMessageSwipeable`），`Generate('swipe')` 会把被换掉那条 `coreChat.pop()`；
   存储是 `swipes[] + swipe_id + swipe_info[]`（P0-7 的 jsonl 夹具里能看到）。
   **开工前先用 `capture.mjs` 录 `P0-6-swipes` 夹具**，会动 `ChatViewModel` 这条最热的状态机。
2. 世界书剩余：sticky 计时落库、`@@` 装饰器、outlet 位置。
3. **C 批模型档案**真机验收；**D 批三级继承**（会话 > 卡 > 全局，每项可跟随）。
4. 可选零活：橡皮筋"猛甩一次不碰屏"单测；详情页 Greeting tab 的备选开场白（`detail` 到齐后才排版，
   内容多时可能在动画中途掉一帧 —— 真要治就按 pitfalls「新页首帧」那套先占位再替换）。

## 六、这台机器与出包（每次都要用）

- **本机编不了 `:app` / `:data`**：只能跑 `tools/test-core.sh`、`tools/check-kotlin-comments.sh`、
  `check-kotlin-imports.sh`、`check-core-purity.sh`；Android 侧错误只有 CI 能暴露。
- **出包循环**：
  1. `git commit` → `git tag apk-mN && git push origin HEAD apk-mN`
  2. `bash tools/poll-ci.sh <sha>`（只认本次 sha 的 run；约 40s 一次）
  3. 下载 `releases/download/apk-mN/app-debug.apk`（tag release 钉死，不会被覆盖）
  4. **验 dex 里的 40-hex `BUILD_ID`**（`app-debug.apk` 大小经常撞车，v116/v117 都是 15753607B）
  5. `tools/verify-apk.sh <apk> 55:E0:…:0B:E7` 验签名
  6. `cp` 成 `artifacts/app-vNNN-<sha7>.apk` 并 `present`
- **日志**：设置 → 关于 → 导出日志（SAF）。排障入口见下一节。
- **文档约定**：`status.md` 只写现状、每次**整篇重写**；`compat-matrix.md` 是进度唯一真相；
  ADR **只增不改**；踩坑进 `pitfalls.md`；规则进两处 `AGENTS.md`（指令链 64 KB 上限）。

## 七、日志 tag 速查（排障从这儿进）

| tag | 看什么 |
|---|---|
| `nav` | 转场两行：`转场预热 … 新页首帧 Xms` + `转场 … 帧 N，最长帧 Mms`（M 大 = 动画期间在干活） |
| `scroll` | 聊天：`模式=A/B/C`、`补偿 +Npx(drift=N)`、`拖动中`、`边界 …`、`串流结束 N 笔/Mpx` |
| `rubber` | `回弹 start raw=… visual=…`、`fling 撞上限`、`回弹被打断`（raw 有上限 ±444） |
| `glass` | 玻璃档开关、着色器编译 ≥8ms、降采样图层回读 |
| `startup` / `wi` / `card` | 启动各步 +ms、世界书解析、读详情耗时（`读详情 <card> Xms 条目=N`） |
| `chat` / `profile` / `ui` / `regex` / `diag` | 发送/完成/失败、档案、主题、正则条数、导出日志 |

## 八、常用文件地图

| 想改什么 | 去哪 |
|---|---|
| 聊天 UI / 串流跟随 / 阅读锚点 | `ChatScreen.kt`（`smoothHeight` / `readingBubble` / `anchoredHeight` / 边界 effect） |
| 页面栈转场 / 预热 | `PageStack.kt`（`warming` / `running` / `progress`） |
| 橡皮筋 | `RubberOverscroll.kt`（最外层 `clipToBounds` 别删） |
| 状态机（几乎全部状态） | `ChatViewModel.kt`（`UiState` 一个 data class） |
| 角色详情页 / 卡配置二级页 | `CharacterDetailScreen.kt` / `CardConfigPageScreen.kt` |
| 行系统 / 列表形状 | `ListRow.kt`、`Style.kt` |
| 玻璃材质 | `Glass.kt`、`LiquidGlass.kt` + ADR 0005/0006/0007 |
| 启动（外观预读 / 会话并行 / 遮罩） | `ChatViewModel.init`、`MainActivity`、`MainScreen.StartupCurtain` |
| 缓存（卡面 / 世界书条目数） | `data/.../cards/impl/CardStore.kt`、`data/.../worldinfo/impl/FileWorldBookRepository.kt` |

## 九、这一轮踩的坑（一句话，细节全在 pitfalls）

1. `requestScrollToItem` 会 `itemAnimator.reset()` → 挂 `animateItem` 的气泡**重播淡入**；
   它还会 `scroll{}` 抢 mutex 取消拖动。要平移内容用 `dispatchRawDelta`。
2. **新页首帧**（组合+测量+录图层）落在动画里 = 丢几帧；修法是起动画前预热，不是减动画时长。
3. 任何 **`graphicsLayer` 平移**（橡皮筋 / 桥接 / 拖拽）都要问一句"它的槽位边界谁来裁" ——
   滚动容器自带 clip 管不到外面那层平移。
4. **长文本别等数据到齐才第一次排版**：能拿列表里已有的字段先顶上就先顶（详情页描述）。
5. 日志读法：`nav` 的「帧数 2–5」= 一次长帧；`wi`/`startup` 的数字能直接指认解析/GC/首帧。
