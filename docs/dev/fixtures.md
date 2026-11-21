# 夹具流水线（已跑通，2026-11-22）

目的：把「兼容原版」从形容词变成断言。做法是**让原版自己组装提示词，我们在传输层把请求体抄下来**。

产出：`fixtures/expected/P0-5-prompt-basic.json`（5 条 messages，已确认两次录制完全一致）。

## 1. 怎么录

```bash
# 1) 起原版（本机 8000）
cd vendor/SillyTavern && node server.js --browserLaunchEnabled=false

# 2) 另开一个 shell 录
cd /home/dsh/AndroidTavern
export LD_LIBRARY_PATH=$PWD/vendor/pw-libs/usr/lib/x86_64-linux-gnu
export PLAYWRIGHT_BROWSERS_PATH=$PWD/vendor/pw-browsers
node tools/fixture/capture.mjs --out fixtures/expected/P0-5-prompt-basic.json
```

流程（`tools/fixture/capture.mjs`）：

1. Playwright 打开原版页面，等 preloader 消失；
2. 选中角色卡（`selectCharacterById`），等聊天真正加载；
3. **把 `window.fetch` 换成拦截器**，命中 `/api/backends/chat-completions/generate` 就把 body 存下来，回一段固定 SSE；
4. 调原版的 `Generate('normal')` —— 组装、世界书、宏、裁剪全是它自己做的；
5. 把捕获到的 body 写成 fixture。

**为什么不用假 API 服务器**：假 API 得复刻 ST 的 SSE 回包格式、还得让 ST 的自定义端点校验放行；而 `fetch` 拦截在它之前，拿到的 body 一字不差，还少一个进程。

## 2. 三个坑（脚本里已处理，别再踩）

| 坑 | 现象 | 解法 |
|---|---|---|
| `main_api` 由页面下拉框驱动，没有导出 setter | 生成静默结束，一个请求都没有 | 设 `#main_api` 的 value 为 `openai` 并 `dispatchEvent(new Event('change'))` |
| `online_status === 'no_connection'` 时 `Generate` 直接 early return，**不抛错** | 同上，日志只有 `Generate entered` | `setOnlineStatus('mock-model')` 把它骗成已连接 |
| 每轮生成会把假回复写进聊天 | 第二次录出的 messages 变了（chatLength 1→2） | 录制前清掉 `data/default-user/chats/<角色>/`；`--keep-chat` 可保留 |

## 2.5 两个夹具，两张卡

| 夹具 | 卡 | 覆盖 |
|---|---|---|
| `P0-5-prompt-basic` | `fixtures/cards/P0-5-synthetic.png`（合成） | 卡 → 提示词的主干：main、描述、人格、场景、示例、newChat、开场白 |
| `P1-1-prompt-lorebook` | 原版自带的 `Seraphina` | 额外包含 character_book 注入（P1 才实现） |

**为什么不用 Seraphina 做 P0-5**：它内嵌 `character_book`，一生成就注入世界书条目，
把主干和"世界书激活"混在同一个夹具里，失败时分不清是谁的锅。合成卡的字段用
`DESC-ALPHA` / `PERS-BETA` / `SCEN-GAMMA` / `EXAMPLE-DELTA` 这类标记，任何变换都一眼可见。
生成脚本：`python3 tools/fixture/make-test-card.py`。

⚠️ 实测发现：`Generate('normal', { skipWIAN: true })` **并不会**跳过角色卡内嵌 book 的注入
（两张卡录出来的 5 条消息一模一样）。所以"关掉世界书"这条路走不通，只能换卡。

## 2.6 多轮历史：`--seed`

```bash
node tools/fixture/capture.mjs --out fixtures/expected/P0-5-prompt-history.json \
  --character Synthetic --no-wi --seed fixtures/chats/P0-5-multiturn-seed.json
```

`--seed` 不写 JSONL（原版不认手写的聊天文件，会直接开一个新的），而是像用户一样把
`userTurns` 里的每句话发进去：设 `#send_textarea` → `Generate('normal')`。
助手回复由假 SSE 统一给 `ok`，所以每轮可复现。录到的是**最后一轮**请求。

产物：12 条消息，`[Start a new Chat]` 后面跟着开场白 + 两轮问答。

## 3. fixture 结构

```json
{
  "_meta": { "stVersion", "capturedAt", "endpoint", "how", "character", "context", "generateError" },
  "request": { "type", "messages": [...], "model", "temperature", "stream", ... }
}
```

Kotlin 侧断言**只比 messages**：

```kotlin
assertEquals(expected.request.messages, actual.messages)
```

`user_name`、`char_name`、`group_names` 这些是 ST server 转发时才用的字段，别写死进断言——它们变了不代表不兼容。

## 4. 局限（诚实标注）

- 录到的是 **client → ST server** 的请求体，还没到 provider。ST server 基本原样转发，但 `custom_prompt_post_processing` 等字段是 server 侧消费的，真要复刻时再看 `src/endpoints/backends/chat-completions.js`。
- 只覆盖「默认预设 + 单卡 + 首轮」。世界书、作者注、群聊、预设变体都要各自录一份，命名对齐矩阵 ID。
- 每次升级 `ST_TAG` 要重录全部 fixture，diff 出来就是"原版变了什么"。

## 5. 后续（M1 之前要补的）

- [ ] `--preset` / `--worldinfo` 参数，让 fixture 能带不同预设和世界书
- [ ] 一份「有历史消息」的 recorder（现在只有开场白）
- [ ] 把录制放进 CI（`workflow_dispatch`），产物作为 artifact 回传，避免人工跑
