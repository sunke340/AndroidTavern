# ADR 0008 — 拖动排序用 `sh.calvin.reorderable`，不手写

- **状态**：接受（2026-11-24）
- **关系**：收窄 [ADR 0005](0005-glass-without-haze.md) 那条"不引第三方 UI 库"的适用范围
- **依据**：[AGENTS.md](../../AGENTS.md) 铁律 #10「能复用就先复用：仓库里有 → 现成的库
  → 都没有才自研」。本文件是「查现成的库」这一步的结论，ADR 0005 是「必须自研」那一步的结论。

## 背景

预设页要能**长按拖动换位**改 `prompt_order`。第一版是手写的（`detectDragGesturesAfterLongPress`
+ `graphicsLayer.translationY` + 按行高反算目标位次 + `dispatchRawDelta` 贴边滚动）。用户反馈
"效果不理想"，并要求**不要重新造轮子**。

## 决策

引入 [`sh.calvin.reorderable`](https://github.com/Calvin-LL/Reorderable) `3.1.0`。

## 理由

手写那版要独立解决三件事，每一件在真机上都能出问题：

1. **位次推算**：累计位移 / 行高 → 目标 index，换位后还要把"已经挪过的行数 × 行高"从位移里扣掉，否则被拎起来的那行会跳；
2. **贴边自动滚动**：得自己判断"拖到视口上下 18%"，再自己塞滚动位移；
3. **换位动画**：换完顺序，其它行需要有位移动画，否则是硬跳。

库把这三件都做了，另外白送：长压起拖、可拖把手（`Modifier.draggableHandle` /
`longPressDraggableHandle`）、不同高度的 item、`scrollThresholdPadding`、可访问性建议。
它是 Lawnchair / Home Assistant / ProtonVPN / Mihon 这些项目在用的库，不是玩具。

## 适用范围（只针对"机械"，不针对"材质"）

ADR 0005 那条"不引第三方 UI 库"的教训是**材质数学**：Haze 在两台真机上都只给半透明、
不给模糊，最后结论是玻璃必须自研（`LiquidGlass.kt`）。拖动排序不是材质 —— 它是手势与布局的
机械组合，没有"设备支持不支持"的问题。所以这条禁令**收窄为**：材质、转场、按压反馈这些
与我们的坐标系/录图层强耦合的东西仍然自研；纯粹的列表机械可以引库。

## 版本约束（升级前必读）

| 项 | 值 |
|---|---|
| 库版本 | `3.1.0` |
| 它声明的 Compose 依赖 | `org.jetbrains.compose.*:1.7.0` → 对上我们的 BOM `2024.12.01`（Compose **1.7.6**） |
| 它声明的 Kotlin | `kotlin-stdlib 1.9.0`（我们用 2.1.20，能读） |

**升级前先看 POM 里的 Compose 版本**：如果新版要 Compose 1.8+，就得连 BOM 与整条工具链一起升，
这和 ADR 0007 里 Haze 1.5.4 钉版本的道理一样。

## 代价

- APK 体积：15,589,235 → 15,703,923 字节（**+115 KB**）。
- 多一个依赖要跟着 Android 生态版本走。

## 使用约束（来自库的 FAQ，踩了会闪）

`onMove` **返回前**必须已经改完列表（不要在 `onMove` 里 `launch` 一个协程去改），
否则被拎起来的那一行的位置会滞后一帧，看起来就是闪一下。我们的 `movePrompt()` 是同步改
`MutableStateFlow`，满足这条。
