package com.treechat.workbench.ui.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import com.treechat.workbench.ui.window.FloatingWindowItem
import com.treechat.workbench.ui.window.WindowSpace
import com.treechat.workbench.data.model.ConversationNode
import com.treechat.workbench.ui.theme.AccentNeonGreen
import kotlin.math.abs
import kotlin.math.min

/**
 * 工作台画布的**几何与布局辅助**（纯函数，无 Compose 状态）。
 *
 * 从 `TreeTopologyView.kt`（曾超过 2400 行）中拆出。这些函数只做数学：
 * 卡片让位位移、可见节点计算、深度与祖先链、连线贝塞尔、内容自适应缩放。
 * 它们不持有任何 Compose 状态，也不碰 ViewModel —— 放在一起可以单独推理，
 * 也避免"改画布手势"时在读不到边的常量与纯函数之间来回跳。
 */

internal data class Displacement(
    val offset: Offset,
    /**
     * 挤出距离。同向叠力时按最大半径推进，保证多个小窗覆盖同一片区域时，
     * 被挤的卡片不会停在两张小窗中间那点缝隙里。
     */
    val radius: Float
)

/**
 * 计算"卡片最终位置"与"整块画布的范围"。
 *
 * 挤出规则（依次是：判定重叠 → 选方向 → 累积推距）：
 *
 * 1. **只看与小窗矩形真正相交的卡片**。不相交的一律不动 ——
 *    这是"没被覆盖的卡片不要乱动"这条要求的实现。
 * 2. 方向取"离窗哪条边最近"，取反方向推开：窗在卡片左边就把卡片往右推。
 *    这比固定往右推更符合直觉，也避免把靠右的卡片推出画布。
 * 3. 同一方向、同一"投影带"（垂直/水平方向上有重叠）的多个小窗，
 *    按最大半径推进而不是逐窗累加：累加会让被两张窗夹住的卡片被推到很远，
 *    松手弹回时来回甩，很晕。
 */
