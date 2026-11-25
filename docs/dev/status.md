# 项目状态快照（2026-11-25 10:20）

> **这一页只写"现在是什么状态"**。每次更新**整篇重写**，不堆历史 ——
> 历史看 git 提交与 [pitfalls.md](pitfalls.md)，规则看 [AGENTS.md](../../AGENTS.md) 与
> [app/AGENTS.md](../../app/AGENTS.md)，进度看 [compat-matrix.md](../compat-matrix.md)，
> 接手先看 [handoff.md](handoff.md)。
> 与 AGENTS.md 冲突时**以 AGENTS.md 为准**；这里不是规范，别当硬性要求引用。

## 当前基线

| | |
|---|---|
| 代码 | `edffcb5`（tag `apk-m169`） |
| 最新交付 | `artifacts/app-v138-edffcb5.apk`（签名与 dex `BUILD_ID` 都核过） |
| 真机验过的最后一版 | `artifacts/app-v117-0631bff.apk`（串流跟随那一轮） |
| CI | 铁律 / 纯 JVM 单测 / debug APK 三 job 全绿 |
| 本机测试 | `tools/test-core.sh` 全绿（9 个模块，89 条）+ 三个 `check-*.sh` |
| 规模 | `app` 约 41 个 kt 文件 / 约 10.8k 行；`data` 有 config + message_swipes（DB v3） |
| 工具链 | Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01（=1.7.6）/ minSdk 26 / targetSdk 35 |

## 这一版（M5）改了什么

用户 2026-11-25 上午看完 M4 后提了 9 条；这一版全部落码。

| 块 | 状态 |
|---|---|
| 抽屉收回阈值 1/2 → **1/3**（仍只看位移） | ✅ |
| 输入区形变 180 → **320/240ms** | ✅ |
| 聊天页模型抽屉 = 设置页模型页同款（勾选圈），**选中不关抽屉** | ✅ |
| 会话/卡配置四行改为**整页选择**（`ConfigPickerScreen` + `Destination.ConfigPicker`） | ✅ 不再用抽屉 |
| 思考强度只在"模型"里出现一次（模型页顶部 + 快捷抽屉顶部） | ✅ 删除两页里的独立滑块 |
| 消息动作改**图标**（复制/撤回 Canvas 画，其余 core 图标） | ✅ |
| 开场白气泡底下不显示动作 | ✅ |
| **串卡 bug**：保存用了会话那张卡的原始 JSON；`.bak.png` 被当成卡 | ✅ 见下 |
| 卡编辑保存改三选：**新建 / 覆盖（自动备份）/ 取消** | ✅ 新增 `CardRepository.saveCardAs` |

### 串卡 bug（值得单独记）

真机日志证据：删掉 `synthetic.png` 后编辑保存，写的是 `synthetic.bak.png`，保存后"条目=4"。

1. `saveCardFields` 用的是 ViewModel 的 `card`（**会话那张卡**的原始 JSON），而 `loadDetail`
   从不刷新它 → 先聊 Seraphina 再编辑 Synthetic，保存时把 Seraphina 的整份 JSON
   （含内嵌世界书）写进 Synthetic。现在详情单独记 `detailRaw + id` 并核对。
2. 卡目录扫描只按 `.png` 过滤 → 每次保存产生的 `.bak.png` 都成了"一张新卡"，
   用户点它再编辑就写到备份上。现在排除 `.bak.png` / `.tmp`，删卡连带删备份。

已经串进去的卡不会自动恢复：删掉重导原始卡最干净。

## 真机待复核（M5）

清单在 [device-verification.md](device-verification.md) 的 M5 一节。最可能还要调的三处：
**配置整页的观感是否像设置页**、**图标行是否看得懂**、**新建/覆盖的措辞与默认项**。

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
- **编辑弹层不是玻璃**：要浮键盘、改长文本。
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
