package com.treechat.workbench.ui.tree

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.treechat.workbench.data.model.ConversationNode

/**
 * 多维工作台（图视图）的状态持有者。
 *
 * 单独抽出来并由上层 remember，是为了让「切到聊天视图再切回来」时，
 * 用户手动拖拽摆放的节点坐标不会丢失 —— 如果状态放在 TreeTopologyCanvas
 * 内部，AnimatedContent 切走时该组合会被销毁，坐标就回到默认布局了。
 */
class GraphViewState {
    /** 节点 id -> 画布坐标（px）。用户拖拽会写回这里。 */
    val positions = mutableStateMapOf<String, Offset>()

    /**
     * 被**用户亲手**摆放或调整过尺寸的节点 id。
     *
     * 用途有两个，都很关键：
     * 1. 自动布局（computeGraphLayout）不得覆盖这些节点 —— 否则"重新进来
     *    位置又变了"，用户摆好的思维导图就白摆了。
     * 2. 只有这些节点参与持久化。把自动算出来的坐标也存进库，下次启动就会
     *    把一份机器算的坐标当成用户意图，新节点再也排不齐。
     *
     * 用 List 而不是 Set：Compose 只提供 mutableStateListOf / mutableStateMapOf，
     * **没有 mutableStateSetOf**。节点规模是几十个，List 的 contains 足够快。
     */
    val userPlaced = mutableStateListOf<String>()

    /** 标记某节点为用户亲手摆放（幂等，重复调用不会产生重复项）。 */
    fun markUserPlaced(nodeId: String) {
        if (!userPlaced.contains(nodeId)) userPlaced.add(nodeId)
        // 用户既然有了新的手工调整，撤销快照就自动作废，避免复原到很久以前的陈旧状态
        clearBackup()
    }

    /**
     * 节点 id -> 卡片尺寸（px）。
     * 没记录的节点用默认尺寸；用户拖过边角后写回这里。
     * 与 positions 一样放在上层 remember，切视图不丢。
     */
    val nodeSizes = mutableStateMapOf<String, Size>()

    fun sizeOf(nodeId: String, fallback: Size): Size = nodeSizes[nodeId] ?: fallback

    var scale by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /** 是否已经做过一次自动布局；新增节点时增量补齐，不覆盖手工摆放。 */
    var hasLaidOut by mutableStateOf(false)

    /**
     * 自动重新布局前拍下的完整快照（包含手工坐标、卡片尺寸、放缩视口等）。
     * 用于支持"再次点击同一个按钮复原回原先的手工排版"。
     */
    var layoutBackup by mutableStateOf<LayoutSnapshot?>(null)
        private set

    /** 当前是否可以"二次点击复原" */
    val canRestoreLayout: Boolean get() = layoutBackup != null

    /**
     * 丢弃"上一次自动布局的备份"。
     *
     * 恢复**命名存档**后必须调用它：存档恢复出来的坐标在语义上就是"用户摆的"，
     * 若还留着旧的那份单份备份，用户点"复原"会跳到一个与存档无关的历史布局，
     * 非常难解释。改成显式方法而不是把字段公开，是为了让"谁该清它"这件事
     * 在代码里说得清楚。
     */
    fun discardLayoutBackup() {
        layoutBackup = null
    }

    /** 备份当前所有卡片位置、大小及视口 */
    fun backupCurrentLayout() {
        layoutBackup = LayoutSnapshot(
            positions = positions.toMap(),
            userPlaced = userPlaced.toList(),
            nodeSizes = nodeSizes.toMap(),
            scale = scale,
            pan = pan
        )
    }

    /** 复原上次备份的自定义布局 */
    fun restoreLayout(): Boolean {
        val backup = layoutBackup ?: return false
        positions.clear()
        positions.putAll(backup.positions)
        userPlaced.clear()
        userPlaced.addAll(backup.userPlaced)
        nodeSizes.clear()
        nodeSizes.putAll(backup.nodeSizes)
        scale = backup.scale
        pan = backup.pan
        layoutBackup = null
        return true
    }

    fun clearBackup() {
        layoutBackup = null
    }

    /**
     * 当前正在被拖动或缩放的卡片 id。
     *
     * 用途：让**被压住的邻居卡片自动让开**（用户明确要求）。
     * 这张卡被视为不可动，其余卡片沿最小分离轴被推开；
     * 松手时置回 null，让位位移随之消失 —— 卡片自动回位。
     */
    var activeCardDrag by mutableStateOf<String?>(null)

    fun resetView() {
        scale = 1f
        pan = Offset.Zero
    }

    fun clearLayout() {
        positions.clear()
        nodeSizes.clear()
        // 用户明确要求"重新布局"时才清掉手工摆放标记 —— 否则自动布局
        // 永远不敢动这些节点，"重新布局"按钮会变成没反应。
        userPlaced.clear()
        hasLaidOut = false
        resetView()
    }
}

/**
 * 画布边缘自动平移（"拖到边缘还没拖完，画布自己慢慢挪"）。
 *
 * ## 为什么放在 GraphViewState 上而不是某个手势闭包里
 * 需要它的拖拽分属不同的 pointerInput 闭包：
 *   1. 拖动**节点卡片**（TreeTopologyCanvas 的画布手势闭包）；
 *   2. 拖动画布里的**小窗**（WindowTitleBar 的拖拽闭包）。
 * 把"指针现在在哪、要不要挪、挪多快"做成一份共享状态，
 * 两个手势循环各自把最新指针位置喂给 [update]，帧循环统一 [tick] 出位移 ——
 * 不必各自实现一套，也就不会出现"两股力量同时写 pan"互相打架。
 *
 * ## 速度曲线：越靠边越快，但有明确上限
 * 采用**线性加速**而非平方：`速度 = 最大速度 × 深入比例`。
 * 平方曲线在窗口不大时起步太肉、靠近边缘又太猛；本 App 的画布
 * 常在半屏窗口里使用（可用区域本来就不大），需要"温和起步、够用即止"。
 * 最大速度 [Companion.MAX_SPEED_PX_PER_SEC] 刻意压得较低（见该常量的取值理由），
 * 保证不会一冲到底。
 */
