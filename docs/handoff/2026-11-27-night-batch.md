# 接手卡 · 2026-11-27 凌晨批量（揭示节奏 + 人设 + 作者注 + 胶囊菜单 + TTS/分享）

> 给下一次会话 / 换人接手用。**一次交接一份，本页是最新那份**；上一份（揭示节奏方案定稿）
> 在 [2026-11-27-reveal-pacing.md](2026-11-27-reveal-pacing.md)，方案细节仍以它和
> [plan-reveal-pacing.md](../plan-reveal-pacing.md) 为准，本页只讲"今晚之后的状态"。
> 现状 → [status.md](../dev/status.md)｜规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)

## 30 秒

- **代码**：`git log -1`；本轮功能提交最高到胶囊菜单，回退点 tag `apk-m212`（揭示节奏）、
  `apk-m213`（人设）、`apk-m214`（作者注）、`apk-m215`（胶囊菜单）。
- **最重要的一句**：这一轮**没有真机回执**。用户明确说"没有真人测试环节"，所以以下全部只过了
  CI 编译 + 静态检查 + 单测，**观感没人看过**。
- 做完：揭示节奏（推进数学已抽进 `core/model/RevealPacer` 并有 7 条单测）、人设（P1-8）、
  作者注（P1-7）、`forbid_overrides`、世界书 outlet + 示例对话顶部/底部注入、胶囊菜单
  （需求批 E 的两处原型）、TTS 朗读 + 语音输入（P2-3）、分享收卡 + 保存卡图（P2-4）、
  开源许可页、core 署名、P4 的 snap 一笔、INV-11 结构检查。
- 没做且**有明确理由**的见 [status.md](../dev/status.md)「还没做」：群聊（要群聊夹具 + 大改）、
  后台生成+通知（前台服务 + 权限，盲上会崩）、桌面小组件、作者注 TOP_AN/BOTTOM_AN、
  `data` 层测试（要先抽纯编解码）。

## 一、下一轮怎么开工（按顺序）

1. **先要真机回执**（照 [M29 验证清单](../dev/verification/2026-11-27-M29.md) 走，24 条，
   风险和判据都写好了）。最需要眼睛的四件：慢档（15cp/s）的逐字观感、『打印帧』日志里的实际吞吐、
   胶囊菜单的落点与磨砂、TTS 朗读。回执不符预期就按 [plan-reveal-pacing.md](../plan-reveal-pacing.md) §6 的 19 条逐项对。
2. **群聊（P2-1）**：**夹具和提示词组装都完成了**（六份夹具 + `GroupContext`，见
   [plan-group-chat.md](../plan-group-chat.md)）。下一步是数据模型（`ChatSession.groupMembers`、
   消息 `speaker`、DB v5）、应用层接线、界面 —— **这三块要动用户的数据库，没有真机与回滚路径
   之前不要动**（这条是 2026-11-27 05:xx 定的）。录制群聊夹具的方法见 [fixtures.md §4.6](../dev/fixtures.md)
   （`getCharacters()` 才会装 groups、群行在右侧抽屉里、Welcome 弹窗要 close 掉）。
3. **后台生成+通知（P2-2）**：等有真机再动。前台服务 + `POST_NOTIFICATIONS` 运行时权限 +
   `foregroundServiceType`，配错就是启动即崩，盲上是拿用户的手机做实验。
4. 其余按 [status.md](../dev/status.md) 的清单；`forbid_overrides` 与 outlet 都要**新夹具**
   （不能改现有 P0-5 那张卡，否则整套断言要重录）。

## 二、这一轮新立/新确认的口径（别再重新讨论）

- **揭示节奏**：见方案文档。摘要：游标 + 布局即真相、零追高零桥接；档位 15/35/70/不限速（默认 35）；
  不设时间上限；落库后继续打印；点正在打印的气泡 = 补满；复制永远是全文。
- **人设**：名字 = `{{user}}`，描述进 `prompt_order` 的 `personaDescription` 那一格（**独立 system 消息**）；
  三级：会话 > 卡 > 全局默认；描述为空那一段就不发。
- **作者注**：插进**聊天历史**（不是系统提示固定一段）；深度 = 「后面留几条」；`interval = 1` 是原版特判
  （每轮都插）；正文清空 = 这一层没有。
- **默认顺序表用 100001**（原版聊天补全那张）—— 100000 是文本补全那张，没有 `personaDescription`。
- **胶囊菜单**：`DropdownMenu` 采不到内容层，永远别回退；宿主只放骨架（录图层之外）。
- **卡库分组作废**：原版 1.19.1 没有角色文件夹。

## 三、这一轮踩的坑（都是"本机编不了"放大的）

1. **`str.replace` 的锚点会撞车**：`Regex("正则"), Preset("预设"),` 在两个枚举里都出现，replace 只命中
   前一处 → `ConfigKind` 多一个重复项、`SettingsPage` 反而没加，连锁 8 个 ambiguity。改枚举时**先
   看清锚点是否唯一**，或者用 `count(old)==1` 断言后**逐个文件**改。
2. **缺 import 只能 CI 抓**：本轮 4 次 CI 失败里 3 次是缺 import（`Surface`、`Persona`、`AuthorNote`、
   `LocalContext`、`boundsInWindow`）。**新写一个 Composable 就顺手扫一遍用到的符号**：
   `Dialog / DialogProperties / Surface / systemBarsPadding / SelectionContainer / AnimatedVisibility…`
3. **KDoc 与 `@Composable` 会被"插到前一个函数头上"**：`SpeakGlyph` 插在 `CopyGlyph` 之前，把它的
   KDoc+注解挤成"两个 `@Composable`"→ `This annotation is not repeatable`。往文件里插函数时**带上
   自己的 KDoc 并检查前后各一行的边界**。
4. **shell 里 `git commit -m` 带反引号/引号会炸**：本轮第一次提交直接报 `pathspec '→' did not match`。
   **一律写消息文件再 `git commit -F`**。
5. **夹具录制要按原版自己的入口**：作者注第一次直接塞 `extension_prompts['2_floating_prompt']`
   一条都没注入 —— 原版每轮由 `setFloatingPrompt()` 按 `chat_metadata` 重算。录之前先读那一段源码。
6. **`--seed` 参数是文件路径**（`{"userTurns": [...]}`），不是逗号分隔的字符串。

## 四、出包循环（和上一份一样，别改）

```bash
git commit -F <消息文件> && git push
bash tools/poll-ci.sh <sha>          # 约 2 分钟；失败日志在 release ci-log-android 的 build-<sha7>.log
git tag apk-mNNN <sha> && git push origin apk-mNNN
```

提交前本机必跑：

```bash
bash tools/check-core-purity.sh && bash tools/check-kotlin-comments.sh \
  && bash tools/check-kotlin-imports.sh && bash tools/check-ui-map.sh \
  && bash tools/check-ui-invariants.sh && bash tools/test-core.sh   # 10 模块 141 条
```

## 五、建议加载的 skills

| skill | 为什么 |
|---|---|
| `verification-before-completion` | 本项目"完成"必须带本轮新鲜证据（check 输出、CI 绿、BUILD_ID） |
| `diagnosing-bugs` | 真机问题全靠日志（`tag=chat` / `scroll` / `rubber` / `tts` / `card`） |
| `handoff` | 下次交接照它写一页 |
