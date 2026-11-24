# 项目状态快照（写于 2026-11-24 11:50）

**用途**：换会话/压缩上下文后，先读这一页就能接上。更细的规则在 [AGENTS.md](../../AGENTS.md)、
[app/AGENTS.md](../../app/AGENTS.md)，进度唯一真相在 [compat-matrix.md](../compat-matrix.md)。

## 当前基线

- 代码：`e70f4df`（tag `apk-m114`；兜底回退点：`apk-m48` 是玻璃版，`apk-m27` 是纯 C2 版；
  列表改版之前的最后一版是 `apk-m100` / `99ec1f5`）
- 状态：CI 全绿；本机 `tools/test-core.sh` / `tools/check-*.sh` 全绿
- 已交付 APK：`app-v91-e70f4df.apk`（签名固定，可覆盖安装；上一版 `app-v82-99ec1f5.apk`）
- **结构整理还没开始**：计划与审计在 [structure-audit.md](structure-audit.md)
- 引擎/工具链：Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01 / minSdk 26 / targetSdk 35

## 接手先看这五件（2026-11-24 中午）

1. **今天这一批（列表 / 弹层 / 底栏 / 角色卡 / 间距）已出包，逐项待真机确认** ——
   清单在下面「待真机逐项确认」。**动这些之前先读 [app/AGENTS.md](../../app/AGENTS.md)
   的「列表」「角色卡」「动作单」三节**，那里记着为什么不能手写行、为什么弹层不能用
   `ModalBottomSheet`、为什么卡片磨砂不能用 `GlassSurface`。
2. **结构整理一轮都没做**：审计 + 三轮计划在 [structure-audit.md](structure-audit.md)。
   第 1 轮是低风险（包分层 + 抽 `ConfirmDeleteDialog` / `statusBarTop` + 修 2~3 处漂移注释）。
3. **玻璃帧率始终没量过**：液态玻璃现在覆盖底栏、页头、弹层、输入区、发送钮、返回钮。
   发黏就先给最贵的那块单独传 `liquid = null`（底栏最贵）。
4. **橡皮筋的 `flingEaten` 修复（`1675887`）仍未真机验证**：拉到底 → 猛甩向上 → 撞顶后应立即回弹。
   日志判据（tag=`rubber`）：`回弹 start` 只出现一次、紧接 `fling 撞上限，残余惯性丢弃`、
   然后 `回弹结束`；若 `回弹 start` 反复出现、中间夹 `回弹被打断 raw=-4xx`，说明还有第三个真因。
5. **模型页「点整行＝切换档案」改了肌肉记忆**：点行切换、长按出「编辑/删除」。手感要你确认。

## 2026-11-24 上午～下午：列表 / 弹层 / 底栏 / 角色卡（行系统 v2 起的一整批）

用户要求"重新设计各处列表"（会话列表、模型列表）。先出了一版 HTML 样张
（[docs/design/mockups/lists-v1.png](../design/mockups/lists-v1.png)）确认方向，再落地。

| 改了什么 | 之前 | 现在 |
|---|---|---|
| 行结构 | 每行 64dp、通栏 1px 分隔线、标题 14sp/500、副行 11sp 两行（模型行把 baseUrl 塞进去） | 68dp、15sp/600、12sp 副行一行、行尾时间列 44dp 等宽数字 |
| 分组 | 没有容器，全靠通栏线分隔；列表末尾还垂着一条线 | 圆角 18dp 面板（`ListGroup`/`GroupPiece`）、边框只画外轮廓、分隔线从文字左缘起且最后一行不画 |
| 会话副行 | `MM-dd HH:mm · 12 条` | **最后一条消息**（"你："前缀），时间分级：今天给时刻 / 昨天 / 周几 / 月-日 / 跨年给年份 |
| 会话分组 | 无 | 「今天 / 更早」 |
| 模型行 | 点整行＝编辑，行尾 11sp「设为默认」（约 30dp 触控区，与整行差 8dp） | **点整行＝切换**（主），行尾 `⋯`＝编辑/删除（48dp 触控区），圆圈勾表示当前 |
| 世界书 / 正则 | 手写 `Row`，与其它列表三套边距 | 走 `ListRow` + `ListGroup` |
| 角色卡 | 缺描述时渲染「（这张卡没有描述）」 | 不渲染那一行 |

