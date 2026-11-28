# `ChatViewModel` 拆分方案（3,792 行的上帝类）

> 2026-11-28 **第二次修订**（审查后）。上诉条款：本页只是方案，**未改一行代码**。
> 数字全部按当前 `HEAD` 重新统计；凡与 [architecture-audit.md](dev/architecture-audit.md)（2026-11-26 快照）
> 冲突的，以本页为准。修订过程与被推翻的旧结论见文末「二次审查记录」。

## 零、这一版推翻了上一版的什么

| 上一版的说法 | 按 HEAD 核实的结果 |
|---|---|
| B2「发送前准备在主线程，要挪走」 | **已修**：`c984f71` 把世界书扫描 + 提示词组装放进 `withContext(Dispatchers.Default)`（[ChatViewModel.kt:3162-3195](../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L3162-L3195)）。**这批不再是线程问题** |
| 「B1 抽 302 行三级继承规则」 | 302 行是整个区块（含采样辅助），`effective*` 只有 **73 行**，且**分三类**不是同一类规则（详见 B1） |
| 「B3 四份 × 25 行 SAF 重复」 | 真正重复的只有 **3 行**（4 处）+ `displayNameOf`（2 处），共可减 **~12 行** |
| 「B6 UI 下标 ↔ DB 行号强耦合」 | **已不存在**：消息操作全部按 `uiKey`/`dbId`（`deleteMessageAt(uiKey)`、`rows.firstOrNull { it.uiKey == … }`）。剩下的真问题是**全量载入** |
| 「B0–B5 后 ≤2,600 行」 | **不成立**，现实减量约 **−400 行**（3,792 → ~3,390）。详见「五、预算重算」 |
| 「18 个 effective getter 同一规则」 | 只有 **14 个**是解析链，其中 5 个是四层（采样）、9 个是二三层；另 4 个是派生计算，不该搬 |

## 一、现在到底多大（HEAD 口径）

| 指标 | 值 |
|---|---|
| 文件行数 | **3,793**（末行为空；有效内容 3,792） |
| 非空且非整行注释的行 | **3,334** |
| 类内 `fun` | **159** = 无修饰符（=public）**121** + `private` **38**（含 **3 个扩展函数**）；`override` **0** |
| `viewModelScope.launch` / `withContext(...)` | **85** / **105** |
| `_state.update {}` | **146** |
| `.copy(...)`（状态更新） | **194** |
| `UiState` 字段 / `effective*` getter | **72** / **18**（+1 个 `displayRegexScripts`） |
| UI 侧 `state.xxx` 引用 | **373**（`ChatScreen` 72 · `ConfigPickerScreen` 52 · `SettingsPages` 36 · `MainScreen` 31 · 其余分散） |
| 显式 `ChatViewModel.UiState` 类型注解 | **42** |
| 文件内嵌套类型 | 8 个 `data class`（`WorldBookView`/`ChatRow`/`UiState` …）+ 1 个顶层 `enum class BookBulkMode` |

> 上一版写的「156 个函数」是错的：那条正则要求 `fun 名(`，把 3 个扩展函数（`fun X.y(`）漏掉了。
> 本页口径：**逐行 `fun` 计数 + 按修饰符分类**，扩展函数与嵌套类型分开列，脚本化时照此实现。

文件内已有的 `// ---- 区块 ----` 边界（按 HEAD 重算，含行数）：

