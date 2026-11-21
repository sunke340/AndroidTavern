# feature/ —— Compose 界面

规矩：
- 一律 MVVM + 单向数据流：`Screen`（无状态 Composable）→ `ViewModel`（StateFlow）→ `UseCase` → repository 接口。
- **Composable 里不许直接访问 Room、文件系统、网络。** 一律经 usecase。
- feature 之间不许互相依赖；共用 UI 组件抽 `:feature:common`（需 ADR）。
- 每个 feature 只对外暴露 `fun NavGraphBuilder.xxxScreen(...)` 与自己的路由常量。

计划中的 feature：`chat`、`character`、`lorebook`、`preset`、`settings`。
