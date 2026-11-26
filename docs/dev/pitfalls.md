# 踩坑档案（历史与细节）

**这份文件是 `app/AGENTS.md` 在 2026-11-24 瘦身前的原文归档**，一条没删。
规则本身在 [app/AGENTS.md](../../app/AGENTS.md)（每次会话自动加载，只有一两条规则 + 链接）；
这里放的是"当年为什么这么改、试过哪几条路、数字是多少"。

> **怎么用**：在 `app/` 下动手前先读 `app/AGENTS.md`；需要知道某个数字/形状/坑的来历时，
> 按下面索引跳进来。**别只读这里** —— 这里没有"必须遵守"的清单，只有理由。

## 索引

| 主题 | 看哪 |
|---|---|
| 导航与页面转场（`PageStack`、zIndex、离场页快照、第一帧） | 当前导航 / 页面转场 |
| `AnimatedContent` 的尺寸弹簧病根 | `AnimatedContent` 必须 fillMaxSize |
| 列表行系统（行高、分隔线、按压即亮） | 列表：一套行系统 |
| 角色卡（满幅图 + 磨砂条 + `CardFrost`） | 角色卡 |
| 动作单（自绘玻璃弹层、`SheetController`） | 动作单 |
| 玻璃模糊（录图层、`record` 扩展、`renderEffect` 挂节点、高光内缩） | 玻璃模糊 / 玻璃与页头 |
| 液态玻璃参数（AGSL、折射、色散、面板白度、投影） | 液态玻璃 / 主题色按钮 |
| 页头（色罩 vs 磨砂、渐变不许加拐点） | 页头的规矩 / 页头铺到状态栏 |
| 橡皮筋滚动（raw 上限、`flingEaten`、代数守卫） | 键盘与橡皮筋 |
| 动效曲线与时长、减动效 | 动效：先查表 |
| insets 与键盘 | 窗口 insets 的规矩 |

---

## 当前导航（2026-11-22 起）

