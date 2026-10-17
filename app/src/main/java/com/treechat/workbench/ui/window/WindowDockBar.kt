package com.treechat.workbench.ui.window

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * 侧边停靠坞：被最小化的小窗在这里收成大头钉胶囊。
 *
 * 与上一版的区别（用户要求「最小化的右侧标签改为可以拖动改位置」）：
 * 旧实现是一个 `Arrangement.spacedBy(8.dp, CenterVertically)` 的 Column ——
 * 位置完全由"有几个标签"决定，用户没法摆。现在每个标签的纵向位置由
 * `FloatingWindowItem.dockFraction`（0..1 的比例）决定，容器内用 offset
 * 绝对定位，于是可以任意上下拖动。
 *
 * 为什么把容器做成全高 Box 而不是继续用 Column：
 * 绝对定位需要知道容器高度才能把比例换算成像素。全高 Box 的高度就是
 * 视口高度，配合 onSizeChanged 拿到 px，换算无歧义；若容器高度随内容变化，
 * "50% 处"会随着标签增减而漂移，用户拖好的位置每次都会被改写。
 */
@Composable
fun WindowDockBar(
    manager: FloatingWindowManager,
    side: DockSide,
    modifier: Modifier = Modifier
) {
    val docked = manager.dockedOn(side)
    if (docked.isEmpty()) return

    val density = LocalDensity.current
    var containerHeightPx by remember { mutableIntStateOf(0) }
    // 上下各留出 56dp：贴到最上会顶到顶栏，贴到最下会压住输入区
    val minOffsetPx = with(density) { 56.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            // ⚠️ 这里原本是 `.width(1.dp)`，注释写着"实际内容用 offset 绝对定位，
            // 会溢出这个宽度"。**那个假设是错的，它就是"最小化后收不成侧边标签"
            // 的根因**：
            //   - `Modifier.offset` 只改变**放置**，不改变**测量**；
            //   - 父级 Box 被 `width(1.dp)` 定死后，子级拿到的 maxWidth 仍是 1dp；
            //   - 而 `width/size/padding` 默认 enforceIncoming = true，会被压进 1dp。
            // 于是整枚胶囊（含固定 66dp 的标题文本）被挤成约 1dp 宽的细条 ——
            // 标签其实**一直在渲染**，只是宽度几乎为零，视觉上等于不存在。
            //
            // 改成整宽容器：胶囊仍用 align(TopEnd/TopStart) 贴到屏幕边缘，
            // 纵向仍由 dockFraction 绝对定位（见下方 offset）；容器自身
            // 没有任何手势修饰符，因此不会拦截触摸，也不遮挡下层内容。
            .fillMaxWidth()
            .onSizeChanged { containerHeightPx = it.height }
            .zIndex(9999f)
    ) {
        docked.forEach { item ->
            val available = (containerHeightPx - minOffsetPx * 2).coerceAtLeast(1f)
            val targetY = minOffsetPx + available * item.dockFraction

            DockItemCapsule(
                windowItem = item,
                side = side,
                modifier = Modifier
                    .align(if (side == DockSide.RIGHT) Alignment.TopEnd else Alignment.TopStart)
                    .offset(y = with(density) { targetY.toDp() }),
                onRestore = { manager.toggleMinimize(item.id) },
                onClose = { manager.closeWindow(item.id) },
                // 像素增量换算成比例增量后交给管理器，UI 层不直接改状态字段
                onDrag = { dyPx ->
                    if (containerHeightPx > 0) {
                        manager.dragDockItem(item.id, dyPx / containerHeightPx)
                    }
                },
                onDragStateChange = { dragging -> item.isDockDragging = dragging }
            )
        }
    }
}

@Composable
private fun DockItemCapsule(
    windowItem: FloatingWindowItem,
    side: DockSide,
    modifier: Modifier = Modifier,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStateChange: (Boolean) -> Unit
) {
    // 靠屏幕内侧的两个角圆，贴边那侧方 —— 视觉上"挂"在屏幕边缘
    val shape = if (side == DockSide.RIGHT) {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    } else {
        RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed || windowItem.isDockDragging) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "dockScale"
    )

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (windowItem.isDockDragging) 8.dp else 3.dp,
        shadowElevation = if (windowItem.isDockDragging) 8.dp else 3.dp,
        modifier = modifier.scale(scale)
    ) {
        Row(
            modifier = Modifier
                // 关键顺序：先挂拖拽手势，再挂点击。
                // 反过来的话，拖动一开始按压就被 clickable 吃掉，
                // 表现成"标签能点开、但怎么拖都不动"。
                .pointerInput(windowItem.id) {
                    detectDragGestures(
                        onDragStart = { onDragStateChange(true) },
                        onDragEnd = { onDragStateChange(false) },
                        onDragCancel = { onDragStateChange(false) },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // 只认纵向位移：标签永远贴在屏幕侧边，横向拖动没有意义；
                            // 把横向也算进去会让手指稍微偏一点就触发拖动，与"点击还原"打架。
                            onDrag(dragAmount.y)
                        }
                    )
                }
                .clickable(interactionSource = interactionSource, indication = null) { onRestore() }
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = "拖动以改变位置",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Icon(
                imageVector = Icons.Outlined.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "L${windowItem.depth}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = windowItem.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(66.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
