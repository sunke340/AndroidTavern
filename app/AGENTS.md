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
}
// depth: Tabs=0, Settings/Detail=1, Chat/CardConfig=2
// 转场风格可选（NavAnimation，默认「标准」）：
//   标准：同一时刻只有一层在动 —— 进：新页整屏从右滑入；返回：当前页整屏向右滑出
//   层叠：两层一起动 —— 进：新页推入 + 旧页左退 1/4；返回：当前页右退 + 上一页回 1/4
//   淡入 / 关闭：不位移 / 完全不要动画（排查用）
```

**别再回到 early return 的写法**：那样换页是整棵树直接替换，做不了进出动画（踩过）。

### 返回时"谁压着谁"由 zIndex 决定（踩过一次，用户报了两轮）

`AnimatedContent` **默认把新进来的内容画在最上层**（官方文档：同 zIndex 时 incoming target 放在最后、
盖住 outgoing）。所以只改位移方向、不给 zIndex，返回就是"上一级从上层滑进来盖住正在退场的当前页"，
两层朝同一方向错位滑动 —— 用户看到的就是"从右下角弹出来"。

修法：`targetContentZIndex = if (forward) 1f else -1f`——用**二值**而不是"深度值"：
深度值依赖"旧页保留它进来时的 z"这一前提，前提不成立时两层落到同一个 z，又变成新页在顶。
+1/-1 与其它层的 z 无关，谁在上是确定的（进：新页在上；返回：退场页在上）。

反面判据：**「返回时完全没动画」＝退场那层被盖住了**。因为 pop 的上一级是
`EnterTransition.None`（瞬间就位、不透明），它一旦在上层，动的那层就整个被遮掉，
看起来就是"秒切"。这条比"看着别扭"更能直接定位到 z 序。
出处：[ContentTransform 文档](https://developer.android.com/reference/kotlin/androidx/compose/animation/ContentTransform)
（官方的嵌套菜单示例用的就是这招，只是它按层级值给）。

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
        (enter togetherWith exit)
            .apply { targetContentZIndex = if (forward) 1f else -1f }
            .using(null)                 // ② 关掉尺寸弹簧。注意 setter 是 internal，只能 using(null)
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
└── 二级页（全屏）：ChatScreen / CharacterDetailScreen / SettingsSubPage / CardConfigPageScreen
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

三个底部页面（会话 / 角色卡 / 设置）**必须用 `PageScaffold`**，不要各自手写页头：

- 页头固定 56dp（左右 16 / 上下 8），标题垂直居中，右侧动作按钮槽 40dp；
- **没有副标题**：数量、空态这类信息交给内容区；
- 页头下面一条分隔线，三页内容从同一条线开始；
- 一次性结果（导入成功/失败、删会话、删卡）走 `UiState.notice` → Snackbar，**不进页头**。
- 底栏是自绘的 `TavernBottomBar`（56dp，图标 20dp + 11sp 标签，选中只靠主色）：
  Material3 的 `NavigationBar` 固定 80dp、带胶囊指示器，压不矮。
- 颜色/间距/圆角一律取自 `Theme.kt`（`ThemePreset`）与 `Style.kt`（`Space`/`Radius`），不要在页面里写死。
- 多选项控件用 `SegmentedRow`（每项 `weight(1f)`）：**宽度均分铺满**，不要再用 FilterChip 拼一排。
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

## 窗口 insets 的规矩（踩过两次）

- `MainActivity` 必须调 `enableEdgeToEdge()`，Manifest 里钉死 `windowSoftInputMode="adjustResize"`。
  **两者缺一就会出现"点输入框时画面先抽一下再落回"**——系统缩放窗口和 Compose 的 `imePadding()`
  动画各走一套，位移叠了两次。
- 页面用 `Scaffold`（含 `TopAppBar`）拿状态栏/导航栏留白；聊天页不许再用裸 `Column` 当根。
- 键盘遮挡用 `Modifier.imePadding()`，别用 `adjustPan`；`values-night/themes.xml` 要跟着维护窗口底色。
