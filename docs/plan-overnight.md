# 夜间执行计划（2026-11-22 夜 → 明早验收）

用户睡前授权：按此计划连续执行，早上起床验收。**只做清单内的，不顺手扩张。**

## 起点状态（2026-11-22 02:45）

- M0 已完成并在真机验证：[device-verification.md](dev/device-verification.md)
- `:core:{model,card-format,prompt,ai}` 共 20 个单测；`:app` 能装、能连 provider、能流式、能切卡、能看请求
- 未做：持久化、卡片导入、Key 存储、签名固定、世界书

## 三个目标（按此顺序，做不完就停）

### G1 · 基础收尾（必达，约 1h）

1. 固定 debug 签名：debug keystore 入库（用户已批准），`build.gradle.kts` 指定 `signingConfig`
2. CI 注入递增 `versionCode`（用 run number），避免覆盖安装时提示降级
3. 保存回退点：把当前可用 APK 发成 `apk-m0` release
4. release 备注写 commit sha + 构建时间

验收：手机上可直接覆盖安装，不卸载、不报签名不一致。

### G2 · 带自己的卡（第二种，价值最高）

5. SAF 导入卡片：系统文件选择器选 PNG（`ACTION_OPEN_DOCUMENT`）→ `PngCard.read` → 复制进应用私有目录
6. 卡片列表：列出内置卡 + 已导入卡，可切换；记住上次选择

验收：从手机里选一张自己的 PNG 卡，导进来能聊，回复像卡里的人。

### G3 · 记录留存（M1 地基，风险最高）

7. `:core:chat-format`：JSONL 读写（首行 `chat_metadata`，逐行消息含 `swipes`），本机可测
8. 用原版录一份带多轮 + swipe 的聊天做 fixture
9. `:data`（Room）：会话表 + 消息表；App 重启后恢复
10. 会话列表：新建 / 切换 / 删除

验收：聊三轮 → 杀进程 → 重开，记录还在。

### 顺手（有余力）

11. Key 存 EncryptedSharedPreferences
12. 停止 = 保留已收到的半截内容（现在会丢）
13. CI 冒烟检查：`aapt2 dump badging` 验证 minSdk / INTERNET / 签名

## 纪律

- `main` 必须始终可构建；每步小提交
- 涉及兼容行为先有夹具（铁律 #2）
- 每个目标完成且 CI 绿 → 打 tag `night-g1` / `night-g2` / `night-g3`
- **06:30 硬停**：保证 main 绿 + APK 可装 + 写早上测试说明
- 不碰 `vendor/`，不扩 P3

## 明早验收清单（按实际做到哪档给）

**G1**：覆盖安装（不卸载）→ 填 key → 发一句 → 关 App → 重开看 Key 还在不在

**G2**：导入自己的 PNG 卡 → 聊 → 回复是否贴合卡设

**G3**：聊三轮 → 杀进程 → 重开 → 记录/会话列表是否在

## 执行结果（2026-11-22 11:30 补记）

三档全部完成，CI 全绿（`9e5f2c7`），出包 `apk-m1`，已验签并直发 QQ。

| 目标 | 状态 | 落地 | tag |
|---|---|---|---|
| G1 固定签名 + versionCode + 回退点 | ✅ | `88c2ada`、`97e4ee5` | `night-g1` |
| G2 SAF 导入卡 + 卡列表 + 记忆选择 | ✅（待真机） | `db8da5b` | `night-g2` |
| G3 JSONL 读写 + SQLite 持久化 + 会话列表 | ✅（待真机） | `9850b7c`、`9e5f2c7` | `night-g3` |
| 顺手：停止保留半截内容 | ✅ | `db8da5b` | — |
| 顺手：Key 存 Keystore | ❌ 没做 | — | — |
| 顺手：CI aapt2 冒烟 | 🟡 做了 apksigner/aapt2 打印 + 本机 `tools/verify-apk.sh` 验签 | `88c2ada`、`9ea998f` | — |

意外收获 / 踩到的坑：

- 原版真实聊天文件被拿来当夹具后，发现一个**逐字节往返**的硬要求（重新序列化必须与原文件完全一致）；
  `ChatFile` 靠"原始 JsonObject 是真相"做到了，8 个单测里 1 个就是断言这个。
- Kotlin 块注释可嵌套：注释里写了 `cards/*.png` 把整个类体吞了，CI 报 EOF 处 "Unclosed comment"。
  加了 `tools/check-kotlin-comments.sh` 进铁律检查。
- 本机没有 apksigner，用 AGP 缓存里的 apksig 库做了 `tools/verify-apk.sh`，
  `apk-m1` 实测 `verified=true v2=true`，指纹与 `keystore/README.md` 一致。
- 夜间会话中途离线 8 小时（03:10 → 11:29），后半段没有继续排活；06:30 硬停这条没有实际生效机会。

## 交付

每完成一档：APK 直发 QQ（`c2c:<openid>`，见 `.secrets/personal.md`），并附这一档的验收要点。
若某档做不完：明确说"没做到"，给出下一档能测的范围，不假装完成。

---

# 夜间执行计划 · 2026-11-23（世界书 + 队列）

