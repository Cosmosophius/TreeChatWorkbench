package com.treechat.workbench.ui.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.task.BackgroundTaskRegistry
import com.treechat.workbench.task.BackgroundTaskRegistry.State
import com.treechat.workbench.task.BackgroundTaskRegistry.Task

/**
 * 后台任务面板：**随时调出来看** OCR / 建索引这类长任务跑到哪了。
 *
 * 为什么要有它：整本扫描件 OCR、几 MB 教材切片建索引动辄几十秒到几分钟，
 * 以前只有一个飘在屏幕上的小胶囊 —— 用户切到别的 Tab 就再也看不见进度，
 * 也不知道到底是"还在跑"还是"卡死了"。这里把登记在
 * [BackgroundTaskRegistry] 里的任务逐条列出来，含阶段说明与耗时。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTasksSheet(onDismiss: () -> Unit) {
    val tasks by BackgroundTaskRegistry.tasks.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val running = tasks.count { it.isRunning }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "后台任务",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (running > 0) "进行中 $running 个 · 共 ${tasks.size} 条"
                        else "共 ${tasks.size} 条，当前没有进行中的任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = { BackgroundTaskRegistry.clearFinished() },
                    enabled = tasks.any { !it.isRunning }
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清除已完成", fontSize = 12.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "还没有后台任务。导入扫描件做 OCR、或给教材建切片索引时，进度会出现在这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // 进行中的排在最上面，其次按开始时间倒序
                    items(tasks.sortedWith(compareByDescending<Task> { it.isRunning }.thenByDescending { it.startedAtMs })) { task ->
                        TaskRow(task)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (task.state) {
        State.RUNNING -> Icons.Outlined.HourglassEmpty to scheme.primary
        State.DONE -> Icons.Outlined.CheckCircle to scheme.primary
        State.FAILED -> Icons.Outlined.ErrorOutline to scheme.error
        State.CANCELLED -> Icons.Outlined.ErrorOutline to scheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = elapsedText(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }

            if (task.detail.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.state == State.FAILED) scheme.error else scheme.onSurfaceVariant
                )
            }

            if (task.isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                val p = task.progress
                if (p == null) {
                    // 进度未知：不确定态进度条，明确表示"在跑但算不出百分比"
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun elapsedText(task: Task): String {
    val end = task.finishedAtMs ?: System.currentTimeMillis()
    val sec = ((end - task.startedAtMs) / 1000).coerceAtLeast(0)
    val label = when (task.state) {
        State.RUNNING -> "已用"
        State.DONE -> "耗时"
        State.FAILED -> "失败于"
        State.CANCELLED -> "取消于"
    }
    return if (sec < 60) "$label ${sec}s" else "$label ${sec / 60}分${sec % 60}秒"
}