| 起始行 | 行数 | 区块 |
|---|---|---|
| 110 | 149 | 嵌套数据类型（`WorldBookView` / `ChatRow` / `UiState` 等） |
| 260 | 37 | 界面结构（导航） |
| 297 | 32 | 预设（设置→预设） |
| 329 | 134 | 采样参数（文本框保留原始输入） |
| 463 | 25 | 三级继承生效值总述 + 5 个 getter |
| 488 | 277 | 采样五项四层解析 + `SamplingPreset` 装配 |
| 765 | 300 | 卡 |
| 1065 | 228 | 会话 |
| 1293 | 99 | 导出 |
| 1392 | 64 | 开场白 |
| 1456 | 125 | 采样三级写入 |
| 1581 | 110 | 会话级配置 |
| 1691 | 51 | 角色卡级覆盖 |
| 1742 | 136 | 人设 |
| 1878 | 53 | 输入与请求（含 `persistConnection`） |
| 1931 | 132 | 卡编辑 |
| 2063 | 599 | 模型档案 |
| 2662 | 188 | 预设 |
| 2850 | 492 | 正则脚本 |
| 3342 | 228 | 发送收尾 |
| 3570 | 224 | 消息操作 |

## 二、约束（决定"怎么拆"，不是"拆不拆"）

1. **本机编不了 `:app`** → 每批必须小到一屏能看完的 diff，一批 = 一个提交 = 一个 tag。
2. **纯逻辑落 `core/*` 才有本机秒级反馈**（`tools/test-core.sh`）；`app` 的单测**未验证**会不会被
   `./gradlew test` 跑到（见「九、未验证事项」），所以**不把测试押在 `app/src/test` 上**。
3. **先例**：`RevealPacer` / `RevealCatchUp` / `PromptOrderCompat` 都从它里面抽出来、带单测、行为不变。
4. **回退成本**：一次 tag 构建 ≈ 2 分钟 CI + 十几 MB 下载（本机网络慢，实测 15MB 要 10~20 分钟）。

## 三、批次

### B0 · 尺寸棘轮（只加门禁，不改代码）

- `tools/check-viewmodel-size.sh`：输出三项并比对 `tools/viewmodel-budget.txt`
  —— **有效行数**（非空非整行注释）**+ public fun 数 + `_state.update` 数**。
  三项都设封顶，**下限只降不升**（每批成功后下调）。
- 口径固定为：正则在 `^    (public|private|internal|protected)? (suspend)? (inline)? fun 名`
  上匹配，**另行统计** `override`、扩展函数 `fun X.y(`、嵌套类型里的 fun，避免上一版的漏计。
- CI 的「铁律检查」job 里跑；输出里同时打印**文件行数**，便于人看趋势。

**这不是"架构改善"的证明**，只防"一边拆一边涨"。承认局限：把代码搬到新文件就能绕过 —— 所以
B5 的搬迁量会同步写进预算文件的注释里，避免数字游戏。

**判据**：故意加 1 行 → 红；撤掉 → 绿。
**风险**：极低。**减行**：0（不改产品代码）。

### B1 · 抽「生效值解析」到 core（只抽解析链，不抽派生）

`effective*` 的 18 个 getter 在 `UiState` 内部（纯数据类，无仓库访问），按 HEAD 实际分成三类：

| 类 | getter | 层级 | 备注 |
|---|---|---|---|
| **A 解析链（14 个）** | `effectivePersonaId` / `effectiveProfileId` / `effectivePresetName` | 会话 → 卡 → 全局 | 全局默认值是 `personaDefaultId` / `activeProfileId` / `activePreset` |
| | `effectiveReasoningEffort` / `effectiveWorldBookNames` / `effectiveRegexIds` | 会话 → 卡 | **没有全局层**（`null` 就是"跟随到底=不发"） |
| | `effectiveMessageStyle` / `effectiveBubbleFontSize` | 卡 → 全局 | 只有两层 |
| | `effectiveBuiltinDisabled` | 会话 → 卡 → 全局 | **空集 ≠ null**：空集 = 这一层明确全开 |
| | `effectiveTemperature` / `MaxTokens` / `TopP` / `FrequencyPenalty` / `PresencePenalty` | 会 → 卡 → **预设** → 全局文本 | 带 `toDoubleOrNull()`/`toIntOrNull()`；`maxTokens` 还要 `takeIf { it > 0 }` |
| **B 派生（4 个，不搬）** | `effectivePersona`（查表）/ `effectiveUserName`（依赖人设）/ `effectiveAuthorNote`（多字段 + 角色映射 + 默认值）/ `effectiveRegexScripts` + `displayRegexScripts`（过滤与合成） | — | 它们是**组合**而不是解析；搬去 core 只会制造包装层 |
| C 与配置无关（2 个） | `activeRoundId` / `streamingText` | — | 不动 |

