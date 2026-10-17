package com.treechat.workbench.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 主题模式。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * M3 之外的自定义语义色。
 *
 * Material 3 没有定义"警告"这个角色（只有 error）。正确做法不是硬编码一个黄色，
 * 而是按 M3 官方的 extended colors 模式补一个 CompositionLocal，
 * 这样它同样能随明暗主题切换。
 */
@Immutable
data class ExtendedColors(
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

private val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        warning = Color(0xFFFFCF5F),
        warningContainer = Color(0xFF5C4300),
        onWarningContainer = Color(0xFFFFDEA8)
    )
}

/** 访问扩展色：AppTheme.extended.warning */
object AppTheme {
    val extended: ExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current
}

private val DarkExtended = ExtendedColors(
    warning = Color(0xFFFFCF5F),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDEA8)
)

private val LightExtended = ExtendedColors(
    warning = Color(0xFF7A5900),
    warningContainer = Color(0xFFFFDEA8),
    onWarningContainer = Color(0xFF261A00)
)

/**
 * 应用主题。
 *
 * @param themeMode 跟随系统 / 强制亮色 / 强制暗色
 * @param dynamicColor 是否启用莫奈（Material You）壁纸取色。
 *        **需要 Android 12（API 31）及以上**；在 Android 10 / 11 上此参数会被忽略，
 *        自动回落到 palette 指定的预设配色 —— 而不是崩溃。
 * @param palette 预设主题色。仅在不使用壁纸取色时生效。
 */
@Composable
fun TreeChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    palette: ThemePalette = ThemePalette.DEFAULT,
    /**
     * 全局字号缩放倍数（1.0 = 不缩放）。
     *
     * 由 `AppSettings.fontScale` 提供，取值已被钳制在
     * `[MIN_FONT_SCALE, MAX_FONT_SCALE]` 内（见 AppSettings.clampFontScale）。
     */
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        dynamicColor && supportsDynamic -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> generateSchemeFromSeed(
            seedArgb = palette.seedArgb.toInt(),
            dark = darkTheme,
            monochrome = palette.monochrome
        )
    }

    val extended = if (darkTheme) DarkExtended else LightExtended

    // 全局字号缩放：只缩放每一个 TextStyle 的字号与行高，
    // **不动 LocalDensity**（那会连带放大图标与间距，把布局撑变形）。
    // 具体取舍与覆盖边界见 TypeScale.kt 的文档。
    val scaledTypography = rememberScaledTypography(TreeChatTypography, fontScale)

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = scaledTypography,
            shapes = TreeChatShapes,
            content = content
        )
    }
}

// ============================================================================
// 过渡期桥接（临时）
// ============================================================================
//
// 下面这些名字是 M3 化之前遗留在各 UI 文件里的旧颜色常量。
// 它们全部改为读取 MaterialTheme.colorScheme，因此**行为已经是 Material 的**
// （会随明暗主题与动态取色变化），只是调用点还写着旧名字。
//
// 注意：这些是 @Composable getter，只能在 Composable 上下文里读；
// 在 Canvas / remember / 普通工具函数里使用会因为读不到 CompositionLocal
// 而编译失败，需要先把值取出来再传进去。
//
// 待 TreeTopologyView / FloatingWindowCard / SettingsDialog 三个文件
// 完成迁移后，这一整段应当删除。
// ---------------------------------------------------------------------------

private val ColorSchemeCompat: androidx.compose.material3.ColorScheme
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme

val DarkBackground: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.background

val DarkSurface: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.surfaceContainer

val DarkSurfaceVariant: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.surfaceContainerHigh

val WindowBorderColor: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.outlineVariant

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.onSurface

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.onSurfaceVariant

val TextMuted: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.outline

val AccentNeonGreen: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.primary

val AccentIceBlue: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.tertiary

val DangerRed: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.error

val WarningYellow: Color
    @Composable @ReadOnlyComposable get() = AppTheme.extended.warning

val AccentCyan: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.tertiary

val AccentGold: Color
    @Composable @ReadOnlyComposable get() = AppTheme.extended.warning

val AppBackground: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.background

val CardSurface: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.surfaceContainer

val PanelSurface: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.surfaceContainerHigh

val DividerDark: Color
    @Composable @ReadOnlyComposable get() = ColorSchemeCompat.outlineVariant
