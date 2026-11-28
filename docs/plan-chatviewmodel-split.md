# `ChatViewModel` 拆分方案（3,793 行）

> **第三版 + 第四轮小修**（2026-11-28）。本页只是方案：**未改任何生产代码 / 测试 / CI / 脚本**。
> 数字按当前 `HEAD` 逐项重新统计；凡与 [architecture-audit.md](dev/architecture-audit.md)（11-26 快照）冲突，
> 以本页为准。
>
> **状态标记**（本页全文使用）：
> - **【已验证】** = 在本轮用只读命令从当前代码/CI 记录里读出来的；
> - **【推断】** = 由代码结构推导、但没有运行验证的；
> - **【待验证】** = 必须在实施时用特定方法确认的（每条都给最小验证方法）。

## 零、本版修正了三处事实错误

| 第二版的说法 | 本版核实结果 | 证据 |
|---|---|---|
| B2「`scanWorldInfo` 已是纯函数」 | **错一半**：函数自身无副作用，但它调用的 `LorebookScanner` **持有 `Random`**（`LorebookScanner(private val random: Random = Random.Default)`），概率条目的结果**不确定** → "相同输入必得相同输出"**不成立** | `core/lorebook/.../LorebookScanner.kt:20`【已验证】 |
| B4「档案只有 1 个写入口」 | **错**：同一份档案存储有 **5 个**写入口（`persistConnection` / `onApiKeyChange` / `saveProfileDraft` / `saveProfile`-类路径 / `deleteProfile`），且 `saveProfileDraft` 写的是**整条记录** | `ChatViewModel.kt:1903, 1919, 2121, 2122, 2158`【已验证】 |
| 预算「≈3,390–3,450」 | **算式不闭合**：漏加了 B6 的 +50~100，且 B1 的 73 行也低估了。按本版重算见「四」 | 见下 |

## 一、现状（HEAD 口径，全部【已验证】）

| 指标 | 值 |
|---|---|
| 文件总行数 | **3,793**（末行为空串） |
| 非空行 | **3,523**（空行 270） |
| 注释 / KDoc 行 | **717**（非空非注释 = 2,806，**仅作参考**，不做预算指标） |
| 类内 `fun` | **159** = 无修饰符（=public）**121** + `private` **38**；其中 **3 个是扩展函数**；`override` **0**；嵌套类型里的 fun **0** |
| `viewModelScope.launch` / `withContext(...)` | 85 / 105 |
| `_state.update {}` / `.copy(...)` | 146 / 194 |
| `UiState` 字段 | **72** |
| `effective*` / `display*` getter | **18 + 1** = 19（明细见 B1） |
| UI 侧 `state.xxx` 属性访问 | **373**（`ChatScreen` 72 · `ConfigPickerScreen` 52 · `SettingsPages` 36 · `MainScreen` 31 · 其余分散） |
| 显式 `ChatViewModel.UiState` 类型注解 | **42** |
| 跨模块引用 `UiState` | **0**（`data`/`core` 都不认识它） |
| 嵌套类型 | 8 个 `data class` + 1 个顶层 `enum class BookBulkMode` |

> 口径变更说明：第二版的「156 个函数」漏掉了 3 个扩展函数（`fun X.y(`）。本版的口径是
> **逐行 `fun` 计数 + 按修饰符分类 + 扩展函数/override/嵌套类型分开统计**，B0 的脚本必须照此实现。

## 二、约束（决定"怎么拆"）

1. **本机编不了 `:app`** → 每批的 diff 必须一屏能看完；一批 = 一个提交 = 一个 tag。【已验证】
2. **纯逻辑落 `core/*` 才有本机秒级反馈**（`tools/test-core.sh`）。`app` 的单测是否被 `./gradlew test`
   跑到**尚未验证**（见「九」#1），所以**不把测试押在 `app/src/test`**。
3. **先例**：`RevealPacer` / `RevealCatchUp` / `PromptOrderCompat` 都从它里面抽出来、带单测、行为不变。
4. **回退成本**：一次 tag 构建 ≈2 分钟 CI + 15MB 下载（本机实测 10~20 分钟）。

## 三、批次

### B0 · 尺寸棘轮（只加门禁，不改产品代码）

**为什么先做**：没有门禁，后面每一批都会被新需求重新撑大（历史曲线 144 → 2,391 → 3,137 → 3,792）。

**范围**：`tools/check-viewmodel-size.sh` + `tools/viewmodel-budget.txt`，在 CI 的「铁律检查」job 里跑。

**统计口径（本版定稿，已用只读命令在 HEAD 上验过）**：

| 指标 | 规则 | 当前值 |
|---|---|---|
| **非空行（主指标）** | `line.strip().isNotEmpty()` | **3,523** |
| 文件总行 / 空行 / 注释行 | 仅打印作信息，**不参与判定** | 3,793 / 270 / 717 |
| 类内 fun | `^    (public\|private\|internal\|protected)? (override)? (suspend)? (inline)? fun 名` | **159** |
| 分开报告 | 扩展函数 `^    (private\|internal)? fun X.y(`、`override fun`、嵌套类型里的 `^        fun ` | 3 / 0 / 0 |
| public fun | 上表里无可见性修饰符的 | **121** |
| `_state.update` | 出现次数 | **146** |

