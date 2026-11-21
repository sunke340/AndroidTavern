# 夹具流水线（设计，M0 实现）

目的：把「兼容原版」从形容词变成 CI 断言。做法是**跑原版 ST，录下它真正发给 API 的请求体**，再让 Kotlin 侧拼出来的东西与它逐条比对。

> 状态：设计稿。具体抓取点尚未实测，M0 第一件事就是把它跑通。以下标 ⚠️ 的步骤必须在实现时确认。

## 1. 思路

```
同一套输入（卡 + 世界书 + 预设 + 聊天）
        │
        ├─▶ 原版 ST（本机 node）──▶ 假 API :8899 ──▶ 录下请求体 ──▶ fixtures/expected/
        │
        └─▶ 我们的 PromptBuilder（Kotlin）──────────▶ 断言两边相等
```

## 2. 假 API

一个几十行的 Node 服务：收 `POST /v1/chat/completions`，把完整 body 落盘，回一段固定的 SSE 流。

要点：
- 必须回**流式**响应，否则原版可能走非流式分支，录到的 body 与手机端不一致。
- 回包内容固定（如 `"ok"`），这样 `expected/` 只关心请求。
- 记录时带上：ST 版本、所用预设名、聊天文件、世界书文件名——写进 fixture 的 `_meta`。

## 3. 在原版里怎么接上假 API

⚠️ 待确认。候选顺序：

1. **自定义 OpenAI 兼容端点**：ST 的 `chat_completion_source = custom` + `custom_url = http://127.0.0.1:8899/v1`。注意原版对非 localhost 的自定义 URL 可能走反代校验，localhost 一般放行；若被拦，看 `public/scripts/openai.js` 里 `custom_url` 的校验条件。
2. **反向代理**：把 `reverse_proxy` 指到假 API，这是原版支持的路径，最稳但要多配一步。
3. **最后手段**：在 `openai.js` 里临时插一行 `fetch` 落盘（只改 `vendor/` 的本地副本，不提交）。

M0 验证完把结论写回本节，删掉这个 ⚠️。

## 4. fixture 结构

```json
{
  "_meta": {
    "stVersion": "1.19.1",
    "preset": "Default",
    "character": "fixtures/cards/P0-5-seraphina.png",
    "chat": "fixtures/chats/P0-5-simple.jsonl",
    "worldinfo": null,
    "capture": "custom-endpoint",
    "note": "首轮生成，无作者注"
  },
  "request": {
    "model": "gpt-4o",
    "messages": [ { "role": "system", "content": "..." } ],
    "temperature": 1,
    "max_tokens": 300
  }
}
```

Kotlin 侧断言：

```kotlin
// 只比对我们声称兼容的部分，避免为无关字段（如 user 字段、stream_options）写死
assertEquals(expected.messages, actual.messages)
```

## 5. 覆盖顺序

先只录 P0-5（基础提示词），它绿了再录世界书，再录作者注/人设。**一次只加一个变量**，否则差异出来分不清是谁造成的。

## 6. 注意事项

- 原版的组装分三处（`public/script.js`、`PromptManager.js`、`openai.js`），录到的 body 是三者叠加的最终结果——所以**只以 body 为准**，不要试图从单处源码推导。
- 每次升级 `ST_TAG` 都要重录全部 fixture，diff 出来就是"原版变了什么"，这本身就是价值。
