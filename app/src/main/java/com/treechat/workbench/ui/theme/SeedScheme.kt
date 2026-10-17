package com.treechat.workbench.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette

/**
 * 主题色预设。
 *
 * 用户选的只是一个**种子色**，完整配色由 Material 官方色彩算法（HCT 色调映射）
 * 推导出来，所以无论选哪个颜色，明暗配对与对比度都自动符合 M3 规范 ——
 * 这不是"从调色板里挑几个好看的十六进制"，而是 Google 生成配色的同一套算法。
 */
enum class ThemePalette(
    val displayName: String,
    /** 用 Long 承载：0xFF28C840 这类值超出 Int 正数范围，写成 Int 会编译不过。 */
    val seedArgb: Long,
    /** 单色模式：强制极低色度，用于"素净"风格。 */
    val monochrome: Boolean = false
) {
    DEFAULT_GREEN("青绿", 0xFF28C840),
    BLUE("海蓝", 0xFF1A73E8),
    PURPLE("紫罗兰", 0xFF6750A4),
    ORANGE("暖橙", 0xFFE8710A),
    RED("绯红", 0xFFD93025),
    TEAL("松石", 0xFF00897B),
    PINK("樱粉", 0xFFD01884),
    INDIGO("靛青", 0xFF3F51B5),
    MONOCHROME("素净", 0xFF5A5A5A, monochrome = true);

    companion object {
        val DEFAULT = DEFAULT_GREEN

        fun fromName(name: String?): ThemePalette =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 从种子色生成一套完整的 M3 配色。
 *
 * 色调取值来自 Material 3 规范（Tonal Spot 方案）：
 * 浅色主题下 primary 取 tone 40、容器取 tone 90；深色主题下反过来取 80 / 30。
 * 只有严格按这套配对，前景色才保证落在背景色上有足够对比度。
 *
 * @param seedArgb 种子色（ARGB）
 * @param dark 是否深色主题
 * @param monochrome 是否强制去色
 */
fun generateSchemeFromSeed(
    seedArgb: Int,
    dark: Boolean,
    monochrome: Boolean = false
): ColorScheme {
    val seed = Hct.fromInt(seedArgb)
    val seedHue = seed.hue
    val seedChroma = seed.chroma

    // 主色保持较高色度，保证强调色足够醒目；单色模式下压到接近 0
    val primaryChroma = if (monochrome) 0.0 else maxOf(seedChroma, 48.0)
    val secondaryChroma = if (monochrome) 0.0 else 16.0
    val tertiaryChroma = if (monochrome) 0.0 else 24.0
    val neutralChroma = if (monochrome) 0.0 else 4.0
    val neutralVariantChroma = if (monochrome) 0.0 else 8.0

    val primary = TonalPalette.fromHueAndChroma(seedHue, primaryChroma)
    val secondary = TonalPalette.fromHueAndChroma(seedHue, secondaryChroma)
    // 第三色相偏 60°：M3 用它在视觉上区分次要强调元素（如链接、图标）
    val tertiary = TonalPalette.fromHueAndChroma((seedHue + 60.0) % 360.0, tertiaryChroma)
    val neutral = TonalPalette.fromHueAndChroma(seedHue, neutralChroma)
    val neutralVariant = TonalPalette.fromHueAndChroma(seedHue, neutralVariantChroma)

    // 错误色刻意保持高色度，即使在"素净"模式下也不去色 ——
    // 删除类危险操作如果变成灰色，用户会失去视觉警示。
    val error = TonalPalette.fromHueAndChroma(25.0, 84.0)

    return if (dark) {
        darkColorScheme(
            primary = primary.at(80),
            onPrimary = primary.at(20),
            primaryContainer = primary.at(30),
            onPrimaryContainer = primary.at(90),

            secondary = secondary.at(80),
            onSecondary = secondary.at(20),
            secondaryContainer = secondary.at(30),
            onSecondaryContainer = secondary.at(90),

            tertiary = tertiary.at(80),
            onTertiary = tertiary.at(20),
            tertiaryContainer = tertiary.at(30),
            onTertiaryContainer = tertiary.at(90),

            error = error.at(80),
            onError = error.at(20),
            errorContainer = error.at(30),
            onErrorContainer = error.at(90),

            background = neutral.at(10),
            onBackground = neutral.at(90),
            surface = neutral.at(10),
            onSurface = neutral.at(90),
            surfaceVariant = neutralVariant.at(30),
            onSurfaceVariant = neutralVariant.at(80),

            surfaceContainerLowest = neutral.at(4),
            surfaceContainerLow = neutral.at(10),
            surfaceContainer = neutral.at(12),
            surfaceContainerHigh = neutral.at(17),
            surfaceContainerHighest = neutral.at(22),
            surfaceDim = neutral.at(6),
            surfaceBright = neutral.at(24),

            outline = neutralVariant.at(60),
            outlineVariant = neutralVariant.at(30),

            inverseSurface = neutral.at(90),
            inverseOnSurface = neutral.at(20),
            inversePrimary = primary.at(40),
            scrim = neutral.at(0)
        )
    } else {
        lightColorScheme(
            primary = primary.at(40),
            onPrimary = primary.at(100),
            primaryContainer = primary.at(90),
            onPrimaryContainer = primary.at(10),

            secondary = secondary.at(40),
            onSecondary = secondary.at(100),
            secondaryContainer = secondary.at(90),
            onSecondaryContainer = secondary.at(10),

            tertiary = tertiary.at(40),
            onTertiary = tertiary.at(100),
            tertiaryContainer = tertiary.at(90),
            onTertiaryContainer = tertiary.at(10),

            error = error.at(40),
            onError = error.at(100),
            errorContainer = error.at(90),
            onErrorContainer = error.at(10),

            background = neutral.at(99),
            onBackground = neutral.at(10),
            surface = neutral.at(99),
            onSurface = neutral.at(10),
            surfaceVariant = neutralVariant.at(90),
            onSurfaceVariant = neutralVariant.at(30),

            surfaceContainerLowest = neutral.at(100),
            surfaceContainerLow = neutral.at(96),
            surfaceContainer = neutral.at(94),
            surfaceContainerHigh = neutral.at(92),
            surfaceContainerHighest = neutral.at(90),
            surfaceDim = neutral.at(87),
            surfaceBright = neutral.at(98),

            outline = neutralVariant.at(50),
            outlineVariant = neutralVariant.at(80),

            inverseSurface = neutral.at(20),
            inverseOnSurface = neutral.at(95),
            inversePrimary = primary.at(80),
            scrim = neutral.at(0)
        )
    }
}

/** 取某个色调的 ARGB 颜色。 */
private fun TonalPalette.at(tone: Int): Color = Color(tone(tone))

/** 供界面预览用：取种子色本身。 */
fun ThemePalette.previewColor(): Color = Color(seedArgb.toInt())

/**
 * 由 Monet（壁纸取色）得到的配色反推一个种子色。
 *
 * 用途：Android 12+ 上 Compose 只提供一种壁纸配色（Tonal Spot），
 * 想再给出其它配色变体就需要源色。这里从已生成的配色里取主色作为色相参考 ——
 * 属于近似，色相与壁纸一致，明度按各变体自己的规则重算。
 */
fun ColorScheme.deriveSeedArgb(): Int = primary.toArgb()
