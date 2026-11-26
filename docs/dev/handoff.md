# 接手卡（2026-11-26 09:20）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**：
> 现状 → [status.md](status.md)；规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)；
> 需求批的口径与排期 → [plan-req-2026-11-25.md](../plan-req-2026-11-25.md)；
> 词表（怎么称呼界面上每个东西）→ [ui-map.md](../ui-map.md)；
> 必须成立什么 → [ui-invariants.md](../ui-invariants.md)；
> 真机验证记录 → [verification/](verification/)（一批一个文件，索引在 README）；M0–M16 归档在 [device-verification.md](device-verification.md)；历史细节 → [pitfalls.md](pitfalls.md)。

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容它的文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)）。
- **代码基线**：`410a713`（M17 三条修复）。三包都已出：
  `app-v159-c984f71.apk`（`apk-m190`）、`app-v160-dcb0742.apk`（`apk-m191`）、`app-v161-410a713.apk`（`apk-m192`）。
- **真机确认到**：`v157`（M15）。v158 / v159 / v160 均已真机跑过；**M17 暴露的三条已修进 v161，
  待 M19 复验**（停止/继续/重生成的收尾、列表整屏闪、`‹2/2›` 位置、查看请求里的采样参数）。
- **在做的活**：2026-11-25 的 16 条需求（A/B/C 已确认、D 待验、E/F 未开工，口径见
  [plan-req-2026-11-25.md](../plan-req-2026-11-25.md)）+ 2026-11-26 凌晨的七条
  （[plan-req-2026-11-26.md](../plan-req-2026-11-26.md)，已出 v160）。
- **铁律**：core 纯 JVM；兼容行为必须带 fixture；不猜原版；本机不构建；**能复用先复用**；
  **说界面上的东西用 `docs/ui-map.md` 的名字，对不上就先问、不许猜**。

## 一、这个会话（2026-11-25 下午 → 深夜）做了什么

按实际交付顺序，**每批都有真机回执才往下走**：

| 交付 | 批次 | 内容 | 回执 |
|---|---|---|---|
| v150 | 页头结案 | 二级页首项留白还原、世界书列表补回 64dp（`8ee1b72` → `df71772` 这一系列收尾） | M9 / M9.1 全过 |
| v151 | 回归修复 | 卡配置「对话样式」「关于这张卡」被内嵌世界书顶掉（`6f4dd53` 把分发写成常量） | M10 全过 |
| v152 | INV-01 第一批 | 页头收口：`DetailHeaderMode { Frosted, Translucent }` + `contentSpacing`，骨架交出最终 top；删 `contentTopInset` / `frostedHeader` / `DetailScrollUnderTop`；12 处调用对齐；脚本 INV-01 三条判据 | M11 全过 |
| v153 | 不变量第二批 | INV-04（玻璃三铁律）/ INV-06（每个可滚面挂橡皮筋）/ INV-08（页面不许写字面色值）进脚本；补掉 3 处真漏 | M12 全过 |
| v154→v155 | 需求批 A | 聊天页离场不闪、二级页光标不残留、抽屉期间列表静止、删开场白底部文案 | M13 全过 |
| v156 | 需求批 B | 世界书/正则选择页三个动作进右上胶囊、开场白走显示正则、斜体改主色 | M14 全过 |
| v157 | 需求批 C | 条目全文页、会话置顶（DB v4）、两处搜索、会话预览与卡简介走正则 | M15 全过 |
| v158 | 需求批 D | 采样五项三级、默认正则整行删 + 占位符、内置正则三级；外加三处修正 | **待 M16** |

## 二、现在的状态

**真机已确认**：`v150`（M9/M9.1）→ `v151`（M10）→ `v152`（M11）→ `v153`（M12）→ `v155`（M13）→
`v156`（M14）→ `v157`（M15）。

**待真机确认（v158 / M16）** —— 见 [device-verification.md](device-verification.md) 的 M16：
1. 世界书条目页点进「条目全文」再返回，**列表保持原位置**；
2. 置顶组填充是描边色、片间是"白线"，行尾**只写时间**；
3. 默认正则：标签独占一行时**连那一行一起删**（其它空行不动），`<StatusPlaceHolderImpl/>` 也清；
4. 采样五项三级（会话 → 卡 → 预设 → 全局，留空跟随），全局采样页五项都在；
5. 内置正则三级开关（卡/会话正则页的「内置」分组 + 胶囊批量动作含内置）。

**已知未做（按优先级）**
- **E 批**：右上胶囊点开**变形成液态玻璃菜单**（需求 #12）。现在 4 处用 Material3 `DropdownMenu`，
  它是独立窗口、采不到内容层 → 做不成玻璃；要新写菜单宿主。口径：**先做聊天页 ⋮ 与卡详情 ⋮ 两处原型**，
  看过观感再铺开。新组件要同步 `ui-map.md` + INV-05 白名单。
- **F 批**：**用户人设**（需求 #3）。多人设、三级（会话 > 卡 > 全局默认）、`{{user}}` = 生效人设名、
  描述按原版位置进提示词（原版是当一条 system 消息）。**夹具先行**：`capture.mjs` 要能设人设，
  没有 fixture 不许开工（AGENTS 铁律 #2/#4）。
