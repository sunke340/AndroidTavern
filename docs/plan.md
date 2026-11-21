# 原生安卓酒馆（AndroidTavern）规划

对标对象：SillyTavern **1.19.1**（本地已 clone 到 `vendor/SillyTavern`，见文末附录的实测数据）。

一句话定位：**把 SillyTavern 的"文件格式 + 提示词行为"搬到手机上，用 Kotlin/Compose 重写 UI 与运行时，做到「卡能带过来、聊能带回去」。**

---

## 0. 运行时形态：单进程，没有前后端

**不做 Express 式的服务端，不做 WebView 套壳，App 内不跑 localhost 服务。** 原版分前后端的每一条理由（浏览器无文件系统、CORS、密钥不能进浏览器、多用户多标签、长任务）在安卓进程里要么不成立、要么用平台原生能力做得更好。详见 [adr/0002-single-process.md](adr/0002-single-process.md)。

Node 只在**开发期**出现：跑原版 ST 录 golden fixture（§5），不进产品。

---

## 1. 先定"兼容"这个词的含义

"兼容原版酒馆"有三种做法，代价差一个数量级，必须先选：

| 方案 | 做法 | 成本 | 结论 |
|---|---|---|---|
| **A. 格式兼容 + 行为复刻** | 原生重写 UI/运行时；读写 ST 的卡/聊天/世界书/预设格式；提示词组装用原版做基准测试 | 高（8–12 人月走到 80%） | ✅ **推荐** |
| B. WebView 套壳本地 ST | 手机内跑 Node（nodejs-mobile/Termux）+ WebView 显示原版 UI | 低（1–2 周） | 只当过渡：能立刻用，但违反"原生"，且后台/通知/输入法体验差 |
| C. 原生 UI + 复用 ST 的 JS 逻辑 | QuickJS 引擎跑 prompt builder / world-info | 中 | ❌ 桥接成本比重写还高，调试痛苦 |

选 A。但要把"兼容"落成两条**可验收**的硬标准，否则会变成无底洞：

1. **往返可迁移**：PC 版 ST 里能用的卡/世界书/聊天，导入本 App 后行为一致；本 App 导出的东西，PC 版 ST 能直接打开继续聊。
2. **提示词等价**：同样的角色卡 + 预设 + 聊天记录，本 App 发给 API 的 `messages[]` 与 ST 1.19.1 发出的**逐条一致**（用 golden fixture 自动比对，见 §5）。

其余一切（UI 长什么样、设置项叫什么叫）都属于自由发挥，不用兼容。

---

## 2. 兼容矩阵（按用户实际会用的顺序排优先级）

ST 1.19.1 有 82 个前端模块、46 个 server endpoint，全量复刻不现实。按"日常真的在用"切：

### P0 — 不做就没有"酒馆"这回事
| 功能 | 关键数据/行为 | 工作量 |
|---|---|---|
| 角色卡导入（PNG/JSON/charx） | PNG `tEXt` 块 `chara`（base64 JSON），V2 `data{}`；V3 走 `ccv3`；charx = zip | 3–5d |
| 单聊生成 | 系统提示词 + 定义 + 历史 → `/chat/completions` 流式 | 5d |
| 提示词组装 | `main / worldInfoBefore / charDescription / charPersonality / scenario / worldInfoAfter / dialogueExamples / chatHistory / jailbreak` 顺序 + 宏替换 | 8d（最容易被低估） |
| 消息操作 | swipe（多候选）、重新生成、继续、编辑、删除、分支 | 5d |
| 聊天持久化 | JSONL：首行 metadata，逐行 `{name,is_user,is_system,send_date,mes,extra}` | 3d |
| OpenAI 兼容 API | 自定义 base URL + key、流式 SSE、停止串、参数（temp/top_p/top_k/min_p/penalties/max_tokens/seed） | 4d |
| 角色卡库 | 头像、标签、搜索、分组 | 4d |

