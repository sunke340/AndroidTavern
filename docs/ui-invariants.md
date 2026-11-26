# UI 不变量规格（`docs/ui-invariants.md`）

**这份文件解决一个问题**：有些东西在真机上坏过一次、还坏过第二次（页头材质/间距、超高气泡按不到
正文、选字拖动列表）。它们不是"某个页面写错了"，而是**同一个决定散在很多地方**。
这份表把"必须成立什么"写成可判定的一条条，脚本管得住的进 CI，脚本管不住的进真机清单。

**分工**：`docs/ui-map.md` 说"什么是什么"（名词，沟通用）；本文件说"必须成立什么"（协议，防回归）。
两份都不描述"页面长什么样"——那会在一个星期内过期。

---

## 一、判据分三类

| 类型 | 含义 | 强度 | 成本 |
|---|---|---|---|
| **A 结构级** | 把散在各处的决定收成**唯一入口**，让改错"做不到" | 最强 | 半天量级 |
| **B 文本级** | grep / 函数区间能查的近似判据 | 能挡 80% 回归 | 10–30 分钟/条 |
| **C 真机** | 观感与手感，写进 [verification/](dev/verification/) 里当期那份 `YYYY-MM-DD-Mxx.md` 的编号条目（一批一个文件，2026-11-26 起） | 只能人工 | 用户点一次 |

## 二、验收规则（不满足就不许进表）

1. 必须写明**唯一入口**（代码在哪）或**脚本判据**（`tools/check-ui-invariants.sh` 的第几条）；
2. 脚本判定类必须**自测过会红**（故意改坏一次，看到报错再改回来）；
3. 观感类必须有一句可执行的**真机判据**（"看什么 = 判定成立"），并进 `verification/<日期>-<Mxx>.md` 编号；
4. 破坏记录要写清是哪次提交（下次有人问"为什么会这样"能直接查到）。

## 三、明确不做

- 截图 / 像素对比：本机无 KVM、无模拟器，跑不了；
- 逐页布局快照；
- 颜色数值断言：主题会变，断言了就是负担。

---

## 四、不变量表

状态：✅ 已由脚本判定 · 🟡 部分可实现 · ⬜ 未做

