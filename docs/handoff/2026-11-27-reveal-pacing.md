# 接手卡 · 2026-11-27 凌晨 · 流式回复揭示节奏（方案定稿，待评审 → 待实施）

> 给下一次会话 / 换人接手用。**一次交接一份，本页是最新那份**；上一份（聊天列表 P3/P4 那段）在
> [2026-11-26-chat-list-p3.md](2026-11-26-chat-list-p3.md)，**只作历史读**。
> 方案细节**不在这里**，全在 [docs/plan-reveal-pacing.md](../plan-reveal-pacing.md)（定稿，唯一口径）。
> 现状 → [status.md](../dev/status.md)｜规则 → [AGENTS.md](../../AGENTS.md) + [app/AGENTS.md](../../app/AGENTS.md)

## 30 秒

- **项目**：原生安卓「酒馆」——Kotlin + Compose 重写 SillyTavern 1.19.1，兼容文件格式与提示词行为。
- **代码基线**：`ff0d803`（只比 v180 多两个 docs 提交：方案草稿、方案定稿 + 本卡）；最新交付 **v180 = `7f3f472` / tag `apk-m211`**。
- **正在做的事**：修「不满一屏时 AI 回复逐行抬升 → 整屏抽搐」。**方案已定稿**（[plan-reveal-pacing.md](../plan-reveal-pacing.md)），
  **代码一行未动**，等第三方评审 + 用户确认交互契约后实施。
- **今晚已交付并验收**：v177 修「点发送后列表先下滑」（M27 ✅）；v178 许可批（AGPL-3.0 + 自制演示卡「青禾」）；
  v179 修「滚回视口时动作行重浮现」（✅ 用户确认）；v179/v180 两次修"抽搐"**均被驳回**。
- **不要**再试"滞后换平滑"（追平时间 / px/s 高度匀速 / 橡皮筋 / 弹簧追高）——实测与复盘见方案 §1.3、§4。

## 一、做到哪一步

| 事项 | 状态 |
|---|---|
| 三条用户反馈里的 #1（发送时列表下滑） | ✅ 已修（v177 / `9ab1dd5` / `apk-m208`），M27 通过 |
| #2（滚回视口动作行重浮现） | ✅ 已修（v179 / `7911928` / `apk-m210`），用户确认 |
| #3（不满一屏时整屏抽搐） | ⬜ **方案定稿待实施**（[plan-reveal-pacing.md](../plan-reveal-pacing.md)） |
| 许可批（AGPL + 自制卡） | ✅ 落地（v178 / `a090008` / `apk-m209`），[ADR 0009](../adr/0009-agpl-license.md) |
| 聊天列表重构 P4（动画收口） | ⬜ 未开始（口径见 [r2 §13](../plan-chat-list-flash-r2.md)） |
| 需求批 E / F | ⬜ 未开始（[plan-req-2026-11-25.md](../plan-req-2026-11-25.md)） |
| 许可批的「开源许可」页 | ⬜ 未开始（要真机验渲染） |

## 二、下一步（按顺序，别跳）

1. **等第三方评审**对 [plan-reveal-pacing.md](../plan-reveal-pacing.md) 的结论（§7 四个开放项）。
2. 用户确认三件事（方案 §C）：**点气泡=补满（仅打印期）**、**复制=全文**、**档位 15/35/70/不限速 · 默认 35**。
3. 按方案 §B「实施最小改动清单」动手：揭示游标（页级、不进 `UiState`）+ 布局即真相 + 四档 + 打断规则 + 日志。
   - 首轮**保留诊断日志**（`打印帧` 2 秒一条），验完再删。
   - 预计 **1–2 轮真机**覆盖方案 §6 的 19 条验收项。
4. 之后回 P4，再做需求批 E → F。

## 三、已拍板 / 已被否（别重新提）

**已拍板（产品原则，不再讨论）**：用户选速度、程序**不偷偷加速**、**不设最长打印时间**、落库后**继续打印**、
复制=全文、系统「移除动画」优先（直接全文）、不新增文本缓冲队列、不改请求/提示词/落库/导出。

**已被数据否掉（别再试）**：
- 追平时间 T（弹簧/低通）→ 实测就是"走走停停 + 最新 7 行藏 150–600ms"；
- 高度匀速 px/s（夹"报给布局的高度"）→ 需求要的是"字"的节奏，不是"像素"；
- 不跟（浏览器式）→ 攒包一次整块上跳 4–7 行（2026-11-24 已否）；
- 自动换档 / 60 秒上限 → 都等于替用户改节奏；
- `rows.lastOrNull()` 推断收尾行 → `uiKey` 本就跨 settle 稳定，不需要推断；
- 用 `String.length` 参与速率比较 / 每次 delta 全文重数码点 → 单位不一致 + 无谓扫描。

