package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize

/**
 * 组件预览（**只在 debug 包里出现**，入口在 设置 → 关于）。
 *
 * 为什么要这一页：调材质参数现在的回路是"改常量 → 出包 4 分钟 → 装机 → 用肉眼看"，
 * 而且一次只能比一个值 —— 2026-11-24 那次 `faceWhite` 改了看不出效果，一半原因就是没法并排对比。
 *
 * 三个部分：
 * 1. **材质**：胶囊 / 发送钮 / 动作单面 / 页头带 / 卡片磨砂条，都压在彩色装饰上（白底上看不出差别）；
 * 2. **材质参数**：faceWhite / refraction / rimAlpha 三根滑杆，松手才换 spec（拖动中每帧重建
 *    着色器会卡），下面直接给出可复制回 `LiquidGlassSpec` 的一行代码；
 * 3. **行样式**：五种 `ListRow` 组合，验行高/内缩分隔线/时间列是否一致。
 *
 * **玻璃样张是真的采样**：装饰底单独录进一份 `GraphicsLayer`（`decor`），再降采样成 `frost`，
 * 样张按窗口坐标去采它 —— 而不是采当前页自己那一帧（那样会自反馈）。所以样张可以随便摆在
 * 普通滚动内容里，不需要像页头/底栏那样躲到录图层外面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val glass = state.visualEffects.effective() == VisualEffects.Glass
    val tune = remember { Tuning() }
    // 只在"松手后的值"变化时才换 spec：拖动中每帧换 = 每帧重建 AGSL 着色器
    val spec = remember(tune.appliedFaceWhite, tune.appliedRefraction, tune.appliedRimAlpha) {
        LiquidGlassSpec.Default.copy(
            faceWhite = tune.appliedFaceWhite,
            refraction = tune.appliedRefraction,
            rimAlpha = tune.appliedRimAlpha,
        )
    }

    GlassDetailScaffold(
        title = "组件预览",
        subtitle = if (glass) "玻璃档" else "轻量档 · 设置→外观 切玻璃",
        onBack = viewModel::closeGallery,
        glass = glass,
        contentTopInset = 0.dp,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 让位拆两半：底部留在外面，顶部挪进滚动层 —— 内容才能从页头底下穿过去，
                // 页头才是"半透明"而不是一条纯色（用户 2026-11-25 第 7 条）
                .padding(bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding() + DetailScrollUnderTop)
                .padding(horizontal = Space.l),
        ) {
            Spacer(Modifier.height(Space.s))
            SectionLabel("材质")
            Spacer(Modifier.height(Space.s))
            MaterialStage(glass = glass, spec = spec)

            Spacer(Modifier.height(Space.l))
            SectionLabel("材质参数")
            Spacer(Modifier.height(Space.s))
            ParamPanel(tune = tune)
            Spacer(Modifier.height(Space.s))
            SecondaryAction(text = "重置为默认", onClick = tune::reset)

            Spacer(Modifier.height(Space.l))
            SectionLabel("行样式")
            Spacer(Modifier.height(Space.s))
            RowStage()

            Spacer(Modifier.height(Space.xl))
        }
    }
}

// ---- 材质样张 -----------------------------------------------------------

/**
 * 装饰底 + 玻璃样张。
 *
 * 装饰底单独录一份图层（只含装饰，不含样张），样张再按窗口坐标采它 ——
 * 这样样张就是"浮在彩色内容上"的真玻璃，而不是采自己那一帧。
 */
