package com.treechat.workbench.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Markdown 渲染所需的颜色。
 *
 * 显式传参而不是在函数里读 MaterialTheme：解析与渲染函数大多是非 Composable 的
 * （它们可能在 Canvas、remember 或普通工具函数里被调用），
 * 在那些位置读 CompositionLocal 会直接编译失败。把颜色当成数据传进来，
 * 也顺带让这套渲染器不依赖主题实现，便于复用。
 *
 * ## 这个文件为什么只剩配色了（v1.8.0）
 *
 * 原来的 MarkdownText / 各块渲染组件已删除，渲染迁到
 * `ui.selection.SelectableMarkdownText`。原因：用户要求"长按文本不调系统
 * 选取、支持字符级自选并提问/高亮"，而**选区必须能换算回源 Markdown 偏移**
 * 才能把高亮存进库。老渲染器无法提供这个映射。
 *
 * 之所以整体删掉而不是保留两套：两份渲染并存时，配色、行距、圆角任何一处
 * 改动都只改一边，界面上就会出现"同一条消息在两种状态下长得不一样"，
 * 而这类漂移极难被发现。宁可只留一份。
 *
 * 块切分与偏移映射见同目录的 MarkdownOffsets.kt。
 */
@Immutable
data class MdColors(
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val link: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val codeBlockBg: Color,
    val codeBlockHeaderBg: Color,
    val divider: Color,
    val quoteBar: Color,
    val tableHeaderBg: Color,
    /**
     * 「同文本超链接」的强调色：点开它 → 弹出该分支的小窗。
     *
     * 刻意**不用** [link]（那是 Markdown 的 URL 链接色，取自 tertiary）：
     * 两种标记必须一眼可辨。这里取 primary（应用品牌色），
     * 于是正文里的可点内容只有一种含义：这里有对应的分支。
     */
    val quoteLink: Color
)

/**
 * 取当前主题下的 Markdown 配色。
 *
 * 可见性为 internal 而非 private：选择渲染层
 * （ui.selection.SelectableMarkdown）需要复用同一套配色，否则两处渲染
 * 会对不上色。**不要各写一份**。
 */
@Composable
internal fun rememberMdColors(): MdColors {
    val scheme = MaterialTheme.colorScheme
    return MdColors(
        text = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textMuted = scheme.outline,
        accent = scheme.primary,
        link = scheme.tertiary,
        inlineCodeBg = scheme.surfaceContainerHighest,
        inlineCodeText = scheme.primary,
        codeBlockBg = scheme.surfaceContainerLowest,
        codeBlockHeaderBg = scheme.surfaceContainerHigh,
        divider = scheme.outlineVariant,
        quoteBar = scheme.tertiary,
        tableHeaderBg = scheme.surfaceContainerHigh,
        quoteLink = scheme.primary
    )
}
