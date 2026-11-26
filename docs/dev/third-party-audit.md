# 第三方依赖与许可审计（2026-11-26，只读）

> 一次性盘点，**不是规范**；判定以 `build.gradle.kts` 与源码为准，与本文冲突时以代码为准。
> 本文只做分析，**没有改任何代码/配置**。要落地时按第 8 节执行，落地后回来更新第 2～5 节。
>
> | | |
> |---|---|
> | 基线 | `87e5b56`（自 `fcee9e7` 起只有 `ChatScreen.kt` 与 docs 变化，**依赖与资产完全未动**） |
> | 方法 | 通读全部 `build.gradle.kts`；对 `app/ core/ data/` 的 `import` 做聚合；用 Python 拆 `jtokkit` / `okhttp` jar 的 `META-INF`；用 md5 比对内置卡与 `vendor/SillyTavern` |
> | 未做 | 未构建、未跑真机、未跑 `tools/`；未逐条下载 11 个库的 LICENSE 全文（只交叉核对了 shuding / emilkowalski / Cloudy / Reorderable 四家的公开许可信息） |
> | 关系 | 与 [architecture-audit.md](architecture-audit.md)（职责与正确性）、[structure-audit.md](structure-audit.md)（怎么摆）互补：这份只管**依赖面与许可义务** |

---

## 落地状态（2026-11-26 更新）

用户在 2026-11-26 拍板后已经落地（决策与理由见 [ADR 0009](../adr/0009-agpl-license.md)）：

| 项 | 状态 |
|---|---|
| 项目许可 | ✅ 根目录 `LICENSE`（AGPL-3.0 全文）+ README 声明：**接受 AGPL**，承认是 ST 的衍生作品 |
| 第三方声明 | ✅ `THIRD_PARTY_NOTICES.md` + `licenses/`（Apache-2.0、两份 MIT） |
| APK 内的许可文本 | ✅ 构建期 `syncNotices` 把上面几份同步进 `assets/notices/`（**唯一真相在仓库根**，不在 `assets/` 里手抄一份） |
| `packaging` 剔许可文件那行 | ✅ 已删（§6.3） |
| 内置 Seraphina 卡 | ✅ 已删除；改成自制演示卡 `assets/cards/qinghe.png`（`tools/cards/make-demo-card.py` 生成） |
| APK 里设「开源许可」页 | ⬜ 未做（下一批：设置 → 关于） |
| `core/` 注释措辞 | ⬜ 未做（下一批：改成"移植自 …（AGPL-3.0）+ 夹具编号"的署名式表述） |

下面第 0～10 节保留**决策前**的分析原貌（除本节与标注处外未改动），作为判断依据留档。

---

## 0. 结论摘要

1. **确实用了第三方库，但全是 Apache-2.0，没有 GPL/AGPL 运行时依赖**：核心只有 5 个非 Google 的
   （reorderable / jtokkit / okhttp+okhttp-sse / kotlinx-serialization / kotlinx-coroutines），
   其余是 AndroidX/Compose 全家桶。
2. **液态玻璃与动画曲线没有引库**：是"照数学/照表重写"。玻璃参考 shuding/liquid-glass（MIT）的
   位移场、参数命名照 skydoves/Cloudy（Apache-2.0）；动效表照 emilkowalski/skills（MIT），
   弹簧数值抄 androidx 的 M3 Expressive tokens。
3. ~~**仓库里没有任何 `LICENSE` / `NOTICE` / 第三方声明文件**~~（决策前的事实，2026-11-26 已补齐，
   见上面的"落地状态"；`packaging` 那行也已删，第 6.3 节）。
4. **内置了一张来自 SillyTavern 默认内容的角色卡**（`seraphina.png`，md5 与 ST 仓库里的一致），
   内容仓库的条款与代码许可不是一回事（第 5 节）。
5. **最大的许可风险不是库，是 `core/` 里自述"逐字照抄原版"的那几处 ST 逻辑**——
   ST 是 AGPL-3.0（第 7 节）。这一条决定的是"整个 APK 要不要开源"，比 Apache 归属重要一个量级。

---

## 1. 分发物里到底有什么

一个 APK = 三样东西：

