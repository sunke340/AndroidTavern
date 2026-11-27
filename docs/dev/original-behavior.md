# 原版行为笔记（实测，非推断）

这里只记**已经被夹具证明过**的行为。每条都写清楚：结论、证据（夹具）、源码位置。
没验证过的猜测不要写进来——那是 `docs/dev/fixtures.md` 说的"不猜原版行为"。

---

## 提示词组装

### main 提示词

`Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.`
先做宏替换，再作为第一条 system 消息。
证据：`P0-5-prompt-basic` [0]；源码 `default/content/presets/openai/Default.json` 的 `main`。

### 空字段不产生消息

`personality` / `scenario` / `mes_example` 为空时，对应的消息**整条消失**，不会出现空字符串消息。
证据：Seraphina 卡的 personality 为空，`P1-1-prompt-lorebook` 里就没有 personality 那条。

### CRLF 一律去掉

卡里的 description 是 CRLF，发出去是 LF。**历史消息**也逐条去掉 `\r`（源码注释：waste of tokens）。
证据：`P0-5-prompt-basic` [1] 与卡片原文的逐字符 diff（5 处 `\r` 删除）；源码 `openai.js` 的 `content.replace(/\r/gm, '')`。

### 示例对话：三个反直觉的规矩

1. **第一行永远被丢掉**——原版假设它必然是 `<START>` 这类标题，没有标题也照丢。
2. `<START>`（不分大小写）先被替换成 `{Example Dialogue:}`，然后随第一行一起消失；
   真正发出去的是**另外插入的一条** `[Example Chat]`（预设 `new_example_chat_prompt`），
   每条示例块前插一条。
3. 名字前缀是"**删掉第一个出现的 `名字:`**"（`replace` 而非去前缀），所以正文中间出现同名也会被吃。
   消息的 role 全部是 `system`，不是 user/assistant。
证据：`P0-5-prompt-basic` [4][5][6]；源码 `openai.js` 的 `setOpenAIMessageExamples` / `parseExampleIntoIndividual`。

### new chat 标记的位置

`[Start a new Chat]` 是 `chatHistory` 块的第一条，**零历史和多轮历史都在**（不是"只有新聊天才插"）。
证据：`P0-5-prompt-history` [7] 之后紧跟 [8] 开场白。

### 历史消息的角色映射

角色卡消息 → `assistant`；用户消息 → `user`；系统消息 → `system`。内容原样，**默认不加名字前缀**。
证据：`P0-5-prompt-history` [8]–[11]。

### names_behavior 的四种取值

`NONE=-1`（永不加）、`DEFAULT=0`（**只有群聊或 sendas 才加** `名字: ` 前缀）、`CONTENT=2`（除旁白外都加）、
`COMPLETION=1`（不加，走 completion 模式）。预设默认是 `DEFAULT`。
证据：源码 `openai.js` 的 `character_names_behavior` 与 595–611 行；单聊夹具里没有前缀即为 DEFAULT 的表现。

### 人设描述：`prompt_order` 里的一格，不是"拼进 main"

人设描述（`power_user.persona_description`，默认位置 `IN_PROMPT`）是**独立的一条 system 消息**，
位置由 `prompt_order` 的 `personaDescription` 决定 —— 默认预设里在 `worldInfoBefore` 之后、
`charDescription` 之前。`{{user}}` 展开成人设名。
证据：`P1-8-persona-prompt` [1]；源码 `openai.js:1433`。

**顺带一个坑**：原版 `Default.json` 有**两张** `prompt_order`：`character_id = 100000`（文本补全）
与 `100001`（聊天补全，多了 `personaDescription`）。我们走 100001（`openai.js` 的 `dummyId: 100001`）。

### 作者注：一条按间隔插进历史的文本

