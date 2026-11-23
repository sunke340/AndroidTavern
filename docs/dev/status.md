# 项目状态快照（写于 2026-11-22 19:10）

**用途**：换会话/压缩上下文后，先读这一页就能接上。更细的规则在 [AGENTS.md](../../AGENTS.md)、
[app/AGENTS.md](../../app/AGENTS.md)，进度唯一真相在 [compat-matrix.md](../compat-matrix.md)。

## 当前基线

- 代码：`0d69e72`（tag `apk-m60`；兜底回退点：`apk-m48` 是玻璃版，`apk-m27` 是纯 C2 版）
- 状态：CI 全绿；本机 `tools/test-core.sh` 全绿（含 9 个世界书/计数夹具断言）
- 已交付 APK：`app-v53-0d69e72.apk`（签名固定，可覆盖安装；上一版 `app-v51-25303bf.apk`）
- 引擎/工具链：Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01 / minSdk 26 / targetSdk 35

## 已经做完的（能装、能日常聊）

| 块 | 状态 |
|---|---|
| 卡：PNG 导入（SAF）、卡网格两列 3:4、详情四标签、卡内嵌书勾选、删卡连带删会话 | ✅ 真机可用 |
| 聊天：SSE 流式、开场白（first_mes 作为第一条消息）、气泡/整行卡片、停止保留半截 | ✅ |
| 持久化：SQLite 会话/消息、会话列表、新建（先选卡弹层）、长按删除（动作单+确认） | ✅ |
| 设置：一级/二级（模型/采样/外观/世界书/关于）、主题四套色板 + 深浅、Key 加密存储 | ✅ |
| 导航：`Destination` + `AnimatedContent`，0–2 层，三档转场（标准/淡入/关闭）；位移只做在"进来那页" | ✅ 真机确认（2026-11-22 用户验收） |
| 应用图标：黑猫月色 adaptive icon（前景 63% 安全区 + 模糊插画底） | ✅ |
| 日志：`AppLog`（内存+落盘）+ 崩溃兜底 + 关于页导出 | ✅ |
| 世界书引擎：关键字/常数/位置/深度/概率/四种 selectiveLogic/递归/包含组/sticky/cooldown/delay/预算 | 🟨 引擎 + 夹具全绿；App 已接线（卡内嵌书勾选真生效），**待真机** |
| 全局世界书：导入 `.json` / 启用停用 / 删除 / 与卡内嵌书一起扫描 | 🟨 代码完成，**待真机** |
| 正则脚本：导入 / 逐条开关 / 用户输入改写 / 模型输出只改显示 | 🟨 引擎对夹具逐条相等，**待真机** |

## 正在做的 / 下一步（按顺序）

1. **C 批：模型档案**（C1 数据层 + C2 界面都已完成，待真机验收）
   - ✅ 表 `model_profiles` + DB v1→v2 迁移（只增表）+ Key 按档案 id 存 + 幂等种默认档案
   - ✅ 请求层：自定义头跟着档案走，思考强度映射 `reasoning_effort`
   - ✅ 界面：设置→模型 = 档案列表（点行编辑、"设为默认"、新建）；三级编辑页
     （`Destination.ProfileEdit`，草稿式：点保存才写库/写 Key）；聊天页 ⋮ 显示当前档案
2. **D 批：三级继承**（依赖 C）
   - `会话 > 角色卡 > 全局`，每项可"跟随"；解析器放 `:core` 并用单测覆盖
   - 会话配置页（聊天页 `⋮` 里那个置灰入口）；覆盖项最小集：模型 / 温度·maxTokens / 对话样式 / 世界书
3. **P0-6 消息操作**：swipe / 重新生成 / 编辑 / 删除单条（要先用原版录 swipe 夹具）
4. **世界书剩下的**：sticky 计时状态落库（现在只在内存，重启重置）、`@@` 装饰器、outlet 位置
5. **P1-5 预设**：导入 ST 预设 JSON（采样参数 + `main` + `prompt_order`）——**还没做**
6. **P0-6 消息操作**：swipe / 重新生成 / 编辑 / 删除单条
7. **待真机确认**：v54 这版（磨砂只留聊天页、发送钮高光调亮、橡皮筋可打断 + 改弹簧、按压改弹簧）

