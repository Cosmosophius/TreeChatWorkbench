package com.treechat.workbench.etymology.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 词典「思维导图模式」画布（与「工作台」对话树视觉规范 100% 对齐）。
 *
 * 核心升级：
 * 1. 唯一数据源：直接使用 [buildDictCards] 产出的 [DictCard] 集合，保证数据绝对一致；
 * 2. 围绕查询词生成结构化知识图谱（中心根节点、词源分支、多语流变分支、对译分支、同义分支）；
 * 3. 卡片自由拖拽与连线动态跟随（对齐 Workbench 工作台）：
 *    - 节点卡片挂载独立拖拽手势，位移随缩放比率跟手调整；
 *    - 连线计算动态叠加起点与终点节点的当前位移，贝塞尔连线实时平滑跟随重绘；
 * 4. 浮动详情小窗（替换原底部全屏抽屉）：
 *    - 节点点击时在画布内浮出独立详情小窗，展示节点全称、完整详细取值、数据来源、发音试听；
 *    - 具备明确关闭按钮与标题栏拖拽移动能力，杜绝跳到主对话；
 * 5. 点阵底纹、缩放工具簇、状态角标与语义缩放（LOD）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictMindMap(
    cards: List<DictCard>,
    query: String,
    onSpeak: (text: String, ipa: String, accent: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // 画布平移与缩放状态
    var scale by remember { mutableFloatStateOf(0.9f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 每一个卡片的动态位移偏移记录（对齐工作台卡片拖拽）
    val nodeOffsets = remember { mutableStateMapOf<String, Offset>() }

    // 当查询词或卡片列表重置时，清空自定义卡片位移
    LaunchedEffect(cards, query) {
        nodeOffsets.clear()
    }

    // 选中的节点详情（画布内浮动详情小窗，非对话小窗）
    var inspectedNode by remember { mutableStateOf<MindMapNode?>(null) }

    // 构建导图树模型与自动布局坐标
    val tree = remember(cards, query) { buildMindMapTree(query, cards) }
    val layout = remember(tree) { computeMindMapLayout(tree) }

    // 节点基础布局位置索引表
    val nodeBasePositions = remember(layout) {
        layout.nodes.associate { it.node.id to it.position }
    }

    val dotTile = rememberDotGridTile(scheme.outlineVariant)

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(scheme.surface)) {
        // ---------------- 1. 可缩放与平移的无限画布 ----------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.25f, 2.5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
        ) {
            // 背景点阵与动态跟随的贝塞尔连线
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            ) {
                // 平铺点阵
                val brush = ShaderBrush(ImageShader(dotTile, TileMode.Repeated, TileMode.Repeated))
                drawRect(brush = brush, size = size * 3f, topLeft = Offset(-size.width, -size.height))

                // 绘制各分支从母节点到子节点的平滑三次贝塞尔曲线
                // 连线两端实时加上各节点的拖拽偏移量，实现动态平滑跟随！
                val strokeColor = scheme.outlineVariant.copy(alpha = 0.65f)
                val strokeWidth = 1.8f

                layout.connections.forEach { conn ->
                    val fromBase = nodeBasePositions[conn.fromNodeId] ?: Offset.Zero
                    val toBase = nodeBasePositions[conn.toNodeId] ?: Offset.Zero
                    val fromOffset = nodeOffsets[conn.fromNodeId] ?: Offset.Zero
                    val toOffset = nodeOffsets[conn.toNodeId] ?: Offset.Zero

                    val startPoint = fromBase + fromOffset + conn.startAnchor
                    val endPoint = toBase + toOffset + conn.endAnchor

                    val path = bezierPath(startPoint, endPoint)
                    drawPath(
                        path = path,
                        color = strokeColor,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            // 绘制节点实体卡片（支持拖拽与实时连线重绘）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            ) {
                layout.nodes.forEach { placedNode ->
                    val nodeId = placedNode.node.id
                    val currentOffset = nodeOffsets[nodeId] ?: Offset.Zero
                    val finalX = placedNode.position.x + currentOffset.x
                    val finalY = placedNode.position.y + currentOffset.y

                    MindMapNodeCard(
                        node = placedNode.node,
                        currentScale = scale,
                        onNodeClick = { inspectedNode = placedNode.node },
                        onSpeak = onSpeak,
                        modifier = Modifier
                            .offset {
                                IntOffset(finalX.roundToInt(), finalY.roundToInt())
                            }
                            // 点击手势：弹出画布内浮动详情小窗
                            .pointerInput(nodeId) {
                                detectTapGestures(
                                    onTap = { inspectedNode = placedNode.node }
                                )
                            }
                            // 拖拽手势：位移按当前缩放比换算并更新 nodeOffsets，连线自动实时跟随
                            .pointerInput(nodeId, scale) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val safeScale = scale.coerceAtLeast(0.1f)
                                    val current = nodeOffsets[nodeId] ?: Offset.Zero
                                    nodeOffsets[nodeId] = current + (dragAmount / safeScale)
                                }
                            }
                    )
                }
            }
        }

        // ---------------- 2. 左上角状态角标 ----------------
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerHigh.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, scheme.outlineVariant),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
        ) {
            Text(
                text = "${layout.nodes.size} 个节点 · ${(scale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // ---------------- 3. 右下角工具簇（对齐工作台） ----------------
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MapToolButton(
                icon = Icons.Default.Add,
                label = "放大",
                onClick = { scale = (scale * 1.2f).coerceAtMost(2.5f) }
            )
            MapToolButton(
                icon = Icons.Default.Remove,
                label = "缩小",
                onClick = { scale = (scale / 1.2f).coerceAtLeast(0.25f) }
            )
            MapToolButton(
                icon = Icons.Default.CenterFocusStrong,
                label = "复位居中",
                onClick = {
                    scope.launch {
                        scale = 0.9f
                        offsetX = 0f
                        offsetY = 0f
                        nodeOffsets.clear()
                    }
                }
            )
        }

        // ---------------- 4. 浮动详情小窗（对齐工作台，展示全称、详细取值、来源、试听、关闭按钮） ----------------
        AnimatedVisibility(
            visible = inspectedNode != null,
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.92f),
            modifier = Modifier.fillMaxSize()
        ) {
            inspectedNode?.let { node ->
                MindMapDetailFloatingWindow(
                    node = node,
                    onDismissRequest = { inspectedNode = null },
                    onSpeak = onSpeak
                )
            }
        }
    }
}