- 新增 `core/config`（纯 JVM 模块）或放进现有 `core/model`：`EffectiveConfig` 数据类 +
  `resolve(session, card, sampling, global, texts)` 纯函数。**输入类型问题**：`ConfigOverride`
  现在在 `data`（`data/src/.../config/ConfigOverrideRepository.kt`，是**纯数据类**，全仓只有 3 个文件引用）。
  → 把该数据类**下沉到 core**（`data` 的仓库实现继续留在 `data`，接口引用 core 类型），依赖方向不变。
- `UiState` 里 14 个 getter 退化成一行转发（**名字与签名不变**，UI 零改动）。
- 单测（本机可跑）覆盖：四层优先级、`null` vs 空集、`maxTokens<=0` = 不限、
  `effectiveReasoningEffort` 无全局层、采样文本解析失败回落、`"off"` 语义。
  **另需钉住一处现存歧义**：`effectiveAuthorNote` 注释写"正文为空 = 这一层没有，继续往上找"，
  但代码是 `?:` 链 —— 会话层若显式存了空串会**截断继承**（持久化层目前把空串归一成 `null`，
  所以现实不可达）。抽纯函数时必须决定语义并写进测试，不许两头都不写。

**不变量**：优先级不变 / 空集语义不变 / 采样五项顺序不变 / UI 显示与请求实际值一致（用「查看请求」对照）。
**风险**：中低（规则有测试兜住，UI 不改）。**减行**：规则 73 行→ core，留 14 行转发 → VM **净减 ≈ 30~40 行**。
**收益主要是可测性，不是行数** —— 如果只想要行数，这批不值得做。

### B2 · 把 `scanWorldInfo` 参数收窄并落 core（原「挪线程」作废）

HEAD 事实：线程问题已解（`c984f71`），`scanWorldInfo` 已经**是纯函数**（参数全传、注释写明不碰
`worldInfoStates`/`globalBookEntries`），且运行在 `Dispatchers.Default`。剩下的问题只有两个：

1. 它吃整个 `UiState`（`snapshot: UiState`）—— 一个纯函数却依赖整个状态对象，无法在 core 里测；
2. 它待在 `:app`，本机跑不了。

- 在 `core/lorebook` 加一个形参对象（`WorldInfoInputs`：内嵌书、禁用/强制集合、全局书、模型名、
  用户名、聊天历史、扫描状态），`scanWorldInfo` 变成 core 函数；
- ViewModel 里只负责"从 `_state.value` 组这个对象"（主线程快照，与现在一致）；
- 现有世界书夹具（`P1-*` 那批）全部照旧；新增 2~3 条针对"三级书选择 + 强制开启 + 预算 3795"的单测。

**不变量**：世界书激活结果不变（同一夹具）；`character_first` 顺序不变；sticky/cooldown 状态读写位置不变；
发送/继续/重生成三条路径的提示词内容与顺序不变。
**风险**：中低（参数收窄是机械的，但世界书是兼容行为，必须夹具兜）。**减行**：VM **≈ −45 行**。

### B3 · SAF 读取去重（降级为「顺手做」，不单独一批）

按 HEAD 核实，四处并不相同：

| 调用点 | 取文件名 | 读文本 | 后续差异 |
|---|---|---|---|
| `importWorldBook` | ✔ `displayNameOf` | ✔ | `worldBookRepository.import(name, json)` |
| `importPreset` | ✔ `displayNameOf` | ✔ | 额外 `setActive` + `applySamplingOf` |
| `importRegexScripts` | ✘ | ✔ | `regexRepository.import(json)`（不需要名字） |
| `importChat` | ✘ | ✔ | `ChatFile.parse` + 逐条映射 + 卡名查表 |