- 计划外的老账：世界书 **outlet**（缺夹具：原版只有预设里出现 `{{outlet::名字}}` 才展开）、
  卡库**分组**、`forbid_overrides` / "按角色禁用某段"、**角色详情页页头要不要穿**（卡头是常驻海报，
  要动就是单独一轮）。
- **明确不做**：MVU / 酒馆助手的**状态栏渲染**（HTML/JS 界面）——无 WebView（ADR 0002）。
  这轮只把 `<StatusPlaceHolderImpl/>` 清掉。

## 三、下一步优先级

1. **收 v161 的 M19 回执**（6 项，重点：停止三路收尾、列表不再闪、`‹2/2›` 位置）。
   v158 的 M16 剩余项、v160 的 M18 其余项用户已跑过；采样五项可用「查看请求」首行核对。
2. **审计 P0-3**：`data` 层 JVM 测试（三级配置编解码 / `.meta` 容错 / DB 迁移 / JSONL 往返）。
3. **E 批**：胶囊→玻璃菜单（先两处原型）。
4. **F 批**：用户人设（夹具先行）。
5. 老账：outlet（夹具先行）→ 卡库分组 → 角色详情页头。
6. 推迟项：`P1-3` 重组成本优化（等真机清单清空再做）。

## 四、这台机器与出包（比上一版更长的教训，每次都看）

- **本机编不了 `:app` / `:data`**，只能跑 `tools/test-core.sh` 与 `tools/check-*.sh`。
  提交前至少跑：`check-core-purity` / `check-kotlin-comments` / `check-kotlin-imports` /
  `check-ui-map` / `check-ui-invariants` + `test-core.sh`。
- **出包循环**：
  1. `git commit`（消息里有引号/反引号就写文件 + `-F`）→ `git push`；
  2. 等 CI：**优先用 release 页面判断**（`https://github.com/sunke340/AndroidTavern/releases/tag/apk-latest`
     里 grep 提交 sha），API 只在额度内用（见下）；
  3. 下载 `releases/download/apk-latest/app-debug.apk`（**加 `?cb=时间戳` 免缓存**）；
  4. **验 dex 里的 40-hex `BUILD_ID`**（`classes17.dex`，用 python `zipfile`）——**唯一判据**；
  5. `tools/verify-apk.sh <apk> 55:E0:…:0B:E7>`（完整指纹见脚本历史/CI 日志）；
  6. `cp` 成 `artifacts/app-vNNN-<sha7>.apk`，打 tag `apk-mN`，`git push origin <tag>`。
- ⚠️ **`apk-latest` 的发布步骤可能失败**（同一 release 里覆盖同名资产 `app-debug.apk` 会撞名，
  2026-11-26 踩过）：现象是「构建 success、测试 success，只有『公开 APK（apk-latest）』这一步红」。
  **处理：给同一个 commit 打 `apk-mN` tag 再 push**，走 tag release 取包（v158 就是这么出的）。
- ⚠️ **CI 失败日志**：`ci-log-android` / `ci-log-jvm` 两个 release，**现在按 `build-<sha7>.log` 命名**
  （固定名 `build.log` 会因为同名资产上传失败——失败日志那一步自己红，等于没日志）。
  直接下 `releases/download/ci-log-android/build-<sha7>.log` 看 `^e:` 行；
  CI 也把关键错误行打成 run 页面上的 annotation。
- ⚠️ **GitHub API 每小时 60 次**：`poll-ci.sh` 一轮两次、连跑就会打满（现象：脚本输出空、exit 3）。
  限流时**别再用它**，改「release 页面 grep sha」+「下载验 BUILD_ID」；要看 run 状态等重置。
- ⚠️ **`poll-ci.sh` 会假 READY**：它只看 release body 里的 sha，run 可能还没跑完。
- **真机日志**：设置 → 关于 → 导出日志（SAF）。AppLog 只写应用内，journald 里没有。

## 五、日志 tag 速查

| tag | 看什么 |
|---|---|
| `nav` | `转场预热 … 新页首帧 Xms`、`转场 … 帧 N，最长帧 Mms`、页面移除间隔 |
| `scroll` | 串流跟随模式、补偿落地、**`焦点滚动被拦下 …`**（选字不拖列表的判据） |
| `config` | 三级配置写入：会话/卡级模型、思考强度、预设、**采样五项**、**内置正则** |
| `chat` | 发送 / 完成 / 失败、换开场白、重新生成、swipe、继续、置顶 |
| `ui` | 输入区展开、离场收焦点与选区 epoch、**`抽屉打开：冻结列表底部让位 …`** |
| `wi` | 世界书：开书耗时、条目数、看条目全文 |
| `rubber` / `glass` | 橡皮筋回弹；玻璃档参数与逐像素校验 |

## 六、文件地图（这轮动过的）