- 数据层：`ChatSession` 加 `lastMessage` / `lastFromUser`，SQL 用 `MAX(id)` 子查询取末条
  （不整表载入）。两个字段都有默认值，`createSession` 与其它构造点没改、**无 DB 迁移**。
- 依据：`emil-skills` 里跨平台成立的三条 —— 反馈发生在按下那一刻（`apple-design` §1、
  `mobile-native` §5）、高频列表不做出现/消失动画（`choreograph`）、数字用 tabular-nums 且
  超过一周换绝对日期（`ui-cheatsheet`）；`break-ui` 的"缺字段别渲染占位行"用在了角色卡上。
- **未验证**：全部只在编译器与样张层面确认过，真机手感（尤其模型页"点整行＝切换"）待你试。
- 同一天后续（用户要求）：
  - **次要操作收进长按抽屉**（`ActionSheet.kt`）：会话 / 世界书 / 模型档案三处同一个入口；
    世界书行尾的删除图标、模型行尾的 `⋯` 都删了。抽屉里的删除只关抽屉 + 弹确认框，不直接删。
  - **行的按压改成"按下即满色"**：原来两侧都 120ms tween，点一下 80~120ms 就跳页，
    底色还没长出来（用户："还没出现就进去了"）。现在按下 `snap()`、松开 160ms 淡回。
  - **弹层与底栏的先后**：底栏先向下滑走，弹层再上来；收起时反之。
  - **选卡弹层也有真磨砂**（`CardPickerBody` + `TabsRoot` 里的 `GlassSheetScaffold`）。
  - **动作单（长按抽屉）换成自绘玻璃弹层**：`ModalBottomSheet` 在 Dialog 窗口里采不到内容层，
    改成 `SheetController` + 页面骨架里的 `GlassActionSheetHost`（在录图层之外），
    弹层因此是真磨砂 + 折射；没有拖拽关闭。
  - **聊天输入区投影乘 0.5**（`GlassSurface(shadowScale = …)`）：它压在正文上，全强度太抢。
  - **底栏选中底块改成滑动**：一块 14% 主色跟着选中项走（200ms、`Motion.Standard`，减动效 snap）。
  - **角色卡按方案 B 改掉了**（用户选 B，并要求文字下面垫磨砂）：满幅图 + 56dp 磨砂条，
    名字与会话数/内嵌书压在条上；磨砂用预先解出的 32px 小图（`CardFrost`），**不用 GlassSurface**
    —— 卡在内容录图层里，玻璃会采到自己这一帧。样张见
    [docs/design/mockups/cards-v1.png](../design/mockups/cards-v1.png)（方案 B 那一栏）。

### 这一批交付过的包（都在 `artifacts/`）

| 包 | commit | 装了什么 |
|---|---|---|
| `app-v83-d312a42.apk` | `d312a42` | 行系统 v2（分组面板/内缩分隔线/按下即亮）、会话最后一条消息 + 分级时间、模型页整行切换、世界书/正则并入行系统、角色卡删占位行 |
| `app-v84-ec9248d.apk` | `ec9248d` | 次要操作收进长按抽屉（会话/世界书/模型档案）；行按压改「按下即满色」 |
| `app-v85-c793834.apk` | `c793834` | 角色卡方案 B：满幅图 + 56dp 磨砂条 + 长按抽屉（`CardFrost` 预解 32px 小图） |
| `app-v86-9183d67.apk` | `9183d67` | 三处顶部间距（会话 -12dp、聊天 +8dp、模型 +16dp） |
| `app-v87-d44640a.apk` | `d44640a` | 聊天输入区投影 ×0.5；底栏选中底块改成一块滑动 |
| `app-v88-a4888f3.apk` | `a4888f3` | 动作单换成自绘液态玻璃弹层（真磨砂 + 折射） |
| `app-v89-cd86793.apk` | `cd86793` | 底栏先滑走再弹层；选卡弹层迁到新材质；底栏去涟漪（**已被 v90 取代**） |
| `app-v90-449f69f.apk` | `449f69f` | 弹层 faceWhite 0.40 + 底栏点按轻 tint（**被 v91 取代**） |
| `app-v91-e70f4df.apk` | `e70f4df` | 弹层 faceWhite 0.50；聊天页**页头下沿**投影 ×0.5；新增 `tag=glass 液态档 …` 诊断日志 |

### 待真机逐项确认（都在这批里，没人点过）

