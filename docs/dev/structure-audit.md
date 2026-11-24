# 结构与资源库审计（2026-11-24）

**这份文档回答三个问题**：可复用的材质/动效/组件有没有被整理出来？代码结构合不合理？
注释是不是冗余？以及**接下来怎么改、按什么顺序、每轮怎么验**。

> 结论先说：**复用层已经写好了（占 app 的 34%），但它没有边界**；结构方向对，但文档与代码
> 有一处不一致（`feature/` 是空目录）、一处过度集中（`ChatViewModel` 1305 行）；
> 注释总量 16.1%，主体是"决策与踩坑"，**该留**，只有三类需要收。

> ## 修订（2026-11-24 下午，已执行）
>
> 用户要求先评估计划再动手，评完重排了三件事的优先级。**下面第一到第八节是评估时的原文，
> 判定以本节为准**：
>
> 1. **`app/AGENTS.md` 不是 21 KB，是 42.4 KB**（`wc -c` = 43,425 字节）—— 第五节的数写错了。
>    整条 AGENTS 链 = 5.7K + 42.4K + 2.1K + 0.9K = **52.3 KB / 64 KB 上限，已用 82%**，
>    而且它**每次会话都进上下文**。瘦身从"可做可不做"变成**收益最高**的一件事。
> 2. **包分层暂缓，不做。** 同一 Gradle 模块内 `internal` 本来就全局可见，包名**拦不住任何依赖**；
>    而本机编不了 `:app`，30 个文件搬包后漏的每个 `import` 都要等 4 分钟 CI 才暴露。
>    完整理由见第二节新增的「为什么不搬」。**将来真要拆，只搬引用面小的叶子包**。
> 3. **`ChatViewModel` 不拆 controller**（1304 行、0 个 composable、`UiState` 是 40 字段的单一
>    状态机）。拆四个 `StateFlow` + `combine` 会撞上本文第 186 行自己点出的"同帧两次 update"
>    风险，而 `PageStack` 每帧都给 `UiState` 打快照。改为**只抽纯逻辑**，见第六节。
> 4. **新增 P0：先验上一批的真机清单，再改代码** —— 否则"UI 本身没做对"和"重构弄坏了"
>    混在同一次装机里，归因成本翻倍。

---

## 一、资源库现状

### 1.1 代码侧：34% 已经是通用件

`app/src/main/kotlin/app/tavern/` 下 30 个文件、7643 行（含注释）按"通用 / 页面"分：

| 通用件（与页面无关，可复用） | 文件 | 行数 |
|---|---|---|
| 主题与样式 | `Theme.kt` · `Style.kt` · `Motion.kt` | 966 |
| 玻璃与材质 | `Glass.kt` · `LiquidGlass.kt` | 775 |
| 弹层 / 减动效 / 转场 | `ActionSheet.kt` · `ReducedMotion.kt` · `PageStack.kt` | 443 |
| 手势与页头 | `RubberOverscroll.kt` · `PageHeader.kt` · `SessionTime.kt` | 436 |
| **合计** | | **≈2620（34%）** |

页面侧：`ChatScreen` 479 · `SettingsPages` 527 · `CharacterDetailScreen` 341 · `DetailPage` 312 ·
`CharactersScreen` 237 · `SessionsScreen` 188 · `ProfileEditScreen` 195 · `CardConfigPageScreen` 155 ·
`WorldBookEntriesScreen` 119 · `SettingsScreen` 67 · `CardPickerSheet` 65 · `CardAvatar` 128 · `CardDetail` 30。

状态机与装配：`ChatViewModel` 1305 · `MainScreen` 614 · `AppGraph` 78 · `Screen` 76 · `TavernApp` 59 ·
`MainActivity` 20。

### 1.2 文档侧：其实已经有"文档资源库"

- 材质：`docs/adr/0005`(不用 Haze) · `0006`(效果挂节点) · `0007`(AGSL 液态玻璃参数)
- 动效：`docs/design/motion.md`（频率闸门 / 曲线时长 / Never Ship / 三处有意偏离）
- 规矩：`app/AGENTS.md` 的「玻璃与页头」「动作单」「列表」「角色卡」「动效」五节
- 样张：`docs/design/mockups/lists-v1.*`、`cards-v1.*`

**缺的不是知识，是"代码边界 + 看得见的预览"。**

