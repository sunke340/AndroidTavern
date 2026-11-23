package app.tavern

import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import app.tavern.data.log.AppLog

/**
 * 玻璃档的系统门槛：**API 33（TIRAMISU）**。
 *
 * 液态玻璃要 `RuntimeShader`（AGSL）做真折射，33 才有。低于它不开放玻璃档 ——
 * 2026-11-23 定的：那套"半透明 + 白描边"的彩色玻璃在真机上不可用，宁可只给轻量。
 */
val glassSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * 液态玻璃的参数。数值是"好调不好猜"的那几个，单位见注释。
 *
 * 参考实现：shuding/liquid-glass 的位移场（SDF → 边缘位移 → 重采样），
 * 加上 Apple WWDC25 讲的三件套：边缘折射、镜面高光、内阴影定厚度。
 */
data class LiquidGlassSpec(
    /**
     * 折射带宽度（dp）：只有离边界这么近的地方才弯。
     *
     * 第一版 7dp 在 44dp 的钮上太宽 —— 整块都像厚玻璃（2026-11-23 用户："玻璃感觉太厚了"）。
     * 5.5dp 大约是直径的 12%，边缘有厚度、中心还是平的。
     */
    val edgeDp: Float = 4.5f,
    /** 折射强度（0..1）：边缘采样点往外偏多少。参照"薄玻璃胶囊"只要 0.22，0.5 以上是厚透镜。 */
    val refraction: Float = 0.22f,
    /** 色散（0..1）：R/B 通道用略微不同的偏移采样，边缘出现很淡的彩色边。0 就是无色散。 */
    val dispersion: Float = 0.03f,
    /**
     * 内侧泛光强度（0..1）。**默认 0：关掉**。
     *
     * **宽度等于 [rimWidthDp]**（和细线同宽，1.6dp），不是原来那样按折射带 4.5dp 铺开 ——
     * 4.5dp 那版读起来是"一层体现厚度的渐变白"，页头上还因为按 `clamp(n.y,0,1)` 分布变成梯形
     * （2026-11-23 用户自己指出）。现在它是细线外面一点点柔光，两者叠在一起才是"边缘受光"。
     */
    val specular: Float = 0.25f,
    /**
     * 玻璃面白底浓度（0..1）。**不透明度主要靠它**：
     * 0.30 时背后内容基本看不见（用户："玻璃太不透明了"）；0.22 是"能看到背后、但被糊过"。
     */
    val faceWhite: Float = 0.22f,
    /**
     * 底边内阴影强度（0..1）。**已经归零**：现在的光照是"上下出高光、左右压暗"，
     * 底部再压一道灰带会破坏上下对称（2026-11-23 用户要求）。留着这个字段只为以后调。
     */
    val innerShadow: Float = 0f,
    /**
     * 边缘细光线的宽度（dp）。参照里的玻璃胶囊就是"一圈 1px 的光边"，靠它立住轮廓，
     * 不是靠厚倒角。别超过 1.5dp，否则又变成描边。
     * 2026-11-23 迭代：1.1 -> 0.73（2/3）-> 0.4（减半、0.1 精度）-> **0.8**（关掉按 band 铺的泛光后
     * 细线单独存在，需要粗一点才看得见）-> 1.6（0.8 时"基本看不到"，翻倍）。
     * **所有玻璃表面共用这一个值**，不要再按表面分别调。
     */
    val rimWidthDp: Float = 1.6f,
    /** 细光线亮度（0..1）：**上方与下方**最亮，左右两侧只剩一线。 */
    val rimAlpha: Float = 0.85f,
    /**
     * 左右两侧的内阴影强度（0..1）。
     *
     * 光照模型是"上下打光"：上下边缘出高光、左右两侧压暗 —— 看起来像一根竖着受光的圆管，
     * 这是胶囊/圆钮最耐看的打光（2026-11-23 用户指定，原来是"左上角单侧光"）。
     */
    val sideShadow: Float = 0.02f,
    /**
     * 高光只画**下方**（且是直的）。
     *
     * 圆钮/胶囊要上下都亮；但整条页头是"一条横边"，四边都亮就变成一颗大胶囊
     * （2026-11-23 用户：深色模式下聊天页页头看着像个大胶囊）。开这个开关后，
     * 亮度 = `clamp(n.y, 0, 1)`：只有下沿亮、左右竖向边完全不亮 —— 线自然就是直的。
     */
    val rimBottomOnly: Boolean = false,
) {
    companion object {
        /**
         * 所有玻璃表面共用的默认参数。
         *
         * **必须是同一个实例**：`GlassSurface` 用 `liquid` 当 `remember` 的 key，
         * 每次重组新建一个 data class 会让着色器每帧重建（实际上线会烧电）。
         */
        val Default = LiquidGlassSpec()

        /**
         * 整条磨砂页头（方案 A）：**磨砂板 + 下沿一条直亮线 + 下沿软影，不做透镜**。
         *
         * 三个量是专门为"横条"定的：
         * - `refraction = 0`：一条直边上做折射没有意义，只会让下面滚过的字变形；
         * - `rimBottomOnly = true`：四边都亮会变成一颗大胶囊，只留下沿；
         * - `faceWhite = 0.80`：**面要稳定** —— 头顶是黑字还是空白，页头都该是同一块浅板
         *   （0.22 时它完全被头顶内容支配，就在浅灰/深灰之间漂）。
         */
        val HeaderBand = LiquidGlassSpec(
            edgeDp = 8f,
            refraction = 0f,
            dispersion = 0f,     // 不折射就不用三通道错开采样，省两次纹理取样
            rimBottomOnly = true,
            faceWhite = 0.80f,
            sideShadow = 0f,
        )
    }
}