| 项 | 怎么看 | 不对时改哪 |
|---|---|---|
| 会话行副行 = 最后一条消息 | 列表每行第二行应是自己/角色说的最后一句，自己发的带「你：」 | `SessionsScreen.buildSessionItems` / `previewLine` |
| 会话时间分级 + 今天/更早分组 | 今天显示时刻、昨天显示"昨天"、一周内周几、更早月-日 | `SessionTime.kt` |
| 模型页点行=切换 | 点行立刻切默认（圆圈勾移动），长按出编辑/删除 | `ModelPage` 的 `onClick/onLongClick` |
| 角色卡磨砂条 | 名字压在图上、底下是 56dp 磨砂条；看浓度（0.72）与深色模式 | `CharactersScreen.CardBand`（`surface × 0.72`、56dp） |
| 弹层"实不实" | **必须在彩色内容上看**（角色卡页长按一张卡）：白底列表上看不出 0.22 与 0.50 的差别 | `LiquidGlassSpec.Sheet` 的 `faceWhite`（现 0.50） |
| 弹层是否真糊 | 弹层顶边有折射、面里是糊色块（认不出字） | 导出日志 grep `液态档`，`着色器=false` 就是没跑 |
| 底栏点按 tint | 点一下有 6% 主色，和滑动紫块会叠（合计约 19%） | `TavernBottomBar` 里 `0.06f` |
| 页头下沿影子 | 聊天页页头下面那条影子，现在 0.16→0.08 | `DetailPage` 里 `shadowScale = 0.5f` |
| 底栏让位再弹层 | 开弹层时底栏先向下滑走，弹层随后上来 | `SheetEnterDelayMillis`（180）/ 底栏 180·200 |
| 选卡弹层新材质 | 右上「＋」：三列卡网格压在玻璃面上 | `CardPickerBody` |

## 已经做完的（能装、能日常聊）

| 块 | 状态 |
|---|---|
| 卡：PNG 导入（SAF）、卡网格两列 3:4、详情四标签、卡内嵌书勾选、删卡连带删会话 | ✅ 真机可用 |
| 聊天：SSE 流式、开场白（first_mes 作为第一条消息）、气泡/整行卡片、停止保留半截 | ✅ |
| 持久化：SQLite 会话/消息、会话列表、新建（先选卡弹层）、长按删除（动作单+确认） | ✅ |
| 设置：一级/二级（模型/采样/外观/世界书/关于）、主题四套色板 + 深浅、Key 加密存储 | ✅ |
| 导航：`Destination` + 自写 `PageStack`，0–2 层，三档转场（标准/淡入/关闭）；返回时**深的那页盖在上面滑走** | ✅ 真机确认（2026-11-24 用户验收） |
| 列表：行系统 v2（分组圆角面板 + 内缩分隔线 + 按下即亮），会话加最后一条消息与分级时间，模型页整行切换 | 🟨 编译通过、已出包，**待真机确认** |
| 角色卡：满幅图 + 底部磨砂条（名字 + 会话数/内嵌书）+ 长按抽屉 | 🟨 编译通过、已出包，**待真机确认** |
| 弹层：自绘液态玻璃（真磨砂 + 折射），会话/世界书/模型/角色卡/选卡共用；底栏在它起来前先滑走 | 🟨 编译通过、已出包，**待真机确认** |
| 底栏：一块 14% 主色底块滑动 + 点按 6% 轻 tint（无涟漪） | 🟨 编译通过、已出包，**待真机确认** |
| 应用图标：黑猫月色 adaptive icon（前景 63% 安全区 + 模糊插画底） | ✅ |
| 日志：`AppLog`（内存+落盘）+ 崩溃兜底 + 关于页导出 | ✅ |
| 世界书引擎：关键字/常数/位置/深度/概率/四种 selectiveLogic/递归/包含组/sticky/cooldown/delay/预算 | 🟨 引擎 + 夹具全绿；App 已接线（卡内嵌书勾选真生效），**待真机** |
| 全局世界书：导入 `.json` / 启用停用 / 删除 / 与卡内嵌书一起扫描 | 🟨 代码完成，**待真机** |
| 正则脚本：导入 / 逐条开关 / 用户输入改写 / 模型输出只改显示 | 🟨 引擎对夹具逐条相等，**待真机** |

## 正在做的 / 下一步（按顺序）

**两条线并行，但一次只推一条：**

