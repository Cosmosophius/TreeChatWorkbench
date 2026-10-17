package com.treechat.workbench.ui.selection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 自建文本选择工具条（**替代系统选取菜单**）。
 *
 * 为什么不用系统菜单：用户要求"长按不调用系统选取，改为可自行选择文本并
 * 针对其提问、复制、高亮"。系统菜单只给复制/全选，插不进"提问"和"高亮"，
 * 也给不出选区对应的源 Markdown 偏移。
 *
 * 位置：固定在聊天区底部而不是浮在选区上方。原因是浮在选区上方需要把
 * 文本行的坐标从"块坐标系"换算到"列表坐标系"再到"屏幕坐标系"，
 * 三层换算里任何一层在滚动时都会产生跳动；固定在底部则只依赖聊天区的
 * 布局，滚动时位置恒定，且不会盖住用户正在看的那几行。
 *
 * @param quote 当前选中的可见文本（用于预览）
 * @param visible 是否显示
 */
@Composable
fun SelectionToolbar(
    visible: Boolean,
    quote: String,
    onAsk: () -> Unit,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    highlightActive: Boolean = false,
    onUndoHighlight: (() -> Unit)? = null,
    onEtymology: (() -> Unit)? = null
) {
    if (!visible) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已选 ${quote.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = quote.replace("\n", " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消选择",
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarAction(Icons.Default.HelpOutline, "针对此段提问", onAsk, primary = true)
                ToolbarAction(Icons.Default.ContentCopy, "复制", onCopy)
                if (onEtymology != null) {
                    ToolbarAction(Icons.Default.Translate, "词源·音标", onEtymology)
                }
                ToolbarAction(
                    Icons.Default.Brush,
                    if (highlightActive) "取消高亮" else "高亮",
                    onHighlight,
                    active = highlightActive
                )
                if (onUndoHighlight != null) {
                    ToolbarAction(Icons.AutoMirrored.Filled.Undo, "撤销高亮", onUndoHighlight)
                }
                ToolbarAction(Icons.Default.SelectAll, "全选", onSelectAll)
            }
        }
    }
}

@Composable
private fun ToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    active: Boolean = false
) {
    val tint = when {
        primary -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    TextButton(
        onClick = onClick,
        modifier = Modifier.widthIn(min = 0.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1
        )
    }
}

/** 供调用方把选区文本裁成一个适合做分支标题/引用卡片的短串。 */
fun String.asQuotePreview(maxChars: Int = 40): String {
    val flat = replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= maxChars) flat else flat.take(maxChars) + "…"
}

/**
 * 类 Word / 系统级浮动选区操作栏。
 * 紧贴选中文字正上方（或正下方）悬浮展示，实现就地提问、复制与高亮。
 */
@Composable
fun FloatingSelectionToolbar(
    visible: Boolean,
    anchorRect: Rect,
    onAsk: () -> Unit,
    onCopy: () -> Unit,
    /**
     * 划线（高亮）动作。**可空**：不传（null）就不显示这个按钮。
     * 对话界面照常传（持久高亮是那里的功能）；
     * 阅读器 2026-10-17 起移除划线/笔记入口 —— 用户明确不要，
     * 读书笔记的采集只保留手写批注层与右侧边栏。
     */
    onHighlight: (() -> Unit)? = null,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit,
    highlightActive: Boolean = false,
    onUndoHighlight: (() -> Unit)? = null,
    onEtymology: (() -> Unit)? = null,
    // ---- 以下参数均为**带默认值的可选参数**，对话界面的现有调用点不传，行为完全不变 ----
    /** 文字文档阅读器用：追加「笔记 / 翻译 / 句法」动作（对话界面不传，保持原样）。 */
    onNote: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    onSyntax: (() -> Unit)? = null,
    /** 复制后的即时反馈：为 true 时复制按钮显示为「已复制」（与对话界面底部动作同一套反馈）。 */
    copyFeedback: Boolean = false,
    /** 是否显示「全选」动作（文字文档阅读器不显示；默认显示，对话界面行为不变）。 */
    showSelectAll: Boolean = true,
    /** 提问动作的文案（阅读器用「提问」，默认沿用对话界面的「针对此段提问」）。 */
    askLabel: String = "针对此段提问",
    /** 高亮动作的文案与激活态文案（阅读器用「划线」）。 */
    highlightLabel: String = "高亮",
    highlightActiveLabel: String = "取消高亮"
) {
    if (!visible) return

    val positionProvider = remember(anchorRect) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val anchorCenterX = anchorBounds.left + anchorRect.center.x.toInt()
                val targetX = (anchorCenterX - popupContentSize.width / 2)
                    .coerceIn(16, (windowSize.width - popupContentSize.width - 16).coerceAtLeast(16))

                val spacingPx = 28
                val topCandidate = anchorBounds.top + anchorRect.top.toInt() - popupContentSize.height - spacingPx
                val targetY = if (topCandidate >= 32) {
                    topCandidate
                } else {
                    anchorBounds.top + anchorRect.bottom.toInt() + spacingPx
                }
                return IntOffset(targetX, targetY)
            }
        }
    }

    // ⚠️ 关键修复：必须显式设置 dismissOnClickOutside = false 且 onDismissRequest = null！
    // Compose Popup 默认 dismissOnClickOutside = true，一旦用户伸手去触摸悬浮栏外部的
    // 水滴手柄准备拖动扩展选区，Android PopupWindow 就会判定为"外部点击"并立即回调
    // onDismissRequest -> controller.clear()，导致一碰手柄选词就瞬间消失！
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = null,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarAction(Icons.Default.HelpOutline, askLabel, onAsk, primary = true)
                ToolbarAction(
                    if (copyFeedback) Icons.Default.Check else Icons.Default.ContentCopy,
                    if (copyFeedback) "已复制" else "复制",
                    onCopy,
                    active = copyFeedback
                )
                if (onEtymology != null) {
                    ToolbarAction(Icons.Default.Translate, "词源·音标", onEtymology)
                }
                if (onHighlight != null) {
                    ToolbarAction(
                        Icons.Default.Brush,
                        if (highlightActive) highlightActiveLabel else highlightLabel,
                        onHighlight,
                        active = highlightActive
                    )
                }
                if (onNote != null) {
                    ToolbarAction(Icons.AutoMirrored.Filled.NoteAdd, "笔记", onNote)
                }
                if (onTranslate != null) {
                    ToolbarAction(Icons.Default.Translate, "翻译", onTranslate, primary = true)
                }
                if (onSyntax != null) {
                    ToolbarAction(Icons.Default.AccountTree, "句法", onSyntax)
                }
                if (onUndoHighlight != null) {
                    ToolbarAction(Icons.AutoMirrored.Filled.Undo, "撤销高亮", onUndoHighlight)
                }
                if (showSelectAll) {
                    ToolbarAction(Icons.Default.SelectAll, "全选", onSelectAll)
                }
                ToolbarAction(Icons.Default.Close, "取消", onDismiss)
            }
        }
    }
}