/**
 * AGSL 源码。全部结论都落在这一份着色器里：
 *
 * 1. 用**圆角矩形 SDF** 算出"离边界多远"和"法线"；
 * 2. 只有边界一圈（`uEdge`）非零的位移场，把采样点沿法线往外推 —— 这就是透镜折射；
 * 3. 法线朝光的一侧叠一层高光；底边压一道内阴影（不然读不出厚度）。
 *
 * 注意：AGSL 里 `uniform shader uContent` 的名字必须和
 * `RenderEffect.createRuntimeShaderEffect(shader, "uContent")` 的第二个参数一致。
 */
private const val LIQUID_AGSL = """
uniform shader uContent;

uniform float2 uNodeSize;
uniform float2 uShapeSize;
uniform float  uRadius;
uniform float  uEdge;
uniform float  uRefraction;
uniform float  uDispersion;
uniform float  uSpecular;
uniform float  uFaceWhite;
uniform float  uInnerShadow;
uniform float  uRimWidth;
uniform float  uRimAlpha;
uniform float  uSideShadow;
uniform float  uSideWidth;
uniform float3 uFaceColor;
uniform float  uRimMode;
uniform float4 uTint;

float sdRoundRect(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + r;
    float2 qq = max(q, float2(0.0));
    return min(max(q.x, q.y), 0.0) + length(qq) - r;
}

float2 sdfNormal(float2 p, float2 hs, float r) {
    float d0 = sdRoundRect(p, hs, r);
    float dx = sdRoundRect(p + float2(0.75, 0.0), hs, r) - d0;
    float dy = sdRoundRect(p + float2(0.0, 0.75), hs, r) - d0;
    float2 g = float2(dx, dy);
    float len = length(g);
    if (len < 0.0001) {
        return float2(0.0, 0.0);
    }
    return g / len;
}

half4 main(float2 fragCoord) {
    float2 hs = uShapeSize * 0.5;
    float2 origin = (uNodeSize - uShapeSize) * 0.5;
    float2 p = fragCoord - origin - hs;

    float d = sdRoundRect(p, hs, uRadius);
    float2 n = sdfNormal(p, hs, uRadius);

    float distIn = clamp(-d, 0.0, uEdge);
    float band = 1.0 - smoothstep(0.0, uEdge, distIn);
    float inside = 1.0 - smoothstep(0.0, 1.0, d);

    float2 offset = n * (band * inside * uRefraction * 22.0);

    half4 base = uContent.eval(fragCoord + offset);
    if (uDispersion > 0.001) {
        half4 cr = uContent.eval(fragCoord + offset * (1.0 + uDispersion));
        half4 cb = uContent.eval(fragCoord + offset * (1.0 - uDispersion));
        base = half4(cr.r, base.g, cb.b, base.a);
    }

    float3 rgb = float3(base.rgb);
    rgb = mix(rgb, uFaceColor, uFaceWhite);
    rgb = mix(rgb, uTint.rgb, uTint.a);

    // 光从**上方与下方**来：竖向上的边缘（n.y 大）出高光，左右两侧（n.x 大）压暗
    float vertical = abs(n.y);
    float horizontal = abs(n.x);

    // uRimMode > 0.5 = 只下方（页头横条）；否则上下都亮（圆钮/胶囊）
    // 用 step/mix 而不是 if/ternary：AGSL 里少写一个分支更稳
    float bottomOnly = step(0.5, uRimMode);
    float down = clamp(n.y, 0.0, 1.0);
    float lit = mix(0.28 + 0.72 * vertical, down, bottomOnly);
    float glow = mix(vertical, down, bottomOnly);

    // 细光线：薄玻璃靠它立轮廓（横条只在下沿画一条直的）
    float rim = (1.0 - smoothstep(0.0, uRimWidth, distIn)) * inside;
    rgb = mix(rgb, float3(1.0), rim * lit * uRimAlpha);

    // 边缘柔光：**和细线同宽**（uRimWidth），只是把那条线往外晕开一点点。
    // 不要再用 band（折射带 4.5dp）：那会变成一片"厚度渐变白"，页头上还是梯形。
    float halo = 1.0 - smoothstep(0.0, uRimWidth, distIn);
    rgb = rgb + (pow(glow, 6.0) * halo * inside * uSpecular);

    // 左右两侧的内阴影：贴着左右边界最重，往里化开
    float sideMask = horizontal * (1.0 - smoothstep(0.0, uSideWidth, distIn)) * inside;
    rgb = rgb * (1.0 - uSideShadow * sideMask);

    float t = clamp((fragCoord.y - origin.y) / uShapeSize.y, 0.0, 1.0);
    float inner = smoothstep(0.55, 1.0, t) * band * inside;
    rgb = rgb * (1.0 - uInnerShadow * inner);

    return half4(half3(rgb), 1.0);
}
"""