**暂不引 `navigation-compose` / Navigation 3**：层级只有 0–2 深，用「一个 `Destination` 状态 +
自写的 `PageStack`」表达（转场原因见下面"页面转场"一节），返回键走 `BackHandler`。
等出现深链或三级以上再引库，那时写 ADR。

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
//   标准：进：新页整屏从右滑入（当前页左移 1/4 做视差）
//        返回：当前页（深）整屏向右滑走；上一页从 -1/4 滑回原位
//   淡入：交叉淡化；关闭：完全不要动画（排查用）
// 叠放次序由 PageStack 按方向决定（进=新页在上，返回=旧页在上），不再依赖 AnimatedContent。
```

**别再回到 early return 的写法**：那样换页是整棵树直接替换，做不了进出动画（踩过）。

### 页面转场：自写 `PageStack`，不用 `AnimatedContent`（2026-11-24 用户验收通过）

**结论**：`AnimatedContent` **做不到**"退场那页（深）盖在上面滑走"。它给两层套的 zIndex 取自
**同一段转场的那一个** `ContentTransform.targetContentZIndex`（源码：`ZIndexModifierElement(
specOnEnter.targetContentZIndex, stateForContent)` → `place(0, 0, zIndex = …)`），
单一值只能两层一起抬或一起压；打平后由放置顺序决定，而 target 永远放在最后
→ **进来那页永远在最上面**。改 `targetContentZIndex` 的取值、±1、按深度给，全都无效。
（Navigation 3 的 `NavDisplay` 是在 `AnimatedContent` 之上自己维护一张 zIndex 表才做到的，
而它要 Compose 1.10，我们锁 1.7.6。）

所以转场在 [PageStack.kt](../../app/src/main/kotlin/app/tavern/PageStack.kt)（约 90 行）：

- **离场页留在组合树里**（`key(dest)` 分组）——它的滚动位置与内部状态不丢；
- **叠放由方向决定**：进 → 新页在上；返回 → 旧页（深）在上，整屏向右滑走；
- **位移成对**：进 = 新页从右滑入 + 旧页左移 `1/4`；返回 = 旧页整屏右滑走 + 新页从 `-1/4` 归位。
  两个 `1/4` 是同一个量（这版是用户验收过的动势，**别再改 None**）；
- **只动 `graphicsLayer.translationX`**：整页不重新量尺寸，老 `SizeTransform` 那个坑自然消失；
- 减动效（`rememberReduceMotion`）→ 只交叉淡化；`NavAnimation.None` → 瞬间切。
- **tab 之间**仍是内层 `AnimatedContent`（`w/8` 横移 + 180ms 淡入）；那是有意保留的，不是漏改。
- **离场页必须拿"它还是当前页时"的状态快照**（`snapshots[dest]`，每帧只给当前页写）。
  否则页面读实时 `UiState`，而返回是把状态一起改掉的（`closeDetail()` 同时清 `detailCardId`），
  离场页会走到自己的 `?: return` 渲染空 —— 层在、内容没了，看着就是"退场页凭空消失"。
- **第一帧不能拿上一轮的终态**（2026-11-24 用户："页面先跳到 -1/4 再复位"、"整屏闪一下"）：
  目标一变的那一帧，`pages` 还是旧的、而 `progress` 还是上一轮的 1，离场页会按 **p=1**
  渲染一帧（进 = `-w/4`，返回 = `+w`）→ 一帧闪现。
  现在用 `running` 显式区分：`running=false` 时**只画原来那页、不做任何变换**；
  效应里的顺序固定为「进度归零 → running=true → 加入新页」（同帧提交），
  下一帧才是 `running + 两页 + p=0` 的正确起点。`graphicsLayer` 里没转场时强制
  `alpha=1 / translationX=0`（减动效模式的 alpha 也曾被残留的 p=1 算成 0 → 整屏空白一帧）。

**判据（怎么确认层次对了）**：返回时应该看到**退场页**在动（整屏右移）、它下面的页在归位；
如果看到的是"上一页从上面滑进来盖住当前页"，就是叠放反了。逐帧证据的做法：录屏抽帧，
拿退场页做参考算每一列差异——退场页应该还能在屏幕上找到（滑走过程中），
而不是一帧就不见了（那就是被盖住了）。

**踩过的坑**：`Destination` 原本是 `private`，`PageStack` 在另一个文件里引用它会报
`'public' function exposes its 'private-in-file' parameter type`；现在 `Destination` 与
`PageStack` 都是 `internal`。

### 列表：一套行系统，别手写行（2026-11-24 改版）

**所有列表行都走 `ListRow`，成组的列表用 `ListGroup` / `GroupPiece`**（都在 `Style.kt`），
不要再手写 `Row` 拼一个"看起来差不多"的行 —— 之前世界书/正则是手写 Row，
于是同一个设置里出现三套边距、三种行高。

- 尺寸与字号都在 `Style.kt`：`RowToken`（行高 68dp、头像 40dp 方形圆角 10dp、
  行内边距 12/11、时间列 44dp）、`RowTitleStyle` 15sp/600、`RowSubtitleStyle` 12sp、
  `RowMetaStyle` 11sp + `fontFeatureSettings = "tnum"`（等宽数字，时间刷新时列不抖）。
- **行本身不画背景与分隔线**：底色、圆角、边线、分隔线都归 `GroupPiece`。
  这样 `LazyColumn` 里的会话列表与普通 `Column` 里的设置列表才能长得一模一样。
- **边框只画在组的外轮廓上**（Top 画上/左/右，Middle 只画左/右，Bottom 画下/左/右）。
  每片都画一圈 = 相邻两行之间出现 2px 双线。
- **分隔线从文字左缘起**（`dividerInsetWithAvatar` 64dp / 无头像 12dp），**最后一行不画**：
  老写法是每行一条通栏线，列表末尾还会多出一条悬空的直线。
- **按压反馈"按下即亮"**：`7%` 主色底、`Motion.Fast`、`indication = null` 关涟漪
  （涟漪在圆角面板里会画成一块方角深色）。这是 apple-design 的"反馈必须发生在手指落下时"，
  属于反馈而不是装饰性动效，所以高频列表也可以有。
- **行的出现/消失仍然不加动画**（见下一节），只保留 `placementSpec`。
- 会话时间用 `SessionTime.kt`：今天给时刻、昨天、一周内周几、同年「月-日」、跨年给年份；
  分组只有「今天 / 更早」—— 再细分（昨天/本周/本月）会让标题比内容还多。

### 角色卡：满幅图 + 底部磨砂条（2026-11-24 改版）

- 卡片＝**满幅 3:4 图 + 圆角 18 + 无描边**，名字与元信息压在底部 56dp 的磨砂条上
  （`CharactersScreen.CardBand`）：糊图 → 主题半透明面（`surface` × 0.72）→ 上沿 1px 亮线 → 文字。
- **副行不再写角色描述**：多数卡这里写的是正文，截两行读不懂，还把卡片撑高（有描述的卡比
  没描述的高一截）。元信息＝`N 个会话 · 内嵌书 M 条`，没聊过写「未开始」。
  会话数直接数 `state.sessions`、内嵌书用 `CardRef.bookEntryCount`，**不需要查库**。
- **磨砂不能用 `GlassSurface`**：角色卡在内容录图层里，玻璃采样那一层会采到自己这一帧
  （页头胶囊"无限套娃"就是这个坑）。用 `CardFrost`：预先解一张 ~32px 的小图，放大画回去
  就是糊的（5KB/张、不碰图层时序、不依赖 RenderEffect）。
- 长按 → 抽屉（开始新会话 / 查看详情 / 删除角色卡）；删除必须二次确认，提示里写会话数。
- 按压：卡片上压 **8% 黑**（行是白面板才用 7% 主色，图上盖紫会染色），手感走
  `rememberPressTint()` —— 按下当帧满色、松开 160ms 淡回。

### 动作单（长按抽屉）：自绘玻璃弹层，别用 `ModalBottomSheet`（2026-11-24）

- **`ModalBottomSheet` 做不到真磨砂**：它渲染在独立的 Dialog 窗口里，而磨砂/折射是
  "把主窗口内容录进 `GraphicsLayer` 再按窗口坐标采样"——跨窗口拿不到那张图层，
  弹层只能画成半透明白板。
- 现在的做法：`ActionSheet.kt` 里的 `SheetController` + `LocalSheetController`。
  **页面只"请求"**（`sheet.show(title, actions)`），由最近的页面骨架渲染：
  - 一级三页：`TabsRoot` 里的 `GlassActionSheetHost`（用底栏那张 `frostLayer`）；
  - 二级/三级页：`GlassDetailScaffold` 里的同一个 host（用该页自己的 `frost`）。
  两处都在**录图层之外**，所以弹层能真的糊住背后内容。
- **不要在页面里直接 composed 弹层**：它会画在录图层里面，玻璃采样会采到自己这一帧。
- 弹层参数：上两角 24dp 圆角、`shadow = false`、**`liquid = LiquidGlassSpec.Sheet`**
  （faceWhite 0.50，比胶囊的 0.22 **更实**一档 —— 弹层面积大，太透会被背后的内容顶掉文字；
  **单独一份实例，调弹层不连带改胶囊**）；
  ⚠️ **要判断这档效果，必须在彩色内容上开弹层（角色卡网格）**：白底列表上"白面混白底"，
  0.22 与 0.50 几乎同色，肉眼分不出来 —— 这不是没生效。链路是
  `spec.faceWhite → uFaceWhite → rgb = mix(rgb, uFaceColor, uFaceWhite)`（`LiquidGlass.kt`）；
  非默认档每次会打一行 `tag=glass 液态档 face=… 着色器=true/false`，导出日志即可确认。
  遮罩黑 32%、180ms 淡入、点遮罩关闭、`BackHandler` 关闭；滑入 `Motion.Slow/Standard`，
  减动效时改淡入。
- **一级页弹层起来之前，底栏先向下滑走**（`TavernBottomBar(hidden=…)`，180ms；
  弹层用 `enterDelayMillis = SheetEnterDelayMillis`（180）延后入场；收起时弹层先走、
  底栏 200ms 后才回来）。两块浮层不叠在一起。
- 选卡弹层（`CardPickerBody`）也走同一个 `GlassSheetScaffold`，宿主同样在 `TabsRoot`；
  页面里不要再写 `ModalBottomSheet`。
- 行内的「删除」只关弹层 + 交给调用方弹确认框，**不直接删**。
- **确认框统一用 `ConfirmDeleteDialog`**（`ConfirmDeleteDialog.kt`）：标题 + 一句后果 + **红色确认钮**
  （`colorScheme.error`）。别再手写 `AlertDialog` —— 原来 7 处各写一遍，于是同一个动作出现两种措辞，
  而且只有档案编辑那处的确认钮是红的。参数全部写名字：两个回调都是 lambda，写反了编译器不报。
- 目前**没有拖拽关闭**（只能点遮罩或返回键）；要做就照动效文档的两段式（跟手段 1:1 + 松手弹簧）。

### 列表不要做"出现/消失"的淡入淡出（2026-11-23）

`Modifier.animateItem(fadeInSpec = …)` 会在换页时把**整格卡片从 alpha 0 淡上来**
（用户的原话是"卡片颜色由浅到深"）：换页时这棵子树被重建，新网格会被测两遍，
第二遍里所有 item 对动画器都是"第一次见" → 触发 appearance 动画。
**两张列表都只留 `placementSpec`，`fadeInSpec`/`fadeOutSpec` 传 `null`。**

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
MainScreen（Destination + PageStack）
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
  页头底下是**半透明色罩**（不是磨砂，磨砂只有聊天页有），从状态栏后面铺下来；
- **页头必须画在"内容录图层"之外**：画在里面的话，玻璃胶囊采样的图层会带上上一帧的胶囊自己，
  每帧叠加 = 无限套娃（胶囊里能看到自己的鬼影）。同理底栏也是浮层。
- 页面自己不再有页头，内容用 `contentPadding(top = PageHeaderHeight + …)` 让位（空态也一样）：
  这样静止时内容在页头下面，滚动时会从玻璃胶囊底下穿过去 —— 胶囊才有东西可糊。
- **页头不放实心按钮**（`PrimaryAction`）：截图那种胶囊里是平铺图标，用 `CapsuleIcon`。
- **胶囊里的内容只 `fillMaxHeight`，不要 `fillMaxSize`**：`fillMaxSize` 会把胶囊撑满整行，
  标题的 `weight(1f)` 被挤成 0 宽 —— 真机上表现就是"标题消失、只剩一个图标贴在最左"（踩过）。
- **没有副标题**：数量、空态这类信息交给内容区；
- 一次性结果（导入成功/失败、删会话、删卡）走 `UiState.notice` → Snackbar，**不进页头**。
- 底栏是自绘的 `TavernBottomBar`（56dp，图标 20dp + 11sp 标签，选中只靠主色 + 一块滑动的
  14% 主色底块 + 点按时 6% 主色的轻 tint）；**每个 tab 不要自己画底块**（那会变成"消失 + 出现"），
  也**不要开涟漪**（方形深色块会压在滑动中的紫块上；要按压反馈就用 `rememberPressTint`）：
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
  右边**圆形玻璃按钮**装图标动作（单动作时是正圆，2026-11-23 从胶囊改过来）；
  页头底下默认是**半透明色罩**（`frostedHeader = false`），只有聊天页传 `true` 变成磨砂条；
- 每个二级页**自己录一份内容图层**（它在一级页的录制范围之外）。好处是切页时两页各采各的，
  不会抢同一个图层；代价是同一时刻最多两份全屏图层（过渡 280ms）。
- 内容顶部让位：默认 `contentTopInset = DetailHeaderHeight`；需要内容**滚到玻璃页头下面**的页面
  （聊天）传 `0.dp`，改用列表的 `contentPadding` 让位 —— `contentPadding` 不会裁内容，滚动时会从玻璃底下过。

## 玻璃模糊：效果挂在节点上（踩过一次，两台真机都中）

- 底栏玻璃是自绘的（[ADR 0005](../adr/0005-glass-without-haze.md) /
  [ADR 0006](../adr/0006-glass-blur-on-node.md)）：内容层 `glassLayer.record{}` 录一份自己，
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
- **边缘高光是一圈"宽带"，不是一条线**：`RimLayers` 由外向内叠 4 层
  （1dp@0.55 / 2.5dp@0.32 / 5dp@0.18 / 8dp@0.09），叠出来是"边缘最亮、往内渐淡"的亮带。
  透明度别调高（早期是 0.95/0.45/…）：太白会把下面的磨砂盖死，看着像实心白圈。
- **多层叠加只在"白玻璃面"上用**：有色面（`fillColor != null`）改单圈细线，否则会数得出同心圈。
- **高光要"内缩半个线宽"，不能只裁**（2026-11-23 两轮教训）：
  ① 不裁不缩 → 外半个环落在**页面底色**上，浅色按钮变成一圈白描边/贴纸（"边缘的光是纯白的"）；
  ② 只加 `clip(shape)` → 外半圈被裁掉，1dp 的层只剩 0.5dp，肉眼变成"没有高光"（"看不出来高光"）。
  现在每层 `topLeft/size/cornerRadius` 都往内缩 `w/2`，整条带子完整落在面内、宽度不变。
  轻量档那圈 1dp 细描边不受影响（`clip` 只加在玻璃档）。
- **深色暗带已删除**，见下面 2026-11-23 那一节，不要再加回来。
- **不要给浮层垫矩形底色**：胶囊周边必须能看见背景和消息。曾经在底栏/输入区脚下压过
  "透明 → 底色"的渐变，结果被读成"胶囊被装进一个方形容器里"（2026-11-23 用户指出，已删）。
- **聊天页磨砂页头下沿的影子减半**（`shadowScale = 0.5f`，0.16 → 0.08）：它正对着消息正文，
  整强度会一直抢注意力（用户 2026-11-24）。注意"页头下方"与"输入区"是两条不同的投影，
  改错地方看起来会"没变化"。
- **页头默认是半透明色罩**（2026-11-23 定调，同一天先改成磨砂、当天又收回来）：只有聊天页是
  磨砂条（`GlassSurface` + 下圆角、`shadow = false`，`GlassDetailScaffold(frostedHeader = true)`）；
  其余页头一律"色罩 + `HeaderFadeTail` 28dp 渐变尾巴"。`GlassBand` 这个手写磨砂带已经删了，别再写。
- **二级页的输入区用 `bottomOverlay` 槽**（`GlassBackdrop`）：画在录制之外，能采磨砂，
  消息滚动时会从胶囊后面经过；列表用 `contentPadding(top/bottom)` 让位，不要裁内容。
- **投影自己画**：模糊的黑胶囊往下压几像素（`Modifier.blur(…, BlurredEdgeTreatment.Unbounded)`）；
  不能用 `Modifier.shadow` —— elevation 阴影在内容后面，配半透明底会透出亮度台阶。`Unbounded` 不能省，
  默认的 Rectangle 会把模糊结果裁在节点边界内，光晕直接没了。
- 判据：切到玻璃看胶囊里的字 —— **还认得出字 = 没糊；一片糊只剩色块 = 糊上了**。
  真机没 logcat，先看 `AppLog` tag `glass` 那两行（sdk / 硬件加速 / 半径，底栏 / 内容 / 尺寸）。

## 玻璃与页头（2026-11-23 第二轮，血泪版）

### 玻璃表面只用 `GlassSurface`，别手写模糊

页头磨砂试过两条手写路线，真机上**都只剩底色、没有糊**：

1. `graphicsLayer { compositingStrategy = Offscreen }` + `drawRect(DstIn 渐变)` 遮罩 —— 整层被擦掉，
   文字全透出来；
2. 分条透明度（8 条，每条的 `graphicsLayer{ alpha }`）+ 父节点 `blur` —— 仍然只有底色。

结论：**要磨砂就用 `GlassSurface`**（和底栏、胶囊、输入框同一条路径）。

**只有聊天页的页头是磨砂条**（2026-11-23 用户第二次定调）：它是唯一一个"内容一直在标题正下方滚"
的页头，必须糊掉才不跟标题打架 —— `GlassSurface`、形状「上两角直角 + 下两角 16dp 圆角」、
`shadow = false`，标题与动作压在上面。

其余所有页头（一级三页 + 设置/角色详情/档案编辑）是**半透明色罩**：`headerScrim(background)`
（`Style.kt`）= 从 `y = 0` 到页头盒子底部**一条纯线性**渐变，`HeaderScrimAlpha`(0.94) → 透明。
盒子高度 = 状态栏 + 页头 + `HeaderFadeTail`(28dp)，那 28dp 就是"收尾段"，内容才不会一刀切地出现。
入口是 `GlassDetailScaffold(frostedHeader = …)`，**默认 false**；只有 `ChatScreen` 传 true。

**渐变里不许加拐点**：试过"前 78% 恒定 + 最后 28dp 化开"来保住标题区的浓度，
用户一眼看出是**阶梯**（两块拼的）。现在是线性；代价是标题下沿只剩 20%~45% 浓度 ——
嫌透就整体抬 `HeaderScrimAlpha` 或加长 `HeaderFadeTail`（让坡更缓），不要加拐点。

### GlassSurface 的圆角必须从 Shape 读

投影和描边原来写死 `CornerRadius(size.height / 2f)`，只对胶囊成立；套到页头那种全宽横条会变成
大圆角。现在用 `cornerRadiusOf(shape)`（`shape.createOutline(...)` 取 `Outline.Rounded`）。

### 胶囊暗部（暗带）已删除，不要加回来

2026-11-23 决策：整段删除。原因不是参数没调好，而是几何：暗带只把**圆头**向内缩、长直边不动，
于是"可见边界"从外轮廓移到了暗带内沿——它与按外轮廓绘制的投影、按压反馈形成两套弧线，
看起来就是形状对不上。需要厚度感就调白高光（`RimLayers`）与填充透明度，不要再用深色描边。

### 按压反馈：放大，不压暗

- 整块玻璃放大到 **1.25**；不压暗。
- **用弹簧，不用 `tween`**（2026-11-23 用户："放大缩小不够丝滑"）。按下 `Motion.PressDown`
  （0.6 / 800）、松开 `Motion.PressUp`（0.8 / 380），数值抄的是 Material 3 **Expressive** 的
  spatial spring tokens。实现是 `Animatable` + **`interactionSource.interactions.collect`**：
  `Animatable` 被下次 `animateTo` 打断时会把当前值和末速度交给新动画，所以连按是连续的。
- **别用 `collectIsPressedAsState()` 驱动按压动画**（2026-11-23 用户："有时动画不出现"）：
  `pressed` 是布尔状态，快速点按时 Press 和 Release 落在**同一帧**里，重组只看到最终的 `false`，
  动画一次都不播。吃 interaction 流则每个事件都到。
- 另配一条下限 `MinPressVisibleMs = 90ms`：Release 若来得太早，先等满 90ms 再收回，
  否则"按下去"这一下肉眼看不见（点得再快也有反馈）。
- **涟漪关掉**：ripple 画在方形点击区里，圆钮上会变成"中间一个圆盘/方块变暗"，和整块玻璃对不上。
- 组合方式：`MutableInteractionSource` 同时交给 `GlassSurface`（负责缩放）和内部 `clickable`
  （`indication = null`）；页头那种"玻璃胶囊 + 图标"两层写的，用 `LocalGlassPress` 共享同一个 source。

### 液态玻璃（AGSL 真折射，2026-11-23 起）

- **液态玻璃是所有玻璃表面的默认样式**（2026-11-23 起，`GlassSurface(liquid = LiquidGlassSpec.Default)`）：
  底栏胶囊、页头胶囊、二级页返回钮/动作钮、聊天输入框、发送钮、整条磨砂页头 —— 同一套光。
  `liquid` 必须是**同一个实例**（`LiquidGlassSpec.Default` / `.HeaderBand`）：它被当 `remember` key，
  每次重组新建一个 data class 会让着色器重建。
- **`GlassSurface` 有两个重载（带 frost / 带 GlassBackdrop），两边的 `liquid` 默认值必须一致**：
  曾经 backdrop 重载默认写成 `null`，把默认关掉了 —— 结果全 App 只有走那条重载的**聊天输入框**
  还是旧玻璃（2026-11-23 用户一眼看出来）。
- **横条 = 方案 A（`LiquidGlassSpec.HeaderBand`）**：`refraction=0` / `dispersion=0`（一条直边上做折射
  只会让滚过的字变形）、`rimBottomOnly=true`（亮度 = `clamp(n.y,0,1)`，只有下沿一条直亮线）、
  `faceWhite=0.80`（**面要稳定**：头顶是黑字还是空白，页头都是同一块浅板）、`sideShadow=0`。
  形状必须是 **`RectangleShape`**：下两角留圆角时那条"直线"会在两端往上翘，看着就是"大胶囊的下半圈"
  （用户两次提到）。
- **横条的投影用 `shadowBottomOnly = true`**（只在下沿投一条 10dp、alpha 0.16 的细带，模糊后向下散）：
  页头整条是半透明的，**照常画"整块模糊矩形"会从面里透出来把整条糊灰**；四周均匀也没有意义
  （两边在屏幕外、顶上在状态栏后面）。模糊向上散的那一点正好当内阴影。
- **玻璃面颜色必须跟主题走**（`uFaceColor` = `colorScheme.surface`）：写死白的话，深色模式下
  整块玻璃会发灰发白（2026-11-23 深色模式实拍就是这个症状）。
- **发送钮**是第一个试点，参数见下（[ADR 0007](../adr/0007-liquid-glass-agsl.md)）：
  `sdRoundRect` 出距离场与法线 → 只在边缘带内把采样点沿法线外推（折射）→ R/B 通道错开一点点（色散）
  → **上下打光**：细光线亮度 `0.28 + 0.72×|n.y|`（上下两条边最亮、左右只剩一线）、
  内侧反光也按 `|n.y|` 分布、左右两侧压一道**极淡**的内阴影（`|n.x|`，0.02，范围 = 折射带）。
  代码在 `app/LiquidGlass.kt`。
- **光照模型是"上下打光"不是单侧光**（2026-11-23 用户要求）。别再引入 `uLight` 那种单方向点乘：
  圆钮上单侧光会让一侧高光过重、另一侧发灰。
- **薄玻璃的轮廓靠细光线（`rimWidthDp = 1.6dp` / `rimAlpha = 0.85`），不靠厚倒角**：
  折射带 4.5dp、强度 0.22 就够，再大就变回"厚透镜"（用户："玻璃感觉太厚了"）。
  这个 1.6dp **所有表面共用**，不要再按表面分别调。
- **边缘柔光（`specular`）必须和细线同宽**：早期它按 `band`（折射带 4.5dp ≈ 15px）铺，
  在有色面上就是"一层体现厚度的渐变白"，在页头上因为按 `clamp(n.y,0,1)` 分布变成**梯形**，
  用户先后报了这两次。现在遮罩是 `1 - smoothstep(0, uRimWidth, distIn)`，和细线同宽。
- **面板白度 0.22 + 主题色 20%**：透但被糊过。白度 0.30 时背后什么都看不见（"太不透明了"）。
- **玻璃档门槛是 API 33**（`glassSupported`）。低于 33 **不退回那套彩色玻璃**，直接降成轻量；
  `VisualEffects.effective()` 是唯一入口，**渲染点不要直接比 `state.visualEffects`**。
- 底片复用现成的 `frost` 层：**模糊挂内层节点、着色器挂外层节点**（同一节点只能有一个 `renderEffect`）。
- 着色器失败必须兜住：`runCatching` + `AppLog.e("glass", …)` + 退回描边，不能崩。
- 判据：底片是纯色时**看不出折射是对的**（物理如此）；要看效果得让内容从钮背后滚过去。

### 主题色按钮

- 发送钮现在由 AGSL 着色器画（面/高光/内阴影都在里面），下面几条只在**退回描边样式**时生效。
- 发送钮 = 玻璃面 + **叠加 32% 主色**（不是替换底色），停止态保持浅色面。
- **叠加浓度决定高光能不能被看见**（2026-11-23 连改四版的核心结论）：页面底色 `(250)`，
  16% 内部 `(232,229,244)`、22% 内部 `(214,208,234)` —— 面太亮，"亮边"和面拉不开差，
  两版都是"看不出来"。32% 内部 ≈ `(202,192,229)`，配上纯白细线（≈250）差 48 级才成立。
  想更淡就得接受高光更弱 —— 这两个是同一个物理量，不能分开调。
- **有色面只勾一圈细线**（`RimHairlineWidth=1.2dp`、`RimHairlineAlpha=0.9`，纯白）：
  `RimLayers` 那 4 层是四块**硬边**描边，白玻璃面上层间无色差、看着像柔和倒角；一旦面被染色
  （32% 主色），就变成**4 个同心圈**（2026-11-23 用户截图）。所以 `fillColor != null` 时改用单圈。
- **纯白是对的**（我先前判断错，用户纠正）：这颗钮外面包着一圈投影，边缘的"环境色"是被压暗的
  灰蓝（≈240），不是页面底色（250）—— 纯白细线在那个环境里就是亮的。判断高光够不够亮，
  要比的是**紧贴边缘的那圈颜色**，不是页面底色。
- **投影是四周均匀的一圈**（`14dp` 模糊 / `alpha 0.10`，**不下沉**）：下沉会让灰影堆在下方，
  看着像"托在底座上"（用户："正确的外部阴影是向周围均匀扩散"）。
- **不要按档位再压投影**：曾给液态档乘过 `alpha×0.35 / 模糊×0.6`，结果钮外圈只比底色暗 3~5 级
  （输入框是 10~14 级）—— 用户直接看出"没有阴影"。要调就调全局那两个常量。
- 纸飞机是三角形，几何居中看着偏左：图标右移 **1dp**。
- **图标本身也是玻璃**：`tint = primary.copy(alpha = 0.72f)`。图标画在玻璃层之上，给它透明度，
  背后那层磨砂（含折射结果）就会透上来；纯色会像印上去的（2026-11-23 用户要求）。

### 页头铺到状态栏

- 页头盒子从 `y = 0` 起（含状态栏），标题行按状态栏高度下移；内容是满屏的，滚到页头下面
  会被磨砂糊掉（聊天）或从色罩里淡出（其它页）。
- 各页顶部让位 = **状态栏高度 + 页头高度 + 间距**（`PageHeaderHeight`/`DetailHeaderHeight` 本身不含状态栏）。
  间距按"第一项是什么"算，别一律加同一个数（2026-11-24 用户逐页报过）：
  - 会话页第一项是分组标题（自带 16dp 上边距）→ 内容让位**不再另加**；
  - 角色卡第一项是卡图 → 让位 + 8dp；
  - 聊天页首条消息 → 让位 + 16dp（磨砂页头下要留够）；
  - 设置二级页的列表（模型档案）→ 列表自己 + 16dp，和其它页开头 `SectionLabel` 的 16dp 对齐。

### 键盘与橡皮筋

- 内容底部让位 = **max(导航栏, 键盘)**，不能相加（键盘弹出时导航栏已被盖住，相加会多抬一个导航栏）。
- 列表两端是橡皮筋：`Modifier.rubberOverscroll(rememberRubberOverscroll())`（`:app/RubberOverscroll.kt`），
  五个可滚动面全覆盖：会话列表、角色卡网格、聊天、世界书条目、设置**一级**页（`verticalScroll`）与二级页；
  跟手 0.62、最多 64dp、松手用 `Motion.RubberBand`（**1.0 / 380，临界阻尼**）回弹；
  它内部用了 `LocalDensity`，所以这个 modifier 必须标 `@Composable`，
  且要挂在 `verticalScroll` / `LazyColumn` **之前**（外层）。
- **阻尼要渐进**：老写法 `offset += available * 0.5` 是全程一样重、撞上 max 突然停，手感发硬；
  现在乘一个 `room = 1 - |offset| / max`，起步跟手、越拉越紧、到顶导数为 0（不再是硬墙）。
- **回弹时要按住"不许过冲"**（2026-11-23 用户："复位后再抽一下"）：欠阻尼弹簧会越过 0 再回来，
  64dp 的位移上就是肉眼可见的一次反向抽动。现在弹簧取临界阻尼，并且写 `offset` 时按方向
  `coerceAtLeast(0f)`，保证只朝 0 收敛。
- **光 `cancel()` 不够，还要"代数"**：动画是每帧回调，`cancel()` 只是标记，已经排进这一帧的回调照样执行 ——
  它会把用户刚拖出来的位移**盖回动画轨迹**，那就是"抽一下"。所以 `RubberOverscrollState.gen`
  每次手指接管 +1，动画写 `offset` 前先核对代数。日志里 `回弹被打断 offset=0 / 回弹结束 offset=23`
  这一对就是它。`回弹结束` 现在带 `反涨=true|false`，用来确认 |offset| 是否单调下降。
- `onPostFling` **不要 join 回弹动画**：会让滚动链按在那儿半秒，还可能和新手势交错。
- **物理：累计 raw + 渐近公式，不要"逐帧乘阻尼 + 夹上限"**（这是 2026-11-23 晚重写的核心）：
  - `raw` = 手指拖出去的原始量（fling 的位移也算，但有上限）；
  - `visual = raw·R·K/(R+K·raw)`（R 固定 400dp、K=0.6）—— 渐近、越拉越紧、**没有硬墙**；
  - 松手让 `raw` 用 `spring(1.0, 700)` 回 0，`visual` 由同一个公式跟随。
  旧写法（逐帧 ×0.62、`coerceIn(±64dp)`）有两个结构性毛病：撞上限是硬墙；逐帧阻尼和回弹动画
  互相覆盖，手感"被拽住"。
- **`raw` 必须有上限**（由"视觉最大 64dp"反解，≈444px）：一次快速甩动撞到顶部时，fling 的
  剩余惯性会整段灌进 raw —— 真机实测到 `raw=-10658px visual=-1149px`（328dp 位移），
  回弹要倒着走完这一万多像素，内容就在最远处停几百毫秒，用户称之为"悬停"。
- **fling 与手势要用 `NestedScrollSource` 区分**：到上限之后 fling 送来的位移只"吃掉"，
  不再累积、也不 `takeOver`（否则回弹被反复重置）；**fling 一撞上限就立刻开始回弹**，
  不要等 `onPostFling`（那可能是几百毫秒之后）。
- **"吃掉"必须记在一个"这次 fling"的标记上（`flingEaten`），不能拿"raw 现在是否在上限"当判据**
  （2026-11-23 晚第二次修，上一版正是栽在这里）：回弹一启动，下一帧 raw 就离开上限
  （`spring(1.0,700)` 16ms 走掉约 7%），"在上限才挡"的守卫立刻失效 → 落进 `takeOver()`
  把刚启动的回弹 cancel 掉 → 再灌满 → 再 startSettle，逐帧循环。**表现仍是"悬停"，
  但时长 = 整个 fling（大初速度可达 1 秒）**；日志判据：`回弹 start` 反复出现、每两条之间夹
  `回弹被打断 raw=-4xx`。
  现在的规则：fling 一旦把 raw 顶到上限 → `flingEaten = true`，此后该次 fling 的所有位移
  只吃、不累积、不 takeOver；**清标记只认 Drag 位移**（真正的新手指）。`onPreScroll` 里
  fling 直接返回 `Offset.Zero`，不让它接管回弹。
- **`onPostScroll` 要把 available 全部报成"已消费"**（`return available`），不要报 `delta/damping`：
  报不足的部分会被系统当成"还没消费"，交给 Android 12+ 的 overscroll 拉伸再叠一层 ——
  表现就是**每个页面能拉的最大距离不一样**（内容越短越能拖；角色卡页最明显）。
- **回弹必须能被手指打断**（2026-11-23 用户："回弹动画中再拉，不跟手，还在播上一次的动画"）。
  根因：回弹是在 `onPostFling` 里 suspending `animate(...)` 写 `offset`，普通回调里没法取消，
  用户新拖动写进去的值下一帧就被旧动画覆盖。修法：动画跑在 `settleJob` 里，
  `onPreScroll`/`onPostScroll` 一进来先 `settleJob.cancel()` (`takeOver()`)，新手势从**当前位移**接着算。
  死循环不会出现：回弹自己不走 scroll 回调。

## 动效：先查表，再动手（2026-11-23 起）

完整的三张表（该不该动 / 曲线与时长 / 评审 Never Ship）在 [docs/design/motion.md](../design/motion.md)。
这里只放每次都要遵守的：

- **进入曲线 = `Motion.Decelerate = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)`**（ease-out-quad）。
  它换过三次，判据是"位移摊在哪个时段"：240ms 的翻页用强 ease-out `(0.23,1,0.32,1)` 时
  前 10% 的时间就走掉 40% 位移、约 75ms 视觉走完 —— 用户反馈"太快"；现在约 155ms 走完。
  **要更慢先加时长（`Motion.Slow` 现在就是 280，240→280 是用户验收后的值），不要再把曲线往缓推**（会变成起步发木）。
  别用 `ease-in`，也别退回 `FastOutSlowInEasing` 或 `(0,0,0,1)`。
- **可被打断的东西用弹簧，不用 tween**：`tween` 重定向会从零起跑（那下顿挫就是不丝滑）；
  弹簧带着当前速度继续走。**归位类动画阻尼必须 1.0**（欠阻尼 = "复位后再抽一下"）。
- **减动效要自己接**：`rememberReduceMotion()`（`ReducedMotion.kt`，取
  `ValueAnimator.areAnimatorsEnabled()` + `ON_RESUME` 重读）。系统「移除动画」开着时：
  页面/tab/四标签切换 → 纯淡化、发送↔停止 → 纯淡化、按压 → 不缩放、橡皮筋松手 → 直接归零。
  口径是"更少更轻不是全关"，手指直接拖出来的位移保留。**渲染点统一用它，别各自读系统设置。**
- 频率闸门是真的：100 次/天以上的操作不加动效；说不清目的（反馈/空间连续/状态指示/避免突兀）就别做。

## 窗口 insets 的规矩（踩过两次）

- `MainActivity` 必须调 `enableEdgeToEdge()`，Manifest 里钉死 `windowSoftInputMode="adjustResize"`。
  **两者缺一就会出现"点输入框时画面先抽一下再落回"**——系统缩放窗口和 Compose 的 `imePadding()`
  动画各走一套，位移叠了两次。
- 页面用 `Scaffold`（含 `TopAppBar`）拿状态栏/导航栏留白；聊天页不许再用裸 `Column` 当根。
- 键盘遮挡用 `Modifier.imePadding()`，别用 `adjustPan`；`values-night/themes.xml` 要跟着维护窗口底色。

---

# 预设顺序列表：三版才定下来的形状（2026-11-24）

同一件事改了三版，每次都是"看着不对"，值得把几何上的死结写清楚，免得第四次再撞。

| 版本 | 做法 | 撞上的问题 |
|---|---|---|
| v1 | `GroupPiece` 分组面板（首尾圆角、中间直角） | 片型**按位置**算，被拖的那行换位置时形状跟着变 → "拖动时形状跳变"；用户："像从整块里裁出来的一条" |
| v2 | 每行独立卡片（`Radius.card` + 各自 1dp 描边 + 8dp 缝） | 缝隙一去掉，两条描边并排 = **2px 双线**（`cardBorder` 半透明，两层叠加更黑） |
| v3 | `GroupPiece` + **起拖冻结片型** | 拖动中不跳了，但**落下**时解冻、按落点重算片型 → 用户仍能看到"落下时变一下" |
| v4（定稿） | 每行 `Radius.group` **四角全圆**、贴合、**不画描边**、衔接处一条 1px 线（两端各让 18dp 避开圆弧）；拖动只改投影与缩放 | — |

## 死结在哪

**「四角全圆 + 每行一圈描边 + 行间贴合」三件事不能同时成立。**

- 贴合 ⇒ 相邻两行的描边在缝隙处重叠。半透明描边叠两层会更深、看着像 2px；
- 想只画一条 ⇒ 就不能每行画整圈，必须按"这一行在组里的位置"只画外侧边 —— 而片型一旦按位置算，
  拖动就会改形状；
- 想形状不变 ⇒ 每行必须同一种形状；同一种形状 + 全圆 + 贴合 ⇒ 只能放弃每行描边。

定稿选了"形状恒定"（用户对拖动的期待最高），代价是这一页的列表没有外框描边，只用胶囊本身 + 一条内缩细线分隔。
要外框就只有两条路：给整组画一圈外框，或者行间留缝 + 每行带描边（回到"一叠卡片"）。

## 顺带记两条实现细节

- 衔接处那条线两端要让出圆角半径，否则会压在圆弧上、看着像"线戳出了胶囊"；
- 拖动中要**藏起这一行下方的分隔线**，不然那条线会跟着胶囊一起被拎走。

## 页头"半透明"是设计，别当 bug 夹死（2026-11-24）

用户报「全局世界书条目页的页头是透明的、卡内嵌书那页是不透明的，应该是 bug」。我按这个理解把
全局页的 `LazyColumn` 从"`contentPadding` 让位"改成"`padding(padding)` 裁在页头下面"，
半透明就没了 —— 用户回："我本来就需要让页头是半透明的，这是我的设计。"（当天 revert。）

- 事实：二级页页头是 0.94 的色罩（`HeaderScrimAlpha`），列表从它底下滑过时本来就该透出 6%；
  列表类二级页（世界书条目两页、预设编辑、聊天）的既定观感就是这样。
- 判据：**看这页会不会滚**。会滚的列表页 → `contentTopInset = 0.dp` + 让位做在 `verticalScroll`
  **里面**；内容短、不滚的设置页 → 让位做在外面，内容被裁在页头下面。两种都合法。
- 教训：用户报"两页不一致"时，先问他**想要哪一种**，别默认"看起来更实的那版"是对的；
  这类"透明度/厚度/间距"的差异，一半以上是审美选择，不是缺陷。

## 非懒加载的 `ListGroup` 会吃掉转场动画（2026-11-24）

症状：内嵌世界书条目多的卡，进「角色详情 → 配置 → 内嵌世界书」卡顿、转场动画直接消失。
日志（`tag=nav`）：正常转场是 `用时 280–290ms，帧 30±，最长帧 16–58ms`；
这一页三次 push 分别是 `455ms/帧 2/最长帧 448ms`、`318ms/2/307`、`280ms/5/257`。
**pop 方向全部正常**——因为它不重建列表。

原因：那一页用的是 `ListGroup`（`Column` 里 `for (index in 0 until count)` 的**非懒加载**版本），
进页第一帧就把全部条目组合出来：几百条 × (`GroupPiece` + `ListRow` + 两行文字 + `Switch`) 一次做完，
一帧 448ms，280ms 的 `Animatable` 直接跳到终态。

修法：**条目页一律 `LazyColumn` + `itemsIndexed` + `GroupPiece(rowPieceAt(index, count))`**
（全局书条目页本来就是这样，所以它没这个问题）。
`ListGroup` 只留给"行数固定且很少"的页面（设置项、三五行那种）。

判据：**任何行数由数据决定的列表都必须懒加载**，哪怕现在"只有几十条"。
另外一条：转场卡顿先看 `tag=nav` 的"最长帧"与"帧数"——帧数只有 2–5 就是一次长帧，
帧数 30± 而最长帧 40–60ms 才是均匀掉帧，两者的修法完全不同。

## 占位块被"替换"成 alpha=0 的图片，就会闪一帧空白（2026-11-24）

症状：刚进 App 时会话列表的角色头像"闪一下"。

`CardAvatar` 原来是 if/else 两支：没解码时画占位方块（首字），解码后画 `Image`，
而 `Image` 的 `alpha` 是从 0 淡入的。**两支是替换关系**，所以图片出现的那一帧：
占位已经没了、图片还是全透明 → **一帧空白**，紧接着才淡入，看起来就是闪。

两处一起修：
1. 占位块**一直在下面**（`Box` 里先铺占位，图片用 `matchParentSize()` 淡入盖上去）；
2. 首屏用到的几张图在**发布列表之前**并行解好（`preloadAvatars`，上限 8 张、已缓存跳过），
   行一出现就是最终画面，连淡入都省了。

规律：**"占位 → 内容"的两支写法 + 内容从 0 淡入 = 必然闪一帧空白**。
要么占位垫在下面，要么内容不淡入；异步资源列表（头像/封面/缩略图）都要检查这一条。

## 启动第一眼的白/紫不是 Compose 画的（2026-11-24）

用户：点开 App 先白色铺满 → 瞬间变淡紫 → 主页渐入。

- **白 = 系统窗口底色**：`themes.xml` 里写的是 `#FFFBFE`（Material 浅色默认），
  而 Compose 主题的 `background` 是 `#F8F6FC`。这一段在 Compose 第一帧之前，只能由主题决定。
