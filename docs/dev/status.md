# 项目状态快照（写于 2026-11-22 17:35，压缩上下文前）

**用途**：换会话/压缩上下文后，先读这一页就能接上。更细的规则在 [AGENTS.md](../../AGENTS.md)、
[app/AGENTS.md](../../app/AGENTS.md)，进度唯一真相在 [compat-matrix.md](../compat-matrix.md)。

## 当前基线

- 代码：`32628d4`（兜底回退点：tag `apk-m18`；上一版 `bf4ab77` = tag `apk-m17`）
- 状态：CI 全绿；本机 `tools/test-core.sh` 34 个单测全绿
- 已交付 APK：`app-v15-32628d4.apk`（签名固定，可覆盖安装；上一版 `app-v14-bf4ab77.apk`）
- 引擎/工具链：Kotlin 2.1.20 / AGP 8.7.3 / Compose BOM 2024.12.01 / minSdk 26 / targetSdk 35

## 已经做完的（能装、能日常聊）

| 块 | 状态 |
|---|---|
| 卡：PNG 导入（SAF）、卡网格两列 3:4、详情四标签、卡内嵌书勾选、删卡连带删会话 | ✅ 真机可用 |
| 聊天：SSE 流式、开场白（first_mes 作为第一条消息）、气泡/整行卡片、停止保留半截 | ✅ |
| 持久化：SQLite 会话/消息、会话列表、新建（先选卡弹层）、长按删除（动作单+确认） | ✅ |
| 设置：一级/二级（模型/采样/外观/世界书/关于）、主题四套色板 + 深浅、Key 加密存储 | ✅ |
| 导航：`Destination` + `AnimatedContent`，0–2 层，push/pop 动效（叠放次序按深度给 `targetContentZIndex`，返回才是"抽走一张卡"） | 🟨 待真机确认 |
| 应用图标：黑猫月色 adaptive icon（前景 63% 安全区 + 模糊插画底） | ✅ |
| 日志：`AppLog`（内存+落盘）+ 崩溃兜底 + 关于页导出 | ✅ |

## 正在做的 / 下一步（按顺序）

1. **C 批：模型档案**（未开始）
   - DB 表 `model_profiles`（显示名 / baseUrl / 模型 id / 思考强度 / 自定义请求头 / 是否默认）
   - Key 仍走 Keystore，按 profile id 存；**迁移现有单套配置成"默认模型"，不能丢 Key**
   - 请求头合并进 OkHttp；思考强度映射 `reasoning_effort`
2. **D 批：三级继承**（依赖 C）
   - `会话 > 角色卡 > 全局`，每项可"跟随"；解析器放 `:core` 并用单测覆盖
   - 会话配置页（聊天页 `⋮` 里那个置灰入口）；覆盖项最小集：模型 / 温度·maxTokens / 对话样式 / 世界书
3. **P0-6 消息操作**：swipe / 重新生成 / 编辑 / 删除单条（要先用原版录 swipe 夹具）
4. **P1 世界书引擎**：激活、递归、预算（`docs/compat-matrix.md` 里 P1-1~P1-4）

## 已知问题 / 风险（别当成已完成）

- **串流抽动**这版改成了 `reverseLayout` 修，逻辑上应该消失，但**还没有真机确认**；
  若仍闪，让用户导出日志看请求/完成时间点。
- `ModalBottomSheet`（选卡、动作单）的 insets 与底栏叠加未在真机确认。
- 世界书只存"勾选"，**不影响提示词**（引擎未实现）。
- 日志只到"事件级"：不含 HTTP 响应原文；进程被系统杀掉时抓不到（导航已补 `tag=nav` 打点）。
- **未接预测式返回**：Android 13+ 的返回手势在松手后才播动画，拖动时看不到上一级预览。
  要接得用 `PredictiveBackHandler` + 把拖拽进度喂给 `AnimatedContent`（约一轮专门的工作量）。
- 本机编不了 Android 模块：所有 Compose 改动只能靠 CI 编译 + 真机验。

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
- UI 方向「C · Dense Ink」：细线分隔、1px 描边、信息密度高；主题墨紫 `#6D4BC4`（深色 `#C6B4F5`）。
- 不引第三方 UI 库、不引 navigation-compose、不引 Coil/material-icons-extended。
