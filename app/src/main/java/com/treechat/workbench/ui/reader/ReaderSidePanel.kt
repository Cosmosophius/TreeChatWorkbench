package com.treechat.workbench.ui.reader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.data.prefs.InkCardSnapshot
import com.treechat.workbench.etymology.data.VocabIndex
import org.json.JSONArray

/**
 * 阅读器右侧**可调**边栏（考研 AI 式）：生词 / 词条 / AI / 注释 四页签。
 *
 * 与参考软件同构的交互（截图口径）：
 *   · 宽度可拖；收起后正文书页占满；
 *   · 「生词」页：本页生词计数 + 词条列表（点词跳「词条」页）；
 *   · 「词条」页：释义 / 音标 / 词性 / 派生；可收藏（收藏词变化存 SharedPreferences）；
 *   · 「AI」页：当前选区的快捷动作 + 追问输入（发到这本书的根树分支）；
 *   · 「注释」页：本书的读书笔记卡片列表。
 *
 * ⚠️ 状态一律进 [ReaderPanelState]（SharedPreferences 持久化），
 * **不**新增 Room 表列（全局纪律）。
 */

/** 边栏页签。 */
enum class PanelTab(val label: String) {
    VOCAB("生词"),
    ENTRY("词条"),
    /** 句法标注卡（模型切分结果）；分析完成后自动切到这里。 */
    SYNTAX("句法"),
    AI("AI"),
    NOTES("注释")
}

/**
 * 边栏的持久状态。
 *
 * 用 SharedPreferences（`treechat_reader_panel`）：布局与考纲选择属于纯界面状态。
 */
class ReaderPanelState private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("treechat_reader_panel", Context.MODE_PRIVATE)

    var expanded: Boolean by mutableStateOf(prefs.getBoolean(KEY_EXPANDED, true))
        private set
    var widthDp: Int by mutableStateOf(prefs.getInt(KEY_WIDTH, 280))
        private set
    var tab: PanelTab by mutableStateOf(
        runCatching { PanelTab.valueOf(prefs.getString(KEY_TAB, null).orEmpty()) }
            .getOrDefault(PanelTab.VOCAB)
    )
        private set

    /** 考纲生词匹配总开关（"关闭考纲"就是这个）。 */
    var examEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_EXAM_ON, false))
        private set

    /** 启用的考纲 token（可多选；交集判命中）。 */
    var examTokens: Set<String> by mutableStateOf(
        prefs.getString(KEY_EXAM_TOKENS, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { s -> s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            ?: setOf("cet6")
    )
        private set

    /** 收藏词（小写）。走 JSON 数组存一行。 */
    var favoriteWords: Set<String> by mutableStateOf(
        prefs.getString(KEY_FACTS, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { s -> runCatching { JSONArray(s) }.getOrDefault(JSONArray()) }
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }.toSet() }
            ?: emptySet()
    )
        private set

    fun applyExpanded(v: Boolean) {
        expanded = v
        prefs.edit().putBoolean(KEY_EXPANDED, v).apply()
    }

    fun applyWidth(v: Int) {
        widthDp = v.coerceIn(MIN_WIDTH, MAX_WIDTH)
        prefs.edit().putInt(KEY_WIDTH, widthDp).apply()
    }

    fun applyTab(v: PanelTab) {
        tab = v
        prefs.edit().putString(KEY_TAB, v.name).apply()
    }

    fun applyExamEnabled(v: Boolean) {
        examEnabled = v
        prefs.edit().putBoolean(KEY_EXAM_ON, v).apply()
    }

    fun applyExamTokens(v: Set<String>) {
        examTokens = v
        prefs.edit().putString(KEY_EXAM_TOKENS, v.joinToString(",")).apply()
    }

    fun toggleFavorite(word: String) {
        val key = word.trim().lowercase()
        val next = if (key in favoriteWords) favoriteWords - key else favoriteWords + key
        favoriteWords = next
        prefs.edit().putString(KEY_FACTS, JSONArray(next.toList()).toString()).apply()
    }

    fun isFavorite(word: String): Boolean = word.trim().lowercase() in favoriteWords

    companion object {
        const val MIN_WIDTH = 200
        const val MAX_WIDTH = 480

        private const val KEY_EXPANDED = "expanded"
        private const val KEY_WIDTH = "width_dp"
        private const val KEY_TAB = "tab"
        private const val KEY_EXAM_ON = "exam_enabled"
        private const val KEY_EXAM_TOKENS = "exam_tokens"
        private const val KEY_FACTS = "favorite_words"

        @Volatile private var singleton: ReaderPanelState? = null

        fun obtain(context: Context): ReaderPanelState =
            singleton ?: synchronized(this) {
                singleton ?: ReaderPanelState(context).also { singleton = it }
            }
    }
}