真正重复的只有这一句（4 处）：
```kotlin
getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("读不到文件")
```
→ 抽 `private fun readTextOf(uri): String`（5 行）**并入 B1 那个提交**，不单独占一轮 CI。
`displayNameOf` 只有 2 个调用点，保留原样，**不做"统一导入框架"**（四处差异是真实的，抹平只会加分支）。

**不变量**：四处导入的成功/失败提示文案与 `notice` 内容不变；异常路径仍是 `error("读不到文件")`。
**风险**：极低。**减行**：**≈ −12 行**（不是 −80）。

### B4 · `persistConnection` 先调查再决定（拆成 B4a / B4b）

**HEAD 事实**：只有两个调用点（`onBaseUrlChange` / `onModelChange`，逐按键触发）；
`saveConnection` 用 `prefs.edit()...apply()`；同时还会 `profileRepository.find(id)` →
`save(copy(...))`（**读-改-写**）；没有 `onCleared()`。

**参考意见说得对：不能直接上 Mutex/Channel。** 先分清楚两个不同的风险：

1. `apply()` 本身：`SharedPreferencesImpl` 的落盘是**单线程串行**的，同一实例内按入队顺序写 →
   **"SharedPreferences 会乱序"这个担心不成立**；
2. 真正的风险是**档案表的读-改-写**：两个协程各自 `find` 到旧值、先后 `save`，后完成的那次会用
   旧值覆盖新值（丢更新）；另外两次写跨越"偏好 + 档案表"两个存储，顺序不定。

**B4a（只调查，不改代码）**：临时加日志量"连续输入 20 个字符 → 实际写入次数 / 最大间隔"，
判断是否值得合并写入。产出结论写进本页。
**B4b（按结论选最小方案）**：优先级从低到高 —— ① 只在失焦/回车时落盘（改 UI 调用点）；
② 用一个 `Mutex` 把"偏好 + 档案"整段串行化（约 5 行）；③ 并发槽只保留最新值（`Channel(CONFLATED)`）。
**没有证据就先只做 ②**。

**不变量**：连续快速改完 `baseUrl`/`model` 后重启，看到的是最后的值；Key 的 600ms 防抖行为不变。
**风险**：低中。**减行**：0（可能 +5）。

### B5 · `UiState` 搬迁与分组

**先纠正上一版的风险判断**：373 处 `state.xxx` 是**属性访问**，只要字段名不变就不受影响；
真正要动的是 **42 处显式 `ChatViewModel.UiState` 类型注解** 和 194 处 `copy(...)`（后者只要不改字段名也安全）。

分两段，中间必须有一个可交付的包：

**B5-0（先做，风险最低）**：把 `UiState` 及 18 个 getter 从 `ChatViewModel` 的**嵌套类**改成
**顶层类并搬到独立文件** `app/.../ChatState.kt`（同包，`UiState` 名字不变）。
- 影响面：42 处 `ChatViewModel.UiState` → `UiState`；373 处 `state.` **不动**；
- 收益：VM 文件 **−307 行**（声明 229–535），`UiState` 变成可独立阅读的单元；
- 风险：低（纯搬迁），但**一次改 42 个文件**，所以单独一个包 + 一个 tag。

**B5-1 … B5-4（按组切，每刀一个包）**：`Nav` / `Config` / `Gen` / `Edit` 分成 4 个 `data class`
作为 `UiState` 的字段。**规则：字段名与类型先不动，只改归属**（`state.screen` → `state.nav.screen`），
不许出现"临时双份字段 / 兼容代理 / 两处同时写"。
- 每刀：先在该组内的**生产点**（`_state.update { it.copy(...) }` 约 146 处中的一部分）改，
  再改消费点，最后全量 `grep` 复核 + CI + 真机回归；