三项封顶（非空行 / public fun / `_state.update`），**只降不升**。

**为什么主指标是"非空行"而不是"有效行"**：判断"哪一行是注释"必须排除**字符串里的 `//` 与 `*`**
（本项目现在 0 处 ``"""``，`//` 只出现在 URL 字面量里，靠"行首"判断侥幸没错），
但脚本要长期用、不能依赖"当前文件恰好没有多行字符串"。**非空行没有任何歧义**；
注释量另打印一行，人看趋势即可。

**承认局限（不掩盖）**：把代码搬到新文件就能绕过 → 所以 `viewmodel-budget.txt` 里**同时记录
"已移出到别的文件的量"**（B1/B2/B5-0 各记一笔），避免用搬文件刷指标。它只能防增长，**不能证明架构改善**。

**不变量**：现有 6 个 check 全绿；脚本输出与手工统计一致（首跑必须对一次账）。
**单测**：无（纯脚本）。**CI**：故意加 1 行 → 红；撤销 → 绿。【待验证：这是实施时的第一个动作】
**真机**：无。**回退**：`git revert` 该提交即可。

### B1 · 抽「生效值解析」到 core（含 B3 的小工具）

**为什么这时做**：它是最独立的一块（纯数据类上的纯计算），先跑通"抽到 core + 一行转发"的流程，
B2 照抄；而且它是"界面显示 A、请求发 B"那类历史 bug 的根源。

**已核实的结构**（19 个 getter，**分三类**，不是同一类规则）：

| 类 | getter | 层级 | 行数 |
|---|---|---|---|
| **A 解析链（14 个，该抽）** | `effectiveMessageStyle` | 卡 → 全局 | 4 |
| | `effectivePersonaId` / `effectiveProfileId` / `effectivePresetName` | 会话 → 卡 → 全局默认 | 4+4+4 |
| | `effectiveBubbleFontSize` | 会话 → 卡 → 全局 | 8 |
| | `effectiveReasoningEffort` / `effectiveWorldBookNames` / `effectiveRegexIds` | 会话 → 卡（**无全局层**） | 4+4+6 |
| | `effectiveBuiltinDisabled`（空集 ≠ null） | 会话 → 卡 → 全局 | 5 |
| | `effectiveTemperature` / `MaxTokens` / `TopP` / `FrequencyPenalty` / `PresencePenalty`（四层，含文本解析） | 会话 → 卡 → **预设** → 全局文本 | 5+4+4+4+9 |
| **B 派生（5 个，不抽）** | `effectivePersona`（查表）/ `effectiveUserName` / `effectiveAuthorNote`（多字段 + 角色映射）/ `effectiveRegexScripts`（过滤） | — | 7+7+26+12 |
| | `displayRegexScripts`（与内置规则合成） | — | 2 |
| **合计** | | | **123 行** |

**范围**：
1. `ConfigOverride` 数据类**下沉到 `:core:model`**（不放新模块）。核实过：该文件 **0 个 import、0 个注解**，
   字段只有 `String?` / `Set<String>` / `Double?` / `Int?` / `Float?`，无 Room/Gson/Serialization 注解，
   不依赖 `data` 内任何类型 → 迁移不触碰序列化与数据库映射。【已验证】
   ⚠️ 但它是**一个文件里同时声明了接口 `ConfigOverrideRepository`**（还带 `session()/card()` 两个作用域键）：
   迁移 = **拆文件**（数据类走、接口留 `data`），3 个引用文件改 import。这是"拆文件"而非"搬类"，要在提交信息里写清。
2. `resolve(...)` 放 **`:core:preset`**（它已拥有 `SamplingPreset`；`core:model` 是叶子不能反向依赖 preset）。
   需要在 `core/preset/build.gradle.kts` 加 `implementation(project(":core:model"))`（core→core，无环）。
   **不新建 `core/config` 模块**：按 [layout.md](dev/layout.md) §9 三条判据（独立 JVM 测试 / 两个以上 feature 复用 /
   自有三方依赖）都不充分，模块数量本身是成本。
3. `UiState` 里 14 个 A 类 getter 退化成一行转发，**名字与签名不变**，UI 零改动。
4. **解析结果与 `copy()` 的生命周期（必须写死，否则会出"状态改了、effective 没变"）**：
   - `UiState` 是 `data class`，且**从不就地修改**（146 处状态变更全走 `copy()`）；
     `copy()` 会**跑一遍构造**，因此存在构造体里的 `EffectiveConfig` 每次 `copy()` **必然重算** ——
     语义绝对安全，代价是 194 处 `copy()` 各多分配一个小对象；
   - 若用 `private val cfg by lazy(LazyThreadSafetyMode.NONE) { resolve(...) }`：也是**每实例至多算一次**，
     且只有真正被读的实例才算（界面只读关心的字段）；`copy()` 生成的新实例 lazy 状态是全新的，
     **不存在"复制了旧解析结果"的过期问题**。UI 单线程访问，用 `NONE` 免锁；
   - **禁止**：把 `cfg` 做成 `companion` 缓存、或按 `hashCode` 记忆化 —— 状态对象每次都变，反而更慢更错；
   - 选 `lazy(NONE)`，并在类注释里写明"`copy()` 后由新实例重新解析"。
   【推断：两种方案的开销差异未实测；语义上两者都正确，`lazy` 只在"构造了但没读"的实例上省一次计算】
5. **等价性验证（B1 的硬验收）**：写一组"新旧公式对照"测试 —— 在测试里把 14 个 getter 的**旧 `?:` 链原样重写**，
   对同一批输入（覆盖 null/空串/空集/0/负数/`"off"`/四层组合）逐项比较 `resolve()` 输出与旧公式结果。
   这条测试是"行为不变"的直接证据，比"我看了一遍"可靠。

**`effectiveAuthorNote` 的语义（重点核实项）**【已验证】：
- 注释写的是"正文为空 = 这一层没有，继续往上找"，代码是 `sessionOverride.authorNote ?: cardOverride.authorNote`
  然后 `isNullOrBlank() → null` —— 单看代码，**空串会截断继承**；
- 但**两个写入点都做了归一**：`setSessionAuthorNote` 用 `text?.takeIf { it.isNotBlank() }`，
  持久化用 `ifBlank { null }` → **空串在运行时不可达**，"注释与代码"目前并不矛盾；
- 风险在于这是**每个写入点各自遵守的约定**，不是类型保证。→ **规则**：`resolve()` 内部也对
  `authorNote` 做 `takeIf { isNotBlank() }` 归一（与现状等价，因为不可达），并在**测试里钉住**
  「层写入空串时，解析结果等于上一层的值」。

**B3（并入本批，不单独占一轮 CI）**：四处 SAF 读取块实测 **3 + 5 + 5 + 5 = 18 行**，
抽成 `private fun readTextOf(uri): String`（约 6 行）后 → 4 行调用 + 6 行定义 = **净 −8 行**。
`displayNameOf` 只有 2 个调用点，**不动**；四处的后续差异（`setActive`+`applySamplingOf` / 不需要文件名 /
`ChatFile.parse`）是真实的，**不抹平**。

**不变量**：优先级与层级不变；空集 ≠ null 不变；`maxTokens <= 0` = 不限不变；`"off"` 语义不变；
采样文本解析失败回落不变；UI 显示值与「查看请求」里的实际值一致；四处导入的成功/失败文案不变。
**单测**（`core`，本机可跑）：四层优先级 × 边界（`null` / 空串 / 空集 / 0 / 负数 / `"off"`）≥ 12 条。
**CI**：绿 + 168 条旧测试全绿。**真机**：改一个会话的采样/人设/作者注，看「查看请求」与界面一致（1 次）。
**回退**：单提交 revert；tag 回到上一批。
**预算**：VM 文件 **−55（getters）+ −8（B3）= −63**；仓库总量 **+约 120**（EffectiveConfig + resolve + 测试）。

### B2 · 把 `scanWorldInfo` 收窄成 core 输入（原「挪线程」作废）

**为什么这时做**：B1 已经跑通"抽到 core"的流程；这块是唯一还端着整个 `UiState` 的纯计算。
**前提已变**：线程问题在 `c984f71` 已修（`withContext(Dispatchers.Default)`），**本批不碰线程**。【已验证】

**核实结果**【已验证】：

| 检查项 | 结果 |
|---|---|
| 读共享可变状态（`worldInfoStates` / `globalBookEntries`） | **不读**；调用方在主线程取快照（`HashMap(globalBookEntries)`）再传参 |
| 修改输入 | 不改；对 `cardEntries` 用 `filter/map` 生成新列表 |
| 文件 / 数据库 / 日志 / 网络副作用 | 无（`AppLog`/仓库调用都在调用方） |
| 依赖时间 / 随机数 | **随机数：依赖**（`LorebookScanner(random = Random.Default)`）→ **输出不确定** |
| 相同输入 → 相同输出 | **默认不成立**（概率条目）；换成固定 seed 才成立 |
| 输入集合是否可能被并发修改 | 不会：传入的是不可变 `List`/`Set` 与快照 `HashMap`（**读**方不再改它） |

**范围**：
1. `core/lorebook` 新增输入对象 `WorldInfoInputs`（**最小业务字段**，不是 UiState 的改名）：
   `cardEntries`（已应用禁用/强制开启）、`globalBooks`（名字 + enabled + 条目）、`onlyBookNames`、
   `model`、`userName`、`characterDescription/Personality/Scenario`、`history`、`scanState`、`maxContext`；
2. `scanWorldInfo` 移入 core（`WorldInfoPreferences` 之类的入口），VM 只负责组对象并调用；
3. **随机性显式化**：生产路径照旧 `Random.Default`；测试传固定 seed。
   **这必须在文档与代码注释里写清楚**，否则下一轮会有人以为它是纯函数。
4. **随机数调用顺序必须不变（否则同 seed 也会出不同结果）**：概率条目按"每次扫描按顺序抽号"决定激活，
   只要**抽号顺序**变一格，后面所有条目的结果全部平移。本批是**整体搬迁、不重写内部循环**，
   顺序按构造保持；但仍要验：
   - **core 侧可验**：用一个 `RecordingRandom`（包装 `Random(seed)`，记录每次抽号的类型与顺序）跑同一输入两次，
     断言"抽号序列一致"；再把搬迁当刻的输出**录成 golden**（固定 seed 的激活条目集合），后续任何改动都必须对得上；
   - **core 侧验不了**：与"搬迁前 app 里的旧路径"逐位对比（旧路径在 `:app`，本机编不了）→ 只能真机对照一次：
     同一条会话、同一本书，分别在看得到的日志里核对 `wi=…(before=…字 after=…字)` 的激活条数。
   - 另外提醒：生产用 `Random.Default` 意味着**同一用户两次发送，概率条目结果可能不同 —— 这是现状**，不是本次引入，别当回归。

**不变量**：世界书激活结果不变（现有 `P1-*` 夹具全绿）；`character_first` 顺序不变；强制开启/禁用不变；
预算 3795 不变；sticky/cooldown 状态读写位置不变；发送/继续/重生成三条路径提示词一致。
**单测**：固定 seed 下"同一输入两次结果相同" + 三级书选择 + 强制开启 + 预算裁剪（≥ 5 条）。
**CI**：绿 + 夹具全绿。**真机**：发一条消息，看请求里世界书条目数与之前一致（1 次）。
**回退**：单提交 revert（core 新文件无用即删）。
**预算**：VM **−42**（46 行移出，调用点 +4）；仓库 **+46（core）+ 测试**。

### B3 · 已并入 B1（原因：18 行 → 净 −8，独立一批要多花一次 CI + 一次真机，不划算）

### B4 · 档案写入：两类风险，默认**不做**（改由证据触发）

**核实到的写入口（5 个，全部在 `ChatViewModel`）**【已验证】：

| 行 | 入口 | 写什么 |
|---|---|---|
| 1919 | `persistConnection`（`onBaseUrlChange` / `onModelChange` **逐按键**触发） | `existing.copy(baseUrl, model)` **读-改-写** |
| 1903 | `onApiKeyChange`（600ms 防抖） | `saveApiKey(profileId, key)` |
| 2121 | `saveProfileDraft` | **整条 `ModelProfile`**（name/baseUrl/model/headers/isDefault） |
| 2122 | `saveProfileDraft` | `saveApiKey(id, draft.apiKey)` |
| 2158 | `deleteProfile` | 删除 |

**风险 A：过期草稿覆盖（可达，比并发更值得注意）**【已验证代码路径】
`openProfileEditor` 在**打开编辑器那一刻**读一次库、把整条档案放进 `UiState.profileDraft`（`:2066-2084`）；
`saveProfileDraft` 用**草稿**构造整条 `ModelProfile` 写回（`:2100-2122`）。草稿存活到 `closeProfileEditor`，
期间任何写入口改了同一条档案，保存时都会被草稿里的**旧值**整条覆盖。可达操作序列：

> 设置 → 档案 → 编辑某档案 → 返回 → 设置 → 连接 → 改 baseUrl（`persistConnection` **立即**写档案）→
> 回到档案编辑 → 保存 → **baseUrl（以及 apiKey）被草稿旧值冲掉**。

**风险 B：两个写入口并发交错（概率低）**：`persistConnection` 的读-改-写与其它写入同时进行，后完成者整条覆盖。
`SharedPreferences.apply()` 的落盘是**单线程串行**的（"SP 乱序"不成立），真正的缝隙在"读旧值 → 写整条"。
**无 `onCleared` 丢写问题**（`persistConnection` 不防抖）；`onApiKeyChange` 的 600ms 防抖**保持不动**
（它防的是 Keystore 加密频率）。

**修复选项与预算（做/不做分开列）**：

| 方案 | 具体改动 | VM 预算 | 治哪个风险 | 行为变化 |
|---|---|---|---|---|
| **不做**（默认） | — | **0** | — | 无 |
| M1 只加锁 | 一个 `Mutex` 包住上表 5 个入口（都在同一个类、不嵌套调用 → 无死锁面） | **+5 ~ +8** | 只治 B | 无 |
| M2 治过期草稿 | `ProfileDraft` 加"打开时的原值快照"，保存前重读库，**只写用户真正改过的字段** | **+12 ~ +20** | 治 A（也顺带覆盖 B 的大部分） | **有**：别人改过的字段以库里为准 |
| M1+M2 | 两者都做 | **+17 ~ +28** | 两个都治 | 同 M2 |

**推荐：默认不做。** 触发条件（任一）：真机撞到 baseUrl / Key 被回退；或用户明确要求。
若要做，**优先 M2** —— 风险 A 可达、风险 B 需要毫秒级撞车；而 M1 解决不了 A。
**调查方式**：不写永久诊断代码；若需量化，用**一次性临时构建**打时间戳日志，测完撤销〔本轮不执行〕。

**不变量（若做）**：快速改 baseUrl/model 后重启取到最新值；保存档案不会把别处刚改的字段冲回旧值；
Key 防抖时长不变。**单测**：不可得（依赖仓库）；只能真机：按上面的操作序列复现一次。
**回退**：单提交 revert。

### B5 · `UiState` 搬迁与分组

**先纠正第二版的风险判断**（也已核实）：373 处 `state.xxx` 是**属性访问**，只要字段名不变就不受影响；
真正要动的是 42 处显式类型注解。**已核实的其他约束**【已验证】：

| 检查项 | 结果 |
|---|---|
| 反射（`UiState::class` 等） | **0 处** |
| `typealias` / 间接类型引用 | **0 处** |
| 跨模块（`data`/`core`）引用 | **0 处** |
| 测试代码依赖嵌套类 | **0 处**（唯一提到 `ChatViewModel` 的是 core 测试里的一行注释） |
| 构造器默认值依赖外部上下文 | **无**（都是字面量，无 `System.*` / `UUID` / `Context`） |
| 可见性变化 | 从"嵌套在 public 类里"变成同包顶层 public —— 调用方都在同包/同模块，**无破坏** |
| 序列化 / 持久化 | **无**（`UiState` 纯内存，不落盘、不导出） |
| Compose 观察 | 仍是**一个** `StateFlow` + `collectAsState`，不新增流 |

**B5-0（先做，纯搬迁）**：`UiState` 从嵌套类改为**同包顶层类**，搬到 `app/.../ChatState.kt`。
- 影响面：42 处 `ChatViewModel.UiState` → `UiState`；373 处 `state.` **不动**；2 处构造 **不动**；
- 收益：VM 文件 **−307 行**（229–535），`UiState` 成为可独立阅读的单元；
- **风险**：一次改 42 个文件（纯类型名），所以**单独一批 + 单独 tag**。

**B5-1 … B5-4（按组切，每刀一批）**：`Nav` / `Config` / `Gen` / `Edit` 四个 `data class` 作为字段。
**规则**：字段名与类型先不动，只改归属（`state.screen` → `state.nav.screen`）；**禁止**过渡期双份字段、
兼容代理、两处同时写、新增 `StateFlow`。
- 每刀：先改该组的**生产点**（146 处 `_state.update` 里属于该组的部分），再改消费点，
  最后全量 `grep` 复核 + CI + 真机回归；
- 顺序：`Edit`（引用最少）→ `Nav` → `Config` → `Gen`（引用最多）。

**不变量**：`UiState()` 默认值逐项相同；状态转移结果相同；Compose 观察点不变（一个流、不扩大订阅面）；
无状态更新遗漏（每刀用"改过的生产点 ↔ 改过的消费点"清单对账）。
**单测**：无（Compose/状态机不在 core）。**CI**：绿。**真机**：进聊天 / 切卡 / 改配置 / 编辑卡 四动作。
**回退**：每刀独立 tag，回装上一版 APK + revert 对应提交。
**预算**：B5-0 **−307**；B5-1~4 **0**（结构变化，总行数不变）。

### B6 · 消息分页（独立技术设计，本轮不实施）

**已核实的事实**【已验证】：
- 存储**不是 Room**，是手写 `SQLiteOpenHelper`（`data/.../chat/impl/TavernDatabase.kt`）；
- `messages(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, is_user, content, …)`，
  且**已有索引** `idx_messages_session ON messages(session_id, id)` → 游标查询有现成索引；
- swipe 备选表 `PRIMARY KEY (message_id, seq)` + `FOREIGN KEY … ON DELETE CASCADE`；
- 读取 `chatRepository.messages(sessionId)` **一次返回全部**（`ORDER BY id ASC`），
  `openSessionInternal` 全量映射成 `rows`（`uiKey = "m"+id`、`dbId = id`）；
- 消息操作**已按 `uiKey`/`dbId`**（`deleteMessageAt(uiKey)`、`rewindToMessage(uiKey)`），
  **不存在"UI 下标 ↔ DB 行号"耦合**（第二版已纠正，此处保留）。

**必须回答清楚才能开工的问题**（目前**都没有答案**，因此 B6 = **需要独立技术设计**）：

1. 首屏 N 条（建议 50）是否包含**生成中的占位行**？（占位行不在库里 → 应在"分页结果 + inflight"合成层处理）
2. `reverseLayout = true` 下，向上加载历史是"末尾插入"还是"头部插入"？锚点用哪套补偿？
   （现有 `scroll` 日志与 `followBridgePx` 都在跟底方向，**反方向未验证**）
3. 快速滚动如何防重入/重复游标（同一 `id` 窗口被请求两次）？
4. 加载中删除消息：游标已越过被删行时如何继续？
5. 切会话时如何取消旧的分页请求（现有 `openSessionInternal` 会停流，但分页是自己的协程）？
6. 删除 / 撤回 / 重生成是否**始终**用稳定 id（当前是，分页后要保持）？
7. 分页期间新消息到达：`rows` 尾部合并规则（现在靠"落库后重建/追加"，分页后要防止与"历史窗口"打架）。
8. 数据库 ID 游标是否可能漏/重（`AUTOINCREMENT` 单调，删除留下空洞 → 用"边界 id"而非 offset 即可）。
9. 导入的旧会话（`send_date` 重复、坏行已丢弃）在分页后是否仍按 `id` 排序（应与时间无关）。

**归属（谁写 SQL、谁持游标）**【已验证 ADR】：

| 层 | 负责什么 | 依据 |
|---|---|---|
| `data/chat/impl/SqliteChatRepository.kt` | **所有 SQL**（`WHERE session_id=? AND id<? ORDER BY id DESC LIMIT ?`，返回前反转成升序） | ADR 0003：SQL 集中在 `TavernDatabase` 与 impl，**不在 VM 里拼 SQL** |
| `data/chat/TavernDatabase.kt` | 表结构与索引 | **索引已存在** `idx_messages_session(session_id, id)` → **本项不需要 schema 迁移** |
| `data/chat/ChatRepository.kt`（接口） | 新增 `messages(sessionId, limit, beforeId)` | **ADR 0003 已经写死了这个签名**（"分页仍要做：`messages(sessionId, limit, beforeId)`，不许整表载入"） |
| `ChatViewModel` | 只持 `pageCursor: Long?` / `hasMoreHistory: Boolean`，负责调用与合并 | 不写 SQL、不碰 `SQLiteDatabase` |
| `core/*` | **不新增**（除非确需"纯切片/游标推进"函数，届时再定） | 分页是 I/O 关注点，不是业务规则 |

⚠️ **文档与代码不一致（顺手记录，B6 时一并修）**：`ChatRepository.messages` 的注释写着
"分页接口等长聊天真的出现再加（见 ADR 0003 的约束）"，但 **ADR 0003 明确说"分页仍要做，不许整表载入"**。
两者措辞相反 → B6 实施时改注释对齐 ADR，别让下一个人以为"可以不做"。

**不变量**：消息顺序与完整性不变；删除/撤回目标不串位；向上翻页视口内容不动；生成中切会话再回来不打架；
导出仍覆盖全部消息（分页只影响加载，不影响导出）。
**验证**：SQL 与游标推进都在 `data` 层 → **core 单测覆盖不到**，靠 ①`data` 实现自审 + CI 编译、
②真机（300+ 条会话首屏、向上翻页、删除/撤回、续接）。**不为了"能测"把分页逻辑硬塞进 core**。
**预算**：VM **+50~100**（分页状态与合成），仓库 **+100~150**（仓库 API + 状态 + 测试）。

## 四、预算（逐项算式，两个口径分开算）

**口径 A：`ChatViewModel.kt` 文件行数**（3,793 起始）

| 批次 | 算式 | 净变化 | 累计 |
|---|---|---|---|
| B0 | 不改产品代码 | 0 | 3,793 |
| B1（含 B3） | **实测 −24**（非空行 −13；预估 −63，见下方"为什么差这么多"） | **−24** | 3,769 |
| B2 | `scanWorldInfo` 46 行移出 + 调用点 +4 | **−42** | 3,727 |
| B4（默认不做） | 不做 = 0；只加锁 M1 = +5~+8；只治草稿 M2 = +12~+20；两者 +17~+28 | 0 ~ +28 | 3,727 ~ 3,755 |
| B5-0 | `UiState` 307 行搬到 `ChatState.kt` | **−307** | 3,420 ~ 3,448 |
| B5-1~4 | 组内重组，字段总数不变 | 0 | 3,420 ~ 3,448 |
| B6 | 分页状态 + 合成 | **+50 ~ +100** | **3,470 ~ 3,548** |

→ **VM 文件：B6 之前 ≈ 3,420（不做 B4）/ 3,448（做满 B4）；B6 之后 ≈ 3,470 ~ 3,548**。
相对 3,793 净减 ≈ **245 ~ 323 行**，非空行从 3,523 降到 ≈ 3,180~3,300。

**为什么 B1 实测 −24 而预估 −63**（诚实记录，别下次又估错）：

- 13 个 getter 从 65 行压成 13 行（−52）—— 这部分没错；
- 但"装配输入"必须先有一次 `resolveEffectiveConfig(...)` 调用：`eager` 块 **23 行**，这是原方案里
  根本没算进去的（原方案默认这些 `?:` 链凭空消失，实际它们变成了显式参数）；
- B3 的"18 行 → 净 −8"也没兑现：helper 自身 8 行（含注释）、替换省 12 行，净 −4；
- 又新增 7 行 import。

**结论**：B1 的价值是**把规则搬进 core 并有 20 条测试**，不是行数。用行数当 KPI 会误判这一批"白干了"。

**口径 B：整个仓库的代码量**（不是同一件事）

| 批次 | 仓库总量 |
|---|---|
| B1 | **+≈120**（`EffectiveConfig` + `resolve` + ≥12 条测试），另有 data 层 3 处 import 调整 |
| B2 | **+≈46**（core 新文件）+ 测试 ≈ +60 |
| B3 | −8 与 +6 已在 B1 内 |
| B5-0 | **±0**（同一个类换文件） |
| B6 | **+100 ~ +150** |

→ **"拆完总代码变少"是错的**：VM 文件少约 300 行，仓库总量反而**增加约 200~300 行**（新的纯函数、测试、分页状态）。
**这正是应该追求的方向**（职责外移 + 可测），但不要拿它当"精简"宣传。

**不再有 2,600 行目标。** 验收改为：行为不变 + 职责清晰 + 可测性提升 + B0 棘轮只降不升。
要继续把 VM 压到 2,600 以下，只能把整块职责（模型档案 599 / 正则 492 / 世界书 ~300）拆成独立类 ——
那是**新增 3 个抽象层**，与"不制造 Manager/Helper"冲突 → 列为**可选的 C 阶段，需单独决定，本轮不做**。

## 五、执行顺序（最终推荐）

`B0 → B1（含 B3）→ B2 → [B4 可选，可插在这里] → B5-0 → B5-1…B5-4 → B6`

| 顺序 | 为什么此时做 | 与第二版的差别 |
|---|---|---|
| B0 | 先把尺寸钉住，否则后面每批都在涨 | 不变 |
| B1 | 最独立、纯数据类上的纯计算；验证"抽到 core + 转发"流程 | 含 B3（18 行不值一轮 CI） |
| B2 | 照抄 B1 的流程；唯一还端着 UiState 的纯计算 | 前提改为"收窄 + 落 core"，不碰线程 |
| B4 | **降级为可选**：风险真实但概率低、后果可恢复；不做也不阻塞 | 原为必做批次，现为"可选加固" |
| B5-0 | 42 个文件一次改，要等流程跑顺 | 保留 |
| B5-1~4 | 引用最多的 `Gen` 放最后 | 保留 |
| B6 | 要动数据读法与滚动锚点，混在别处无法归因 | 保留（且必须先成文设计） |

## 六、验收矩阵

| 批次 | CI | 单测（core，本机） | 真机 |
|---|---|---|---|
| B0 | 6 个 check + 棘轮（故意加一行要变红） | — | — |
| B1 | 绿 + 旧 168 条全绿 | 优先级/边界/空串/`"off"` ≥12 条 | 改采样+人设+作者注 → 「查看请求」与界面一致 |
| B2 | 绿 + 世界书夹具全绿 | 固定 seed 确定性 + 三级书 + 强制开启 ≥5 条 | 发一条消息，核对世界书条目数 |
| B4（默认不做；若做） | 绿 | —（依赖仓库，不可测） | 按「打开档案编辑 → 别处改 baseUrl → 回来保存」序列复现；M2 之后 baseUrl 不应被冲回 |
| B5-0 | 绿 | — | 进聊天/切卡无任何变化 |
| B5-1~4 | 每刀绿 | — | 四个回归动作 + 状态清单对账 |
| B6 | 绿 | 分页切片 + 游标推进 | 300+ 条会话首屏、向上翻页、删除/撤回、续接 |

## 七、Git / CI / APK / 回退（区分已验证与推断）

**【已验证，2026-11-28 实测】**：

- 触发：`push` 到 `main`、`push` 任何 `apk-*` tag、PR、手动；
- **tag 构建不会更新 `apk-latest`**：核对了 run `46126860611`（tag `apk-m233`）——
  `公开 APK（apk-latest release）` = **skipped**，`公开 APK（回退点 tag）` = success；
- `apk-latest` 只由 `refs/heads/main` 的构建覆盖（**文档提交也会覆盖它**）；
- APK 与 commit 绑定：`TAVERN_BUILD_ID = github.sha`（可从 dex 里查出）；
- `versionCode = github.run_number`（只增不减，不会拒装）；
- release 资产自带 `digest: sha256:…`，本机 `sha256sum` 逐字节核对一致（`apk-m233` 实测）；
- `tools/verify-apk.sh` 验 x.509 签名（x.509 摘要，不是文件摘要）。

**【推断，未实测】**：tag 构建是否也会跑「出 debug APK」的 smoke 步骤（配置上看会，未逐条核对 run 的 step 明细）。

**每批的验收记录必须写四个值**：commit sha、tag、APK 的 sha256、`BUILD_ID` 读数。
⚠️ **tag 只证明"这个 commit 被构建过"，不代表 APK 过了真机验收** —— 真机回执单独记。

**回退**：`git revert <sha>`；装机回退 = 装上一个 tag 的 APK（同一把签名，可覆盖安装）。

## 八、审查记录

### 第一轮（初版）→ 第二版：见第二版旧记录，结论保留：P0-2 已修、下标耦合不存在、≤2,600 撤回。

### 第二轮（参考 AI 意见）→ 第二版：已采纳 8 条、调整 2 条、拒绝 3 条（明细见提交 `b75277c` 说明）。

### 第三轮（本轮最终审计）→ 第三版

**必须修正（已改）**

1. **B2 的"纯函数"结论错误**：`LorebookScanner` 持有 `Random`，默认输出不确定。→ 改为"函数无副作用、
   但含随机性"，并要求测试传固定 seed、生产保留 `Random.Default`。
2. **预算算式不闭合**：漏加 B6 的 +50~100、B1 少算 14 行。→ 重新逐项计算，给出两个口径，
   撤销 `3,390–3,450` 这个区间。
3. **B4 的"单写入口"错误**：实际 5 个写入口，且 `saveProfileDraft` 写整条记录。→ 风险重新描述，
   并把 B4 降级为可选加固。
4. **`effectiveAuthorNote` 的"注释与代码矛盾"判断过于草率**：核实后两个写入点都归一空串，
   现实不可达。→ 改为"约定式保证 + 在 `resolve()` 内做防御性归一 + 测试钉住"。
5. **`ConfigOverride` 迁移依据不足**：不能凭"3 处引用"。→ 改为逐项核实（0 import / 0 注解 / 仅基础类型 /
   文件需拆分 / 接口留 data），并给出模块落点与理由（`core:model` + `core:preset`，不新建模块）。

**建议修正（已改）**

6. B0 的统计口径补全（扩展函数/override/嵌套 fun 分开统计、搬迁量记账）。
   *（第四轮又改了主指标：有效行 → 非空行，见下）*
7. B5 补"已核实无反射/别名/跨模块/测试依赖、构造默认值无外部上下文"六项检查。
8. B6 补"已有 `idx_messages_session(session_id, id)`、手写 SQLite 而非 Room、`AUTOINCREMENT`"
   三条事实，并把未决问题从 7 条扩到 9 条。
9. tag 与 `apk-latest` 的关系：从"待验证推断"升级为**实测已验证**（附 run id）。

**可以保留（未改）**

10. 批次顺序 B0 → B1 → B2 → B5-0 → B5-1~4 → B6 及每批不变量：与代码证据一致，无需变动。
11. 拒绝"新增 Manager/Controller 压到 2,600 行"的判断：维持。
12. `state.xxx` 373 处不需要机械替换的判断：维持（已核实是属性访问）。

### 第四轮（用户点名的六项小修）→ 本版

| # | 要求 | 本版处理 |
|---|---|---|
| 1 | 修正 B0 注释统计口径 | 主指标改为**非空行 3,523**（无歧义）；文件总行/空行/注释行只打印；说明"有效行"要排除字符串里的 `//`、`*`，不能依赖"当前文件恰好没有多行字符串" |
| 2 | 明确 B1 解析结果与 `UiState.copy()` 生命周期 | 写死：`copy()` 会跑构造 → 要么每次重算、要么 `by lazy(NONE)` 每实例至多算一次；**新实例不继承旧 lazy**，故不会过期；禁止 companion 缓存/按 hashCode 记忆化；加"新旧公式对照"测试作为等价性硬验收 |
| 3 | 补充 B2 随机数调用顺序不变的验证 | 加 `RecordingRandom` 断言抽号序列 + 固定 seed golden；并注明"与搬迁前 app 旧路径逐位对齐"**core 验不了**，只能真机核对激活条数 |
| 4 | 修正 B4 过期草稿覆盖风险 | 新增**风险 A**（可达操作序列：编辑档案 → 别处改 baseUrl → 回来保存 → 被草稿旧值覆盖，`:2066/2100`）；原并发问题降为**风险 B** |
| 5 | B4 做与不做的预算分别列出 | 四种方案表：不做 0 / M1 加锁 +5~8（只治 B）/ M2 草稿基线合并 +12~20（治 A）/ 两者 +17~28；**推荐默认不做**，触发条件写明 |
| 6 | 明确 B6 的 SQL 分页归属 | 按 ADR 0003 的原文签名 `messages(sessionId, limit, beforeId)`：SQL 只在 `data/chat/impl`、索引已在 `TavernDatabase`（无需迁移）、VM 只持游标、`core` 不加；并记下接口注释与 ADR 措辞相反的待修点 |

## 九、未验证事项（每条给最小验证方法）

| # | 事项 | 状态 | 最小验证方法 |
|---|---|---|---|
| 1 | `./gradlew test` 是否覆盖 `:app` 的单测（`check-tests-ran.sh` 会不会变红） | 待验证 | 临时在 `app/src/test` 放 1 条 `@Test`，push，看 CI 的「纯 JVM 单测」是否跑到它；随后撤销 |
| 2 | B4 风险 B（并发交错）的实际概率 | 待验证（推断为低） | 一次性临时构建：在 5 个写入口打"入口名 + 时间戳"，连打 20 字符后立刻保存档案；测完撤销。**风险 A（过期草稿）不靠测量，靠上面的操作序列复现** |
| 3 | B6 向上翻页与 `reverseLayout=true` 的锚点配合 | 待验证（B6 前置） | 技术设计阶段先写"锚点补偿方案"，再在真机上用 300+ 条会话验 |
| 4 | `EffectiveConfig` 用 `lazy(NONE)` 与"构造体重算"的实际开销差 | 待验证（推断可忽略） | B1 实施后对比 300+ 条会话的滚动帧时间（`scroll` 日志），两者语义都正确，只比开销 |
| 5 | tag 构建是否也跑签名冒烟步骤 | 推断 | 查一次 tag run 的 job steps 明细 |
| 6 | B1 迁移后 `data` 层 3 个文件的 import 是否有遗漏（本机编不了 data） | 待验证 | CI 的「出 debug APK」job 会编译 `:data`；红了按 `build-<sha7>.log` 修 |

---

**本轮只做审计与文档修订：未修改生产代码、未修改测试、未修改 CI、未创建脚本、未新建类、未创建 commit 或 tag。**