### 1.3 缺的三件

1. **组件没去重**（见第三节的实证）。
2. **没有预览页**：改材质参数只能真机跑一遍。2026-11-24 那次"弹层 faceWhite 改了看不出变化"，
   一半原因是白底上看不出、一半原因是没法在 App 里并排对比。
3. **没有模块边界**：`feature/` 是空目录（见第七节）。

---

## 二、目录分层方案（**暂缓 —— 不执行**）

**为什么不搬**（2026-11-24 评估结论）：

- 它**不产生任何约束**。`internal` / `private` 在同模块内全局可见，包名挡不住谁引用谁；
  "页面之间不许互依"这条现在由 `app/AGENTS.md` 的文字守着，换个目录既不更多也不更少。
  真正能约束的模块边界，第七节已经决定不建。
- **成本被低估了**：搬走的每个符号，引用它的文件都要补 `import`（`Style.kt` 的 `Space`/`Radius`/
  `ListRow` 几乎是全部 24 个页面文件），保守 200+ 行 import。而**本机编不了 `:app`**，
  漏一条就要等 4 分钟 CI 才报 `Unresolved reference`，一次一条符号。
- **免费的替代**：**同一包内拆文件不花任何 import 代价**。所以"`Style.kt` 638 行只留 token、
  行系统搬进 `ListRow.kt`"可以照做（见第九节 P4），收益接近、成本为零。先拆文件，别拆包。
- **反方向的收益为零**：包分层对运行时、编译时间、可读性（文件名已经都是 `*Screen.kt`）都没影响。

下面的目标结构与迁移清单**保留**，等真出现"要开第二个 app / 要给别人复用"时照此执行，
且**只搬引用面小的叶子包**（`ui.glass`、`ui.nav`），`ui.theme`（`Space`/`Radius` 到处用）最后搬。

**原方案（存档）**：不新增 Gradle 模块（这台机器 CI 编译很脆），只做**包分层 + 文件移动**。
`internal` / `private` 的可见性在同模块内不受影响，所以移动是机械操作。

目标结构（`app/src/main/kotlin/app/tavern/` 下）：

```
app.tavern
├── MainActivity.kt · TavernApp.kt · AppGraph.kt · Screen.kt      （装配，留根）
├── ui.theme      Theme.kt  Style.kt  Motion.kt  ReducedMotion.kt
├── ui.glass      Glass.kt  LiquidGlass.kt
├── ui.component  ActionSheet.kt  ListRow/ListGroup（Style 里的部分）  PageHeader.kt
│                 ConfirmDeleteDialog.kt（新）  CardAvatar.kt（卡片磨砂条 CardFrost 也在这）
├── ui.nav        PageStack.kt  Screen.kt（Destination）
├── ui.text       SessionTime.kt（时间分级与副行，和导航无关 —— 评估时归错到 ui.nav）
├── screen        SessionsScreen · CharactersScreen · SettingsScreen · SettingsPages
│                 ChatScreen · CharacterDetailScreen · DetailPage · ProfileEditScreen
│                 CardConfigPageScreen · WorldBookEntriesScreen · CardPickerSheet · CardDetail
└── vm            ChatViewModel.kt（见第六节）
```

迁移清单（文件 → 包）：

| 文件 | 去哪个包 |
|---|---|
| `Theme.kt` `Style.kt` `Motion.kt` `ReducedMotion.kt` | `ui.theme` |
| `Glass.kt` `LiquidGlass.kt` | `ui.glass` |
| `ActionSheet.kt` `PageHeader.kt` `CardAvatar.kt` | `ui.component` |
| `PageStack.kt` `Screen.kt` | `ui.nav` |
| `SessionTime.kt` | `ui.text`（**不是 `ui.nav`**：它是格式化工具，和导航无关） |
| 12 个 `*Screen.kt` / `SettingsPages.kt` / `DetailPage.kt` / `CardPickerSheet.kt` / `CardDetail.kt` | `screen` |
| `ChatViewModel.kt` | `vm` |
| `MainActivity` `TavernApp` `AppGraph` | 留 `app.tavern` |

**注意**：~~拆包后~~ **已提前做掉的那一半（2026-11-24，P4）**：`ListRow` / `ListGroup` / `GroupPiece` /
`RowToken` / 三个行文字样式已经搬进**同包**的 `ListRow.kt`，`Style.kt` 从 638 行降到 307 行 ——
同包拆文件零 import 代价。将来真要按包拆，这一份直接搬进 `ui.component/` 即可。