## 四、验证过的事实（带证据；别当猜测）

**【代码】**（`ChatViewModel.kt`，只读核实）
- `ChatRow` 不变量 K1–K5：`uiKey` 在行存活期内**永不改变**；命名空间 `m{dbId}` / `u{seq}` / `p{seq}`；`dbId` 不参与 key。
- 开轮 `inflight.uiKey = round.replyUiKey`（`:2878`）；收尾 `RowAction.Append` **复用同一 `replyUiKey`**，
  `Rewrite` 用同一 key 换正文；收尾是**一次 `_state.update`**（不会露出"行已加、生成中还没清"的中间帧）。
- 收缩窗口内 token 只进本地 buffer、**不推** `inflight.text`；`selectSwipe` 在 `isStreaming` 时直接 return。
- `stop()` → `settleClaimed(text=null)` → 用 `streamingText` = **已收到全文**（不是已显示前缀）。

**【真机日志】**（v180，2026-11-26 23:32，503 字 / 19 秒）
- 平均到达 ≈ 125 px/s，但**攒包**：583ms 无字，然后 490px（7 行）一次到；
- 画面速度在 0 与 ~1900 px/s 间来回（停 232/270/583ms）→ 用户说的"抽搐"；
- 橡皮筋 `raw/visual` 恒 0、列表滚动偏移恒 0、`槽−自然` 恒 119px → 元凶是**追高桥接**，不是它们。

**【工程】** 本机 2 核 / 1.7 GiB / **无 Android SDK → 编不了 `:app`**；无 KVM → 无模拟器。
提交前必跑（本机可跑、零依赖）：

```bash
bash tools/check-core-purity.sh && bash tools/check-kotlin-comments.sh \
  && bash tools/check-kotlin-imports.sh && bash tools/check-ui-map.sh \
  && bash tools/check-ui-invariants.sh && bash tools/test-core.sh   # 10 模块 131 条
```

## 五、出包循环（每次都要走）

```bash
git commit -F <消息文件>          # 消息里有引号/反引号就写文件，别用 -m
git push
bash tools/poll-ci.sh <sha>       # 约 2 分钟；失败日志在 release ci-log-android / ci-log-jvm 的 build-<sha7>.log
# 下载 apk-latest 很慢（15–20 分钟），必须放后台 job；BUILD_ID 是唯一判据（classes17.dex 里的 40-hex）
bash tools/verify-apk.sh <apk> 55:E0:C6:E2:19:20:61:92:51:12:04:E8:F8:AC:80:C6:19:44:91:54:26:5B:50:FA:D3:3A:B4:72:8B:96:0B:E7
cp <apk> artifacts/app-vNNN-<sha7>.apk && git tag apk-mNNN <sha> && git push origin apk-mNNN
```

⚠️ 文档提交也会触发 CI 并覆盖 `apk-latest`，**交付以 `artifacts/` + tag 为准**。

## 六、坑（这次踩过的，一句话一条）

1. **`Int` 减 `Float` 返回 `Float`**：v177 第一版就栽在"声明 `Int` 却返回 `Float`"，本机编不了，
   只有 CI 能抓（`Return type mismatch`）。
2. **`assets.srcDir(dir)` 映射的是目录内容**，不是目录本身：许可文本第一版落到了 `assets/LICENSE.txt`，
   要落 `assets/notices/` 得先放进子目录（v178 修）。
3. **"没有日志"≠"没发生"**：橡皮筋有**静默累积**路径（非 fling 的 `raw += available` 不打日志），
   上一轮据此误判过一次。
4. **入场动画在 LazyColumn 回收后重播**：`remember(uiKey)` 会随回收重置，而 `entering` 永远为真 →
   必须"播过就登记"（v179 修的 `entrancedKeys`）。
5. **基线 0 不是"上一个高度"**：`smoothHeight == 0` 时的差量必须同步按 0 处理（v177），
   否则一帧就画出 140px 假位移。

## 七、建议加载的 skills

| skill | 为什么 |
|---|---|
| `verification-before-completion` | 这个项目的"完成"必须带本轮新鲜证据（check 脚本输出、CI 绿、BUILD_ID 校验）。说"好了"之前先跑它。 |
| `diagnosing-bugs` | 真机问题全靠日志（`tag=chat` / `scroll` / `rubber`）；它那套"先造红/绿反馈环再进假设"正是 v177–v180 四轮的做法。 |
| `handoff` | 下次交接照它写一页。 |
