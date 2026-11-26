# AndroidTavern

原生安卓版「酒馆」。目标：兼容 SillyTavern 1.19.1 的**文件格式与提示词行为**，用 Kotlin + Compose 重写 UI 与运行时。单 APK、单进程、无服务端、无 WebView。

**许可：GNU AGPL-3.0**（[LICENSE](LICENSE)）。本项目是 SillyTavern 1.19.1 的**衍生作品**：
界面与运行时不共享代码，但 `core/` 里有多处兼容逻辑移植自原版（AGPL-3.0）。
第三方组件与内置资产见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，决策见 [ADR 0009](docs/adr/0009-agpl-license.md)。

## 从哪读起

| 你是谁 | 先读 |
|---|---|
| 贡献者 / AI agent | [AGENTS.md](AGENTS.md)（唯一规则入口） |
| 想知道做什么、不做什么 | [docs/plan.md](docs/plan.md) |
| 想知道做到哪了 | [docs/compat-matrix.md](docs/compat-matrix.md) |
| 想知道现在是什么状态 | [docs/dev/status.md](docs/dev/status.md) |
| 想知道代码放哪 | [docs/dev/layout.md](docs/dev/layout.md) |
| 想知道这台机器能不能干活 | [docs/environment.md](docs/environment.md) |
| 想知道某个决定为什么这么定 | [docs/adr/](docs/adr/) |

## 仓库地图

```
AGENTS.md      规则入口（agent 与人的共同入口）
app/           Compose 入口、导航、DI，以及**目前全部界面**
core/          纯 JVM 兼容内核（禁 android.*，能在本机跑单测）
feature/       空目录（界面还没拆出来，别当成已存在，见 docs/dev/structure-audit.md）
data/          SQLite（平台 API，不是 Room）、SAF、Keystore、仓库实现
docs/          规划、决策、矩阵、开发说明
tools/         开发期脚本（夹具、检查、出包、内置卡生成）
fixtures/      golden 夹具（兼容性的物证）
vendor/        原版 SillyTavern，只读参考，不入库（bash tools/fixture/bootstrap.sh 重建）
```

每个代码目录里有一份自己的 `AGENTS.md`，写该目录独有的规则（`core/` 禁 Android 依赖、
`data/` 的存储约定、`app/` 只管装配）。依赖方向是单向的 **app → data → core**。

## 状态

已经能日常使用：导卡 / 聊天 / 流式 / 开场白 / 落库 / Key 加密 / 主题 / 世界书 / 预设与
`prompt_order` / 三级配置 / 消息显示格式。进度以 [docs/compat-matrix.md](docs/compat-matrix.md) 为准，
当前批次与下一批看 [docs/dev/status.md](docs/dev/status.md)。

两条验收标准（全文见 [ADR 0001](docs/adr/0001-compat-strategy.md)）：

1. PC 版 ST 的卡 / 世界书 / 聊天能导入本 App 且行为一致；本 App 导出的东西 PC 版 ST 能接着聊。
2. 同一套输入，本 App 发给 API 的 `messages[]` 与 ST 1.19.1 逐条一致（自动比对，不靠肉眼）。
