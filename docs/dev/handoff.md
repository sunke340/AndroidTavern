# 接手卡（2026-11-25 18:40）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**：
> 现状 → [status.md](status.md)；规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)；
> 词表（怎么称呼界面上每个东西）→ [ui-map.md](../ui-map.md)；
> 必须成立什么 + 待做的防回归计划 → [ui-invariants.md](../ui-invariants.md)；
> 历史与踩坑 → [pitfalls.md](pitfalls.md)。

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容它的文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)）。
- **基线**：代码 `e13c004`（tag `apk-m188`）/ 交付包 `artifacts/app-v157-e13c004.apk`；
  上游 `7dde66c` / `v156`（M14 通过）。
- **在做的活**：用户 2026-11-25 晚提的 **16 条需求批**，口径与排期全在
  [plan-req-2026-11-25.md](../plan-req-2026-11-25.md)。**A 批（离场与手感）已实现，待 M13 验**；
  A / B 批已真机确认；C 批（条目全文 / 置顶 / 搜索）已实现，待 M15。下一批 D（配置能力）。
- **真机验过的最后一段**：`v153`（2026-11-25 晚，M12 通过：三处补挂的橡皮筋）。
  页头材质与首项留白那一系列（`8ee1b72` → `df71772`）已在 v150 结案。
- **铁律**：core 纯 JVM；兼容行为必须带 fixture；不猜原版；本机不构建；**能复用先复用**；
  **说界面上东西时用 `docs/ui-map.md` 的名字，对不上就先问、不许猜**。

## 一、这个会话（2026-11-25 下午—傍晚）做了什么

### 1. 选字 bug 查到底（四轮，前三个判断都是错的）

用户报"气泡内文字不能长按选择"。**先记结论，别再走回头路**：

| 轮次 | 我当时的判断 | 真相 |
|---|---|---|
| 1 | v142 给气泡传了 `selectable = pageIsCurrent`，静态 `CompositionLocal` 留了陈旧 false | **错**（v143 把开关整个删了仍然不行） |
| 2 | 加诊断探针，量"按下落在哪个矩形" | 得到事实：**最新那条消息高约 18900px**（≈5400dp ≈269 行，两套数据交叉验证） |
| 3 | 探针下沉到 `SelectionContainer` / `Text` | 事实：局部 y≈17k 处**只有气泡这一层收到按下**，容器与正文都没收到 → 命中测试被截断 |
| 4 | 真因 | **Material3 `Surface` 内部的 `Modifier.clip(shape)`**：18900px 高的节点上，这层裁剪让子树在约 8k px 之后收不到触摸。渲染正常，所以"画面有字但按不动" |

修法：`MessagePanel`（`background` + `border` + `LocalContentColor`）替掉 `Surface`；`CardRow` 同步。
**然后**又暴露第二个问题：能选字了，但长按会让列表跳 427px —— 焦点 `bringIntoView` 的默认算法对
"比视口高、一端在视口内"的节点取 `trailingEdge - containerSize`。修法：聊天列表给
`LocalBringIntoViewSpec provides NoFocusScrollSpec`（恒返回 0）。**日志里 `偏移 0 → 427` 就是它。**

诊断探针（`SelectionProbe.kt` + `tag=sel`）已在 `6f4dd53` **整文件删除**；真机上不应再看到 `sel`。

### 2. 六条体验修正 + 页头（`6f4dd53` → `a5a568d` → `df71772`）

- 思考强度**只留会话 / 角色卡两级**，两级滑块都加了「自定义」第六格（默认串 `max`，
  判断靠"不在预设集合里"）；**档案（全局）层整项移除**（`ModelProfile.reasoningEffort` 字段、
  档案表读写、编辑页那一段、两处行副标题、请求回落全删）。
- 模型抽屉收回后把输入栏叫回来：`SheetSpec.onDismiss` → `dismiss()` 里回调 → 聊天页
  `requestFocus()` + `keyboard.show()`。
- 默认正则的清标签规则，标签名改成可选 → `</>` / `<>` 也清（补了 3 条单测）。
- 模型钮：淡紫 12% 主色底 + **满色**主色字（原透明）；流式停止钮：淡红 20% 底 + 红方块。
- **二级页页头恢复半透明**：观感来自"内容在它下面滚" → `contentTopInset = 0.dp` + 让位挪进滚动容器。
  随后用户报两处问题，`df71772` 还原：`DetailScrollUnderTop` **就是** `DetailHeaderHeight`（不再夹带
  呼吸值），列表页补回 `+ DetailHeaderHeight + Space.l`。**改之前的数值：Column 页 = 页头底边；
  列表页 = 页头底边 + 16dp。**
