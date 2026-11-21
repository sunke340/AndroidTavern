# AGENTS.md — `core/`（纯 JVM 内核）

本目录下的模块**禁止**出现 `android.*` / `androidx.*` / `Context` / `R.*`。检查：`tools/check-core-purity.sh`。

为什么这么硬：本机没有 Android SDK、没有 KVM、可用内存不到 1 GiB。兼容性逻辑放在纯 JVM 里，是这台机器上唯一"改一行、跑一次单测"的回路；一旦混入 Android 依赖，就只能推 CI 等结果。

## 模块

| 模块 | 职责 | 入口类 |
|---|---|---|
| `model` | `ChatMessage` / `ChatRole`，谁都能依赖的叶子 | — |
| `card-format` | PNG `tEXt` chunk、charx、V2/V3 映射 | `PngCard` / `CharacterCard` |
| `chat-format` | JSONL 流式读写 | `ChatFile` |
| `prompt` | 提示词组装：顺序、宏、names_behavior、裁剪 | `PromptBuilder` |
| `lorebook` | 激活：关键字、selectiveLogic、递归、概率、预算 | `LorebookScanner` |
| `tokenizer` | jtokkit + 模型→编码映射 | `Tokens` |
| `preset` | 预设与采样参数 | `SamplingPreset` |
| `ai` | provider 适配、SSE、取消 | `ChatCompletionClient` |

## 规矩

- 开 `explicitApi()`；对外只留一个入口类，其余 `internal`。
- **任何兼容行为都必须能被 fixture 断言**，单测读 `fixtures/expected/`，不手写期望字符串。
- 不读文件、不碰设置、不做生命周期——这些是 `data` 和 `feature` 的事。
- 顺序、裁剪、递归这类行为只能以原版实发请求为准；不要从单处源码推导（原版分三处组装）。
- 新增依赖前先问：标准库能不能做？已有依赖能不能做？
- 模块间依赖只能单向：`prompt → {card-format, model}`、`ai → model`。
- **测试方法必须返回 Unit**（`assertIs` 这类有返回值，别让它当方法体最后一句），否则 JUnit 静默跳过；`tools/check-tests-ran.sh` 守这条。

测试：`tools/gradle-local.sh :core:<模块>:test`（本机；CI 用 `./gradlew test`）