**验收**：三项本机检查 + CI 编译 + 真机各页能打开（不需要逐项点，能进能返回即可）。

---

## 三、重复清单（**第 1 轮已做，2026-11-24**）

实测（2026-11-24）：

| 重复 | 次数 | 位置 |
|---|---|---|
| `AlertDialog` 删除确认 | **7** | `SessionsScreen`、`CharactersScreen`、`SettingsPages`(×2：世界书/模型档案)、`CharacterDetailScreen`(×2：会话/卡)、`ProfileEditScreen` |
| `WindowInsets.statusBars.asPaddingValues()` | **7** | 各页顶部让位 |
| 行内「删除」动作单接线 | 4 | 会话 / 角色卡 / 世界书 / 模型档案（`sheet.show(...)` 各写一遍） |

建议抽出：

```kotlin
/** 不可逆删除的统一确认框：标题 + 一句后果 + 红色确认。 */
@Composable
fun ConfirmDeleteDialog(
    title: String,          // "删除会话？"
    message: String,        // "「X」的 N 条消息会一起删掉，不能撤销。"
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "删除",
)

/** 状态栏高度：各页顶部让位统一从这里取。 */
@Composable
fun statusBarTop(): Dp
```

收益：约 70 行重复消失，且以后改"删除的措辞/颜色"只改一处。

**已做的（2026-11-24）**：`ConfirmDeleteDialog.kt` + `PageHeader.kt` 里的 `statusBarTop()`，
7 处 `AlertDialog` → 7 处 6 行调用，7 处 `WindowInsets.statusBars…` → 7 处一个函数调用。
顺手统一了三处不一致（原来只有 `ProfileEditScreen` 的确认钮是红色、两处没写"不能撤销"、
同一动作两处措辞不同）：确认钮一律 `colorScheme.error`，删会话/删卡各只有**一句**措辞。

---

## 四、预览页 `GalleryScreen`（第 2 轮）

**目的**：以后调材质/动效不再"改代码 → 出包 → 真机看"。这是这次 `faceWhite` "改了看不出"的
直接教训。

- **仅 debug 可见**：`BuildConfig.DEBUG` 为真时才在「设置 → 关于」底部显示一行「组件预览」。
  发布包里不存在（不违反"界面上不写解释性文案"——它不在发布包里）。
- 内容（一屏一组，可滚动）：
  1. **材质**：胶囊（Default）、页头带（HeaderBand）、弹层（Sheet，faceWhite 0.50）、
     发送钮（主题色 32%）、卡片磨砂条（`CardFrost`）；
  2. **玻璃参数滑杆**（debug only）：`faceWhite` / `refraction` / `rimAlpha` 三个可调，
     调完把数值显示出来，方便定稿后写回 `LiquidGlassSpec`；
  3. **动效**：按压（行/卡/玻璃钮）、底栏滑动底块、弹层进出、页面转场（标准/淡入/关闭）、
     橡皮筋；
  4. **行样式**：有头像/无头像/只有标题/带时间/带 trailing 五种。
- 风险：这一页会引入状态与滑杆（`Slider` 在 material3 里有），但**不进发布包**，不影响正式 UI。

---

## 五、注释审计

**实测**：1228 注释行 / 7643 总行 = **16.1%**（空行 529，代码约 5886）。

| 文件 | 注释/总行 | 判断 |
|---|---|---|
| `Motion.kt` | 64/90 = 71% | **留**：注释就是"为什么是这些数值"，本身就是资产 |
| `Glass.kt` | 145/492 = 29% | 留（记录了三版失败的根因） |
| `LiquidGlass.kt` | 105/285 = 37% | 留（着色器逐项解释） |
| `Style.kt` | 115/639 = 18% | 大部分留，行系统那几段可压缩 |
| `ChatViewModel.kt` | 139/1305 = 11% | 留 |

**该收的三类**：

1. **复述代码的**（`// 遍历所有项`、`// 设置颜色`）—— 少量，直接删。
2. **表述与实现漂移的** —— 比 `ListGroup` 注释写"懒加载与非懒加载共用"，实际会话页是直用
   `GroupPiece`。改成"两者共用同一套片（`GroupPiece`）"。
