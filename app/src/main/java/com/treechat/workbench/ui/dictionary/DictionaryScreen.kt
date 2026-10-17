package com.treechat.workbench.ui.dictionary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.data.model.WordExample
import com.treechat.workbench.data.model.WordFavorite
import com.treechat.workbench.data.model.WordKind
import com.treechat.workbench.etymology.data.CrossLinguisticAlignment
import com.treechat.workbench.etymology.data.VocabIndex
import com.treechat.workbench.etymology.repository.EtymologyRepository
import com.treechat.workbench.etymology.ui.DEFAULT_COLLAPSED_CARD_IDS
import com.treechat.workbench.etymology.ui.DictBoard
import com.treechat.workbench.etymology.ui.DictMindMap
import com.treechat.workbench.etymology.ui.buildDictCards
import com.treechat.workbench.etymology.ui.EtymoScopeBottomSheet
import com.treechat.workbench.etymology.ui.IpaDisplay
import com.treechat.workbench.etymology.ui.isRecorded
import com.treechat.workbench.etymology.ui.rememberSpeaker
import com.treechat.workbench.etymology.ui.toggleCollapsedId
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import com.treechat.workbench.ui.components.SwipeDirection
import com.treechat.workbench.ui.components.SwipeableCardStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 词典模块主界面（三语查词 + 单词本收藏夹）。
 *
 * 信息架构（本次重做）：
 * - 查词结果不再是纵向信息流，而是**并列的可展开卡片板**（[DictBoard]）：
 *   现代汉语 / 中古音 / 上古音 / 字义 / 英语现代音 / 英语保守 RP / 英语释义 /
 *   俄语标准音 / 俄语释义 / **词源与来源** / 同义词 / 数据来源与许可，各占一张卡片，
 *   点标题栏折叠展开；**不同语言的音标绝不塞进同一列**；
 * - 词语来源直接可见：「词源与来源」卡片列出来源语族（EtymWN 源词语言码，查表）
 *   与词源关系；每条数据点标注来源，规则推导项显式标「规则推导」，查不到写"未收录"；
 * - 「音标 · 词源（全量）」拉起 [EtymoScopeBottomSheet]，它用的是**同一套卡片**；
 * - 收藏夹沿用 [SwipeableCardStack]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(
    viewModel: ChatNodeViewModel,
    /** 「选词提问」：把 (会话标题, 问题) 交给上层落到「词典」文件夹并开窗。 */
    onAskWord: (title: String, question: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val favorites by viewModel.wordFavorites.collectAsState()

    var query by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) } // 0=查词 1=收藏夹
    var sheetQuery by remember { mutableStateOf<String?>(null) }
    var searchMindMapMode by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {

        // ---------------- 顶部搜索框 ----------------
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text("输入汉语词（同化 / 面包）、英语（light）或俄语（вода）", fontSize = 12.sp)
            },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )

        // ---------------- 模式切换：查词 / 收藏夹 ----------------
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            SegmentedButton(
                selected = mode == 0,
                onClick = { mode = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text("查词") }
            )
            SegmentedButton(
                selected = mode == 1,
                onClick = { mode = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text("收藏夹 (${favorites.size})") }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (mode == 0) {
            SearchPane(
                query = query,
                viewModel = viewModel,
                favorites = favorites,
                onOpenEtymo = { sheetQuery = it },
                onWordClick = { query = it },
                onAskWord = onAskWord,
                scope = scope,
                initialMindMapMode = searchMindMapMode,
                onMindMapToggled = { searchMindMapMode = it }
            )
        } else {
            FavoritesPane(
                favorites = favorites,
                viewModel = viewModel,
                onUnfavorite = { word, kind -> viewModel.removeWordFavorite(word, kind) },
                onOpenEtymo = { sheetQuery = it },
                onOpenMindMap = { word ->
                    query = word
                    searchMindMapMode = true
                    mode = 0
                }
            )
        }
    }

    // 音标 · 词源全息面板
    sheetQuery?.let { q ->
        EtymoScopeBottomSheet(
            initialQuery = q,
            onDismissRequest = { sheetQuery = null }
        )
    }
}

