package com.treechat.workbench.data.prefs

import java.util.UUID

/**
 * 手写批注工具类型。
 *
 * 融合 GitHub 开源手写项目（Notable / DrawBox / WriteBuddy / Sketchbook）的核心工具集：
 * - [PEN]：压感钢笔/签字笔（米/O/V 主动手写笔走硬件 4096 级压感，电容笔与手指走速度拟真笔锋）
 * - [HIGHLIGHTER]：平头荧光马克笔（半透明高亮，划重点不遮挡卡片文字）
 * - [ARROW]：直线/箭头指示器（支持跨卡片连线，首尾可自动锚定卡片）
 * - [TAPE]：记忆遮挡胶带（源自 WriteBuddy，涂抹遮住重点词句，背诵时点击即可切换显隐）
 * - [ERASER]：橡皮擦（触碰整笔擦除，同时支持主动手写笔尾端/侧键自动触发）
 */
enum class InkToolType(val label: String) {
    PEN("钢笔"),
    HIGHLIGHTER("荧光笔"),
    ARROW("箭头"),
    TAPE("遮挡带"),
    ERASER("橡皮擦")
}

/**
 * 手写画布的交互模式。
 *
 * - [WRITE]：书写批注态。单指/电容笔/手写笔落笔即书写；双指捏合依然可随时缩放平移画布。
 * - [ARRANGE]：排布态。单指/手写笔可自由拖动卡片位置、拉卡片右下角改宽高、或平移画布。
 */
enum class InkInteractionMode(val label: String) {
    WRITE("书写批注"),
    ARRANGE("移动/改卡片")
}

/**
 * 单个笔迹采样点。
 *
 * @property x 当所属 [InkStroke.anchorCardId] 为 null 时，表示画布世界坐标 px；
 *   当绑定了某张卡片时，表示相对于该卡片左上角及宽度的归一化比例（0f..1f），
 *   从而在卡片被拖动或拉大缩小时，笔迹能严丝合缝地跟着卡片一起移动和缩放。
 * @property y 同上，世界坐标 px 或相对于卡片高度的归一化比例（0f..1f）。
 * @property pressure 压感系数（0.15f..1.35f）。主动手写笔来自真实硬件压力，电容笔/手指来自速度拟合。
 */
data class InkPoint(
    val x: Float = 0f,
    val y: Float = 0f,
    val pressure: Float = 1f
)

/**
 * 单条矢量手写笔迹（或遮挡胶带、指示箭头）。
 *
 * @property id 唯一标识，用于撤销/重做与橡皮擦精准命中删除
 * @property tool 对应 [InkToolType.name]
 * @property colorArgb ARGB 颜色整数
 * @property baseWidth 基准线宽（画布世界坐标单位 px）
 * @property anchorCardId 若非空，说明该笔迹落笔于指定卡片上，坐标为该卡片的归一化局部坐标
 * @property endAnchorCardId 若为 [InkToolType.ARROW] 且终点落在另一张卡片上，记录终点卡片 ID，拖动卡片时箭头自动跟随
 * @property points 采样点序列
 * @property tapeRevealed 当 [tool] 为 [InkToolType.TAPE] 时，标记该遮挡胶带是否已被点击半透明揭开（用于背诵自测）
 */
data class InkStroke(
    val id: String = UUID.randomUUID().toString(),
    val tool: String = InkToolType.PEN.name,
    val colorArgb: Long = 0xFFEF4444,
    val baseWidth: Float = 3.5f,
    val anchorCardId: String? = null,
    val endAnchorCardId: String? = null,
    val points: List<InkPoint> = emptyList(),
    val tapeRevealed: Boolean = false
) {
    val toolType: InkToolType
        get() = runCatching { InkToolType.valueOf(tool) }.getOrDefault(InkToolType.PEN)
}

/**
 * 笔记白板中的单张「活体卡片」快照。
 *
 * 与死图片截图不同，每张卡片保留独立的坐标 `(x, y)` 与尺寸 `(width, height)`，
 * 在「笔记」界面中依然可以自由拖动位置、拉边角放大缩小，且与原工作台互不干扰。
 */
data class InkCardSnapshot(
    val id: String = "",
    val parentId: String? = null,
    val title: String = "",
    val quote: String? = null,
    /** 节点下的核心回答摘要，把卡片拉大时自动显示更多正文，便于用荧光笔划重点或贴遮挡胶带。 */
    val summary: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 480f,
    val height: Float = 220f
)

/**
 * 一份完整的「工作台思维导图活体手写笔记」（可复制多份副本、独立编辑、导出 PDF/PNG）。
 */
data class InkNoteBoard(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "思维导图手写批注",
    val sourceConversationId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val panX: Float = 80f,
    val panY: Float = 120f,
    val scale: Float = 0.85f,
    val cards: List<InkCardSnapshot> = emptyList(),
    val strokes: List<InkStroke> = emptyList()
)
