# AGENTS.md — `app/`

> ⚠️ 这个模块装着**全部界面**（35 个文件、约 8.9k 行，包 `app.tavern` 是扁平的）。
> 分层评估与「为什么不拆包」见 [structure-audit.md](../docs/dev/structure-audit.md)。
> **历史与细节不在这里**：踩过什么、数字怎么来的，全在 [pitfalls.md](../docs/dev/pitfalls.md)。
> 本文件只放每次都要遵守的规则。

**不写**：业务逻辑、数据访问、提示词拼装（在 `:core` / `:data`）。DI 手写对象图 `AppGraph`，
上 Hilt 先写 ADR；`Application.onCreate` 不放重活。

## 导航

0–2 层深：`Destination` 状态 + 自写 [PageStack.kt](../app/src/main/kotlin/app/tavern/PageStack.kt)，
返回键走 `BackHandler`。**不引 navigation-compose**。转场位移的 `1/4` 是用户验收过的量，别改。

改这块守住五条（前三条踩过三次，后两条 2026-11-24 加的）：
1. **离场页拿"它还是当前页时"的状态快照**，否则返回时状态已前进、它渲染空 → 看着凭空消失；
2. **第一帧不许用上一轮的终态**（`running` 门：没在转场就只画原页、不做任何变换）；
3. **叠放按方向定**（进=新页在上，返回=深页在上整屏滑走），别再回 `AnimatedContent`
   （真要用，先读 pitfalls“AnimatedContent 必须 fillMaxSize” —— 病根是尺寸弹簧，不是曲线）。
4. **动画开始前先预热新页**（`PageStack` 的 `warming`）：新页入栈后在起跑位/全透明下过完
   **它自己的第一帧**，再起动画。新页首帧（组合+测量+录图层）本身就是 30–80ms，
   直接落在动画里就是"丢几帧"。代价是响应多约 1 帧 —— 别为了"点下去立刻动"去掉预热。
5. **新页首帧要便宜**：长文本别等到数据到齐才第一次排版（能拿列表里已有的字段先顶上就先顶），
   行数由数据决定的列表一律 `LazyColumn`（详情页会话 tab 就这么踩过）。

**聊天列表必须 `reverseLayout = true` + `items(transcript.asReversed())`**（串流气泡在视觉最底部）。
别改成“正常排序 + scrollToItem 跟底”：每个 token 都会触发重排 → “整屏抽动”。

## 列表：一套行系统

- 行一律 `ListRow`，成组用 `ListGroup` / `GroupPiece`（**`ListRow.kt`**），别手写 `Row` 拼"差不多"的行。
  尺寸字号在同一份文件里：`RowToken` / `RowTitleStyle` / `RowSubtitleStyle` / `RowMetaStyle`（`tnum` 等宽数字）。
- **行不画背景与分隔线**（归 `GroupPiece`）；**边框只画组的外轮廓**，每片画一圈就是双线。
- 分隔线从文字左缘起（有头像 64dp / 无头像 12dp），**最后一行不画**。
- **按下即亮**：7% 主色、`indication = null` 关涟漪；**出现/消失不加动画**，只留 `placementSpec`。
- 会话时间用 `SessionTime.kt`；分组只有「今天 / 更早」。
- 世界书条目（全局书详情页 + 卡内嵌书）**只有一份行**：`WorldBookEntryRow`（两行正文预览 + 行尾开关），
  两页别再各写一套。开关是**三态**（跟随文件 / 强关 / 强制开启），界面上仍是两态：
  文件里关着的条目可以"强制开启"，那一种整行 6% 红底 + 红色开关，**行里不写任何状态文案**
  （会被长正文挤掉，也会把行撑宽；用户 2026-11-24）。含义由列表顶部 `WorldBookForcedHint`
  一行小字说明，且**只在真有强制条目时出现**。页面级批量动作放右上 `WorldBookBulkMenu`
  （全部开启 / 全部关闭 / 恢复默认），别在页面里再挂按钮。

