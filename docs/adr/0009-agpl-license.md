# ADR 0009 — 以 AGPL-3.0 发布，承认本项是 SillyTavern 的衍生作品

- **状态**：接受（2026-11-26）
- **依据**：[第三方依赖与许可审计](../dev/third-party-audit.md)（2026-11-26）第 7 节的三选一
- **影响面**：仓库根 `LICENSE`、`THIRD_PARTY_NOTICES.md`、`licenses/`、README、随包资产
  （`app/src/main/assets/cards/`）、以及 `core/` 里那 8 处"移植自 / 照抄"的注释措辞

## 背景

本项目是 **Kotlin + Compose 重写 SillyTavern 1.19.1**，兼容它的文件格式与提示词行为。
审计查出两件事：

1. `core/` 里 8 处注释自述"移植自 `public/scripts/openai.js` 的 …""逐字段照抄原版
   `convertCharacterBook()`（`world-info.js:5617`）"这类措辞，并引原版行号；
2. 仓库与 Release 都**已经公开**（`github.com/sunke340/AndroidTavern`，APK 匿名可下），
   所以"公开发布"不是将来时。

SillyTavern 主仓是 **AGPL-3.0**。把它的 JS 逻辑翻译成 Kotlin 属于改编，改编产生衍生作品；
分发衍生作品就要按 AGPL 提供完整对应源码并以同一许可授权。

## 决策

1. **整个项目（源码 + APK）以 AGPL-3.0 发布**：根目录 `LICENSE` 放 AGPL-3.0 全文，
   README 与 `THIRD_PARTY_NOTICES.md` 写明归属与修改说明。
2. **承认衍生关系，而不是绕开它**：注释里保留"移植自 / 行为与原版一致"的事实与出处，
   补上项目名与许可（AGPL-3.0），并把可验证的部分指向夹具编号。
   —— 这与审计 P3 的措辞有分歧：审计建议"不论走哪条路，这句话都不该留在源码里"；
   既然承认 AGPL，**写清出处才是合规动作**，抹掉反而是"明知而隐匿"。
3. **不再随包分发任何来自 SillyTavern 默认内容的资产**：删掉 `default_Seraphina.png`，
   演示卡改成自制（`tools/cards/make-demo-card.py` 生成，可重跑）。

## 为什么不走另外两条路

| 路 | 不选的理由 |
|---|---|
| **clean-room 重写** | 要重写 `ExampleDialogue` / `CardLorebook` / `WorldBookDecorators` / `ChatFile` / `PngCard` / `WorldBookFile` / `LorebookEntry` 这些**已被 golden fixture 锁死**的兼容内核。用户看不到任何变化，风险是把 P0–P3 四批成果重开一遍；而且清不干净的话，问题只是被推到以后。 |
| **向维护者要例外** | 现实中基本不给；而且它解决不了内容作者对角色卡的版权（第 3 条仍要自己做）。 |
| **什么都不做（保留所有权利）** | 公开仓库 + 公开 APK 已经是分发；缺授权不是"没写就没义务"，而是违约。 |

## 为什么不担心"接受 AGPL 的代价"

AGPL 的核心义务是"拿到二进制的人能拿到完整对应源码"。**源码本来就在公开仓库里**，
所以边际成本只有：根目录 `LICENSE` + 一份 `THIRD_PARTY_NOTICES.md` + README 一段说明。
真正的约束是"以后不能改闭源"——对一个源码公开、不上架、不商业化的项目，这是空约束。

## 后果

- **不能闭源**：以后即使加商业功能，整个作品仍是 AGPL。要改走别的许可，得先解决衍生问题。
- **App Store / Google Play 不兼容**（AGPL 的附加条款与商店条款冲突）；本项目本来也不上架。
- **F-Droid 可行**：F-Droid 接受 AGPL，但要求可复现构建与自由许可，届时还要处理签名与 `vendor/` 之外的东西。
- **每次分发都要带许可文本**：APK 里要有（`assets/notices/`），Release 页也应有；
  `THIRD_PARTY_NOTICES.md` 随源码即可（AGPL 只要求源码侧完整）。
- **`core/` 的注释要改成署名式表述**（第 2 条）。这一批先落许可文件与资产，
  注释改写单独一批做（纯注释，`tools/check-kotlin-comments.sh` 能守），避免把文档变更和代码变更混在一起。
- **内置卡的来源从此有硬约束**：`app/src/main/assets/cards/` 下的图必须是自制或授权明确的，
  `core/card-format` 的 `BundledCardTest` 会读这个目录，删卡/换卡都在本机能验。

## 待办（不属于本 ADR 的决策）

- `fixtures/worldinfo/P1-1-seraphina-book.json` 仍是从原版默认卡转出来的夹具，留在公开仓库里。
  它只影响测试，不随 APK 分发；要彻底处理得重录该链路的夹具（需要原版在跑），成本大于收益，
  暂记在审计文档的"待确认"里。
