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
  右边**圆形玻璃按钮**装图标动作（单动作时是正圆，2026-11-23 从胶囊改过来）；
  页头底下默认是**半透明色罩**（`frostedHeader = false`），只有聊天页传 `true` 变成磨砂条；
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
- **发送钮**是第一个试点，参数见下（[ADR 0007](../docs/adr/0007-liquid-glass-agsl.md)）：
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

## 窗口 insets 的规矩（踩过两次）

- `MainActivity` 必须调 `enableEdgeToEdge()`，Manifest 里钉死 `windowSoftInputMode="adjustResize"`。
  **两者缺一就会出现"点输入框时画面先抽一下再落回"**——系统缩放窗口和 Compose 的 `imePadding()`
  动画各走一套，位移叠了两次。
- 页面用 `Scaffold`（含 `TopAppBar`）拿状态栏/导航栏留白；聊天页不许再用裸 `Column` 当根。
- 键盘遮挡用 `Modifier.imePadding()`，别用 `adjustPan`；`values-night/themes.xml` 要跟着维护窗口底色。
