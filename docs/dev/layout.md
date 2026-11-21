# 工程结构

本文规定代码、文档、夹具放在哪，以及谁可以依赖谁。规则与依赖方向冲突时**以本文为准**，改动需提 ADR。

## 1. Gradle 模块

模块路径 = `:` 分隔的目录名（`core/prompt` → `:core:prompt`）。

```
settings.gradle.kts        include(":app", ":core:card-format", ...)

app/                       :app                    Compose 入口、Navigation、DI 装配
core/
  model/                   :core:model             纯 JVM  ChatMessage / ChatRole（叶子，谁都能依赖）
  card-format/             :core:card-format       纯 JVM  PNG/charx/V2-V3 编解码（入口 PngCard / CharacterCard）
  chat-format/             :core:chat-format       纯 JVM  JSONL 读写（流式）
  prompt/                  :core:prompt            纯 JVM  提示词组装（兼容性心脏）
  lorebook/                :core:lorebook          纯 JVM  世界书激活算法
  tokenizer/               :core:tokenizer         纯 JVM  jtokkit + 模型→编码映射
  preset/                  :core:preset            纯 JVM  预设/采样参数模型
  ai/                      :core:ai                纯 JVM  provider 适配、SSE、取消（入口 ChatCompletionClient）
feature/
  chat/ character/ lorebook/ preset/ settings/     Compose 界面
data/                      :data                    Room、DataStore、SAF、Keystore
```

**`core/preset` 与 `core/ai` 为什么也在 `core`**：OkHttp 是纯 JVM 库，模型与请求构造不需要 Context。留在这里就能在本机（1.7GB、无 SDK）跑单测，这是本项目唯一可行的验证回路。

## 2. 依赖方向

```
:app ──▶ :feature:* ──▶ :data ──▶ :core:*
  └──────────────────────▶ :core:*        （app 直接用 core 允许）
```

- 单向，**反向一律禁止**：`core` 不知道 `data`，`data` 不知道 `feature`。
- `:feature:*` 之间**不许互相依赖**。需要共享 UI 组件 → 建 `:feature:common`，经 ADR 批准。
- `:core:*` 之间可以互依，但保持无环。当前依赖是：
  `:core:prompt → :core:card-format`、`:core:prompt → :core:model`、`:core:ai → :core:model`。
  方向不能反：卡片格式不该知道"提示词"，`ai` 不该知道"角色卡"。

## 3. 每个模块内部

```
:core:prompt/
  src/main/kotlin/app/tavern/core/prompt/
    PromptBuilder.kt          对外唯一入口
    model/                    PromptItem、ChatMessage、Macro 上下文
    internal/                 token 化、顺序拼装、裁剪（不对外）
  src/test/kotlin/app/tavern/core/prompt/
    PromptBuilderFixtureTest.kt   ← 读 fixtures/expected/*.json 断言
```

- `core/**` 开 `explicitApi()`：公开面越小，兼容性改动越可控。
- `internal/` 放实现细节，单测可直接测（test source set 同模块）。
- 每个模块一个对外入口类，其余全部 `internal`。

## 4. `feature` 的形状

一律 MVVM + 单向数据流，**禁止在 Composable 里直接摸 Room / 文件 / 网络**：

```
ChatScreen.kt        @Composable，只读 UiState、发 Event
ChatViewModel.kt     StateFlow<UiState>，调 usecase
ChatUseCase.kt       会话级编排（生成、swipe、重试），依赖 repository 接口
```

- 状态：`sealed interface ChatUiState`；事件：`sealed interface ChatEvent`。
- 导航用一个 `:app` 内的 NavHost，`feature` 只暴露 `fun NavGraphBuilder.chatScreen(...)`。

## 5. `data` 的形状

- Room：`CharacterEntity / MessageEntity / LorebookEntity / PresetEntity` + DAO + migration。
- 文件：导入导出走 SAF（`ACTION_OPEN_DOCUMENT`），`FileStore` 接口在 `data`，实现在 `data`。
- 密钥：`KeystoreSecretStore`，**任何明文都不落盘、不进日志**。
- 仓库接口**定义在 `data`**（`CharacterRepository` 等），实现放 `data/impl/`。`feature` 只看到接口。
  > 不把接口放 `core` 的原因：仓库概念是"应用怎么存取"，不是"领域规则"，放 `core` 会让纯 JVM 模块被迫理解 Android 存储。

## 6. 代码与文档、夹具、脚本的分工

| 东西 | 位置 | 规则 |
|---|---|---|
| 面向 agent/贡献者的规则 | 根 `AGENTS.md` + 各目录 `AGENTS.md` | 根放"每次都要遵守的"；模块目录放该模块独有的规则，就近优先、按需加载 |
| 方向与里程碑 | `docs/plan.md` | 少改；改了就说明方向变了 |
| 进度 | `docs/compat-matrix.md` | **每个 PR 都可能改**；只改状态列 |
| 决策 | `docs/adr/NNNN-*.md` | **只增不改**；推翻旧决策要写新 ADR 并标注 supersedes |
| 开发说明 | `docs/dev/*.md` | 怎么搭、怎么录夹具、怎么发版 |
| 环境事实 | `docs/environment.md` | 实测数据，环境变了就更新 |
| 夹具 | `fixtures/{cards,chats,worldinfo,presets,expected}/` | 命名 `P0-5-prompt-basic.json`，与矩阵 ID 对齐 |
| 脚本 | `tools/` | 只给开发用，产品代码不得依赖 |

## 7. 夹具命名约定

```
fixtures/
  cards/        P0-1-seraphina.png          输入：一张真实卡
  chats/        P1-9-simple.jsonl           输入：一段真实聊天
  worldinfo/    P1-3-lorebook.json          输入：世界书
  presets/      P1-5-default.json           输入：预设
  expected/     P0-5-prompt-basic.json      期望：发给 API 的完整请求体
```

- 一个 `expected/*.json` 对应矩阵里一个 ID；一个 ID 可以有多份（`-basic`、`-recursion`）。
- `expected` 里**不许出现真实 API Key**，用 `sk-test` 占位。
- 体积：整个 `fixtures/` 保持在 5MB 以内；大图先降采样。

## 8. 什么不该进这个仓库

- `vendor/SillyTavern`（只读参考，`.gitignore`，用 `tools/fixture/bootstrap.sh` 重建）。
- 构建产物、`local.properties`、APK、`.idea`。
- 真实用户数据、真实密钥、带隐私的聊天记录（夹具必须脱敏）。

## 9. 新增一个模块的判据

先问三个问题，都"否"就不要新建：

1. 它需要**独立在纯 JVM 上测试**吗？（是 → `core`）
2. 它会**被两个以上 `feature` 复用**吗？（是 → 考虑抽模块，否则先在 feature 内部）
3. 它有自己的**第三方依赖**且不希望污染别人吗？（是 → 独立模块）

否则：放进已有模块的子包。模块数量本身是成本。
