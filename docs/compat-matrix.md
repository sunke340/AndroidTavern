# 兼容矩阵

**本文件是进度的唯一真相。** 任何兼容行为的状态变化，只改这里；[plan.md](plan.md) 只描述方向，不记进度。

状态图例：`⬜ 未开始` · `🟨 进行中` · `✅ 已验收` · `🟡 近似`（行为有意简化，差异写在备注） · `⛔ 不做`

验收方式：`fixture:<id>` 表示 `fixtures/expected/<id>.json` 有断言用例；`手工`表示只能人眼验收，必须写清步骤。

---

## P0 · 不做就没有"酒馆"

| ID | 功能 | 状态 | 验收 | 原版参考 |
|---|---|---|---|---|
| P0-1 | 角色卡导入：PNG（`tEXt` `chara`/`ccv3`） | 🟨 | 单测：合成卡 + 原版 Seraphina（ccv3 优先）；SAF 导入链路未做 | `src/character-card-parser.js` |
| P0-2 | 角色卡导入：V2/V3 JSON、charx(zip) | 🟨 | V2/V3/V1 JSON 已通（单测）；charx 未做 | `src/charx.js`、`src/types/spec-v2.d.ts` |
| P0-3 | 角色卡导出（PNG 写回，保留其他 chunk） | ✅ | 单测：IDAT 逐字节相同 + 回读一致 + 同时写 chara/ccv3 | 同上 |
| P0-4 | 单聊生成（OpenAI 兼容 + SSE 流式） | 🟨 | 单测：SSE 增量/结束、非流式、401 错误、请求体与夹具一致、取消；**尚未连通真实 provider** | `public/scripts/openai.js` |
| P0-5 | 提示词组装（顺序 + 宏替换 + names_behavior） | 🟨 | fixture:P0-5-prompt（9 条）/ P0-5-prompt-history（12 条，多轮）逐字相等；names_behavior 仅覆盖默认值 DEFAULT；世界书注入与预算裁剪未做 | `public/script.js`、`PromptManager.js` |
| P0-6 | 消息操作：swipe / 重新生成 / 继续 / 编辑 / 删除 | ⬜ | 手工 + fixture:P0-6-swipes | `public/script.js` |
| P0-7 | 聊天持久化：JSONL 读写（metadata + 逐行消息） | ⬜ | fixture:P0-7-jsonl | `src/endpoints/chats.js` |
| P0-8 | 角色卡库（头像、标签、搜索、分组） | ⬜ | 手工 | `src/endpoints/characters.js` |

## P1 · 决定"像不像一个真酒馆"

| ID | 功能 | 状态 | 验收 | 原版参考 |
|---|---|---|---|---|
| P1-1 | 世界书基础：关键字、常数、插入位置/深度、概率、扫描深度 | ⬜ | fixture:P1-1-prompt-lorebook（已录，实现未写） | `public/scripts/world-info.js` |
| P1-2 | 世界书进阶：secondary keys + 4 种 selectiveLogic | ⬜ | fixture:P1-2-logic | 同上 |
| P1-3 | 世界书递归扫描 / group scoring / sticky / cooldown / delay | ⬜ | fixture:P1-3-recursion | 同上 |
| P1-4 | 世界书 token 预算与裁剪顺序 | ⬜ | fixture:P1-4-budget | 同上 |
| P1-5 | 预设（采样参数子集）与 prompt_order 编辑 | ⬜ | fixture:P1-5-preset | `default/content/presets/openai/Default.json` |
| P1-6 | Token 计数（cl100k / o200k）与上下文裁剪 | ⬜ | fixture:P1-6-tokens | `src/tokenizers` |
| P1-7 | 作者注（深度注入、role、间隔、参与扫描） | ⬜ | fixture:P1-7-an | `public/scripts/authors-note.js` |
| P1-8 | 人设 / 用户名 / 名称宏 | ⬜ | fixture:P1-8-persona | `public/scripts/personas.js` |
| P1-9 | 聊天导入导出（含群聊），与 PC 版互转 | ⬜ | fixture:P1-9-io | `src/endpoints/chats.js` |

## P2 · 加分项

| ID | 功能 | 状态 | 验收 |
|---|---|---|---|
| P2-1 | 群聊（natural / list / manual / pool 激活策略） | ⬜ | fixture:P2-1-group |
| P2-2 | 后台生成 + 通知（前台服务，Android 14 限制） | ⬜ | 手工 |
| P2-3 | 系统 TTS 朗读 / 语音输入 | ⬜ | 手工 |
| P2-4 | 分享菜单收卡、图片长按保存、桌面小组件 | ⬜ | 手工 |
| P2-5 | 本地离线模型（llama.cpp JNI，4B 4bit） | ⬜ | 手工 |

## P3 · 明确不做

| 功能 | 原因 |
|---|---|
| 浏览器扩展体系 / Web UI 主题 CSS | 与原生 UI 不兼容，无对应物 |
| STscript 全量 | 生态过重；只做 `{{macro}}` 与少量 `/command` 子集 |
| 向量记忆 / Data Bank RAG | 后置；本地 ONNX 与调 API 二选一，等 P1 稳定再定 |
| 图片生成（ComfyUI / NovelAI / SD WebUI） | 后置，非核心体验 |
| 多用户 / server plugin / Horde | 单机单用户，架构上不存在 |
| 原生 SDK 接 Claude/Gemini | 先走 OpenAI 兼容层；表现不足再补原生适配 |

---

## 已核实的原版硬数据（改动前先复核，可能随版本变）

- 版本 1.19.1，`engines.node >= 20`。
- 默认 `prompt_order`：`main → worldInfoBefore → charDescription → charPersonality → scenario → (enhanceDefinitions 关) → nsfw → worldInfoAfter → dialogueExamples → chatHistory → jailbreak`。
- 预设顶层 62 键，含 `names_behavior / squash_system_messages / assistant_prefill / continue_prefill / continue_postfix / wi_format / scenario_format / personality_format / openai_max_context / openai_max_tokens`。
- 世界书条目字段：`uid, key[], keysecondary[], comment, content, constant, selective, vectorized, selectiveLogic, order, position, disable, addMemo, excludeRecursion, delayUntilRecursion, displayIndex, probability, useProbability, outletName, group, groupOverride, groupWeight, scanDepth, caseSensitive, matchWholeWords, useGroupScoring, automationId, role, sticky, cooldown, delay, triggers[], ignoreBudget`。
- `selectiveLogic`：`AND_ANY / NOT_ALL / NOT_ANY / AND_ALL`。
- 聊天 JSONL：首行 metadata，之后逐行 `{name, is_user, is_system, send_date, mes, extra}`。
