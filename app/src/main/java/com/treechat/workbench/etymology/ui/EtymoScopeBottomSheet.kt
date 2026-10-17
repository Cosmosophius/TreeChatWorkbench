package com.treechat.workbench.etymology.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.etymology.engine.IpaAudioSynthesizer
import com.treechat.workbench.etymology.repository.EtymologyRepository

/**
 * 跨语音韵与词源面板（EtymoScope）——正文选词/阅读器/浏览器/对话里跳进来的全宽词典面板。
 *
 * 核心升级：
 * 1. 突破宽度限制：采用全屏宽度（[sheetMaxWidth] = [Dp.Unspecified] + fillMaxWidth()）；
 * 2. 双模式切换：顶部增加【并列卡片】与【思维导图】FilterChip 模式切换；
 * 3. 思维导图集成：切换到思维导图时，调用 [DictMindMap]，支持卡片自由拖拽、贝塞尔连线平滑跟随及画布内浮动详情小窗；
 * 4. 保持对外签名不变，既有调用点（阅读器/浏览器/对话/主界面）无缝兼容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtymoScopeBottomSheet(
    initialQuery: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // 每次发音后自增，驱动系统语音自检重新读取实际选中的 voice / 是否回落。
    var spokenTick by remember { mutableStateOf(0) }
    val speaker = rememberSpeaker(onSpoken = { spokenTick++ })

    var searchQuery by remember { mutableStateOf(initialQuery) }
    var currentSpeed by remember { mutableStateOf(IpaAudioSynthesizer.speechRate) }
    // 已折叠卡片的 id 串（长参考卡片默认折叠）；面板开着时（含转屏）保持。
    var collapsedIds by rememberSaveable { mutableStateOf(DEFAULT_COLLAPSED_CARD_IDS) }
    // 视图模式：并列卡片 vs 思维导图
    var mindMapMode by rememberSaveable { mutableStateOf(false) }

    val alignment = remember(searchQuery) {
        if (searchQuery.isBlank()) null else EtymologyRepository.resolve(searchQuery)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetMaxWidth = Dp.Unspecified,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部控制区（固定 12dp 水平间距）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "跨语音韵与词源",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "全息词源 · 跨语平行对叠 · 思维导图与并列卡片双重视角",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 查词框
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("汉 / 英 / 俄 词，如 同化 · light · вода", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // 发音倍速与视图模式切换行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 视图模式切换 FilterChip
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = !mindMapMode,
                            onClick = { mindMapMode = false },
                            label = { Text("并列卡片", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = mindMapMode,
                            onClick = { mindMapMode = true },
                            label = { Text("思维导图", fontSize = 11.sp) }
                        )
                    }

                    // 发音倍速调节
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "倍速",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        listOf(0.5f, 0.75f, 1.0f, 1.25f).forEach { rate ->
                            FilterChip(
                                selected = currentSpeed == rate,
                                onClick = {
                                    currentSpeed = rate
                                    IpaAudioSynthesizer.speechRate = rate
                                },
                                label = { Text("${rate}x", fontSize = 10.sp) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
            }

            // 主展示区
            val report = alignment
            if (report == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "输入汉语词（可多字，如「同化」「面包」）、英语词或俄语词开始查询。\n" +
                            "支持在「并列卡片」与「思维导图」之间自由切换；\n" +
                            "在思维导图模式下可自由缩放平移、拖拽卡片调整布局并点开画布浮动详情小窗。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                if (mindMapMode) {
                    // 思维导图模式：撑满全屏宽度，提供无边界漫游与拖拽交互
                    val cards = remember(report, searchQuery) { buildDictCards(report, searchQuery) }
                    DictMindMap(
                        cards = cards,
                        query = searchQuery,
                        onSpeak = { text, ipa, accent -> speaker(text, ipa, accent) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    // 并列卡片模式
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    ) {
                        DictBoard(
                            alignment = report,
                            query = searchQuery,
                            speaker = speaker,
                            collapsedIds = collapsedIds,
                            onToggleCard = { id -> collapsedIds = toggleCollapsedId(collapsedIds, id) },
                            onWordClick = { searchQuery = it },
                            modifier = Modifier.fillMaxSize(),
                            footer = { VoiceDiagnostics(refreshKey = spokenTick) }
                        )
                    }
                }
            }
        }
    }
}

/** 系统语音自检块（卡片版的底部整行内容）。 */
@Composable
private fun ColumnScope.VoiceDiagnostics(refreshKey: Int) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "系统语音自检",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "中古 / 上古 / 保守 RP 三类由内置共振峰合成器生成（界面上标「规则推导/合成」）；" +
                    "缺目标语音时不冒充，如实写明回落到了哪一支。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(4.dp))
            VoiceSelfCheck(refreshKey = refreshKey)
        }
    }
}