- 顺序：先 `Edit`（引用最少）→ `Nav` → `Config` → `Gen`（引用最多、风险最大）。

**不变量**：初始值不变（`UiState()` 默认值逐项相同）；状态转移结果不变；Compose 观察点数量与重组范围不扩大
（`collectAsState` 仍然只订阅一个 `StateFlow`）；没有持久化/序列化（已核实：`UiState` 不被 `data`/`core` 引用）。
**风险**：B5-0 低；B5-1~4 机械但面广，每刀必须独立 tag。**减行**：B5-0 **−307**；B5-1~4 **0**（结构变化）。

### B6 · 消息分页（最后做，且先写技术设计）

按 HEAD 核实的事实（原方案说"下标耦合"是错的）：

- 读取：`chatRepository.messages(sessionId)` **一次返回全部**（`ORDER BY id ASC`）；
  `openSessionInternal` 把它整份映射成 `rows`（`uiKey = "m"+id`、`dbId = id`）；
- 身份：消息操作已按 `uiKey`/`dbId`（`deleteMessageAt(uiKey)`、`rewindToMessage(uiKey)`），
  **不按下标**；swipe 备选在独立表里按 `message_id + seq` 关联；
- 真正的问题：**全量载入 + 全量重建**（长会话首屏与内存）。

所以 B6 的设计要点（**开工前必须先成文**，本轮不设计完）：

1. 排序与游标：`ORDER BY id ASC` 已是稳定序；游标用 **`id` 上界/下界**（不是 `send_date`，
   导入的旧会话 `send_date` 会重复）；
2. 首屏：最近 N 条（建议 50）逆序取再翻转；
3. 向上翻页：`id < 当前首条 id` 取 N 条，插入到 `rows` **前面**，并按住滚动锚点
   （反排列表里这是"末尾追加"，与现有 `reverseLayout=true` 的锚点逻辑要对上 —— **未验证**）；
4. 删除/撤回/重生成：一律按 `dbId`/`uiKey`，不依赖下标 → 分页后仍成立；
5. 生成中的占位行仍在 `rows` 尾部（不落库），不能被"向上翻页"的插入打乱；
6. 旧会话/重复时间戳/坏行：解析层已经在导入时归一，分页不看时间字段；
7. 测试：`core` 里加一个纯 JVM 的"分页切片 + 游标推进"单测；DB 层行为只能在 CI/真机验。

**不变量**：消息顺序与完整性不变；删除/撤回目标不串位；滚动不跳（往上翻页时视口内容不动）；
生成中切换会话再回来，续接与分页不打架。
**风险**：高（数据正确性 + 滚动）。**减行**：不减（预计 +50~100）。

## 四、减量预算（重算）

| 批次 | VM 文件 | 说明 |
|---|---|---|
| 现在 | 3,792 | |
| B1 | −35 | 规则 73 行进 core，留 14 行转发；含 B3 的 −12 |
| B2 | −45 | `scanWorldInfo` 整块进 core |
| B4 | ±0~+5 | 串行化 |
| B5-0 | −307 | `UiState` 搬出到 `ChatState.kt`（**同一份代码，换文件**） |
| B5-1~4 | 0 | 组内重组 |
| B6 | +50~100 | 分页状态与游标 |
| **合计** | **≈ 3,390–3,450** | |

**结论：上一版的 ≤2,600 行不成立，撤回该 KPI。** 想继续减只有一条路：把整块职责搬成独立类
（模型档案 599 行、正则 492 行、世界书 ~300 行），但那是**新增 3 个抽象层**，与"不要为数字硬拆"冲突。
→ 列为**可选的 C 阶段，必须单独决定**；在此之前，目标改为：

- **职责目标**：`ChatViewModel` 只剩"编排"（收事件 → 改状态 → 调仓库/服务）；
- **可测目标**：解析（B1）、世界书输入（B2）、分页切片（B6）三类纯逻辑在 `core` 有单测（≥ +15 条）；
- **防增长**：B0 棘轮三项只降不升。

