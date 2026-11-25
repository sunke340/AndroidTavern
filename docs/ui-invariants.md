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
| **C 真机** | 观感与手感，写进 `device-verification.md` 的编号条目 | 只能人工 | 用户点一次 |

## 二、验收规则（不满足就不许进表）

1. 必须写明**唯一入口**（代码在哪）或**脚本判据**（`tools/check-ui-invariants.sh` 的第几条）；
2. 脚本判定类必须**自测过会红**（故意改坏一次，看到报错再改回来）；
3. 观感类必须有一句可执行的**真机判据**（"看什么 = 判定成立"），并进 `device-verification.md` 编号；
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
| INV-01 | 二级页页头的**材质 + 首项让位**只能由骨架决定，页面不得各写 | `GlassDetailScaffold` 的 `DetailHeaderMode { Frosted, Translucent, Solid }`（待引入） | 脚本：页面目录里不得再出现 `contentTopInset =` / `frostedHeader =`；真机：二级页滚动时内容从页头底下穿过，首项留白 = Column 页贴页头底边、列表页 +16dp | ⬜ | `8ee1b72`（磨砂带→色罩）、`df71772`（首项多出 12dp）、`6f4dd53`（世界书列表少了 64dp） |
| INV-02 | 聊天页气泡底板必须用 `MessagePanel`，**不许**用 Material3 `Surface` | `ChatScreen.MessagePanel` | 脚本：`ChatScreen.kt` 里裸 `Surface(` 只允许出现在 `RequestDialog` 内（注释先剔除） | ✅ | `1952a2b`：Surface 的 `clip(shape)` 让 18900px 高的气泡在约 8k px 之后收不到触摸 → 长按完全没反应 |
| INV-03 | 选中文字**不许**拖动列表 | `ChatScreen` 的 `NoFocusScrollSpec` + `LocalBringIntoViewSpec` | 脚本：`ChatScreen.kt` 必须出现 `LocalBringIntoViewSpec provides NoFocusScrollSpec`；真机：反复长按，`偏移=` 不变 | ✅ | `f58ad0c`（修 `8ee1b72` 之后暴露的焦点滚动，跳动量 = `trailingEdge - containerSize`） |
| INV-04 | 玻璃三铁律：不许 `Modifier.blur` / Haze；录图层只用 `record` 扩展；`renderEffect` 不许在 draw 块里"设了再清" | `GlassSurface`（`Glass.kt`） | 脚本：grep `Modifier.blur(`、`haze`、四参数 `record(`；（待做） | ⬜ | ADR 0005/0006：Haze 在 vivo sdk37 只给半透明；四参数 record 画出空图层 |
| INV-05 | 弹层只能走宿主：不许 `ModalBottomSheet`，页面不许自己 composed 弹层 | `SheetController` + `GlassSheetScaffold`（`ActionSheet.kt`、`MainScreen.kt`） | 脚本：全 app 不得出现 `ModalBottomSheet`；`GlassSheetScaffold(` 只许出现在这两个文件 | ✅ | `a4888f3`：`ModalBottomSheet` 是独立窗口，采不到内容层 |
| INV-06 | 每个可滚动面都必须挂 `rubberOverscroll` | 各页 `Modifier.rubberOverscroll(...)`（在 `verticalScroll`/`LazyColumn` **之前**） | 脚本：出现 `verticalScroll(` / `LazyColumn(` 的文件必须同时出现 `rubberOverscroll`（待做，需排除骨架） | ⬜ | 2026-11-23 多次"某页拉到头没有回弹" |
| INV-07 | 内容底部让位取**并集** `max(导航栏, 键盘)`，不许相加 | `GlassDetailScaffold` 的 `maxOf(navBottom, imeBottom)` | 脚本：不得出现 `navBottom + imeBottom` / `imeBottom + navBottom` | ✅ | 键盘弹出时多抬一个导航栏高度 |
| INV-08 | 颜色只从 `Theme.kt` / `Style.kt` 取，页面里不许出现字面色值 | `TavernTheme` / `LocalTavernPalette` | 脚本：页面目录 grep `Color(0x` / `0xFF`（待做） | ⬜ | 深色模式下发灰（写死白踩过） |
| INV-09 | 显示格式**只影响显示**；内置正则每条规则都有单测 | `core/regex/BuiltinRegex`、`InlineMarkup` | 已有：`tools/test-core.sh`（19 条）+ fixture `P1-12-inline-markup` | ✅ | 清标签规则漏了 `</>`（2026-11-25，已补单测） |
| INV-10 | 词表（`docs/ui-map.md`）与枚举、代码名字一致 | `docs/ui-map.md` | 已有：`tools/check-ui-map.sh`（枚举页面都在、名字都能 grep 到） | ✅ | 页头规则写进 AGENTS 而代码没对应入口 → 文档反过来误导改动 |

**为什么 INV-01 是 A 型（而不是又加一条 grep）**：页头坏过两轮，两次都不是"有人新写了一个
`contentTopInset`"，而是"同一批参数被不同人按不同理解组合"。B 型只能拦前者。
只有把材质与让位收进一个枚举、页面层**没有 API 可写**，"页头坏了"才不再取决于 11 个页面记得写什么。

---

## 五、执行计划（三批，可分批验收）

### 第一批（约半天）—— 收口 + 骨架

1. 引入 `DetailHeaderMode { Frosted, Translucent, Solid }`：骨架内部由它同时决定
   ①材质（磨砂带 / 色罩）②首项让位（`DetailScrollUnderTop` 或列表 `contentPadding`）。
2. 删掉页面上的 `contentTopInset` / `frostedHeader` 参数（11 个调用点），聊天页传 `Frosted`，
   其余二级页 `Translucent`，角色详情暂按现状（见"已知未做"）。
3. `tools/check-ui-invariants.sh` 加 INV-01 判据；`device-verification.md` 补对应真机条目。
4. **验收**：观感零变化；脚本自测会红；`tools/check-ui-invariants.sh` 与 `check-ui-map.sh` 都绿。

### 第二批（约 1 小时）—— 便宜的保险

INV-04 / INV-06 / INV-08 三条 grep 规则逐条加，每条都自测会红。需要处理骨架文件的白名单，
避免一上来就误报。

### 第三批（约半小时）—— 闭环

把 C 型判据（观感/手感）按编号整理进 `docs/dev/device-verification.md`，
本表"判据"列补上编号，形成"脚本管结构、清单管观感"两套互补的闸门。

---

## 六、怎么加一条新的不变量

1. 先判断它是 A / B / C 哪一类（能收成唯一入口就 A，否则 B，观感就 C）；
2. 在表里加一行：ID（`INV-NN`）、不变量一句话、唯一入口、判据、破坏记录；
3. 如果是 B 类：在 `tools/check-ui-invariants.sh` 里加判据，**先故意改坏一次确认会红**；
4. 如果是 C 类：在 `docs/dev/device-verification.md` 相应里程碑表里加一行，并把编号填回本表；
5. 同一次提交里完成，别留"下次补"。
