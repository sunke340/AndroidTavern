package app.tavern

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// 主题：一套「骨架 + 可换色板」。
//
// 为什么不用 Android 12 的动态取色：主题色是用户在这里主动选的，
// 动态取色会让选择失效（系统按壁纸覆盖），所以关掉，颜色完全由下面的色板决定。
// ---------------------------------------------------------------------------

/** 深浅模式。默认跟随系统。 */
enum class ThemeMode(val label: String) {
    Follow("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

/**
 * 一套色板。浅色/深色各一份，语义固定：
 * - `primary`：按钮、选中态、强调文字
 * - `background` / `surface`：页面底色 / 卡片底色
 * - `divider` / `cardBorder`：1px 分隔线与描边（C 方向的"线感"就靠这两个）
 * - `userBubble`：用户消息底色，必须与 `onUserBubble` 有足够对比
 */
data class ThemePalette(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val divider: Color,
    val cardBorder: Color,
    val userBubble: Color,
    val onUserBubble: Color,
)

/** 主题预设。以后加新配色只需要在这里加一个枚举项。 */
enum class ThemePreset(val label: String, val light: ThemePalette, val dark: ThemePalette) {

    Forest(
        label = "森林绿",
        light = ThemePalette(
            primary = Color(0xFF2E7D5B),
            onPrimary = Color(0xFFFFFFFF),
            background = Color(0xFFF8F8F5),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEFF1EC),
            onBackground = Color(0xFF17191D),
            onSurface = Color(0xFF17191D),
            onSurfaceVariant = Color(0xFF6E7278),
            outline = Color(0xFFC9DED2),
            divider = Color(0xFFE6E5DF),
            cardBorder = Color(0xFFE0E0DA),
            userBubble = Color(0xFFE3F1E8),
            onUserBubble = Color(0xFF133620),
        ),
        dark = ThemePalette(
            primary = Color(0xFF7BD88F),
            onPrimary = Color(0xFF0B1C12),
            background = Color(0xFF0F1115),
            surface = Color(0xFF171B21),
            surfaceVariant = Color(0xFF232A34),
            onBackground = Color(0xFFEDEFF3),
            onSurface = Color(0xFFEDEFF3),
            onSurfaceVariant = Color(0xFF9BA3AF),
            outline = Color(0xFF3A4A3F),
            divider = Color(0xFF1D2128),
            cardBorder = Color(0xFF232A34),
            userBubble = Color(0xFF15281C),
            onUserBubble = Color(0xFFD6F5DE),
        ),
    ),

    Amber(
        label = "暖棕",
        light = ThemePalette(
            primary = Color(0xFF8A5A2B),
            onPrimary = Color(0xFFFFFFFF),
            background = Color(0xFFFAF7F2),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF4EEE6),
            onBackground = Color(0xFF1C1611),
            onSurface = Color(0xFF1C1611),
            onSurfaceVariant = Color(0xFF746A5F),
            outline = Color(0xFFE3D3BE),
            divider = Color(0xFFE9E2D6),
            cardBorder = Color(0xFFE5DCCE),
            userBubble = Color(0xFFF6E9D8),
            onUserBubble = Color(0xFF3B2410),
        ),
        dark = ThemePalette(
            primary = Color(0xFFE0B27A),
            onPrimary = Color(0xFF231605),
            background = Color(0xFF12110F),
            surface = Color(0xFF1A1816),
            surfaceVariant = Color(0xFF26221E),
            onBackground = Color(0xFFF0EAE2),
            onSurface = Color(0xFFF0EAE2),
            onSurfaceVariant = Color(0xFFA79C8F),
            outline = Color(0xFF4A3E2E),
            divider = Color(0xFF221F1B),
            cardBorder = Color(0xFF302B25),
            userBubble = Color(0xFF2A2118),
            onUserBubble = Color(0xFFF3E2C9),
        ),
    ),

    Slate(
        label = "石墨蓝",
        light = ThemePalette(
            primary = Color(0xFF334E68),
            onPrimary = Color(0xFFFFFFFF),
            background = Color(0xFFF7F8FA),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEDF0F3),
            onBackground = Color(0xFF161A1E),
            onSurface = Color(0xFF161A1E),
            onSurfaceVariant = Color(0xFF65707B),
            outline = Color(0xFFC7D2DC),
            divider = Color(0xFFE1E6EB),
            cardBorder = Color(0xFFDDE2E7),
            userBubble = Color(0xFFE5ECF3),
            onUserBubble = Color(0xFF16283A),
        ),
        dark = ThemePalette(
            primary = Color(0xFF8FB3D9),
            onPrimary = Color(0xFF0A1520),
            background = Color(0xFF0F1216),
            surface = Color(0xFF171C22),
            surfaceVariant = Color(0xFF222A33),
            onBackground = Color(0xFFE8EDF2),
            onSurface = Color(0xFFE8EDF2),
            onSurfaceVariant = Color(0xFF9FAAB6),
            outline = Color(0xFF3A4756),
            divider = Color(0xFF1C222A),
            cardBorder = Color(0xFF232B34),
            userBubble = Color(0xFF16222E),
            onUserBubble = Color(0xFFD5E6F5),
        ),
    ),

    Violet(
        label = "紫罗兰",
        light = ThemePalette(
            primary = Color(0xFF6D5BD0),
            onPrimary = Color(0xFFFFFFFF),
            background = Color(0xFFFAF9FC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF1EFF8),
            onBackground = Color(0xFF1A1723),
            onSurface = Color(0xFF1A1723),
            onSurfaceVariant = Color(0xFF6E6880),
            outline = Color(0xFFD9D3EE),
            divider = Color(0xFFE9E5F2),
            cardBorder = Color(0xFFE4DFF1),
            userBubble = Color(0xFFEDE9FB),
            onUserBubble = Color(0xFF2B2050),
        ),
        dark = ThemePalette(
            primary = Color(0xFFB4A6FF),
            onPrimary = Color(0xFF1B1430),
            background = Color(0xFF0F0E14),
            surface = Color(0xFF17151F),
            surfaceVariant = Color(0xFF232030),
            onBackground = Color(0xFFEFEDF5),
            onSurface = Color(0xFFEFEDF5),
            onSurfaceVariant = Color(0xFFA9A3BC),
            outline = Color(0xFF3A3450),
            divider = Color(0xFF1E1B27),
            cardBorder = Color(0xFF262233),
            userBubble = Color(0xFF201B33),
            onUserBubble = Color(0xFFE4DDFF),
        ),
    ),
}


/** 当前生效的色板（组件里取 `LocalTavernPalette.current.divider` 之类）。 */
val LocalTavernPalette = staticCompositionLocalOf { ThemePreset.Violet.light }

@Composable
fun TavernTheme(
    mode: ThemeMode = ThemeMode.Follow,
    preset: ThemePreset = ThemePreset.Violet,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.Follow -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val palette = if (dark) preset.dark else preset.light

    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            background = palette.background,
            onBackground = palette.onBackground,
            surface = palette.surface,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceVariant,
            onSurfaceVariant = palette.onSurfaceVariant,
            outline = palette.outline,
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            background = palette.background,
            onBackground = palette.onBackground,
            surface = palette.surface,
            onSurface = palette.onSurface,
            surfaceVariant = palette.surfaceVariant,
            onSurfaceVariant = palette.onSurfaceVariant,
            outline = palette.outline,
        )
    }

    CompositionLocalProvider(LocalTavernPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