- **淡紫 = 我们自己的启动遮罩**（`StartupCurtain`）。Android 12+ 上面还有一层 splash，
  底色同样取自主题（`windowSplashScreenBackground`，默认跟 `windowBackground`）。
- 修法三层：颜色资源（values + values-night）、v31 主题明写 splash 底色、
  `MainActivity.onCreate` 按用户真实主题 `setBackgroundDrawable` 再对齐一次。
- 判据：**"启动第一帧颜色不对"先去查 windowBackground / splash，不是 Compose**。

## 被压栈的页面会被移出组合树，局部 `remember` 会丢（2026-11-24）

用户：详情页停在「会话」标签，点进聊天再返回，却回到「简介」；从「配置」进二级页也一样。

真因：`PageStack` 转场结束后只保留当前页（离场页 `pages.removeAt(0)` 移出组合），
页面里的 `rememberSaveable` 不在 SaveableStateHolder 里，**重建时归零**。

修法：跨页需要记住的 UI 状态放 `UiState`（这次是 `detailTab`），页面只读/写它。
判据：**凡是"离开这一页再回来应该保持不变"的状态，都不能用页面内的 `remember`**；
只有"离开就该重置"的（临时弹层、输入草稿、确认框）才留在页面里。

## reverseLayout 贴底时，文本长高=整屏瞬移一行（2026-11-24）

