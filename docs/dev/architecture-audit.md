# 架构审计（2026-11-26，只读）

> 一次性审计，不随代码更新；**判定以代码为准**，与其它文档冲突时以代码和
> [AGENTS.md](../../AGENTS.md) 为准。这里记录的是「当时是什么样 + 为什么」，不是规范。
>
> | | |
> |---|---|
> | 基线 | `f5ada25`（HEAD 只改 docs；代码与 `9c280b4` 相同） |
> | 规模 | 123 个 `.kt` / 21,906 行：`app` 44 / 14,183，`data` 22 / 2,276，`core` 9 模块 / 5,447 |
> | 方法 | 通读全部源码 + `git log` 规模统计 + 跑仓库自带闸门脚本；**未构建、未跑真机、无性能采样** |
> | 边界 | 结论分「事实（有代码行）」与「推断（有行但未实测）」，附录 B 分开列；推断项要坐实得看附录 C |
> | 关系 | 与 [structure-audit.md](structure-audit.md)（2026-11-24，讲复用层与目录分层）互补：那份管「怎么摆」，这份管「职责、正确性、维护成本」 |

---

## 0. 结论摘要

1. **分层方向是对的**：`app → data → core` 单向无环，`core` 是纯 JVM 且有 24 个测试类 + fixture，
   这是整个仓库最健康的部分。
2. **但 UI 与 ViewModel 合成了一坨**：一个 `ChatViewModel` 3,137 行 / 109 个 public 方法 /
   喂 65 字段的单一 `UiState`；2 天内涨了 1,832 行，近两天 182 个提交。
3. **`app` + `data` 零自动化测试**（17k 行），CI 只测 `core`；回归靠真机清单。
4. **发送路径在主线程做分词与世界书扫描**（P0-2），长会话下是掉帧/ANR 面。
5. **流式任务没有所有权**（P1-1）：离页/换会话不 cancel，可被孤立成「停不掉的并发流」。
6. **状态更新是全树级**：无 `@Immutable`、无 stability 配置、页面参数带非 `@Stable` 的 ViewModel，
   每个 token 都会让整棵子树重组（P1-3）。
7. 其余是可控的技术债：三个自制序列化格式、原子写不一致、缓存无失效、
   Key 放在 `UiState` 里、每次请求 new 一个 `OkHttpClient`。

---

## 1. 实际采用的架构模式（不推测，逐条给证据）

**「单 Activity + 单 ViewModel + 单一不可变 UiState + StateFlow 的单例状态机式 MVVM」**，
事件侧是**直接方法调用**，不是 MVI（没有 Intent、没有 reducer）。

