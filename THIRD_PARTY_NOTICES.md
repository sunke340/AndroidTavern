# 第三方声明

本文件描述本项目当前的组成：**列出的内容随应用分发，或构成其实现依据；未列出的内容即不存在**。
各组件的完整许可文本见 [licenses/](licenses)。

## 本项目

本项目以 **GNU AGPL-3.0** 发布（全文见 [LICENSE](LICENSE)），并且是 **SillyTavern 1.19.1 的衍生作品**。
界面与运行时不共享代码（以 Kotlin + Compose 重写），但 `core/` 中多处兼容逻辑移植自原版，
其行为由 `fixtures/` 中的 golden 夹具锁定。SillyTavern 以 AGPL-3.0 发布
（<https://github.com/SillyTavern/SillyTavern>），版权归其贡献者所有。

## 随应用分发的组件

| 组件 | 版本 | 许可 | 版权 | 来源 |
|---|---|---|---|---|
| AndroidX Compose（BOM：ui / foundation / material3 / material-icons-core / tooling-preview） | 2024.12.01 | Apache-2.0 | The Android Open Source Project | <https://developer.android.com/jetpack/androidx> |
| AndroidX Activity / Lifecycle / Core | 1.9.3 / 2.8.7 / 1.15.0 | Apache-2.0 | The Android Open Source Project | 同上 |
| OkHttp / okhttp-sse | 4.12.0 | Apache-2.0 | Square, Inc. | <https://github.com/square/okhttp> |
| jtokkit | 1.1.0 | Apache-2.0 | Knuddels GmbH & Co. KG | <https://github.com/knuddels/jtokkit> |
| kotlinx-serialization-json | 1.8.0 | Apache-2.0 | JetBrains s.r.o. | <https://github.com/Kotlin/kotlinx.serialization> |
| kotlinx-coroutines（core / android） | 1.9.0 | Apache-2.0 | JetBrains s.r.o. | <https://github.com/Kotlin/kotlinx.coroutines> |
| sh.calvin.reorderable | 3.1.0 | Apache-2.0 | Calvin-LL | <https://github.com/Calvin-LL/Reorderable> |

以上组件均为 Apache-2.0，且均未修改。测试依赖（JUnit 5、kotlin-test、mockwebserver、
kotlinx-coroutines-test）不随应用分发。

## 实现参考

下表列出未随项目分发、但构成本项目实现依据的公开资料。

| 项目 | 用途 | 许可 | 版权 |
|---|---|---|---|
| [shuding/liquid-glass](https://github.com/shuding/liquid-glass) | 液态玻璃的圆角矩形距离场、边缘法线、位移采样与色散 | MIT | Copyright (c) 2025 Shu Ding |
| [emilkowalski/skills](https://github.com/emilkowalski/skills) | 动效的判断准则、曲线与时长、手势物理取值 | MIT | Copyright (c) 2026 Emil Kowalski |
| Material 3 Expressive 动效规范 | 弹簧参数（`Motion.kt`） | Apache-2.0 | The Android Open Source Project |

前两项的 MIT 全文见 [licenses/MIT-shuding-liquid-glass.txt](licenses/MIT-shuding-liquid-glass.txt)
与 [licenses/MIT-emilkowalski-skills.txt](licenses/MIT-emilkowalski-skills.txt)。

## 随应用分发的内置资产

| 文件 | 来源 | 说明 |
|---|---|---|
| `app/src/main/assets/cards/qinghe.png` | 本项目自制 | 卡面由 `tools/cards/make-demo-card.py` 生成（仅用标准库，可重跑），卡正文为本项目撰写；文件同时携带 `chara`(V2) 与 `ccv3`(V3) 两个 `tEXt` 块，`core/card-format` 的 `BundledCardTest` 断言其可被解析。 |

本项目不包含 SillyTavern 的默认角色卡、头像或图片。测试夹具
`fixtures/worldinfo/P1-1-seraphina-book.json` 由原版默认卡转换而来，随源码提供以锁定世界书
激活行为，不随应用分发。

## 许可全文

| 文件 | 许可 |
|---|---|
| [LICENSE](LICENSE) | GNU AGPL-3.0（本项目自身） |
| [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) | Apache License 2.0 |
| [licenses/MIT-shuding-liquid-glass.txt](licenses/MIT-shuding-liquid-glass.txt) | MIT（Shu Ding） |
| [licenses/MIT-emilkowalski-skills.txt](licenses/MIT-emilkowalski-skills.txt) | MIT（Emil Kowalski） |