### A. 结构整理（用户 2026-11-24 提出，计划见 [structure-audit.md](structure-audit.md)）

1. **第 1 轮（低风险）**：`app.tavern` 拆包（`ui.theme` / `ui.glass` / `ui.component` /
   `ui.nav` / `screen` / `vm`）+ 抽 `ConfirmDeleteDialog`（现在 **7 处重复**）与
   `statusBarTop()`（7 处重复）+ 修 2~3 处表述漂移的注释。
2. **第 2 轮**：debug-only 的 `GalleryScreen`（材质/动效/行样式预览，可当调参台）+
   把历史踩坑迁到 `docs/dev/pitfalls.md`，把 `app/AGENTS.md` 从 21KB 瘦到 ≤12KB。
3. **第 3 轮（中高风险）**：拆 `ChatViewModel`（1305 行 → Session/Profiles/Settings/Cards 四个 controller）。
4. `:ui` 独立 Gradle 模块**暂时不做**（要开第二个 app 或要给别人复用时才值得）。

### B. 功能线（结构整理之后回到这里）

1. **P1-5 预设导入**：ST 预设 JSON → 采样参数 + `main` + `prompt_order`（要先录 `fixture:P1-5-preset`）
2. **D 批：三级继承**（会话 > 角色卡 > 全局，每项可"跟随"；解析器放 `:core` 带单测）
3. **P0-6 消息操作**：swipe / 重新生成 / 编辑 / 删除单条（要先用原版录 swipe 夹具）
4. **世界书剩下的**：sticky 计时状态落库、`@@` 装饰器、outlet 位置
5. **C 批模型档案**：C1 数据层 + C2 界面都完成，**待真机验收**

## 2026-11-24：页面转场那一夜（五个 bug，全部结案）

用户这一夜连着报了五个现象，修完它才真正"对"。根因都不是"曲线/时长调错"，按发现顺序：

| 现象 | 根因 | 在哪 |
|---|---|---|
| 返回时一级页盖在二级页上 | `AnimatedContent` 两层的 zIndex 取自**同一个** `ContentTransform`，单一值必然打平，打平后 target 永远后放置 = 最上面 | 换成自写 `PageStack` |
| 换成 PageStack 后"退场页凭空消失" | 离场页继续读实时 `UiState`，而返回时 `closeDetail()` 已把 `detailCardId` 清掉 → 它自己的 `?: return` 渲染空；**层在、内容没了** | 按页状态快照 |
| "页面先跳到 -1/4 再复位""整屏闪一下" | 目标变化的那一帧 `pages` 还是旧的、`progress` 还是上一轮的 1 → 离场页按终态渲染一帧 | `running` 门 + 写顺序 |
| 卡片"由浅到深"再显出来 | `Modifier.animateItem(fadeInSpec=…)` 的 appearance 动画：换页时子树重建、网格被测两遍，第二遍所有 item 都算"第一次见" | 两张列表只留 `placementSpec` |
| 切 tab 有 157px 横移 | 这是 tab 层自己 `AnimatedContent` 的 `w/8` 位移 + 180ms 淡入，**不是 bug**，没动 | — |

**教训（写进 app/AGENTS.md 了）**：
1. 页面读全局状态时，"离场页"必须拿快照，否则状态已前进、页面渲染空；
2. 动画的第一帧不能用上一轮的终态，否则必闪一帧；
3. 判断"叠放对不对"用录屏抽帧 + 逐列差异，不要靠描述猜（这次就是靠它把"消失"和"被盖住"分开的）。

## 已知问题 / 风险（别当成已完成）
- **页头色罩浓度**：现在是 `HeaderScrimAlpha = 0.94`（`Style.kt`），一条纯线性渐变、不许加拐点；
  0.62 试过太透。要调只改这个常量，别改渐变形状。
- **按压 1.25 倍是否过大未确认**：放大倍数与弹簧是两件事，觉得太大改 `Glass.kt` 的 `PressScale`。
- **串流抽动**：`reverseLayout` 修完至今用户没再复现，但没专门确认过；再报就导日志看请求/完成时间点。
- ~~转场做不到"当前页向右抽走"~~ **已解决（2026-11-24）**：不用 `AnimatedContent` 了，改自写 `PageStack`，
  返回时深的那页盖在上面整屏滑走；用户已验收。
