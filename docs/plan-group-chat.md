# 群聊（P2-1）方案草稿 —— 夹具已录，代码未动

> 状态：**方案草稿**（2026-11-27 凌晨，`8e8951e`）。四份夹具已入库，请求形状已查实；
> 数据模型与 UI 还没动。**这一页给下一次会话开工用**，口径对齐
> [original-behavior.md](dev/original-behavior.md)「群聊」与 [compat-matrix.md](compat-matrix.md) P2-1。

## 1. 已经查实的（来自夹具，不是推的）

四份夹具：`P2-1-group-natural` / `-list` / `-manual` / `-pooled`（成员 `Synthetic` + `SysPrompt`）。
**四份的请求形状完全一样**，只有"这一轮谁说话"不同。

| 位置 | 群聊里的样子 |
|---|---|
| body `group_names` | 成员名数组（`["Synthetic","SysPrompt"]`） |
| body `char_name` | **这一轮的说话人** |
| main | 单聊那条模板，`{{char}}` = 说话人 |
| charDescription / personality / scenario | **只取说话人那张卡** |
| 示例对话 | 名字前缀**保留**（`User: …` / `Synthetic: …`），第一行照旧丢掉 |
| 历史块首 | `[Start a new group chat. Group members: Synthetic, SysPrompt]` |
| 历史每条 | `说话人: 正文`（用户是 `User: `） |
| 末尾 | `[Write the next reply only as <说话人>.]`（在 `post_history_instructions` 之前） |
| 卡的 `system_prompt` | 照样顶替 main（manual 那份里就是 `SYSPROMPT-OMEGA …`） |

**激活策略只影响"谁说话"**（natural ← 按上下文算、list ← 列表顺序、manual ← 用户点谁、
pooled ← 随机但不同人），**不影响请求结构**。这是 P2-1 里最容易做错的地方：不需要为四种策略写四套组装。

## 2. 数据模型（建议）

现在：`ChatSession` 一条会话 = 一张卡；消息只有 `role` / `content`。

建议的最小改动：

1. `ChatSession` 加 `groupMembers: List<String>`（卡 id 或 avatar 名；空 = 单聊）。
   DB 迁移到 **v5**：`ALTER TABLE sessions ADD COLUMN group_members TEXT`（JSON 数组或 `|` 分隔）。
2. 消息加 `speaker: String?`（群聊里是角色名；单聊 null）。
   JSONL 导出/导入时映射到 `name` 字段（原版每条消息本来就有 `name`，单聊也写）。
3. **说话人选择**不落库之外的状态：`activateStrategy`（0–3）+ `autoModeDelay` 存到会话里。

> 为什么不用"每张卡一条会话再合并"：原版是**一条**会话、一个 chat 文件，
> 历史和 `chat_metadata` 都是共享的；拆开会把 swipe / sticky / cooldown 这些按会话存的东西弄乱。

## 3. 提示词组装（core 改动）

`PromptBuilder.build(...)` 加一个可选参数：

```kotlin
public data class GroupContext(
    public val members: List<String>,   // 成员名，顺序即 `group_names`
    public val speaker: String,         // 这一轮谁说话
)
```

规则（全部有夹具断言）：
- `charName` 用 `speaker`；卡取说话人那张；
- 示例对话：**不剥名字前缀**（单聊才剥）——`ExampleDialogue.parse` 需要一个 `keepNames` 分支；
- 历史：`speaker: content`（用户是 `userName: content`）；
- 块首换成 `SillyTavernDefaults.GROUP_CHAT`（`[Start a new group chat. Group members: {members}]`）；
- 末尾追加 `[Write the next reply only as {speaker}.]`，位置在 `post_history_instructions` **之前**。

四项各来一条断言（拿四份夹具当 expected），别只测一条。

## 4. 界面

- 角色卡页：长按/多选 →「建群」，或页头「+」→ 选多张卡（复用 `CardPickerBody` 的多选形态）；
- 会话列表：群会话的行副标题显示成员数（`3 个角色`），头像用拼图（`CardFrost` 那套已有）；
- 聊天页：页头副标题显示成员；manual 策略下输入区上方要能选"这轮谁说话"；
- 群聊里**不做**「开场白翻页」（开场白是单卡的属性）。

## 5. 验收（真机 + 夹具）

| # | 怎么造 | 过 = 看到什么 |
|---|---|---|
| 1 | 两张卡建群，natural 策略发一句 | 只有一个人在回复；`查看本次请求` 里 `group_names` 两个、`char_name` = 说话人 |
| 2 | 同上，看历史 | 每条消息前有 `名字: `；块首是 `[Start a new group chat. Group members: …]` |
| 3 | 切到 manual，点另一个人说话 | 回复换人；请求里 `char_name` 跟着换 |
| 4 | 群里生成中按停止 / 重新生成 | 与单聊一致（揭示节奏那套照常） |
| 5 | 群聊导出 JSONL 再导入 | 成员名与 `speaker` 都不丢；PC 版能接着聊 |
| 6 | 单聊回归 | 任何单聊请求与现在逐字相同（`P0-5-prompt-basic` 仍绿） |
| 7 | 群里点「继续」 | 同一个人接着说；请求末尾有 `[Continue your last message …]`（夹具 `P2-1-group-continue`） |

## 6. 开放问题

1. **群里的「继续」——已查实**（夹具 `P2-1-group-continue`）：`char_name` = **被续写那条的说话人**
   （那份里是 SysPrompt，和最后一条 `SysPrompt: ok` 一致）；请求最末尾还会多一条
   `[Continue your last message without repeating its original content.]`（在 group nudge 之后）。
   → 我们"继续 = 同一说话人再落一条"的设计成立。
2. **群里的 swipe —— 半查实**（夹具 `P2-1-group-swipe`）：被替换的是最后一条
   （`SysPrompt: ok ok`），但请求里的 `char_name` 是 **Synthetic** —— 和"翻谁的消息"对不上，
   更像是"下一个该说话的人"。**这条还没想明白**，实现 swipe 之前要再录一份能明确区分
   "谁的消息被翻"与"谁下一个说"的夹具（例如成员顺序/策略换成 manual，再点指定人翻）。
3. **成员卡被删**：会话里的 `groupMembers` 会指向不存在的卡 —— 建议**只丢那一个成员**并提示，
   不要整条会话报废（单聊删卡连带删会话是另一条规矩，别混）。
4. **群里的 `first_mes`**：建群时第一条是"每个人的开场白"还是"发起人的"？还没录 ——
   建群那条链路（`createNewGroupChat`）会一次性写好历史，下次录一份 `--dump-chat` 就知道。
