# 第三方声明

本项目以 **AGPL-3.0** 发布（[LICENSE](LICENSE)）。它同时是 **SillyTavern 1.19.1 的衍生作品**：
界面与运行时不共享代码（Kotlin + Compose 重写），但 `core/` 里有多处兼容逻辑**移植自**原版
（见各文件注释与 `fixtures/` 的 golden 夹具）。SillyTavern 的许可是 AGPL-3.0
（<https://github.com/SillyTavern/SillyTavern>），其版权归 SillyTavern 贡献者所有。

本文件列出随 APK 分发或作为设计参考的第三方组件。完整许可文本在 [licenses/](licenses)。

## 随 APK 分发

全部为 **Apache-2.0**。本项目**没有修改**任何一个库，因此 Apache-2.0 §4(b)（标注修改）不适用。

| 组件 | 版本 | 许可 | 版权 | 来源 |
|---|---|---|---|---|
| AndroidX Compose（BOM：ui / foundation / material3 / material-icons-core / tooling-preview） | 2024.12.01 | Apache-2.0 | The Android Open Source Project | <https://developer.android.com/jetpack/androidx> |
| AndroidX Activity / Lifecycle / Core | 1.9.3 / 2.8.7 / 1.15.0 | Apache-2.0 | The Android Open Source Project | 同上 |
| OkHttp / okhttp-sse | 4.12.0 | Apache-2.0 | Square, Inc. | <https://github.com/square/okhttp> |
| jtokkit | 1.1.0 | Apache-2.0 | Knuddels GmbH & Co. KG | <https://github.com/knuddels/jtokkit> |
| kotlinx-serialization-json | 1.8.0 | Apache-2.0 | JetBrains s.r.o. | <https://github.com/Kotlin/kotlinx.serialization> |
| kotlinx-coroutines（core / android） | 1.9.0 | Apache-2.0 | JetBrains s.r.o. | <https://github.com/Kotlin/kotlinx.coroutines> |
| sh.calvin.reorderable | 3.1.0 | Apache-2.0 | Calvin-LL | <https://github.com/Calvin-LL/Reorderable> |

> 版权行以上游仓库的 `LICENSE` / `README` / 源码头为准（部分 jar 的 `META-INF` 里不带许可文件，
> 所以这里必须自己列，不能依赖构建产物）。
> 测试依赖（JUnit 5、kotlin-test、mockwebserver、kotlinx-coroutines-test）**不进 APK**，不在此列。

## 设计参考（抄了数学/数值，没有引依赖）

| 项目 | 用在哪 | 许可 | 版权 |
|---|---|---|---|
| [shuding/liquid-glass](https://github.com/shuding/liquid-glass) | 液态玻璃的圆角矩形 SDF → 边缘法线 → 位移采样 → 色散 | MIT | Copyright (c) 2025 Shu Ding |
| [emilkowalski/skills](https://github.com/emilkowalski/skills) | 动效"该不该动 / 曲线时长 / 手势物理"三张表 | MIT | Copyright (c) 2026 Emil Kowalski |
| [skydoves/Cloudy](https://github.com/skydoves/Cloudy) | 只抄参数命名（未引依赖：它要 AGP 9.2 / Compose 1.11） | Apache-2.0 | Skydoves |
| Material 3 Expressive motion tokens | 弹簧参数（`Motion.kt`） | Apache-2.0 | The Android Open Source Project |
| CSS `ease-out-quad` / Apple 公开的橡皮筋公式 | 曲线与回弹（事实标准 / 公开演讲公式） | 无许可约束 | — |

两份 MIT 全文见 [licenses/MIT-shuding-liquid-glass.txt](licenses/MIT-shuding-liquid-glass.txt)
与 [licenses/MIT-emilkowalski-skills.txt](licenses/MIT-emilkowalski-skills.txt)。

**Note**：`dev.chrisbanes.haze:haze` 在 2026-11-22 引入、当天撤掉（真机只给半透明不给模糊，
[ADR 0005](docs/adr/0005-glass-without-haze.md) supersedes 0004）。它**不在**当前依赖里。

## 内置资产

| 文件 | 来源 | 说明 |
|---|---|---|
| `app/src/main/assets/cards/qinghe.png` | **本项目自制** | 卡面由 `tools/cards/make-demo-card.py` 现画（纯标准库、可重跑），卡正文自己写；同时带 `chara`(V2) 与 `ccv3`(V3) 两个 `tEXt` 块。`core/card-format` 的 `BundledCardTest` 守着它能被解析。 |

本仓库**不包含**任何来自 SillyTavern 默认内容的图片或角色数据 ——
原先随包的 `seraphina.png` 已于 2026-11-26 删除（[ADR 0009](docs/adr/0009-agpl-license.md)）。
唯一还带着原版内容的是测试夹具 `fixtures/worldinfo/P1-1-seraphina-book.json`，它不随 APK 分发。

## 许可全文

| 文件 | 许可 |
|---|---|
| [LICENSE](LICENSE) | GNU AGPL-3.0（本项目自身） |
| [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) | Apache License 2.0 |
| [licenses/MIT-shuding-liquid-glass.txt](licenses/MIT-shuding-liquid-glass.txt) | MIT（Shu Ding） |
| [licenses/MIT-emilkowalski-skills.txt](licenses/MIT-emilkowalski-skills.txt) | MIT（Emil Kowalski） |

## 维护

加/删一个依赖时：改本文件的两张表 + 附录式清单，并把新库的许可全文放进 `licenses/`；
然后回来更新 [第三方依赖与许可审计](docs/dev/third-party-audit.md) 第 2 节与第 8 节的勾选状态。
本文件是**对外**文档，措辞要能直接给下游看；审计文档是**对内**的分析记录，两者不要混。
