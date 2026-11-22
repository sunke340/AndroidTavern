# AGENTS.md — `app/`

装配层，只做三件事：`MainActivity` + 主题、`NavHost`、依赖注入对象图。

**不写**：业务逻辑、数据访问、提示词拼装、界面细节（那在 `feature`）。

- 导航：在这里把各 feature 的 `NavGraphBuilder.xxxScreen(...)` 拼起来，路由常量集中在 `Routes.kt`。
- DI：**先手写**对象图（`AppGraph`），省编译时间——这台机器经不起注解处理。要上 Hilt 先写 ADR。
- `minSdk` 目标 26+；`targetSdk` 跟最新稳定版（前台服务类型、通知权限、SAF 行为都随它变）。
- 不在 `Application.onCreate` 里做重活：冷启动预算 1 秒内。

## 当前导航（2026-11-22 起）

**暂不引 `navigation-compose`**：层级只有 0–2 深，用「一个 `Destination` 状态 + `AnimatedContent`」表达，
返回键走 `BackHandler`。等出现深链或三级以上（角色→世界书→条目编辑）再引，那时写 ADR。

```kotlin
sealed interface Destination {
    data object Tabs                            // 三栏根页（tab 切换另有内层 AnimatedContent）
    data object Chat                            // 聊天（二级）
    data class Settings(page: SettingsPage)     // 设置二级
    data class Detail(cardId)                   // 角色详情（二级）
    data class CardConfig(cardId, page)         // 角色配置（三级）
    data object ProfileEdit                     // 模型档案编辑（三级，叠在设置→模型之上）
}
// depth: Tabs=0, Settings/Detail=1, Chat/CardConfig/ProfileEdit=2
// 转场风格可选（NavAnimation，默认「标准」）：
//   标准：进：新页整屏从右滑入；返回：上一页从左侧 1/3 处滑回（当前页原位被覆盖）
//   淡入：交叉淡化；关闭：完全不要动画（排查用）
// 注意：位移**只能做在"进来那页"上**，见下面"永远是 target 在上层"那条。
```

**别再回到 early return 的写法**：那样换页是整棵树直接替换，做不了进出动画（踩过）。

### 永远是"进来的那页"在上层：zIndex 救不了（踩了四轮，2026-11-22 结案）

现象演进：返回"像从右下角弹出" → 改方向没用 → 改 `targetContentZIndex`（0/1/2）没用 →
改 ±1 没用 → 录屏逐帧发现"返回时画面 246ms 里一个像素没动，一帧就切完了"。

真因（读 Compose `AnimatedContent.kt` 源码确认）：它给**每一个**可见内容套的都是同一个 spec 的
`ZIndexModifierElement(specOnEnter.targetContentZIndex, stateForContent)`。两层拿到同一个值，
zIndex 等于没设，只能退回"后放置的在上"，而 target 是最后放置的 —— 所以**永远是进来那页在上**，
退场那页的位移永远看不见。文档里"能设 zIndex 改变叠放"在这版实现上对**同一过渡内的两层**不成立。

结论与规矩：

1. **位移只做在"进来那页"（enter）上**；返回的观感靠"上一页从左侧滑回"，不要指望"当前页滑出去"。
2. **退场那页（exit）只负责"活满时长"**。`ExitTransition.None` 会让旧页在 12–14ms 内就被移除
   （实测日志），动画期间露出来的是窗口底色。所以给它一个满时长、位移为 0 的 slideOut 占位。
3. 判据：**「返回完全没动画」＝ 动的那层在下层**。先看 `nav ... 页面移除，距上次换页 Nms` 这行日志：
   ~240ms 说明转场跑了（那就是叠放问题），几十毫秒说明转场没跑（问题在状态层）。

**退场别配比位移更短的淡出**：240ms 位移 + 180ms 淡出的组合会让退场页提前变透明，
后面露出的就是窗口底色，观感是"闪一下"。页面切换只留纯位移，不叠 fade。

### `AnimatedContent` 必须 `fillMaxSize()` + `using(null)`（真正的病根，2026-11-22）

