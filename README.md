# AndroidTavern

原生安卓版「酒馆」。目标：兼容 SillyTavern 1.19.1 的**文件格式与提示词行为**，用 Kotlin + Compose 重写 UI 与运行时。单 APK、单进程、无服务端。

## 从哪读起

| 你是谁 | 先读 |
|---|---|
| 贡献者 / AI agent | [AGENTS.md](AGENTS.md)（唯一规则入口） |
| 想知道做什么、不做什么 | [docs/plan.md](docs/plan.md) |
| 想知道做到哪了 | [docs/compat-matrix.md](docs/compat-matrix.md) |
| 想知道代码放哪 | [docs/dev/layout.md](docs/dev/layout.md) |
| 想知道这台机器能不能干活 | [docs/environment.md](docs/environment.md) |
| 想知道某个决定为什么这么定 | [docs/adr/](docs/adr/) |

## 仓库地图

```
AGENTS.md      规则（agent 与人的共同入口）
app/           Compose 入口、导航、DI
core/          纯 JVM 兼容内核（禁 android.*）
feature/       Compose 界面
data/          Room / SAF / Keystore / 仓库实现
docs/          规划、决策、矩阵、开发说明
tools/         开发期脚本（夹具、检查、引导）
fixtures/      golden 夹具（兼容性的物证）
vendor/        原版 SillyTavern，只读参考，不入库
```

## 状态

**只有规划，没有代码。** 下一步是 M0：工程骨架 + CI 出包 + fixture 流水线。

两条验收标准（全文见 [ADR 0001](docs/adr/0001-compat-strategy.md)）：

1. PC 版 ST 的卡 / 世界书 / 聊天能导入本 App 且行为一致；本 App 导出的东西 PC 版 ST 能接着聊。
2. 同一套输入，本 App 发给 API 的 `messages[]` 与 ST 1.19.1 逐条一致（自动比对，不靠肉眼）。
