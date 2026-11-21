# core/ —— 纯 JVM 兼容内核

**这里的所有模块禁止出现 `android.*` / `androidx.*`**（铁律 #1，用 `tools/check-core-purity.sh` 检查）。

原因：本机没有 Android SDK、没有 KVM、可用内存不到 1GB。把兼容性逻辑放在纯 JVM 模块里，是这台机器上唯一能"改一行、跑一次单测"的回路；一旦混入 Android 依赖，这个项目就只剩"推 CI 等结果"一种开发方式。

模块与职责见 [../docs/dev/layout.md](../docs/dev/layout.md)：

| 模块 | 职责 |
|---|---|
| `card-format` | PNG `tEXt` chunk 读写、charx、V2/V3 字段映射 |
| `chat-format` | JSONL 流式读写（metadata 首行 + 逐行消息） |
| `prompt` | 提示词组装：顺序、宏、names_behavior、裁剪 |
| `lorebook` | 世界书激活：关键字、selectiveLogic、递归、概率、预算 |
| `tokenizer` | jtokkit + 模型→编码映射 |
| `preset` | 预设与采样参数模型 |
| `ai` | provider 适配、SSE 流式、重试、取消 |

规矩：
- 开 `explicitApi()`，对外只留一个入口类，其余 `internal`。
- 每个模块的单测读 `fixtures/expected/` 断言，不手写期望字符串。
- 不在这里读文件、不在这里发网络请求之外的事（`ai` 例外，它就是网络层）。
