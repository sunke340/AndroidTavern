# 项目状态快照（写于 2026-11-24 09:05）

**用途**：换会话/压缩上下文后，先读这一页就能接上。更细的规则在 [AGENTS.md](../../AGENTS.md)、
[app/AGENTS.md](../../app/AGENTS.md)，进度唯一真相在 [compat-matrix.md](../compat-matrix.md)。

## 当前基线

- 代码：`d312a42`（tag `apk-m102`；兜底回退点：`apk-m48` 是玻璃版，`apk-m27` 是纯 C2 版）
- 状态：CI 全绿；本机 `tools/test-core.sh` 全绿
- 已交付 APK：`app-v83-d312a42.apk`（签名固定，可覆盖安装；上一版 `app-v82-99ec1f5.apk`）
- 引擎/工具链：Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01 / minSdk 26 / targetSdk 35

## 接手先看这三件（2026-11-24 早）

1. **页面转场已验收**（`99ec1f5` / `apk-m100`）：不用 `AnimatedContent`，改自写
   [PageStack.kt](../../app/src/main/kotlin/app/tavern/PageStack.kt)；五个 bug 的根因在
   [app/AGENTS.md](../../app/AGENTS.md) 的「页面转场」一节 —— **动这块之前先读那一节**。
   两条一次性诊断探针（`tag=nav` / `tag=ui`）**已在 2026-11-24 删掉**。
   **列表改版刚做完（`d312a42` / `apk-m102`），待真机确认**，见下面"列表改版"一节；
   动列表之前先读 [app/AGENTS.md](../../app/AGENTS.md) 的「列表：一套行系统」。
2. **橡皮筋的 `flingEaten` 修复（`1675887`）还没真机验证**：拉到底 → 猛甩向上 → 撞顶后应立即回弹。
   日志判据（tag=`rubber`）：正常是 `回弹 start` 只出现一次、紧接 `fling 撞上限，残余惯性丢弃`，
   然后 `回弹结束`；若 `回弹 start` 反复出现、中间夹 `回弹被打断 raw=-4xx`，说明还有第三个真因。
3. **两处没量过 / 没验收的观感**：
   - 液态玻璃已在所有玻璃表面生效，**没量过帧率**（底栏最贵，发黏就给它单独传 `liquid = null`）；
   - 页头"方案 A"（直角矩形 + 磨砂板 0.80 + 只下沿亮线与投影）几何已定，**用户还没最终点头**。

## 2026-11-24：列表改版（行系统 v2，待真机确认）

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

## 已经做完的（能装、能日常聊）

| 块 | 状态 |
|---|---|
| 卡：PNG 导入（SAF）、卡网格两列 3:4、详情四标签、卡内嵌书勾选、删卡连带删会话 | ✅ 真机可用 |
| 聊天：SSE 流式、开场白（first_mes 作为第一条消息）、气泡/整行卡片、停止保留半截 | ✅ |
| 持久化：SQLite 会话/消息、会话列表、新建（先选卡弹层）、长按删除（动作单+确认） | ✅ |
| 设置：一级/二级（模型/采样/外观/世界书/关于）、主题四套色板 + 深浅、Key 加密存储 | ✅ |
| 导航：`Destination` + 自写 `PageStack`，0–2 层，三档转场（标准/淡入/关闭）；返回时**深的那页盖在上面滑走** | ✅ 真机确认（2026-11-24 用户验收） |
| 列表：行系统 v2（分组圆角面板 + 内缩分隔线 + 按下即亮），会话加最后一条消息与分级时间，模型页整行切换 | 🟨 编译通过、已出包，**待真机确认** |
| 应用图标：黑猫月色 adaptive icon（前景 63% 安全区 + 模糊插画底） | ✅ |
| 日志：`AppLog`（内存+落盘）+ 崩溃兜底 + 关于页导出 | ✅ |
| 世界书引擎：关键字/常数/位置/深度/概率/四种 selectiveLogic/递归/包含组/sticky/cooldown/delay/预算 | 🟨 引擎 + 夹具全绿；App 已接线（卡内嵌书勾选真生效），**待真机** |
| 全局世界书：导入 `.json` / 启用停用 / 删除 / 与卡内嵌书一起扫描 | 🟨 代码完成，**待真机** |
| 正则脚本：导入 / 逐条开关 / 用户输入改写 / 模型输出只改显示 | 🟨 引擎对夹具逐条相等，**待真机** |