## 已知问题 / 风险（别当成已完成）

## UI 第二轮（2026-11-23 白天，全部走 CI 出包）

| 块 | 状态 |
|---|---|
| 世界书条目页：设置→世界书→点书名，做成**真三级页**（`Destination.WorldBookEntries`，走导航转场） | ✅ 已发 |
| 全局世界书：可看条目、**逐条开关**（应用内覆盖，不改导入文件） | ✅ 已发 |
| 胶囊暗带 | ❌ **整段删除**（决策见 app/AGENTS.md：它改了感知边界，和投影/反馈的弧线对不上） |
| 按压反馈 | 整块玻璃放大 1.25；**弹簧**（按下 0.6/800、松开 0.8/380，抄 M3 Expressive）；**吃 interaction 流**（布尔状态会吞掉同一帧内的快速点按）+ 最短 90ms；涟漪关掉 |
| 页头 | **只有聊天页磨砂**（`frostedHeader = true`）；其余是**一条纯线性色罩**：`headerScrim()` 0.94 → 透明，盒子多 28dp 收尾（加"饱和平台"会被看成阶梯） |
| 状态栏 | 与页头连成一片（内容铺满整屏，滚到页头下面被糊掉） | ✅ 用户确认真机生效 |
| 发送钮 | **液态玻璃（AGSL 真折射，薄玻璃）**：折射带 4.5dp/0.22 + 色散 + 上下打光细光线（`0.28+0.72·|n.y|`）+ 左右淡内阴影(0.07)；面板白 0.22 / 主色 20% / 图标 72% 半透；投影四周均匀 14dp·0.10 不下沉。失败或低于 33 退回描边样式 |
| 视觉效果档 | 玻璃档门槛 **API 33**（`glassSupported`）；低于 33 只开放轻量，设置页不显示玻璃选项（`VisualEffects.effective()`） |
| 键盘 | 内容底部让位取 max(导航栏, 键盘)（之前相加，多抬一个导航栏） | ✅ 用户确认可拖到胶囊上方 |
| 橡皮筋滚动 | `:app/RubberOverscroll.kt`：跟手 0.62 + 渐进阻尼、最多 64dp、`Motion.RubberBand`(临界阻尼) 且不许过冲；**打断**用 `settleJob.cancel()` + **代数守卫**（防已排队的帧把拖出来的位移盖回去），`onPostFling` 不 join；日志 tag `rubber`（含 `反涨=`） |

- **半透明色罩的浓度没有真机确认**：取 0.62（对齐上一轮玻璃档 GlassBand 的 tint）。觉得太透就改
  `Style.kt` 的 `HeaderScrimAlpha`（轻量档早期用过 0.94）。
- **按压 1.25 倍是否过大未确认**：放大倍数与弹簧是两件事，觉得太大改 `Glass.kt` 的 `PressScale`。
- **串流抽动**：`reverseLayout` 修完至今用户没再复现，但没专门确认过；再报就导日志看请求/完成时间点。
- **转场有一个实现限制**：`AnimatedContent` 里永远是"进来那页"在上层（`targetContentZIndex` 对同一过渡内的
  两层无效，源码确认），所以返回是"上一页从左侧压回来"而不是"当前页向右抽走"。用户已接受这个观感。
