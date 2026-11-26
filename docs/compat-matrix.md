# 兼容矩阵

**本文件是进度的唯一真相。** 任何兼容行为的状态变化，只改这里；[plan.md](plan.md) 只描述方向，不记进度。

状态图例：`⬜ 未开始` · `🟨 进行中` · `✅ 已验收` · `🟡 近似`（行为有意简化，差异写在备注） · `⛔ 不做`

验收方式：`fixture:<id>` 表示 `fixtures/expected/<id>.json` 有断言用例；`手工`表示只能人眼验收，必须写清步骤。

---

## P0 · 不做就没有"酒馆"

| ID | 功能 | 状态 | 验收 | 原版参考 |
|---|---|---|---|---|
| P0-1 | 角色卡导入：PNG（`tEXt` `chara`/`ccv3`） | 🟨 | 单测 + 真机（assets 里的卡）；SAF 导入与卡列表已实现，**待真机验收**（[步骤](dev/device-verification.md)） | `src/character-card-parser.js` |
| P0-2 | 角色卡导入：V2/V3 JSON、charx(zip) | 🟨 | V2/V3/V1 JSON 已通（单测）；charx 未做 | `src/charx.js`、`src/types/spec-v2.d.ts` |
| P0-3 | 角色卡导出（PNG 写回，保留其他 chunk） | ✅ | 单测：IDAT 逐字节相同 + 回读一致 + 同时写 chara/ccv3；App 侧**卡编辑**已接（详情 ⋮ →「编辑卡片」，改名字/简介/人格/场景/开场白/示例对话；保存三选**新建 / 覆盖 / 取消**，覆盖写前留 `.bak.png`、临时文件原子替换，新建走 `saveCardAs`），**待真机** | 同上 |
| P0-4 | 单聊生成（OpenAI 兼容 + SSE 流式） | ✅ | 单测 + [真机验证](dev/device-verification.md)：真连 provider，回复为卡内角色设定 | `public/scripts/openai.js` |
| P0-5 | 提示词组装（顺序 + 宏替换 + names_behavior） | 🟨 | fixture:P0-5-prompt（9 条）/ P0-5-prompt-history（12 条，多轮）逐字相等；**卡级 `system_prompt` / `post_history_instructions` 顶替 main / jailbreak**（fixture:P0-5-sysprompt，顺序与原版一致）；names_behavior 仅覆盖默认值 DEFAULT | `public/script.js`、`PromptManager.js` |
| P0-6 | 消息操作：swipe / 重新生成 / 继续 / 编辑 / 删除 / 撤回 | 🟨 | 动作入口是气泡底下的**按钮行**（用户 2026-11-25 第 11 条：长按改为选字）：助手＝复制/编辑/[重新生成]/[继续]/删除，用户＝撤回/复制/编辑/删除。「撤回」= `rewindToMessage`：删这条及之后、正文放回输入框（rewind 语义）。重新生成 = 追加新的 swipe 备选（旧的可翻回）；继续 = 请求末尾加原版那条 system 提示（夹具 `P0-6-continue` 只卡请求），但**结果落成一条新助手消息**（用户 2026-11-26 定的形态差异：原版是 `mes +=` 拼回同一条；导出回 PC 版会看到两条连续 assistant，ST 可正常读）。`swipe` 翻页只对最后一条（原版 `isMessageSwipeable`），备选存 `message_swipes`（DB v3）。**待真机** | `public/script.js` |
| P0-7 | 聊天持久化：JSONL 读写（metadata + 逐行消息） | 🟨 | fixture:P0-7-jsonl（原版录制的真实聊天：逐字节往返 + swipes + 坏行容错）；App 内运行时持久化走 SQLite；**导出已做**（聊天页 ⋮ →「导出聊天记录」，SAF 选位置，最后一条助手消息带 swipe 备选，单测覆盖"导出后还能换一个"）；**导入已做**（角色详情 ⋮ →「导入聊天记录」，.jsonl 解析后单事务写库，日期原样保留、swipe 备选一起进） | `src/endpoints/chats.js` |
| P0-8 | 角色卡库（头像、标签、搜索、分组） | 🟨 | 两列网格 + 角色详情页 + **搜索**（名字/简介/标签）+ **标签筛选**（一排胶囊，标签存在卡列表缓存里，不再解 PNG）已做，**待真机**。~~分组（原版的文件夹）~~ → **2026-11-27 核实：原版 1.19.1 没有角色文件夹**（`src/endpoints/characters.js` 里只有按角色名建素材/聊天目录），这一项作废；卡片归类靠标签 | `src/endpoints/characters.js` |
| P0-9 | 开场白：新建聊天时插入 `first_mes` | 🟨 | fixture:P0-8-first-mes（原版 chat 数组 + 同一时刻的请求体交叉断言）；App 侧插入已实现，**待真机** | `public/script.js`（`createNewChat`/`selectCharacterById`） |