| ID | 不变量（必须成立什么） | 唯一入口 | 判据 | 状态 | 破坏记录 |
|---|---|---|---|---|---|
| INV-01 | 二级页页头的**材质 + 首项让位**只能由骨架决定，页面层**没有 API 可写** | `GlassDetailScaffold` 的 `DetailHeaderMode { Frosted, Translucent }` + `contentSpacing`（见第五节的"第一批"） | 脚本（白名单只有 `DetailPage.kt`）：页面文件不得出现 `contentTopInset` / `frostedHeader` / `DetailScrollUnderTop` / `DetailHeaderHeight` / `statusBarTop(` / `calculateTopPadding() +`；每处 `GlassDetailScaffold(` 必须显式传 `headerMode =`。真机：按 [device-verification.md](dev/device-verification.md)「M9 / M9.1 回执」A 组复验 | ✅ | `8ee1b72`（磨砂带→色罩）、`df71772`（首项多出 12dp）、`6f4dd53`（世界书列表少了 64dp；同提交把卡配置三页的分发写死成常量，另两页内容被顶掉 → M10） |
| INV-02 | 聊天页气泡底板必须用 `MessagePanel`，**不许**用 Material3 `Surface` | `ChatScreen.MessagePanel` | 脚本：`ChatScreen.kt` 里裸 `Surface(` 只允许出现在 `RequestDialog` 内（注释先剔除） | ✅ | `1952a2b`：Surface 的 `clip(shape)` 让 18900px 高的气泡在约 8k px 之后收不到触摸 → 长按完全没反应 |
| INV-03 | 选中文字**不许**拖动列表 | `ChatScreen` 的 `NoFocusScrollSpec` + `LocalBringIntoViewSpec` | 脚本：`ChatScreen.kt` 必须出现 `LocalBringIntoViewSpec provides NoFocusScrollSpec`；真机：反复长按，`偏移=` 不变 | ✅ | `f58ad0c`（修 `8ee1b72` 之后暴露的焦点滚动，跳动量 = `trailingEdge - containerSize`） |
| INV-04 | 玻璃三铁律：只有 `Glass.kt` 能碰模糊/折射；录图层只用 `record(size)` 扩展；`renderEffect` 不许在绘制时"设了再清" | `GlassSurface`（`Glass.kt`） | 脚本：除 `Glass.kt` 外不得出现 `.blur(` / `haze`；全仓不得出现 `renderEffect = null`；`.record(` 的参数里不得有 `density` / `layoutDirection` 或 ≥3 个顶层参数 | ✅ | ADR 0005/0006：Haze 在 vivo sdk37 只给半透明；四参数 `record` 画出空图层 |
| INV-05 | 弹层只能走宿主：不许 `ModalBottomSheet`，页面不许自己 composed 弹层 | `SheetController` + `GlassSheetScaffold`（`ActionSheet.kt`、`MainScreen.kt`） | 脚本：全 app 不得出现 `ModalBottomSheet`；`GlassSheetScaffold(` 只许出现在这两个文件 | ✅ | `a4888f3`：`ModalBottomSheet` 是独立窗口，采不到内容层 |
| INV-06 | 每个可滚动面都必须挂 `rubberOverscroll` | 各页 `Modifier.rubberOverscroll(...)`（在 `verticalScroll`/`LazyColumn` **之前**） | 脚本：出现 `verticalScroll(` / `LazyColumn(` 的文件必须同时出现 `rubberOverscroll`；白名单只有 `ActionSheet.kt`（弹层宿主：内容区高度限在 62% 屏高、把手拖拽在另一节点上；要挂先真机验手势不打架） | ✅ | 2026-11-23 多次"某页拉到头没有回弹"；2026-11-25 补判据时又抓到 3 处真漏（档案编辑、组件预览、`查看请求` 正文） |
| INV-07 | 内容底部让位取**并集** `max(导航栏, 键盘)`，不许相加 | `GlassDetailScaffold` 的 `maxOf(navBottom, imeBottom)` | 脚本：不得出现 `navBottom + imeBottom` / `imeBottom + navBottom` | ✅ | 键盘弹出时多抬一个导航栏高度 |
| INV-08 | 颜色只从 `Theme.kt` / `Style.kt` 取，页面里不许出现字面色值 | `TavernTheme` / `LocalTavernPalette` | 脚本：除白名单外不得出现 `Color(0x` / `0xFF…`；白名单 = `Theme.kt`（调色板本身）、`GalleryScreen.kt`（组件预览的色带底图，是演示内容） | ✅ | 深色模式下发灰（写死白踩过） |
| INV-09 | 显示格式**只影响显示**；内置正则每条规则都有单测 | `core/regex/BuiltinRegex`、`InlineMarkup` | 已有：`tools/test-core.sh`（19 条）+ fixture `P1-12-inline-markup` | ✅ | 清标签规则漏了 `</>`（2026-11-25，已补单测） |
| INV-11 | 层级浮层（胶囊菜单）只能由骨架画，页面不许再回 `DropdownMenu` | `GlassCapsuleMenuHost` / `CapsuleMenuController` | 脚本：`GlassCapsuleMenuHost(` 只许出现在 `CapsuleMenu.kt` / `MainScreen.kt` / `DetailPage.kt`；`ChatScreen.kt` 与 `CharacterDetailScreen.kt` 不许出现 `DropdownMenu(` | ✅ | `DropdownMenu` 是独立窗口，采不到内容层 → 玻璃退化成纯色板（2026-11-27 需求批 E 的由来） |
| INV-12 | 揭示节奏的两条结构约束：打印期**不许桥接**；推进数学**只来自** `core/model/RevealPacer` | `ChatScreen.followBridgePx()` / `RevealPacer` | 脚本：`followBridgePx()` 的前 600 字符里必须有 `revealKey != null -> 0f`；`ChatScreen` 必须调用 `reveal.pacer.step(`，且不许再出现 `reveal.pending` / `reveal.lastTickNanos` | ✅ | v179/v180 两次都是"又去调桥接"；推进数学若在页面里重写，单测就白建了（2026-11-27） |
| INV-10 | 词表（`docs/ui-map.md`）与枚举、代码名字一致 | `docs/ui-map.md` | 已有：`tools/check-ui-map.sh`（枚举页面都在、名字都能 grep 到） | ✅ | 页头规则写进 AGENTS 而代码没对应入口 → 文档反过来误导改动 |