| 组成 | 内容 | 许可义务来自 |
|---|---|---|
| 自研代码 | `app/ data/ core/` 全部 Kotlin（约 2.2 万行） | 由本项目自己的许可决定（目前**无许可声明**） |
| 第三方字节码 | 11 个库（第 2 节） | 各自许可证（全 Apache-2.0） |
| 资产 | `app/src/main/assets/cards/` **一张自制演示卡**（`.png`） | 见第 5 节 |

**不进包**的：`vendor/SillyTavern`（只读参考、`.gitignore` 里）、`tools/`（Node 脚本）、
构建期插件（AGP 8.7.3 / Kotlin 2.1.20 / Gradle 8.11.1）、测试依赖（JUnit 5 / kotlin-test /
mockwebserver，只在 `:core:*` 的 `test` classpath）。

---

## 2. 运行时依赖清单（有证据）

版本全部**内联写在各模块的 build 文件里**，没有 version catalog。

### 2.1 真正会进 APK 的

| 库 | 版本 | 位置 | 用途 | 许可 |
|---|---|---|---|---|
| `androidx.compose:compose-bom` → ui / material3 / foundation / material-icons-core / ui-tooling-preview | BOM `2024.12.01`（Compose 1.7.6） | [app/build.gradle.kts:81-88](../../app/build.gradle.kts#L81-L88) | 全部界面 | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.9.3 | [app/build.gradle.kts:90](../../app/build.gradle.kts#L90) | 入口 | Apache-2.0 |
| `androidx.lifecycle:{runtime,viewmodel}-compose` | 2.8.7 | [app/build.gradle.kts:89,91](../../app/build.gradle.kts#L89) | 状态/生命周期 | Apache-2.0 |
| `androidx.core:core-ktx` | 1.15.0 | [app/build.gradle.kts:92](../../app/build.gradle.kts#L92) | 平台扩展 | Apache-2.0 |
| **`sh.calvin.reorderable:reorderable`** | **3.1.0** | [app/build.gradle.kts:97](../../app/build.gradle.kts#L97) | 预设 `prompt_order` 拖动换位（[ADR 0008](../adr/0008-reorderable-library.md)） | Apache-2.0 |
| **`com.knuddels:jtokkit`** | **1.1.0** | [core/tokenizer/build.gradle.kts:12](../../core/tokenizer/build.gradle.kts#L12) | BPE 分词（cl100k/o200k） | Apache-2.0 |
| **`com.squareup.okhttp3:okhttp` + `okhttp-sse`** | **4.12.0** | [core/ai/build.gradle.kts:14-15](../../core/ai/build.gradle.kts#L14-L15) | SSE 流式请求 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.8.0 | card-format / chat-format / preset / regex 等 | 卡/会话/预设/正则 JSON | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines{-core,-android}` | 1.9.0 | app + core:ai | 并发 | Apache-2.0 |

`app` 里唯一的"非 Google"运行时依赖就是 **reorderable**；`:data` 如 [data/AGENTS.md](../../data/AGENTS.md)
所述**零第三方依赖**（没上 Room/DataStore，SQLite 走平台 API，见 [ADR 0003](../adr/0003-sqlite-over-room.md)）。

证据（import 聚合）：非平台 import 只出现在
`kotlinx.serialization.json`（102 处）、`kotlinx.coroutines`、`com.knuddels.jtokkit`（3 处）、
`okhttp3`（8 处）、`sh.calvin.reorderable`（2 处，都在 `PresetEditScreen`），没有别的。

### 2.2 只在测试里

`junit-jupiter` 5.11.4、`kotlin-test-junit5`、`okhttp-mockwebserver`、`kotlinx-coroutines-test`。
**不进 APK**；除非将来分发测试 jar 或源码包，否则不用出现在致谢里。

---

## 3. 只参考实现、没有引依赖（用户记得的那两处）

### 3.1 液态玻璃

- 数学（圆角矩形 SDF → 边缘法线 → 位移采样 → R/B 色散）照 **[shuding/liquid-glass](https://github.com/shuding/liquid-glass)**
  的位移场；参数命名照 **[skydoves/Cloudy](https://github.com/skydoves/Cloudy)**。
- 但 Cloudy 要 AGP 9.2 / Kotlin 2.4 / Compose 1.11，跟 Haze 2.0 同一个坑，**只抄命名不引依赖**
  （[ADR 0007](../adr/0007-liquid-glass-agsl.md)）。
- 着色器是自写的 AGSL（`LIQUID_AGSL`，[LiquidGlass.kt:135](../../app/src/main/kotlin/app/tavern/LiquidGlass.kt#L135) 起），
  叠加 Apple WWDC25 讲的"边缘折射 / 镜面高光 / 内阴影定厚度"。
- 出处写在 [LiquidGlass.kt:28](../../app/src/main/kotlin/app/tavern/LiquidGlass.kt#L28) 与 ADR 0007。
- 许可：shuding/liquid-glass = **MIT**（Copyright (c) 2025 Shu Ding）；Cloudy = **Apache-2.0**。

### 3.2 动画曲线

三处来源，全是"取值"不是"代码"：

| 来源 | 用在哪 | 许可 |
|---|---|---|
| [emilkowalski/skills](https://github.com/emilkowalski/skills) 的三张表（该不该动 / 曲线时长 / 手势物理） | [docs/design/motion.md](../design/motion.md) | MIT（Copyright (c) Emil Kowalski） |
| CSS 标准 `ease-out-quad` = `cubic-bezier(0.25, 0.46, 0.45, 0.94)` | [Motion.kt:77](../../app/src/main/kotlin/app/tavern/Motion.kt#L77) | 无（事实标准） |
| androidx Material3 1.4 的 `ExpressiveMotionTokens.kt`（0.6/800、0.8/380、1.0/380） | [Motion.kt:79-107](../../app/src/main/kotlin/app/tavern/Motion.kt#L79-L107) | Apache-2.0 |
| Apple 公开的橡皮筋公式 `o·D·K/(D+K·|o|)` | [RubberOverscroll.kt](../../app/src/main/kotlin/app/tavern/RubberOverscroll.kt) | 无（公开演讲里的公式） |

> 注：`Motion.Standard` 是 M3 emphasized 的近似；M3 那套 motion token 在本项目 BOM（1.3.1）里没有 API，
> 所以只抄数值没引代码。

---

## 4. 引过又撤掉的

**`dev.chrisbanes.haze:haze` 1.5.4**（Apache-2.0）：

- `6ef7989` 引入（[ADR 0004](../adr/0004-glass-visuals-haze.md)，底栏悬浮胶囊 + 背景模糊）；
- 真机（vivo / Android 17 / sdk 37）上只给半透明不给模糊，且内部无日志出口；
- `7873086` 撤掉，[ADR 0005](../adr/0005-glass-without-haze.md) supersedes 0004。

**当前依赖里没有 Haze**，`grep -rn haze app/` 只剩注释。所以"玻璃用了第三方库"这个印象是对的，
但属于历史，不是现状。

---

## 5. 资产：内置的默认角色卡

| 文件 | 来源 | 状态 |
|---|---|---|
| `assets/cards/qinghe.png` | **本项目自制**：卡面由 `tools/cards/make-demo-card.py` 生成，正文自己写，同时带 `chara`(V2) 与 `ccv3`(V3) | ✅ 无问题（`BundledCardTest` 守解析） |
| ~~`assets/cards/synthetic.png`~~ | 自制，但内容是夹具占位（`DESC-ALPHA`/`FIRST-OMEGA`） | 已从 assets 删除（`fixtures/cards/P0-5-synthetic.png` 仍留给测试） |
| ~~`assets/cards/seraphina.png`~~ | **SillyTavern 默认内容**（md5 `7a5ba85094b59d25be26d12146b7bdb5`，与 `vendor/SillyTavern/default/content/default_Seraphina.png` 完全一致） | ✅ 2026-11-26 已删除，不再随包分发 |

为什么不跟代码许可走：

- ST 主仓是 AGPL-3.0（`vendor/SillyTavern/package.json` 的 `"license": "AGPL-3.0"`）；
- 但 ST 官方内容仓库 [SillyTavern-Content](https://github.com/SillyTavern/SillyTavern-Content)
  写着「All rights to copyrighted works are reserved by their respective owners. You should gain
  their permission if you'd like to redistribute these assets outside of SillyTavern's ecosystem.」

**处置**：换成自制卡最省事；要保留就去拿作者许可，并在第三方声明里写清出处。

---

## 6. 许可义务逐条

### 6.1 Apache-2.0（11 个库里的绝大多数）

§4 要求三件事：

1. 给接收者一份许可证副本；
2. 保留版权、专利、归属声明（NOTICE 里的）；
3. 若修改过，说明修改（**本项目未修改任何库**，这条不适用）。

**已核实**：`jtokkit-1.1.0.jar` 与 `okhttp-4.12.0.jar` 的 `META-INF` 里**都只有 `MANIFEST.MF`，
没有 LICENSE/NOTICE**。所以"转达声明"这件事不会自动发生，必须自己写清单。

版权行别只抄 LICENSE 文件——OkHttp 的 `LICENSE.txt` 里并没有 `Copyright 2019 Square, Inc.`
这一行，版权行在 README 和源码头里。

### 6.2 MIT（shuding / emilkowalski）

要求：在所有副本或实质性部分中保留版权声明与许可文本。
本项目是"重写数学 + 抄表格"，属于"substantial portion"的边界地带。**写进去成本几乎为零**，
不写将来被问起就是一句话的事。建议列在"设计参考"里，附 MIT 全文。

### 6.3 现有配置里一个会吞声明的坑

[app/build.gradle.kts:62-64](../../app/build.gradle.kts#L62-L64)：

```kotlin
packaging {
    resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}
```

Android 模板遗留，作用是**把依赖自带的许可文件从 APK 里剔掉**。加上这些 jar 本来就没带
LICENSE，结果是**当前 APK 里一份许可文本都没有**。补齐只能靠自己放 `assets/`。

### 6.4 项目自身许可（目前是空的）

`git ls-files | grep -i licen` 无输出；README 里也没有许可声明。
法律上等于"保留所有权利"——但**如果第 7 节成立，这并不能免责**：分发衍生作品时缺 AGPL 授权
本身就是违约，不是"没写就没义务"。

---

## 7. AGPL-3.0 是什么，以及为什么它和本项目有关

### 7.1 一句话

**AGPLv3 = GPLv3 + 第 13 条**。第 13 条补的是 GPL 的漏洞：不能靠"只架服务、不发行二进制"逃离开源。
只要你让用户**通过网络和修改版交互**，就必须提供完整对应源码。

### 7.2 义务

| 场景 | GPLv3 | AGPLv3 |
|---|---|---|
| 自己用，不发布 | 无义务 | 无义务 |
| 把二进制给别人（U 盘 / Release / 发朋友） | 必须提供完整对应源码，同一许可，不许加额外限制 | 同左 |
| 架服务让用户网页访问修改版 | 无义务 | **必须提供源码** |
| 改了代码 | 改的部分照旧开源 | 同左 |

关键：copyleft **传染的是"衍生作品"**。把 ST 的 JS 逻辑翻译成 Kotlin = 改编 = 衍生；
只看文档/夹具重写行为 = 通常不算（clean-room）。

### 7.3 和本项目的关联点

`core/` 里这些注释自述"照抄/逐字照抄原版"：

- [ExampleDialogue.kt:6-7](../../core/prompt/src/main/kotlin/app/tavern/core/prompt/ExampleDialogue.kt#L6-L7)（"逐字照抄原版、不是我的设计"）
- [CardLorebook.kt:14](../../core/card-format/src/main/kotlin/app/tavern/core/card/CardLorebook.kt#L14)（"映射规则**逐字段照抄**"，还引了 `world-info.js:5617`）
- [WorldBookDecorators.kt:6](../../core/lorebook/src/main/kotlin/app/tavern/core/lorebook/WorldBookDecorators.kt#L6)（"语义**照抄**"，引了 `world-info.js:4652`）
- [ChatFile.kt:16](../../core/chat-format/src/main/kotlin/app/tavern/core/chat/ChatFile.kt#L16)、
  [PngCard.kt:12](../../core/card-format/src/main/kotlin/app/tavern/core/card/PngCard.kt#L12)、
  [WorldBookFile.kt:18](../../core/card-format/src/main/kotlin/app/tavern/core/card/WorldBookFile.kt#L18)、
  [LorebookEntry.kt:65](../../core/lorebook/src/main/kotlin/app/tavern/core/lorebook/LorebookEntry.kt#L65)

两种可能，结论完全不同：

| 如果是… | 结论 |
|---|---|
| **照着源码翻译**（保留了原版的怪处、引了行号，看起来很可能是） | APK 整体是 ST 的衍生作品 → 分发即触发 AGPL → 必须 AGPL 授权 + 提供完整源码 |
| **只看夹具/文档重写**（行为一样、代码不同） | 不构成衍生；只有被抄的片段受约束 |

⚠️ 注释里写"逐字照抄"本身是对己不利的证据——真被问时很难辩解成 clean-room。

### 7.4 三条路

| 选择 | 代价 |
|---|---|
| **接受 AGPL** | 仓库加 `LICENSE`（AGPL-3.0 全文）+ README 声明；Release 提供源码（源码本来就在 GitHub，天然满足）。代价：以后不能改 MIT/闭源；App Store 不兼容（本项目不上架，F-Droid 反而欢迎） |
| **改 clean-room** | 只读原版**行为**（文档、跑原版看输出），不读实现；用现有 fixture 卡住结果。工作量集中在 `ExampleDialogue` / `CardLorebook` 这类"照抄怪处"的地方，改完注释也得改 |
| **找维护者要例外** | 现实中基本不给，且不改变内容作者对角色卡的版权 |

> 以上不构成法律意见。要公开发布前，至少先决定走哪条路。

---

## 8. 行动清单（按优先级）

- [x] **P0 · 决定 AGPL 走向** → **接受 AGPL-3.0**（2026-11-26，[ADR 0009](../adr/0009-agpl-license.md)）。
- [x] **P0 · 处理内置 Seraphina 卡** → 删除，改自制演示卡（`qinghe.png`）。
- [x] **P1 · 建 `THIRD_PARTY_NOTICES.md`** + `licenses/`（Apache-2.0、两份 MIT 全文，均取自上游）。
- [x] **P1 · 项目根加 `LICENSE`**（AGPL-3.0 全文）+ README 许可说明。
- [ ] **P2 · APK 内"开源许可"页**：设置 → 关于，读 `assets/notices/` 文本；或退一步把 notices 挂 Release 附件。
- [ ] **P2 · 清掉 `packaging` 里剔 `META-INF` 许可文件的那行**（或明确保留，别再让它静默吞声明）。
- [x] **P3 · `core/` 注释改成**署名式**表述**（"移植自 SillyTavern 1.19.1（AGPL-3.0）的 X，
      行为由 fixture P?-? 锁定"）。⚠️ 走 AGPL 之后**不是删掉出处，而是写清出处**（ADR 0009 第 2 条）。
      → 2026-11-27 已完成：`core/` 里 9 处"照抄 / 逐字照抄"改成"移植自 / 与原版一致 + 上游项目、版本、
      许可、源文件路径"，一个 `照抄` 都没留。
- [ ] **P3 · `docs/design/motion.md` 与 `Motion.kt` 的漂移**：文档写橡皮筋 `spring(1.0, 700)`，
      代码是 `380`（[Motion.kt:107](../../app/src/main/kotlin/app/tavern/Motion.kt#L107)）。顺手对齐。

---

## 9. 待确认 / 待补证据

1. ~~`core/` 那几处到底是"翻译"还是"行为复刻"~~ → 已按"翻译"处理（接受 AGPL，见 ADR 0009）。
2. `jtokkit` 仓库 LICENSE 的版权行（GitHub raw 取不到，可能要读源码头或 README）。
3. 内置 Seraphina 卡的上游许可：ST 主仓 AGPL 是否覆盖 `default/content/`，还是内容仓库的保留条款优先。
4. ~~`assets/cards/synthetic.png` 是否真为自制~~ → 已随包删除；现在的演示卡由脚本生成，可复现。
5. 若将来加 Room / Coil / WorkManager（[docs/plan.md](../plan.md) 技术栈里列了），本文件的清单要同步增补。

---

## 10. 怎么更新本文与那份声明

`THIRD_PARTY_NOTICES.md` 是**对外**文件：只描述当前组成，不记变更史（"某组件已移除"这类话一律
不写 —— 没列出来的就是不随包分发），也不写维护推理。维护规则：

- 加/删依赖后：改本文件第 2 节表格 + `THIRD_PARTY_NOTICES.md` 的组件表，把新库的许可全文放进
  `licenses/`；顺手跑一遍 `grep -rh "^import " --include=*.kt app core data` 看有没有漏掉的包。
- **不能靠构建产物抄许可**：部分 jar 的 `META-INF` 里不带许可文件，所以那张表必须自己维护。
  测试依赖不进 APK，不列。
- 只参考了数学/参数、没引入依赖的，放"实现参考"，别混进"随应用分发"。
- 决定 AGPL 走向后：重写第 7.3～7.4 与第 8 节的 P0 项，把"待确认"里的对应条目划掉。
- 本文是一次性快照，**不要**把它当规范引用；`build.gradle.kts` 才是真相。