| 判据 | 事实 | 位置 |
|---|---|---|
| 单 Activity | 全仓只有 `MainActivity`，只做 `enableEdgeToEdge()` + `setContent` | [MainActivity.kt:20-23](../../app/src/main/kotlin/app/tavern/MainActivity.kt#L20-L23) |
| 单 ViewModel | 全仓唯一 `ViewModel` 子类；`MainScreen` 用 `viewModel()` 取一次并往下传 | [ChatViewModel.kt:95](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L95)、[MainScreen.kt:170](../../app/src/main/kotlin/app/tavern/MainScreen.kt#L170) |
| 单状态源 | `MutableStateFlow(UiState)`；全 App 只在 `MainScreen` 收一次 | [ChatViewModel.kt:388-389](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L388-L389)、[MainScreen.kt:171](../../app/src/main/kotlin/app/tavern/MainScreen.kt#L171) |
| 无 reducer | 109 个 public 方法各自 `_state.update { it.copy(...) }`（共 142 处 copy） | [ChatViewModel.kt](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L158-L368) |
| UI 直连 VM | 36 个 composable 参数是 `viewModel: ChatViewModel`，24 个直接收 `state: UiState` | 全仓 `grep "viewModel: ChatViewModel"` |
| DI 手写 | `AppGraph` 单例 + 双重检查锁，9 个仓储 | [AppGraph.kt:29-113](../../app/src/main/kotlin/app/tavern/AppGraph.kt#L29-L113) |
| 导航自写 | `Destination` 枚举 + `PageStack`，明确不用 navigation-compose | [Screen.kt:9-10](../../app/src/main/kotlin/app/tavern/Screen.kt#L9-L10)、[PageStack.kt:53-71](../../app/src/main/kotlin/app/tavern/PageStack.kt#L53-L71) |
| Repository 模式 | 接口在 `data` 包根，实现放 `impl/`；**接口全是同步阻塞函数** | [ChatRepository.kt:60-61](../../data/src/main/kotlin/app/tavern/data/chat/ChatRepository.kt#L60-L61) |
| 无 domain 层 | 没有 use-case/interactor；提示词组装、世界书扫描、三级配置解析都在 ViewModel | [ChatViewModel.kt:2641-2714](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2641-L2714) |
| 无持久化框架 | SQLite 走 `SQLiteOpenHelper`（ADR 0003）；其余是 SharedPreferences + 文件 | [TavernDatabase.kt:13-14](../../data/src/main/kotlin/app/tavern/data/chat/impl/TavernDatabase.kt#L13-L14) |

### 1.1 与文档的偏差

- AGENTS.md 的画法里有 `feature/` 一层，**实际是 0 个 `.kt` 的空目录**（`feature/` 只有 `AGENTS.md`）。
  界面全在 `:app` 的扁平包 `app.tavern` 下（44 文件）。
- 依赖方向与文档一致（`app → data → core`），只是少了一层。

---

## 2. 架构图

### 2.1 编译期模块图（DAG，无环）

```
:app  (com.android.application, Compose, 14183 行)
 ├──▶ :data  (android.library, 2276 行)
 │      ├──▶ :core:model          (叶子, 208)
 │      ├──▶ :core:card-format    ──▶ :core:lorebook
 │      ├──▶ :core:regex
 │      └──▶ :core:preset
 ├──▶ :core:model
 ├──▶ :core:card-format
 ├──▶ :core:chat-format ──▶ :core:model
 ├──▶ :core:prompt ──▶ :core:{card-format, lorebook, model, preset}
 ├──▶ :core:lorebook   (叶子, 1307)
 ├──▶ :core:tokenizer  (叶子, jtokkit)
 ├──▶ :core:regex
 └──▶ :core:ai ──▶ :core:model  (okhttp + okhttp-sse)

:feature  ✗ 空目录（0 个 kt 文件）
```

声明见 [app/build.gradle.kts:68-77](../../app/build.gradle.kts#L68-L77)、
[data/build.gradle.kts:27-31](../../data/build.gradle.kts#L27-L31)；
`tools/check-core-purity.sh` 通过（`core/**` 里 `android.*`/`androidx.*` 零命中）。

### 2.2 运行时结构

```
MainActivity (ComponentActivity)
└─ MainScreen  ← 唯一 StateFlow 收集点（collectAsStateWithLifecycle）
   ├─ TavernTheme(mode, preset)
   ├─ PageStack（自写；离场页保留 ~280ms；LocalPageIsCurrent 是动态 CompositionLocal）
   │    ├─ TabsRoot → SessionsScreen / CharactersScreen / SettingsScreen
   │    └─ ChatScreen / CharacterDetailScreen / SettingsSubPage /
   │       WorldBookEntriesScreen / ConfigPickerScreen / PresetEditScreen …（共 36 处接 VM）
   ├─ SnackbarHost（全局提示，压在页面栈之上）
   └─ StartupCurtain（首屏遮罩，booted=false 时盖住）

ChatViewModel : AndroidViewModel            ← 3137 行 / 109 public fun
   ├─ UiState（65 字段 + 14 个 effective* getter）──StateFlow──▶ 全树
   ├─ 9 个仓储（AppGraph 单例）:
   │    CardStore / SqliteChatRepository / PreferencesSettingsRepository /
   │    FileWorldBookRepository / PreferencesRegexRepository /
   │    FilePresetRepository / PreferencesConfigOverrideRepository /
   │    PreferencesSessionScanStateRepository / SqliteProfileRepository
   ├─ 内存态：card / detailRaw / detailRawId / openBook / worldInfoStates /
   │          globalBookEntries / job / keySaveJob
   └─ OpenAiCompatibleClient  ← 每次发送 new 一个（内含新 OkHttpClient）

存储：SQLite(tavern.db v4) · SharedPreferences(6 个文件) · filesDir(PNG/JSON) · SAF · Android Keystore
```

### 2.3 单次发送时序（现状）

```
点击发送（主线程）
 → viewModel.send()
   → applyRegex(用户输入)                             [主线程]
   → beginCompletion()
       ├─ scanWorldInfo() ← 世界书扫描 + jtokkit 分词   [主线程！]  ← P0-2
       ├─ PromptBuilder().build()                      [主线程]
       ├─ _state.update{ isStreaming=true, 清输入框, 写 requestPreview }
       └─ job = viewModelScope.launch {
              append(用户消息)                          [IO]
              client.stream(request).collect {
                  Delta     → _state.update{ streamingText }   ← 每 token 一次全树重组（P1-3）
                  Completed → finish() → append(助手) + refreshSessions()  [IO]
                  Failed    → fail() → 回滚用户消息 + 还原输入框
              }
          }
```

### 2.4 存储与生命周期

| 数据 | 后端 | 位置 |
|---|---|---|
| 会话/消息/swipe/模型档案 | SQLite `tavern.db` v4（外键级联删除、单 `SQLiteOpenHelper`） | [TavernDatabase.kt:13-117](../../data/src/main/kotlin/app/tavern/data/chat/impl/TavernDatabase.kt#L13-L117) |
| API Key | Android Keystore AES-GCM，密文进 SharedPreferences（IV 与密文一起存） | [KeystoreSecretStore.kt:22-83](../../data/src/main/kotlin/app/tavern/data/secret/impl/KeystoreSecretStore.kt#L22-L83) |
| 连接/采样/外观 | SharedPreferences `tavern.settings` | [PreferencesSettingsRepository.kt:45-64](../../data/src/main/kotlin/app/tavern/data/settings/impl/PreferencesSettingsRepository.kt#L45-L64) |
| 三级配置覆盖 | SharedPreferences `config_override`（自制 `key=value` 文本） | [PreferencesConfigOverrideRepository.kt:21-100](../../data/src/main/kotlin/app/tavern/data/config/impl/PreferencesConfigOverrideRepository.kt#L21-L100) |
| 角色卡 | `filesDir/cards/*.png` + `.meta` 缓存 | [CardStore.kt:24-33](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L24-L33) |
| 世界书 / 预设 | `filesDir/worldinfo/*.json`、`filesDir/presets/*.json` + prefs 存开关 | [FileWorldBookRepository.kt:17-21](../../data/src/main/kotlin/app/tavern/data/worldinfo/impl/FileWorldBookRepository.kt#L17-L21)、[FilePresetRepository.kt:19-22](../../data/src/main/kotlin/app/tavern/data/presets/impl/FilePresetRepository.kt#L19-L22) |
| 世界书计时状态 | SharedPreferences `session_scan_state`，一 session 一个 key | [PreferencesSessionScanStateRepository.kt:7-27](../../data/src/main/kotlin/app/tavern/data/sessionstate/impl/PreferencesSessionScanStateRepository.kt#L7-L27) |
| 应用日志 | `filesDir/logs/app.log`（内存环形 800 条 + 文件滚动 1 MB） | [AppLog.kt:20-108](../../data/src/main/kotlin/app/tavern/data/log/AppLog.kt#L20-L108) |

---

## 3. 主要模块及其职责

| 模块 | 行数 | 实际职责 | 备注 |
|---|---|---|---|
| `:app` | 14,183 | **UI + ViewModel + 导航 + DI 装配**，四件事在同一个扁平包 | `ChatViewModel` 3,137 / `ChatScreen` 1,469 / `MainScreen` 765 / `SettingsPages` 660 |
| `:data` | 2,276 | 仓库接口 + 9 个实现、SQLite schema/迁移、Keystore、应用内日志 | 零第三方依赖（无 Room/DataStore），接口全同步 |
| `:core:model` | 208 | `ChatMessage`/`ChatRole`/`ModelProfile(s)` | 叶子 |
| `:core:card-format` | 778 | PNG `tEXt`、chara/ccv3、卡内嵌书读写 | 依赖 lorebook |
| `:core:chat-format` | 440 | ST `.jsonl` 流式读写 | |
| `:core:prompt` | 948 | 提示词组装（顺序/宏/裁剪） | 依赖 4 个 core 模块 |
| `:core:lorebook` | 1,307 | 激活：关键字/selectiveLogic/递归/概率/预算/sticky | 最重的纯逻辑，646 行的 `LorebookScanner` |
| `:core:regex` | 895 | 用户脚本 + 内置显示规则 + `InlineMarkup` | |
| `:core:preset` | 292 | 预设解析、采样参数 | |
| `:core:tokenizer` | 129 | jtokkit + 模型→编码映射 | |
| `:core:ai` | 450 | OpenAI 兼容客户端，SSE、取消、失败走事件 | okhttp + okhttp-sse |
| `:feature` | 0 | **不存在**（空目录） | 文档已承认，见 §1.1 |

### 3.1 职责边界的实际状态（越界清单）

- **UI 越界 3 处**：`CardAvatar`/`CardHero`/`CardFrost` 直接 `AppGraph.cards(context)` 读文件并解码
  （[CardAvatar.kt:49](../../app/src/main/kotlin/app/tavern/CardAvatar.kt#L49)、
  [:110](../../app/src/main/kotlin/app/tavern/CardAvatar.kt#L110)、
  [:181](../../app/src/main/kotlin/app/tavern/CardAvatar.kt#L181)）；
  `MainActivity` 直接读偏好对齐窗口底色
  （[MainActivity.kt:38](../../app/src/main/kotlin/app/tavern/MainActivity.kt#L38)）。
- **ViewModel 越界成「应用服务层」**：卡片编目、会话管理、三级配置解析、提示词用例、世界书调度、
  预设编辑、主题设置、5 个文件导入、流式客户端持有者——全在一个类里。
- **data 越界（轻）**：`CardStore` 自带一套 `.meta` 序列化格式与 Base64 编解码
  （[CardStore.kt:259-312](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L259-L312)）。

---

## 4. 严重问题清单

> 严重度按「会不会造成数据错误 / 卡死 / 无法维护」判定，不按修复难度。

### 4.1 P0（现在就在付代价）

#### P0-1 `ChatViewModel` 是 God Class，且是增长最快的东西

- 3,137 行、**109 个 public 方法**、27 个私有函数、72 个 `viewModelScope.launch`、
  104 个 `withContext(Dispatchers.IO)`、42 个 `runCatching`。
- `UiState` 65 字段 + 14 个 `effective*` 派生 getter（三级配置的生效值）。
- 规模曲线：**144 行（首版）→ 2,391 行（20 个提交前）→ 3,137 行（现在）**
  （`git show <rev>:app/.../ChatViewModel.kt | wc -l` 逐版本统计）。
- 近两天提交 182 个，其中 25 个动了这个文件；`ChatScreen` 39 个。
- 成本：任何改动都要读它；而本机编不了 `:app`，改错只能在 CI 上发现（4 分钟）。

#### P0-2 发送路径在主线程做重活（分词 + 世界书扫描 + 提示词组装）

`beginCompletion` 里 `scanWorldInfo()` 与 `PromptBuilder().build()` 在
`job = viewModelScope.launch`（第 2716 行）**之前**执行，即运行在点击回调的主线程
（[ChatViewModel.kt:2641-2716](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2641-L2716)）。
其中：

- 对**整段历史**逐条 `Tokens.countAsMessage`（jtokkit 编码）算预算
  （[:2568](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2568)）；
- 每个世界书条目也要过 token 计数；
- 之后同步写 SharedPreferences（[:2590](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2590)）。

聊天越长越慢。`docs/dev/status.md` 自己记着「单条 269 行是常态」，这是明确的掉帧/ANR 面。

#### P0-3 `app` / `data` 零自动化测试

24 个测试文件**全部**在 `core`（`find -name '*Test.kt'`）；`app` 与 `data` 的 `src/test` 是 0 个文件。
17k 行 UI+VM+持久化没有一条自动化断言，CI 的「纯 JVM 单测」job 也覆盖不到。
对照 `core` 的 fixture 驱动（兼容行为必须有夹具），这是最大的不对称。

### 4.2 P1（会造成错误数据 / 卡死 / 明显 bug）

#### P1-1 流式任务无所有权：可被孤立、无法停止、可并发

- `job` 是单个字段（[ChatViewModel.kt:410](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L410)）；
- `beginCompletion` 第 2716 行**无条件覆盖**它；
- `closeChat()`（[:1869](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1869)）与
  `openSessionInternal()`（[:949-1030](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L949-L1030)）
  都不 cancel、也不置 `job=null`；只有 `stop()` 会
  （[:1987](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1987)）。

后果链：离开聊天页 → 流继续跑（旧会话照样落库，见
[:2774](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2774)）→ `openSessionInternal` 把
`isStreaming` 重置成 false（[:1010](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1010)）→
用户可以再发一条 → `job` 被新任务覆盖 → **旧流永远停不掉**，两路流并发。

#### P1-2 `stop()` 与 `finish()` 可能双重落库（窄窗口）

`stop()` 把 `streamingText` 落库并追加进 transcript
（[:1983-2001](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1983-L2001)）；
`finish()` 在 `Completed` 时也落库
（[:2734-2737](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2734-L2737)）。
`job.cancel()` 打断不了已经进入 `withContext(Dispatchers.IO)` 的块（块内无挂起点，会跑完再在边界抛）。
若「停止」晚于 `Completed` 一点点，同一条回复会被写两次。无互斥、无去重键。

#### P1-3 全树重组是结构性的，不是偶发

- 全仓 `@Immutable`/`@Stable` **0 处**，无 Compose stability 配置文件，无 `kotlinx-collections-immutable`；
  `UiState` 里是 `List<>`/`Map<>`，按稳定性推断即 unstable。
- 页面签名带 `viewModel: ChatViewModel`（36 处，未标注 `@Stable`）→ 永远不能 skip。
- 因此任何 `_state.update`（包括**每个 token** 更新 `streamingText`）都会让读 `state` 的整棵子树失效。
- 叠加：每个气泡每次重组都重跑显示正则 + `InlineMarkup` 解析并重建 `AnnotatedString`
  （[ChatScreen.kt:1247](../../app/src/main/kotlin/app/tavern/ChatScreen.kt#L1247)、
  [:1283](../../app/src/main/kotlin/app/tavern/ChatScreen.kt#L1283)；
  [DisplayText.kt:52-56](../../app/src/main/kotlin/app/tavern/DisplayText.kt#L52-L56)），
  没有按消息内容 remember → 串流时是 O(n²)。

#### P1-4 头像/卡图缓存无失效机制

`avatarCache` / `frostCache` / `heroCache` 是进程级 `LruCache`，key = `cardId`
（[CardAvatar.kt:35](../../app/src/main/kotlin/app/tavern/CardAvatar.kt#L35)、
[:38](../../app/src/main/kotlin/app/tavern/CardAvatar.kt#L38)、
[:94](../../app/src/main/kotlin/app/tavern/CardAvatar.kt#L94)）。
全仓只有 get/put，**没有任何 invalidate**；而「覆盖保存原卡」不改文件名
（[CardStore.kt:181-199](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L181-L199)），
删卡后重导入同名卡也复用同一 id。结果：改卡/换图后，列表与详情继续显示旧图，直到进程重启。
另外缓存不受 `onTrimMemory` 约束（`heroCache` 是 900px × 4，推断约 10–30 MB native 常驻）。

#### P1-5 消息全量载入 + UI 下标 ↔ DB 行号强耦合

- `messages(sessionId)` 一次读整个会话
  （[SqliteChatRepository.kt:103-126](../../data/src/main/kotlin/app/tavern/data/chat/impl/SqliteChatRepository.kt#L103-L126)），
  与 `data/AGENTS.md` 第 8 行「消息分页读，不整表载入内存（长聊天会 OOM）」**直接冲突**；
  接口注释也承认「分页接口等长聊天真的出现再加」
  （[ChatRepository.kt:60-61](../../data/src/main/kotlin/app/tavern/data/chat/ChatRepository.kt#L60-L61)）。
- `deleteMessageAt(index)` / `rewindToMessage(index)` / `selectSwipe(index)` 都拿 `transcript`
  下标去 `stored.getOrNull(index)` 找 DB 行
  （[:3055](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L3055)、
  [:3096-3100](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L3096-L3100)、
  [:3027-3029](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L3027-L3029)）。
  现在 transcript 与库 1:1 才没出事；一旦加入「不入库的 system 行 / 折叠消息」，就是**删错一行**。

#### P1-6 每次发送新建一个 `OkHttpClient`

`beginCompletion` 每轮 `OpenAiCompatibleClient(...)`
（[:2690](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2690)），公开构造器内部
`OkHttpClient()`（[OpenAiCompatibleClient.kt:49-53](../../core/ai/src/main/kotlin/app/tavern/core/ai/OpenAiCompatibleClient.kt#L49-L53)），
`stream()` 里又 `.newBuilder()` 复制一份
（[:62-64](../../core/ai/src/main/kotlin/app/tavern/core/ai/OpenAiCompatibleClient.kt#L62-L64)）。
每个实例自带 Dispatcher + ConnectionPool，旧池空闲 5 分钟才回收 → 长对话里线程/连接反复创建，
且无法共享 DNS 与连接复用。

#### P1-7 写盘原子性不一致（卡是原子的，预设/世界书不是）

卡写入走 temp + rename 并留 `.bak.png`
（[CardStore.kt:186-195](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L186-L195)），但：

- 预设直接 `writeText`（[FilePresetRepository.kt:43](../../data/src/main/kotlin/app/tavern/data/presets/impl/FilePresetRepository.kt#L43)）；
- 世界书直接 `writeText`（[FileWorldBookRepository.kt:40](../../data/src/main/kotlin/app/tavern/data/worldinfo/impl/FileWorldBookRepository.kt#L40)）；
- 卡列表缓存 `.meta` 也直接 `writeText`
  （[CardStore.kt:304](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L304)）。

断电/杀进程留下半截 JSON 后，`list()` 是 `runCatching{…}.getOrNull()` **静默跳过**
（[FilePresetRepository.kt:26](../../data/src/main/kotlin/app/tavern/data/presets/impl/FilePresetRepository.kt#L26)、
[FileWorldBookRepository.kt:57](../../data/src/main/kotlin/app/tavern/data/worldinfo/impl/FileWorldBookRepository.kt#L57)）
→ 用户的预设/世界书「凭空消失」，没有任何提示。

#### P1-8 `AppLog` 在调用线程同步写文件（含主线程）

`log()` 在 `synchronized` 里对每条日志做一次 `file.appendText(...)`（open/write/close）
（[AppLog.kt:70-81](../../data/src/main/kotlin/app/tavern/data/log/AppLog.kt#L70-L81)）。
日志点遍布 UI、导航、滚动（`tag=nav` / `tag=scroll` / `tag=ui`），也就是这些路径上有可感知的文件
I/O，还共享一把全局锁。滚动诊断已经不得不自己做「按时间窗合并」
（[ChatScreen.kt:1406-1413](../../app/src/main/kotlin/app/tavern/ChatScreen.kt#L1406-L1413)）
来压住调用量——设计在替实现兜底。

### 4.3 P2（真实但不紧急）

| # | 问题 | 证据 |
|---|---|---|
| P2-1 | **API Key 放在 `UiState` 里**：`UiState` 是 data class，`toString()` 会带出 Key；任何「打印 state / 抓 Compose 快照 / 加崩溃上报」都会泄露，与「Key 不落明文、不进日志」的卖点冲突 | [ChatViewModel.kt:160](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L160) |
| P2-2 | Key 双写两份（老字段 + 档案），且有两个独立的 `KeystoreSecretStore` 实例 | [:1575-1576](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1575-L1576)、[PreferencesSettingsRepository.kt:19](../../data/src/main/kotlin/app/tavern/data/settings/impl/PreferencesSettingsRepository.kt#L19)、[SqliteProfileRepository.kt:23](../../data/src/main/kotlin/app/tavern/data/profiles/impl/SqliteProfileRepository.kt#L23) |
| P2-3 | 连接参数逐字符落库：`persistConnection()` 每次按键 launch 一个协程、无串行化（并发写、顺序不保证）；Key 有 600ms 防抖，但 `onCleared` 会丢掉最后 600ms | [:1581-1598](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1581-L1598)、[:1567-1579](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1567-L1579) |
| P2-4 | 世界书 sticky/cooldown 状态只在内存（注释自认）；每次扫描写 prefs，但只在打开会话时读回，冷启动不读 | [:2005-2012](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2005-L2012)、[:973-976](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L973-L976) |
| P2-5 | `maxContext = 3795`、`userName = "User"` 硬编码在 ViewModel（换模型 / 多人设 / 长上下文要改代码） | [:2562](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2562)、[:2567](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2567) |
| P2-6 | 迁移缺失直接 `error()` 崩溃（有意严格，但升级面风险集中在一处） | [TavernDatabase.kt:52](../../data/src/main/kotlin/app/tavern/data/chat/impl/TavernDatabase.kt#L52) |
| P2-7 | 无 `SavedStateHandle`：进程被杀后导航位置与 `ProfileDraft` 草稿全丢 | `UiState` 无 SavedState 来源 |
| P2-8 | 无网络重试/退避：一次失败即 `Failed`，靠用户手动重发（有回滚，但见 P1-2） | [OpenAiCompatibleClient.kt:90-96](../../core/ai/src/main/kotlin/app/tavern/core/ai/OpenAiCompatibleClient.kt#L90-L96) |

### 4.4 汇总

| 级别 | 数量 | 一句话 |
|---|---|---|
| P0 | 3 | God Class 增长、主线程重活、app/data 零测试 |
| P1 | 8 | 流式所有权、双写窗口、全树重组、缓存失效、全量载入、每请求 new client、原子写、同步日志 |
| P2 | 8 | Key 在 state、双份 Key、逐字符落库、计时状态只在内存、硬编码、迁移即崩、无 SavedState、无重试 |

---

## 5. 重复代码与循环依赖

**循环依赖：无。** 模块层是 DAG（见 §2.1，逐个 `build.gradle.kts` 核对）。
包层谈不上循环，因为 `:app` 是**一个扁平包**（44 文件）——这本身是「想拆拆不动」的同义词。

**重复代码（可直接消除的）：**

| 重复 | 位置 | 量 |
|---|---|---|
| SAF 导入四件套（`displayNameOf` + `openInputStream` + `readText` + `runCatching` + `notify`） | 聊天 [:1120-1160](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1120-L1160)、世界书 [:2052-2078](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2052-L2078)、预设 [:2333-2361](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2333-L2361)、正则 [:2462-2490](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2462-L2490) | 4 份，各约 25 行 |
| `sanitize(name)` 文件名清洗 | [FilePresetRepository.kt:109-112](../../data/src/main/kotlin/app/tavern/data/presets/impl/FilePresetRepository.kt#L109-L112)、[FileWorldBookRepository.kt:129-130](../../data/src/main/kotlin/app/tavern/data/worldinfo/impl/FileWorldBookRepository.kt#L129-L130)（卡还有第三份，[CardStore.kt:314-320](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L314-L320)） | 3 份，截断长度不一致（64 / 64 / 40） |
| `Set<Int> ↔ Set<String>` 转换 | [PreferencesSettingsRepository.kt:208-210](../../data/src/main/kotlin/app/tavern/data/settings/impl/PreferencesSettingsRepository.kt#L208-L210) vs [FileWorldBookRepository.kt:67-86](../../data/src/main/kotlin/app/tavern/data/worldinfo/impl/FileWorldBookRepository.kt#L67-L86) 内联 6 次 | — |
| 「读文件 → runCatching → 静默 null」 | 预设 / 世界书 / 卡缓存三处 | 3 份 |
| 自制文本序列化格式 | `ConfigOverride`（`key=value` + `\u001F`，[PreferencesConfigOverrideRepository.kt:71-100](../../data/src/main/kotlin/app/tavern/data/config/impl/PreferencesConfigOverrideRepository.kt#L71-L100)）、`.meta`（7 列 TSV + Base64，[CardStore.kt:267-306](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L267-L306)）、预设顺序（`ident\t0/1`，[FilePresetRepository.kt:74-95](../../data/src/main/kotlin/app/tavern/data/presets/impl/FilePresetRepository.kt#L74-L95)） | **3 个**：加一个字段要同步「写、读、空值语义」三处 |
| 巨型文件（单一职责失效） | `ChatViewModel` 3,137 · `ChatScreen` 1,469（页面+输入栏+气泡+选区+滚动锚点+菜单）· `MainScreen` 765（导航+Destination 映射+骨架+舞台） | — |

---

## 6. 错误处理、异常恢复、并发安全

### 6.1 做得好的（有代码证据）

- 流式失败**不抛异常**而是发事件，UI 能把「Key 不对」渲染成可重试提示
  （[OpenAiCompatibleClient.kt:26-36](../../core/ai/src/main/kotlin/app/tavern/core/ai/OpenAiCompatibleClient.kt#L26-L36)、
  [:90-96](../../core/ai/src/main/kotlin/app/tavern/core/ai/OpenAiCompatibleClient.kt#L90-L96)）。
- 取消会真的断 HTTP（`awaitClose { source.cancel() }`，
  [:110](../../core/ai/src/main/kotlin/app/tavern/core/ai/OpenAiCompatibleClient.kt#L110)），
  不会「点了停止还在烧 token」。
- 发送失败回滚刚写入的用户消息并还原输入框
  （[:2816-2839](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2816-L2839)），避免「重试一次多一条」。
- `KeystoreSecretStore` 解密失败不抛、返回 null，密钥失效不会让 App 起不来
  （[KeystoreSecretStore.kt:41-53](../../data/src/main/kotlin/app/tavern/data/secret/impl/KeystoreSecretStore.kt#L41-L53)）。
- 导入一律「先解析，解析不了不进目录」
  （[FilePresetRepository.kt:40-41](../../data/src/main/kotlin/app/tavern/data/presets/impl/FilePresetRepository.kt#L40-L41)、
  [FileWorldBookRepository.kt:35-37](../../data/src/main/kotlin/app/tavern/data/worldinfo/impl/FileWorldBookRepository.kt#L35-L37)、
  [CardStore.kt:129-131](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L129-L131)）。
- 崩溃兜底把堆栈落盘，真机可导出
  （[TavernApp.kt:30-38](../../app/src/main/kotlin/app/tavern/TavernApp.kt#L30-L38)）。

### 6.2 缺口

1. **异常被降级成「空」而不是「错误」**：42 处 `runCatching` 多数是 `getOrDefault(emptyList())`
   或 `getOrNull()`，失败与「真的没有」在 UI 上不可区分。最典型：首屏会话读取失败 → 显示
   「还没有会话」([:429-438](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L429-L438))；
   有的连日志都不打（[:901-902](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L901-L902)）。
2. **取消语义靠一处手工维持**：全仓只有 1 处显式 `catch (CancellationException) { throw }`
   （[:2902-2903](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2902-L2903)）。
   目前 `runCatching` 包的都是同步仓储调用，还没吞取消；但仓储接口一旦改成 `suspend`，
   就会静默吞掉取消——这是个没有闸门保护的隐形约定。
3. **没有统一的写串行化**：72 个 `launch` + 104 个 `withContext(IO)` 各自为政，
   没有 `Mutex`、没有单线程 dispatcher、没有事务边界包住「读-改-写」。
   唯一的结构性保护是单 `SQLiteOpenHelper`
   （[TavernDatabase.kt:109-117](../../data/src/main/kotlin/app/tavern/data/chat/impl/TavernDatabase.kt#L109-L117)）
   与 SQLite 事务（append / import / swipes 都正确用了 `beginTransaction`）。
4. **`CardStore` 的 `.meta` 读改写无锁**
   （[CardStore.kt:62-105](../../data/src/main/kotlin/app/tavern/data/cards/impl/CardStore.kt#L62-L105)）：
   两个并发 `list()`/`import()` 会互相覆盖缓存行。只影响性能缓存、不丢用户数据。
5. **`worldInfoStates` / `globalBookEntries` 是裸 `HashMap`**，靠「只在主线程碰」的隐式约定
   （注释里承认，[:2005-2013](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2005-L2013)），
   没有类型系统或断言兜住。

---

## 7. 中长期维护风险

| 风险 | 为什么现在就要管 | 证据 |
|---|---|---|
| **单点知识垄断**：改任何功能都要改 `ChatViewModel`，且它 2 天涨 1,832 行 | 交接/回滚成本随时间线性变差；`:app` 本机编不了，只能靠 CI 4 分钟 | 144→2391→3137 行 |
| **三层配置的「每层都要接」**：新增一个可配置项要改数据字段 + `ConfigKind` + 两级页面 + 持久化 + 生效 getter | 文档已把它写成硬性规则，说明踩过「显示 A、实际发 B」 | `app/AGENTS.md` 三级配置节；`UiState` 14 个 `effective*` |
| **三个自制序列化格式**、没有版本号 | 加字段/改语义要读三个 parser；坏文件静默丢弃（P1-7） | §5 |
| **零 app 测试 + 视觉回归靠人**：`check-ui-invariants.sh` 只查文本/结构，查不了观感 | 现在靠「真机清单 + v158 待验」的人工循环 | `.github/workflows/ci.yml` 只跑 core 单测 |
| **观感知识只在文档与真机上**：玻璃/AGSL/阴影/转场阈值都靠「真机实测」 | 无 KVM，CI 与模拟器都复现不了，回归只能等用户报 | ADR 0005/0006/0007、`pitfalls.md` |
| **文档开始成为负担**：指令链 64 KB 上限、已用约 20 KB，细节外迁 | 改功能要先读 4 份文档；文档与代码靠脚本对齐，覆盖面有限 | `AGENTS.md` 文档地图节 |
| **适配面在膨胀**：`core/ai` 只有 OpenAI 兼容一种；`maxContext`/用户名硬编码 | 「更多 provider / 多人设」都要动 ViewModel 与提示词入口 | P2-5 |

---

## 8. 改进建议与优先级

> 排序原则：先止血（主线程 / 数据正确性），再降维护成本（拆 ViewModel），最后才是体验优化。
> 每条都给落点，避免「重构」这种不可验收的词。

### 8.1 P0（1–2 天量级）

1. **把发送路径的重活移出主线程**
   `beginCompletion` 里 `scanWorldInfo` + `PromptBuilder().build` + token 统计整体挪进
   `viewModelScope.launch { withContext(Dispatchers.Default) { … } }`；UI 先进入
   `isStreaming=true` 的「准备中」态；`worldInfoStates` 只在该后台块里访问
   （或换成单线程 dispatcher / `Mutex`）。
   落点：[ChatViewModel.kt:2641-2716](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2641-L2716)，
   `scanWorldInfo` 内的 `save` 改到 IO。
2. **给流式任务定所有权**
   `job` 改成 `StreamingJob(sessionId, job)`；`openSessionInternal` / `closeChat` / `beginCompletion`
   在起新任务前按策略处理旧任务（取消，或明确「后台继续」并在 UI 上标记该会话正在生成）。
   `isStreaming` 从全局布尔改成按会话判断。
   落点：[:410](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L410)、
   [:1869](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1869)、
   [:949](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L949)、
   [:2716](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L2716)。
3. **消掉 `stop()` 的双写窗口**：先 `job.cancelAndJoin()`（或用 finished 标记 / DB 行 id 去重），
   确认 `finish()` 没写过再 append partial。
   落点：[:1983-2001](../../app/src/main/kotlin/app/tavern/ChatViewModel.kt#L1983-L2001)。
4. **给 `data` 层补 JVM 测试**
   接口是同步的、只依赖 4 个 `Context` API（SharedPreferences / File / ContentResolver /
   SQLiteOpenHelper），用 Robolectric 或把 `File`/`prefs` 抽成可注入句柄即可测：
   三级配置编解码往返、`.meta` 解析容错、DB 迁移 v1→v4、JSONL 往返。
   这是投入产出比最高的一处（2,276 行、全部是「错了就丢数据」的代码）。

### 8.2 P1（下一个迭代）

5. **拆 `ChatViewModel`（沿用 structure-audit 的结论：先抽纯逻辑，不搬 UI）**
   建议切分（与本审计的实际职责一一对应）：
   `ChatStreamUseCase`（发送/重生成/继续/停止）、`SessionController`（会话开关/删除/置顶）、
   `HierarchicalConfig`（三级继承 + 14 个 `effective*`）、`CatalogController`（卡/世界书/预设/正则的
   列表与导入）、`Appearance`（主题/视觉档/转场）。
   **建议先做 `HierarchicalConfig`**：纯函数、输入输出明确、能立刻补单测，顺带修掉「三层忘接」。
6. **止住重组成本**
   `UiState` 的列表字段换 `ImmutableList`，或给 `UiState`/`ChatMessage` 加 `@Immutable`
   并打开 stability 配置文件；气泡文本 `remember(message.content, regexScripts) { markupText(...) }`；
   把 `viewModel` 换成 lambda 回调或 `@Stable` 的 `Actions` 接口，让页面可 skip。
   ⚠️ 这是**行为无变化**的性能改动，适合在真机清单清空后再做，否则会和观感问题混在一起
   （[structure-audit.md](structure-audit.md) 修订第 3 条已点过这个顾虑）。
7. **`OkHttpClient` 单例 + 重试**
   `OpenAiCompatibleClient` 加一个 `OkHttpClient` 参数或 companion 单例；对流式失败加 1 次退避重试
   （仅限 5xx/超时，且只在未收到任何 Delta 时）。
8. **统一原子写 + 图片缓存失效**
   抽出 `writeAtomically(file, bytes)`（temp+rename）给预设/世界书/`.meta` 用；
   给 `CardAvatar` 加 `invalidate(cardId)`，在 `saveCard`/`saveCardAs`/`delete`/`import` 成功后调用
   （或把缓存 key 改成 `cardId + mtime + length`）。
9. **日志改异步单写线程**：`AppLog.log()` 只入内存队列 + 单线程分批量刷盘；主线程永不碰 I/O。

### 8.3 P2（顺手做）

10. 把 `apiKey` 从 `UiState` 拿出去（VM 内部持 `SecretRef`，UI 只显示「已配置/未配置」）。
11. 消息分页：`messages()` 加 `limit/offset`，聊天页先载最后 N 条，向上滚动再载
    （DB 已有 `idx_messages_session` 支撑，UI 可以后做）。
12. `maxContext` / 用户名 / 重试次数进「模型档案」或全局设置。
13. 合并三种自制文本格式为一个带 `version` 的 codec（或统一到 kotlinx.serialization，`core` 已有依赖）。

### 8.4 不建议做的事（与本审计结论冲突的常见「标准改法」）

- **不要为了「分层」把 `:feature` 拆出来**：现在拆要动 30+ 文件搬包，而本机编不了 `:app`，
  每个漏改的 `import` 都要等 CI 4 分钟才暴露（[structure-audit.md](structure-audit.md) 修订第 2 条已论证）。
- **不要把 `app` 的扁平包改成 `ui/ data/ vm/` 三级包**：同一 Gradle 模块内 `internal` 全可见，
  包名拦不住依赖，只增加改造成本。
- **不要引 Hilt/Koin/navigation-compose 来解决上面的问题**：真正的成本在 `ChatViewModel` 的职责与
  主线程重活，不在装配方式；换 DI 框架只会把 4 分钟的 CI 变得更慢。

---

## 附录 A：本次核查跑过的东西

```bash
bash tools/check-core-purity.sh      # OK: core 是纯 JVM
bash tools/check-kotlin-comments.sh  # OK: 123 个 Kotlin 文件无嵌套/未闭合块注释
bash tools/check-kotlin-imports.sh   # OK: 没有重复 import
find . -name '*Test.kt'              # 24 个，全在 core；app/data 的 src/test 为 0
for m in app data core/*; do wc -l; done   # 模块规模（见头部表）
git show <rev>:…/ChatViewModel.kt | wc -l  # 规模曲线 144 → 2391 → 3137
git log --format=%h -- <file> | wc -l      # 近两天 Churn：总 182 / VM 25 / ChatScreen 39
```

## 附录 B：事实 / 推断 / 待实测

| 结论 | 性质 |
|---|---|
| 模块 DAG、无环、core 纯 JVM | **事实**（构建脚本 + 脚本通过） |
| 单 VM / 单 UiState / 直连调用 / 手写 DI 与导航 | **事实**（代码行） |
| 发送路径若干重活在主线程 | **事实**（调用栈位置：都在 `launch` 之前） |
| 「长会话掉帧/ANR」 | **推断**（未测耗时；判据见附录 C） |
| 每个 token 全树重组 | **推断**（无 `@Immutable`/stability 配置是事实；实际跳过率需 Layout Inspector 验证） |
| 流可被孤立、可并发 | **事实**（无 cancel 路径）；「用户实际能触发」需一次真机操作验证 |
| `stop()` 双写 | **事实上的竞态窗口**（代码路径存在）；发生概率未测 |
| 缓存导致旧图 | **事实**（无 invalidate）；需真机复现一次确认「改卡后仍显示旧图」 |
| 内存占用 10–30 MB（heroCache） | **推断**（按 900px 解码尺寸估算） |

## 附录 C：下次复核怎么验（可执行判据）

1. **P0-2 是否还在**：在 `send()` 入口与 `job = viewModelScope.launch` 之间打断点/加日志，
   看 `scanWorldInfo` 与 `build` 是否在 main 线程；或对 200+ 条会话测 `send()` 到首个 Delta 的耗时。
2. **P1-3 是否还在**：加 `@Immutable` 前后各录一次长回复的 `Choreographer` 掉帧；
   或在 `MessageRow` 里打重组计数（`SideEffect { count++ }`）。
3. **P1-1 是否还在**：真机操作「流式中 → 返回 → 打开另一会话 → 发一条」，
   看 `tag=chat` 日志是否出现两路并行的 `完成/失败`；`stop()` 是否只停掉最后一轮。
4. **P1-4 是否还在**：改一张卡的名字/图 → 返回列表，看头像是否立刻更新（不重启进程）。
5. **P1-7 是否还在**：`grep -n "writeText" data/src/main` 应只剩日志与 `.meta`（或都没有）。
6. **P0-3 是否还在**：`find app data -path '*src/test*' -name '*.kt' | wc -l` 应为 0。