3. **三处重复的同一结论** —— `app/AGENTS.md`(**实测 42.4 KB，不是 21 KB**) +
   `docs/dev/status.md`(22 KB) + `docs/design/motion.md`(8 KB) 都在讲同一批坑。
   注意 `app/AGENTS.md` 是**每次会话都要进上下文**的文件，整条 AGENTS 链已经
   52.3 KB / 64 KB 上限（**82%**），再长就会被截断 —— 截断的正好是它。建议：
   - 新建 `docs/dev/pitfalls.md`，把"历史踩坑"按主题归档（玻璃 / 列表 / 转场 / 手势 / 构建）；
   - `app/AGENTS.md` 只留**必须遵守的规则 + 一行理由 + 链接**，目标 **≤12KB**；
   - `status.md` 只留"当前基线 + 待确认项 + 下一步"，历史细节指向 pitfalls。

**已做（2026-11-24，P2）**：`docs/dev/pitfalls.md` = 旧 `app/AGENTS.md` 的**逐字归档**
（43,425 → 44,347 字节，只加索引头与相对链接修正，22 个小节标题全在）；
新 `app/AGENTS.md` **8.9 KB**（目标 ≤12 KB），规则 40 余条、每条一行 + 链接。
整条 AGENTS 链 **52.3 KB → 17.8 KB（82% → 28% of 64 KB）**。

**不要做的事**：不要为了"注释率好看"去删踩坑记录。这个仓库文档密度高是**有意的**，
换来的是一年后的自己不用重新踩一遍。

---

## 六、`ChatViewModel`：**不拆 controller，只抽纯逻辑**（2026-11-24 修订）

实测：1304 行、**0 个 `@Composable`**、`UiState` 是**一个 40 字段的扁平 data class**。
它不是"五件事糊在一起"，它是这个 App 的**单一状态机**。

**为什么不拆**（推翻原文的建议）：

- 拆四个 controller + `combine` 会撞上本文第 186 行自己点出的风险："同一帧两次 `update` 丢字段"；
- `PageStack` 每帧给 `UiState` 打快照（`snapshots[dest]`），状态源一拆，**同帧内的合并语义就变了**，
  而这个 VM 是全仓最热路径；
- 收益是"可读性"，但"AI 每次整文件读 + 1304 行、0 个 composable"这种规模并不难读。

**要动就动这里**（零状态风险）：

- `worldInfoStates` / `globalBookEntries` 两本缓存（世界书扫描状态）→ 抽成独立类，还能进 `:core` 带单测；
- 流式拼接与落库的那段（`stop()` 附近）→ 抽成纯函数，输入输出都明确；
- 真要拆文件时**同包内拆**（`ChatViewModel.kt` + `ChatViewModel.WorldBooks.kt`），import 代价为零。

原文存档如下。

---

现在 1305 行，承担五件事：消息与流式、会话列表、主题/采样/外观、模型档案、卡导入。
建议拆成组合关系（对外仍是一个 `UiState`）：

```
ChatViewModel
├── SessionController   会话与消息：openSession / send / stop / 流式拼接 / append 落库
├── ProfilesController  模型档案：list / setDefault / save / delete / refreshProfiles
├── SettingsController  主题 / 采样 / 外观 / 导航动效
└── CardsController     导入 / 删卡 / 世界书导入 / 正则导入
```

- 每个 controller 持有 `MutableStateFlow`，VM 用 `combine` 合成 `UiState`
  （现在的 `_state.update { it.copy(...) }` 是"全量状态"，拆开时要小心**同一帧两次 update 丢字段**）。
- **风险**：这是全仓最热的状态机，拆错会连带聊天、设置、导入一起坏。建议**单独一轮**，
  拆完先只跑编译 + 真机走一遍"导入卡 → 建会话 → 聊天 → 改设置 → 切档案"。
- **收益**：单文件从 1305 → 每个 300 左右，改聊天不再碰到设置。

---

## 七、`feature/` 空目录与文档不一致

- 事实：`feature/` 下**只有 AGENTS.md，0 个 kt 文件**；`settings.gradle.kts` 只 include
  `:core:*`、`:data`、`:app`。界面全在 `:app`。
