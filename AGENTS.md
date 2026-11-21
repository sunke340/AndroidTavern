# AGENTS.md

原生安卓「酒馆」：Kotlin + Compose 重写 SillyTavern 1.19.1，兼容其**文件格式**与**提示词行为**。
单 APK、单进程、无服务端、无 WebView（[ADR 0002](docs/adr/0002-single-process.md)）。
**M0 收尾中**：`:core:{model,card-format,prompt,ai}` 有 20 个单测；`:app` 已能装到真机、真连 provider、流式显示。

## TL;DR — 违反即改动无效

1. **`core/**` 禁 `android.*` / `androidx.*`**。它必须能在纯 JVM 跑单测，用 `tools/check-core-purity.sh` 验。
2. **兼容行为改动必须带 fixture**（提示词组装、世界书激活、格式解析）。没有 fixture 的兼容改动不接受。
3. **`vendor/SillyTavern` 只读**，不入库，产品代码不得 import；重建用 `bash tools/fixture/bootstrap.sh`。
4. **不猜原版行为**。拿不准去 `vendor/SillyTavern` grep，或补 fixture。"应该差不多"= 不写。
5. **不做 P3**（浏览器扩展、主题 CSS、STscript 全量、多用户、Horde、图片生成）。想做得先改 `docs/plan.md`。
6. **不加服务端 / 本地 HTTP / WebView**。要加先写 ADR。
7. **本机不构建**。`assembleDebug` 会 OOM；出包走 CI。
8. **密钥走 Keystore**，不落明文、不进日志、不进 fixture。
9. **ADR 只增不改**。推翻旧决策就写新的并标注 supersedes。

## 仓库布局与依赖方向

```
app/        Compose 入口、NavHost、DI 装配（无业务逻辑）
feature/    界面：chat / character / lorebook / preset / settings（MVVM，禁直接摸 data）
data/       Room、DataStore、SAF、Keystore、仓库实现
core/       纯 JVM 内核：card-format / chat-format / prompt / lorebook / tokenizer / preset / ai
docs/       plan（方向）· compat-matrix（进度）· adr（决策）· dev（怎么搭）
tools/      开发脚本（检查、夹具、引导）——产品代码不得依赖
fixtures/   golden 夹具；命名 `P<档>-<号>-<case>.json`，与矩阵 ID 对齐
vendor/     原版 ST 只读副本（.gitignore）
```

依赖单向：`app → feature → data → core`，`feature`/`app` 可直达 `core`。反向禁止，`feature` 之间禁止互依。
模块内部结构与新增模块的判据见 [docs/dev/layout.md](docs/dev/layout.md)。

命名：Gradle 模块 `core/prompt` → `:core:prompt` → 包 `app.tavern.core.prompt` → 入口类 `PromptBuilder`。

## 命令

```bash
tools/check-core-purity.sh            # 铁律 #1（本机可跑，零依赖）
bash tools/fixture/bootstrap.sh       # 重建 vendor/SillyTavern@1.19.1
tools/test-core.sh                    # 本机：所有 :core:* 单测 + 静默跳过守卫
./gradlew test                        # CI 用（聚合 test 会配置 :app，需要 Android SDK，本机跑不了）
git push                              # CI 出 debug APK

# 录 golden fixture（需原版 ST 在 8000 跑着；细节见 docs/dev/fixtures.md）
node tools/fixture/capture.mjs --out fixtures/expected/<ID>.json
```

⛔ `./gradlew assembleDebug` 本机不要跑：2 核 / 1.7 GiB 可用不到 900 MiB，必挂 swap。
⚠️ 无 KVM → 模拟器不可用，UI 只能在真机看。环境细节见 [docs/environment.md](docs/environment.md)。

## 完成定义

- [ ] 代码在正确模块，依赖方向未破
- [ ] `tools/check-core-purity.sh` 通过
- [ ] 兼容行为改动 → fixture 已提交且单测断言它
- [ ] [docs/compat-matrix.md](docs/compat-matrix.md) 状态列已更新
- [ ] 新的架构决策 → 新 ADR

## 提交

```
<area>: <一句话做了什么>

<为什么 / 影响面 / 未解风险>
```

`area` ∈ `core-prompt` `core-lorebook` `feature-chat` `data` `docs` `ci` `tools`。正文中文，标识符英文。不写"改了什么"的流水账。

## 文档地图（设计细节都在这里，别往本文件堆）

| 想找 | 去哪 |
|---|---|
| 范围、里程碑、不做什么 | [docs/plan.md](docs/plan.md) |
| 每个特性的状态与验收方式（**进度唯一真相**） | [docs/compat-matrix.md](docs/compat-matrix.md) |
| 模块职责、包结构、依赖规则 | [docs/dev/layout.md](docs/dev/layout.md) |
| 夹具怎么录、长什么样 | [docs/dev/fixtures.md](docs/dev/fixtures.md) |
| 原版已实测的行为（别猜） | [docs/dev/original-behavior.md](docs/dev/original-behavior.md) |
| 决策与理由 | [docs/adr/](docs/adr/) |
| 本机能力边界（实测） | [docs/environment.md](docs/environment.md) |

模块级规则在各自目录的 `AGENTS.md`（`core/` `data/` `feature/` `app/`），**进入该目录工作时自动加载**，就近优先。
本文件与嵌套文件合计只有 64 KB 预算（DSH 的指令链上限），所以这里只放"每次都要遵守的"，其余链接出去。
