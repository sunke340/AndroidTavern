# 接手卡（2026-11-25 02:10）

> 给"下一次压缩上下文之后"的自己和任何接手的人。**这一页只讲别漏掉的东西**：
> 现状 → [status.md](status.md)；规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)；
> 进度 → [compat-matrix.md](../compat-matrix.md)；历史与踩坑 → [pitfalls.md](pitfalls.md)。

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容它的文件格式与提示词行为。
  单 APK、单进程、无 WebView、无后端（[ADR 0002](../adr/0002-single-process.md)）。
- **基线**：代码 `5926993`（tag `apk-m154`）/ 交付包 `artifacts/app-v123-5926993.apk`；
  这一夜的包 v118–v122 都在 `artifacts/`（每个都验过签名与 dex `BUILD_ID`）。
- **没有悬着的事**：用户 2026-11-25 列出的 10 条需求 + P0-6 消息操作**全部落码并出包**。
  **但全部还没有真机验收** —— 上一轮（串流跟随/转场/橡皮筋）才是验过的。
- **下一件事**：按 [status.md](status.md)「待真机验」那张表从上到下点一遍；再往后是世界书剩余、
  卡编辑（写回 PNG）。P0-6 的余项：`继续` 的裸拼在真实模型上是否粘字（夹具里是 `ok ok`）。
- **铁律**：core 纯 JVM；兼容行为必须带 fixture；不猜原版；本机不构建；**能复用先复用**。

## 一、这一夜做了什么（10 条需求 + P0-6）

| 需求 | 实现要点 | 关键决策 / 依据 |
|---|---|---|
| 1 抽屉把手拖拽 | 把手区整宽 28dp 可拖，跟手用 `translationY`；松手按"位移 > 弹层高 1/4 或下甩 > 800dp/s"收回，否则临界阻尼弹回 | 不改高度（拖动中重排会换行）；共享组件，全 App 生效 |
| 5–8 输入区 | 一个玻璃胶囊：收起=单行（发送在胶囊内右端），聚焦=向上加一行（左模型右发送），文字最多 5 行，发送后收起 | 底部让位从常量 80dp 改成**实测高度**，否则翻倍后最后一条被盖 |
| 9 模型钮 | 左下 40dp 玻璃圆钮，显示当前档案首字；抽屉列档案 + 思考强度（跟随/关/低/中/高） | 写**会话级**覆盖，不动全局默认 |
| 2 三级配置 | `ConfigOverride`（null=跟随）+ `session:<id>` / `card:<id>` 存 SharedPreferences；会话配置页从聊天 ⋮ 进，卡配置在详情「配置」tab | 解析只在 `UiState.effective*` 这几个 getter；发送/世界书/正则/连接参数全读它们 |
| 3 开场白 | 卡的全部开场白进 `UiState.greetings`；聊天页在"没说过话 + 不止一条"时于气泡**上沿**放 `‹ N/M ›`，选中=改写第一条消息；详情页 tab 用同款箭头翻页、非默认时出「设为默认」 | 用户要的形态（箭头贴气泡、不要页头按钮）；B 策略=只有空会话能换 |
| 4 详情页 | 保留 TabRow，卡头改 200dp 大图 + 底部渐变压名字/元信息 | 用户明确"只重做卡头与信息" |
| 10 玻璃确认框 | `AlertDialog`（独立窗口采不到内容层）→ 控制器 + 骨架宿主 + `LiquidGlassSpec.Sheet` | 8 处调用点签名不变，内部换实现 |
| P0-6 | 长按气泡：复制/编辑/删除；最后一条助手消息还有重新生成、继续；swipe `‹ N/M ›` 翻页 | 重新生成=新增 swipe 备选；继续=末尾加原版那条 system 提示；备选存 `message_swipes`（DB v3） |

## 二、三级配置（会话 > 角色卡 > 全局）