### P1 — 决定"像不像一个真酒馆"
| 功能 | 说明 | 工作量 |
|---|---|---|
| 世界书（Lorebook） | 关键字触发、secondary keys + 4 种 selectiveLogic、常数条目、插入位置/深度、概率、扫描深度、递归扫描、group scoring、sticky/cooldown/delay、token 预算。ST 里 `world-info.js` 约 6000 行，是纯逻辑硬骨头 | 10–15d |
| 预设与提示词管理器 | ST 的 62 键预设（`openai/Default.json`）子集；prompt_order 可编辑 | 6d |
| Token 计数与上下文裁剪 | cl100k/o200k 用 jtokkit；按预算裁历史（保留 system + 最近 N 条 + 世界书配额） | 4d |
| 作者注（Author's Note） | 深度注入、role、间隔、是否参与世界书扫描 | 2d |
| 人设（Persona）/ 用户名 | 名称替换宏、头像 | 2d |
| 聊天导入导出 | 与 PC 版互转（含群聊） | 3d |

### P2 — 加分项，手机上反而能做更好
- 群聊（多角色，`natural / list / manual / pool` 激活策略）：8d
- 后台生成 + 通知（ForegroundService，Android 14 限制要处理）：4d
- 系统 TTS 朗读 + 语音输入：3d
- 分享菜单收卡、图片长按保存、桌面小组件：3d
- 本地离线模型（llama.cpp JNI，Qwen3/Gemma 3 4B 4bit）：10d+（可选，是差异化卖点）

### P3 — 明确"先不做"（在 README 里写死，避免被追着要）
- 浏览器扩展体系 / Web UI 主题 CSS
- STscript 全量（先做 `{{macro}}` + 少量 `/command` 子集）
- 正则脚本（`regex` 扩展）：可做，但要排在 P1 之后
- 向量记忆 / Data Bank RAG（本地 ONNX embedding 或调 API 二选一）
- 图片生成（ComfyUI / NovelAI / SD WebUI）
- 多用户、server plugin、Horde
- 非 OpenAI 协议的厂商原生 SDK（Claude/Gemini 走 OpenAI 兼容层先顶上，后面再补原生）

---

## 3. 工程结构

```
:app                     Compose + Navigation + 依赖装配
:feature:chat            消息列表、流式渲染、swipe、输入
:feature:character       卡库、卡编辑器、导入向导
:feature:lorebook        世界书编辑器
:feature:preset          预设 / 提示词管理器 / 采样参数
:core:card-format        PNG chunk 读写、charx、V2/V3 映射
:core:chat-format        JSONL 编解码（流式，不整文件读进内存）
:core:prompt             PromptBuilder —— 兼容性的心脏
:core:lorebook           激活算法（纯 Kotlin，无 Android 依赖，可 JVM 单测）
:core:tokenizer          jtokkit + 模型→编码映射
:core:ai                 provider 适配、SSE、重试、代理
:data                    Room（消息/卡/世界书）+ DataStore（设置）+ SAF
```

原则：`core:*` 全部**纯 JVM**（不 import android.*），这样 90% 的兼容性逻辑能在这台 1.7GB 的机器上跑单测，不需要真机。

技术栈：Kotlin 2.x、Compose Material3、Room、DataStore、kotlinx.serialization、OkHttp(+SSE)、Coil、WorkManager、Hilt（或手写 DI，省编译时间）、jtokkit、EncryptedSharedPreferences 存 key。

**存储决策**：DB 为准（Room），JSONL 只在导入/导出时生成。不要学 ST 直接拿 JSONL 当数据库——手机上频繁追加会掉电、掉性能。

---

## 4. 里程碑（每个里程碑都以"能在真机上演示"收尾）

| 阶段 | 内容 | 周期（单人全职） | 验收 |
|---|---|---|---|
| **M0** | 工程骨架、CI 出包、真机安装通路、日志回传 | 3–5d | 空壳 App 装到手机上，点按钮出一个 Toast |
| **M1** | 单卡单聊：导卡 → 拼提示词 → 流式生成 → 落库 | 2–3w | 用一张真实 V2 卡与 OpenAI 兼容 API 对话，导出 JSONL 后 PC 版 ST 能继续 |
| **M2** | 世界书 + 预设 + token 预算 | 2–3w | 带 50 条世界书的卡行为与 PC 版一致 |
| **M3** | swipe/编辑/分支 + 群聊 + 全格式互转 | 2w | 卡、聊天、世界书三向往返无损 |
| **M4** | 后台生成、通知、TTS、分享接收 | 1–2w | 锁屏也能出下一句，通知里能直接回 |
| **M5** | 正则脚本、STscript 子集、向量记忆、本地模型 | 长期 | 按需 |

