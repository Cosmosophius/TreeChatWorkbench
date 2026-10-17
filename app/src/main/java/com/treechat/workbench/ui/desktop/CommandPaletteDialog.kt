package com.treechat.workbench.ui.desktop

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.DarkSurface
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary

/**
 * 桌面级命令面板动作项。
 */
data class DesktopCommand(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val icon: ImageVector,
    val shortcutHint: String? = null,
    val onExecute: () -> Unit
)

/**
 * 桌面级快速命令面板 (Command Palette)。
 * 支持快捷键 Ctrl/Cmd + K 快速唤起，提供全局动作即搜即用体验。
 */
@Composable
fun CommandPaletteDialog(
    commands: List<DesktopCommand>,
    onDismiss: () -> Unit,
    /**
     * 初始关键词（从快捷键带过来时用）。
     *
     * 为什么需要：Ctrl+F 与 Ctrl+K 现在是同一个面板，但 Ctrl+F 语义是"检索"，
     * 应该直接落到输入框可打字，而不是还要用户自己再点一下。
     */
    initialQuery: String = "",
    /** 检索范围（全部对话 / 某一个）。null 表示不显示这排筛选。 */
    searchScope: com.treechat.workbench.data.db.SearchScope? = null,
    onSearchScopeChange: (com.treechat.workbench.data.db.SearchScope) -> Unit = {},
    /** 检索类型（标题与消息 / 仅标题 / 仅消息）。 */
    searchType: com.treechat.workbench.data.db.SearchType? = null,
    onSearchTypeChange: (com.treechat.workbench.data.db.SearchType) -> Unit = {},
    /** 可选会话列表（范围筛选器的选项来源）。 */
    searchConversations: List<com.treechat.workbench.data.db.ConversationSummary> = emptyList(),
    /** 关键词变化时回调，让上层去触发检索。 */
    onQueryChange: (String) -> Unit = {}
) {
    var query by remember { mutableStateOf(initialQuery) }
    // 只有"面板已打开且 query 变了"才上报，避免初始值重复触发一次查询
    LaunchedEffect(query) { if (query != initialQuery) onQueryChange(query) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val filtered = remember(query, commands) {
        if (query.isBlank()) {
            commands
        } else {
            val q = query.trim().lowercase()
            commands.filter {
                it.title.lowercase().contains(q) ||
                    it.subtitle.lowercase().contains(q) ||
                    it.category.lowercase().contains(q)
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF16191E))
                .border(1.dp, Color(0xFF2C323D), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 搜索输入框行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AccentNeonGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text = "输入命令或动作搜索… (Esc 退出)",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.SansSerif
                            ),
                            cursorBrush = SolidColor(AccentNeonGreen),
                            singleLine = true
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清除",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // ---- 检索筛选：范围 + 类型 ----
                //
                // 只在"确实在做检索"（query 非空且上层给了范围/类型）时出现，
                // 平时搜命令时不占地方。
                if (searchType != null || searchScope != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (searchType != null) {
                            com.treechat.workbench.ui.search.PaletteFilterRow(
                                options = com.treechat.workbench.data.db.SearchType.entries.toList(),
                                selected = searchType,
                                labelOf = { it.displayName },
                                onSelect = onSearchTypeChange
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (searchScope != null) {
                            com.treechat.workbench.ui.search.PaletteFilterRow(
                                options = listOf(
                                    com.treechat.workbench.data.db.SearchScope.AllConversations
                                ) + searchConversations.map {
                                    com.treechat.workbench.data.db.SearchScope.OneConversation(it.id)
                                },
                                selected = searchScope,
                                labelOf = { sc ->
                                    when (sc) {
                                        is com.treechat.workbench.data.db.SearchScope.AllConversations ->
                                            "全部对话"
                                        is com.treechat.workbench.data.db.SearchScope.OneConversation ->
                                            searchConversations.firstOrNull { it.id == sc.conversationId }
                                                ?.title?.take(12)?.ifBlank { "未命名对话" } ?: "某个对话"
                                    }
                                },
                                onSelect = onSearchScopeChange
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color(0xFF242932))
                )

                // 动作列表
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "未找到匹配的命令",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        itemsIndexed(filtered, key = { _, item -> item.id }) { _, cmd ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        cmd.onExecute()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF202630)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = cmd.icon,
                                        contentDescription = null,
                                        tint = AccentNeonGreen,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cmd.title,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = cmd.subtitle,
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                if (cmd.shortcutHint != null) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF232934))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = cmd.shortcutHint,
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