## 角色卡

满幅 3:4 图 + 圆角 18、无描边；名字与元信息压在底部 **56dp** 磨砂条上（`CardBand`：
糊图 → `surface × 0.72` → 上沿 1px 亮线 → 文字）。**磨砂不能用 `GlassSurface`** —— 卡在录图层里，
会采到自己这一帧；用 `CardFrost`（预解 ~32px 小图）。副行不写角色描述，写「N 个会话 · 内嵌书 M 条」。
长按出抽屉；删除二次确认并写会话数；按压 8% 黑（图上盖紫会染色）。

**卡编辑保存是"新建 / 覆盖 / 取消"三选**（**和删除确认同一个玻璃框**，不是抽屉）：覆盖先备份 `.bak.png`，新建以原卡图
写一张新卡（`CardRepository.saveCardAs`，名字冲突自动 `-2`）。**`.bak.png` 不算卡**：卡目录扫描
只认 `.png` 且排除 `.bak.png`/`.tmp`（2026-11-25 真机：每次保存都多出一张同名卡）。删卡连带删备份。
保存用的原始 JSON 是**详情页那张卡**的（`detailRaw` + id 校验），绝不能用会话那张（串卡 = 世界书串味）。

**详情页卡头是"海报版式"**（260dp：极小块磨砂拉满当底 + 完整竖图 `ContentScale.Fit` +
底部渐变 + 名字/元信息，`CardHero`），保留 TabRow。卡基本是竖图，横条 + `Crop` 会截掉一大截
（2026-11-25 用户报的就是这个）；别再退回 96dp 小头像那一版，也别把 `Crop` 加回来。

**开场白切换**（`GreetingSwitch.kt`）：聊天页把 `‹ N/M ›` 贴在**开场白气泡下沿右侧**
（反排列表锚点在底部，贴上沿换条时控件会跟着跳）、详情页开场白 tab 用同款箭头翻页；
两处都不放页头按钮。聊天页只在"这条会话还没有用户消息 + 卡不止一条开场白"时出现
（用户 2026-11-25 定的 B 策略）；换一条 = 改写会话第一条消息。详情页"设为默认"写卡级
`greetingIndex`；**默认那条也占一个同尺寸的灰字胶囊「当前默认」**，翻页时高度不变（否则按钮
一出现就把正文往下压）。

## 动作单（长按抽屉）与确认框

- 走 `SheetController` + `LocalSheetController`：**页面只 `sheet.show(...)`**，由骨架
  （`TabsRoot` / `GlassDetailScaffold`）的 `GlassActionSheetHost` 渲染，两处都在**录图层之外**。
- **不许 `ModalBottomSheet`**（独立 Dialog 窗口，采不到内容层）；也不许在页面里直接 composed 弹层
  （会画进录图层）。
- 材质 `LiquidGlassSpec.Sheet`（faceWhite 0.50，单独实例）。**判断它只在彩色内容上看**（白底上
  0.22 与 0.50 同色）；日志 `tag=glass 液态档 … 着色器=`。
- 一级页：弹层起来前**底栏先滑走**（180ms）；收起时弹层先走、底栏 200ms 后回来。
- 行内「删除」只关弹层 + 弹确认框，**不直接删**；确认框统一 `ConfirmDeleteDialog`（红色确认钮，
  参数全写名字）。
- **确认框不是 `AlertDialog`**：它走 `ConfirmController` + 骨架里的 `GlassConfirmHost`（液态玻璃）。
  调用点签名保持不变，但页面骨架必须提供控制器 —— 二级页都在 `GlassDetailScaffold` 里，别新开一个
  不经过骨架的页面（那样确认框会静默不弹）。
