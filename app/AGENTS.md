# AGENTS.md — `app/`

> ⚠️ 这个模块装着**全部界面**（33 个文件、约 8.1k 行，包 `app.tavern` 是扁平的）。
> 分层评估与「为什么不拆包」见 [structure-audit.md](../docs/dev/structure-audit.md)。
> **历史与细节不在这里**：踩过什么、数字怎么来的，全在 [pitfalls.md](../docs/dev/pitfalls.md)。
> 本文件只放每次都要遵守的规则。

**不写**：业务逻辑、数据访问、提示词拼装（在 `:core` / `:data`）。DI 手写对象图 `AppGraph`，
上 Hilt 先写 ADR；`Application.onCreate` 不放重活。

## 导航

0–2 层深：`Destination` 状态 + 自写 [PageStack.kt](../app/src/main/kotlin/app/tavern/PageStack.kt)，
返回键走 `BackHandler`。**不引 navigation-compose**。转场位移的 `1/4` 是用户验收过的量，别改。

改这块守住三条（踩过三次）：
1. **离场页拿"它还是当前页时"的状态快照**，否则返回时状态已前进、它渲染空 → 看着凭空消失；
2. **第一帧不许用上一轮的终态**（`running` 门：没在转场就只画原页、不做任何变换）；
3. **叠放按方向定**（进=新页在上，返回=深页在上整屏滑走），别再回 `AnimatedContent`
   （真要用，先读 pitfalls“AnimatedContent 必须 fillMaxSize” —— 病根是尺寸弹簧，不是曲线）。

**聊天列表必须 `reverseLayout = true` + `items(transcript.asReversed())`**（串流气泡在视觉最底部）。
别改成“正常排序 + scrollToItem 跟底”：每个 token 都会触发重排 → “整屏抽动”。

## 列表：一套行系统

- 行一律 `ListRow`，成组用 `ListGroup` / `GroupPiece`（**`ListRow.kt`**），别手写 `Row` 拼"差不多"的行。
  尺寸字号在同一份文件里：`RowToken` / `RowTitleStyle` / `RowSubtitleStyle` / `RowMetaStyle`（`tnum` 等宽数字）。
- **行不画背景与分隔线**（归 `GroupPiece`）；**边框只画组的外轮廓**，每片画一圈就是双线。
- 分隔线从文字左缘起（有头像 64dp / 无头像 12dp），**最后一行不画**。
- **按下即亮**：7% 主色、`indication = null` 关涟漪；**出现/消失不加动画**，只留 `placementSpec`。
- 会话时间用 `SessionTime.kt`；分组只有「今天 / 更早」。

## 角色卡

满幅 3:4 图 + 圆角 18、无描边；名字与元信息压在底部 **56dp** 磨砂条上（`CardBand`：
糊图 → `surface × 0.72` → 上沿 1px 亮线 → 文字）。**磨砂不能用 `GlassSurface`** —— 卡在录图层里，
会采到自己这一帧；用 `CardFrost`（预解 ~32px 小图）。副行不写角色描述，写「N 个会话 · 内嵌书 M 条」。
长按出抽屉；删除二次确认并写会话数；按压 8% 黑（图上盖紫会染色）。

## 动作单（长按抽屉）与确认框

- 走 `SheetController` + `LocalSheetController`：**页面只 `sheet.show(...)`**，由骨架
  （`TabsRoot` / `GlassDetailScaffold`）的 `GlassActionSheetHost` 渲染，两处都在**录图层之外**。
- **不许 `ModalBottomSheet`**（独立 Dialog 窗口，采不到内容层）；也不许在页面里直接 composed 弹层
  （会画进录图层）。
- 材质 `LiquidGlassSpec.Sheet`（faceWhite 0.50，单独实例）。**判断它只在彩色内容上看**（白底上
  0.22 与 0.50 同色）；日志 `tag=glass 液态档 … 着色器=`。
- 一级页：弹层起来前**底栏先滑走**（180ms）；收起时弹层先走、底栏 200ms 后回来。
- 行内「删除」只关弹层 + 弹确认框，**不直接删**；确认框统一 `ConfirmDeleteDialog`（红色确认钮，
  参数全写名字）。没有拖拽关闭。

## 玻璃

**只用 `GlassSurface`，别手写模糊。** 三条铁律（详见 pitfalls「玻璃模糊」/ ADR 0006）：

1. 录图层用 **`DrawScope.record` 扩展**（`layer.record(size) { drawContent() }`）；四参数成员版会把
   内容照旧画到主画布 —— 不崩不报错，图层是空的。录之前先 `drawRect(pageBackground)`。
2. **模糊/着色器挂节点上**（`renderEffect` 是渲染时读的，draw 块里"设了再清"等于没设）。
3. 糊谁就让节点**比被糊区域大一圈**（边缘 clamp 会出直纹）；`positionInWindow` 量在没有 padding 的层上。

液态玻璃是**所有玻璃表面的默认**：`LiquidGlassSpec.Default`，同一实例别重建，两个重载的默认值要一致。
横条用 `.HeaderBand` + `RectangleShape`（`refraction=0`、`rimBottomOnly`、`faceWhite=0.80`、
`shadowBottomOnly`）。**面颜色跟主题**（`surface`），写死白深色模式会发灰。轮廓靠 1.6dp 细线
（所有表面共用），不靠厚倒角；光照是**上下打光**不是单侧光。门槛 API 33，入口只有
`VisualEffects.effective()`；着色器失败 `runCatching` + `AppLog.e` + 退回描边。

主题色按钮：玻璃面 + **32% 主色**（叠加不是替换）；有色面**只勾一圈细线**（4 层叠会数出同心圈）；
细线纯白；投影**四周均匀 14dp/0.10 不下沉**；图标 `primary × 0.72`。

