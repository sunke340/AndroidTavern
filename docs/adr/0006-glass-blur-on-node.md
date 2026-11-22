# ADR 0006 · 玻璃的模糊挂在节点上，不许「设完就清」

- 状态：已采纳（2026-11-22），**supersedes [ADR 0005](0005-glass-without-haze.md) 的绘制方式**
  （0005 的"不用 Haze、自己画"这条决策不变）
- 影响面：`app` 的 `TavernBottomBar` 绘制；ADR 0005 第 26–31 行那段代码作废

## 背景

ADR 0005 的写法在真机上**没有模糊**，只有半透明底；用户两台设备（vivo / Android 17 / sdk 37 和另一台）
表现一致。当时我把原因归到"系统级限制"——这个判断是错的：`Modifier.blur` 在 Android 12+ 上到处都是，
系统级 API 也确认 RenderEffect 就是**同一窗口内部**内容糊化的正路
（[AOSP 窗口模糊文档](https://source.android.com/docs/core/display/window-blurs)明确区分：
窗口模糊 API 是跨窗口、需要半透明浮动窗口，`RenderEffect` 才是窗口内）。

真因在 ADR 0005 那段代码本身：

```kotlin
glassLayer.renderEffect = blurEffect      // 设
translate(...) { drawLayer(glassLayer) }  // 画
glassLayer.renderEffect = null            // ← 同一帧里清掉
```

`GraphicsLayer` 底下就是 RenderNode：绘制操作录进 display list，而 `renderEffect` / `alpha` /
`translation` 这些是**渲染时才读**的属性（正因为如此才能在内容不变时只改属性、不重录）。
在绘制阶段先设后清，等 HWUI 真正渲染这一帧时效果早没了 —— 于是只剩半透明。
官方 sample 的写法是 `record` → 设 `renderEffect` → `drawLayer`，设完**保持**
（[drawLayer 文档](https://composables.com/jetpack-compose/androidx.compose.ui/graphics/functions/drawLayer.md)：
"The GraphicsLayer provided must have GraphicsLayer.record invoked on it"）。

## 决策

模糊只通过**声明式节点**表达，效果跟着节点活到渲染完：

```kotlin
// 内容层（不变）：录一份完整内容，正常画
Modifier.drawWithContent {
    glassLayer.record(density, layoutDirection, size) { drawContent() }
    drawLayer(glassLayer)
}

// 底栏：把图层塞进一个挂 Modifier.blur 的节点里画
Box(Modifier.matchParentSize().expandForBlur(pad)) {
    Canvas(Modifier.fillMaxSize().blur(GlassBlur)) {
        val origin = barOrigin.value - contentOrigin()
        translate(pad - origin.x, pad - origin.y) { drawLayer(glassLayer) }
    }
}
Box(Modifier.matchParentSize().background(fill).border(...))   // 半透明底 + 描边
Row { ... }                                                    // 图标文字
```

`Modifier.blur(radius)` 本身就是 `graphicsLayer { renderEffect = BlurEffect(radius, radius) }`，
是官方给的、被 `Modifier.blur` 用户长期验证的那条路径。**任何 `renderEffect` / `alpha` 之类的
图层属性都不要在 draw 块里"设了用完就清"。**

两个配套细节：

1. **模糊节点要比胶囊大一圈**（`expandForBlur(pad)`，`pad` = 模糊半径）。离屏缓冲只有节点那么大，
   边缘按 `TileMode.Clamp` 收敛；胶囊只有 56dp 高，24dp 的模糊会把上下各一大段糊成直纹。
   放大之后多出来的部分由外层胶囊 `clip` 裁掉。
2. **`positionInWindow` 要量在"没有 padding 的那层"上**。同一个 LayoutNode 上，`Modifier.padding`
   之前报的是含 padding 的外框 —— 上一版把 `onGloballyPositioned` 挂在带 padding 的节点上，
   底栏坐标偏了约 (16dp, 8dp+navBar)，只是当时"没有模糊"掩盖了错位。现在外壳只管留白与阴影，
   胶囊本体是里面那层，坐标才准。

顺带定死（沿用 ADR 0005）：elevation 阴影只能配不透明底色；玻璃档不挂 `Modifier.shadow`。

## 后果

- 好：模糊路径与 `Modifier.blur` 完全一致，出问题能靠"切玻璃档看字还认不认得出来"判定。
- 好：`glass` 开关同时决定"录不录图层"，API < 31 直接按「轻量」画，不录也不糊。
- 坏：玻璃档仍是每帧一份额外的全屏图层绘制 + 一小块离屏模糊，低端机要盯帧率。
- 诊断：真机没有 logcat，`TabsRoot` 在玻璃开启时往 `AppLog`（tag `glass`）记一行
  `sdk / 硬件加速 / 模糊半径` 和一行 `底栏 / 内容 / 内容尺寸` 坐标，排障先看这两行。
- 回退：视觉效果切「轻量」即不录层不模糊；再不行退回 tag `apk-m27`（无玻璃版）。

## 未做 / 下一步

- 页头、列表卡片、聊天气泡的玻璃化（复用同一套图层录制）
- 折射/色散（AGSL，API 33+）：暂不做
