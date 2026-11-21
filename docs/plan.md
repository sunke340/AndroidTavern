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

## 2. 范围与优先级

ST 1.19.1 有 82 个前端模块、46 个 server endpoint，全量复刻不现实。按"日常真的在用"切成四档：

| 档 | 含义 | 代表 |
|---|---|---|
| **P0** | 不做就没有"酒馆"这回事 | 卡导入、单聊、提示词组装、swipe、JSONL、OpenAI 兼容 API |
| **P1** | 决定"像不像一个真酒馆" | 世界书、预设、token 预算、作者注、人设、导入导出 |
| **P2** | 加分项，手机上反而能做得更好 | 群聊、后台生成+通知、TTS、分享收卡、本地模型 |
| **P3** | 明确不做 | 浏览器扩展、主题 CSS、STscript 全量、多用户、Horde、图片生成 |

**逐项清单、状态、验收方式全部在 [compat-matrix.md](compat-matrix.md)**——它是进度的唯一真相，本文件不再重复列表（两处列表必然不同步）。

粗估：P0 ≈ 5 周，P1 ≈ 6 周，两者合起来就是"能日常用"的形态。

---

## 3. 工程结构

```
:app      Compose 入口、导航、DI 装配
:core:*   纯 JVM：card-format / chat-format / prompt / lorebook / tokenizer / preset / ai
:feature:* Compose 界面：chat / character / lorebook / preset / settings
:data     Room、DataStore、SAF、Keystore、仓库实现
```

原则：`core:*` 全部**纯 JVM**（不 import android.*），这样 90% 的兼容性逻辑能在这台 1.7GB 的机器上跑单测，不需要真机。

**模块职责、依赖方向、每个模块内部的包结构、新增模块的判据，全部在 [dev/layout.md](dev/layout.md)**，此处不重复。

技术栈：Kotlin 2.x、Compose Material3、Room、DataStore、kotlinx.serialization、OkHttp(+SSE)、Coil、WorkManager、jtokkit、EncryptedSharedPreferences；DI 先手写（省编译时间，这台机器经不起注解处理）。

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

一句话：**跑原版 ST，把它真正发给 API 的请求体录成 golden fixture，让 Kotlin 侧的拼装结果与它逐条比对。**

在写第一行业务代码之前先把这条流水线跑通。方法与 fixture 结构见 [dev/fixtures.md](dev/fixtures.md)，脚本入口 `tools/fixture/bootstrap.sh`。

产出：每加一个兼容特性就多一个 fixture。**"兼容原版"从形容词变成一个 CI 绿灯。**

顺带解决另一个坑：裁剪、宏展开、世界书递归这些边界情况，人脑记不住，但 fixture 记得住。

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