- **动作单的把手可拖**（组件已支持，位移按"**弹层自身高度 1/3**"收回，**不看速度**；1/4 太容易
  误关、1/2 要拖太远，都是用户 2026-11-25 试出来的）。别在页面里自己写拖拽；弹层内容超过
  62% 屏高时会在弹层内滚。
- **页面自己要在骨架之前发请求时，控制器必须由页面创建再传进骨架**
  （`GlassDetailScaffold(sheetController = …, confirmController = …)`）。
  `LocalSheetController` / `LocalConfirmController` 是骨架**内部**提供的：页面在调用骨架之前读
  `.current` 拿到的是"没有宿主"的默认实例，`show()` / `ask()` 静默落空 —— 2026-11-25 真机上一整批
  "点不进去、长按没反应、确认框不弹"都是这一条。没有宿主的请求会打 `ui …请求没有宿主` 日志。

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

聊天页两个圆钮成对：**发送 20% 主色**（主操作）、**模型 12% 主色 + 满色主色字**（次操作；原来是透明的，
用户 2026-11-25 第 5 条）；流式时发送钮变**淡红 20% + 红色方块**（停止，第 6 条）。
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
- **页头材质只有两个值**：`headerMode = DetailHeaderMode.Frosted`（磨砂带，只有聊天页）/
  `DetailHeaderMode.Translucent`（纯线性色罩 `HeaderScrimAlpha` 0.94，其余二级页）。**必须显式传**。
- **首项让位只有一个入口**：骨架交出的 `padding.calculateTopPadding()` 已经等于
  `状态栏 + DetailHeaderHeight + contentSpacing`；列表页传 `contentSpacing = Space.l`（首项离页头底边 16dp），
  Column 页不传。页面**只写 `padding.calculateTopPadding()`，不许再接 `+`**（`tools/check-ui-invariants.sh` 的
  INV-01 查这条，`df71772` 多 12dp / `6f4dd53` 少 64dp 都是页面自己加出来的）。
- **半透明页头的观感来自"内容在它下面滚"**（用户 2026-11-25 第 7 条）：让位的 top 要**用在滚动容器里面**
  （`.verticalScroll(...).padding(top = padding.calculateTopPadding())` 或列表 `contentPadding`），
  页头盒子浮在上面，滚上去的行从它底下穿过。唯一例外：角色详情页（卡头是常驻海报）把 `padding` 整份用在外面。
  **渐变不许加拐点**（会被看成阶梯）。聊天页磨砂页头下沿投影 ×0.5（`shadowScale`）。
- 顶部间距按"第一项是什么"算：会话页分组标题自带 16dp 不再加；卡 +8dp；聊天 +16dp；设置二级列表 +16dp。
- 状态栏高度统一 `statusBarTop()`（`PageHeader.kt`）。
- 胶囊里只 `fillMaxHeight`（`fillMaxSize` 会把标题挤成 0 宽）；不放实心按钮（用 `CapsuleIcon`）；
  没有副标题；一次性结果走 `Snackbar`（`UiState.notice`）。
- **页头已经写着页面名，列表开头不再放同名小标题**（"预设"/"全局世界书 N 本"这类）；
  页面级动作（导入 / 新建）放右上角 `CapsuleIcon`，底部不挂通栏按钮 ——
  列表就是页面本身，底部按钮会把页面再切一刀。空状态文案要跟着改（"点右上「+」"）。
- **列表类二级页的页头是半透明的，列表从它底下穿过**（世界书条目两页、预设编辑、聊天）——
  这是设计，**别当"页头透明"的 bug 去夹死它**（2026-11-24 误修过一次，见 pitfalls）。
  判据是"这页会不会滚"：会滚 → top 让位做在 `verticalScroll` **里面**（用骨架给的
  `padding.calculateTopPadding()`）；空状态那种不滚的盒子 → 整份 `padding` 用在外面。
- 底栏自绘（56dp）：选中只靠主色 + **一块滑动的 14% 底块**（每个 tab 别自己画底块）+ 点按 6% tint；
  **不开涟漪**。
