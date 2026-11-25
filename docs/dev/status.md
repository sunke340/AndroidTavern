# 项目状态快照（2026-11-25 13:10）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 接手先看 [handoff.md](handoff.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `4ada4c6`（tag `apk-m175`） |
| 最新交付 | `artifacts/app-v144-4ada4c6.apk`（默认正则 + 显示格式）；上一包 v143 是选字修复 |
| 真机验过的最后一版 | `artifacts/app-v117-0631bff.apk`（串流跟随那一轮） |
| CI | 铁律 / 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` 全绿（9 个模块，89 条）+ 三个 `check-*.sh` |
| 规模 | `app` 约 41 个 kt 文件 / 约 10.8k 行；`data` 有 config + message_swipes（DB v3） |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 这一版（M6）改了什么

用户 2026-11-25 中午看完 M5 后提了 10 条；这一版已落码，等真机：

| 块 | 状态 |
|---|---|
| 输入区形变 320 → **400/300ms** | ✅ |
| 键盘收起 + 空输入 → 收回到一行（按键盘状态判断，不再只看焦点）；再点胶囊会主动弹键盘 | ✅ |
| 去掉会话换模型的"本会话使用…"提示条 | ✅ |
| 配置页「跟随上级」**常驻**（之前它一出现就把整列挤下移，点错行） | ✅ |
| 消息图标 32dp、列表底部再让 12dp（图标不再被输入胶囊的投影压住） | ✅ |
| 撤回：删"这条用户消息 + 之后双方全部"，按数据库重建列表，日志与条数提示 | ✅ |
| 卡编辑保存改成**和删除确认同一个玻璃框**（取消 / 新建一张 / 覆盖原卡） | ✅ |
| 输入框默认文案删掉（默认空） | ✅ |
| 形变时的按钮遮挡：膨胀行只留高度，发送/模型钮改成**浮层**（不再被逐帧裁剪） | ✅ |
| 撤回后列表滑回最底部（一次性信号 → `animateScrollToItem(0)`，日志 `scroll 回到列表底部`） | ✅ |
| 删掉消息「编辑」（撤回已覆盖）；连带删 `EditMessageDialog` / `editMessageAt` | ✅ |
| 离场页收掉输入焦点与选字手柄（动态 `LocalPageIsCurrent` + 列表 `key(epoch)` 重建） | ✅ |
| **修**：v142 用静态 local + 逐项 `selectable` 导致"最新气泡不能长按选字、滑一下才好" | ✅ v143 |
| **P1-12 默认正则**：内置只读两条（清包裹标签 / 动作强调），设置→正则可关；只影响显示 | ✅ v144 |

## 真机待复核（M6）

清单在 [device-verification.md](device-verification.md) 的 M6 一节。最可能还要调的两处：
**最后一条消息的图标是否还有遮挡**、**键盘收起→复原那一下的手感**。

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
| 卡头海报版式（260dp，磨砂底 + 完整竖图 Fit） | ✅ M4 后用户未再提裁切 |
| 卡级 `system_prompt` / `post_history_instructions` 顶替 main / jailbreak | ✅ fixture:P0-5 |

## 三级配置（会话 > 角色卡 > 全局）

- 数据：`ConfigOverride`（每项 null = 跟随上级）+ SharedPreferences `session:<id>` / `card:<id>`，**不动 DB**。
- 解析：只认 `UiState.effective*` 六个 getter（profile / reasoning / preset / worldBookNames / regexIds /
  regexScripts）；发送、世界书扫描、正则两侧、连接参数全读它们。
- 界面：会话配置页 4 行、卡配置 tab 7 行，点进去都是**整页选择**（模型 / 世界书 / 正则 / 预设），
  页面里点一行就地生效、不自动返回；当前项用勾选圈标出。思考强度只在"模型"页顶部。
- 聊天页左下角是唯一的抽屉：快速换模型 + 思考强度滑块，选中不收。

## 消息操作（P0-6 现状）

| 动作 | 语义 | 证据 |
|---|---|---|
| 重新生成 | 不删行：新文本作为新的 swipe 备选追加，旧的可翻回 | `P0-6-swipes` 夹具 |
| 继续 | 请求末尾加原版那条 system 提示，结果接在最后一条后面 | `P0-6-continue` 夹具 |
| swipe | 只对最后一条助手消息；备选存 `message_swipes`（DB v3） | 同上 |
| 编辑 / 删除 | 编辑同步改当前 swipe；删除走玻璃确认框 | 手工 |
| 撤回 | 删这条用户消息及之后全部，正文放回输入框 | 手工，**待真机** |

入口是气泡底下一排**图标**；长按是选文字；开场白不挂这排。

## 下一步

1. M5 真机复核；有问题按清单编号回。
2. 世界书 **outlet**：缺夹具（默认预设里没有 `{{outlet::…}}`）。
3. 卡库**分组**（原版文件夹）未做。
4. `forbid_overrides` 与"按角色禁用某段"没做。

## 已知问题 / 风险

- **swipe 只对最后一条**（与原版一致），中间那条只能编辑。
- **「继续」是裸拼**（旧文 + 新文）：模型不给前导空格时会粘字。
- **消息没有稳定 key**：串流中往 transcript 加消息会插入一项。
- **减动效没有 App 内开关**：跟系统（无障碍 → 移除动画）。
- **配置页选完不自动返回**：多选必须如此，单选也统一了；要"选完即回"再加。
- 世界书开关会重解析；批量开启可能撑爆提示词（有预算裁剪兜底）。outlet 只记录不注入。
- **`apk-latest` 的 release body 在资产上传前就更新**：`poll-ci.sh` 报 READY ≠ 新包已就绪，
  必须下载后验 dex `BUILD_ID`（这次也是这么验的）。
- **本机编不了 `:app` / `:data`**：Android 侧编译错误只有 CI 会暴露（一轮约 4 分钟）。

## 这台机器的现实

- 能跑：`tools/test-core.sh`、`tools/check-*.sh`、Playwright 录原版夹具（ST 在 8000 跑着）
- 不能跑：`assembleDebug`（内存不足）、模拟器（无 KVM）、`:app` 编译
- 出包：`git tag apk-mN && git push origin HEAD apk-mN` → 等 run 完成 → 下载 → 验 `BUILD_ID` + 签名 → 存 `artifacts/`
- 细节见 [runbook.md](runbook.md) 与 [environment.md](../environment.md)