**症状**：聊天页串流时，角色每换一行字，整个画面"啪"地往上跳一行高。

**原因**：聊天列表是 `reverseLayout = true`，贴底（`firstVisibleItemIndex==0 && offset==0`）
时视口钉在内容底部；串流气泡长高多少，上面的内容就被顶上去多少 —— **一帧完成**。

### 第一轮（v112）：补偿 + 逐行收回 → 抽搐

做法是"每长高 delta 就把 delta 加进补偿，再用 180ms 收回"。单行时看着还行，遇到快速
长文本（用户那条回复 6190 字）就出问题：补偿越积越多、动画不断被重置 → **画面抽搐**。
教训：**"累积 + 回收"的补偿在事件频率接近动画时长时必然失稳**，别用。

### 第二轮（v113，现行方案）：一条动画追高度

- 气泡 `onSizeChanged` 量实际高度；
- 一个 `Animatable` 用 `Motion.Base` 平滑**追**这个高度；
- 平移量 = `实测高度 − 平滑高度`，只在贴底时作用到列表的 `graphicsLayer.translationY`
  （只改绘制，不重新布局）。差值天然会收敛到 0，没有累积，也就不会抽；
- 气泡刚出现那一次（`smoothHeight.value == 0f`）直接 `snapTo`，不产生位移。