`ANWithWI = ANTop.join("\n") + "\n" + 正文 + "\n" + ANBottom.join("\n")`，只削掉**首尾各一个**换行
（不是 trim —— 正文为空时中间的空行会留下）；然后按 `interval` 决定插不插：
`interval = 1` 是特判（每轮都插，连一句用户消息都没有也插），否则 `用户消息数 % interval == 0`。
插进历史时深度 = 「它后面正好留几条消息」，最少退到 `[Start a new Chat]` 之后。
位置三档：`IN_PROMPT`（紧跟主提示词，`P1-7-an-pos-0`）、`IN_CHAT`（`P1-7-an-depth-2`）、
`BEFORE_PROMPT`（主提示词之前，`P1-7-an-pos-2`）。
证据：`P1-7-an-prompt` / `-depth-2` / `-with-wi` / `-pos-0` / `-pos-2`；源码 `world-info.js:5270`、`authors-note.js:324`。

### 示例对话也能被世界书插：EMTop / EMBottom

`position = 5 / 6` 的世界书条目并进「示例对话」块：**每一块（世界书每条 + 卡自己的 `mes_example`）
都自带一个 `[Example Chat]`**，顶部在卡示例之前、底部在之后。内容同样过 `parseMesExamples`
（以 `<START>` 分段并剥名字前缀）—— 没写 `<START>` 的单段内容会被整段丢掉（我第一次录出来就是三个空标记）。
证据：`P1-3-example-injection`；源码 `script.js:4639`。

### 出口（outlet）：`{{outlet::名字}}` 宏

`position = 7` 的条目收进 `WIOutletEntries`，以 `extension_prompt_types.NONE` 挂成扩展提示词 ——
**永远不会自己出现在系统提示里**，只被 `{{outlet::名字}}` 宏取走（`getOutletPrompt(key) || ''`，
没激活时替换成空串，宏本身不留）。
证据：`P1-3-outlet`；源码 `world-info.js:5249`、`macros.js:668`。

### `forbid_overrides`：逐条提示词的旗标

预设里某条提示词带 `forbid_overrides: true` 时，**卡里的 `system_prompt` / `post_history_instructions`
不许顶掉它**（判定是 `!== true`，所以没写 / false 都算"可以被顶掉"）。
证据：`P0-5-sysprompt`（没旗标，卡里的顶掉了）vs `P0-5-forbid-overrides`（打旗标后回落到预设正文；
jailbreak 在默认预设里是空串，于是整条消息消失）；源码 `openai.js:1498/1508`。

### 群聊：一条链里"只写当前说话的那个人"

四份夹具把四种激活策略都录了（`P2-1-group-natural` / `-list` / `-manual` / `-pooled`），
成员都是 `Synthetic` + `SysPrompt`；**四份的请求形状完全一样**，只有"这一轮谁说话"不同
（natural ← Synthetic、list ← SysPrompt、manual ← SysPrompt、pooled ← Synthetic）：

| 位置 | 单聊 | 群聊 |
|---|---|---|
| `group_names` | `[]` | 成员名（`["Synthetic","SysPrompt"]`） |
| `char_name` | 卡名 | **当前该说话的那个**（natural 是 Synthetic，list 那轮是 SysPrompt） |
| main | `Write {{char}}'s …` | 同模板，但 `{{char}}` = 说话人 |
| 角色三段（desC/人格/场景） | 这张卡 | **只有说话人那张卡**（list 夹具里是 SysPrompt 的 `DESC-SYS`/`PERS-SYS`/`SCEN-SYS`） |
| 示例对话 | 名字前缀被剥掉 | **前缀保留**（`User: …` / `Synthetic: …`） |
| 历史块首 | `[Start a new Chat]` | `[Start a new group chat. Group members: Synthetic, SysPrompt]` |
| 历史消息 | 原文 | 每条前面加 `名字: `（`Synthetic: FIRST-OMEGA` / `SysPrompt: FIRST-SYS`） |
| 末尾 | 无 | 一条控制提示 `[Write the next reply only as <说话人>.]` |
| 卡的 `system_prompt` | 顶替 main | 同样顶替（list 夹具 [0] 是 `SYSPROMPT-OMEGA`，因为那张卡有 system_prompt） |