**为什么 INV-01 是 A 型（而不是又加一条 grep）**：页头坏过两轮，两次都不是"有人新写了一个
`contentTopInset`"，而是"同一批参数被不同人按不同理解组合"。B 型只能拦前者。
只有把材质与让位收进一个枚举、页面层**没有 API 可写**，"页头坏了"才不再取决于 11 个页面记得写什么。

**补充（2026-11-25 晚定稿时想清楚的）**：枚举**只收两种材质**。原来计划的第三个值
`Solid`（或叫 `BelowHeader`）想表达"内容不从页头底下穿过"，但**这件事骨架管不了**：
穿不穿取决于页面把 `padding.top` 用在滚动容器里面还是外面，代码路径与 `Translucent` 完全一样。
给它一个值等于撒谎 —— 一个"实际上什么也不做"的枚举值，正是"文档说一套、代码做一套"的来源。
所以：枚举两种材质，"不穿"只留**一个具名例外**（角色详情页把 `padding` 用在滚动容器外面），
由白名单 + 真机条目守住；要不要让它也穿，是另一个产品问题（见下面第一批第 7 条）。

---

## 五、执行计划（三批，可分批验收）

### 第一批（约半天）—— 收口 + 骨架（**2026-11-25 晚完成**）

**要解决的问题**：页头的"材质"和"首项让位"散在 12 处调用里，而且让位有**四种写法** ——
`+ DetailScrollUnderTop`、`+ DetailHeaderHeight + Space.l`、聊天页自己算 `+ statusBarTop() + Space.l`、
`.padding(padding)` 全用。枚举只收 `contentTopInset` 的话，页面照样能往首项留白上加一笔：
`df71772`（多 12dp）和 `6f4dd53`（世界书列表少 64dp）两次都是这么坏的。所以**骨架要交出最终 top**。

1. **枚举只收材质**（为什么不是三个值，见上一节末尾的补充）：

   ```kotlin
   /** 二级/三级页页头材质。首项让位不在这里 —— 见 contentSpacing。 */
   enum class DetailHeaderMode {
       /** 磨砂带（`LiquidGlassSpec.HeaderBand`）：内容一直在标题正下方滚时才用，只有聊天页 */
       Frosted,
       /** 半透明色罩（`HeaderScrimAlpha`）：其余二级页 */
       Translucent,
   }
   ```

2. **骨架交出最终 top**：`content(padding)` 的 `padding.calculateTopPadding()`
   = `statusBarTop() + DetailHeaderHeight + contentSpacing`。
   `contentSpacing: Dp = 0.dp` 由页面按"首项是什么"传（列表给 `Space.l`，Column 页不传）。
   **页面从此只写 `padding.calculateTopPadding()`，不许再接 `+` 运算** —— 那两次间距事故的入口就在这个 `+`。
3. 删 `contentTopInset` / `frostedHeader` / `DetailScrollUnderTop`；12 处调用逐一对表：

   | 页面 / 调用处 | `headerMode` | `contentSpacing` | 让位用法 |
   |---|---|---|---|
   | `SettingsPages` / 设置七个二级页 | `Translucent` | 0 | bottom 在外 + top 在滚动层内 |
   | `SessionConfigScreen` | `Translucent` | 0 | 同上 |
   | `ProfileEditScreen` | `Translucent` | 0 | 同上 |
   | `ConfigPickerScreen` | `Translucent` | 0 | 同上 |
   | `GalleryScreen` / 组件预览 | `Translucent` | 0 | 同上 |
   | `CardEditScreen` / 正式 | `Translucent` | 0 | 同上 |
   | `CardEditScreen` / 读取中 | `Translucent` | 0 | `.padding(padding)`（空状态） |
   | `CardConfigPageScreen` / 内嵌世界书 | `Translucent` | `Space.l` | 列表 `contentPadding` |
   | `CardConfigPageScreen` / 对话样式·关于 | `Translucent` | 0 | bottom 在外 + top 在滚动层内 |
   | `WorldBookEntriesScreen` | `Translucent` | `Space.l` | 列表 `contentPadding` |
   | `PresetEditScreen` | `Translucent` | `Space.l` | 列表 `contentPadding` |
   | `ChatScreen` | `Frosted` | `Space.l` | 列表 `contentPadding` |
   | `CharacterDetailScreen` | `Translucent` | 0 | `.padding(padding)` —— **唯一"不穿"的具名例外** |

   > 已知的小差：空状态 / 读取中的居中盒子用了 `.padding(padding)`，top 会从"只剩状态栏"变成
   > "状态栏 + 页头"，居中点下移半个页头（28dp）—— 那本来就是"内容区"该在的位置，真机点一眼即可。