/**
 * 建一个"把节点自己的绘制内容当底片"的折射效果。
 *
 * @param nodeSize 节点尺寸（**含**模糊需要的每边外扩 [padPx]）
 * @param shapeSize 形状尺寸（不含外扩）——SDF 按它算，坐标原点是节点中心
 */
@SuppressLint("NewApi") // 调用点由 [glassSupported] 把关，低于 33 这里直接返回 null
internal fun buildLiquidGlassEffect(
    nodeSize: IntSize,
    shapeSize: IntSize,
    shape: Shape,
    density: Density,
    tint: Color,
    tintAlpha: Float,
    /** 玻璃面的底色（跟主题走）：深色模式下必须是深色，写死白会让整块玻璃发灰。 */
    faceColor: Color,
    spec: LiquidGlassSpec,
): ComposeRenderEffect? {
    if (!glassSupported) return null
    if (nodeSize.width <= 0 || nodeSize.height <= 0) return null
    return runCatching {
        val shader = RuntimeShader(LIQUID_AGSL)
        shader.setFloatUniform("uNodeSize", nodeSize.width.toFloat(), nodeSize.height.toFloat())
        shader.setFloatUniform("uShapeSize", shapeSize.width.toFloat(), shapeSize.height.toFloat())
        shader.setFloatUniform("uRadius", cornerRadiusOf(shape, shapeSize, density))
        shader.setFloatUniform("uEdge", spec.edgeDp * density.density)
        shader.setFloatUniform("uRefraction", spec.refraction)
        shader.setFloatUniform("uDispersion", spec.dispersion)
        shader.setFloatUniform("uSpecular", spec.specular)
        shader.setFloatUniform("uFaceWhite", spec.faceWhite)
        shader.setFloatUniform("uInnerShadow", spec.innerShadow)
        shader.setFloatUniform("uRimWidth", spec.rimWidthDp * density.density)
        shader.setFloatUniform("uRimAlpha", spec.rimAlpha)
        shader.setFloatUniform("uSideShadow", spec.sideShadow)
        // 侧阴影从边界往里化开的距离：比折射带略窄，只贴边一圈（2026-11-23 用户："左右两侧阴影太大"）
        shader.setFloatUniform("uSideWidth", spec.edgeDp * 1.0f * density.density)
        shader.setFloatUniform("uFaceColor", faceColor.red, faceColor.green, faceColor.blue)
        shader.setFloatUniform("uRimMode", if (spec.rimBottomOnly) 1f else 0f)
        shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue, tintAlpha)
        RenderEffect.createRuntimeShaderEffect(shader, "uContent").asComposeRenderEffect()
    }.onFailure {
        // 真机没 logcat：着色器编译失败必须留痕，否则只会看到"按钮没效果"
        AppLog.e("glass", "液态玻璃着色器失败（退回描边样式）", it)
    }.getOrNull()
}

/** 形状在当前尺寸下的圆角半径；非圆角形状返回 0（SDF 会退化成矩形）。 */
private fun cornerRadiusOf(shape: Shape, size: IntSize, density: Density): Float {
    val outline = shape.createOutline(
        Size(size.width.toFloat(), size.height.toFloat()),
        androidx.compose.ui.unit.LayoutDirection.Ltr,
        density,
    )
    return (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
}