### 不在底部时怎么办（仍未最终定）

`reverseLayout` 的锚点是"第一可见项 + 偏移"，所以：

- 第一可见项是**更早的消息**（下标 > 0）：气泡在视口外长，视口本来就不动，**什么都不用做**；
- 第一可见项**就是串流气泡自己**（下标 == 0、滚出去 > 0）：长高会顶走正在读的这一段。
  现在的做法是**冻结文本**（气泡停止更新）。用户 2026-11-24 提出"不想要冻结"，三条路线：
  A 保持冻结；B 冻结**高度**但内容照常追加（`height()` + `clipToBounds`，零抖动，推荐）；
  C 真生长 + 每次长高把滚动偏移减 δ（最正确，但布局与滚动两趟，可能每行 1 帧回拉）。
  **等用户选，别自作主张。**

判据：任何"贴底跟随"的列表，内容长高都要**用连续动画追**而不是"加一笔再收"；
另外"要不要跟随"必须由用户位置决定，不能无条件跟随。

## reverseLayout 的锚点语义与"读气泡自己"的补偿（2026-11-24 晚）

白天那条「贴底长高 = 整屏瞬移一行」的根因，晚上把它彻底拆清楚了（读 Compose 1.7.6 源码
`LazyListMeasure.kt` / `LazyListMeasuredItem.kt` 确认，不是推测）：

- 列表滚动位置 = **(firstVisibleItemIndex, firstVisibleItemScrollOffset)**，锚点钉在**第一可见项的
  起始边**上。`reverseLayout = true` 时起始边就是**底边**：`o == 0` 表示第一项的底边正卡在视口底边。
- 项的摆放公式是 `y_top = H + o - h`（H = 列表高度，h = 该项高度）。所以：
  - `h` 变大而 `o` 不变 → 内容整体**往上移** `Δh`（这就是贴底时"瞬移一行"）；
  - `o` 变大 → 内容整体**往下移**（往前走 = 更早的消息进来）。
- 三个场景其实是同一个公式的三种取值：
  - **A 贴底**：`idx == 0 && o == 0`，长高就得跟（现在的"动画追高度差"）；
  - **C 读气泡自己**：`idx == 0 && o > 0` —— 气泡底边沉到视口外、正在读它上半截，
    长高**必然**顶走内容；
  - **B 看更早的消息**：`idx > 0`，气泡根本**没被布局**（它在锚点后面），长高不影响任何可见内容。
    —— 所以 B 本来就不需要任何处理，之前"冻结"只对 C 起作用。