@Composable
private fun MaterialStage(glass: Boolean, spec: LiquidGlassSpec) {
    val pageBackground = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary
    val decor = rememberGraphicsLayer()
    val frost = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .onGloballyPositioned { origin = it.positionInWindow() }
                .drawWithContent {
                    if (glass) {
                        decor.record(size = size.toIntSize()) {
                            drawRect(pageBackground)
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(decor)
                        recordFrost(frost, decor, size.toIntSize())
                    } else {
                        drawContent()
                    }
                },
        ) {
            GalleryDecor(Modifier.matchParentSize())
        }

        val backdrop = GlassBackdrop(frost, { origin })
        Column(
            modifier = Modifier.fillMaxWidth().padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassSurface(
                    backdrop = backdrop,
                    glass = glass,
                    shape = RoundedCornerShape(Radius.pill),
                    modifier = Modifier.height(44.dp).width(132.dp),
                    liquid = spec,
                ) {
                    Text(
                        "胶囊",
                        style = RowTitleStyle,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                GlassSurface(
                    backdrop = backdrop,
                    glass = glass,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp),
                    fillColor = primary.copy(alpha = 0.32f),
                    liquid = spec,
                ) {
                    Text(
                        "↑",
                        style = RowTitleStyle,
                        color = primary.copy(alpha = 0.72f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // 动作单：真实那张面用 LiquidGlassSpec.Sheet（0.50），这里跟滑杆联动好并排比
            GlassSurface(
                backdrop = backdrop,
                glass = glass,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxWidth().height(92.dp),
                shadow = false,
                liquid = spec.copy(faceWhite = 0.50f),
            ) {
                Column(
                    modifier = Modifier.padding(Space.m),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text("动作单面 0.50", style = RowTitleStyle)
                    Text("白底上看不出 0.22 与 0.50 的差别", style = RowSubtitleStyle)
                }
            }

            GlassSurface(
                backdrop = backdrop,
                glass = glass,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shadowBottomOnly = true,
                liquid = LiquidGlassSpec.HeaderBand,
            ) {
                Text(
                    "页头带 0.80",
                    style = RowTitleStyle.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = Space.m),
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(Radius.group)),
            ) {
                GalleryDecor(Modifier.matchParentSize())
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .drawBehind {
                            drawRect(surface.copy(alpha = 0.72f))
                            drawRect(Color.White.copy(alpha = 0.28f), size = Size(size.width, 1.dp.toPx()))
                        }
                        .padding(horizontal = Space.m),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("卡片磨砂条 0.72 · 56dp", style = RowTitleStyle)
                    Text("3 个会话 · 内嵌书 1 条", style = RowSubtitleStyle)
                }
            }
        }
    }
}

/** 彩色装饰：横带 + 假文字行 + 圆点 —— 折射/色散/糊都有东西可采。 */
@Composable
private fun GalleryDecor(modifier: Modifier = Modifier) {
    val bands = listOf(
        Color(0xFF6D4BC4),
        Color(0xFFE0A23C),
        Color(0xFF2E8B8B),
        Color(0xFFB54A6A),
        Color(0xFF3A3F5C),
    )
    Box(
        modifier.drawBehind {
            val bandHeight = size.height / bands.size
            bands.forEachIndexed { index, color ->
                drawRect(
                    color = color,
                    topLeft = Offset(0f, index * bandHeight),
                    size = Size(size.width, bandHeight),
                )
            }
            // 假文字行：玻璃边缘的折射要有"细结构"才看得出来
            val lineHeight = 6.dp.toPx()
            val gap = 14.dp.toPx()
            var y = 12.dp.toPx()
            var index = 0
            while (y < size.height - lineHeight) {
                val width = size.width * (if (index % 3 == 0) 0.42f else 0.26f)
                drawRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(size.width * 0.08f, y),
                    size = Size(width, lineHeight),
                )
                y += lineHeight + gap
                index += 1
            }
            // 圆点：看边缘的色散
            val radius = 5.dp.toPx()
            var x = size.width * 0.62f
            while (x < size.width - radius) {
                drawCircle(color = Color.White.copy(alpha = 0.7f), radius = radius, center = Offset(x, size.height * 0.12f))
                drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = radius, center = Offset(x, size.height * 0.86f))
                x += radius * 4
            }
        },
    )
}

// ---- 参数面板 -----------------------------------------------------------