## M1 · 本地可用性（不是原版兼容项，但"能用"必须靠它）

| ID | 功能 | 状态 | 验收 | 备注 |
|---|---|---|---|---|
| M1-1 | 从手机导入 PNG 卡 + 卡列表 + 记住上次选择 | 🟨 | 手工：设置 → 导入 PNG 角色卡 → 选一张 → 能聊；杀进程重开仍记得 | 非角色卡会被拒绝并说明原因 |
| M1-2 | 会话与消息持久化（SQLite，ADR 0003） | 🟨 | 手工：聊三轮 → 杀进程 → 重开，记录还在 | 流式半截只在内存，完成/停止才落库 |
| M1-3 | 会话列表：新建 / 切换 / 删除 | 🟨 | 手工：新建两个会话各说一句，来回切换内容不串；删掉当前会话落到下一个；**删到 0 个也要能删**（不再自动补），并弹确认 | 删会话连带删消息（外键级联） |
| M1-5 | 角色详情页（四标签）+ 删卡（⋮ 菜单，连带删会话） | 🟨 | 手工：点卡进详情 → 简介/开场白/会话/配置四标签能切；开新会话要插入开场白；⋮ 里删卡后网格里消失、会话也没了 | 改卡字段（写回 PNG）是下一批 |
| M1-6 | 卡级配置：内嵌世界书条目勾选 + 卡级消息样式 | ✅ | 手工（2026-11-23 真机）：关掉 `power` 那条 → 再发一句，「请求」里那一段世界书消失；卡里关着的条目可强制开启（开关三态） | 勾选已经**真的影响提示词**（P1 引擎接上）；ST 把 `enabled` 写在卡文件里，我们暂不写回卡（覆盖只记在本机，按条目 uid 存） |
| M1-7 | 设置分层（一级列表 + 二级页）与主题系统（四色板 × 深浅） | 🟨 | 手工：设置各行右侧显示当前值；主题切换即时生效；色点顺序紫在最左 | 墨紫为默认；老设备一次性从森林绿迁移 |
| M1-8 | 应用内日志 + 崩溃兜底 + 导出 | 🟨 | 手工：设置 → 关于 → 导出日志，得到 txt；内容含发送/完成/失败与崩溃栈，不含 Key 与正文 | 内存 800 条 + 文件 1MB 滚动；进程被系统杀掉时抓不到 |
| M1-9 | 二级页面转场（push/pop）与自绘底栏（56dp） | ✅ | 手工：进二级页整屏从右滑入；返回时上一页从左侧滑回；底栏 56dp 无胶囊（2026-11-22 用户验收） | 踩了两个坑：①尺寸动画必须 `fillMaxSize()+using(null)` 关掉 SizeTransform（否则换页是"内容从 0 长出来"）②`AnimatedContent` 里永远是 target 在上层，`targetContentZIndex` 改不动，所以位移只能做在 enter 上 ③2026-11-25 补：新页**首帧**（组合+测量+录图层）会吃掉动画前几帧，改在起动画前先预热（真机 `帧 26/最长帧 83ms` → `帧 35/最长帧 8–16ms`） |
| M1-10 | 模型档案（多套连接配置：baseUrl / 模型 / 思考强度 / 自定义头 / 每档案独立 Key） | 🟨 | 手工：建两个档案来回切，各发一句都能通；杀进程后 Key 都在；老版本升级上来 Key 没丢 | 数据层 + 界面都完成（`model_profiles` 表、v1→v2 只增表、Key 按档案 id 存、三级编辑页草稿式保存）；**待真机验收** |
| M1-4 | API Key 加密持久化（Keystore AES-GCM） | 🟨 | 手工：填 Key → 杀进程重开 → 不用重填就能发；Key 空时发消息应提示"先填"，不是发出去吃 401 | 明文不落盘；解不开时当没存过 |

