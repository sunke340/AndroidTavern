# 项目状态快照（2026-11-25 09:15）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 接手先看 [handoff.md](handoff.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `28fbce1`（tag `apk-m168`） |
| 最新交付 | `artifacts/app-v137-28fbce1.apk`（签名与 dex `BUILD_ID` 都核过） |
| 真机验过的最后一版 | `artifacts/app-v117-0631bff.apk`（串流跟随那一轮） |
| CI | 铁律 / 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` 全绿（9 个模块，89 条）+ 三个 `check-*.sh` |
| 规模 | `app` 约 40 个 kt 文件 / 约 10.6k 行；`data` 有 config + message_swipes（DB v3） |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 这一版（M4 第一包）修了什么

用户 2026-11-25 早上给了 M3 的 21 条回执：这批改 14 条，2 条是说明（系统减动效、装饰器测法），
剩下 5 条（3/16/17/18/20）确认正常、不动。

| 块 | 状态 |
|---|---|
| **浮层静默失效的根因**：页面在骨架之前读 `Local*Controller`，拿到没宿主的实例 | ✅ 页面自建控制器传进骨架 + `attached` 标记与缺宿主日志 |
| 抽屉把手收回阈值 1/4 → **1/2** 自身高度，并且不再看速度（只有拖过半才收） | ✅ |
| 输入区聚焦展开补 `expandVertically` / `shrinkVertically` 形变 | ✅ |
| 思考强度 = 左右滑块（会话配置页 / 卡配置 tab / 模型抽屉顶部） | ✅ 卡级新增 `useCardReasoning` |
| ⋮ 只写「会话配置」；会话配置页删副标题与底部说明 | ✅ |
| 卡配置的模型/世界书/正则/预设行恢复可点 | ✅（同一根因） |
| 开场白切换器移到气泡**右下**；详情页默认那条占「当前默认」灰胶囊 | ✅ |
| 消息操作改气泡底部按钮行；长按=选字；用户气泡新增「撤回」(rewind) | ✅ |
| 卡编辑右上角「保存」→ ✓ | ✅ |
| 卡头改海报版式（260dp，磨砂底 + 完整竖图 `Fit`） | ✅ 解决竖图被横条 Crop |

## 真机待复核（M4）

清单在 [device-verification.md](device-verification.md) 的 M4 一节（每条都写了"该看到什么"）。
最可能出问题的三处：**滑块在抽屉里是否实时跟手**、**卡头海报版式是否接受**、
**消息按钮行是否太占地方**。日志里新增 `ui …请求没有宿主`，这类哑火以后能直接看出来。

## 已经验收可用（沿用）

| 块 | 状态 |
|---|---|
| 卡：PNG 导入、网格、搜索与标签、详情四标签、内嵌书勾选、删卡连带会话 | ✅ |
| 聊天：SSE 流式、串流跟随 + 阅读锚点补偿、停止保留半截 | ✅ 用户 2026-11-25："确实正常了" |
| 导航：`Destination` + `PageStack`，起动画前预热新页 | ✅（35 帧 / 最长帧 8–16ms） |
| 橡皮筋、玻璃、页头、底栏、设置六页 | ✅ |
| 预设（P1-5）：解析 + `prompt_order` + 导入/选择/编辑拖动换位 | ✅ 用户验收 |
| 世界书：条目三态开关、批量菜单、`@@` 装饰器、sticky/cooldown 落库 | ✅ fixture + 单测 |
| 聊天导出/导入（原版 `.jsonl`，含 swipe） | ✅ 用户实测导出、目测导入正常 |
| 卡编辑写回 PNG（备份 + 原子替换） | ✅ 用户实测简介保存成功 |
| 卡级 `system_prompt` / `post_history_instructions` 顶替 main / jailbreak | ✅ fixture:P0-5 |

## 三级配置（会话 > 角色卡 > 全局）

- 数据：`ConfigOverride`（每项 null = 跟随上级）+ SharedPreferences `session:<id>` / `card:<id>`，**不动 DB**。
- 解析：只认 `UiState.effective*` 六个 getter（profile / reasoning / preset / worldBookNames / regexIds /
  regexScripts）；发送、世界书扫描、正则两侧、连接参数全读它们。
- 界面：会话配置页 4 行（模型/世界书/正则/预设）+ 思考强度滑块；卡配置 tab 7 行 + 同款滑块；
  模型抽屉顶部也有一个滑块（会话级或卡级，取决于从哪打开）。
- 卡级默认开场白也走这里（`greetingIndex`）。

## 消息操作（P0-6 现状）

| 动作 | 语义 | 证据 |
|---|---|---|
| 重新生成 | 不删行：新文本作为新的 swipe 备选追加，旧的可翻回 | `P0-6-swipes` 夹具 |
| 继续 | 请求末尾加原版那条 system 提示，结果接在最后一条后面 | `P0-6-continue` 夹具 |
| swipe | 只对最后一条助手消息；备选存 `message_swipes`（DB v3） | 同上 |
| 编辑 / 删除 | 编辑同步改当前 swipe；删除走玻璃确认框 | 手工 |
| 撤回 | 删这条用户消息及之后全部，正文放回输入框 | 手工，**待真机** |

## 下一步

1. M4 真机复核；有问题按清单编号回。
2. 世界书 **outlet**：缺夹具（默认预设里没有 `{{outlet::…}}`），要先给 capture 加"选预设"。
3. 卡库**分组**（原版文件夹）未做。
4. `forbid_overrides` 与"按角色禁用某段"没做（现在的卡级顶替是无条件的）。

## 已知问题 / 风险

- **swipe 只对最后一条**（与原版一致），中间那条只能编辑。
- **「继续」是裸拼**（旧文 + 新文）：模型不给前导空格时会粘字。
- **消息没有稳定 key**：串流中往 transcript 加消息会插入一项。
- **编辑弹层不是玻璃**：要浮键盘、改长文本，玻璃面板在键盘上方难量高度。
- **减动效没有 App 内开关**：跟系统（无障碍 → 移除动画 / 开发者选项 → 动画时长缩放）。
- 世界书开关会重解析；批量开启可能撑爆提示词（有预算裁剪兜底）。outlet 只记录不注入。
- 玻璃帧率从未量过；未接预测式返回；日志只到事件级。
- **`apk-latest` 的 release body 在资产上传前就更新**：`poll-ci.sh` 报 READY ≠ 新包已就绪，
  必须下载后验 dex `BUILD_ID`（这次也是这么验的）。
- **本机编不了 `:app` / `:data`**：Android 侧编译错误只有 CI 会暴露（一轮约 4 分钟）。

## 这台机器的现实

- 能跑：`tools/test-core.sh`、`tools/check-*.sh`、Playwright 录原版夹具（ST 在 8000 跑着）
- 不能跑：`assembleDebug`（内存不足）、模拟器（无 KVM）、`:app` 编译
- 出包：`git tag apk-mN && git push origin HEAD apk-mN` → 等 run 完成 → 下载 → 验 `BUILD_ID` + 签名 → 存 `artifacts/`
- 细节见 [runbook.md](runbook.md) 与 [environment.md](../environment.md)
