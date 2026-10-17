package com.treechat.workbench.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * 全局字号缩放。
 *
 * ## 为什么缩放 Typography，而不是改 LocalDensity
 *
 * 有一种更省事的做法：包一层 `CompositionLocalProvider(LocalDensity provides
 * Density(density.density, fontScale = userScale))`。但 `LocalDensity` 的 fontScale
 * 会**同时**影响所有以 `sp` 表达的值 —— 包括图标尺寸、内边距、圆角等按 sp 写的地方
 * （本项目就有若干处 `fontSize = 12.sp` 之外的 sp 用法），
 * 结果是"字大了、卡片和图标也跟着撑变形"，而不是单纯把字放大。
 *
 * 这里只缩放 `MaterialTheme.typography` 里的每个 `TextStyle`，
 * 所以**只影响文字**，布局尺寸保持不变。
 *
 * ## 已知覆盖边界（如实说明，避免高估效果）
 *
 * 本函数只覆盖**走 `MaterialTheme.typography.*` 取字号的文字**。
 * 项目里少量直接从 Typography 之外取字号的地方不受影响，主要有：
 *   - `ChatMessageView.kt` 里把 `baseFontSize` 传给 Markdown 渲染引擎，
 *     它本身取自 `MaterialTheme.typography.bodyMedium.fontSize` —— 会跟随；
 *   - 各处显式写死的 `fontSize = 11.sp / 12.sp`（多为徽标、计数等小字）。
 * 若日后要求"绝对全量跟随"，需要把这些显式 sp 也改为从 typography 取值。
 */
@Composable
fun rememberScaledTypography(base: Typography, scale: Float): Typography =
    remember(base, scale) {
        if (scale == 1f) base
        else Typography(
            displayLarge = base.displayLarge.scaled(scale),
            displayMedium = base.displayMedium.scaled(scale),
            displaySmall = base.displaySmall.scaled(scale),
            headlineLarge = base.headlineLarge.scaled(scale),
            headlineMedium = base.headlineMedium.scaled(scale),
            headlineSmall = base.headlineSmall.scaled(scale),
            titleLarge = base.titleLarge.scaled(scale),
            titleMedium = base.titleMedium.scaled(scale),
            titleSmall = base.titleSmall.scaled(scale),
            bodyLarge = base.bodyLarge.scaled(scale),
            bodyMedium = base.bodyMedium.scaled(scale),
            bodySmall = base.bodySmall.scaled(scale),
            labelLarge = base.labelLarge.scaled(scale),
            labelMedium = base.labelMedium.scaled(scale),
            labelSmall = base.labelSmall.scaled(scale)
        )
    }

/**
 * 按倍数缩放一个 TextStyle 的字号与行高。
 *
 * ⚠️ 两个必须挡的坑：
 *  1. `TextUnit.Unspecified`：`Unspecified * 1.5f` 会抛
 *     `IllegalArgumentException`。Typography 里多数样式有显式字号，但不能假设都有。
 *  2. 行高缺省时若只放大字号，行距会显得偏挤，所以行高一并缩放。
 */
private fun TextStyle.scaled(scale: Float): TextStyle {
    val newSize: TextUnit = if (fontSize.isUnspecified) fontSize else fontSize * scale
    val newLineHeight: TextUnit =
        if (lineHeight.isUnspecified) lineHeight else lineHeight * scale
    // letterSpacing 刻意**不缩放**：字距是排版细节，放大后反而显得松散。
    return copy(fontSize = newSize, lineHeight = newLineHeight)
}
