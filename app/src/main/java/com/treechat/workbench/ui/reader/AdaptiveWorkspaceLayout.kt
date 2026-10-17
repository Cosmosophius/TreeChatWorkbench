package com.treechat.workbench.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 纯 Material 3 Compose 实现的多方案自由拼装自适应工作台布局 (AdaptiveWorkspaceLayout)
 *
 * 特性：
 * 1. 【多方案自由拼装】：支持正文、对话树、词源全息、批注笔记四大板块自由开合；
 * 2. 【按需隐藏无感适配】：用户不需要笔记时，笔记栏彻底收起，其余面板自动弹性填满；
 * 3. 【双形态一键穿梭】：自由在“极简聚焦浮窗”与“分栏平铺工作台”之间切换，输入和滚动状态零丢失。
 */
@Composable
fun AdaptiveWorkspaceLayout(
    config: WorkspacePanelsConfig,
    onConfigChange: (WorkspacePanelsConfig) -> Unit,
    documentContent: @Composable () -> Unit,
    chatTreeContent: @Composable () -> Unit,
    etymologyContent: @Composable () -> Unit,
    notesContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 1. 顶部极简面板控制带 (Material 3 Compact Panel Bar)
        WorkspacePanelControlBar(
            config = config,
            onConfigChange = onConfigChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp
        )

        // 2. 主体工作区呈现
        if (config.layoutMode == WorkspaceLayoutMode.MULTI_PANE_TILED) {
            // 平铺工作台多栏布局：水平弹性分栏
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                // 面板 A：原注文档
                AnimatedVisibility(
                    visible = config.showDocument,
                    enter = fadeIn() + expandHorizontally(spring()),
                    exit = fadeOut() + shrinkHorizontally(spring()),
                    modifier = Modifier.weight(
                        weight = if (config.showChatTree) 1.2f else 1.8f,
                        fill = true
                    )
                ) {
                    WorkspacePanelContainer(title = "原注文档", icon = Icons.Outlined.MenuBook) {
                        documentContent()
                    }
                }

                if (config.showDocument && (config.showChatTree || config.showEtymology || config.showNotes)) {
                    VerticalDivider(modifier = Modifier.padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }

                // 面板 B：对话树分支
                AnimatedVisibility(
                    visible = config.showChatTree,
                    enter = fadeIn() + expandHorizontally(spring()),
                    exit = fadeOut() + shrinkHorizontally(spring()),
                    modifier = Modifier.weight(1.0f, fill = true)
                ) {
                    WorkspacePanelContainer(title = "研读对话树", icon = Icons.Outlined.ChatBubbleOutline) {
                        chatTreeContent()
                    }
                }

                if (config.showChatTree && (config.showEtymology || config.showNotes)) {
                    VerticalDivider(modifier = Modifier.padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }

                // 面板 C：三语词源全息
                AnimatedVisibility(
                    visible = config.showEtymology,
                    enter = fadeIn() + expandHorizontally(spring()),
                    exit = fadeOut() + shrinkHorizontally(spring()),
                    modifier = Modifier.weight(0.9f, fill = true)
                ) {
                    WorkspacePanelContainer(title = "跨语词源 (EtymoScope)", icon = Icons.Outlined.Translate) {
                        etymologyContent()
                    }
                }

                if (config.showEtymology && config.showNotes) {
                    VerticalDivider(modifier = Modifier.padding(horizontal = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }

                // 面板 D：批注笔记白板（按需收起，不占空间）
                AnimatedVisibility(
                    visible = config.showNotes,
                    enter = fadeIn() + expandHorizontally(spring()),
                    exit = fadeOut() + shrinkHorizontally(spring()),
                    modifier = Modifier.weight(0.85f, fill = true)
                ) {
                    WorkspacePanelContainer(title = "批注笔记", icon = Icons.Outlined.EditNote) {
                        notesContent()
                    }
                }
            }
        } else {
            // 极简单屏浮窗形态：以文档/主屏为底，其他按需浮层交互
            Box(modifier = Modifier.fillMaxSize()) {
                documentContent()

                // 若开启了对话树，作为就地悬浮浮层
                if (config.showChatTree) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 16.dp, bottom = 16.dp, end = 16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .fillMaxHeight(0.88f),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                            shadowElevation = 10.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            chatTreeContent()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶部极简面板开合与布局切换控制带
 */
@Composable
private fun WorkspacePanelControlBar(
    config: WorkspacePanelsConfig,
    onConfigChange: (WorkspacePanelsConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：多面板自由勾选 Chip 排（正文 / 对话 / 词源 / 笔记）
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = config.showDocument,
                onClick = { onConfigChange(config.togglePanel(WorkspacePanelType.DOCUMENT)) },
                label = { Text("原著", fontSize = 11.5.sp) },
                leadingIcon = { Icon(Icons.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(13.dp)) }
            )
            FilterChip(
                selected = config.showChatTree,
                onClick = { onConfigChange(config.togglePanel(WorkspacePanelType.CHAT_TREE)) },
                label = { Text("对话", fontSize = 11.5.sp) },
                leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(13.dp)) }
            )
            FilterChip(
                selected = config.showEtymology,
                onClick = { onConfigChange(config.togglePanel(WorkspacePanelType.ETYMOLOGY)) },
                label = { Text("词源", fontSize = 11.5.sp) },
                leadingIcon = { Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(13.dp)) }
            )
            FilterChip(
                selected = config.showNotes,
                onClick = { onConfigChange(config.togglePanel(WorkspacePanelType.NOTES)) },
                label = { Text("笔记", fontSize = 11.5.sp) },
                leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(13.dp)) }
            )
        }

        // 右侧：一键在「极简浮窗」与「分栏平铺」之间切换
        IconButton(
            onClick = { onConfigChange(config.toggleLayoutMode()) },
            modifier = Modifier.size(32.dp)
        ) {
            val isTiled = config.layoutMode == WorkspaceLayoutMode.MULTI_PANE_TILED
            Icon(
                imageVector = if (isTiled) Icons.Outlined.ViewStream else Icons.Outlined.ViewColumn,
                contentDescription = if (isTiled) "切换为极简浮窗" else "切换为多栏平铺",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 单个面板卡片外壳封装
 */
@Composable
private fun WorkspacePanelContainer(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .padding(2.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 微型标题条 (28dp 高度，极度节约面积)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    }
}
