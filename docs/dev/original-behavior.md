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

---

## 待验证（不要照抄进实现）

- `names_behavior = CONTENT` 时的完整前缀规则（要单录一份夹具）。
- 世界书注入的位置、顺序、格式（`P1-1-prompt-lorebook` 已录，未分析）。
- token 预算不够时先砍谁（需要一份超长上下文的夹具）。
- 群聊的成员名前缀与 `group_nudge_prompt`。
