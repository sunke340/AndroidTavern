# 接手卡（2026-11-25 10:50）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**：
> 现状 → [status.md](status.md)；规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)；
> 进度 → [compat-matrix.md](../compat-matrix.md)；历史与踩坑 → [pitfalls.md](pitfalls.md)。

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容它的文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)）。
- **基线**：代码 `cc7ecd6`（tag `apk-m170`）/ 交付包 `artifacts/app-v139-cc7ecd6.apk`；
  `artifacts/` 里 v118–v139 都验过签名与 dex `BUILD_ID`。**真机验收过的最后一个**仍是 `app-v117-0631bff.apk`。
- **没有悬着的事**：10 条 UI 需求 + P0-6 + 后半夜第二批（装饰器/搜索/导入导出/卡编辑/system_prompt）
  + **M3 回执的 21 条修复**（2026-11-25 早上）都已落码出包，等真机复核。
- **下一件事**：按 [device-verification.md](device-verification.md) 的 **M6** 表逐条点。
  再往后是世界书 outlet、卡库分组。
- **铁律**：core 纯 JVM；兼容行为必须带 fixture；不猜原版；本机不构建；**能复用先复用**。

## 一、这一夜做了什么（10 条需求 + P0-6）

| 需求 | 实现要点 | 关键决策 / 依据 |
|---|---|---|
| 1 抽屉把手拖拽 | 把手区整宽 28dp 可拖，跟手用 `translationY`；松手按"位移 > 弹层高 **1/2**"收回（不看速度），否则临界阻尼弹回 | 不改高度（拖动中重排会换行）；共享组件，全 App 生效。1/4 → 1/2 是用户 2026-11-25 早上定的（之前太容易误关；下甩即收也去掉） |
| 5–8 输入区 | 一个玻璃胶囊：收起=单行（发送在胶囊内右端），聚焦=向上加一行（左模型右发送），文字最多 5 行，发送后收起 | 底部让位从常量 80dp 改成**实测高度**，否则翻倍后最后一条被盖 |
| 9 模型钮 | 左下 40dp 玻璃圆钮，显示当前档案首字；抽屉列档案，顶部是**思考强度滑块** | 写**会话级**覆盖，不动全局默认；滑块同款也在会话配置页与卡配置 tab（卡级可再盖一层） |
| 2 三级配置 | `ConfigOverride`（null=跟随）+ `session:<id>` / `card:<id>` 存 SharedPreferences；会话配置页从聊天 ⋮ 进，卡配置在详情「配置」tab | 解析只在 `UiState.effective*` 这几个 getter；发送/世界书/正则/连接参数全读它们 |
| 3 开场白 | 卡的全部开场白进 `UiState.greetings`；聊天页在"没说过话 + 不止一条"时于气泡**下沿右侧**放 `‹ N/M ›`，选中=改写第一条消息；详情页 tab 同款箭头翻页，默认那条也占一个「当前默认」灰胶囊 | 用户要的形态（箭头贴气泡、不要页头按钮）；B 策略=只有空会话能换；下沿是因为反排锚点在底部 |
| 4 详情页 | 保留 TabRow，卡头改**海报版式** 260dp：磨砂底（`CardFrost` 拉满）+ 完整竖图 `Fit` + 渐变压名字/元信息（`CardHero`） | 用户明确"只重做卡头与信息"；卡是竖图，横条 `Crop` 一定截（2026-11-25 真机报的） |
| 10 玻璃确认框 | `AlertDialog`（独立窗口采不到内容层）→ 控制器 + 骨架宿主 + `LiquidGlassSpec.Sheet` | 8 处调用点签名不变，内部换实现 |
| P0-6 | **气泡底下一排按钮**（长按改为选字）：助手=复制/编辑/[重新生成]/[继续]/删除，用户=撤回/复制/编辑/删除；swipe `‹ N/M ›` 翻页 | 重新生成=新增 swipe 备选；继续=末尾加原版那条 system 提示；撤回=`rewindToMessage`（删这条及之后、正文回输入框）；备选存 `message_swipes`（DB v3） |
| 世界书 `@@` 装饰器 | `@@activate` 不看关键字、`@@dont_activate` 一律不激活；解析照抄原版（未知装饰器行也剥掉、`@@@` 转义跳过） | fixture:P1-3-decorators 逐字相等 |
| sticky / cooldown 落库 | 计时状态按会话存（`LorebookScanStateCodec`） | 原版存在 `chat_metadata.timedWorldInfo` |
| 卡库搜索 + 标签筛选 | 名字/简介/标签过滤；标签进卡列表缓存，不再解 PNG | P0-8 |
| 聊天导出/导入 | 原版 `.jsonl`：导出带 swipe 备选；导入单事务写库、日期原样保留 | P0-7 |
| 卡编辑写回 PNG | 六个文本字段 + 备选开场白增删改；`.bak.png` 备份 + 临时文件原子替换 | P0-3 的写回单测 |
| 卡级 `system_prompt` / `post_history_instructions` | 顶替预设的 main / jailbreak（位置不变） | fixture:P0-5-sysprompt |