- 结论：C 的正确修法不是别的，就是把 `o` 加 `Δh`（`requestScrollToItem(0, o + Δh)`）。
  偏移是布局量，改它不会让"视觉位置与真实布局/触摸区域"错位，也不会和橡皮筋打架。
- 布局与滚动是两趟：高度在**布局**里变，偏移要到**下一次测量**才生效，中间那一帧会跳。
  所以补偿那一帧用 `graphicsLayer.translationY = Δh` 顶住，等偏移落地时把 translation 归零 ——
  两边在同一帧抵掉，视觉一步不挪。**这不是"绘制位移掩盖布局"**：translation 只活一帧，
  且恒等于还没落地的那笔偏移。
- 只有 `Δh` 是**量出来的**才补（`onSizeChanged` 前后差），不是"串流气泡可见就补"；
  `isScrollInProgress` 期间一律不补（只把基线推到最新），否则等于和手指抢。
- 顺带确认两件原本担心的事：① `items(transcript.asReversed())` **没有稳定 key 也不会在串流结束时
  跳** —— 串流气泡占的位置正好被落库后的那条助手消息接班（项数不变、下标不变）；
  ② 但"串流中往 transcript 加消息"（发新消息）会**插入**一项、下标全错位 —— 这个没修，
  它是发消息时的既有行为，与串流增长无关。

**已知未修**：从贴底切出去的那一帧，`translationY` 会从"追高度的欠账"直接归零，
欠账大时（极快的流）会往上收一下。日志 `模式=C … 平滑差=Npx` 就是量它，真机反馈再决定要不要补。

## requestScrollToItem 会重播 `animateItem` 的淡入（2026-11-24 真机实测）

v114（撤掉冻结、改成补偿滚动偏移）真机反馈两条，日志 `tag=scroll` 与 Compose 源码对上了：

- **旧气泡一直闪**：补偿用的是 `LazyListState.requestScrollToItem()`，它内部
  `snapToItemIndexInternal` 里 `if (positionChanged) itemAnimator.reset()`，而 `reset()` 会把
  `keyIndexMap` 清成 `Empty`、`firstVisibleIndex = -1`。下一次 `itemAnimator.onMeasured()` 里
  `previousIndex = previousKeyToIndexMap.getIndex(key) = -1` → **每个可见项都被判成"刚出现"**，
  `shouldAnimateAppearance = true` → 重播一次淡入。长回复每 100–500ms 补一次，等于旧气泡不停
  淡入 → 用户看到"旧气泡一直闪烁"，而串流气泡没挂 `animateItem`、所以不动。
- **拖动时文字一直往上顶**：因为 `requestScrollToItem` 会 `scroll{}` 抢 mutex、**取消进行中的拖动**，
  所以只能"手势期间不补"；可手指拖动只改变偏移，气泡长高照样把内容顶走 → "不跟手"。

修法：补偿改用 **`dispatchRawDelta(Δ)`**（走嵌套滚动的低层通道：不动 `itemAnimator`、不抢 mutex、
不打断拖动/惯性），于是可以**手势期间照补** —— 它只抵掉"长高顶上去的那一笔"，手指的位移照常生效。
返回值是实际滚出去的像素：`<= 0`（到边界滚不动）时基线不动、由桥接先顶住，下一笔再试。

判据（以后还会用到）：**任何"我在旁边顺手调一下滚动"的写法，先看它会不会 `itemAnimator.reset()`**；
`scrollToItem` / `animateScrollToItem` / `requestScrollToItem` 三个都会。只想平移内容时用
`dispatchRawDelta`。

v114 日志拿到：补偿与长高严格 1:1，`顶Y` 在非拖动期间恒定（585 / 1496 / 2154）。
v115（`dispatchRawDelta`）复验：74 笔补偿全部 `+N == drift`、47 笔带 `拖动中`、0 笔 `补偿失败`，
drift 只会是 70/140/210/280（= 1/2/3/4 行，一行 25dp）。

**但 `平滑差` 不能按 v114 那一个 3px 的样本下结论**（后来实测到 70 / 151 / 368px）：
它是贴底"追高欠账"，只在 A 模式下作用到绘制上，所以**跨 A 边界时会丢掉/补上**：
- A→C（开始往上拖）：`平滑差` 被丢掉 → 内容向上收一下（实测 8 / 68 / 70px）；
- C→A（回到底部）：`平滑差` 被补上 → 内容向下沉一下再滑上去（实测 41 / 151px）；
- B→C：B 没有 translation，数字再大（368px）也没有视觉影响。
要修就在跨边界的那一刻把这笔欠账折成滚动量（`dispatchRawDelta`，v115 之后手势期间也安全），
并让 `anchoredHeight` 承接 `平滑高度`；否则等于把绘制量凭空丢掉。

日志读法上的一个小坑：**新一轮串流第一行 `模式=A` 常带着上一轮的气泡高/顶Y** —— 重置 effect 与
模式日志 effect 是两条 `LaunchedEffect`，顺序不保证。只影响读数，不影响行为。

## 新页首帧会把转场动画吃掉几帧（2026-11-24）

用户报：从卡列表进详情页、从任意二级页返回详情页、进条目多的内嵌世界书页，转场动画都会丢几帧。

**机制**：`PageStack` 在目标变化的那一帧把新页入栈，新页的**第一次组合+测量+录制图层**
就发生在动画的前几帧里。这一帧的耗时（简单页 30–40ms，内容多的页 60–80ms）直接就是丢的帧；
`tag=nav` 的「最长帧」量的正是它。

**修法（v116）**：动画开始前先**预热**——新页入栈后先在"起跑位/全透明"下过完自己的第一帧
（这一帧只画旧页），`withFrameNanos` 等到它落地，再 `running = true` 起动画。
代价：点下去到动画启动多约 1 帧；收益：动画满帧。日志 `tag=nav` 里
`转场预热 Slide 新页首帧 44ms（等 61ms）` 在前、`转场 Slide … 最长帧 12ms` 在后，就是它对上了。

**同时把首帧本身减负**（光预热不治本，重页面还是 60–80ms）：
- 详情页的描述原来只在 `detail` 到齐后才排版 —— 而 `detail` 是**转场进行中**才读回来的，
  于是长描述的重排正好砸在动画中间。现在先用卡列表里已有的 `description` 顶上：
  转场那一帧就把长文本排好，`detail` 到达时文本没变、`Text` 不重排。
- 详情页「会话」tab 原来是 `ListGroup`（`Column` + `for` 全量组合）——**行数由数据决定，
  本来就该 LazyColumn**（和世界书条目页同一条规则）。这个 tab 的选中态会保留，
  返回详情页时正好把整张会话表在第一帧组合出来。

**判据**：`nav` 的两个数一起看 —— 预热那行的「新页首帧」是页面成本，转到那行的
「最长帧」是动画期间的成本；预热生效后后者应 ≤20ms。若「新页首帧」仍然很大（>60ms），
说明还得减页面本身（长文本、图片解码、首帧就发起的重活）。

## 转场预热：数字对上了（2026-11-25 真机）

v116 加了"起动画前先预热新页"（`PageStack` 的 `warming`）。真机日志 `tag=nav` 前后对比：

```
改前：转场 Slide 用时 285ms，帧 26，最长帧 83ms     ← 新页首帧落在动画里，丢约 10 帧
改后：转场预热 Slide 新页首帧 76ms（等 82ms）        ← 这笔钱挪到了动画之前
      转场 Slide 用时 284ms，帧 35，最长帧 8ms        ← 动画满帧（120Hz，35 帧 ≈ 满）
```

预热数字还顺手变成了"页面成本"的度量：About 9–33ms、详情页 41–91ms、内嵌世界书 83–107ms、
聊天 90ms。**以后谁嫌"点了慢半拍"，先看这个数** —— 那是页面自己的成本，该减页面，
不要把预热关掉（关掉它只会把成本挪回动画里）。

## 橡皮筋的位移会画到自己槽位外面（2026-11-25）

用户报：角色卡详情页各 tab 内容往上拖到头时，会盖住上面的 Tab 栏。

真因不在 Tab 栏、也不在内容列表：`rubberOverscroll` 的拉伸是
`graphicsLayer { translationY = state.visual }` —— 一笔**绘制位移**，而且之前**没有任何裁剪**。
日志里同一时间窗（在详情页）反复出现 `rubber 回弹 start raw=-444 px visual=-224`：
内容被画高了 **224px（81dp）**，而 Tab 栏只有 48dp 高，于是压过去 ✔。
详情页三个 tab 的内容都不长，一拖就到边界，所以"各 tab"都能复现。

修法：在 `rubberOverscroll` 的 modifier 链**最外层**（`graphicsLayer` 之前、也就是写在前面）
加 `clipToBounds()`，把拉伸裁在滚动容器自己的槽位里。观感不变（仍是"槽位内露出空隙"），
只是不再溢出到邻居身上。这是**共享组件**，所有可滚动面一起受益。

判据：**任何"用 graphicsLayer 平移内容"的动效（橡皮筋、桥接、拖拽）都要问一句"它的槽位边界
谁来裁"** —— 不裁就一定会盖到邻居身上；而滚动容器自带的 clip 只裁它自己那一层
（`clipScrollableContainer` 主轴向裁、交叉轴留 30dp 给阴影），管不到外面那层平移。

**已真机确认**（2026-11-25 00:40，用户："确实正常了"）：Tab 栏不再被盖，橡皮筋观感没变。

## 2026-11-25 · swipe / 继续 的夹具，以及 `poll-ci.sh` 的假 READY

- **怎么录**（ST 已在 8000 跑着）：
  ```bash
  export LD_LIBRARY_PATH=$PWD/vendor/pw-libs/usr/lib/x86_64-linux-gnu
  export PLAYWRIGHT_BROWSERS_PATH=$PWD/vendor/pw-browsers
  node tools/fixture/capture.mjs --out fixtures/expected/P0-6-swipes.json \
    --character Synthetic --no-wi --swipes 2 --dump-chat fixtures/chats/P0-6-swipes-chat.json
  node tools/fixture/capture.mjs --out fixtures/expected/P0-6-continue.json \
    --character Synthetic --no-wi --continue --dump-chat fixtures/chats/P0-6-continue-chat.json
  ```
