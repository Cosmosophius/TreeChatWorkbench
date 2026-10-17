package com.treechat.workbench.ui.explorer

import androidx.compose.ui.geometry.Rect

/**
 * 图标自由摆放的坐标（**单位 dp**，与密度无关，随 SharedPreferences JSON 持久化）。
 *
 * 字段名 x / y 与 JSON 形状 `{ "id": { "x": …, "y": … } }` 一一对应。
 */
data class FreePosition(val x: Float, val y: Float)

/**
 * 图标视图（自由摆放画布）的排布常量与纯函数。
 *
 * 所有函数的入参出参都是 **dp 浮点数**，由调用方按 Density 换算像素；
 * 纯函数便于单独推理，不依赖任何 Compose 状态。
 *
 * 布局语义对齐安卓桌面 launcher：
 * - 没有自定义坐标的条目按「从左到右、从上到下」的默认网格流动；
 * - 拖过并落下的条目记下绝对坐标，覆盖默认位置；
 * - 坐标一律夹紧在画布可视范围内，不允许拖出屏幕或压到工具栏。
 */
object FreePlacement {

    /** 单元格宽度（dp）：图标 + 两行标题 + 徽章都能容下。 */
    const val CELL_W_DP = 104f

    /** 单元格高度（dp）。 */
    const val CELL_H_DP = 140f

    /** 默认网格列间距 / 行距（dp）。 */
    const val SPACING_DP = 10f

    /** 画布左右、上下内边距（dp）。 */
    const val PAD_H_DP = 12f
    const val PAD_V_DP = 8f

    /**
     * 拖动分类阈值（dp）：长按抬起后位移超过它才算「真的拖了」，
     * 否则视为「长按未移动」→ 弹出原有的更多菜单。
     */
    const val DRAG_CLASSIFY_DP = 10f

    /** 自动滚动的边缘感应区（dp，距画布上/下边缘）。 */
    const val EDGE_ZONE_DP = 72f

    /** 边缘自动滚动的基准速度（dp / 60fps 帧，随距离边缘的比例衰减）。 */
    const val EDGE_SPEED_DP = 12f

    /**
     * 依据画布宽度算列数：至少 1 列，上限防止超宽屏出现离谱列数。
     *
     * [cellWDp] 允许调用方指定单元格宽度：书架的书卡片比访达的文件夹格子高得多，
     * 两处必须用各自的格子尺寸算列数，否则书会叠在一起。
     */
    fun columnCount(canvasWidthDp: Float, cellWDp: Float = CELL_W_DP): Int {
        if (canvasWidthDp <= 0f) return 2
        val fit = (canvasWidthDp - PAD_H_DP * 2f + SPACING_DP) / (cellWDp + SPACING_DP)
        return fit.toInt().coerceIn(1, 16)
    }

    /** 第 [index] 个条目在默认网格中的位置（dp）。 */
    fun defaultPosition(
        index: Int,
        columns: Int,
        cellWDp: Float = CELL_W_DP,
        cellHDp: Float = CELL_H_DP
    ): FreePosition {
        val cols = columns.coerceAtLeast(1)
        val col = index % cols
        val row = index / cols
        return FreePosition(
            x = PAD_H_DP + col * (cellWDp + SPACING_DP),
            y = PAD_V_DP + row * (cellHDp + SPACING_DP)
        )
    }

    /** 条目实际位置：自定义坐标优先，否则落回默认网格。 */
    fun positionOf(
        index: Int,
        itemId: String,
        columns: Int,
        custom: Map<String, FreePosition>,
        cellWDp: Float = CELL_W_DP,
        cellHDp: Float = CELL_H_DP
    ): FreePosition = custom[itemId] ?: defaultPosition(index, columns, cellWDp, cellHDp)

    /** 画布内容高度（dp）= 最靠下的单元格底边 + 下边距。 */
    fun contentHeightDp(positions: List<FreePosition>, cellHDp: Float = CELL_H_DP): Float {
        val bottom = positions.maxOfOrNull { it.y + cellHDp } ?: return 0f
        return bottom + PAD_V_DP
    }

    /**
     * 落点夹紧：x 限制在 [0, 画布宽 - 单元格宽]，y 限制在 [0, 内容高 - 单元格高]。
     * 入参 [maxContentHeightDp] 由调用方传 max(内容高, 视口高)，
     * 保证既不会拖出屏幕，也能放进视口里原本空着的区域。
     */
    fun clampPlacement(
        x: Float,
        y: Float,
        canvasWidthDp: Float,
        maxContentHeightDp: Float,
        cellWDp: Float = CELL_W_DP,
        cellHDp: Float = CELL_H_DP
    ): FreePosition {
        val maxX = (canvasWidthDp - cellWDp).coerceAtLeast(0f)
        val maxY = (maxContentHeightDp - cellHDp).coerceAtLeast(0f)
        return FreePosition(
            x = x.coerceIn(0f, maxX),
            y = y.coerceIn(0f, maxY)
        )
    }

    /**
     * 把一整张坐标表夹紧到当前画布内。
     *
     * 为什么需要：用户可能在宽屏上把图标摆到很右边，之后换成窄屏（或分屏、
     * 旋转屏、改字号导致格子上限变化），那些坐标就落到可视区之外 ——
     * 图标"消失"了，用户无法把它拖回来，只能靠"恢复默认排列"删除全部摆放。
     * 每次画布实测尺寸变化时统一夹一遍，位置仍然保留相对关系。
     *
     * @return 夹紧后的坐标表；若没有任何一项越界，原样返回（避免无谓的重组与落盘）。
     */
    fun clampPositions(
        positions: Map<String, FreePosition>,
        canvasWidthDp: Float,
        maxContentHeightDp: Float,
        cellWDp: Float = CELL_W_DP,
        cellHDp: Float = CELL_H_DP
    ): Map<String, FreePosition> {
        if (positions.isEmpty() || canvasWidthDp <= 0f) return positions
        var changed = false
        val out = LinkedHashMap<String, FreePosition>(positions.size)
        positions.forEach { (id, p) ->
            val clamped = clampPlacement(
                x = p.x,
                y = p.y,
                canvasWidthDp = canvasWidthDp,
                maxContentHeightDp = maxContentHeightDp,
                cellWDp = cellWDp,
                cellHDp = cellHDp
            )
            if (clamped != p) changed = true
            out[id] = clamped
        }
        return if (changed) out else positions
    }

    /**
     * 把每个条目的占位矩形换算成 **像素** 的 [Rect]（画布内容坐标系），
     * 供拖拽落点判定用。顺序与 entries 一致，index 即默认网格序号。
     */
    fun contentRectsPx(
        ids: List<String>,
        columns: Int,
        custom: Map<String, FreePosition>,
        density: Float,
        cellWDp: Float = CELL_W_DP,
        cellHDp: Float = CELL_H_DP
    ): Map<String, Rect> {
        val out = HashMap<String, Rect>(ids.size)
        ids.forEachIndexed { index, id ->
            val p = positionOf(index, id, columns, custom, cellWDp, cellHDp)
            val left = p.x * density
            val top = p.y * density
            out[id] = Rect(
                left,
                top,
                left + cellWDp * density,
                top + cellHDp * density
            )
        }
        return out
    }
}