class EdgeAutoScroller {

    /** 是否正在边缘自动平移中。 */
    var active by mutableStateOf(false)
        private set

    private var dir = Offset.Zero
    private var strength = 0f

    /**
     * 更新当前指针位置，重算应不应该自动平移、以及方向与强度。
     *
     * @param position     指针在**视口**坐标里的位置。
     * @param viewport     视口尺寸（画布可见区域，单位 px）。
     * @param edgeZonePx   感应区宽度：指针进入离边缘这么近的范围就开始平移。
     * @return 本次是否处于激活状态（调用方据此决定要不要继续帧循环）。
     */
    fun update(position: Offset, viewport: Size, edgeZonePx: Float): Boolean {
        // 视口还没测量出来时不做任何事（避免除零与乱挪）
        if (viewport.width <= 0f || viewport.height <= 0f || edgeZonePx <= 0f) {
            stop()
            return false
        }
        // 感应区不能超过半个视口，否则小窗口里整片都算"边缘"，
        // 一进入就狂挪 —— 这是必须夹住的边界情况。
        val zone = minOf(edgeZonePx, viewport.width / 2f, viewport.height / 2f)

        // 分别算 x / y 两个轴"深入感应区多少"（0 = 刚好贴边，1 = 已经到边外）
        val dx = when {
            position.x < zone -> -((zone - position.x) / zone)
            position.x > viewport.width - zone -> (position.x - (viewport.width - zone)) / zone
            else -> 0f
        }
        val dy = when {
            position.y < zone -> -((zone - position.y) / zone)
            position.y > viewport.height - zone -> (position.y - (viewport.height - zone)) / zone
            else -> 0f
        }
        if (dx == 0f && dy == 0f) {
            stop()
            return false
        }
        // 对角处两个轴同时有效，各自独立累加（分开算才不会在角落处互相抵消）
        dir = Offset(dx.coerceIn(-1f, 1f), dy.coerceIn(-1f, 1f))
        strength = maxOf(kotlin.math.abs(dir.x), kotlin.math.abs(dir.y))
        active = true
        return true
    }

    /** 指针离开感应区 / 手势结束：立刻停止，不留残余速度。 */
    fun stop() {
        active = false
        dir = Offset.Zero
        strength = 0f
    }

    /**
     * 推进一帧，返回画布 pan 应当施加的位移（视口像素）。
     *
     * 只做**线性加速**（不做平方、不做指数），且速度上限很低：
     * 用户反馈"窗口一般不会很大，最快也不要很快"，所以这里把
     * 峰值速度压到 [MAX_SPEED_PX_PER_SEC]，并按 dt 归一化 ——
     * 与帧率无关，60Hz 与 120Hz 上观感一致。
     */
    fun tick(dtSeconds: Float): Offset {
        if (!active || dir == Offset.Zero) return Offset.Zero
        val speed = MAX_SPEED_PX_PER_SEC * strength.coerceIn(0f, 1f)
        val step = speed * dtSeconds.coerceIn(0f, 0.05f) // 钳住 dt：卡顿一帧不产生跳变
        return Offset(-dir.x * step, -dir.y * step)
    }

    companion object {
        /**
         * 峰值平移速度（视口 px / 秒）。
         *
         * 取值理由：这个数只在"指针已经贴到最边缘"时达到。
         * 画布半屏时视口高约 400–600px，本值约等于 1.5–2 秒走完一屏 ——
         * 足够把选区带出去，又不会"一冲到底"找不到停的地方。
         * 想更慢就调小这个数，它是唯一的速度总开关。
         */
        const val MAX_SPEED_PX_PER_SEC = 320f
    }
}

/**
 * 工作台重新布局前的工作区快照，支持二次点击一键复原。
 */
data class LayoutSnapshot(
    val positions: Map<String, Offset>,
    val userPlaced: List<String>,
    val nodeSizes: Map<String, Size>,
    val scale: Float,
    val pan: Offset
)

/**
 * 分层布局：深度决定 x，兄弟顺序决定 y，父节点在子节点纵向居中。
 *
 * 这是经典的 tidy-tree 简化版。之所以自己写而不引第三方图布局库，
 * 是因为对话树规模小（通常 < 100 节点）、结构固定为树（无环），
 * 引入依赖不划算。
 */
internal fun computeGraphLayout(
    nodes: List<ConversationNode>,
    columnWidth: Float = 430f,
    rowHeight: Float = 200f
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()

    val byParent = nodes.groupBy { it.parentId }
    val result = mutableMapOf<String, Offset>()
    var cursor = 0f

    fun place(node: ConversationNode, depth: Int): Float {
        val children = byParent[node.id].orEmpty()
        val y = if (children.isEmpty()) {
            val slot = cursor
            cursor += rowHeight
            slot
        } else {
            val childYs = children.map { place(it, depth + 1) }
            (childYs.min() + childYs.max()) / 2f
        }
        result[node.id] = Offset(depth * columnWidth, y)
        return y
    }

    nodes.filter { it.parentId == null }.forEach { place(it, 0) }
    return result
}