internal fun computeGeometry(
    graphState: GraphViewState,
    nodes: List<String>,
    defaultSize: Size,
    windows: List<com.treechat.workbench.ui.window.FloatingWindowItem>,
    densityScale: Float,
    /** 正在被拖动/缩放的卡片：它不动，由它把邻居推开。null 表示没人在拖。 */
    activeNodeId: String? = null
): GraphGeometry {
    val gap = 24f * densityScale
    val maxPush = 1400f * densityScale

    val sizes = nodes.associateWith { graphState.sizeOf(it, defaultSize) }

    val displaced = mutableMapOf<String, Displacement>()

    val windowRects = windows.map { item ->
        val w = item.width.value * densityScale
        val h = item.height.value * densityScale
        Rect(
            left = item.offset.x,
            top = item.offset.y,
            right = item.offset.x + w,
            bottom = item.offset.y + h
        )
    }

    // 每个节点累积四个方向的推距，最后取"净位移"
    val pushes = mutableMapOf<String, MutableList<Pair<Offset, Float>>>()

    nodes.forEach { nodeId ->
        val pos = graphState.positions[nodeId] ?: return@forEach
        val size = sizes[nodeId] ?: defaultSize
        val card = Rect(
            left = pos.x,
            top = pos.y,
            right = pos.x + size.width,
            bottom = pos.y + size.height
        )

        windowRects.forEach { window ->
            if (!card.overlaps(window)) return@forEach

            // 四个方向各自需要的推开距离
            val needRight = window.right - card.left + gap
            val needLeft = card.right - window.left + gap
            val needDown = window.bottom - card.top + gap
            val needUp = card.bottom - window.top + gap
            val minNeed = minOf(needRight, needLeft, needDown, needUp)

            val (direction, distance) = when (minNeed) {
                needRight -> Offset(1f, 0f) to needRight
                needLeft -> Offset(-1f, 0f) to needLeft
                needDown -> Offset(0f, 1f) to needDown
                else -> Offset(0f, -1f) to needUp
            }
            pushes.getOrPut(nodeId) { mutableListOf() }
                .add(direction to distance.coerceAtMost(maxPush))
        }
    }

    // ---------------- 卡片 × 卡片 让位 ----------------
    //
    // 用户要求："拖动/缩放卡片时，压住的邻居卡片自动让开（松手后可回位）"。
    // 做法：把正在被拖动的那张卡视为**不可动**，其余卡片沿"最小分离轴"推开。
    // 只做一轮即可——拖动是逐帧进行的，每帧都会重算，
    // 连锁重叠会在随后的帧里逐步散开，不必在这里做迭代求解。
    // 松手后 activeNodeId 变 null，这段不再执行 → 位移消失 → 卡片自动回位。
    if (activeNodeId != null) {
        val activePos = graphState.positions[activeNodeId]
        val activeSize = sizes[activeNodeId]
        if (activePos != null && activeSize != null) {
            val activeRect = Rect(
                left = activePos.x,
                top = activePos.y,
                right = activePos.x + activeSize.width,
                bottom = activePos.y + activeSize.height
            )
            nodes.forEach { nodeId ->
                if (nodeId == activeNodeId) return@forEach
                val pos = graphState.positions[nodeId] ?: return@forEach
                val size = sizes[nodeId] ?: defaultSize
                // 用"已被小窗推开之后"的位置判断，避免二次重叠
                val shifted = pos + (displaced[nodeId]?.offset ?: Offset.Zero)
                val card = Rect(
                    left = shifted.x,
                    top = shifted.y,
                    right = shifted.x + size.width,
                    bottom = shifted.y + size.height
                )
                if (!card.overlaps(activeRect)) return@forEach

                val needRight = activeRect.right - card.left + gap
                val needLeft = card.right - activeRect.left + gap
                val needDown = activeRect.bottom - card.top + gap
                val needUp = card.bottom - activeRect.top + gap
                val minNeed = minOf(needRight, needLeft, needDown, needUp)
                val (direction, distance) = when (minNeed) {
                    needRight -> Offset(1f, 0f) to needRight
                    needLeft -> Offset(-1f, 0f) to needLeft
                    needDown -> Offset(0f, 1f) to needDown
                    else -> Offset(0f, -1f) to needUp
                }
                pushes.getOrPut(nodeId) { mutableListOf() }
                    .add(direction to distance.coerceAtMost(maxPush))
            }
        }
    }

    pushes.forEach { (nodeId, list) ->
        // 按方向归并：同方向取最大推距，不同方向（例如一左一右）相互抵消
        val byDirection = list.groupBy { it.first }
        var dx = 0f
        var dy = 0f
        var radius = 0f
        byDirection.forEach { (direction, entries) ->
            val strongest = entries.maxOf { it.second }
            dx += direction.x * strongest
            dy += direction.y * strongest
            radius = maxOf(radius, strongest)
        }
        if (dx != 0f || dy != 0f) {
            displaced[nodeId] = Displacement(Offset(dx, dy), radius)
        }
    }

    // 画布范围 = 所有卡片基准位置 + 小窗位置 的并集
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    nodes.forEach { nodeId ->
        val pos = graphState.positions[nodeId] ?: return@forEach
        val size = sizes[nodeId] ?: defaultSize
        left = min(left, pos.x)
        top = min(top, pos.y)
        right = maxOf(right, pos.x + size.width)
        bottom = maxOf(bottom, pos.y + size.height)
    }
    windowRects.forEach { rect ->
        left = min(left, rect.left)
        top = min(top, rect.top)
        right = maxOf(right, rect.right)
        bottom = maxOf(bottom, rect.bottom)
    }
    val bounds = if (left > right || top > bottom) {
        Rect.Zero
    } else {
        Rect(left, top, right, bottom)
    }

    return GraphGeometry(displaced, sizes, bounds)
}

/**
 * 工作台小窗里的对话内容。
 *
 * 与全局浮层里的那个 NodeChatContent 是同一个（同一个 ViewModel、同一条
 * 节点消息流），只是这里不再套一层浮层的拖拽/缩放 —— 那些由外层
 * FloatingWindowCard 提供。
 */

private fun toggleCollapse(nodeId: String): String {
    val nowCollapsed = BranchCollapseState.toggle(nodeId)
    return if (nowCollapsed) "已收起该卡片的所有下级分支" else "已展开下级分支卡片"
}

/**
 * 计算可见节点：全部节点减去"被折叠节点的所有后代"。
 *
 * - 被折叠节点**自身仍可见**（"收起该卡片的所有下级分支"字面语义）；
 * - 用 visited 集合兜底：即使历史数据里出现成环，也不会死循环。
 */
private fun computeVisibleNodes(
    nodes: List<ConversationNode>,
    collapsedIds: Set<String>
): List<ConversationNode> {
    if (collapsedIds.isEmpty()) return nodes
    val byParent = nodes.groupBy { it.parentId }
    val hidden = mutableSetOf<String>()
    collapsedIds.forEach { rootId ->
        val stack = ArrayDeque(byParent[rootId].orEmpty())
        while (stack.isNotEmpty()) {
            val child = stack.removeFirst()
            if (hidden.add(child.id)) {
                stack.addAll(byParent[child.id].orEmpty())
            }
        }
    }
    if (hidden.isEmpty()) return nodes
    return nodes.filterNot { it.id in hidden }
}