`allow_self_responses` / `generation_mode` / `activation_strategy` 会改变"这一轮谁说话"，
但**每一条请求的形状是上面这样**。

证据：`P2-1-group-natural` / `-list` / `-manual` / `-pooled`。

**继续**：`char_name` = 被续写那条的说话人，末尾多一条
`[Continue your last message without repeating its original content.]`（夹具 `P2-1-group-continue`）。

**Swipe**：两份夹具（`-swipe` / `-manual-swipe`）里被替换的都是 SysPrompt 那条，
但 `char_name` 与 nudge 都指向 **Synthetic**（下一个该说话的人）—— 语义可疑，
实现前先分清是「原版怪处」还是「录制时序问题」，别照抄（见 plan-group-chat §6.2）。

---

## 角色卡文件

### 读卡时 `ccv3` 优先

PNG 里可能同时有 `tEXt` 的 `chara`（V2）和 `ccv3`（V3），**读的时候 ccv3 优先**，没有再退回 chara。
证据：`src/character-card-parser.js` 的 `read()`；Seraphina 卡两个都有，我们按 ccv3 读出 `spec=chara_card_v3`。

### 写卡时两个都写

原版写回时先删掉已有的 `chara`/`ccv3`，然后**同一份 JSON 写两遍**：一份 `chara`，
一份把 `spec`/`spec_version` 改成 `chara_card_v3`/`3.0` 写进 `ccv3`，都插在 `IEND` 之前。
这样老客户端（只认 chara）和新客户端（只认 ccv3）都能读。
证据：同文件 `write()`；我们的 `PngCard.write` 与 `PngCardTest` 复刻了这一点。

### 只动 tEXt，不动图

写回时除 `tEXt`（`chara`/`ccv3`）以外的 chunk 一律原样搬运；IDAT 被重编码就意味着画质/体积受损。
证据：`PngCardTest` 断言重写前后 IDAT 逐字节相同。

## 待验证（不要照抄进实现）

- `names_behavior = CONTENT` 时的完整前缀规则（要单录一份夹具）。
- 世界书注入的位置、顺序、格式（`P1-1-prompt-lorebook` 已录，未分析）。
- token 预算不够时先砍谁（需要一份超长上下文的夹具）。
- 群聊的成员名前缀与 `group_nudge_prompt`。

---

## 世界书

### 扫描输入带名字前缀，且是倒序的

扫的是 `coreChat.map(x => `${x.name}: ${x.mes}`).reverse()`（`public/script.js:4626`），
**每条消息前面有 `名字: `**，数组从最新往最旧。全局扫描深度默认 2（`world-info.js:69`），
即只看最后两条。这也是为什么条目里写 `alpha` 能匹配到用户刚发的那句话。
证据：`P1-2-logic`（用户说 `alpha beta delta`，四条逻辑条目都按这个缓冲判定）。

### 卡内嵌书：`enabled` 缺字段 = 关

`convertCharacterBook()` 写的是 `disable: !entry.enabled`（`world-info.js:5640`），
所以卡里没写 `enabled` 的条目在原版里是**关着的**。不是"默认打开"。
证据：`P1-1` 的卡 4 条都显式 `enabled: true`；对照 `CardLorebook.kt` 的实现。

### 只导入不挂载 = 一条都不注入

原版把内嵌书导入成独立世界书文件后，还要挂到卡（`character.data.extensions.world`）
或全局激活列表（`selected_world_info`）才会被 `getCharacterLore` / `getGlobalLore` 读到。
证据：第一次录 `P1-2` 时只导入不挂载，请求体里一条世界书都没有；补上 `--select-world` 后才有。

### 落地顺序：同序号的条目，注入时是**反序**