- `ModalBottomSheet`（选卡、动作单）的 insets 与底栏叠加未在真机确认。
- 世界书只存"勾选"，**不影响提示词**（引擎未实现）。
- 日志只到"事件级"：不含 HTTP 响应原文；进程被系统杀掉时抓不到（导航已补 `tag=nav` 打点）。
- **未接预测式返回**：Android 13+ 的返回手势在松手后才播动画，拖动时看不到上一级预览（用户暂未要求）。
- 本机编不了 Android 模块：所有 Compose 改动只能靠 CI 编译 + 真机验。
- **CI 编译内存**：`gradle.properties` 的值是给本机（1.7 GiB）调的，workflow 里 checkout 后会 sed 成
  `-Xmx3g -XX:MaxMetaspaceSize=1g`。加依赖后 Metaspace 会顶不住，别把那两行 sed 删掉。
- **玻璃已覆盖底栏 + 页头**：底栏胶囊与页头（大标题 + 右侧玻璃胶囊）都走 `GlassSurface`；
  页头是浮层、画在录图层之外（否则胶囊会采到上一帧的自己）。卡片/聊天气泡仍是旧样式，下一轮继续。
  模糊是每帧离屏绘制，滚动是否掉帧待真机；掉帧就在 设置→外观→视觉效果 切「轻量」。
- **把内容录进图层要用 `DrawScope` 的 `record` 扩展**（`layer.record(size) { drawContent() }`）：
  四参数成员版只改图层自己的 `DrawContext`，`drawContent()` 会照旧画到主画布 —— 屏幕正常、图层空、底栏画了个寂寞。
  之前三版玻璃（Haze / RenderEffect / 降采样）全栽在这一行上，不是系统不支持 `RenderEffect`。
- **玻璃模糊必须挂在节点上**（ADR 0006）：`renderEffect` / `alpha` 这类图层属性是渲染时读的，
  在 draw 块里「设了用完就清」等于没设 —— 之前两台真机都只看到半透明就是这个原因，不是系统限制。
  连带：模糊节点要比被糊的区域大一圈（离屏缓冲边缘会 clamp 成直纹）；`positionInWindow` 要量在没有
  padding 的那层上。诊断看 `AppLog` tag `glass` 的两行（sdk / 硬件加速 / 半径，底栏 / 内容 / 尺寸）。

## 开发回路（这台机器的现实）

- 本机能跑：`tools/test-core.sh`、`tools/check-*.sh`、Playwright 录原版夹具、HTML 出设计样张
- 本机不能跑：`assembleDebug`、模拟器、`:app` 编译
- CI 结果**每 40–45 秒轮询一次**（不要 sleep 300 干等）；失败日志从 `ci-log-jvm` / `ci-log-android`
  release 的 `build.log` 取，本地下载后 grep `^e: `
- 发 APK：`git tag apk-mN && git push origin apk-mN` → 等 `apk-latest` 的 body 出现新 sha →
  下载 → 用 **dex 里的 `BuildConfig.BUILD_ID`** 核对是不是这次的包（大小经常撞车）→
  `tools/verify-apk.sh` 对指纹 → 复制到 `~/.dsh-qqbot/media/` 再 `qqbot_send_file`
- 指纹：`55:E0:C6:E2:…:0B:E7`（见 `keystore/README.md`）
- 已踩的编译坑：Kotlin 块注释可嵌套、重复 import、图标不在 core、`Icons.Filled.X` 扩展属性不能全限定、
  `by mutableStateOf` 需 `setValue` import。前两个已进 CI 铁律检查（10 秒内挡下）。

## 关键设计决定（一句话版）

- 兼容 = 文件格式往返 + 提示词逐条等价；不改写卡内容、不在发送前加工提示词。
- DB 是运行时真相，JSONL 只做导入导出（ADR 0003：原生 SQLite，不用 Room）。
- UI 方向「C · Dense Ink」：细线分隔、1px 描边、信息密度高；主题墨紫 `#5C3BAE`（深色 `#C6B4F5`）。
- 不引第三方 UI 库（玻璃模糊是**自研**的，见 [ADR 0005](../adr/0005-glass-without-haze.md) /
  [ADR 0006](../adr/0006-glass-blur-on-node.md)；曾引 Haze，已撤）、
  不引 navigation-compose、不引 Coil/material-icons-extended。