- **弹层没有拖拽关闭**（只能点遮罩或返回键）：要做就照动效文档的两段式（跟手段 1:1 + 松手弹簧）。
- 弹层是自绘的，所以 insets / 返回键 / 连点都要自己管；改这块要过一遍 `ActionSheet.kt`。
- 世界书只存"勾选"，**不影响提示词**（引擎未实现）。
- 日志只到"事件级"：不含 HTTP 响应原文；进程被系统杀掉时抓不到（导航已补 `tag=nav` 打点）。
- **未接预测式返回**：Android 13+ 的返回手势在松手后才播动画，拖动时看不到上一级预览（用户暂未要求）。
  自写 `PageStack` 之后技术上可做（要按手势进度驱动两页的 translationX），但没排期。
- 本机编不了 Android 模块：所有 Compose 改动只能靠 CI 编译 + 真机验。
- **CI 编译内存**：`gradle.properties` 的值是给本机（1.7 GiB）调的，workflow 里 checkout 后会 sed 成
  `-Xmx3g -XX:MaxMetaspaceSize=1g`。加依赖后 Metaspace 会顶不住，别把那两行 sed 删掉。
- **玻璃已覆盖底栏 + 页头**：底栏胶囊与页头（大标题 + 右侧玻璃胶囊）都走 `GlassSurface`；
  页头是浮层、画在录图层之外（否则胶囊会采到上一帧的自己）。卡片已改（磨砂条用预解小图），
  聊天气泡仍是旧样式。
  模糊是每帧离屏绘制，滚动是否掉帧待真机；掉帧就在 设置→外观→视觉效果 切「轻量」。
- **把内容录进图层要用 `DrawScope` 的 `record` 扩展**（`layer.record(size) { drawContent() }`）：
  四参数成员版只改图层自己的 `DrawContext`，`drawContent()` 会照旧画到主画布 —— 屏幕正常、图层空、底栏画了个寂寞。
  之前三版玻璃（Haze / RenderEffect / 降采样）全栽在这一行上，不是系统不支持 `RenderEffect`。
- **玻璃模糊必须挂在节点上**（ADR 0006）：`renderEffect` / `alpha` 这类图层属性是渲染时读的，
  在 draw 块里「设了用完就清」等于没设 —— 之前两台真机都只看到半透明就是这个原因，不是系统限制。
  连带：模糊节点要比被糊的区域大一圈（离屏缓冲边缘会 clamp 成直纹）；`positionInWindow` 要量在没有
  padding 的那层上。诊断看 `AppLog` tag `glass` 的两行（sdk / 硬件加速 / 半径，底栏 / 内容 / 尺寸）。

## UI 第二轮（2026-11-23 白天，全部走 CI 出包）

| 块 | 状态 |
|---|---|
| 世界书条目页：设置→世界书→点书名，做成**真三级页**（`Destination.WorldBookEntries`，走导航转场） | ✅ 已发 |
| 全局世界书：可看条目、**逐条开关**（应用内覆盖，不改导入文件） | ✅ 已发 |
| 胶囊暗带 | ❌ **整段删除**（决策见 app/AGENTS.md：它改了感知边界，和投影/反馈的弧线对不上） |
| 按压反馈 | 整块玻璃放大 1.25；**弹簧**（按下 0.6/800、松开 0.8/380，抄 M3 Expressive）；**吃 interaction 流**（布尔状态会吞掉同一帧内的快速点按）+ 最短 90ms；涟漪关掉 |
| 页头 | **只有聊天页磨砂**（`frostedHeader = true`）；其余是**一条纯线性色罩**：`headerScrim()` 0.94 → 透明，盒子多 28dp 收尾（加"饱和平台"会被看成阶梯） |
| 状态栏 | 与页头连成一片（内容铺满整屏，滚到页头下面被糊掉） | ✅ 用户确认真机生效 |
| 液态玻璃 | **AGSL 真折射，所有玻璃表面默认走它**（`LiquidGlassSpec.Default`）。最终参数：折射带 4.5dp·0.22 + 色散 0.03 + 上下打光细光线（`0.28+0.72·|n.y|`，宽 1.6dp）+ 同宽柔光 0.25 + 左右内阴影 0.02；面板白 0.22（跟主题 `surface`）；投影四周均匀 14dp·0.10 不下沉。详见 [ADR 0007](../adr/0007-liquid-glass-agsl.md) |
| 发送钮 | 主色 20% / 图标 72% 半透；失败或低于 API 33 退回描边样式 |
| 视觉效果档 | 玻璃档门槛 **API 33**（`glassSupported`）；低于 33 只开放轻量，设置页不显示玻璃选项（`VisualEffects.effective()`） |
| 键盘 | 内容底部让位取 max(导航栏, 键盘)（之前相加，多抬一个导航栏） | ✅ 用户确认可拖到胶囊上方 |
| 橡皮筋滚动 | `:app/RubberOverscroll.kt`（2026-11-23 晚按官方 `OverscrollEffect` 思路重写）：**累计 raw**（不逐帧阻尼）+ 视觉 = 橡皮筋公式 `raw·R·K/(R+K·raw)`（R 固定 400dp、K=0.6，渐近、无硬墙）+ 松手用 `spring(1.0, 700)` 把 raw 弹回 0；**raw 上限** ≈444px（视觉最大 64dp 反解）；**fling 与手势用 `NestedScrollSource` 区分**；打断用 `settleJob.cancel()` + 代数守卫；日志 tag `rubber`（`raw=` / `visual=`） |