- **原版结构**（`fixtures/chats/P0-6-*.json`）：消息带 `swipes[]` / `swipe_id` / `swipe_info[]`，
  当前显示的那条**也在** `swipes` 里。我们的库用 `message_swipes(message_id, seq, content)` 表达同一件事
  （一对多，存 JSON 要自己处理转义）。
- **继续的请求**（`P0-6-continue`）= 普通生成的 messages **加一条末尾 system**：
  `[Continue your last message without repeating its original content.]`；落盘是 `旧文 + 新文` 裸拼
  （夹具里 `ok` → `ok ok`）。别自己编"请继续"这种措辞。
- **`poll-ci.sh` 会假 READY**：workflow 先更新 `apk-latest` 的 body（带新 sha）再上传资产，
  脚本只看 body。本次 v122 就出现过"READY 但资产还是上一次的 17:27 那份"。
  **判据只有一个**：下载后查 dex 里的 40-hex `BUILD_ID` 等于本次 sha，再验签名。

## 2026-11-25 · 用了新的 core 模块，别忘了 app 的依赖表

`app/build.gradle.kts` 的依赖是**手写列举**的（没用 convention plugin）。新写代码 `import app.tavern.core.chat.*`
时本机发现不了（本机不编 `:app`），只有 CI 报 `Unresolved reference 'chat'`。
本次导出聊天就是：core 里有现成模块，app 没依赖。**先看 app/build.gradle.kts 有没有这一行。**

## 2026-11-25 · CompositionLocal 在骨架「之前」读就是空壳（一批按钮全哑的真因）

真机清单 M3 有一整批诡异现象：会话配置页能进去、能滚，但**每一行都点不开**；详情页新加的
模型/世界书/正则/预设行同样没反应；聊天页长按气泡什么都不弹；聊天页删一条消息的确认框也不弹。
日志里只有 `nav` 和 `rubber`，一条请求日志都没有 —— 说明 `show()` / `ask()` 根本没走到宿主。

根因不在手势，在**读取位置**：

```kotlin
@Composable fun SessionConfigScreen(...) {
    val sheet = LocalSheetController.current   // ← 这里还在骨架外面
    GlassDetailScaffold(...) { ... }           // ← 骨架里才 provide 控制器
}
```

`LocalSheetController` / `LocalConfirmController` 是 `staticCompositionLocalOf { SheetController() }`，
默认值是一个**没有宿主的孤儿实例**。在骨架之前读它，`show()` 只是往孤儿身上写了 spec，
页面上永远不会有反应；`ConfirmDeleteDialog` 同理（它默认读 Local）。

修法：需要从**页面顶层**（页头菜单、列表长按、页面级确认框）发请求的页面，自己
`remember { SheetController() }` / `remember { ConfirmController() }`，通过
`GlassDetailScaffold(sheetController = …, confirmController = …)` 传进去；内容区里的子组件仍读
Local，拿到的是同一个实例。另外给两个控制器加了 `attached` 标记：没宿主的请求会打
`ui 抽屉请求没有宿主：<标题>` / `ui 确认框请求没有宿主：<标题>`，以后这类问题不用再猜。
（一级页没这个问题：`TabsRoot` 把 provider 包在整页内容外面，SessionsScreen 等页面读到的就是真的。）

## 2026-11-25 · 卡编辑把别的卡的世界书写进去了（"串卡"）

真机现象：改 Synthetic 的简介并保存，保存后的 Synthetic 内嵌世界书变成 4 条 —— 那是
Seraphina 的。日志里还有一个更怪的名字：`写回卡 …（备份 synthetic.bak.bak.png）`。

两个根因叠在一起：

1. **保存的底本用错了对象**。`saveCardFields` 读的是 ViewModel 的 `card` 字段（"当前会话那张卡"
   的原始 JSON），而 `loadDetail`（详情页读卡）从来不更新它。于是「先聊 Seraphina → 打开
   Synthetic 详情 → 编辑保存」时，应用把 **Seraphina 的整份 JSON** 套上 Synthetic 的字段写进
   Synthetic 的文件：世界书、扩展字段全跟着过来了。修法：详情单独记 `detailRaw + detailRawId`，
   保存前核对 id；`card` 只给发请求用，详情浏览绝不覆盖它。
2. **`.bak.png` 被当成了卡**。`CardStore.list()` 只按 `.png` 后缀过滤，而每次保存都会写
   `<名字>.bak.png` 备份 —— 于是卡库里每次保存都多一张同名卡。用户点到那张备份再编辑，
   写回就落在 `.bak.png` 上，备份又变成 `.bak.bak.png`（日志里那个名字就是这么来的）。
   修法：`isCardFile()` 排除 `.bak.png` / `.tmp`；删卡时连带删备份。

顺带加的：卡编辑保存改成「新建 / 覆盖 / 取消」三选，新建走 `CardRepository.saveCardAs`
（以原卡 IDAT 为基础写一张新文件，名字冲突加 `-2`）。

## 2026-11-25 · 「跟随上级」一出现就把列表挤下去（点错行）

三级配置的四个选择页最早是「这一层有覆盖时才插入『跟随上级』」：选中第一个模型后，那一行
突然插到列表最上面，整列往下挪一格 —— 手指还按在原处时，下面那一行已经换人了。世界书/正则
更明显：点第一本书，列表整体位移，第二次点到的是隔壁。

规则改成：**「跟随上级」常驻**，跟随态就勾在它上面，具体项不再重复勾。行数恒定，
"点在哪儿就是哪儿"。

## 2026-11-25 · 形变容器里的按钮会被逐帧裁掉

输入区展开/收起用的是 height 动画（expand/shrink 或 heightIn 过渡）。把**按钮**放进这个
逐帧变矮的容器里，动画期间它就被裁：用户看到的是"发送按钮被遮住一截"。

改法：动画只作用在**高度占位**上（一个空 Spacer），按钮全部做成浮层（`Box` + `align`），
浮动在胶囊的右下/左下。胶囊是向上长高的（下沿贴住输入框底部、屏幕下沿不动），所以钉在
底部的按钮在屏幕上几乎不动，也永远不会进入任何一个会被裁的容器。
同理：模型钮的淡入/放大也在浮层里做，别塞回那行。

## 2026-11-25 · 返回时选字手柄钉在屏幕上（弹层不跟页面位移）

用户报：从聊天页返回，选中文字的游标/手柄"固定在屏幕上"，直到转场播完才消失。

原因：光标本身在页面内容里，但**选文字手柄与浮动工具栏是独立弹层**（selection handles / popup），
按窗口坐标定位，不吃 `PageStack` 里那层 `graphicsLayer.translationX`。页面滑走了，它们原地不动。

修法：`PageStack` 给每页提供 `LocalPageIsCurrent`（离场页 = false）。聊天页在离场那一帧
`focusManager.clearFocus(force = true)`（收起输入框的焦点/光标），并把气泡正文从
`SelectionContainer` 切回普通 `Text`（选区状态随容器销毁，手柄/工具栏立刻消失）。

## 2026-11-25 · staticCompositionLocalOf 留下陈旧的 false（气泡不能选字）

为在离场时收起"选文字手柄"，一度给每个气泡传 `selectable = LocalPageIsCurrent.current`，
并把 `LocalPageIsCurrent` 定义成 `staticCompositionLocalOf`。现象：最新那条助手气泡长按选不中，
必须先把列表滑一下才行。

原因：静态 local 的**读者不会被单独标记失效**，而 `ChatScreen` 参数没变时会被跳过 ——
从"离场那一帧"的 `false` 起就一直没重读，直到别的状态（滑动改变 `atBottom`）触发一次重组。

两条修法一起用：
1. local 换成**动态** `compositionLocalOf`（读它的页面会在值变化时重组）；
2. 不再改变"有没有 SelectionContainer"（那会让列表项处于两种结构），改成离场时把消息列表
   `key(epoch)` 整体重建 —— 旧 `SelectionContainer` 随组合销毁，手柄/工具栏一起消失。

## 2026-11-25 · CI 失败日志怎么取（不用 gh）

本机没有 `gh`。失败日志由 CI 自己发到 release：`ci-log-android`（`app` 编译）与
`ci-log-jvm`（纯 JVM 单测），每次失败覆盖 `build.log`：

    curl -sL https://github.com/sunke340/AndroidTavern/releases/download/ci-log-android/build.log -o /tmp/ci.log

看时间戳确认是不是本次（历史上这份日志偶尔不刷新）。`grep -nE "^e: |error:" /tmp/ci.log`。

顺带记两个 API 的包名（这次各踩一次，编译期才报）：
- `awaitFirstDown` / `awaitLongPressOrCancellation` / `awaitEachGesture` 都在
  **`androidx.compose.foundation.gestures`**，不在 `ui.input.pointer`；
- `PointerEventPass`、`pointerInput` 在 `ui.input.pointer`。

## 2026-11-25 · Material3 `Surface` 的裁剪层会让超高子树按不到

一条 **18900px 高**的消息气泡里，手指按在正文上却完全不响应（连选中高亮都没有），
而画面照旧有字 —— 因为 `Surface` 内部有 `Modifier.clip(shape)`，那层裁剪在超高节点上
把**命中测试**截断了（离节点上沿约 8k px 之后整棵子树收不到事件），渲染不受影响。

怎么定位的（值得复用）：给每一层挂一个**只读**的 pointerInput 探针（`SelectionProbe.kt`），
每次按下打「按下窗口坐标 / 节点真实上沿 / 节点真实尺寸 / 按下点的本节点局部坐标 / 自洽差」。
自洽差 = `(窗口 y − 上沿 y) − 局部 y`，它不为 0 就是坐标空间错位。这次全为 0，
所以问题不在坐标而在**命中路径**：看哪几层打了「按下」就能定位到具体那一个 modifier。

