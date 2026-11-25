# AGENTS.md

原生安卓「酒馆」：Kotlin + Compose 重写 SillyTavern 1.19.1，兼容其**文件格式**与**提示词行为**。
单 APK、单进程、无服务端、无 WebView（[ADR 0002](docs/adr/0002-single-process.md)）。
**现状（2026-11-24）**：M1 已可用（导卡/聊天/流式/开场白/落库/Key 加密/主题/日志导出），
P1-5 预设与 prompt_order 已被用户验收，当天还修完启动、世界书、详情页导航与聊天串流一批细节。
接手先看 [docs/dev/handoff.md](docs/dev/handoff.md)，当前进度见 [docs/dev/status.md](docs/dev/status.md)。

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
10. **能复用就先复用**：先看仓库里有没有现成的（组件 / 工具 / 数据 / 文档），
    没有再查现成的库或上游实现，**都没有才自研**，并在 ADR 里写清「为什么不能复用」。
    例：拖动排序用了 `sh.calvin.reorderable`（[ADR 0008](docs/adr/0008-reorderable-library.md)）；
    玻璃材质两次真机失败后必须自研（[ADR 0005](docs/adr/0005-glass-without-haze.md)）——
    这两个相反结论都出自这一条规则，不是谁的偏好。

## 仓库布局与依赖方向

```
app/        Compose 入口 + 全部界面 + DI 装配（**目前界面就在这里**，见下）
feature/    ⚠️ **空目录**（只有 AGENTS.md，0 个 kt 文件）；界面没有拆进来，别当成已存在
data/       Room、DataStore、SAF、Keystore、仓库实现
core/       纯 JVM 内核：card-format / chat-format / prompt / lorebook / tokenizer / preset / ai
docs/       plan（方向）· compat-matrix（进度）· adr（决策）· dev（怎么搭）
tools/      开发脚本（检查、夹具、引导）——产品代码不得依赖
fixtures/   golden 夹具；命名 `P<档>-<号>-<case>.json`，与矩阵 ID 对齐
vendor/     原版 ST 只读副本（.gitignore）
```

依赖单向：**现在实际是 `app → data → core`**（界面全在 `:app`）；`app`/`feature` 可直达 `core`，反向禁止。
什么时候把 `feature/*` 真正拆出来、以及为什么现在不拆，见
[docs/dev/structure-audit.md](docs/dev/structure-audit.md) 第七、九节。
模块内部结构与新增模块的判据见 [docs/dev/layout.md](docs/dev/layout.md)。

命名：Gradle 模块 `core/prompt` → `:core:prompt` → 包 `app.tavern.core.prompt` → 入口类 `PromptBuilder`。

## 命令

```bash
tools/check-core-purity.sh            # 铁律 #1（本机可跑，零依赖）
tools/check-kotlin-comments.sh        # 块注释不许嵌套（会吞掉整段代码）
tools/check-kotlin-imports.sh         # 不许重复 import（编译期才报错）
tools/verify-apk.sh <apk> <指纹>       # 无 SDK 也能验签名
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
- [ ] `tools/check-core-purity.sh` / `check-kotlin-comments.sh` / `check-kotlin-imports.sh` 通过
- [ ] 兼容行为改动 → fixture 已提交且单测断言它
- [ ] [docs/compat-matrix.md](docs/compat-matrix.md) 状态列已更新
- [ ] 新的架构决策 → 新 ADR
- [ ] 没有重复造轮子：先查过仓库内已有的，再查过现成的库（铁律 #10）

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
| 具体怎么跑 / 怎么发 / 怎么救 | [docs/dev/runbook.md](docs/dev/runbook.md) |
| **接手卡（压缩上下文 / 换人时先读这个）** | [docs/dev/handoff.md](docs/dev/handoff.md)（正在悬着的事、待验清单、出包循环） |
| **当前状态与下一步** | [docs/dev/status.md](docs/dev/status.md)（**只写现状，每次整篇重写**；不是规则，别当硬性要求引用） |
| 可复用资源盘点、目录分层、分轮整理计划 | [docs/dev/structure-audit.md](docs/dev/structure-audit.md) |
| **架构审计（2026-11-26，只读）**：实际架构模式、职责边界、God Class/并发/安全/技术债清单 | [docs/dev/architecture-audit.md](docs/dev/architecture-audit.md) |
| **UI 地图与词表**（用户怎么叫、代码叫什么；对不上就问，不猜） | [docs/ui-map.md](docs/ui-map.md) |
| **UI 不变量规格**（必须成立什么 + 三批防回归计划；脚本管结构、清单管观感） | [docs/ui-invariants.md](docs/ui-invariants.md) |
| **历史踩坑与细节**（为什么是这个数字、试过哪几条路） | [docs/dev/pitfalls.md](docs/dev/pitfalls.md) |
| 夜间那一轮的计划与结果（历史） | [docs/plan-overnight.md](docs/plan-overnight.md) |
| 决策与理由 | [docs/adr/](docs/adr/) |
| 本机能力边界（实测） | [docs/environment.md](docs/environment.md) |

模块级规则在各自目录的 `AGENTS.md`（`core/` `data/` `feature/` `app/`），**进入该目录工作时自动加载**，就近优先。
本文件与嵌套文件合计只有 64 KB 预算（DSH 的指令链上限），所以这里只放"每次都要遵守的"，其余链接出去。
当前用了约 20 KB（`app/AGENTS.md` 在 2026-11-24 从 42 KB 瘦到 9 KB，历史细节搬去了 [docs/dev/pitfalls.md](docs/dev/pitfalls.md)）—— 再加节之前先看还剩多少。
