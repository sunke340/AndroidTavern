# 接手卡（2026-11-25 00:35）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**：
> 现状 → [status.md](status.md)；规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)；
> 进度 → [compat-matrix.md](../compat-matrix.md)；历史与踩坑 → [pitfalls.md](pitfalls.md)。

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容它的文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)）。
- **基线**：代码 `0631bff` / tag `apk-m148` / 交付包 `artifacts/app-v117-0631bff.apk`。
- **聊天跟随已收**：冻结撤掉 → 阅读锚点补偿（`dispatchRawDelta`），v115 用户真机验过"都正常了"；
  A↔C 接缝 v116 已修、待验。
- **转场丢帧已收（v116，真机验）**：`PageStack` 起动画前预热新页首帧（`warming`）→ 全部转场
  `帧 35 / 最长帧 8–16ms`（改前 26 帧 / 74–83ms）。预热数字本身也变成了"页面成本"的度量
  （9–107ms），要看的是它。
- **v117 修的是橡皮筋**：`rubberOverscroll` 的拉伸位移之前没裁剪，会画到自己的槽位外面
  （详情页 Tab 栏被内容盖住的真因）。现在最外层 `clipToBounds`。等真机确认观感没变差。
- **下一件事**（新会话直接开工）：P0-6 消息操作，见第四节。
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
7. **聊天跟随（v114 → v115，已真机验）**：用户点名不要"冻结"。撤掉冻结，改成**阅读锚点补偿**：
   贴底照旧追高度；看更早的消息本来不动；停在气泡自己身上时把这一笔长高滚回去。
   - v114 用 `requestScrollToItem` 补偏移 → 真机报两条：**旧气泡一直闪**（它内部
     `itemAnimator.reset()`，挂 `animateItem` 的气泡每补一次重播淡入）、**拖动时文字往上顶**
     （它取消进行中的拖动，只能"手势期间不补"）。
   - v115 改成 **`dispatchRawDelta(Δ)`**（不动动画器、不抢 mutex、不打断手势）→ 手势期间也照补。
     用户复验："都正常了"；日志 74 笔补偿全部 `+N == drift`、47 笔带 `拖动中`、0 笔 `补偿失败`。
   诊断走 `tag=scroll`（模式 A/B/C、每笔补偿、失败、每轮总量）。

日志定位的两条硬事实（以后还用得上）：`tag=nav` 的 **帧数只有 2–5 = 一次长帧**；
`tag=wi` / `tag=startup` 的数字能直接指认是解析、GC 还是首帧。读法见 [runbook](runbook.md) 6.5.1。

## 二、聊天跟随（已收，留两处小接缝）

**结论**：v115 起，流式文本在三种状态下都**持续实时更新、不冻结**；读气泡自己时滚动偏移会跟着长高补，
读到的那一段留在原处；拖动/惯性期间也照补（只抵掉文字顶上去那一笔，手指位移照常）。

- 三场景判据（`reverseLayout`）：贴底 `idx==0 && off==0`；读气泡 `idx==0 && off>0`；
  看更早的消息 `idx>0`（气泡根本没被布局，结构上就不动）。
- **留下的**：A→C 向上收 8–70px、C→A 向下沉 41–151px（贴底"追高欠账"是绘制量，跨边界丢/补），
  量级 ≤1 行、用户没感觉到。修法写在 [pitfalls](pitfalls.md) 最后一节，
  **不要单独为它占一轮**，下次顺手做。
- 别再往回的三个方向：冻结文本（用户明确否掉）、`requestScrollToItem`（闪 + 取消拖动）、
  "手势期间不补"（拖动不跟手）。


## 三、待真机验

聊天跟随已由用户在 v115 验过（见 [status.md](status.md)）。其余三组仍未逐项点：
启动与性能 / 世界书与设置 / 详情页与导航 —— 照 [status.md](status.md) 那张表从上到下点一遍即可。

## 四、下一步优先级

1. **聊天跟随已收**（第二节）；要打磨 A↔C 接缝就下次顺手做，别单独占一轮。
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