## P1 · 决定"像不像一个真酒馆"

| ID | 功能 | 状态 | 验收 | 原版参考 |
|---|---|---|---|---|
| P1-1 | 世界书基础：关键字、常数、插入位置/深度、概率、扫描深度 | 🟨 | fixture:P1-1-prompt-lorebook 逐字通过；**真机已验证**（2026-11-23：注入、勾选关闭后消失、关键字随聊天变化）。`@@` 装饰器未解析。条目开关是三态（跟随文件 / 本机关掉 / **本机强制开启**），强制开启只在本机生效、不写回卡或书文件 | `public/scripts/world-info.js` |
| P1-2 | 世界书进阶：secondary keys + 4 种 selectiveLogic | ✅ | fixture:P1-2-logic（四种逻辑一次录全：AND_ANY/NOT_ALL/NOT_ANY/AND_ALL 各一条，命中与不命中都在同一条请求里） | 同上 |
| P1-3 | 世界书递归扫描 / group scoring / sticky / cooldown / delay | 🟨 | fixture:P1-3-recursion / P1-3-groups / P1-3-sticky / P1-3-cooldown / P1-3-delay / P1-3-delay-passed 全通过；sticky / cooldown 计时状态**已落库**（按会话存，`LorebookScanStateCodec` 编解码 + 单测，对应原版 `chat_metadata.timedWorldInfo`）；`@@` 装饰器已做（`@@activate` / `@@dont_activate`，fixture:P1-3-decorators；未知装饰器照原版剥掉、`@@@` 转义照原版跳过）。**outlet 已注入**（`position = outlet` 的条目被 `{{outlet::名字}}` 宏取走，不进系统提示；夹具 `P1-3-outlet`）；**示例对话顶部/底部已注入**（`position = EMTop / EMBottom` 的条目并进示例块，每块自带 `[Example Chat]`；夹具 `P1-3-example-injection`）。**作者注顶部/底部已注入**（`position = ANTop / ANBottom` 的条目拼进作者注那一条，只削首尾各一个换行；夹具 `P1-7-an-with-wi`）。作者注自己的**位置**也做了三档：`IN_PROMPT`（紧跟主提示词，夹具 `P1-7-an-pos-0`）、`IN_CHAT`（按深度插历史，默认）、`BEFORE_PROMPT`（主提示词之前，夹具 `P1-7-an-pos-2`）。仍不做的：人设描述的 `TOP_AN` / `BOTTOM_AN` 位置 —— 那是「人设描述跟作者注拼在一起」的另一条轴 | 同上 |
| P1-4 | 世界书 token 预算与裁剪顺序 | 🟨 | fixture:P1-4-budget（5% 预算，6 条等长条目只进 3 条）。预算用的是"当一条消息算"的 token（裸计数 + 6），见 P1-6 | 同上 |
| P1-5 | 预设（采样参数子集）与 prompt_order 编辑 | ✅ | 解析 + 顺序 + 界面都做完：`fixture:P1-5-prompt-order`（原版用改过的顺序表实发）逐条相等；`PresetParser` 对 vendor `Default.json` 反向核对；设置→预设：导入/选择/删除，长按→编辑进二级页做顺序开关 + **长按拖动换位**（松手落盘）、恢复原顺序；拼提示词带 order/prompts，请求带 top_p 与两个 penalty。**不做**：逐角色顺序（只做全局那条）、提示词正文编辑（手机上改长文本不如在 PC 版改）。`forbid_overrides`（预设里每条提示词上的旗标：打了就**不许**被卡里的 system_prompt / post_history_instructions 顶掉）**已做**，夹具 `P0-5-forbid-overrides` | `default/content/presets/openai/Default.json`、`public/scripts/PromptManager.js` |
| P1-6 | Token 计数（cl100k / o200k）与上下文裁剪 | 🟨 | fixture:P1-6-tokens（原版页面里调 `getTokenCountAsync()` 录的 10 条计数，逐条相等）。Claude/Llama 等 sentencepiece 词表未接，回落 cl100k；上下文裁剪未做 | `public/scripts/tokenizers.js`、`src/endpoints/tokenizers.js` |
| P1-7 | 作者注（深度注入、role、间隔） | 🟨 | fixture:P1-7-an-prompt / P1-7-an-depth-2（2026-11-27 录）。规则：**插进聊天历史**而不是系统提示固定一段；深度 = 「它后面正好留几条消息」（块长 − 深度，最少退到 `[Start a new Chat]` 之后）；`interval = 1` 是原版特判（每轮都插，连一句用户消息都没有也插），否则 `用户消息数 % interval == 0` 才插；role 三选一。界面在会话配置 →「作者注」。**未做**：`IN_PROMPT` / `BEFORE_PROMPT` 两种位置、参与世界书扫描（`allowWIScan`）、按角色的 AN（character notes） | `public/scripts/authors-note.js` |
| P1-8 | 人设 / 用户名 / 名称宏 | 🟨 | fixture:P1-8-persona-prompt（2026-11-27 录：人设描述是**一条独立的 system 消息**，位置由 `prompt_order` 的 `personaDescription` 决定 —— 默认预设里在 `worldInfoBefore` 之后、`charDescription` 之前；`{{user}}` 展开成人设名）。多人设 + 三级（会话 > 卡 > 全局默认），设置 → 人设；描述为空时那一段不发。**未做**：人设头像、按角色锁定人设（原版 `locked.character`） | `public/scripts/personas.js`、`openai.js` |
| P1-9 | 聊天导入导出（含群聊），与 PC 版互转 | ⬜ | fixture:P1-9-io | `src/endpoints/chats.js` |
| P1-12 | 消息显示格式：Markdown 强调 + 包裹标签清洗 | ✅ | **只影响显示**（存档/导出/请求/提示词都是原文）。两组**只读内置正则**（设置→正则「内置」，可关，关掉=原文）：① 白名单包裹标签（`</content>` / `<output>` / `<response>` / `<message>` / `<text>` / `<answer>`）；② 强调：`**粗**` / `*斜*` / `_斜_`。强调语义用 fixture:P1-12-inline-markup 对齐（同一文本先过原版 `showdown` + `showdown-underscore` 渲染，再比 `<em>`/`<strong>`/`<br>` 的位置）。渲染只认 `<i> <em> <b> <strong> <br>`，其他标签原样显示；不渲染列表/标题等完整 Markdown | `public/script.js`（messageFormatting）、`public/scripts/showdown-underscore.js` |
| P1-11 | 正则脚本（脚本数组 + placement + 捕获组替换） | 🟨 | fixture:P1-11-regex（7 条输入输出直接抄自原版 `getRegexedString()`）：全局/首次替换、`$1`/`$<name>`/`{{match}}`、trimStrings、disabled、promptOnly/markdownOnly、AI_OUTPUT。**不做的**：脚本编辑 UI（只在 PC 版写）、preset/scoped 脚本、`runOnEdit` 的编辑路径 | `public/scripts/extensions/regex/engine.js` |