4. 脚本判据（`tools/check-ui-invariants.sh` 的 INV-01，白名单只有 `DetailPage.kt`）：
   - **先剔除注释再判**（`PageHeader.kt` / `Style.kt` 的说明里就写着 `frostedHeader`，
     `PresetEditScreen` 的 KDoc 里也写着 `contentTopInset` —— 不剔注释一上来就是误报）；
   - **只判"含 `GlassDetailScaffold(` 的文件"**（三个一级页 `CharactersScreen` / `SessionsScreen` /
     `SettingsScreen` 合法地用了 `statusBarTop()` 给内容让位，不能一起判）；
   - 这些文件不得出现 `contentTopInset` / `frostedHeader` / `DetailScrollUnderTop` /
     `DetailHeaderHeight` / `statusBarTop(`；
   - 不得出现 `calculateTopPadding() +`；
   - 每处 `GlassDetailScaffold(` 必须显式传 `headerMode =`（**不许吃默认值** —— 角色详情那种"没写"
     的页面在判据里就是盲区，和 M10 那个常量写死是同一类"看不出来"）。
5. 同一提交要改 [ui-map.md](ui-map.md) 的「页头 · 磨砂」「内容首项留白」两行：`check-ui-map.sh`
   会查表里的代码标识必须真实存在，删了 `frostedHeader` / `DetailScrollUnderTop` 而词表不改，CI 直接红。
6. `device-verification.md` 补 M11 真机条目：按「M9 / M9.1 回执」A 组复验，外加
   「聊天页磨砂页头不变」「角色详情仍不穿」。
7. **不在这批里做**：角色详情页改成穿页头（卡头是常驻海报，要连着 TabRow 一起重排，单独一轮）。
8. **验收**：与 v150 逐页一致（除了第 3 条那个已知小差）；三条脚本判据分别**自测会红**；
   `check-ui-map` / `check-ui-invariants` 绿。

### 第二批（约 1 小时）—— 便宜的保险（**2026-11-25 晚完成**）

INV-04 / INV-06 / INV-08 三条 grep 规则逐条加，每条都自测会红。白名单都写进了脚本注释：
`Glass.kt`（玻璃入口自己）、`ActionSheet.kt`（弹层宿主的滚动）、`Theme.kt` + `GalleryScreen.kt`（色值）。

判据落地时踩到的三处坑（都记在脚本里，改判据前先看）：

1. **INV-04 的 `record(` 不能数总逗号** —— `record(size = IntSize(w, h))` 里那两个逗号在嵌套括号里，
   一数就把合法用法判成四参数（第一次写完当场误报 `Glass.kt:122`）。要数**顶层**逗号。
2. **INV-04 的 `Modifier.blur` 只能判"除 `Glass.kt` 外"** —— 自研玻璃内部合法地用了 `.blur(...)`
   （糊的是录好的图层，不是 backdrop），一刀切会把唯一入口自己判红。
3. **INV-06 / INV-08 都只在"页面"上成立** —— 三个一级页合法地用 `statusBarTop()`、演示页要色带，
   所以白名单必须按文件名写清理由，别用"模糊匹配目录"这种写法。

**顺手修掉的真问题**（不是判据误报）：`ProfileEditScreen`、`GalleryScreen` 两页的可滚动面漏了
`rubberOverscroll`；`查看请求` 弹层正文那段也没挂。三处都补上了 —— 这是本轮唯一会改变手感的改动。

### 第三批（约半小时）—— 闭环

把 C 型判据（观感/手感）按编号整理进 `docs/dev/verification/<日期>-<Mxx>.md`（一批一个文件），
本表"判据"列补上编号，形成"脚本管结构、清单管观感"两套互补的闸门。

---

## 六、怎么加一条新的不变量

1. 先判断它是 A / B / C 哪一类（能收成唯一入口就 A，否则 B，观感就 C）；
2. 在表里加一行：ID（`INV-NN`）、不变量一句话、唯一入口、判据、破坏记录；
3. 如果是 B 类：在 `tools/check-ui-invariants.sh` 里加判据，**先故意改坏一次确认会红**；
4. 如果是 C 类：在当期 `docs/dev/verification/<日期>-<Mxx>.md` 的表里加一行，并把编号填回本表；
5. 同一次提交里完成，别留"下次补"。