## 正在做的 / 下一步（按顺序）

UI 那条线（液态玻璃 / 页头 / 转场 / 动效 / 橡皮筋）已经收口，**回到功能线**：

1. **P1-5 预设导入**：ST 预设 JSON → 采样参数 + `main` + `prompt_order`（**还没做**，要先录 `fixture:P1-5-preset`）
2. **D 批：三级继承**（会话 > 角色卡 > 全局，每项可"跟随"；解析器放 `:core` 带单测；
   会话配置页就是聊天页 `⋮` 里那个置灰入口）
3. **P0-6 消息操作**：swipe / 重新生成 / 编辑 / 删除单条（要先用原版录 swipe 夹具）
4. **世界书剩下的**：sticky 计时状态落库（现在只在内存，重启重置）、`@@` 装饰器、outlet 位置
5. **C 批模型档案**：C1 数据层 + C2 界面都完成，**待真机验收**（建两个档案来回切、杀进程后 Key 还在）

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

- **半透明色罩的浓度没有真机确认**：取 0.62（对齐上一轮玻璃档 GlassBand 的 tint）。觉得太透就改
  `Style.kt` 的 `HeaderScrimAlpha`（轻量档早期用过 0.94）。
- **按压 1.25 倍是否过大未确认**：放大倍数与弹簧是两件事，觉得太大改 `Glass.kt` 的 `PressScale`。
- **串流抽动**：`reverseLayout` 修完至今用户没再复现，但没专门确认过；再报就导日志看请求/完成时间点。
- ~~转场做不到"当前页向右抽走"~~ **已解决（2026-11-24）**：不用 `AnimatedContent` 了，改自写 `PageStack`，
  返回时深的那页盖在上面整屏滑走；用户已验收。
- `ModalBottomSheet`（选卡、动作单）的 insets 与底栏叠加未在真机确认。
- 世界书只存"勾选"，**不影响提示词**（引擎未实现）。
- 日志只到"事件级"：不含 HTTP 响应原文；进程被系统杀掉时抓不到（导航已补 `tag=nav` 打点）。
- **未接预测式返回**：Android 13+ 的返回手势在松手后才播动画，拖动时看不到上一级预览（用户暂未要求）。
  自写 `PageStack` 之后技术上可做（要按手势进度驱动两页的 translationX），但没排期。
- 本机编不了 Android 模块：所有 Compose 改动只能靠 CI 编译 + 真机验。
- **CI 编译内存**：`gradle.properties` 的值是给本机（1.7 GiB）调的，workflow 里 checkout 后会 sed 成
  `-Xmx3g -XX:MaxMetaspaceSize=1g`。加依赖后 Metaspace 会顶不住，别把那两行 sed 删掉。
- **玻璃已覆盖底栏 + 页头**：底栏胶囊与页头（大标题 + 右侧玻璃胶囊）都走 `GlassSurface`；
  页头是浮层、画在录图层之外（否则胶囊会采到上一帧的自己）。卡片/聊天气泡仍是旧样式，下一轮继续。
  模糊是每帧离屏绘制，滚动是否掉帧待真机；掉帧就在 设置→外观→视觉效果 切「轻量」。
- **把内容录进图层要用 `DrawScope` 的 `record` 扩展**（`layer.record(size) { drawContent() }`）：
  四参数成员版只改图层自己的 `DrawContext`，`drawContent()` 会照旧画到主画布 —— 屏幕正常、图层空、底栏画了个寂寞。
  之前三版玻璃（Haze / RenderEffect / 降采样）全栽在这一行上，不是系统不支持 `RenderEffect`。
- **玻璃模糊必须挂在节点上**（ADR 0006）：`renderEffect` / `alpha` 这类图层属性是渲染时读的，
  在 draw 块里「设了用完就清」等于没设 —— 之前两台真机都只看到半透明就是这个原因，不是系统限制。
  连带：模糊节点要比被糊的区域大一圈（离屏缓冲边缘会 clamp 成直纹）；`positionInWindow` 要量在没有
  padding 的那层上。诊断看 `AppLog` tag `glass` 的两行（sdk / 硬件加速 / 半径，底栏 / 内容 / 尺寸）。

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