## P2 · 加分项

| ID | 功能 | 状态 | 验收 |
|---|---|---|---|
| P2-1 | 群聊（natural / list / manual / pool 激活策略） | ⬜ | **夹具已录**（2026-11-27：`P2-1-group-natural` / `-list` / `-manual` / `-pooled`，**四种激活策略各一份**（四份请求形状一致，只有说话人不同），形状见 original-behavior「群聊」）；**代码未动** —— 数据模型（一条会话多张卡）、历史里的 `名字: ` 前缀、末尾 `[Write the next reply only as X.]` 都还没有 |
| P2-2 | 后台生成 + 通知（前台服务，Android 14 限制） | ⬜ | 手工 |
| P2-3 | 系统 TTS 朗读 / 语音输入 | 🟨 | 朗读已做：气泡底下动作行第一颗「朗读」（再点一次停），走系统 `TextToSpeech`，不引引擎、不联网、不要权限；离场即 `shutdown()`。**未做**：语音输入（要 `RECORD_AUDIO` + 权限流程，必须真机验）、朗读音色/语速设置 | 手工 |
| P2-4 | 分享菜单收卡、图片长按保存、桌面小组件 | 🟨 | **收卡已做**：`ACTION_SEND`（image/* + zip）→ `SharedIntake` 一次性交接 → 走正常的导入链路（导完进角色详情）。冷启动与 `onNewIntent` 两条路都接。**未做**：图片长按保存、桌面小组件 | 手工 |
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
