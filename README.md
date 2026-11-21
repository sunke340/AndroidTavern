# AndroidTavern

原生安卓版「酒馆」。目标：兼容 SillyTavern 1.19.1 的**文件格式与提示词行为**，用 Kotlin + Compose 重写 UI 与运行时。

- 规划、兼容矩阵、里程碑 → [docs/plan.md](docs/plan.md)
- 本机能不能开发（实测）→ [docs/environment.md](docs/environment.md)

当前状态：**只有规划，没有代码**。`vendor/SillyTavern` 是原版源码（`--depth 1`），只用于取格式定义和做 golden fixture 基准，不参与构建。

核心验收标准（两条）：
1. PC 版 ST 的卡 / 世界书 / 聊天能导入本 App 且行为一致；本 App 导出的东西 PC 版 ST 能接着聊。
2. 同一套输入，本 App 发给 API 的 `messages[]` 与 ST 1.19.1 逐条一致（自动比对，不靠肉眼）。