- 开场白气泡的动作行改为按 `isGreetingMessage` 隐藏（原来只有"多开场白"时才消失）。

### 3. 词表与规格（本轮最后一段）

- `docs/ui-map.md`：使用协议（对不上就问、不猜）· 维护规则（出现第二次才命名）· 页面树 ·
  区域词表（12）· 材质与共用组件表（约 35 行，含行系统 7 行）· 别名表 · 日志 tag。
- `tools/check-ui-map.sh`（**已进 CI**）：枚举页面都在词表里、词表里的名字都能 grep 到、
  40 个核心组件不许删行。
- `tools/gen-ui-map.sh`：从代码抽页面树/组件索引，改词表时照着抄。
- `docs/ui-invariants.md` + `tools/check-ui-invariants.sh`（**已覆盖 INV-02/03/05/07**）：
  十条不变量，第一批计划是把页头决定收成 `DetailHeaderMode`。

## 二、现在的状态

**真机已确认**：`v150`（M9/M9.1 三组全过）→ `v152`（M11：页头收口）→ `v153`（M12：三处橡皮筋）。
明细见 [device-verification.md](device-verification.md)。**页头材质与首项留白那一系列
（`8ee1b72` → `df71772`）已在 v150 结案**。

**待真机确认（v157）** —— 见 [device-verification.md](device-verification.md) 的 **M15**：
1. 条目全文页（点子条目进入、正文可复制）；2. 两处搜索；3. 会话置顶（重启仍在）；
4. 会话预览与卡简介不露标记。覆盖安装要验「老会话一条不丢」。

**已知未做**
- 十条不变量里 **八条已由 `check-ui-invariants.sh` 守**（INV-01/02/03/04/05/06/07/08），
  INV-09 走 `test-core.sh` 单测、INV-10 走 `check-ui-map.sh` —— 没有待做项。
- **角色详情页页头**没动：它的卡头是"常驻海报"（260dp 图 + TabRow），结构与其他二级页不同。
  要不要让它也从页头底下过，需要用户拍（要动就是单独一轮）。
- 世界书 **outlet**：还没做，且**缺夹具**（原版只有预设里出现 `{{outlet::名字}}` 宏才展开，
  现有预设里一个都没有；要做得先给 capture 加"选预设"并造带 outlet 的预设）。
- 卡库**分组**（原版文件夹）未做。
- `forbid_overrides`、"按角色禁用某段"未做（现在顶替是无条件生效的）。

## 三、下一步优先级

1. **v157 真机复验（M15）**：条目全文 / 搜索 / 置顶 / 预览与简介正则。**重点验覆盖安装**（DB 升到 v4）。
2. **需求批 D 批**：采样三级（2）、默认正则单行/占位符（6）、内置正则三级覆盖（15）。
3. 之后按同一份计划走 E（玻璃菜单）→ F（人设，夹具先行）。
4. 计划外的老账：世界书 outlet（夹具先行）、卡库分组、角色详情页头要不要穿。

## 四、这台机器与出包（每次都要用）

- **本机编不了 `:app` / `:data`**：只能跑 `tools/test-core.sh` 和 `tools/check-*.sh`。
- **出包循环**：
  1. `git commit`（消息里有引号/反引号就写文件 + `-F`）→ `git push`；
  2. `bash tools/poll-ci.sh <sha>`，**但要等 run 真的 `completed success`**；
  3. 下载 `releases/download/apk-latest/app-debug.apk`；
  4. **验 dex 里的 40-hex `BUILD_ID`**（`classes17.dex`；没有 `unzip` 就用 python `zipfile`）；
  5. `tools/verify-apk.sh <apk> 55:E0:C6:E2:19:20:61:92:51:12:04:E8:F8:AC:80:C6:19:44:91:54:26:5B:50:FA:D3:3A:B4:72:8B:96:0B:E7`；
  6. `cp` 成 `artifacts/app-vNNN-<sha7>.apk`，打 tag `apk-mN`，`git push origin <tag>`。
- ⚠️ **`poll-ci.sh` 会假 READY**：它只看 release body 里的 sha。**唯一判据是下载后的 `BUILD_ID`**。
- **CI 失败日志**：本机没有 `gh`；失败日志由 CI 发到 release ——
  `ci-log-android`（`:app` 编译错误）/ `ci-log-jvm`（纯 JVM 单测），取其中的 `build.log`：
  `curl -sL https://github.com/sunke340/AndroidTavern/releases/download/ci-log-android/build.log -o /tmp/ci.log`