用户睡前授权（01:26）：按队列连着做，一档做完接下一档。**只做功能，不碰 UI 视觉**
（胶囊暗部圆弧留给用户白天自己提需求，已记进 [dev/status.md](dev/status.md)）。

## 起点（01:25 实测，不是估计）

| 事实 | 证据 |
|---|---|
| 代码 `f92200d` / tag `apk-m48`，CI 全绿 | 上一轮 |
| 原版 ST 1.19.1 **正在** `127.0.0.1:8000` 跑 | `curl /version` → `SillyTavern:1.19.1:Cohee#1207` |
| `vendor/SillyTavern/node_modules` 已装（533 包） | `ls node_modules \| wc -l` |
| Maven Central / Google 可达（能加新依赖） | `curl -o /dev/null -w %{http_code}` → 200 / 200 |
| 本机可跑 core 单测 + Playwright 录夹具；**不能** assembleDebug、不能模拟器 | [environment.md](environment.md) |

## 队列

### A · 世界书引擎（必达，估 3–4h）

| 步 | 做什么 | 验收 |
|---|---|---|
| A1 | 读 `vendor/SillyTavern/public/scripts/world-info.js`：`checkWorldInfo`(4709–5292)、`getSortedEntries`(4590)、`parseDecorators`(4652)、`filterGroupsByScoring`(5292)、`filterGroupsByTimedEffects`(5337)、`filterByInclusionGroups`(5388)、`convertCharacterBook`(5617)；结论写进 [original-behavior.md](dev/original-behavior.md)（带行号） | 每条结论有源码行号或夹具证据 |
| A2 | 新模块 `:core:lorebook`：`LorebookEntry`（全字段）+ `LorebookScanner`（纯函数：entries × chat × 预算 → before/after/atDepth 三组） | `tools/check-core-purity.sh` + 单测 |
| A3 | `PromptBuilder` 增加世界书入参；`worldInfoBefore` 插在 description 前、`worldInfoAfter` 在 examples 后（默认 `prompt_order`）；注入时做宏替换 | **`P1-1-prompt-lorebook` 逐字通过** |
| A4 | `tools/fixture/make-test-card.py` 支持 `character_book`（可编程造条目）；用 `--seed` 造多轮；录 `P1-2-logic` / `P1-3-recursion` / `P1-4-budget` | 三个夹具逐字通过 |

### B · 世界书接进 App（估 1.5h，让勾选真的有用）

| 步 | 做什么 |
|---|---|
| B1 | data/聊天路径：卡的 `character_book` 减掉 `SettingsRepository.disabledEntries` → 传进 `PromptBuilder`（**现在勾选只存不用**） |
| B2 | 设置→世界书：SAF 导入 ST 世界书 `.json`（`{entries:{uid:{…}}}`）→ 存 `filesDir/worldinfo/`；列表可启用/停用；条目只读 |
| B3 | 请求预览页显示 `worldInfoBefore/After` 段（排查注入用） |

验收：单测 + CI 编译 + 明早真机步骤写进 [device-verification.md](dev/device-verification.md)。

### C · token 计数 P1-6（估 1h）

- 加 `com.knuddels:jtokkit`；`Tokens.count(model, text)`，模型→编码映射照 `src/tokenizers`。
- 夹具：直接打原版 `/api/tokenizers/openai/encode` 录 `text → count`，断言一致（这就是 P1-6）。
- 接给 A4 的预算裁剪：P1-4 的"预算"用真 token 数，不用字符数糊。

### D · 正则 P1-11（估 1.5h，用户点名要的）

- 先补矩阵条目 + `docs/plan.md` 的 M5 范围（正则不在 P3 禁列，属 M5）。
- 端口 `public/scripts/extensions/regex/engine.js`（465 行）：`getRegexedString`、placement（用户输入/模型输出/world info）、`affects`、`only_format_display`。
- 接进发送路径（提示词前处理）与显示路径（渲染前处理）。
- 夹具 `P1-11-regex`：ST 里建两条脚本（一条 user input、一条 ai output）后录。

### E · 预设 P1-5（估 2h，做不完停在 core）

- 导入 ST 预设 JSON：采样参数 + `main` 提示词 + `prompt_order`。
- 新模块 `:core:preset` + 单测；`PromptBuilder` 支持自定义 `main`/顺序。
- UI（设置→采样 变预设列表）留到白天，先不碰。

### F · 有余力再做

1. P0-6 的 core 部分：swipe 栈 / 重新生成 / 删除单条（JSONL 的 `swipes` 已在 `:core:chat-format`）
2. D 批三级继承的解析器（会话 > 卡 > 全局）
3. `docs/compat-matrix.md` / `dev/status.md` / ADR 收尾

## 每档"做完"的定义

1. 代码在正确模块、`tools/check-*.sh` 三个全过、`tools/test-core.sh` 全绿
2. 兼容行为有 fixture 且单测断言它
3. 矩阵状态列已改
4. 小提交 + 推 CI 绿（不等 CI 就可以开始下一档，但**失败必须先修**）

## 纪律

- 不碰 UI 视觉；不动 `vendor/`
- 猜不出的原版行为：写进 `dev/status.md` 的"待问"，**不猜**
- **07:00 硬停**：main 绿 + 出 APK + 发 QQ + 写早间验收清单（没做完的档明确写"没做到"）