`[...allActivatedEntries.values()].sort(sortFn)`（`sortFn` 是 order 降序，稳定排序）后
逐条 `unshift` 进 `WIBeforeEntries`（`world-info.js:5203-5214`）。
所以同 `insertion_order` 的条目，在提示词里出现的次序与书里的次序相反。
证据：`P1-1` 书里是 eldoria/shadowfang/glade/power，注入出来是 power/shadowfang/eldoria。

### 预算数的是"一条消息"的 token，不是裸文本

世界书预算 `budget = round(world_info_budget% × maxContext / 100)`（`world-info.js:4736`），
但计 `getTokenCountAsync(text)` 时走 openai 分支（`tokenizers.js:566`）→
server `/api/tokenizers/openai/count` 按 OpenAI 消息规则算 **3 + 内容 + 3**（`src/endpoints/tokenizers.js:1000-1016`）。
也就是**裸计数 + 6**。按裸计数算预算会差出一条（我第一版就是这么错的）。
证据：`P1-4-budget`（5% 预算，6 条等长条目只进 3 条）；`P1-6-tokens`（10 条计数逐条相等）。

### 递归只在"有新的成功条目"时继续

`nextScanState = RECURSION` 的条件是 `world_info_recursive && !token_budget_overflowed && successfulNewEntriesForRecursion.length`
（`world-info.js:5097`）。`delayUntilRecursion` 的条目在初次扫描被压掉，只有进入递归轮才放行。
证据：`P1-3-recursion`（初始只有 hook 命中，递归轮才带出 wombat 条目与延后条目）。

### sticky / cooldown / delay 以"聊天条数"计时

效果的 `start = chat.length`、`end = start + 轮数`（`world-info.js:604`），存在
`chat_metadata.timedWorldInfo` 里跟着聊天落盘。sticky 结束时若条目有 cooldown，会立刻挂上冷却
（`world-info.js:518`）。`delay` 则是"聊天少于 N 条时压住"（`world-info.js:666`）。
证据：`P1-3-sticky`（关键字消失仍在）/ `P1-3-cooldown`（关键字在却被压住）/ `P1-3-delay` + `P1-3-delay-passed`。

### 包含组：先按 sticky 淘汰，再按关键字分数，最后按权重抽

`filterByInclusionGroups`（`world-info.js:5388`）三段：sticky 独占 → `getScore()` 比主/次键命中数
（`world-info.js:428`）→ 上一轮已激活则整组让位 → 否则 `groupWeight` 加权随机。
证据：`P1-3-groups`（`alpha`+`beta` 命中 2 个键的条目把只命中 1 个的挤掉）。


## 我们**主动偏离**原版的地方（每条都要写理由）

### 1. 旧预设缺 `personaDescription` 那一格时自动补上（2026-11-27）

原版：`prepareOpenAIMessages` 按 `prompt_order` 逐条走，**表里没有 `personaDescription` 就不发人设**。
所以一个 2024 年导出的预设，配上 2026 年新做的"人设"功能 = 人设静默丢失。

真机证据（`tavern-log-20261127-122346`）：`发送 … 人设=卡尔(描述=6字)`，而 `messages` 条数与
"没选人设"时完全一样 —— 人设被丢了，界面上却看不出任何异常。

我们的做法（`core/preset/PromptOrderCompat.ensurePersonaSlot`）：
- 表里**没有**这一格 → 插在 `worldInfoBefore` 之后（就是内置默认表的位置）；
- 表里**有但被关掉**（`enabled = false`）→ **尊重**，不强行打开（那是用户显式关的）；
- 顺序为空（还没加载到）→ 不补，免得凭空造表。

为什么值得偏离：这不是"猜原版行为"，而是"旧文件 + 新功能"的向前兼容；不补的结果是功能静默失效，
而原版那条判据的本意是"按用户的顺序表发"，不是"把人设藏起来"。