/**
 * 三根滑杆的草稿值 + 已应用值。
 *
 * 分开存的原因：`LiquidGlassSpec` 被 `GlassSurface` 当 `remember` key，拖动中每帧换一个实例
 * 就是每帧重建一次 AGSL 着色器（拖起来会粘）。所以拖动只动草稿，松手才应用。
 */
private class Tuning {
    var faceWhite by mutableFloatStateOf(LiquidGlassSpec.Default.faceWhite)
    var refraction by mutableFloatStateOf(LiquidGlassSpec.Default.refraction)
    var rimAlpha by mutableFloatStateOf(LiquidGlassSpec.Default.rimAlpha)

    var appliedFaceWhite by mutableFloatStateOf(LiquidGlassSpec.Default.faceWhite)
    var appliedRefraction by mutableFloatStateOf(LiquidGlassSpec.Default.refraction)
    var appliedRimAlpha by mutableFloatStateOf(LiquidGlassSpec.Default.rimAlpha)

    fun apply() {
        appliedFaceWhite = faceWhite
        appliedRefraction = refraction
        appliedRimAlpha = rimAlpha
    }

    fun reset() {
        faceWhite = LiquidGlassSpec.Default.faceWhite
        refraction = LiquidGlassSpec.Default.refraction
        rimAlpha = LiquidGlassSpec.Default.rimAlpha
        apply()
    }

    /** 一行能直接粘回 `LiquidGlassSpec.kt` 的代码。 */
    val snippet: String
        get() = "faceWhite = ${fmt(appliedFaceWhite)}f, " +
            "refraction = ${fmt(appliedRefraction)}f, " +
            "rimAlpha = ${fmt(appliedRimAlpha)}f"
}

private fun fmt(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

@Composable
private fun ParamPanel(tune: Tuning) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        TuneSlider("faceWhite", tune.faceWhite, 0f, 1f, { tune.faceWhite = it }, tune::apply)
        TuneSlider("refraction", tune.refraction, 0f, 0.6f, { tune.refraction = it }, tune::apply)
        TuneSlider("rimAlpha", tune.rimAlpha, 0f, 1f, { tune.rimAlpha = it }, tune::apply)
        Text(
            text = "LiquidGlassSpec.Default.copy(${tune.snippet})",
            style = RowMetaStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TuneSlider(
    label: String,
    value: Float,
    from: Float,
    to: Float,
    onValueChange: (Float) -> Unit,
    onApply: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(fmt(value), style = RowMetaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onApply,
            valueRange = from..to,
        )
    }
}

// ---- 行样式 -------------------------------------------------------------

/** 五种行组合：有头像/无头像/只有标题/带时间/带尾部件。 */
@Composable
private fun RowStage() {
    val samples = 5
    Column {
        ListGroup(count = samples) { index, piece ->
            GroupPiece(piece = piece, dividerInset = RowToken.dividerInsetWithAvatar) {
                when (index) {
                    0 -> ListRow(
                        title = "有头像 + 副行 + 时间",
                        subtitle = "你：最后一条消息压成一行",
                        meta = "12:04",
                        leading = { RowAvatar(cardId = "preview", fallbackText = "预") },
                    )

                    1 -> ListRow(
                        title = "只有标题",
                        leading = { RowAvatar(cardId = "preview", fallbackText = "预") },
                    )

                    2 -> ListRow(
                        title = "无头像 + 两行副行",
                        subtitle = "副行默认一行就截断；这里传了 subtitleMaxLines = 2",
                        subtitleMaxLines = 2,
                        meta = "昨天",
                    )

                    3 -> ListRow(
                        title = "带尾部件",
                        subtitle = "行尾可以塞开关或图标",
                        trailing = { Text("开", style = RowMetaStyle) },
                    )

                    else -> ListRow(
                        title = "不可点",
                        subtitle = "onClick 为 null 时不带按压反馈",
                    )
                }
            }
        }
    }
}