## 五、顺序（与上一版的差别）

`B0 → B1（含 B3） → B2 → B4a → B4b → B5-0 → B5-1…4 → B6`

- **B3 并入 B1**：只有 12 行，单独一批要多花一次 CI + 一次真机 —— 不划算。
- **B2 放在 B1 之后**：两者都要动 core，B1 先验证"抽到 core + 转发"这套流程，B2 照抄。
- **B4 提前到 B5 之前**：它只碰 30 行，且是真 bug（丢更新）；但**必须先有 B4a 的测量**，
  没有测量就不许上并发结构。
- **B5-0 排在后半段**：42 个文件一次改，虽然机械但影响面大，要等前面的批次把"流程"跑顺。
- **B6 永远最后**：它要动数据读法与滚动锚点，和别的批次混在一起会分不清是谁导致的回归。

## 六、每批的不变量（验收不只看 CI 绿）

- **B0 后**：所有既有 check 仍绿；预算文件与实测一致（脚本自己打印）。
- **B1 后**：配置继承优先级、`null` vs 空集、`maxTokens<=0`、`"off"`、采样解析回落 全部有测试；
  「查看请求」里五项采样与界面显示一致（真机 1 次）。
- **B2 后**：世界书夹具全绿；`character_first` 顺序、强制开启、预算 3795 不变；三条发送路径提示词一致。
- **B3 后**：四处导入的成功/失败提示文案不变。
- **B4 后**：快速改 `baseUrl`/`model` 后重启取到最新值；Key 防抖不变。
- **B5-0 后**：界面/状态无任何变化（纯搬迁）；42 处类型注解编译通过。
- **B5-1~4 后**：`UiState()` 默认值逐项相同；进入聊天/切卡/改配置/编辑 四个回归动作正常。
- **B6 后**：消息顺序与完整性、删除/撤回目标、向上翻页不跳、续接不打架。

## 七、Git / CI / APK / 回退（按 workflow 实际行为）

`.github/workflows/ci.yml` 的现状：

| 事项 | 实际机制 |
|---|---|
| 触发 | `push` 到 `main`、`push` 任何 `apk-*` tag、PR、手跑 |
| `apk-latest` release | **只在 `refs/heads/main` 构建成功时覆盖** → 文档提交也会覆盖它 |
| 回退点 | 打 `apk-mNNN` tag → 构建出**独立 release**（资产不被后续覆盖） |
| APK 与 commit 的绑定 | `TAVERN_BUILD_ID = github.sha`（写进 `BuildConfig.BUILD_ID`，可从 dex 里查出来） |
| versionCode | `TAVERN_VERSION_CODE = github.run_number`（只增不减，手机不会拒装） |
| 资产完整性 | release 资产自带 `digest: sha256:…`；本机 `sha256sum` 逐字节核对（已验：`apk-m233` 一致） |
| 签名 | `tools/verify-apk.sh <apk> <证书SHA-256>`；CI 也会冒烟打印证书摘要 |

所以每批的验收记录必须写四个值：**commit sha、tag、APK 的 sha256、`BUILD_ID` 读数**。
⚠️ tag 只证明"这个 commit 被构建过"，**不能**当作"这个 APK 在真机上验过"——真机回执要单独记。

回退操作：`git revert <sha>` 或直接 `git checkout <上一个 tag> -- <文件>`，
装机回退 = 装上一个 tag 的 APK（同一把签名，可直接覆盖安装）。

## 八、二次审查记录

本次修订参考了另一份 **由 AI 生成** 的审查意见（非人工确认，也不构成权威结论）。逐条判断如下。

**采纳（经 HEAD 代码验证成立）**

1. **B0 的统计口径有矛盾** —— 成立。上一版"156 总数 / 121 public / 38 private"三者不自洽
   （121+38=159）。已改为统一口径并把扩展函数、`override`、嵌套类型分开统计。
