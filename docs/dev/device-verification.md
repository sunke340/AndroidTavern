# 真机验证记录

只记"在手机上真的跑过"的事情，一条一条带证据。设计推演放别处。

---

## M0 · 2026-11-22 · 首次真机跑通

**APK**：`app-debug`（CI 出包，release `apk-latest`），约 11 MB，minSdk 26。
**链路**：PNG 卡（assets）→ `PngCard` → `CharacterCard` → `PromptBuilder` → `OpenAiCompatibleClient` → SSE 流式 → Compose 显示。
**provider**：OpenAI 兼容端点（用户填 baseUrl / key / model）。

### 结果：通过

| 检查项 | 证据 |
|---|---|
| 安装、启动 | 界面正常显示 |
| 卡片解析 | 状态行显示描述字数；「看请求」里出现完整描述 |
| 提示词组装 | 「看请求」显示 4 段：`main` → 描述（含 `[Seraphina's Personality= ...]`、`<START>`、示例对话）→ … |
| 请求发出 | 状态行 `完成（finish_reason=stop）` |
| 流式显示 | 回复逐字出现（用户确认） |
| 角色一致性 | 回复自称"塞拉菲娜，这片魔法森林——艾尔多利亚的守护者"，提到粉发、琥珀眼、治愈魔法、被野兽追赶——**全部来自卡里，不是模型自己编的** |

### 顺带确认的两个行为

1. 这张卡的 `description` **内含** `<START>` 与示例对话；原版把整个 description 当成一条 system 消息发出去，
   不做示例解析。我们照抄——「看请求」里能看到它原样在里面。
2. 用户用中文提问、卡是英文时，模型用中文回。这是模型行为（跟用户语言走），不是我们注入的语言指令。

### 这一轮暴露的问题

- CI 每次重新生成 debug keystore → 每次装新版都得先卸载（签名不一致）。待做：固定 debug 签名。
- `:core:ai` / `:core:card-format` 的公开 API 暴露了 `implementation` 依赖的类型
  （`OkHttpClient`、`JsonObject`），本机编不出来，只有 `:app` 会报 `Cannot access class X`。已修。