- 颜色/间距/圆角只从 `Theme.kt` / `Style.kt` 取；多选项用 `SegmentedRow`。
- 设置分层：一级只放“去哪儿”（`SettingLinkRow` + 当前值），控件放二级页 `SettingsSubPage`。
- 主题持久化存**名字**，重排 `ThemePreset` 枚举不影响已保存的选择。

## 三级配置（会话 > 角色卡 > 全局）

- 数据是 `data/config/ConfigOverride`：每项 `null` = **跟随上级**，空集 ≠ null（"全都不要"是合法选择）。
  会话级存 `session:<id>`、卡级存 `card:<id>`（SharedPreferences），不动 DB。
- **生效值只能从 `UiState.effective*` 这几个 getter 读**（profile / reasoning / preset /
  worldBookNames / regexIds / regexScripts）。发送、世界书扫描、正则（输入与显示）、连接参数全读它们；
  别再在各处写 `x ?: y ?: z` —— 漏一层就是"显示 A、实际发 B"。
- 界面：会话级在 `SessionConfigScreen`、卡级在详情页「配置」tab；四行点进去都是**整页选择**
  （`ConfigPickerScreen` + `Destination.ConfigPicker`，观感照设置二级页），**不再弹底部抽屉**。
  页面里单选/多选都点在原地生效、不自动返回，当前项用 `ActiveMark` 勾出。
  **「跟随上级」那一行常驻**（不随"这一层有没有覆盖"增删）：它一出现/消失就会把整列挪一格，
  正好点在那一行的人会点到隔壁；跟随态就勾在它上面，具体项不再重复勾。
- 唯一的抽屉是**聊天页左下角的快速换模型**（`showModelSheet`）：顶部思考强度滑块 + 与设置页
  "模型"同款的行（勾选圈）；**选中后不收起抽屉**（用户 2026-11-25 第 3 条）。
- 思考强度**只有两级**：会话 / 角色卡，都放在各自的「模型」页顶部（滑块，含「自定义」一格）。
  档案（全局）层不再有这一项（用户 2026-11-25 第 2 条）：`null` = 不发 `reasoning_effort`、`"off"` = 明确关。
  自定义值存**具体字符串**（默认写 `max`），没有哨兵值 —— 判断靠"不在预设集合里"。
- 新增一个可配置项时，三层都要接：数据字段、`ConfigKind` 新分支、两级页面各一行。

## 消息显示格式（P1-12）

- **只影响气泡显示**：存档、导出、`查看请求`、发给模型的提示词全部保持原文。
- 两组**只读内置正则**（`core/regex` 的 `BuiltinRegex`，设置→正则「内置」开关，默认开）：
  「清理包裹标签」（白名单 `content/output/response/message/text/answer`）和
  「动作描写强调」（`**粗**` / `*斜*` / `_斜_`）。关掉 = 不跑这条 = 原文。
- 执行顺序：**用户脚本 → 清标签 → 强调**；内置是全局开关，不受会话/卡级 `regexIds` 过滤
  （老会话存的 id 集合里没有它们，混在一起会被误关）。
- 正则把强调替换成 `<i>` / `<b>`，再由 `InlineMarkup` 解析成 `AnnotatedString`；
  **只认** `<i> <em> <b> <strong> <br>`，其他标签原样显示 —— 不做 HTML/WebView，也不做
  列表/标题那套完整 Markdown。
- 强调语义有夹具：`fixtures/expected/P1-12-inline-markup.json`（原版 showdown +
  showdown-underscore 的渲染结果），`InlineMarkupFixtureTest` 比斜体/粗体/换行的位置。

## 预设与拖动排序（P1-5）