## 二、三级配置（会话 > 角色卡 > 全局）

- 数据：`data/config/ConfigOverride`（profileId / reasoningEffort / presetName / worldBookNames /
  regexIds / greetingIndex，**每项 null = 跟随上级**）。空集 ≠ null（"全都不要"是合法选择）。
- 存储：SharedPreferences，作用域 `session:<id>` / `card:<id>`；删会话时顺手清。
- 解析：`UiState.effectiveProfileId / effectiveReasoningEffort / effectivePresetName /
  effectiveWorldBookNames / effectiveRegexIds / effectiveRegexScripts`。**别在别处再写 `x ?: y ?: z`。**
- 切换会话：`openSessionInternal` 按覆盖重放连接参数（baseUrl/model/apiKey）与预设（promptOrder/提示词/采样）。
- 界面：会话配置 / 卡配置的四行点进去是**整页**（`ConfigPickerScreen` + `Destination.ConfigPicker`），
  单选/多选都在原地生效、不自动返回，当前项用 `ActiveMark`；**不再有配置抽屉**。
  唯一抽屉是聊天页左下角快速换模型（设置页同款列表 + 思考强度滑块，选中不关）。

## 三、聊天输入区几何（用户定的，别再改）

- 收起：单行，文字 + 发送（发送**在胶囊里**）。
- 聚焦：高度翻倍 → 上行只放输入，下行左模型右发送；展开/收起用
  `expandVertically`/`shrinkVertically` + 淡入（用户 2026-11-25 要"形变"，不再瞬变）。
  玻璃着色器会随节点尺寸重建，所以只允许这种**一次性**过渡；拖拽/连续改尺寸仍然禁止。
- 文字超过一行继续长高，最多 5 行；发送后清焦点收起。
- 列表底部让位读**实测像素高度**（`onHeightChanged` → `contentPadding`），不是常量。

## 四、消息操作（P0-6）

| 动作 | 语义 | 证据 |
|---|---|---|
| 重新生成 | 不删行：新文本作为**新的 swipe 备选**追加，旧的可翻回 | `fixtures/expected/P0-6-swipes.json` |
| 继续 | 请求末尾追加 system `[Continue your last message without repeating its original content.]`，结果接在原消息后面 | `fixtures/expected/P0-6-continue.json`（落盘 `ok ok`） |
| swipe | 只对最后一条助手消息（原版 `isMessageSwipeable`）；备选 = `message_swipes` 表 | 同上，原始 chat 在 `fixtures/chats/` |
| 编辑 / 删除 | 编辑同步改当前 swipe 那一份；删除走玻璃确认框，备选级联删 | 手工 |
| 撤回（新） | `rewindToMessage`：删掉这条用户消息及之后全部消息，正文放回输入框（rewind） | 手工 |
| 入口 | 气泡底下**一排图标**（无文字）；长按 = 选字；开场白气泡不挂 | 用户 2026-11-25 |

- `send()` 与上面三件事共用 `beginCompletion(history, userText, replacing, continuation)`；
  `userText == null` = 不追加用户消息。**别再复制一份两百行的发送逻辑。**
- 失败路径：`ReplaceTarget.append=false` 还原被撤下的那条；`append=true` 也是还原（继续不产生新行）。

## 五、待真机验（M6，见 [device-verification.md](device-verification.md) 的 M6）