2. **门禁容易被绕过、也不能证明架构改善** —— 成立。已在 B0 里写明局限，并把"搬迁量"记进预算文件。
3. **B3 的四处重复可能不一致** —— 成立，且比意见更极端：只有 3 行是真重复，减量从 80 降到 12。
4. **B4 不能直接上 Mutex/Channel/runBlocking** —— 成立。已拆成 B4a（测量）/B4b（按证据选最小方案），
   并纠正了一个错误担心：`apply()` 是单线程串行落盘的，"SharedPreferences 乱序"不成立，
   真风险在档案表的读-改-写。
5. **B5 的机械替换风险** —— 成立。已把 373 处属性访问与 42 处类型注解分开，先做 B5-0（纯搬迁），
   并明令禁止过渡期双份字段/兼容代理。
6. **B6 信息不足、不许直接动库** —— 成立。已按 HEAD 补上"身份是 `uiKey`/`dbId`、排序是 `ORDER BY id`、
   全量载入是真问题"三条事实，并把设计要点列成 7 条前置条件。
7. **减量预算必须重算、目标应由职责推导** —— 成立。≤2,600 撤回，改为职责 + 可测 + 棘轮三目标。
8. **顺序可考虑 B3 先于 B2** —— 部分采纳：B3 太薄，**并入 B1 一个提交**比单独一批更省一轮 CI。
9. **每批要写行为不变量** —— 采纳，并按代码补了"空集语义""maxTokens<=0""空串截断继承"等具体条目。

**部分采纳 / 需要调整**

10. **「B2 分两层：data 收集 + core 纯计算」** —— 方向对，但前提已变：HEAD 里线程问题已修（`c984f71`），
    `scanWorldInfo` 已是纯函数。改为"只收窄参数 + 落 core"，不做整条 `prepare()` 的分层。
11. **「不要预先假定整个 prepare 是纯函数」** —— 认可，所以 B2 只搬**已证明是纯**的那一段
    `scanWorldInfo`；`PromptBuilder().build()` 本来就在 `core/prompt`（已是纯函数 + 夹具），不动。

**不成立 / 过度设计**

12. **「B6 的 UI 下标 ↔ DB 行号强耦合」** —— 在 HEAD 上不成立：所有消息操作已按 `uiKey`/`dbId`。
    该条是 11-26 审查的旧结论，已被 K1/K3 重构消掉。B6 真问题是全量载入。
13. **「token 预算裁剪要纯化」** —— 现状里**没有**历史裁剪逻辑（只有世界书的 `maxContext = 3795`）。
    没有的东西不搬。
14. **「新增 Manager/Controller 层把行数降下来」** —— 不采纳。要降到 2,600 只能拆出 3 个新类，
    与"不为数字硬拆"冲突；列为可选 C 阶段，需单独决定。

## 九、未验证事项（不许当成已成立）

1. **`./gradlew test` 是否跑到 `:app` 的单测**（`tools/check-tests-ran.sh` 会不会因此变红）——
   未验证。确认方式：加一条最小 `@Test` 看 CI 反应（一次性实验，不留在代码里）。
2. **B4 的真实写入频率与丢更新概率** —— 未验证，B4a 就是为它准备的。
3. **B6 的向上翻页与现在 `reverseLayout = true` 滚动锚点的配合** —— 未验证，属 B6 技术设计的一部分。
4. **tag 构建是否也跑 `apk-latest` 步骤** —— 已从 workflow 读到 `github.ref == 'refs/heads/main'` 条件，
   tag 不会更新 `apk-latest`；但**未实测**过一次 tag 构建的完整行为，只按配置推断。
5. **本机下载速度**：15MB APK 实测 10~20 分钟（多次），所以"每批一个包"的真实成本要按 20 分钟算。

---

**本次仅修订方案，未修改业务代码、未执行任何重构、未创建脚本/预算文件/测试/新类。**
