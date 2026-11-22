# ADR 0005 · 玻璃自己画，撤掉 Haze

- 状态：已采纳（2026-11-22），**supersedes [ADR 0004](0004-glass-visuals-haze.md)**
- 影响面：`app` 依赖与底栏绘制；ADR 0004 的依赖决定作废

## 背景

ADR 0004 引了 `dev.chrisbanes.haze:haze:1.5.4` 做背景模糊。真机（vivo / Android 17 / sdk 37）上出现两个问题：

1. **模糊不生效**：开关是开的（`视觉=Glass`）、系统支持（sdk ≥ 31）、硬件加速正常，
   但胶囊里只剩半透明底色，背后的文字仍然可读 —— 说明 Haze 内部退化成了 Scrim 分支或底层捕获为空。
   读源码排掉了 `blurEnabled`（sdk ≥ 32 默认 true）、SDK 门槛、硬件加速三个条件后，剩下的是它内部黑盒，
   没有日志出口（`HazeLogger` 只写 android.util.Log，真机上我们读不到）。
2. **"白条"**：折叠在 ADR 0004 的"未解风险"里没预见到 —— elevation 阴影画在内容后面，
   底色一旦半透明就会在胶囊内部留下亮度台阶（逐像素量到 4 级）。

## 决策

**自己画**，只用 Compose 自带的 `GraphicsLayer` + `RenderEffect`（API 31+）：

```
内容层：Modifier.drawWithContent {
    glassLayer.record(density, layoutDirection, size) { drawContent() }  // 1.7.6 没有无参 record
    drawLayer(glassLayer)                                                // 正常画（不糊）
}
底栏：Modifier.clip(胶囊).drawWithContent {
    glassLayer.renderEffect = BlurEffect(24.dp, 24.dp)   // 挂模糊
    translate(-(底栏位置 - 内容位置)) { drawLayer(glassLayer) }
    glassLayer.renderEffect = null                       // 立刻清掉，别影响内容层自己的绘制
    drawContent()                                        // 半透明底色 + 描边 + 图标文字
}
```

为什么这样更可靠：坐标只有"两个兄弟节点的 `positionInWindow` 相减"，没有第三方内部状态；
出问题也能靠日志/像素量出来。API < 31 无 `RenderEffect`，此时退化成半透明底（与「轻量」一致）。

**顺带定死一条**：elevation 阴影只能配不透明底色。玻璃档不挂 `Modifier.shadow`，只靠 1px 描边。

## 后果

- 好：依赖少一个（`haze` 从 `app/build.gradle.kts` 移除，编译内存也松一点）；行为完全可控。
- 坏：`record` + `drawLayer` 是**每帧两份全屏绘制**，玻璃档在低端机上要盯帧率；
  关闭玻璃（轻量档）时不录层，零额外开销。
- 回退：视觉档切「轻量」即不录层、不模糊；再不行退回 tag `apk-m27`（无玻璃版）。

## 未做 / 下一步

- 页头、卡片、聊天气泡的玻璃化（复用同一套 `GraphicsLayer` 录制）
- 折射/色散（AGSL，API 33+）：暂不做