- 根 `AGENTS.md` 的仓库布局图写的是 `app → feature → data → core`，**与代码不符**。
- 处理建议：**改文档，不建模块**。理由：
  1. 现在 30 个文件、界面点集中，拆成 5 个 `feature:*` 模块会让 CI 每轮多配置 5 个模块，
     而这台机器编一次已经 4 分钟；
  2. 拆分真正的收益是"界面之间不许互依"，而现在的页面之间本来就没有互依（都只依赖 ui/* 与 VM）；
  3. 真要拆的时候，`ui.*` 包已经分好了，直接把 `screen` 按页面切出去即可。
- 落地：根 `AGENTS.md` 布局那一段改成"`app`（界面 + 装配）→ `data` → `core`，`feature/` 暂未启用"。

---

## 八、`:ui` 独立模块：什么时候才值得

值得做的信号（满足任一条再做）：

1. 要开**第二个 app**（或要把这套材质给别的项目复用）；
2. `ui.*` 里开始出现**不该依赖 `core:model`** 的东西（现在 `CardFrost`/`CardAvatar` 依赖卡仓库，
   说明它其实偏业务，应留在 app 而不是 `:ui`）；
3. 想在 CI 里对 `ui` 做**独立的截图/预览测试**。

现在做的代价：`settings.gradle.kts` + `app/build.gradle.kts` 各一段、CI 配置多一个模块、
依赖规则与 AGENTS 全部要重审，而收益只是"看起来干净"。

---

## 九、分轮计划与验收（**2026-11-24 下午修订版**）

| 顺序 | 内容 | 出包 | 风险 | 验收 |
|---|---|---|---|---|
| **P0** | **先在真机走一遍上一批的 10 项**（`status.md` 的「待真机逐项确认」） | — | — | 清单点完；不对的先记下来 |
| **P1** | `ConfirmDeleteDialog` + `statusBarTop()` + 三处措辞/颜色统一（第三节） | ✅ | 低（纯抽取；**唯一视觉变化**：确认钮统一成红色） | 三项本机检查 + CI 编译 + 真机长按删一条会话、删一张卡 |
| ~~**P2**~~ ✅ | `app/AGENTS.md` 42.4 KB → **8.9 KB** + 逐字归档建 `docs/dev/pitfalls.md`（第五节） | — | 低 | 已达标：链 52.3 → 17.8 KB |
| ~~**P3**~~ ✅ | `GalleryScreen`（debug only，材质三根滑杆）（第四节） | ✅ | 低 | 已做（`59139f4`）：入口 设置→关于，滑杆松手即应用并给出一行可粘贴的 `copy(...)` |
| ~~**P4**~~ ✅ | `Style.kt` 638 行 → **307 行**（只留 space/radius/页头小件/按钮/空态），行系统搬进同包 **`ListRow.kt`**（364 行） | ✅ | 低 | 已做：原文每一个非 import 行都还在两份文件之一（脚本比对过） |
| **不做** | 包分层（第二节）、拆 `ChatViewModel` 的 controller（第六节） | — | — | — |
| 之后 | 功能线：P1-5 预设导入 → D 批三级继承 → P0-6 消息操作 → 世界书剩余 | — | — | 见 `compat-matrix.md` |

**原文存档（2026-11-24 上午）**：第 1 轮 = 包分层 + 抽取；第 2 轮 = GalleryScreen + pitfalls +
瘦身；第 3 轮 = 拆 ChatViewModel。前两项已按上文重排，第 3 轮取消。

---

## 十、每轮开工前的固定动作

0. **上一批还有没真机验过的东西时，不叠重构**（混在一次装机里就分不清是谁坏的）；
   一次只改一件事 —— 一轮 CI 4 分钟，两件事混在一个提交里，失败原因要翻两遍。
1. 读 `docs/dev/status.md` 的「接手先看」+ 本文件的「分轮计划」；
2. 动页面/材质 → 先读 `app/AGENTS.md` 对应那一节（玻璃 / 列表 / 弹层 / 动效）；
3. 改完跑：`tools/check-core-purity.sh`、`tools/check-kotlin-comments.sh`、
   `tools/check-kotlin-imports.sh`、`tools/test-core.sh`；
4. 出包走 CI（`git tag apk-mN && git push origin HEAD apk-mN`）；
5. 交付前核对 **dex 里的 BUILD_ID**（大小经常撞车）与签名指纹。