| 想改什么 | 去哪 |
|---|---|
| 页头材质与首项让位 | `DetailPage.kt`（`DetailHeaderMode` / `contentSpacing` / `DetailHeader`） |
| 离场清理、抽屉期间列表静止 | `ChatScreen.kt`（`suppressItemFade` / `keepExpanded` / `frozenListBottom`） |
| 选择页（模型/采样/世界书/正则/预设） | `ConfigPickerScreen.kt`（`PickerBulkMenu`、`SamplingPickerBody`、内置分组） |
| 三级配置的生效值 | `ChatViewModel.UiState.effective*`（采样五项、`effectiveBuiltinDisabled` 等） |
| 会话列表（置顶/预览） | `SessionsScreen.kt`、`SessionTime.kt`；置顶字段在 `ChatRepository` / `SqliteChatRepository` / `TavernDatabase`（DB v4） |
| 世界书条目 / 全文 / 搜索 | `WorldBookEntriesScreen.kt`、`WorldBookEntryScreen.kt`、`WorldBookEntryRow.kt`、`CardConfigPageScreen.kt`、`SearchField.kt` |
| 显示管线（正则 → 样式） | `DisplayText.kt`（`markupText` / `displayPlain`）、`core/regex/BuiltinRegex.kt` |
| 采样与内置正则的存储 | `data/config/ConfigOverrideRepository.kt`（+ `impl/PreferencesConfigOverrideRepository`）、`data/settings/*` |
| 点触反馈（行/卡/底栏共用手感） | `Style.kt` 的 `rememberPressTint`（最短 90ms 可视） |
| 回到底部钮 | `ChatScreen.kt` 的 `JumpToBottomButton` + `bottomOverlay`（不参与输入区测量） |
| 开场白「设为默认」胶囊 | `CharacterDetailScreen.kt` 的 `DefaultGreetingChip` |
| 气泡字号三级 | `Screen.kt`（`BubbleFontSizes` / `ConfigKind.FontSize`）、`UiState.effectiveBubbleFontSize`、三处入口（`SettingsPages` / `CardConfigPageScreen` / `SessionConfigScreen` + `ConfigPickerScreen`） |
| 预设里的「提示词全文」 | `PresetPromptScreen`（新增 `Destination.PresetPrompt` + `UiState.presetPromptDetail`） |
| 流式任务所有权 | `ChatViewModel.StreamingTask` / `stop()` / `openSessionInternal()` 的取消逻辑 |
| 词表 / 不变量 | `docs/ui-map.md`、`docs/ui-invariants.md`、`tools/check-ui-map.sh`、`tools/check-ui-invariants.sh` |

## 七、这轮踩的坑（一句话一条）

1. **列表底部让位 = IME 让位 + 输入区高度**，两笔都直接进 `contentPadding.bottom`；只冻输入区，
   抽屉收起时键盘那一下照样让气泡整片往下掉（真机日志 301px→198px→键盘=false 才定位到）。
2. **宿主实例只能在骨架内部读**：页面顶层读 `LocalSheetController.current` 拿到的是没有宿主的默认实例，
   `spec` 永远是 null —— 拿它判断"抽屉开没开"会永远为假。
3. **列表滚动状态要提到 `MainScreen`**：二级页整棵会离开组合树，状态留在页里，从更深的一页返回就回顶部。
4. **CI 的交付面本身会坏**：失败日志与 `apk-latest` 都栽在"同名资产覆盖"；前者已改成
   annotation + `build-<sha7>.log`，后者的兜底是打 `apk-mN` tag。
5. **本机编不了 → 缺 import 只能由 CI 抓**：这轮两次都是 `Unresolved reference`（`dp`、`fillMaxWidth`）。
   加新 UI 代码后，用脚本把"常见 layout 修饰符 vs import"扫一遍再推（见 status.md 的风险段）。
6. **判据要按"它约束谁"划范围**：`Modifier.blur` 只在 `Glass.kt` 外违规（自研玻璃内部合法地糊录好的图层）；
   `.record(` 要数**顶层**逗号（嵌套括号里的逗号会误判成四参数）。
7. **`if (常量) A else B` 会静默杀死一支**：编译、脚本、回执三套防线都看不见（v149/v150 卡配置两页）。
8. 其它照旧：引号/反引号会撕提交消息（用 `-F`）；`positionInWindow` 是扩展函数要单独 import；
   页面里用 `0.dp` 要确认该文件 import 了 `dp`。
9. **`collectIsPressedAsState()` 会在同一帧按下+抬起时被合并** → 短促点按一次都不播。
   要给"必须跟手"的反馈，直接吃 `interactions` 流 + 最短可视时长（2026-11-26 修）。
10. **键盘收起动画不能等 `imeVisible == false`**：那是 IME 退场动画播完的时刻。
    盯 `snapshotFlow { ime.getBottom(density) }` 的"开始下降"才对（`density` 和 `ime` 要在组合里取好）。
11. **底部浮动控件不许进被测量的那一层**：列表让位读的是 `ChatInputBar` 的实测高度，
    把按钮塞进同一个测量里，它出现/消失就会让整屏气泡跳一下。