结论文本条：**消息底板不要用 `Surface`**，用 `MessagePanel`（background + border）。
以后要加投影/抬升，别退回 `Surface`；任何会生成本地渲染层的 modifier，都要先想
"这条消息可能有多高"。

## 2026-11-25 · 选字会拖动列表（焦点 bringIntoView，不是滚动逻辑）

`SelectionContainer` 一旦拿到焦点，焦点系统就会对焦点节点请求 `bringIntoView`，
而 `LazyColumn` 会用 `BringIntoViewSpec` 算滚动量。默认算法对"比视口高、但一端在视口内"的节点
会挑一条边对齐 —— 18900px 高的消息正好落在这一支：`trailingEdge - containerSize = -427`，
于是长按一下列表就挪 427px；按得靠上时改挑 leadingEdge，于是往上跳一大段。

修法：`LocalBringIntoViewSpec` 提供一个 `calculateScrollDistance` 恒为 0 的 spec（只挂在聊天列表上）。
判据：`sel 列表 按下 … 偏移=` 在反复长按之间不变；日志 `scroll 焦点滚动被拦下 …`。

教训：**"选中文字"这类操作不应该改变阅读位置**。任何会拿焦点的子节点（`SelectionContainer`、
输入框）放进滚动容器时，都该先决定"焦点滚动要不要"。

## 2026-11-25 · 一个常量让两页内容静默消失（`val scrollUnderHeader = true`）

`CardConfigPageScreen` 原来用 `val scrollUnderHeader = page == CardConfigPage.WorldBook` 表示
"内容滚不滚到页头底下"，**同时**拿它当选内容的开关（`if (scrollUnderHeader) CardWorldBookPage(...)
else 设置内容`）。`6f4dd53` 为了让三页都半透明，把它改成常量 `true` —— `else` 支整段成了死代码，
于是「对话样式」和「关于这张卡」两页在 v149 / v150 的真机包里都显示成内嵌世界书条目列表。

编译不报、`check-*` 看不见、回执也没点那两行 —— 三套防线全过。真正把它捞出来的是"接手时逐个
`GlassDetailScaffold(` 调用点读一遍"（为 INV-01 数调用点）。

教训：**"怎么排版"和"显示哪一页"不能共用一个布尔**。分发条件直接写页面判据（`page == ...`），
别先抽一个中转布尔；`if (常量)` 包两套内容，等于把"以后看不出来"写进代码。
另外：真机清单要点**入口行**（列表里的每一行），不能只点"这次改到的那一行"。

## 2026-11-25 晚 · 页头 API 收口（INV-01）：只剩两个入口

页面层的入口从"两个可自由组合的参数"收成"一个必填模式 + 一个留白量"：

- `headerMode: DetailHeaderMode`（`Frosted` 磨砂带 / `Translucent` 半透明色罩），**必填**；
- `contentSpacing: Dp = 0.dp`：列表传 `Space.l`（首项离页头底边 16dp），Column 页不传。
- 骨架交出的 `padding.calculateTopPadding()` = `状态栏 + DetailHeaderHeight + contentSpacing`，
  页面**只写它、不许再接 `+`**。

`contentTopInset` / `frostedHeader` / `DetailScrollUnderTop` **已删除**（`DetailScrollUnderTop` 曾与
`DetailHeaderHeight` 同值，两套写法并存正是漂移的源头）。本文件与 `device-verification.md` 里
2026-11-25 之前写下的旧参数名按这一条换算，别照着改。

判据：`tools/check-ui-invariants.sh` 的 INV-01 —— 页面文件（含 `GlassDetailScaffold(` 的、除
`DetailPage.kt` 外）不得出现旧参数名与 `calculateTopPadding() +`，每处调用必须显式传 `headerMode`
（默认值会让页面在判据里变盲区，`6f4dd53` 的卡配置分发就是这么静默坏的）。
自测：`cp -r app/src/main/kotlin/app/tavern /tmp/x`，改坏一处，再
`UI_INVARIANTS_APP=/tmp/x bash tools/check-ui-invariants.sh` 看它红。

## 2026-11-25 夜 · "抽屉期间列表还在动"：IME 让位也是列表底部让位的一部分

现象：模型抽屉弹出/收回时，背后聊天气泡整片上下跳。上一版只冻了**输入区高度**，还是跳 ——
真机日志把两个来源分开了：

```
21:33:36.865 输入区高度=301px      ← 展开 + 键盘在
21:33:38.092 输入区展开=false 键盘=true
21:33:38.168 输入区高度=240px      ← 输入区开始缩
21:33:38.484 键盘=false            ← 键盘收了：IME 让位整块消失
```

抽屉要先收键盘，而 `padding.calculateBottomPadding()`（含 IME 让位）和输入区高度**两笔都直接进
列表的 `contentPadding.bottom`**。反排列表锚在底部 → 少掉多少让位，最新消息就往下掉多少。
所以只冻输入区不够，要把"**这一整笔底部让位**"冻住。

修法：`ChatScreen` 在抽屉打开那一刻把 `padding.bottom + 输入区高度 + 12dp` 存成
`frozenListBottom`，抽屉期间用它；收起后等键盘落定（500ms）再解冻（此时实时值已回到同一水平，
解冻不产生跳）。**只在打开时键盘还在的情况下冻**：键盘本来就没开时打开抽屉什么都没变，
冻住反而会把"收起后键盘回来"那一段变成延迟硬跳。

另一个坑：宿主实例只能在**骨架内部**读。页面顶层 `LocalSheetController.current` 拿到的是没有宿主的
默认实例，它的 `spec` 永远是 `null` —— 拿它判断"抽屉开没开"会永远为假。读的地方要放进
`GlassDetailScaffold` 的 content lambda 里。

## 按压反馈：`collectIsPressedAsState` 会吞掉短促点按（2026-11-26）

用户报"点下去的变深出现太慢，点得太短干脆没有效果"。代码里按下已经是 `snap()`（无进入时长），
所以问题不在动画，而在**事件采集**：`collectIsPressedAsState()` 是"把交互流收成一个布尔 state"，
同一帧里按下 + 抬起（一次 60~90ms 的快速点按）两次事件会被合并，组合读到的值始终是 false ——
一次都不播。

修法（`Style.kt` 的 `rememberPressTint`，行 / 卡 / 底栏共用）：
直接 `source.interactions.collect { … }`，`PressInteraction.Press` 时 `Animatable.snapTo(1f)`；
`Release/Cancel` 时**先补足最短可视 90ms**（按时间差算）再 `animateTo(0f, tween(Motion.Fast + 40))`。
这样"点一下就走"也保证亮过一下。

## 键盘收起：别等 `imeVisible == false`（2026-11-26）

输入框"聚焦翻倍、收键盘回一行"的判据原来是 `imeVisible`（`ime.getBottom() > 0`）。
IME 的退场有 200~300ms 动画，`imeVisible` 在动画**跑完**才变假 —— 观感就是
"键盘先滑下去、滑完了输入框才慢半拍缩回一行"。

修法：`ChatInputBar` 用 `snapshotFlow { ime.getBottom(density) }` 观察高度，**一旦开始下降**
就置 `imeHiding = true`，`expanded` 立刻变假（动画马上开始）；键盘再长高时清掉它。
注意 `ime` 与 `density` 必须在组合里取好再传进 `snapshotFlow` —— 那里不能读 CompositionLocal。

## 聊天页底部浮动钮：不能进"被测量的那一层"（2026-11-26）

列表底部让位读的是 `ChatInputBar` 的**实测高度**（`onSizeChanged`）。「回到底部」钮如果塞进
同一段测量里，它出现/消失就会改变让位 → 整屏气泡上下跳一下。
现在的摆法：`bottomOverlay` 里用 `Column` 把按钮放在 `ChatInputBar` **之上**，
按钮只增加"输入区上方的一段高度"，输入栏本身仍在屏幕底部不动，让位数字完全不变。

## 停止生成必须知道"这一轮在改哪条"（2026-11-26）

`stop()` 原来只做"取消 + 把 streamingText 当一条新回复追加"。对普通发送没问题，
但「重新生成 / 继续」这两轮是**改写已有那一行**（`ReplaceTarget`）—— 那两条消息在开始时就被
从界面上撤下来了（免得"旧气泡 + 流式气泡"同屏）。停止时再把半截内容当新消息追加，等于：

- 「继续」：原条不会回来了（库里还在，所以"退出会话重进又好了"——用户 2026-11-26 报的正是这个）；
- 「重新生成」：库里多出一条新行，而原来那行没被更新，swipe 版本也没写。

修法：`StreamingTask` 带上 `replacing`，`stop()` 分三路——普通发送追加一条；
继续 `restore.content + partial` 拼回原行（没收到字就原样放回）；重新生成把 `partial`
作为 `base + partial` 的一条新 swipe，并 `updateMessage` 到同一行。

顺带一条：`lastSwipes` 是"当前显示那条消息"的版本表。打断了、换成新的一条之后它就是过期的。
`regenerateLast` / `continueLast` 现在只在"底本里当前项 == 屏幕上最后一条的正文"时才信任它，
否则重建为 `[当前内容]`；`stop()` 也要把它归位。否则下一轮重新生成会把**上一条**的旧文本
写到新这条上。

## 聊天列表要用"原下标"做 key（2026-11-26）

反排列表 + `itemsIndexed(transcript.asReversed())` + **没有 key**：Compose 按下标认条目，
在头部插一条 = 所有已有气泡都换了槽位 → 每个 item 都被当成"新出现的"，整屏一起播
`animateItem` 的淡入/淡出（用户 2026-11-26："发一条新消息，整个列表会突然显示一下"）。

修法：`key = { index, _ -> "msg-" + (state.transcript.lastIndex - index) }` —— 用**原 transcript
下标**。追加消息时 lastIndex 与显示下标同时 +1，键不变，于是只有新条目淡入。
串流气泡 `item(key = "stream")`、空状态 `item(key = "empty")` 也各给一个固定 key。

副产物：`‹ 2/2 ›` 曾"先出现在上一条气泡底下、生成完才挪回来"，也是同一个下标身份问题。