// ============================================================================
// 查词面板（并列卡片板）
// ============================================================================

@Composable
private fun SearchPane(
    query: String,
    viewModel: ChatNodeViewModel,
    favorites: List<WordFavorite>,
    onOpenEtymo: (String) -> Unit,
    onWordClick: (String) -> Unit,
    onAskWord: (title: String, question: String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    initialMindMapMode: Boolean = false,
    onMindMapToggled: ((Boolean) -> Unit)? = null
) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "输入一个汉语词、英语词或俄语词开始查词。\n" +
                    "汉语可多字（如「同化」「面包」）：逐字给音韵、整词查汉俄对译。\n" +
                    "结果按「现代汉语 / 中古音 / 上古音 / 字义 / 英语 / 俄语 / 词源与来源 / 同义词 / " +
                    "数据来源」并列成卡片，点卡片标题栏可折叠展开。\n" +
                    "查到的词可以收藏进单词本，也可以就它直接发问（会话会归入「词典」文件夹）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    // 词源索引是同步查表，这里直接解析即可（数据在应用启动时已预热）。
    val alignment = remember(trimmed) { EtymologyRepository.resolve(trimmed) }
    val kind = remember(trimmed) { WordKind.detect(trimmed) }
    val headword = remember(trimmed, alignment) { headwordOf(kind, trimmed, alignment) }
    val speaker = rememberSpeaker()

    val isFav = favorites.any { it.word == headword && it.kind == kind.name }

    // 卡片折叠状态：以 id 串持有，跨查询保持（不落库）；长参考卡片默认折叠。
    var collapsedIds by rememberSaveable { mutableStateOf(DEFAULT_COLLAPSED_CARD_IDS) }

    var questionDraft by remember(trimmed) { mutableStateOf("") }
    var generating by remember(trimmed) { mutableStateOf(false) }
    var genMessage by remember(trimmed) { mutableStateOf<String?>(null) }
    val examplesFlow = remember(headword) { viewModel.observeWordExamples(headword) }
    val examples by examplesFlow.collectAsState(initial = emptyList())

    // ---------------- 云端翻译（译文只留内存态，不入库，切词即失效） ----------------
    val rawGloss = remember(headword) {
        VocabIndex.english(headword)?.gloss?.trim().orEmpty()
            .ifEmpty { VocabIndex.russian(headword)?.gloss?.trim().orEmpty() }
    }
    var translation by remember(headword) { mutableStateOf("") }
    var translating by remember(headword) { mutableStateOf(false) }
    var translationError by remember(headword) { mutableStateOf<String?>(null) }
    // 提前拦一刀的原因：非空时只显示文案、不发请求（与 ReaderScreen 同口径）。
    val unavailableReason = viewModel.oneShotUnavailableReason()
    // 专属协程作用域：SearchPane 离开组合（切页/关屏）时自动取消在途的流式请求。
    val translateScope = rememberCoroutineScope()

    val startTranslation: () -> Unit = {
        translation = ""
        translationError = null
        translating = true
        translateScope.launch {
            try {
                viewModel.streamEntryTranslation(headword, rawGloss)
                    .collect { delta -> translation += delta }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                translationError = e.localizedMessage ?: "翻译失败"
            } finally {
                translating = false
            }
        }
    }

    // 展示模式：false=并列可展开卡片（默认），true=思维导图模式（对齐工作台画布）
    var mindMapMode by rememberSaveable(trimmed, initialMindMapMode) { mutableStateOf(initialMindMapMode) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !mindMapMode,
                onClick = {
                    mindMapMode = false
                    onMindMapToggled?.invoke(false)
                },
                label = { Text("并列卡片", fontSize = 11.sp) }
            )
            Spacer(modifier = Modifier.width(6.dp))
            FilterChip(
                selected = mindMapMode,
                onClick = {
                    mindMapMode = true
                    onMindMapToggled?.invoke(true)
                },
                label = { Text("思维导图（工作台样式）", fontSize = 11.sp) }
            )
        }

        if (mindMapMode) {
            val cards = remember(alignment, trimmed) { buildDictCards(alignment, trimmed) }
            DictMindMap(
                cards = cards,
                query = trimmed,
                onSpeak = { text, ipa, accent -> speaker(text, ipa, accent) },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    DictBoard(
            alignment = alignment,
            query = trimmed,
            speaker = speaker,
            collapsedIds = collapsedIds,
            onToggleCard = { id -> collapsedIds = toggleCollapsedId(collapsedIds, id) },
            onWordClick = onWordClick,
            onAskWord = onAskWord,
            heroAction = {
                IconButton(onClick = {
                    if (isFav) {
                        viewModel.removeWordFavorite(headword, kind)
                    } else {
                        viewModel.addWordFavorite(
                            word = headword,
                            kind = kind,
                            pinyin = favoritePinyin(kind, alignment),
                            ipa = favoriteIpa(kind, alignment),
                            gloss = alignment.summary
                        )
                    }
                }) {
                    Icon(
                        imageVector = if (isFav) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (isFav) "取消收藏" else "收藏",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            heroFooter = {
                TextButton(onClick = { onOpenEtymo(trimmed) }) {
                    Icon(
                        Icons.Outlined.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("音标 · 词源（全量）", fontSize = 12.sp)
                }
            },
            footer = {
                // ---------------- 例句 ----------------
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "例句 (${examples.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                enabled = !generating,
                                onClick = {
                                    generating = true
                                    genMessage = null
                                    scope.launch {
                                        val result = viewModel.generateWordExample(headword, kind)
                                        result.fold(
                                            onSuccess = { pair ->
                                                viewModel.addWordExample(headword, pair.first, pair.second)
                                                genMessage = "已追加一条例句"
                                            },
                                            onFailure = { error ->
                                                genMessage = "生成失败：${error.localizedMessage ?: "未知错误"}"
                                            }
                                        )
                                        generating = false
                                    }
                                }
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (generating) "生成中…" else "生成例句", fontSize = 12.sp)
                            }
                        }
    
                        if (generating) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
    
                        genMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
    
                        Spacer(modifier = Modifier.height(4.dp))
                        if (examples.isEmpty() && !generating) {
                            Text(
                                text = "还没有例句。点「生成例句」会追加一条，可以反复点，越积越多。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            examples.forEach { example ->
                                ExampleRow(example = example, onDelete = { viewModel.deleteWordExample(example.id) })
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ---------------- 云端翻译 ----------------
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "云端翻译",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (translation.isNotBlank() && !translating) {
                                TextButton(onClick = {
                                    translation = ""
                                    translationError = null
                                }) {
                                    Text("清空", fontSize = 12.sp)
                                }
                            }
                        }
                        Text(
                            text = "由当前已配置的云端模型把词库释义译成简体汉语（译文不入库，切词即失效）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        when {
                            // 模型不可用：把原因当面说清，一个请求都不发。
                            unavailableReason != null -> Text(
                                text = unavailableReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )

                            rawGloss.isBlank() -> Text(
                                text = "该词在词库中无释义原文，无法翻译",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )

                            else -> Button(
                                enabled = !translating,
                                onClick = startTranslation
                            ) {
                                Text(if (translating) "翻译中…" else "翻译释义", fontSize = 12.sp)
                            }
                        }

                        if (translating) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        // 失败原因如实显示，已产出的半截译文也留着，不伪装成成功。
                        translationError?.let { message ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "翻译失败：$message",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(
                                enabled = !translating && unavailableReason == null && rawGloss.isNotBlank(),
                                onClick = startTranslation
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("重试", fontSize = 12.sp)
                            }
                        }

                        if (translation.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            SelectionContainer {
                                Text(
                                    text = translation,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ---------------- 选词提问 ----------------
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "选词提问",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = questionDraft,
                            onValueChange = { questionDraft = it },
                            placeholder = { Text("就「$headword」提问…", fontSize = 12.sp) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            enabled = questionDraft.isNotBlank(),
                            onClick = {
                                val title = "词典 · $headword"
                                onAskWord(title, questionDraft.trim())
                                questionDraft = ""
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("在「词典」文件夹提问", fontSize = 12.sp)
                        }
                    }
                }
            }
        )
            }
        }
    }
}

// ============================================================================
// 收藏夹面板（SwipeableCardStack）
// ============================================================================

@Composable
private fun FavoritesPane(
    favorites: List<WordFavorite>,
    viewModel: ChatNodeViewModel,
    onUnfavorite: (String, WordKind) -> Unit,
    onOpenEtymo: (String) -> Unit,
    onOpenMindMap: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val speaker = rememberSpeaker()
    val scheme = MaterialTheme.colorScheme

    // 文件夹与映射状态（轻量 SharedPreferences 持久化）
    var foldersRefreshTick by remember { mutableIntStateOf(0) }
    val folders = remember(foldersRefreshTick) { WordFolderStore.getFolders(context) }
    val wordFolderMap = remember(foldersRefreshTick) { WordFolderStore.getWordFolderMap(context) }

    var currentFolderId by remember { mutableStateOf<String?>(null) }
    val currentFolder = folders.find { it.id == currentFolderId }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // 视图模式：0=单词卡(飞卡栈), 1=MD3列表
    var viewMode by remember { mutableIntStateOf(0) }

    // 多选与长按选择删除状态
    var selectionMode by remember { mutableStateOf(false) }
    val selectedKeys = remember { mutableStateMapOf<String, Boolean>() }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // 依据当前文件夹过滤单词
    val displayWords = remember(favorites, currentFolderId, wordFolderMap) {
        if (currentFolderId == null) {
            favorites
        } else {
            favorites.filter { wordFolderMap[it.id] == currentFolderId }
        }
    }

    // 新建文件夹弹窗
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建单词文件夹", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("例如：考研必背、四级核心") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            WordFolderStore.addFolder(context, newFolderName.trim())
                            newFolderName = ""
                            showNewFolderDialog = false
                            foldersRefreshTick++
                        }
                    }
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("取消") }
            }
        )
    }

    // 批量删除确认弹窗
    if (showBatchDeleteDialog) {
        val count = selectedKeys.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("移出单词本", fontWeight = FontWeight.Bold) },
            text = { Text("确定要将选中的 $count 个单词移出单词本吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = displayWords.filter { selectedKeys.containsKey(it.id) }
                        toDelete.forEach { fav ->
                            onUnfavorite(fav.word, WordKind.valueOf(fav.kind))
                        }
                        selectedKeys.clear()
                        selectionMode = false
                        showBatchDeleteDialog = false
                    }
                ) {
                    Text("全部移出", color = scheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 4.dp)) {

        // ---- 顶部路径栏与多选操作条 ----
        if (selectionMode) {
            Surface(
                color = scheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        selectionMode = false
                        selectedKeys.clear()
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "退出多选")
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "已选 ${selectedKeys.size} 词",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val allSelected = displayWords.isNotEmpty() && displayWords.all { selectedKeys.containsKey(it.id) }
                            if (allSelected) {
                                selectedKeys.clear()
                            } else {
                                displayWords.forEach { selectedKeys[it.id] = true }
                            }
                        }
                    ) {
                        val allSelected = displayWords.isNotEmpty() && displayWords.all { selectedKeys.containsKey(it.id) }
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                    if (selectedKeys.isNotEmpty()) {
                        IconButton(onClick = { showBatchDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "批量删除", tint = scheme.error)
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentFolderId != null) {
                    IconButton(onClick = { currentFolderId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                    }
                    Text(
                        text = "全部 › ${currentFolder?.name ?: "文件夹"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = "单词本 (${favorites.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 新建文件夹按钮
                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹", tint = scheme.primary)
                }

                // 视图切换：单词卡 / 列表
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = viewMode == 0,
                        onClick = { viewMode = 0 },
                        label = { Text("卡片", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = viewMode == 1,
                        onClick = { viewMode = 1 },
                        label = { Text("列表", fontSize = 11.sp) }
                    )
                }
            }
        }

        // ---- 根目录下展示文件夹卡片网格 ----
        if (currentFolderId == null && folders.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "分类文件夹",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.outline,
                    modifier = Modifier.weight(1f)
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                modifier = Modifier.fillMaxWidth().height((if (folders.size > 2) 130 else 68).dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(folders, key = { it.id }) { f ->
                    val count = favorites.count { wordFolderMap[it.id] == f.id }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = scheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, scheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentFolderId = f.id }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$count 词", style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(6.dp))
        }

        if (displayWords.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (currentFolderId != null) "该文件夹还没有单词" else "单词本还是空的",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.outline
                )
            }
            return
        }

        // ---- 视图模式分支 ----
        if (viewMode == 0) {
            // 选项 1：3D 飞卡栈视图
            var currentIndex by remember(displayWords) { mutableIntStateOf(0) }
            LaunchedEffect(displayWords.size) {
                if (currentIndex >= displayWords.size) {
                    currentIndex = (displayWords.size - 1).coerceAtLeast(0)
                }
            }
            val deck = remember(displayWords, currentIndex) {
                if (displayWords.isEmpty()) emptyList()
                else List(displayWords.size) { displayWords[(currentIndex + it) % displayWords.size] }
            }

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "左右滑动切换（左滑下一张 · 右滑上一张） · 长按卡片多选删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline
                )
                Spacer(modifier = Modifier.height(6.dp))

                SwipeableCardStack(
                    items = deck,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSwiped = { direction, _ ->
                        if (displayWords.isNotEmpty()) {
                            currentIndex = if (direction == SwipeDirection.LEFT) {
                                (currentIndex + 1) % displayWords.size
                            } else {
                                (currentIndex - 1 + displayWords.size) % displayWords.size
                            }
                        }
                    },
                    expandedContent = { fav, onCollapse ->
                        FavoriteExpandedContent(
                            fav = fav,
                            viewModel = viewModel,
                            speaker = speaker,
                            onUnfavorite = onUnfavorite,
                            onOpenEtymo = onOpenEtymo,
                            onOpenMindMap = onOpenMindMap,
                            onCollapse = onCollapse
                        )
                    }
                ) { fav, onExpand ->
                    val isChecked = selectedKeys.containsKey(fav.id)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(fav.id, selectionMode) {
                                detectTapGestures(
                                    onTap = {
                                        if (selectionMode) {
                                            if (isChecked) selectedKeys.remove(fav.id) else selectedKeys[fav.id] = true
                                        } else {
                                            onExpand()
                                        }
                                    },
                                    onLongPress = {
                                        selectionMode = true
                                        selectedKeys[fav.id] = true
                                    }
                                )
                            }
                    ) {
                        FavoriteCard(
                            fav = fav,
                            index = currentIndex,
                            total = displayWords.size,
                            speaker = speaker,
                            onOpenEtymo = onOpenEtymo,
                            onOpenMindMap = onOpenMindMap,
                            onExpand = onExpand
                        )
                        if (selectionMode) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (isChecked) scheme.primary else scheme.surface.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                                    .size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isChecked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = null,
                                    tint = if (isChecked) scheme.onPrimary else scheme.outline,
                                    modifier = Modifier.padding(3.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 底部切换页码与按钮
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            if (displayWords.isNotEmpty()) {
                                currentIndex = (currentIndex - 1 + displayWords.size) % displayWords.size
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一张")
                    }

                    Text(
                        text = "${currentIndex + 1} / ${displayWords.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurfaceVariant
                    )

                    FilledTonalIconButton(
                        onClick = {
                            if (displayWords.isNotEmpty()) {
                                currentIndex = (currentIndex + 1) % displayWords.size
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一张")
                    }
                }
            }
        } else {
            // 选项 2：MD3 列表视图（支持点击查词、发音、长按选择多选删除）
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(displayWords, key = { it.id }) { fav ->
                    val isChecked = selectedKeys.containsKey(fav.id)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.surfaceContainerLow,
                        border = if (selectionMode && isChecked) BorderStroke(2.dp, scheme.primary) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(fav.id, selectionMode) {
                                detectTapGestures(
                                    onTap = {
                                        if (selectionMode) {
                                            if (isChecked) selectedKeys.remove(fav.id) else selectedKeys[fav.id] = true
                                        } else {
                                            onOpenEtymo(fav.word)
                                        }
                                    },
                                    onLongPress = {
                                        selectionMode = true
                                        selectedKeys[fav.id] = true
                                    }
                                )
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionMode) {
                                Icon(
                                    imageVector = if (isChecked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = null,
                                    tint = if (isChecked) scheme.primary else scheme.outline,
                                    modifier = Modifier.size(22.dp).padding(end = 6.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = fav.word,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.onSurface
                                    )
                                    val ipa = fav.ipa.orEmpty()
                                    if (ipa.isNotBlank()) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "/$ipa/",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = scheme.outline
                                        )
                                    }
                                }
                                val gloss = fav.gloss.orEmpty()
                                if (gloss.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = gloss,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (!selectionMode) {
                                IconButton(
                                    onClick = {
                                        val accent = when (WordKind.valueOf(fav.kind)) {
                                            WordKind.HANZI -> "cmn_modern"
                                            WordKind.RU -> "ru_standard"
                                            WordKind.EN -> "en_modern"
                                        }
                                        speaker(fav.word, fav.ipa.orEmpty(), accent)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Outlined.VolumeUp, contentDescription = "朗读", tint = scheme.primary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onUnfavorite(fav.word, WordKind.valueOf(fav.kind)) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "移出", tint = scheme.outline, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(
    fav: WordFavorite,
    index: Int,
    total: Int,
    speaker: (text: String, ipa: String, accent: String) -> Unit,
    onOpenEtymo: (String) -> Unit,
    onOpenMindMap: ((String) -> Unit)? = null,
    onExpand: () -> Unit
) {
    val kind = WordKind.fromName(fav.kind)
    val accent = when (kind) {
        WordKind.HANZI -> "cmn_modern"
        WordKind.EN -> "en_modern"
        WordKind.RU -> "ru_standard"
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onExpand),
        shape = MaterialTheme.shapes.extraLarge,
        // 素扉仿古宣纸卡片质感：温润纸色 + 宣纸微边框
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶栏：语种类型与序号
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WordKindChip(kind)
                Text(
                    text = "${index + 1} / $total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 中间主要内容：词、注音与简明释义
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text(
                    text = fav.word,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!fav.pinyin.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = fav.pinyin,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!fav.ipa.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = fav.ipa,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                fav.gloss?.takeIf { it.isNotBlank() }?.let { gloss ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = gloss,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 底部操作区：试听发音、查看全息词源与导图
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { speaker(fav.word, fav.ipa.orEmpty(), accent) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VolumeUp,
                        contentDescription = "试听发音",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("朗读", fontSize = 12.sp)
                }

                FilledTonalButton(
                    onClick = { onOpenEtymo(fav.word) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccountTree,
                        contentDescription = "查看全息词源与导图",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("词源与导图", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun FavoriteExpandedContent(
    fav: WordFavorite,
    viewModel: ChatNodeViewModel,
    speaker: (text: String, ipa: String, accent: String) -> Unit,
    onUnfavorite: (String, WordKind) -> Unit,
    onOpenEtymo: (String) -> Unit,
    onOpenMindMap: ((String) -> Unit)? = null,
    onCollapse: () -> Unit
) {
    val kind = WordKind.fromName(fav.kind)
    val accent = when (kind) {
        WordKind.HANZI -> "cmn_modern"
        WordKind.EN -> "en_modern"
        WordKind.RU -> "ru_standard"
    }
    val examplesFlow = remember(fav.word) { viewModel.observeWordExamples(fav.word) }
    val examples by examplesFlow.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fav.word,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            WordKindChip(kind)
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "收起详情",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (!fav.pinyin.isNullOrBlank()) {
            Text(
                text = "拼音 ${fav.pinyin}",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif
            )
        }
        if (!fav.ipa.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fav.ipa,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { speaker(fav.word, fav.ipa.orEmpty(), accent) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VolumeUp,
                        contentDescription = "试听发音",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            IconButton(
                onClick = { speaker(fav.word, "", accent) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.VolumeUp,
                    contentDescription = "试听发音",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        fav.gloss?.takeIf { it.isNotBlank() }?.let { gloss ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = gloss,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { onOpenEtymo(fav.word) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("全息词源", fontSize = 12.sp)
            }
            if (onOpenMindMap != null) {
                FilledTonalButton(
                    onClick = {
                        onCollapse()
                        onOpenMindMap(fav.word)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("思维导图", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "例句 (${examples.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (examples.isEmpty()) {
            Text(
                text = "该词还没有例句，可在「查词」里为它生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            examples.forEach { example ->
                ExampleRow(example = example, onDelete = { viewModel.deleteWordExample(example.id) })
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = {
                onCollapse()
                onUnfavorite(fav.word, kind)
            }
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("移出单词本")
        }
    }
}

// ============================================================================
// 共用小件
// ============================================================================

@Composable
private fun ExampleRow(example: WordExample, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = example.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (example.translation.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = example.translation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除例句",
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun WordKindChip(kind: WordKind) {
    val label = when (kind) {
        WordKind.HANZI -> "汉字"
        WordKind.EN -> "英语"
        WordKind.RU -> "俄语"
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ============================================================================
// 从 CrossLinguisticAlignment 抽出词条头与收藏缓存所需的字段
// ============================================================================

/** 词条头：汉语用整词（canonicalZh），英/俄用首个真实词条，都没有就回落到查询词。 */
private fun headwordOf(kind: WordKind, query: String, alignment: CrossLinguisticAlignment): String =
    when (kind) {
        WordKind.HANZI -> alignment.canonicalZh.ifBlank { query }
        WordKind.EN -> alignment.engCluster?.words?.firstOrNull()?.word ?: alignment.canonicalZh
        WordKind.RU -> alignment.rusCluster?.words?.firstOrNull()?.word ?: alignment.canonicalZh
    }

/** 收藏缓存：拼音（汉语逐字拼，取真实收录的）。 */
private fun favoritePinyin(kind: WordKind, alignment: CrossLinguisticAlignment): String? {
    if (kind != WordKind.HANZI) return null
    return alignment.cmnList.mapNotNull { if (isRecorded(it.pinyin)) it.pinyin else null }
        .joinToString(" ")
        .ifBlank { null }
}

/** 收藏缓存：音标（汉语逐字 IPA；英/俄取首条真实音标）。经 [IpaDisplay] 归一化显示。 */
private fun favoriteIpa(kind: WordKind, alignment: CrossLinguisticAlignment): String? = when (kind) {
    WordKind.HANZI -> alignment.cmnList.mapNotNull { if (isRecorded(it.ipa)) it.ipa else null }
        .joinToString(" ")
        .ifBlank { null }
    WordKind.EN -> alignment.engCluster?.words?.firstOrNull()?.ipaModern
        ?.takeIf { isRecorded(it) }
        ?.let { IpaDisplay.single(it) }
    WordKind.RU -> alignment.rusCluster?.words?.firstOrNull()?.ipa
        ?.takeIf { isRecorded(it) }
        ?.let { IpaDisplay.single(it) }
}
