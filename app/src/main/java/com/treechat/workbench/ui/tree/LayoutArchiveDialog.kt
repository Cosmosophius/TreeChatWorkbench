package com.treechat.workbench.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.ui.theme.AccentCyan
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.PanelSurface
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary

/**
 * 布局存档管理面板。
 *
 * 用户从画布右下角点「布局存档」进来：
 * - 顶部一条输入框，取个名字 → **保存当前布局**；
 * - 下面是已有存档的列表，每条三个操作：**恢复 / 重命名 / 删除**。
 *
 * ## 为什么"保存"要单独一个输入框而不是弹个确认框
 * 自动存档没有名字就等于没有——用户摆了很久的图，回来时要认得出
 * "这是'方案B'"还是"'摆好但没画完的那版'"。所以名字是必填的，
 * 默认值给一个带时间的建议，用户不改也能直接存。
 */
@Composable
internal fun LayoutArchiveDialog(
    store: LayoutArchiveStore,
    graphState: GraphViewState,
    conversationId: String?,
    defaultTitle: String,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    var nameInput by remember { mutableStateOf(defaultTitle) }
    var renaming by remember { mutableStateOf<LayoutArchive?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PanelSurface,
        title = {
            Text(
                "布局存档",
                color = AccentCyan,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "把当前这张导图的排版（每张卡片的位置大小、缩放与平移）存成一份快照。" +
                        "存多少份都行，随时能回到任意一份。",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))

                // ---- 保存当前 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        label = { Text("存档名称", fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = Color(0xFF2C323D),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val a = store.save(conversationId, nameInput, graphState)
                            nameInput = LayoutArchiveStore.suggestTitle(conversationId)
                            onMessage("已保存布局存档「${a.title}」（${a.nodeCount} 张卡片）")
                        },
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentNeonGreen,
                            contentColor = Color.Black
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 10.dp
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("保存", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "已保存的存档",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))

                val list = store.listFor(conversationId)
                if (list.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("还没有存档", color = TextMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(list, key = { it.id }) { a ->
                            ArchiveRow(
                                archive = a,
                                onRestore = {
                                    val ok = store.restore(a, graphState)
                                    onMessage(
                                        if (ok) "已恢复到「${a.title}」"
                                        else "「${a.title}」里的卡片都已被删除，无法恢复"
                                    )
                                    if (ok) onDismiss()
                                },
                                onRename = { renaming = a },
                                onDelete = {
                                    store.delete(a.id)
                                    onMessage("已删除存档「${a.title}」")
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = TextSecondary, fontSize = 13.sp)
            }
        }
    )

    // ---- 重命名 ----
    renaming?.let { target ->
        var draft by remember { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = PanelSurface,
            title = { Text("重命名存档", color = AccentCyan, fontSize = 14.sp) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.rename(target.id, draft)
                    renaming = null
                }) { Text("保存", color = AccentNeonGreen, fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text("取消", color = TextSecondary, fontSize = 13.sp)
                }
            }
        )
    }
}

@Composable
private fun ArchiveRow(
    archive: LayoutArchive,
    onRestore: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF1B1F26))
            .border(1.dp, Color(0xFF2C323D), RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = archive.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${archive.timeLabel()} · ${archive.nodeCount} 张卡片",
                color = TextMuted,
                fontSize = 10.sp
            )
        }
        RowIcon(Icons.Default.Restore, "恢复这份存档", AccentNeonGreen, onRestore)
        Spacer(Modifier.width(2.dp))
        RowIcon(Icons.Default.Edit, "重命名", TextSecondary, onRename)
        Spacer(Modifier.width(2.dp))
        RowIcon(Icons.Default.Delete, "删除", Color(0xFFFF8A80), onDelete)
    }
}

@Composable
private fun RowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(16.dp))
    }
}