一句话版：输入区 400/300ms 形变 + 键盘收起复原 → 配置页跟随行常驻 → 图标不被遮 →
撤回删双方并重建列表 → 保存是玻璃确认框（取消/新建/覆盖）。


1. **抽屉**：长按行 → 拖把手跟手、过半收回、否则弹回。
2. **输入区**：聚焦翻倍、多行长高、发送后收起、最后一条不被盖。
3. **模型**：左下钮首字正确；抽屉换模型/思考强度；日志 `tag=config`。
4. **会话配置**：改一项后行尾"生效值 · 来源"变「会话」；切回跟随变「角色卡/全局」。
5. **卡配置**：详情 → 配置，四行都生效（新开会话验证）。
6. **开场白**：空会话翻页即换第一条；说过话后箭头消失；详情页「设为默认」影响新会话。
7. **详情页**：大图卡头 + 名字压在图上、不被渐变糊掉。
8. **确认框**：任意删除是玻璃、点遮罩/返回键能取消。
9. **消息操作**：长按气泡四种动作；重新生成后 `‹ 1/2 ›` 能翻回；继续后文本接在末尾。

## 六、下一步优先级

1. 上面 21 条真机验收（最可能有问题的是输入区让位与抽屉拖拽手感）。
2. 世界书 **outlet**：还没做，而且**缺夹具** —— 原版只有预设里出现 `{{outlet::名字}}` 宏才会展开，
   现有默认预设里一个都没有；要做得先给 capture 加"选预设"并造一份带 outlet 宏的预设。
3. 卡库**分组**（原版的文件夹）未做。
4. C 批模型档案真机验收。
5. `forbid_overrides`（预设段是否允许被卡覆盖）与"按角色禁用某段"这两个前置条件没做 ——
   现在的顶替是无条件生效的（夹具里原版也是无条件）。

## 七、这台机器与出包（每次都要用）

- **本机编不了 `:app` / `:data`**：只能跑 `tools/test-core.sh`、三个 `check-*.sh`。
- **出包循环**：
  1. `git commit` → `git tag apk-mN && git push origin HEAD apk-mN`
  2. **等 run 真的 completed/success**（不是 `poll-ci.sh` 说 READY —— 见下）
  3. 下载 `releases/download/apk-latest/app-debug.apk`
  4. **验 dex 里的 40-hex `BUILD_ID`**（在 `classes17.dex` 里，`unzip` 没装就用 python `zipfile`）
  5. `tools/verify-apk.sh <apk> 55:E0:…:0B:E7`
  6. `cp` 成 `artifacts/app-vNNN-<sha7>.apk`
- ⚠️ **`poll-ci.sh` 会假 READY**：workflow 先更新 `apk-latest` 的 body（带新 sha）再上传资产，
  脚本只看 body。2026-11-25 实测过一次"READY 但资产还是上一次的"。**唯一判据是下载后的 `BUILD_ID`。**
- **日志**：设置 → 关于 → 导出日志（SAF）。

## 八、日志 tag 速查

| tag | 看什么 |
|---|---|
| `nav` | 转场两行：`转场预热 … 新页首帧 Xms` + `转场 … 帧 N，最长帧 Mms` |
| `scroll` | 聊天跟随：`模式=A/B/C`、`补偿 +Npx`、`边界 …` |
| `config` | 三级配置：`会话模型 id=…`、`会话预设=…`、`卡级模型 …`、`开会话配置` |
| `chat` | 发送/完成/失败；`换开场白 index=…`、`重新生成：…`、`swipe 切到 N/M`、`继续：…` |
| `rubber` / `glass` | 橡皮筋回弹、玻璃档与着色器 |
| `startup` / `wi` / `card` | 启动各步、世界书解析、读详情耗时 |

## 九、文件地图（这一夜动过的）

