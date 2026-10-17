package com.treechat.workbench.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 规范的 Google Material 3 风格右侧 OCR 扫描进度悬浮窗。
 * 贴合右侧屏幕边缘，支持一键折叠为微型圆形胶囊，严禁使用任何 emoji。
 */
@Composable
fun OcrProgressFloatingBadge(
    visible: Boolean,
    progress: Float,
    currentPage: Int,
    totalPages: Int,
    bookTitle: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
        ),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            shadowElevation = 8.dp,
            tonalElevation = 6.dp,
            modifier = Modifier.clickable { expanded = !expanded }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                        color = scheme.primary,
                        trackColor = scheme.primary.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.Outlined.DocumentScanner,
                        contentDescription = "OCR 扫描中",
                        tint = scheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "OCR ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        contentDescription = if (expanded) "折叠" else "展开",
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (expanded) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = bookTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSecondaryContainer.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                    Text(
                        text = "已扫描 $currentPage / $totalPages 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "取消任务",
                                tint = scheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