## 2026-11-23 晚：液态玻璃那一轮（全部走 CI 出包）

| 块 | 结果 |
|---|---|
| 液态玻璃（AGSL） | 发送钮试点 → **推广到所有玻璃表面**；`LiquidGlass.kt` 是唯一实现，参数在 `LiquidGlassSpec` |
| 玻璃档门槛 | 31 → **API 33**（`glassSupported`）。低于 33 不退回旧彩色玻璃，直接降成轻量；`VisualEffects.effective()` 是唯一入口 |
| 页头（方案 A） | 直角矩形条 + 磨砂板（`faceWhite 0.80`）+ 只下沿直亮线（`rimBottomOnly`）+ 只下沿投影（`shadowBottomOnly`）；不再做折射（`refraction=0`） |
| 深色模式 | 玻璃面颜色改成跟主题（`uFaceColor = surface`），不再写死白 |
| 图标 | 换成女巫猫；自适应前景按 72/108 安全区，外圈补插画边缘紫 `#42107F` |
| 设置一级页 | 补上橡皮筋（`rubberOverscroll` → `verticalScroll`） |
| 遮阳/几何坑 | 高光"纯白描边"→ 内缩半个线宽；"看不出来"→ 泛光按 band 铺了 15px；"同心圈"→ 多层描边叠在有色面上 |

## 开发回路（这台机器的现实）

- 本机能跑：`tools/test-core.sh`、`tools/check-*.sh`、Playwright 录原版夹具、HTML 出设计样张
- 本机不能跑：`assembleDebug`、模拟器、`:app` 编译
- CI 结果**每 40–45 秒轮询一次**（不要 sleep 300 干等）；失败日志从 `ci-log-jvm` / `ci-log-android`
  release 的 `build.log` 取，本地下载后 grep `^e: `
- 发 APK：`git tag apk-mN && git push origin apk-mN` → 等 `apk-latest` 的 body 出现新 sha →
  下载 → 用 **dex 里的 `BuildConfig.BUILD_ID`** 核对是不是这次的包（大小经常撞车）→
  `tools/verify-apk.sh` 对指纹 → 复制到 `~/.dsh-qqbot/media/` 再 `qqbot_send_file`
- 指纹：`55:E0:C6:E2:…:0B:E7`（见 `keystore/README.md`）
- 已踩的编译坑：Kotlin 块注释可嵌套、重复 import、图标不在 core、`Icons.Filled.X` 扩展属性不能全限定、
  `by mutableStateOf` 需 `setValue` import。前两个已进 CI 铁律检查（10 秒内挡下）。

## 关键设计决定（一句话版）

- 兼容 = 文件格式往返 + 提示词逐条等价；不改写卡内容、不在发送前加工提示词。
- DB 是运行时真相，JSONL 只做导入导出（ADR 0003：原生 SQLite，不用 Room）。
- UI 方向「C · Dense Ink」：细线分隔、1px 描边、信息密度高；主题墨紫 `#5C3BAE`（深色 `#C6B4F5`）。
- 不引第三方 UI 库（玻璃模糊是**自研**的，见 [ADR 0005](../adr/0005-glass-without-haze.md) /
  [ADR 0006](../adr/0006-glass-blur-on-node.md)；曾引 Haze，已撤）、
  不引 navigation-compose、不引 Coil/material-icons-extended。