- 数据：`data/config/ConfigOverride`（profileId / reasoningEffort / presetName / worldBookNames /
  regexIds / greetingIndex，**每项 null = 跟随上级**）。空集 ≠ null（"全都不要"是合法选择）。
- 存储：SharedPreferences，作用域 `session:<id>` / `card:<id>`；删会话时顺手清。
- 解析：`UiState.effectiveProfileId / effectiveReasoningEffort / effectivePresetName /
  effectiveWorldBookNames / effectiveRegexIds / effectiveRegexScripts`。**别在别处再写 `x ?: y ?: z`。**
- 切换会话：`openSessionInternal` 按覆盖重放连接参数（baseUrl/model/apiKey）与预设（promptOrder/提示词/采样）。
- 界面：`ConfigSheets.kt` 四个抽屉构造器（模型/预设/世界书/正则），单选直接选，多选点了不关、✓ 原地刷新。

## 三、聊天输入区几何（用户定的，别再改）

- 收起：单行，文字 + 发送（发送**在胶囊里**）。
- 聚焦：高度翻倍 → 上行只放输入，下行左模型右发送（`AnimatedVisibility` 只淡入，不加高度动画：
  玻璃着色器随节点尺寸重建，每帧重建代价高）。
- 文字超过一行继续长高，最多 5 行；发送后清焦点收起。
- 列表底部让位读**实测像素高度**（`onHeightChanged` → `contentPadding`），不是常量。

## 四、消息操作（P0-6）

| 动作 | 语义 | 证据 |
|---|---|---|
| 重新生成 | 不删行：新文本作为**新的 swipe 备选**追加，旧的可翻回 | `fixtures/expected/P0-6-swipes.json` |
| 继续 | 请求末尾追加 system `[Continue your last message without repeating its original content.]`，结果接在原消息后面 | `fixtures/expected/P0-6-continue.json`（落盘 `ok ok`） |
| swipe | 只对最后一条助手消息（原版 `isMessageSwipeable`）；备选 = `message_swipes` 表 | 同上，原始 chat 在 `fixtures/chats/` |
| 编辑 / 删除 | 编辑同步改当前 swipe 那一份；删除走玻璃确认框，备选级联删 | 手工 |

- `send()` 与上面三件事共用 `beginCompletion(history, userText, replacing, continuation)`；
  `userText == null` = 不追加用户消息。**别再复制一份两百行的发送逻辑。**
- 失败路径：`ReplaceTarget.append=false` 还原被撤下的那条；`append=true` 也是还原（继续不产生新行）。

## 五、待真机验（这一夜全部新 UI，一组一组点）

1. **抽屉**：长按行 → 拖把手跟手、过半收回、否则弹回。
2. **输入区**：聚焦翻倍、多行长高、发送后收起、最后一条不被盖。
3. **模型**：左下钮首字正确；抽屉换模型/思考强度；日志 `tag=config`。
4. **会话配置**：改一项后行尾"生效值 · 来源"变「会话」；切回跟随变「角色卡/全局」。
5. **卡配置**：详情 → 配置，四行都生效（新开会话验证）。
6. **开场白**：空会话翻页即换第一条；说过话后箭头消失；详情页「设为默认」影响新会话。
7. **详情页**：大图卡头 + 名字压在图上、不被渐变糊掉。
8. **确认框**：任意删除是玻璃、点遮罩/返回键能取消。
9. **消息操作**：长按气泡四种动作；重新生成后 `‹ 1/2 ›` 能翻回；继续后文本接在末尾。

## 六、下一步优先级

1. 上面九组真机验收（最可能有问题的是输入区让位与抽屉拖拽手感）。
2. 世界书剩余：sticky 计时落库、`@@` 装饰器、outlet 位置。
3. 卡编辑（写回 PNG，需要备份 + 校验）—— **明确没做**，别顺手改卡文件。
4. C 批模型档案真机验收。

## 七、这台机器与出包（每次都要用）