- 提示词顺序是**数据**（`prompt_order`），拼提示词时按它遍历，别在 `PromptBuilder` 里再写死顺序。
- 顺序的开关与拖动在**编辑页**（`PresetEditScreen`，长按预设 → 编辑），列表页只负责选。
- **顺序列表每一行都是四角全圆的胶囊**（`Radius.group`），行与行贴合、**行本身不画描边**：
  每行各画一圈描边在缝隙处就是 2px 双线；而片型按位置算（首尾圆角/中间直角）会让"落下时形状变一下"。
  固定成同一种形状 + 衔接处只画一条 1px 线（两端各让出圆角半径，避开圆弧）→ 两个毛病都没有。
- **拖动只改投影（0→10dp）与缩放（1→1.03，`Motion.Fast`）+ `zIndex`**，形状全程不变；
  拖动中把这一行下方的分隔线藏起来（否则那条线会跟着胶囊被拎走）。抓握纹在最左边，开关在右。
- 编辑页的列表**就是页面本身**：`contentSpacing = Space.l` + `contentPadding` 让位（和聊天页同一套路），
  **不要把 `LazyColumn` 塞进 `heightIn(max = …)` 的固定框里** —— 那看着像开了个小窗口，
  滚动也会变成"外层不动、内层在滚"的两层。
- **页面级操作放页头右上角的圆钮 + `DropdownMenu`**（`GlassDetailScaffold(actions = …)` + `CapsuleIcon`），
  底部不再挂按钮 —— 列表既然是整页，底部按钮会把"整页"又切一刀。
- 拖动换位用 **`sh.calvin.reorderable`**（`PresetOrderEditor`），别手写：
  长压起拖用 `Modifier.longPressDraggableHandle()`（整行可拖，不做拖拽手柄 —— 56dp 行里手柄太窄）；
  `onMove` 里**同步**改完列表（不 `launch`，否则被拎起来那行会闪一帧）；**松手才落盘**。
  版本升级前先看它 POM 里声明的 Compose 版本（见 [ADR 0008](../docs/adr/0008-reorderable-library.md)）。
- 内置默认预设**不许改顺序**：没有文件可回写，改了会让人以为永久生效。
- 顺序覆盖存 SharedPreferences，**不改预设文件本身**（预设要能原样导回 PC 版）。

## 二级/三级页与 insets

- 二三级页统一 `GlassDetailScaffold`（圆形玻璃返回钮 + 大标题 + 右侧圆钮），不要 `Scaffold` + `TopAppBar`；
  **每页自己录一份内容图层**。`headerMode` 必填；内容要滚到页头下面，把 `padding.calculateTopPadding()`
  用在滚动容器里面（列表走 `contentPadding`）；输入区用 `bottomOverlay` 槽（画在录制之外，才采得到磨砂）。
- `MainActivity` 必须 `enableEdgeToEdge()`，Manifest 钉死 `windowSoftInputMode="adjustResize"` ——
  缺一就是“点输入框时先抽一下再落回”（系统缩放与 `imePadding()` 各走一套）。

## 文案与图标

- **界面不写解释性文案**：只留字段标签、状态值、空状态、删除确认、隐私声明。说明写进 `docs/`。
  唯一例外是**颜色/状态的图例**：世界书列表顶部的 `WorldBookForcedHint`（红色=强制开启），
  因为那条红不改口就没人知道是什么意思。
- 图标只从 `material-icons-core` 取；不确定就用文字/Canvas 画（`-extended` 多 10MB，不值得）。
  `Icons.Filled.X` 是扩展属性：必须 `import …filled.X`，**不能全限定**。
- `PrimaryAction` / `SecondaryAction` 最后一个是 `enabled`，**必须具名**传。

## 动效与手势

