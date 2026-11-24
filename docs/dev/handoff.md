# 接手卡（2026-11-24 23:00）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**：
> 现状 → [status.md](status.md)；规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)；
> 进度 → [compat-matrix.md](../compat-matrix.md)；历史与踩坑 → [pitfalls.md](pitfalls.md)。

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容它的文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)）。
- **基线**：代码 `8a88c0f` / tag `apk-m146` / 交付包 `artifacts/app-v115-8a88c0f.apk`。
- **现在悬着一件事**（新会话第一件事，见第二节）：聊天页"读流式气泡时阅读位置会不会被顶走"
  —— 冻结已按用户要求撤掉，换成阅读锚点补偿，**等 v114 手机实测 + `tag=scroll` 日志**。
- **铁律**：core 纯 JVM；兼容行为必须带 fixture；不猜原版；本机不构建；**能复用先复用**（第 10 条）。

## 一、这一轮（2026-11-24 下午 → 晚上）做了什么

用户逐条提、逐条修的节奏，全部已出包：

1. **预设 P1-5 收尾**（当天早先的用户验收）：导入/选择/编辑页拖动换位、恢复默认、顺序列表三版形状定稿。
2. **设置页整理**：预设 / 世界书 / 正则三页的「导入」收进右上圆钮，去掉与页头重名的小标题，
   列表自己用 16dp 顶距；空状态文案改成"点右上「+」"。
3. **世界书条目**：全局书与卡内嵌书共用 `WorldBookEntryRow`（两行正文预览 + 行尾开关）；
   开关做**三态**（跟随 / 强关 / **强制开启**），强制开启整行 6% 红底 + 红开关 + 列表顶部一行小字说明；
   页头右上「⋮」批量菜单（全部开启 / 全部关闭 / 恢复默认）；卡里 `enabled` 缺省语义改成与原版一致（没写=关）；
   覆盖键从**下标**迁到 **uid**（老覆盖一次性迁移）；卡内嵌书页改成 `LazyColumn`（原来 `ListGroup`
   会在进页第一帧组合全部条目，真机 448ms/帧，转场动画被吃掉）。
4. **启动三连修**（都是用户真机反馈 + 日志定位）：
   - 外观（主题/视觉/转场）改成**同步预读**，不再排在 Keystore 解密后面 → 不再"先轻量后玻璃"；
   - 会话列表单独先发、与配置链并行；`sessionsLoaded` / `cardsLoaded` 没读完不显示空状态；
   - 首屏头像**预热** + 占位块垫在图片下面（不再闪一帧空白）；
   - 卡面信息按"字节数+mtime"缓存（`cards/.meta`），`list()` 不再逐张解析 PNG；
   - 新增 `StartupCurtain`：就绪前整屏主题底色（并吃掉触摸），就绪后 180ms 整块淡出；
   - 系统窗口/Android 12+ splash 底色改成主题底色（原来是 `#FFFBFE` 的白色）+
     `MainActivity` 运行时按真实主题再对齐一次。
5. **详情页导航**：标签页存进 `UiState.detailTab`（页面被压栈后会移出组合树，局部 `remember` 会丢）；
   详情页「新建会话」后返回直接落「会话」栏；四个标签补 `rubberOverscroll`。
6. **聊天串流跟随**：贴底时"动画追高度差"的柔和抬升（v112 的"累积+收回"会抽搐，已废弃）；
   不在底部时不动视口。
7. **v114：撤掉串流冻结，改成阅读锚点补偿**（用户 2026-11-24 晚点名）。三个场景按
   `reverseLayout` 的锚点语义分开：贴底照旧追高度；看更早的消息本来不动；
   **停在气泡自己身上时把滚动偏移补回 `Δh`**（`requestScrollToItem`），用一帧 `translationY`
   桥接布局与滚动两趟的差量。补偿只认量到的高度变化，`isScrollInProgress` 一律让位。
   新增 `tag=scroll` 诊断（模式/补偿/跳过/总量）。**只过了本机检查与 CI 编译，真机未验。**

日志定位的两条硬事实（以后还用得上）：`tag=nav` 的 **帧数只有 2–5 = 一次长帧**；
`tag=wi` / `tag=startup` 的数字能直接指认是解析、GC 还是首帧。读法见 [runbook](runbook.md) 6.5.1。

## 二、悬而未决（**新会话先问这个**）

**问题**：聊天页往上翻历史时，串流气泡还要不要继续长？

- 现状（v113）：只有"第一可见项就是串流气泡自己"（`!atBottom && firstVisibleItemIndex == 0`）
  时**冻结文本**，其余情况照常长（视口本来就稳）。