上一轮改 zIndex / 方向 / 淡入全都没用，因为病根是**尺寸动画**：`AnimatedContent` 默认带一个
`SizeTransform`，容器尺寸会在"旧页尺寸→新页尺寸"之间弹簧动画，且 `clip = true`。
两个页面在重建瞬间量出来的尺寸不一样时，容器会从 ~0 弹到满屏，里面的 `fillMaxSize` 内容被压在
窄框里重排、超出部分直接裁掉。逐帧看是：**空白几帧 → 屏幕中央冒出一个几像素的小点 → 长成满屏**。
它跟 enter/exit 无关，所以把动画全关了（`NavAnimation.None`）照样发生（用户就是这么发现的）。

```kotlin
AnimatedContent(
    modifier = Modifier.fillMaxSize(),   // ① 容器钉死，不许自己量尺寸
    transitionSpec = {
        (enter togetherWith exit).using(null)   // ② 关掉尺寸弹簧。setter 是 internal，只能 using(null)
    },
)
```

判据：**页面换页时如果内容是"长出来"的，不是"滑进来"的，就是它。**
官方依据：[AnimatedContent 文档](https://developer.android.com/reference/kotlin/androidx/compose/animation/AnimatedContent)
（"the container will animate its size … unless `SizeTransform` is set to `null`"）、
[issue 233644826](https://issuetracker.google.com/issues/233644826)。

**列表滚动位置要提到 `MainScreen`**：换二级页时 Tabs 整棵子树被销毁重建，`remember` 在
`TabsRoot`/页面里的 `LazyListState`/`LazyGridState` 会丢（真机表现：从二级页返回后列表回到顶部）。
`sessionsList` / `charactersGrid` 现在在 `MainScreen` 里 `remember`，再传下去。

```
MainScreen（Destination + AnimatedContent）
├── TabsRoot（Scaffold + 自绘底栏）
│   ├── SessionsScreen    会话列表 → 选卡弹层新建 → 点一行进 Chat
│   ├── CharactersScreen  角色卡网格（两列 3:4）
│   └── SettingsScreen    一级列表：模型 / 采样 / 外观 / 世界书 / 关于
├── 二级页（全屏）：ChatScreen / CharacterDetailScreen / SettingsSubPage / CardConfigPageScreen
└── 三级页：ProfileEditScreen（设置 → 模型 → 某条档案；返回仍落在"模型"列表，所以 settingsPage 不清）
```

- **聊天不占底部 tab**：它是二级页，带返回栈才符合安卓返回键。
- **世界书不占底部 tab**：它是跟着卡/会话走的编辑面，入口挂在角色详情、聊天 ⋮、设置三处（P1 再做）。
- 消息渲染收敛在 `ChatScreen.kt` 的 `MessageRow(message, style)`；`Bubble`（默认）与 `Card` 都已实现，
  卡级覆盖在角色详情 → 配置 → 对话样式（`null` = 跟随全局）。

## 聊天列表：必须 reverseLayout（踩过一次）

`LazyColumn(reverseLayout = true)` + `items(transcript.asReversed())`，串流气泡固定在最前（视觉最底部）。

**不要改成"正常排序 + scrollToItem 跟底"**：串流时每个 token 都会触发一次滚动/重排，
表现是"进聊天时消息从顶滑到底""输出时整屏抽动"。底部锚点天然没有这个问题。

## 日志：用 AppLog，不要 logcat（真机没有）

- 打点走 `app.tavern.data.log.AppLog`（`i/w/e`），**只记事件与状态码，不记 Key 与消息正文**。
- 崩溃：`TavernApp` 已装 `UncaughtExceptionHandler`，会先落盘再交还系统。
- 导出：设置 → 关于 →「导出日志」（SAF 写 txt）。
- 关键路径（发送/完成/失败/异常/导入/删除/主题切换）都要留一行；否则出了问题只能猜。
- **导航也打点**：`MainScreen` 里 `LaunchedEffect(destination)` 打 `tag=nav`，
  记 `目标 depth=N push/pop/switch`。2026-11-22 那次用户的日志只有一行启动记录，
  返回动画的问题完全没法从日志定位 —— 宁可多一行，不要再"只能靠描述猜"。

## 页头的规矩（踩过一次）

页头是 **`PageHeader` 浮层**（在 `MainScreen` 的 TabsRoot 里渲染，跟底栏同级），页面自己不画页头：

- 结构：左边大标题（`headlineMedium` 粗体），右边**玻璃胶囊**装图标动作（`GlassSurface` + `CapsuleIcon`）；
  页头底下压一层"底色 → 透明"的渐变，内容从下面滚过去时淡出，标题不会跟列表文字叠在一起；
- **页头必须画在"内容录图层"之外**：画在里面的话，玻璃胶囊采样的图层会带上上一帧的胶囊自己，
  每帧叠加 = 无限套娃（胶囊里能看到自己的鬼影）。同理底栏也是浮层。
- 页面自己不再有页头，内容用 `contentPadding(top = PageHeaderHeight + …)` 让位（空态也一样）：
  这样静止时内容在页头下面，滚动时会从玻璃胶囊底下穿过去 —— 胶囊才有东西可糊。
- **页头不放实心按钮**（`PrimaryAction`）：截图那种胶囊里是平铺图标，用 `CapsuleIcon`。
- **胶囊里的内容只 `fillMaxHeight`，不要 `fillMaxSize`**：`fillMaxSize` 会把胶囊撑满整行，
  标题的 `weight(1f)` 被挤成 0 宽 —— 真机上表现就是"标题消失、只剩一个图标贴在最左"（踩过）。
- **没有副标题**：数量、空态这类信息交给内容区；
- 一次性结果（导入成功/失败、删会话、删卡）走 `UiState.notice` → Snackbar，**不进页头**。
- 底栏是自绘的 `TavernBottomBar`（56dp，图标 20dp + 11sp 标签，选中只靠主色）：
  Material3 的 `NavigationBar` 固定 80dp、带胶囊指示器，压不矮。底栏与页头胶囊共用 `GlassSurface`。
- 颜色/间距/圆角一律取自 `Theme.kt`（`ThemePreset`）与 `Style.kt`（`Space`/`Radius`），不要在页面里写死。
- 多选项控件用 `SegmentedRow`（每项 `weight(1f)`）：**宽度均分铺满**，不要再用 FilterChip 拼一排。
- **界面上不写解释性文案**（2026-11-22 用户要求）：不要放"这个功能是怎么实现的""下一批会做什么""两种样式有什么区别"
  这类说明；页面里只留 ①字段标签 ②状态值 ③空状态 ④删除/不可逆的确认措辞 ⑤隐私声明（日志不含 Key/正文）。
  说明写进 `docs/`，不要写进安装包。
- `PrimaryAction` / `SecondaryAction` 的最后一个参数是 `enabled`，**调用时必须具名**
  （`SecondaryAction(text = "…", onClick = { … })`）；写成尾随 lambda 会绑到 `enabled` 上，编译期才报（踩过）。
- 设置要分层：一级只放"去哪儿"（`SettingLinkRow` + 当前值），控件放二级页 `SettingsSubPage`。
- 聊天页的排查工具（本次请求）走右上 `⋮` → 全屏弹层，不占消息区版面。
- 图标只能从 `material-icons-core` 里取（ArrowBack / MoreVert / Send / Person / Settings /
  List / Close / Delete / Add 等）；**不确定有没有的就用文字或 Canvas 自绘**——
  引 `material-icons-extended` 会多 10MB 和一轮构建，本机又编不了，不值得。
- `Icons.Filled.X` 是**扩展属性**：必须 `import androidx.compose.material.icons.filled.X` 再写
  `Icons.Filled.X`，**不能全限定**（`androidx.compose.material.icons.Icons.Filled.X` 会 Unresolved）。
- 主题顺序/默认在 `Theme.kt` 的 `ThemePreset` 枚举里：Violet（墨紫，默认）在最前，
  持久化存的是**名字**，重排枚举不影响已保存的选择。

当初三页各写各的（留白 12/16、有的有副标题有的没有、按钮位置各异），切 tab 时标题会跳几像素。

## 二级/三级页：`GlassDetailScaffold`

二级页（聊天 / 设置二级 / 角色详情 / 角色配置 / 档案编辑）统一用 `GlassDetailScaffold`，
不要再用 `Scaffold` + `TopAppBar`：

- 页头形状：左边**圆形玻璃返回钮**（`GlassSurface` + `CircleShape`）+ 大标题（可带一行副标题），
  右边**玻璃胶囊**装图标动作（`CapsuleIcon`），底下压一层底色→透明渐变；
- 每个二级页**自己录一份内容图层**（它在一级页的录制范围之外）。好处是切页时两页各采各的，
  不会抢同一个图层；代价是同一时刻最多两份全屏图层（过渡 240ms）。
- 内容顶部让位：默认 `contentTopInset = DetailHeaderHeight`；需要内容**滚到玻璃页头下面**的页面
  （聊天）传 `0.dp`，改用列表的 `contentPadding` 让位 —— `contentPadding` 不会裁内容，滚动时会从玻璃底下过。

## 玻璃模糊：效果挂在节点上（踩过一次，两台真机都中）

- 底栏玻璃是自绘的（[ADR 0005](../docs/adr/0005-glass-without-haze.md) /
  [ADR 0006](../docs/adr/0006-glass-blur-on-node.md)）：内容层 `glassLayer.record{}` 录一份自己，
  底栏把图层平移过去、塞进挂 `Modifier.blur` 的节点里画，最后叠半透明底 + 描边 + 图标。
- **把内容录进图层必须用 DrawScope 的 `record` 扩展**（`layer.record(size) { this@drawWithContent.drawContent() }`），
  不能用四参数的成员版 `record(density, layoutDirection, size) { }`。前者会把**本 DrawScope 的画布**临时改指到
  图层（官方 KDoc：retarget the underlying canvas），后者只改图层自己的 `DrawContext` —— 结果 `drawContent()`
  照旧画到主画布：屏幕上内容正常、图层是空的，底栏画出来什么都没有。**这个坑很隐蔽：不崩、不报错、看不出异常。**
- **录图层前先铺页面底色**（`drawRect(pageBackground)`）：背景画在 `Scaffold`/祖先上，不在内容节点的
  `drawContent()` 里。不铺的话图层只有文字和头像（实测 544 个采样点里 9 个不透明），底栏画上去仍是
  「透明磨砂」，背后真实内容照样透出来 —— 看着就是"没糊"。判据：`AppLog` 里 `不透明=` 应该接近采样总数。
- **`renderEffect` / `alpha` / `translation` 是渲染时读的属性**：在 draw 块里
  `renderEffect = …; drawLayer(); renderEffect = null` 会**等于没设**（真机只剩半透明，不报错）。
  要模糊就挂 `Modifier.blur` / `Modifier.graphicsLayer { renderEffect = … }`，让效果跟着节点活到渲染完。
- **糊谁就把那个节点放大一圈**：离屏缓冲边缘按 clamp 收敛，节点和被糊区域一样大时会出直纹。
  `expandForBlur(pad)` 负责这个，多出来的由外层胶囊 `clip` 裁掉。
- **`positionInWindow` 量在没有 padding 的那层上**：同一个 LayoutNode 上带 padding 时报的是外框，
  坐标会偏。外壳管留白/阴影，胶囊本体单独一层。
- **边缘高光是一圈"宽带"，不是一条线**：`RimLayers` 由外向内叠 4 层白（1dp@0.95 / 2.5dp@0.45 /
  5dp@0.22 / 8dp@0.10），叠出来是"边缘最亮、往内渐淡"的 3~5dp 亮带；外半圈压在底色上当光溢出。
  只画 1dp 会被读成描边，不是玻璃（2026-11-22 对比参考图后改的）。两侧再叠一层低透明度主题色。
- **投影自己画**：模糊的黑胶囊往下压几像素（`Modifier.blur(…, BlurredEdgeTreatment.Unbounded)`）；
  不能用 `Modifier.shadow` —— elevation 阴影在内容后面，配半透明底会透出亮度台阶。`Unbounded` 不能省，
  默认的 Rectangle 会把模糊结果裁在节点边界内，光晕直接没了。
- 判据：切到玻璃看胶囊里的字 —— **还认得出字 = 没糊；一片糊只剩色块 = 糊上了**。
  真机没 logcat，先看 `AppLog` tag `glass` 那两行（sdk / 硬件加速 / 半径，底栏 / 内容 / 尺寸）。

## 窗口 insets 的规矩（踩过两次）

- `MainActivity` 必须调 `enableEdgeToEdge()`，Manifest 里钉死 `windowSoftInputMode="adjustResize"`。
  **两者缺一就会出现"点输入框时画面先抽一下再落回"**——系统缩放窗口和 Compose 的 `imePadding()`
  动画各走一套，位移叠了两次。
- 页面用 `Scaffold`（含 `TopAppBar`）拿状态栏/导航栏留白；聊天页不许再用裸 `Column` 当根。
- 键盘遮挡用 `Modifier.imePadding()`，别用 `adjustPan`；`values-night/themes.xml` 要跟着维护窗口底色。