- 进入曲线 `Motion.Decelerate`；**可被打断的用弹簧不用 tween**；归位类阻尼必须 1.0。
- 减动效统一 `rememberReduceMotion()`，别各自读系统设置。
- 内容底部让位 `max(导航栏, 键盘)`，不相加；键盘用 `imePadding()`。
- **聊天输入区高度是变量**（收起一行 / 聚焦翻倍 / 最多 5 行）：列表底部让位必须读**实测高度**
  （`ChatInputBar` 的 `onHeightChanged` → LazyColumn `contentPadding`），不许写常量。
  聚焦展开要有**形变**（高度用 `animateDpAsState` 的空 Spacer 撑；`Motion.InputExpand/InputCollapse`
  = 400/300ms），但只允许这种一次性过渡；拖拽/连续改尺寸仍然禁止。
- **展开条件是「有焦点 且（键盘在 或 已有字）」**：只按 focus 判断的话，用户按返回键收键盘后
  输入框会一直停在两行（2026-11-25 第 9 条）。收起的胶囊再点要 `keyboard.show()` 把键盘叫回来；
  列表底部让位在输入区实测高度之外再 +12dp（胶囊投影会压到图标）。
- **输入区做高度动画时，按钮必须是浮层**（`Box` + `align(BottomEnd/BottomStart)`）：
  高度动画的容器会逐帧裁内容，按钮放进去就会在动画期间被切掉一截（2026-11-25 用户报的）。
  动画只作用在一个空 `Spacer` 的高度上；文字行的右侧让位按展开状态做 `animateDpAsState`。
- **离场页要收掉"浮在窗口上的东西"**：`PageStack` 提供 `LocalPageIsCurrent`（**动态**
  `compositionLocalOf`，离场页 = false）。输入焦点与选文字手柄是**独立弹层**，不跟
  `graphicsLayer` 位移 —— 聊天页在离场那一帧 `clearFocus(force = true)`，并让消息列表
  `key(epoch)` 整体重建（`SelectionContainer` 随旧组合销毁 → 手柄/工具栏立刻消失）。
  ⚠️ 别用"给每个气泡传 selectable=false"：静态 local 的读者不会失效，页面会卡在 false，
  表现为"最新气泡不能长按选字、滑一下才好"（2026-11-25 踩过）。
- **选字不能拖动列表**：`SelectionContainer` 拿焦点会触发 `bringIntoView`，超高气泡会让默认算法把
  列表挪一段（`trailingEdge - containerSize`）。聊天列表用 `LocalBringIntoViewSpec`
  （`NoFocusScrollSpec`，恒返回 0）关掉焦点滚动 —— 阅读位置只由手指滚动 / 串流跟随 / 撤回回底决定。
- **消息底板是 `MessagePanel`（背景 + 描边），不是 Material3 `Surface`**：`Surface` 的 `clip(shape)`
  会让超高气泡的子树在约 8k px 之后收不到触摸（选字完全无反应，画面却正常）。
- **消息操作是气泡底下的图标行，不再长按出抽屉**（用户 2026-11-25 第 11 条）：长按 = 选文字
  （正文包 `SelectionContainer`）。**只放图标不写文字**（第 6 条）：助手＝复制/[重新生成]/
  [继续]/删除，用户＝撤回/复制/删除；按钮 32dp、图标 18dp。**没有「编辑」**（撤回就能改完重发）。
  core 的图标集里没有复制/撤回，
  用 Canvas 画字形（别为两个图标引 10MB 的 `-extended`）。
  「撤回」= `rewindToMessage`：删掉**这条用户消息 + 之后双方全部消息**，删完以库里剩下的为准
  重建 transcript，正文放回输入框（类似 rewind，不是删一条）。
  **开场白气泡底下不挂这排按钮**（它不是一条回复），判据与"有几条开场白"无关
  （`isGreetingMessage` = 会话第一条 + 还没人说过话 + 内容对得上；翻页条另用 `canSwitchGreeting`）。
- **发送 / 重新生成 / 继续共用 `beginCompletion(history, userText, replacing, continuation)`**：
  `userText = null` 表示不追加用户消息。别复制一份发送逻辑（世界书、正则、思考强度会分叉）。