M1 结束就已经是"能用的私人酒馆"，之后每一步都是加法。

---

## 5. 兼容性怎么保证（这是整个项目最该先建的东西）

在写第一行业务代码之前，先建 **golden fixture 流水线**：

1. 本机跑原版 ST（Node 24 已具备，`vendor/SillyTavern` 就是）：
   ```bash
   cd vendor/SillyTavern && npm i --no-audit && node server.js
   ```
2. 把 ST 的 OpenAI 后端指向一个**本地假 API**（几行 Node）记录 `POST /chat/completions` 的完整 body；
3. 用同一套卡/世界书/预设/聊天，走一遍 ST，把请求体存成 `fixtures/*.json`；
4. Kotlin 侧的 `PromptBuilder` 单测直接断言"我拼出来的 messages 等于 fixture"。

产出：每加一个兼容特性，就多一个 fixture。**"兼容原版"从形容词变成一个 CI 绿灯。**

顺带解决另一个坑：裁剪、宏展开、世界书递归这些行为的边界情况，人脑记不住，但 fixture 记得住。

---

## 6. 风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| 提示词组装偏差 | ST 里三处都在改 prompt（script.js 组装、PromptManager、openai.js 后处理），很容易漏 `names_behavior`、`squash_system_messages`、`assistant_prefill` | 只信 fixture，不信记忆 |
| 世界书递归/组权重 | 6000 行 JS，边界条件多 | MVP 先做「关键字 + 常数 + 插入位置/深度 + 概率」，递归/group/sticky 放 M2 后半 |
| PNG 写回 | 重编码会毁掉原图；必须继承所有 chunk 只改写 `chara`/`ccv3` | 手写 chunk 级读写，别用 Bitmap 重存 |
| 后台生成被系统杀 | Android 14 前台服务类型限制 | `dataSync` 类型 + 通知；或"生成中断可续"的语义 |
| 长上下文内存 | 手机 JS/JSON 大对象易 OOM | 流式解析 JSONL、消息分页加载 |
| API Key 安全 | 明文落盘 | Keystore + EncryptedSharedPreferences，导出时提示 |

---

## 7. 附录：从源码实测到的硬事实

- ST 版本 1.19.1，`engines.node >= 20`，仓库 99MB（`--depth 1`）。
- 角色卡 V2 字段（`src/types/spec-v2.d.ts`）：`name, description, personality, scenario, first_mes, mes_example, creator_notes, system_prompt, post_history_instructions, alternate_greetings, character_book, tags, creator, character_version, extensions`。
- 默认 prompt_order（`default/content/presets/openai/Default.json`）：
  `main → worldInfoBefore → charDescription → charPersonality → scenario → (enhanceDefinitions 关) → nsfw → worldInfoAfter → dialogueExamples → chatHistory → jailbreak`
- 预设顶层 62 个键，含 `temperature / frequency_penalty / presence_penalty / top_p / top_k / top_a / min_p / repetition_penalty / openai_max_context / openai_max_tokens / names_behavior / send_if_empty / impersonation_prompt / continue_nudge_prompt / wi_format / scenario_format / personality_format / assistant_prefill / squash_system_messages / continue_prefill / continue_postfix / seed`。
- 世界书条目字段（`public/scripts/world-info.js`）：
  `uid, key[], keysecondary[], comment, content, constant, selective, vectorized, selectiveLogic, order, position, disable, addMemo, excludeRecursion, delayUntilRecursion, displayIndex, probability, useProbability, outletName, group, groupOverride, groupWeight, scanDepth, caseSensitive, matchWholeWords, useGroupScoring, automationId, role, sticky, cooldown, delay, triggers[], ignoreBudget`。
  `selectiveLogic`：`AND_ANY / NOT_ALL / NOT_ANY / AND_ALL`。
- server 端 46 个 endpoint（`src/endpoints/`），其中 `characters / chats / worldinfo / presets / settings / secrets / tokenizers / vectors / speeches / stable-diffusion` 与我们要复刻的强相关。