- 用户 2026-11-24 晚：不想要冻结，问"是不是实现上有困难"。**分析已经给过**（见
  [pitfalls](pitfalls.md) 最后一节），三条路线：
  - **A** 保持现状：锚点项冻结文本；
  - **B**（推荐）冻结**高度**但内容照常追加：给气泡套 `height(冻结值)` + `clipToBounds`，
    布局高度不变、文本继续长，零抖动，改动几行；
  - **C** 真生长 + 每次长高把滚动偏移减 δ（`requestScrollToItem`）：最符合直觉，但布局与滚动是两趟，
    可能每行 1 帧回拉，且要和拖拽/rubber 抢，需真机调。
- **别自作主张选**。用户偏好：先给分析、他点头再动手；改动量大的方案要说明代价。

## 三、待真机验（全部已出包，没人逐项点过）

[status.md](status.md) 的「待真机验」按主题分了四组（启动与性能 / 世界书与设置 / 详情页与导航 / 聊天跟随）。
新会话如果要做验收，照那张表从上到下点一遍即可。

## 四、下一步优先级

1. 先解决第二节那件事（用户点名要的，且改动小）。
2. **P0-6 消息操作**：swipe / 重新生成 / 继续 / 编辑 / 删除单条。
   注意：原版**只有最后一条助手消息能 swipe**（`script.js:9183 isMessageSwipeable`），
   `Generate('swipe')` 会把被换掉的那条 `coreChat.pop()` 掉；存储是 `swipes[] + swipe_id + swipe_info[]`
   （P0-7 的 jsonl 夹具里能直接看到）—— 开工前先用 `capture.mjs` 录 `P0-6-swipes` 夹具。
3. 世界书剩余：sticky 计时落库、`@@` 装饰器、outlet 位置。
4. C 批模型档案真机验收；D 批三级继承（会话 > 卡 > 全局）。

## 五、这台机器与出包（每次都要用）

- **本机编不了 `:app` / `:data`**：只能跑 `tools/test-core.sh`、`tools/check-kotlin-comments.sh`、
  `check-kotlin-imports.sh`、`check-core-purity.sh`；Android 侧错误只有 CI 能暴露。
- **出包循环**：
  1. `git commit` → `git tag apk-mN && git push origin HEAD apk-mN`
  2. `bash tools/poll-ci.sh <sha>`（只认本次 sha 的 run；约 40s 一次；`TAVERN_POLL_TRIES/SLEEP` 可调）
  3. 下载 `releases/download/apk-latest/app-debug.apk`
  4. **验 dex 里的 40-hex `BUILD_ID`**（APK 大小经常撞车，v96–v101 全长一样）
  5. `tools/verify-apk.sh <apk> 55:E0:...:0B:E7` 验签名
  6. `cp` 成 `artifacts/app-vNNN-<sha7>.apk` 并 `present`
- **日志**：设置 → 关于 → 导出日志（SAF）。tag：`startup` / `wi` / `nav` / `glass` / `rubber` / `chat` / `card`。
- **文档约定**：`status.md` 只写现状、每次**整篇重写**；`compat-matrix.md` 是进度唯一真相；
  ADR **只增不改**；踩坑与"为什么是这个数"进 `pitfalls.md`；规则进两处 `AGENTS.md`（指令链 64 KB 上限）。
- CI 轮询已收进 `tools/poll-ci.sh`（按 sha 过滤 run + 读 release body），不再依赖 `/tmp`。

## 六、常用文件地图

| 想改什么 | 去哪 |
|---|---|
| 聊天 UI / 串流跟随 / 阅读锚点 | `app/src/main/kotlin/app/tavern/ChatScreen.kt`（`smoothHeight` / `readingBubble` / `anchoredHeight`） |
| 状态机（几乎全部状态都在这里） | `ChatViewModel.kt`（`UiState` 一个 data class） |
| 页面栈转场 | `PageStack.kt` |
| 行系统 / 列表形状 | `ListRow.kt`、`Style.kt` |
| 玻璃材质 | `Glass.kt`、`LiquidGlass.kt` + ADR 0005/0006/0007 |
| 世界书条目行 | `WorldBookEntryRow.kt` |
| 启动（外观预读 / 会话并行 / 遮罩） | `ChatViewModel.init`、`MainActivity`、`MainScreen.StartupCurtain` |
| 缓存（卡面 / 世界书条目数） | `data/.../cards/impl/CardStore.kt`、`data/.../worldinfo/impl/FileWorldBookRepository.kt` |
