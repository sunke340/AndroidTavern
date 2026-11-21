# AGENTS.md — `feature/`

Compose 界面。每个 feature 一个子目录（`chat` `character` `lorebook` `preset` `settings`）。

## 硬规矩

- **MVVM + 单向数据流**：`Screen`（无状态 Composable，只读 `UiState` 发 `Event`）→ `ViewModel`（`StateFlow`）→ `UseCase` → repository 接口。
- **Composable 里不许直接访问 Room / 文件 / 网络 / DataStore。** 一律经 usecase（数据来源是 `data`）。
- **ViewModel 里不许出现 `Context`、`Activity`、`Resources`。** 需要字符串就传进来，别去拿。
- feature 之间**不许互相依赖**；共用 UI 抽 `:feature:common`（需 ADR）。
- 只对外暴露 `fun NavGraphBuilder.xxxScreen(...)` 与路由常量；导航图在 `:app`。
- 状态用 `sealed interface`，不用一堆 `Boolean` 拼状态。
- 字符串走 `strings.xml`，不写死在 `Text(...)` 里（将来要做多语言）。

## 交互上容易被忽略的

- 生成中必须可**取消**（协程取消 + UI 回到可输入态）。
- 流式渲染不能每 token 触发一次重组：攒帧 / `snapshotFlow` 节流。
- 长聊天列表用 `LazyColumn` + 稳定 key（消息 id），不要 `key = index`。
- 出错要给可执行下一步（"重试"、"检查 Key"），不是一句 Toast「出错了」。