- **本机编不了 `:app` / `:data`**：只能跑 `tools/test-core.sh`、三个 `check-*.sh`。
- **出包循环**：
  1. `git commit` → `git tag apk-mN && git push origin HEAD apk-mN`
  2. **等 run 真的 completed/success**（不是 `poll-ci.sh` 说 READY —— 见下）
  3. 下载 `releases/download/apk-latest/app-debug.apk`
  4. **验 dex 里的 40-hex `BUILD_ID`**（在 `classes17.dex` 里，`unzip` 没装就用 python `zipfile`）
  5. `tools/verify-apk.sh <apk> 55:E0:…:0B:E7`
  6. `cp` 成 `artifacts/app-vNNN-<sha7>.apk`
- ⚠️ **`poll-ci.sh` 会假 READY**：workflow 先更新 `apk-latest` 的 body（带新 sha）再上传资产，
  脚本只看 body。2026-11-25 实测过一次"READY 但资产还是上一次的"。**唯一判据是下载后的 `BUILD_ID`。**
- **日志**：设置 → 关于 → 导出日志（SAF）。

## 八、日志 tag 速查

| tag | 看什么 |
|---|---|
| `nav` | 转场两行：`转场预热 … 新页首帧 Xms` + `转场 … 帧 N，最长帧 Mms` |
| `scroll` | 聊天跟随：`模式=A/B/C`、`补偿 +Npx`、`边界 …` |
| `config` | 三级配置：`会话模型 id=…`、`会话预设=…`、`卡级模型 …`、`开会话配置` |
| `chat` | 发送/完成/失败；`换开场白 index=…`、`重新生成：…`、`swipe 切到 N/M`、`继续：…` |
| `rubber` / `glass` | 橡皮筋回弹、玻璃档与着色器 |
| `startup` / `wi` / `card` | 启动各步、世界书解析、读详情耗时 |

## 九、文件地图（这一夜动过的）

| 想改什么 | 去哪 |
|---|---|
| 三级配置（数据/抽屉/页面） | `data/config/ConfigOverrideRepository.kt`、`app/.../ConfigSheets.kt`、`SessionConfigScreen.kt` |
| 输入区 / 模型钮 / 消息操作入口 | `ChatScreen.kt`（`ChatInputBar` / `SendStopButton` / `ModelButton` / `EditMessageDialog`） |
| 消息操作与发送 | `ChatViewModel.kt`（`beginCompletion` / `ReplaceTarget` / `regenerateLast` / `continueLast` / `selectSwipe`） |
| 开场白 | `GreetingSwitch.kt`（箭头与 `‹ N/M ›`）、`CharacterDetailScreen.kt`（`GreetingTab`）、`ChatScreen` 的 greeting item |
| 抽屉把手拖拽 | `ActionSheet.kt`（`GlassSheetPlate`） |
| 玻璃确认框 | `ConfirmDeleteDialog.kt`（控制器 + `GlassConfirmHost`）、两个骨架里各挂一次 |
| swipe 备选表 | `TavernDatabase.kt`（v3）、`SqliteChatRepository.kt`（`swipes` / `setSwipes`） |
| 本质回归点 | `artifacts/app-v117-0631bff.apk`（串流跟随那一轮验收过的包） |

## 十、这一夜踩的坑（一句话）

1. **切片替换函数体时先确认另一个函数在它前面还是后面**：`ScrollArea` 排在 `GreetingTab` 之前，
   用 `index()` 取到的是更靠前的位置 → 整段被复制，CI 报 `Conflicting overloads`（v120 修）。
2. **`poll-ci.sh` 的 READY 不是"包已更新"**，必须验 `BUILD_ID`。
3. **Shell 里 `git commit -m "...\"继续\"..."`** 会被引号截断；消息里有引号就写文件 + `-F`。
4. **玻璃着色器随节点尺寸重建**：别给玻璃表面加逐帧尺寸动画（输入区展开用"只淡入"）。
5. **`AlertDialog` 永远做不成液态玻璃**（独立窗口采不到内容层）—— 用控制器 + 骨架宿主。