- **真机日志**：设置 → 关于 → 导出日志（SAF）。插件/journald 无关，AppLog 只写应用内。

## 五、日志 tag 速查

| tag | 看什么 |
|---|---|
| `nav` | `转场预热 … 新页首帧 Xms`、`转场 … 帧 N，最长帧 Mms`、页面移除间隔 |
| `scroll` | 串流跟随模式 A/B/C、补偿落地、**`焦点滚动被拦下 …`**（选字不拖动列表的判据） |
| `config` | 三级配置写入：会话/卡级模型、思考强度、预设 |
| `chat` | 发送 / 完成 / 失败、换开场白、重新生成、swipe、继续 |
| `ui` | 输入区展开、确认框显示、离场收焦点与选区 epoch |
| `rubber` / `glass` | 橡皮筋回弹；玻璃档参数与逐像素校验 |
| `sel` | **已删除**（选字诊断用完即撤）；再看到说明装的是 v146 及更早 |

## 六、文件地图（这个会话动过的）

| 想改什么 | 去哪 |
|---|---|
| 消息底板 / 气泡 / 动作行 / 输入区 / 两个圆钮 | `ChatScreen.kt`（`MessagePanel` / `BubbleRow` / `CardRow` / `MessageActionRow` / `ChatInputBar` / `SendStopButton` / `ModelButton`） |
| 选字不拖动列表 | `ChatScreen.kt` 的 `NoFocusScrollSpec` + 列表上的 `LocalBringIntoViewSpec` |
| 页头材质与让位 | `DetailPage.kt`（`GlassDetailScaffold` / `DetailHeaderMode` / `contentSpacing` / `DetailHeader`） |
| 思考强度 | `ThinkingEffort.kt`（滑块 + 自定义）、`ConfigSheets.kt`（抽屉）、`ConfigPickerScreen.kt`（卡/会话的模型页）、`ChatViewModel.useSessionReasoning/useCardReasoning` |
| 抽屉收回回调 | `ActionSheet.kt`（`SheetSpec.onDismiss` → `dismiss()`） |
| 内置正则 | `core/regex/BuiltinRegex.kt`、`core/regex/InlineMarkup.kt`、单测 `BuiltinRegexTest` |
| 词表 / 不变量 | `docs/ui-map.md`、`docs/ui-invariants.md`、`tools/check-ui-map.sh`、`tools/check-ui-invariants.sh` |

## 七、这个会话踩的坑（一句话）

1. **"先改再验"代价很大**：选字那件事连着两轮改错方向；改成"先挂探针取数据、再动手"之后一轮就中了。
   探针只要**不 consume 事件**，是零风险的。
2. **渲染正常 ≠ 命中正常**：`Surface` 的 clip 只影响命中测试。判据是"某几层收不到 `按下`"，
   不是"画面有没有字"。
3. **超高节点是个雷**：单条消息能有 18900px（≈269 行）。任何会生成本地渲染层的 modifier、
   任何"整段正文一个节点"的做法，都要先想高度上限。
4. **焦点会自己滚动**：`SelectionContainer` 拿焦点 → `bringIntoView` → 列表跳
   （量 = `trailingEdge - containerSize`）。滚动容器里放会拿焦点的子节点时，先决定"焦点滚动要不要"。
5. **文档写错会反过来误导改动**：`app/AGENTS.md` 早写着"列表类二级页页头半透明、内容从底下穿过"，
   但代码里 11 个页面各传 `contentTopInset`、设置页用的是默认值 —— 我照着文档以为已经穿过。
   **这就是词表 + 不变量 + 脚本的由来。**
6. 其它照旧：`poll-ci.sh` 假 READY；引号/反引号会撕提交消息（用 `-F`）；
   `awaitFirstDown` 在 `foundation.gestures`；`positionInWindow` 是扩展函数要单独 import；
   页面里传 `0.dp` 要确认那个文件 import 了 `dp`（CI 报 `Unresolved reference 'dp'`）。
7. **一个常量能让整页内容静默消失**：`if (常量) A else B` 里常量一翻，另一支就成了死代码 ——
   编译不报、脚本看不见、回执也没点那两行（v149 / v150 卡配置两页，见 [pitfalls.md](pitfalls.md) 末条）。
   分发条件直接写页面判据，别先抽中转布尔。
