# AGENTS.md

本仓库是**原生安卓酒馆**（Kotlin + Compose 重写 SillyTavern）。本文件是给所有 AI agent 与人类贡献者的**唯一规则入口**。改动前先读完。

## 0. 三句话

1. 产品形态：**单 APK、单进程、无服务端、无 WebView**（[adr/0002](docs/adr/0002-single-process.md)）。
2. 兼容性定义：**文件格式双向可迁移 + 提示词逐条等价**（[adr/0001](docs/adr/0001-compat-strategy.md)）。
3. 当前阶段：只有规划，**没有代码**。第一个里程碑是 M0。

## 1. 开工前按顺序读

| 顺序 | 文件 | 读它是为了 |
|---|---|---|
| 1 | [docs/plan.md](docs/plan.md) | 范围、里程碑、明确不做什么 |
| 2 | [docs/compat-matrix.md](docs/compat-matrix.md) | 每个特性的状态与验收方式（**唯一的进度真相**） |
| 3 | [docs/dev/layout.md](docs/dev/layout.md) | 代码放哪、谁可以依赖谁 |
| 4 | [docs/environment.md](docs/environment.md) | 本机限制（不能构建、无模拟器） |
| 5 | [docs/adr/](docs/adr/) | 已定的决策。**不要推翻 ADR，写一个新的** |

## 2. 铁律

违反任何一条，改动视为无效，无需讨论：

1. **`core/**` 里禁止出现 `android.*` 或 `androidx.*`**。这些模块必须能在纯 JVM 上跑单测。用 `tools/check-core-purity.sh` 验证，CI 会跑。
2. **兼容行为的改动必须配 fixture**。新增/修改提示词组装、世界书激活、格式解析，同时提交 `fixtures/` 下的输入与期望输出。没有 fixture 的行为改动不接受。
3. **`vendor/SillyTavern` 只读**。它是原版源码，用来取格式定义和录基准，**不在 git 里**（`.gitignore`），任何产品代码不得 import 它。
4. **不做 P3**。浏览器扩展体系、主题 CSS、STscript 全量、多用户、Horde、Data Bank RAG 之外的向量功能——想做先改 [plan.md](docs/plan.md)，不要顺手做。
5. **不为一个功能引入服务端/本地 HTTP 服务/WebView**。见铁律对应的 ADR。
6. **本机不做构建**。见 §4，不要尝试 `./gradlew assembleDebug` 然后卡住。
7. **不猜原版行为**。拿不准就 `vendor/SillyTavern` 里 grep 源码，或补 fixture；禁止"应该差不多"。
8. **不写死 API Key、不把密钥落明文盘**。走 Keystore。

## 3. 目录地图

| 路径 | 放什么 | 禁止 |
|---|---|---|
| `app/` | Compose 入口、导航、DI 装配 | 业务逻辑 |
| `core/` | **纯 JVM**：卡格式、聊天格式、提示词组装、世界书、token、AI 客户端 | `android.*`、UI、Context |
| `feature/` | Compose 界面（chat / character / lorebook / preset / settings） | 直接访问 Room/文件/网络，一律经 usecase/repository |
| `data/` | Room、DataStore、SAF、仓库实现、Keystore | 提示词逻辑（放 `core/prompt`） |
| `docs/` | 规划、决策、矩阵、开发说明 | 代码 |
| `tools/` | 开发期脚本：fixture 录制、JDK 引导、检查脚本 | 被产品代码依赖 |
| `fixtures/` | golden 输入与期望输出（进 git，保持小） | 大体积媒体、真实 API Key |
| `.github/workflows/` | CI 出包与单测 | — |

依赖方向只许单向：`app → feature → data → core`，`feature → core` 也可以，反向一律不行。细节见 [docs/dev/layout.md](docs/dev/layout.md)。

## 4. 命令（真实情况）

```bash
# ✅ 本机可跑（需先装 JDK，见 docs/environment.md）
tools/check-core-purity.sh          # 检查 core 是否被 android.* 污染
./gradlew :core:prompt:test         # 纯 JVM 单测（M0 之后）

# ⛔ 本机不要跑（2 核 1.7G，编译 Compose 会 OOM 或卡 40 分钟）
./gradlew assembleDebug

# ✅ 出包走 CI
git push  # GitHub Actions 出 debug APK artifact

# ✅ 开发期基准（可选，本机有 Node 24）
cd vendor/SillyTavern && node server.js   # 原版，用于录 fixture
```

## 5. 完成的定义（DoD）

一个任务算完成，必须同时满足：

- [ ] 代码在正确模块里，依赖方向没被破坏
- [ ] `tools/check-core-purity.sh` 通过
- [ ] 涉及兼容行为 → fixture 已提交，且单测断言它
- [ ] [docs/compat-matrix.md](docs/compat-matrix.md) 的状态列已更新
- [ ] 产生了新的架构级决策 → 新的 ADR
- [ ] 提交信息说清"为什么"，不只说"改了什么"

## 6. 提交规范

```
<area>: <一句话做了什么>

<为什么这么做 / 影响面 / 未解决的风险>
```

`area` 取 `core-prompt`、`core-lorebook`、`feature-chat`、`data`、`docs`、`ci`、`tools` 之一。正文中文，标识符与文件名英文。

## 7. 风格

- Kotlin official style，4 空格，无通配 import，`explicitApi()` 在 `core/**` 开启。
- 标识符英文；**注释与文档中文**。
- 公开 API 不写"能干什么"，写"为什么是这个形状"。
- 不引入新的第三方依赖，除非在 PR 里写清"为什么标准库/已有依赖做不到"。
- 包名 `app.tavern`（发布前可改，`applicationId` 一旦上架不可改，改之前先提 ADR）。

## 8. 已知坑（踩过的）

- 本机可用内存 ~876 MiB，dsh 本体占 ~500 MB；构建必挂 swap。
- 无 `/dev/kvm`、CPU 无 vmx/svm → 模拟器不可用；UI 只能在真机看。
- `no new privileges` → 无 `sudo`，只能用户态安装。
- `vendor/SillyTavern` 只读且不入库；换机器要重跑 `tools/fixture/bootstrap.sh`。
- 原版 ST 1.19.1 的提示词组装分散在 `public/script.js`、`PromptManager.js`、`openai.js` 三处，**只看一处会得出错误结论**。