- 橡皮筋 `Modifier.rubberOverscroll(...)` **每个可滚动面都要挂**（会话/角色卡/聊天/世界书/设置一二级/
  预设编辑…）—— 新加一个可滚动页面时最容易漏的就是这条；顺序必须在 `verticalScroll`/`LazyColumn` **之前**。四条硬约束
  （细节见 pitfalls「键盘与橡皮筋」）：`raw` 有上限；fling 撞上限后记 `flingEaten` 只吃不累积；
  `onPostScroll` 把 available 全报成已消费；回弹必须能被手指打断（代数守卫）。
  **拉伸的位移必须裁在自己的槽位里**（`rubberOverscroll` 最外层 `clipToBounds`）：那笔位移是
  绘制量，不裁就会画到邻居身上（2026-11-25 详情页 Tab 栏被内容盖住就是这么来的）。

**聊天串流跟随**（`ChatScreen`，2026-11-24 定；当晚撤掉"冻结"换成阅读锚点补偿）：
- 贴底（`reverseLayout` 的 `firstVisibleItemIndex==0 && offset==0`）才跟随；
- 内容长高用**一条动画追高度差**（`实测高度 − 平滑高度` 平移上去），**不要**"加一笔补偿再收回"
  —— 文字到得快时那样会累积、抽搐（v112 踩过）；
- **不在底部时不许冻结文本**（用户 2026-11-24 晚明确不要"内容卡住"）：看更早的消息（下标 > 0）
  视口本来就稳，什么都不做；停在串流气泡自己身上（下标 == 0、偏移 > 0）时，把**滚动量**
  补上这次长高的量，让读到的那一段留在原处；
- 补偿只认**量到的高度变化**（`onSizeChanged` 的差量），不许按 token/按帧无条件调滚动；
- **补偿用 `dispatchRawDelta`，不许用 `requestScrollToItem`**（2026-11-24 真机实测）：
  后者内部会 `itemAnimator.reset()`，挂 `animateItem` 的旧气泡每补一次就重播淡入 → 一直闪；
  它还会取消进行中的拖动 → 只能"手势期间不补"，可那段长高照样顶人 → 拖动时不跟手。
- **手势期间照补**：补偿只抵掉"文字长高顶上去的那一笔"，手指的位移照常生效，不跟手指抢。
- 布局与滚动是两趟：补偿那一帧用 `translationY` 顶住差量，等补偿落地时两边同时归零。
  这是"桥接一帧"，不是用绘制位移长期掩盖布局 —— 别把 `translationY` 做成常驻偏移。

## 沟通与词表

界面上的东西**一律用 [docs/ui-map.md](../docs/ui-map.md) 的名字**（页面树 / 区域 / 材质 / 共用组件）。
用户的口头描述对不上那份词表时**先确认，不许猜**：列出 1–3 个候选让用户点，涉及改动的必须等确认；
用户说的新叫法当场记进词表的"别名"。改页面或共用组件时，同一提交里更新词表并跑
`tools/check-ui-map.sh`（CI 也在跑）。**不许在回执里临时造名字** —— 要新词先加表。

真机上坏过、必须守住的结构性约束（超高气泡命中、选字拖列表、弹层只能走宿主、inset 并集）
登记在 [docs/ui-invariants.md](../docs/ui-invariants.md)，能自动查的由
`tools/check-ui-invariants.sh` 在 CI 里守；改这些地方前先读那张表。

## 日志与自查

走 `AppLog`（真机没有 logcat），只记事件与状态码，不记 Key 与正文；导航也打 `tag=nav`，
聊天滚动/锚点补偿打 `tag=scroll`（只记模式切换、补偿落地、补偿失败，并按时间窗合并）。
本机编不了 `:app` → 改完必跑 `tools/check-kotlin-comments.sh`、`tools/check-kotlin-imports.sh`、
`tools/check-core-purity.sh`；出包走 CI。