| 想改什么 | 去哪 |
|---|---|
| 三级配置（数据/页面） | `data/config/ConfigOverrideRepository.kt`、`SessionConfigScreen.kt`、`ConfigPickerScreen.kt`、`ConfigSheets.kt`（共用行） |
| 输入区 / 模型钮 / 消息操作入口 | `ChatScreen.kt`（`ChatInputBar` / `SendStopButton` / `ModelButton` / `EditMessageDialog`） |
| 消息操作与发送 | `ChatViewModel.kt`（`beginCompletion` / `ReplaceTarget` / `regenerateLast` / `continueLast` / `selectSwipe`） |
| 开场白 | `GreetingSwitch.kt`（箭头与 `‹ N/M ›`）、`CharacterDetailScreen.kt`（`GreetingTab`）、`ChatScreen` 的 greeting item |
| 抽屉把手拖拽 | `ActionSheet.kt`（`GlassSheetPlate`） |
| 玻璃确认框 | `ConfirmDeleteDialog.kt`（控制器 + `GlassConfirmHost`）、两个骨架里各挂一次 |
| 思考强度滑块 | `ThinkingEffort.kt`（`ThinkingEffortSlider`）、`ConfigSheets.kt`（抽屉顶部）、`SessionConfigScreen.kt`、卡配置 tab |
| 卡头大图 | `CardAvatar.kt` 的 `CardHero`（磨砂底 + 竖图 Fit）、`CharacterDetailScreen.kt` 卡头 |
| swipe 备选表 | `TavernDatabase.kt`（v3）、`SqliteChatRepository.kt`（`swipes` / `setSwipes`） |
| 卡库搜索 / 标签 | `CharactersScreen.kt`（`CardSearchField` / `TagFilterRow`）、`CardStore` 的 `.meta` 缓存 |
| 卡编辑（写回 PNG） | `CardEditScreen.kt`、`CardRepository.saveCard` / `CardStore.saveCard` |
| 聊天导出 / 导入 | `ChatViewModel.exportChat` / `importChat`、`ChatRepository.importSession`、`:core:chat-format` |
| 世界书装饰器 | `core/lorebook/WorldBookDecorators.kt`（解析）+ `LorebookScanner` 里的两条判断 |
| system_prompt 顶替 | `core/prompt/PromptBuilder.kt` 的 MAIN / JAILBREAK 分支 |

| 本质回归点 | `artifacts/app-v117-0631bff.apk`（串流跟随那一轮验收过的包） |

## 十、这一夜踩的坑（一句话）

1. **切片替换函数体时先确认另一个函数在它前面还是后面**：`ScrollArea` 排在 `GreetingTab` 之前，
   用 `index()` 取到的是更靠前的位置 → 整段被复制，CI 报 `Conflicting overloads`（v120 修）。
2. **`poll-ci.sh` 的 READY 不是"包已更新"**，必须验 `BUILD_ID`。
3. **Shell 里 `git commit -m "...\"继续\"..."`** 会被引号截断；消息里有引号就写文件 + `-F`。
4. **玻璃着色器随节点尺寸重建**：别给玻璃表面加逐帧尺寸动画（输入区展开用"只淡入"）。
5. **`AlertDialog` 永远做不成液态玻璃**（独立窗口采不到内容层）—— 用控制器 + 骨架宿主。
6. **用了新的 core 模块，记得加进 `app/build.gradle.kts`**：本机不编 `:app`，只有 CI 报
   `Unresolved reference`（导出聊天就这么栽过一次）。
7. **提示（Snackbar）要挂在页面栈之上**：TabsRoot 会作为离场页留在组合树里，它那里的
   SnackbarHost 会把二级页的提示"吃掉"（画在被盖住的层上）。
8. **卡编辑保存要认"详情那张卡"的原始 JSON**：以前用 ViewModel 的 `card`（**会话**那张），
   而 `loadDetail` 不刷新它 → 改 A 卡会把 B 卡的内嵌世界书写进去。现在 `detailRaw + id` 校验；
   另外卡目录扫描要排除 `.bak.png`（否则每次保存都多一张同名卡）。见 status 的「串卡 bug」。
9. **CompositionLocal 不能在提供者之前读**：页面在 `GlassDetailScaffold(...)` 之前读
   `LocalSheetController` 拿到的是没宿主的默认实例，`show()` / `ask()` 静默落空 ——
   2026-11-25 真机上一批"点不进去/长按没反应/确认框不弹"全是这一条。页面要在顶层发请求就自己
   `remember { SheetController() }` / `ConfirmController()` 传进骨架；缺宿主现在会打
   `ui …请求没有宿主` 日志（见 pitfalls）。