/** 「AI」页需要的动作（宿主提供，边栏只摆放）。 */
data class PanelAiActions(
    /** 用当前选区提问（走 createQuestionInSourceTree + 打开对话小窗）。 */
    val onAsk: () -> Unit = {},
    /** 用当前选区做句法分析。 */
    val onSyntax: () -> Unit = {},
    /** 用当前选区整段翻译。 */
    val onTranslate: () -> Unit = {},
    /** 追问输入提交（同 onAsk 的对话路径）。 */
    val onFollowUp: (String) -> Unit = {}
)

/** 「生词」页头部的当前页摘要。 */
data class VocabPageSummary(
    /** 当前页命中总数（不去重，对应"本页生词 N"）。 */
    val count: Int,
    /** 去重后的词条（供列表）。 */
    val unique: List<VocabHit>
)

@Composable
fun ReaderSidePanel(
    state: ReaderPanelState,
    summary: VocabPageSummary,
    /** 「词条」页当前要展示的词（null = 提示去正文选词）。 */
    focusedWord: String?,
    notes: List<InkCardSnapshot>,
    ai: PanelAiActions,
    /** 句法标注卡数据（非空时「句法」页签可看结果）。 */
    syntaxSource: String = "",
    syntaxUnits: List<com.treechat.workbench.ui.reader.SyntaxUnit> = emptyList(),
    syntaxUnlocated: Int = 0,
    syntaxFailure: String? = null,
    syntaxAnalyzing: Boolean = false,
    /** 收起（清空）句法标注。 */
    onSyntaxClear: () -> Unit = {},
    /** 句法卡上的「翻译本段」（交给宿主开翻译小窗）。 */
    onSyntaxTranslate: () -> Unit = {},
    /** 点生词列表某词 → 跳「词条」页展示。 */
    onPickWord: (String) -> Unit,
    onWordFocused: (String?) -> Unit,
    onOpenNotesBoard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    // ---- 完全收起：右侧只剩一条细竖钮，点击重新展开 ----
    if (!state.expanded) {
        Surface(
            color = scheme.surfaceContainerHighest,
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp),
            modifier = modifier.width(26.dp).fillMaxHeight()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxHeight()
            ) {
                IconButton(onClick = { state.applyExpanded(true) }) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronLeft,
                        contentDescription = "展开侧栏",
                        tint = scheme.onSurfaceVariant
                    )
                }
                VerticalDivider()
                Text(
                    text = "栏",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
        return
    }

    Box(
        modifier = modifier
            .width(state.widthDp.dp)
            .fillMaxHeight()
    ) {
        Surface(
            color = scheme.surfaceContainerLow,
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            Column {
                // ---- 页签行 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PanelTab.entries.forEach { candidate ->
                        FilterChip(
                            selected = candidate == state.tab,
                            onClick = { state.applyTab(candidate) },
                            label = { Text(candidate.label, fontSize = 12.sp) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 6.dp))

                when (state.tab) {
                    PanelTab.VOCAB -> VocabPane(state, summary, onPickWord)
                    PanelTab.ENTRY -> EntryPane(state, focusedWord, onWordFocused)
                    PanelTab.AI -> AiPane(state, ai, focusedWord)
                    PanelTab.NOTES -> NotesPane(notes, onOpenNotesBoard)
                    PanelTab.SYNTAX -> SyntaxPane(
                        sourceSentence = syntaxSource,
                        units = syntaxUnits,
                        unlocatedCount = syntaxUnlocated,
                        failureReason = syntaxFailure,
                        analyzing = syntaxAnalyzing,
                        onCollapse = onSyntaxClear,
                        onTranslate = onSyntaxTranslate
                    )
                }
            }
        }

        // ---- 左缘拖动把手：横向拖改变宽度 ----
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(14.dp)
                .fillMaxHeight()
                .background(scheme.surfaceContainerHighest.copy(alpha = 0.24f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            // 把手贴住手指：向左拖（dx<0）= 面板变宽；
                            // 旧写法方向反了，"向左拖想要变宽结果变窄"。
                            state.applyWidth(state.widthDp - (delta.x / density.density).toInt())
                            change.consume()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "拖动调整宽度",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 「生词」页：本页计数 + 考纲开关与类目选择 + 词条列表。 */
@Composable
private fun VocabPane(
    state: ReaderPanelState,
    summary: VocabPageSummary,
    onPickWord: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var catalogOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(
                text = "本页生词 ${summary.count}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = state.examEnabled,
                onCheckedChange = { state.applyExamEnabled(it) },
                modifier = Modifier.height(26.dp)
            )
        }

        if (state.examEnabled) {
            // 考纲类目多选（FilterChip），改选即时生效
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                VocabIndex.EXAM_CATEGORIES.forEach { category ->
                    FilterChip(
                        selected = category.token in state.examTokens,
                        onClick = {
                            val next = if (category.token in state.examTokens) {
                                state.examTokens - category.token
                            } else {
                                state.examTokens + category.token
                            }
                            state.applyExamTokens(next)
                        },
                        label = { Text(category.label, fontSize = 11.sp) }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        if (!state.examEnabled) {
            Text(
                text = "已关闭考纲。右上开关恢复匹配后，正文会为命中词画下划线，这里列出本页全部生词。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(summary.unique, key = { it.word.lowercase() }) { hit ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .background(scheme.primary, RoundedCornerShape(1.5.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = hit.word,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onPickWord(hit.word) }
                        )
                        Text(
                            text = hit.tags.joinToString(" ") { it.label },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.outline
                        )
                    }
                }
            }
        }
    }
}

/** 「词条」页：选中词的离线释义；右上限点收藏。 */
@Composable
private fun EntryPane(
    state: ReaderPanelState,
    focusedWord: String?,
    onFocused: (String?) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val entry = focusedWord?.let { VocabIndex.english(it) }

    if (focusedWord == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "在正文里选一个词",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "查的是内置离线词库（考研词表 + ECDICT 常用词核心）。也可以在「生词」页点任意词跳到这里。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.outline
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry?.word ?: focusedWord,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { state.toggleFavorite(focusedWord) }) {
                    Icon(
                        imageVector = if (state.isFavorite(focusedWord)) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (state.isFavorite(focusedWord)) "取消收藏词" else "收藏词",
                        tint = if (state.isFavorite(focusedWord)) scheme.primary else scheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = entry?.ipa?.ifBlank { null } ?: "（词库里没有音标）",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = entry?.gloss?.ifBlank { null } ?: "未收录：这个词不在内置词库中。",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = if (entry?.gloss?.isBlank() == false) scheme.onSurface else scheme.onSurfaceVariant
            )
            val derived = VocabIndex.derivedForms(focusedWord)
            if (derived.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "词形",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    text = derived.joinToString("　") { "${it.label} ${it.form}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { onFocused(null) }) { Text("清除") }
        }
    }
}

/** 「AI」页：当前选区快捷动作 + 追问。 */
@Composable
private fun AiPane(
    state: ReaderPanelState,
    ai: PanelAiActions,
    focusedWord: String?
) {
    val scheme = MaterialTheme.colorScheme
    var followUp by remember { mutableStateOf("") }
    val hasSelection = focusedWord != null

    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Text(
            text = if (hasSelection) "当前选择：$focusedWord" else "在正文里选好后用下面这些",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = ai.onAsk, label = { Text("提问") })
            AssistChip(onClick = ai.onTranslate, label = { Text("翻译") })
            AssistChip(onClick = ai.onSyntax, label = { Text("句法") })
        }
        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "追问",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = followUp,
            onValueChange = { followUp = it },
            placeholder = { Text("接着就这一页/这一段继续问…", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                enabled = followUp.isNotBlank(),
                onClick = {
                    val q = followUp.trim()
                    followUp = ""
                    ai.onFollowUp(q)
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("发送")
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "追问会挂在这本书的研读分支下，与正文里选词提问走完全相同的一条对话链路。",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.outline
        )
    }
}

/** 「注释」页：本书读书笔记卡片列表。 */
@Composable
private fun NotesPane(
    notes: List<InkCardSnapshot>,
    onOpenNotesBoard: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Notes, contentDescription = null, tint = scheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "读书笔记 ${notes.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpenNotesBoard) { Text("打开白板") }
        }

        if (notes.isEmpty()) {
            Text(
                text = "还没有注释。在正文工具条里「划线」或「笔记」会同步到这里（同一本书归并进一块白板）。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.id.ifBlank { it.title + it.x + it.y } }) { card ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        color = scheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = card.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!card.quote.isNullOrBlank()) {
                                Text(
                                    text = card.quote,
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                    color = scheme.onSurface,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!card.summary.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = card.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.outline,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「句法」页：展示模型切分出的句法标注卡。
 *
 * ⚠️ 按用户要求（2026-10-17）：句法标注**不再画在原文/页面上**，
 * 统一显示在这个右侧栏位 —— 分析完成后自动切到这里、自动展开边栏。
 */
@Composable
private fun SyntaxPane(
    sourceSentence: String,
    units: List<SyntaxUnit>,
    unlocatedCount: Int,
    failureReason: String?,
    analyzing: Boolean,
    onCollapse: () -> Unit,
    onTranslate: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    if (analyzing) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "正在请模型切分句法…",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    } else if (failureReason != null) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Text(
                text = "句法切分失败：$failureReason",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error
            )
        }
    } else if (units.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Text(
                text = "还没有句法标注",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "在正文里选一句话，点工具条的「句法」，切分结果会出现在这里 —— 不再压在原文上。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.outline
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            SyntaxSentenceCard(
                sourceSentence = sourceSentence,
                units = units,
                unlocatedCount = unlocatedCount,
                failureReason = null,
                analyzing = false,
                onTranslate = {}, // 翻译动作仍在正文工具条；此处只展示
                onCollapse = onCollapse
            )
        }
    }
}