- 圆角必须从 `Shape` 读（`cornerRadiusOf`），别写死 `size.height / 2` —— 套到全宽横条就成大圆角。
- **不要加暗带/深色描边**（已删：暗带只缩圆头，与投影、按压形成两套弧线）。
- 玻璃钮按压：放大 1.25、**弹簧**（`Motion.PressDown/Up`）、吃 `interactionSource.interactions`
  流（`collectIsPressedAsState` 在同帧点按时一次都不播）、最短 90ms、**关涟漪**。

- 要在一页里摆**多个玻璃样张**（调参页那种）：把装饰底**单独**录一份图层当 `frost`，样张按窗口坐标采它 ——
  这样样张能放在普通滚动内容里，不必躲到录图层外面（`GalleryScreen` 就是这么做的）。

判据：胶囊里的字**还认得出 = 没糊**；底片纯色时看不出折射是对的。

## 页头与底栏

- 页头是 `PageHeader` **浮层**（与底栏同级，画在录图层**之外**），页面自己不画；内容用
  `contentPadding(top = PageHeaderHeight + statusBarTop() + …)` 让位。
- **只有聊天页是磨砂页头**（`frostedHeader = true`），其余是**纯线性色罩**（`HeaderScrimAlpha` 0.94）；
  **渐变不许加拐点**（会被看成阶梯）。聊天页磨砂页头下沿投影 ×0.5（`shadowScale`）。
- 顶部间距按"第一项是什么"算：会话页分组标题自带 16dp 不再加；卡 +8dp；聊天 +16dp；设置二级列表 +16dp。
- 状态栏高度统一 `statusBarTop()`（`PageHeader.kt`）。
- 胶囊里只 `fillMaxHeight`（`fillMaxSize` 会把标题挤成 0 宽）；不放实心按钮（用 `CapsuleIcon`）；
  没有副标题；一次性结果走 `Snackbar`（`UiState.notice`）。
- 底栏自绘（56dp）：选中只靠主色 + **一块滑动的 14% 底块**（每个 tab 别自己画底块）+ 点按 6% tint；
  **不开涟漪**。
- 颜色/间距/圆角只从 `Theme.kt` / `Style.kt` 取；多选项用 `SegmentedRow`。
- 设置分层：一级只放“去哪儿”（`SettingLinkRow` + 当前值），控件放二级页 `SettingsSubPage`。
- 主题持久化存**名字**，重排 `ThemePreset` 枚举不影响已保存的选择。

## 预设与拖动排序（P1-5）

- 提示词顺序是**数据**（`prompt_order`），拼提示词时按它遍历，别在 `PromptBuilder` 里再写死顺序。
- 拖动换位（`PromptOrderEditor`）：**长按整行起拖**（56dp 行里放拖拽手柄太窄，手指抓不住）；
  位移走 `graphicsLayer.translationY`，目标位次按「起点 + 累计位移 / 行高」算（不逐帧累加，
  否则会漂），换位后把「换过去的行数」从位移里扣掉；贴边自动滚用 `dispatchRawDelta`（不起协程）；
  **拖动只改内存，松手才落盘**。
- 内置默认预设**不许改顺序**：没有文件可回写，改了会让人以为永久生效。
- 顺序覆盖存 SharedPreferences，**不改预设文件本身**（预设要能原样导回 PC 版）。

## 二级/三级页与 insets

- 二三级页统一 `GlassDetailScaffold`（圆形玻璃返回钮 + 大标题 + 右侧圆钮），不要 `Scaffold` + `TopAppBar`；
  **每页自己录一份内容图层**。内容要滚到页头下面（聊天）就传 `contentTopInset = 0.dp` + 列表
  `contentPadding` 让位；输入区用 `bottomOverlay` 槽（画在录制之外，才采得到磨砂）。
- `MainActivity` 必须 `enableEdgeToEdge()`，Manifest 钉死 `windowSoftInputMode="adjustResize"` ——
  缺一就是“点输入框时先抽一下再落回”（系统缩放与 `imePadding()` 各走一套）。

## 文案与图标

- **界面不写解释性文案**：只留字段标签、状态值、空状态、删除确认、隐私声明。说明写进 `docs/`。
- 图标只从 `material-icons-core` 取；不确定就用文字/Canvas 画（`-extended` 多 10MB，不值得）。
  `Icons.Filled.X` 是扩展属性：必须 `import …filled.X`，**不能全限定**。
- `PrimaryAction` / `SecondaryAction` 最后一个是 `enabled`，**必须具名**传。

## 动效与手势

- 进入曲线 `Motion.Decelerate`；**可被打断的用弹簧不用 tween**；归位类阻尼必须 1.0。
- 减动效统一 `rememberReduceMotion()`，别各自读系统设置。
- 内容底部让位 `max(导航栏, 键盘)`，不相加；键盘用 `imePadding()`。
- 橡皮筋 `Modifier.rubberOverscroll(...)` 必须在 `verticalScroll`/`LazyColumn` **之前**。四条硬约束
  （细节见 pitfalls「键盘与橡皮筋」）：`raw` 有上限；fling 撞上限后记 `flingEaten` 只吃不累积；
  `onPostScroll` 把 available 全报成已消费；回弹必须能被手指打断（代数守卫）。

## 日志与自查

走 `AppLog`（真机没有 logcat），只记事件与状态码，不记 Key 与正文；导航也打 `tag=nav`。
本机编不了 `:app` → 改完必跑 `tools/check-kotlin-comments.sh`、`tools/check-kotlin-imports.sh`、
`tools/check-core-purity.sh`；出包走 CI。