/**
 * 画布内浮动详情小窗（对齐 Workbench 卡片详情小窗规范）。
 *
 * 核心特性：
 * 1. 浮动于导图画布之上，带半透明遮罩与柔和投影；
 * 2. 标题栏支持拖拽微调位置，不遮挡想对比的导图节点；
 * 3. 详细展示：分类层级、节点全称、完整详细取值（等宽字体长文本）、数据来源、发音试听；
 * 4. 右上角与底部均带有清晰明确的关闭按钮，绝不跳到主对话。
 */
@Composable
private fun MindMapDetailFloatingWindow(
    node: MindMapNode,
    onDismissRequest: () -> Unit,
    onSpeak: (text: String, ipa: String, accent: String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var windowDragOffset by remember(node.id) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.28f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismissRequest() })
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f)),
            modifier = Modifier
                .padding(24.dp)
                // 宽度上限按可用宽度自适应（旧写死 460.dp，平板/桌面下窄得放不下
                // 词源长文本）：先封顶 820.dp，再铺满剩余宽度，窄屏自动等于全宽。
                .widthIn(min = 280.dp, max = 820.dp)
                .fillMaxWidth()
                .offset { IntOffset(windowDragOffset.x.roundToInt(), windowDragOffset.y.roundToInt()) }
                .pointerInput(Unit) {
                    // 拦截点击手势，防止点在卡片内部时穿透到背景遮罩导致关闭
                    detectTapGestures { }
                }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 顶部小窗标题栏（支持拖拽小窗）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.surfaceContainerHighest)
                        .pointerInput(node.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                windowDragOffset += dragAmount
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (node.categoryTitle.isNotBlank()) node.categoryTitle else "导图节点详情",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 内容详情区域
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // 节点全称与角标
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = node.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (!node.badge.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = scheme.tertiaryContainer
                            ) {
                                Text(
                                    text = node.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 完整详细取值
                    Text(
                        text = "完整详细取值",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = scheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = node.value.ifBlank { "（无附加内容）" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = if (node.isMonospace) FontFamily.Monospace else FontFamily.Default,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    // 数据来源
                    if (!node.source.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "数据来源：",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.outline
                            )
                            Text(
                                text = node.source,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }

                    // 发音试听大按钮
                    if (!node.accent.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = { onSpeak(node.value, node.value, node.accent) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VolumeUp,
                                contentDescription = "试听发音",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "试听标准发音（口音: ${node.accent}）",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // 底部明确关闭按钮
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onDismissRequest,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("关闭", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 节点卡片 Composable
// ============================================================================

@Composable
private fun MindMapNodeCard(
    node: MindMapNode,
    currentScale: Float,
    onNodeClick: () -> Unit,
    onSpeak: (text: String, ipa: String, accent: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val isRoot = node.isRoot
    val isBranchHeader = node.isBranchHeader

    // 语义缩放：低于 0.6 时正文隐藏，平滑过渡
    val lodDetail by animateFloatAsState(
        targetValue = if (currentScale >= 0.65f) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "lodDetail"
    )

    val containerColor = when {
        isRoot -> scheme.primaryContainer
        isBranchHeader -> scheme.secondaryContainer
        else -> scheme.surfaceContainerHigh
    }

    val borderColor = when {
        isRoot -> scheme.primary
        isBranchHeader -> scheme.secondary
        else -> scheme.outlineVariant
    }

    Card(
        shape = RoundedCornerShape(if (isRoot) 14.dp else 10.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isRoot) 1.5.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isRoot) 6.dp else 2.dp),
        modifier = modifier.widthIn(min = if (isRoot) 180.dp else 140.dp, max = 260.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 节点标题行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = node.label,
                    style = if (isRoot) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = if (isRoot || isBranchHeader) FontWeight.Bold else FontWeight.Medium,
                    color = if (isRoot) scheme.onPrimaryContainer else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (!node.badge.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = scheme.tertiaryContainer,
                        modifier = Modifier.padding(start = 2.dp)
                    ) {
                        Text(
                            text = node.badge,
                            fontSize = 9.sp,
                            color = scheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                if (!node.accent.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { onSpeak(node.value, node.value, node.accent) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VolumeUp,
                            contentDescription = "发音",
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 查看详情小图标按钮
                if (!isRoot) {
                    Spacer(modifier = Modifier.width(2.dp))
                    IconButton(
                        onClick = onNodeClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "详情",
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            // 节点详细取值（在 LOD < 0.6 时平滑淡出隐藏）
            if (lodDetail > 0.05f && node.value.isNotBlank() && node.value != node.label) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = node.value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (node.isMonospace) FontFamily.Monospace else FontFamily.Default,
                    color = scheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = lodDetail }
                )
            }
        }
    }
}

// ============================================================================
// 导图树结构与自动布局引擎
// ============================================================================

data class MindMapNode(
    val id: String,
    val label: String,
    val value: String = "",
    val categoryTitle: String = "",
    val badge: String? = null,
    val source: String? = null,
    val accent: String? = null,
    val isMonospace: Boolean = false,
    val isRoot: Boolean = false,
    val isBranchHeader: Boolean = false,
    val children: List<MindMapNode> = emptyList()
)

data class PlacedNode(
    val node: MindMapNode,
    val position: Offset
)

/**
 * 节点间的逻辑连线结构：
 * 记录母节点 ID、子节点 ID 以及各自在卡片上的锚点相对偏移。
 * 绘图时实时叠加各自的拖拽位移 [nodeOffsets]，实现卡片拖拽连线平滑跟随！
 */
data class NodeConnection(
    val fromNodeId: String,
    val toNodeId: String,
    val startAnchor: Offset,
    val endAnchor: Offset
)

data class MindMapLayoutResult(
    val nodes: List<PlacedNode>,
    val connections: List<NodeConnection>
)

/**
 * 将平铺卡片集合结构化收拢为清晰的 4 大思维导图主题分支：
 * 1. 词源与演变（语族、PIE流变、派生词、屈折形式变化）
 * 2. 语音流变（现代、中古、上古、英、俄全部发音）
 * 3. 双语对译对比（汉、英、俄平行对照）
 * 4. 同义词族谱（同义词群与 IPA）
 */
private fun buildMindMapTree(query: String, cards: List<DictCard>): MindMapNode {
    val branchNodes = mutableListOf<MindMapNode>()

    // 分支 1：词源与演变
    val etymCards = cards.filter { it.id == "etym" || it.id == "en.inflect" }
    if (etymCards.isNotEmpty()) {
        val subNodes = etymCards.flatMap { card ->
            card.rows.mapIndexed { idx, row ->
                MindMapNode(
                    id = "etym_row_${card.id}_$idx",
                    label = row.label,
                    value = row.value,
                    categoryTitle = card.title,
                    source = row.source,
                    badge = card.badge
                )
            }
        }
        branchNodes += MindMapNode(
            id = "branch_etym",
            label = "词源、语族与派生",
            value = "印欧根流变路径与屈折变化",
            categoryTitle = "词源分支",
            isBranchHeader = true,
            children = subNodes
        )
    }

    // 分支 2：语音流变
    val phoneCards = cards.filter {
        it.id in listOf("cmn.modern", "cmn.mc", "cmn.oc", "en.modern", "en.trad", "ru.modern")
    }
    if (phoneCards.isNotEmpty()) {
        val subNodes = phoneCards.flatMap { card ->
            card.rows.mapIndexed { idx, row ->
                MindMapNode(
                    id = "phone_row_${card.id}_$idx",
                    label = "${card.title.substringBefore(" · ")}: ${row.label}",
                    value = row.value,
                    categoryTitle = card.title,
                    source = row.source,
                    accent = row.accent,
                    badge = card.badge,
                    isMonospace = true
                )
            }
        }
        branchNodes += MindMapNode(
            id = "branch_phonology",
            label = "多语语音流变",
            value = "普通话 · 中古 · 上古 · 现代英音 · 贵族音 · 俄语",
            categoryTitle = "语音分支",
            isBranchHeader = true,
            children = subNodes
        )
    }

    // 分支 3：双语对译对比
    val glossCards = cards.filter { it.id in listOf("cmn.meaning", "en.gloss", "ru.gloss") }
    if (glossCards.isNotEmpty()) {
        val subNodes = glossCards.flatMap { card ->
            card.rows.mapIndexed { idx, row ->
                MindMapNode(
                    id = "gloss_row_${card.id}_$idx",
                    label = "${card.title.substringBefore(" · ")}: ${row.label}",
                    value = row.value,
                    categoryTitle = card.title,
                    source = row.source,
                    badge = card.badge
                )
            }
        }
        branchNodes += MindMapNode(
            id = "branch_bilingual",
            label = "双语与对译对比",
            value = "汉 · 英 · 俄三语对照",
            categoryTitle = "翻译分支",
            isBranchHeader = true,
            children = subNodes
        )
    }

    // 分支 4：同义词族谱（同义词已按义项分组，这里每个单词仍单独成节点，
    // 但节点的分类用该单词所属义项的释义 —— 导图上就能看出"谁跟谁是一个意思"）
    val synCard = cards.find { it.id == "syn" }
    if (synCard != null && synCard.rows.isNotEmpty()) {
        val subNodes = synCard.rows.flatMapIndexed { idx, row ->
            if (row.items.isNotEmpty()) {
                row.items.mapIndexed { j, item ->
                    MindMapNode(
                        id = "syn_${idx}_$j",
                        label = item.word,
                        value = item.ipa.ifBlank { "未收录音标" },
                        categoryTitle = row.source ?: synCard.title,
                        source = row.source,
                        accent = row.accent,
                        badge = synCard.badge
                    )
                }
            } else {
                listOf(
                    MindMapNode(
                        id = "syn_row_$idx",
                        label = row.label,
                        value = row.value,
                        categoryTitle = synCard.title,
                        source = row.source,
                        accent = row.accent,
                        badge = synCard.badge
                    )
                )
            }
        }
        branchNodes += MindMapNode(
            id = "branch_synonyms",
            label = "同义词族谱",
            value = "按义项分组 · 共 ${synCard.rows.size} 个意思",
            categoryTitle = "同义分支",
            isBranchHeader = true,
            children = subNodes
        )
    }

    // 提取中心词 summary 或词性
    val headCard = cards.firstOrNull()
    val headSummary = headCard?.rows?.firstOrNull()?.value ?: ""

    return MindMapNode(
        id = "root_word",
        label = query.ifBlank { "查询词" },
        value = headSummary,
        categoryTitle = "中心词根",
        isRoot = true,
        children = branchNodes
    )
}

/**
 * 径向树型自动布局：
 * 列 0：中心词根卡片
 * 列 1：4 大分支卡片
 * 列 2：各分支具体属性与语音叶子卡片
 */
private fun computeMindMapLayout(root: MindMapNode): MindMapLayoutResult {
    val placed = mutableListOf<PlacedNode>()
    val conns = mutableListOf<NodeConnection>()

    val rootX = 60f
    val col1Width = 190f
    val col2Width = 240f
    val rowSpacing = 80f
    val branchSpacing = 40f

    var currentY = 60f

    root.children.forEach { branch ->
        val branchStartY = currentY
        val branchPos = Offset(rootX + col1Width + 80f, currentY)
        placed += PlacedNode(branch, branchPos)

        var leafY = currentY
        branch.children.forEach { leaf ->
            val leafPos = Offset(branchPos.x + col2Width + 80f, leafY)
            placed += PlacedNode(leaf, leafPos)

            // 一级分支到叶子节点的连线
            conns += NodeConnection(
                fromNodeId = branch.id,
                toNodeId = leaf.id,
                startAnchor = Offset(200f, 30f),
                endAnchor = Offset(0f, 25f)
            )
            leafY += rowSpacing
        }

        val branchTotalHeight = maxOf(leafY - branchStartY, rowSpacing)
        currentY += branchTotalHeight + branchSpacing

        // 根节点到一级分支的连线
        conns += NodeConnection(
            fromNodeId = root.id,
            toNodeId = branch.id,
            startAnchor = Offset(180f, 35f),
            endAnchor = Offset(0f, 30f)
        )
    }

    // 根节点垂直居中
    val rootY = (currentY / 2f).coerceAtLeast(180f)
    placed += PlacedNode(root, Offset(rootX, rootY))

    return MindMapLayoutResult(placed, conns)
}

private fun bezierPath(start: Offset, end: Offset): Path {
    val dx = abs(end.x - start.x).coerceAtLeast(60f) * 0.5f
    return Path().apply {
        moveTo(start.x, start.y)
        cubicTo(
            start.x + dx, start.y,
            end.x - dx, end.y,
            end.x, end.y
        )
    }
}

@Composable
private fun rememberDotGridTile(color: Color): ImageBitmap {
    val tileColor = color.copy(alpha = 0.22f)
    return remember(tileColor) {
        val tile = 36
        val bitmap = ImageBitmap(tile, tile)
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val paint = Paint().apply {
            this.color = tileColor
            isAntiAlias = true
        }
        canvas.drawCircle(Offset(tile / 2f, tile / 2f), 1.2f, paint)
        bitmap
    }
}

@Composable
private fun MapToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = scheme.surfaceContainerHigh.copy(alpha = 0.94f)
        ),
        modifier = Modifier
            .size(40.dp)
            .shadow(3.dp, CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = scheme.onSurface,
            modifier = Modifier.size(19.dp)
        )
    }
}
