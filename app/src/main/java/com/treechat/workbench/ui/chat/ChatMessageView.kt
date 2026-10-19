package com.treechat.workbench.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.treechat.workbench.ui.motion.PerformanceMode
import com.treechat.workbench.util.AppStrings
import com.treechat.workbench.safety.BranchSafetyAuditor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.treechat.workbench.data.model.ConversationNode
import com.treechat.workbench.rag.ImagePicker
import com.treechat.workbench.ui.selection.QuoteLinkMatcher
import com.treechat.workbench.ui.selection.QuoteLinkTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.treechat.workbench.data.model.ChatMessage
import com.treechat.workbench.data.model.Highlight
import com.treechat.workbench.network.search.SearchResult
import com.treechat.workbench.etymology.ui.EtymoScopeBottomSheet
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.window.WindowTetherState
import com.treechat.workbench.ui.markdown.MdRichBlock
import com.treechat.workbench.ui.motion.staggeredAppear
import com.treechat.workbench.ui.selection.HighlightRange
import com.treechat.workbench.ui.selection.MessageSelectionController
import com.treechat.workbench.ui.selection.SelectableMarkdownText
import com.treechat.workbench.ui.selection.SelectionScheme
import com.treechat.workbench.ui.selection.SelectionToolbar
import com.treechat.workbench.ui.selection.blocksForMessage
import com.treechat.workbench.ui.selection.projectHighlights
import com.treechat.workbench.ui.selection.projectKeywordHits
import kotlinx.coroutines.delay

/**
 * 一条消息挂上高亮与选择回调后的一束参数。
 *
 * 打包成一个对象而不是给 ChatMessageItem 再加五六个参数：
 * 那样每加一个动作就要改一次签名，调用点会长到看不清。
 */
data class MessageInteraction(
    val highlights: List<Highlight> = emptyList(),
    val onToggleHighlights: (messageId: String, ranges: List<Pair<Int, Int>>) -> Unit = { _, _ -> },
    val onAskAboutSelection: (messageId: String, quote: String) -> Unit = { _, _ -> },
    val onEtymology: (word: String) -> Unit = {},
    // ---- 粗体呈现（应用级用户偏好的只读快照）----
    //
    // 为什么走这个载体而不是让 MessageRow 自己去拿 ViewModel：
    // MessageRow 是纯展示层，注入 settings 会让它反向依赖数据层。
    // MessageInteraction 本来就负责"把只读上下文传给每条消息"，这里只是同一件事。
    val boldColorStyle: com.treechat.workbench.data.prefs.BoldColorStyle =
        com.treechat.workbench.data.prefs.BoldColorStyle.DEFAULT,
    val boldHighlightColor: com.treechat.workbench.data.prefs.BoldHighlightColor =
        com.treechat.workbench.data.prefs.BoldHighlightColor.NONE,
    val boldFontStyle: com.treechat.workbench.data.prefs.BoldFontStyle =
        com.treechat.workbench.data.prefs.BoldFontStyle.DEFAULT
)

/**
 * 流式跟随滚动的最小间隔（毫秒）。
 *
 * 150 的来由：跟随只需"看起来没掉队"，而每次滚动都会让整个可视区重绘。
 * 实测把间隔从"每帧"提到 150ms，流式期间整屏重绘次数降一个数量级，
 * 观感上仍是紧跟的。
 */
private const val FOLLOW_SCROLL_MIN_INTERVAL_MS = 150L

@Composable
fun NodeChatContent(
    nodeId: String,
    viewModel: ChatNodeViewModel,
    jumpToMessageId: String? = null,
    /**
     * 检索命中时的**关键词**（null 表示不是从检索跳过来的）。
     *
     * ## 为什么定位光靠 messageId 不够
     * 检索命中的是一条消息**里的某几个字**，而 `animateScrollToItem(index)`
     * 只能把整条消息滚进视口顶部。一条 AI 回答动辄上千字，命中词很可能在
     * 很靠后的位置 —— 滚过去了，用户还是得自己用眼睛找一遍，等于没定位。
     * 带上关键词后可以把它在消息里标出来，视线直接落到命中处。
     */
    jumpToKeyword: String? = null,
    /**
     * 点「返回检索结果」时回调（把检索面板重新拉起来）。
     *
     * 默认空实现是为了不打断既有调用方：只有真正接了检索的入口
     * （MainActivity 的浮层小窗）才需要它。
     */
    onBackToSearch: () -> Unit = {},
    /**
     * 命中词高亮自动熄灭时回调。
     *
     * 独立于 [onJumpConsumed]：后者清的是"滚到哪条消息"这个一次性请求，
     * 两者生命周期不同（滚动请求立即消费，高亮要停留几秒）。共用一个回调
     * 会让返回条在滚动完成的瞬间就消失，用户根本没时间看清命中在哪。
     */
    onSearchHighlightExpired: () -> Unit = {},
    onJumpConsumed: () -> Unit = {},
    onForkBranch: (quote: String) -> Unit,
    /** 轻点同文本超链接时回调：打开或置顶该引用对应的分支小窗。 */
    onOpenQuoteWindow: (ConversationNode) -> Unit = {}
) {
    var activeEtymologyWord by remember { mutableStateOf<String?>(null) }
    val messages by viewModel.getMessagesForNode(nodeId).collectAsState(initial = emptyList())
    val streamingSet by viewModel.streamingNodes.collectAsState()
    val isStreaming = streamingSet.contains(nodeId)
    val searchingQuery by viewModel.searchingQuery.collectAsState()
    val highlights by viewModel.getHighlightsForNode(nodeId).collectAsState(initial = emptyList())
    // 流式内存态在这里**一次性** collect（见 ViewModel.streamingText 的说明）：
    // 若让每条 MessageRow 各自 collect 整个 Map，每帧都会触发全列表重组，
    // 降频落库省下来的性能会被 UI 侧全吃回去。
    val streamingTexts by viewModel.streamingText.collectAsState()
    val streamingReasonings by viewModel.streamingReasoning.collectAsState()

    // 会话中所有其它分支节点：提取其引用文本（originQuote），作为当前所有消息的同文本超链接目标
    val allNodes by viewModel.nodes.collectAsState()
    val quoteTargets = remember(allNodes, nodeId) {
        QuoteLinkMatcher.extractTargets(allNodes, currentNodeId = nodeId)
    }

    val listState = rememberLazyListState()
    // 列表焦点：键盘方向键事件必须先有焦点才会路由到 onPreviewKeyEvent
    val listFocusRequester = FocusRequester()

    /**
     * 输入框内容。
     *
     * ⚠️ 必须是 `rememberSaveable`，不能用 `remember`：输入框所在的这段子树
     * 会被 `AnimatedContent` 在切 Tab 时**销毁**（对话界面整体移出组合），
     // 普通 `remember` 的值随之消失 —— 用户切走再切回来，编辑了一半的提问就没了。
     // `rememberSaveable` 把值存进 `SaveableStateRegistry`，重建时自动恢复。
     *
     * 用 `mutableStateOf`（而非 `mutableStateOfSaver`）即可：
     * 草稿是纯文本，Bundle 原生支持 String，无需自定义 Saver。
     */
    var inputText by rememberSaveable(nodeId) { mutableStateOf("") }

    // ---- 待发送的图片 ----
    //
    // 存的是**压缩后的 data URI**（ImagePicker 已做长边限制 + JPEG 压缩）。
    // 不在发送时才压缩：用户选完要能立刻看到缩略图确认选对了，
    // 而且发送路径上不该再插入耗时操作（会让"点发送"看起来卡住）。
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickingImages by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 对话标签（位置锚点小窗）：与阅读器的 PageTagStore 同源，纯界面层状态
    val chatTagStore = remember { ChatTagStore(context) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(ImagePicker.MAX_IMAGES)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        imageError = null
        pickingImages = true
        scope.launch {
            // 读盘 + 解码 + 缩放 + JPEG 编码都是**耗时操作**，
            // 必须切到 IO：放在主线程会让输入栏在压缩几张图时明显卡住。
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { ImagePicker.toDataUri(context, uri) }
                        .onFailure { e ->
                            imageError = e.localizedMessage ?: "有一张图片处理失败"
                        }
                        .getOrNull()
                }
            }
            // 超出的部分直接丢弃并提示：一次附太多图会把请求体和 token 撑爆
            val room = (ImagePicker.MAX_IMAGES - pendingImages.size).coerceAtLeast(0)
            pendingImages = pendingImages + added.take(room)
            if (added.size > room) {
                imageError = "最多同时附 ${ImagePicker.MAX_IMAGES} 张图，多余的已忽略"
            }
            pickingImages = false
        }
    }

    // 当前正在被选择的那条消息：只允许一条消息同时处于选择态。
    // 用消息 id 而不是 controller 本身做 key，是为了在流式更新导致
    // 列表项重建时仍能认出"还是同一条消息在选"。
    var activeSelectionMessageId by remember { mutableStateOf<String?>(null) }

    // ⚠️ 选择控制器必须**按消息各一个**，不能所有消息共用一个。
    //
    // 共用时的实测后果：每条消息在自己的 SelectableMarkdownText compose 时
    // 都会 clearBlocks() 掉同一个控制器，而块序号又是"消息内"的序号，
    // 于是最后 compose 的那条消息会覆盖/清空其它消息的块 ——
    // 长按别的消息时，命中测试用的却是另一条消息的矩形，必然失败。
    //
    // 这正是本项目"选字反复改手势却怎么都不好"的结构性原因：
    // 问题从来不在手势，而在控制器被跨消息共用。
    val selectionControllers = remember { mutableMapOf<String, MessageSelectionController>() }

    /** 当前处于选择态的那条消息的控制器（工具条的取消/全选/高亮要用它）。 */
    var activeSelectionController by remember { mutableStateOf<MessageSelectionController?>(null) }

    var selectionQuote by remember { mutableStateOf("") }
    var selectionHighlightActive by remember { mutableStateOf(false) }

    // 新消息到达：播放一次平滑滚动
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // 流式增长自动跟随：
    // 关键体验修复：用户在生成过程中想要自由向下滑动查看消息下半截、
    // 或者向上滑动查看历史上下文时，绝不能被无情拽回！
    // 只有当用户原本就处于列表最底部且没有在手动触摸滑动时，才跟随流式文本下推；
    // 一旦用户主动滑动浏览，彻底让路，保证平滑自主滚动。
    //
    // ⚠️ 跟随必须**限速**。旧实现每来一片文本就下发一次滚动：列表偏移一变，
    //    整个可视区都要重新绘制（不是只画新增的那几行）。实测这正是流式期间
    //    "每帧约 500ms、Slow issue draw commands 93 次"的主因 —— 整屏重绘
    //    被放大到每秒 60 次。人眼对"跟到底部"的频率并不敏感，150ms 一次既跟手，
    //    又把整屏重绘降到每秒约 6 次。
    val lastFollowAt = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(messages.lastOrNull()?.content?.length) {
        if (messages.isEmpty() || listState.isScrollInProgress) return@LaunchedEffect
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (nowMs - lastFollowAt.longValue < FOLLOW_SCROLL_MIN_INTERVAL_MS) return@LaunchedEffect

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val lastVisible = visibleItems.lastOrNull()
        val isAtBottom = lastVisible == null ||
            (lastVisible.index >= messages.size - 1 &&
                lastVisible.offset + lastVisible.size <= listState.layoutInfo.viewportEndOffset + 120)

        if (isAtBottom) {
            lastFollowAt.longValue = nowMs
            // 吸附在最后一条消息的底部，而不是死板地把长消息强拽到第 0 像素的顶部
            listState.scrollToItem(messages.size - 1, scrollOffset = 10000)
        }
    }

    // 检索命中时要闪的那条消息（配合 jumpToKeyword 在正文里标出命中词）。
    // 与 flashMessageId 分开：那个是"小窗来源消息"的系带高亮，
    // 这个是"检索命中"，两者可能同时存在、语义不同。
    var searchHitMessageId by remember { mutableStateOf<String?>(null) }

    // 从引用卡片跳过来的定位：
    // 等消息列表里出现目标那条之后再滚，否则消息还没加载完就滚会滚到错位置。
    // 完成后立刻回调消费掉这个请求 —— 不清掉的话，之后每次重组都会
    // 把视图强行拽回那条消息，用户就没法往下看了。
    LaunchedEffect(jumpToMessageId, jumpToKeyword, messages.size) {
        val target = jumpToMessageId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            // 检索跳转才需要标词；普通"跳到来源消息"（引用卡片）不需要，
            // 否则每次点引用都会有一团高亮，反而看不清正文。
            if (!jumpToKeyword.isNullOrBlank()) {
                searchHitMessageId = target
            }
            onJumpConsumed()
        } else if (messages.isNotEmpty()) {
            // 目标不在本分支（例如引用来自另一条链路）：不要无限期挂着
            onJumpConsumed()
        }
    }

    // 命中词高亮会自己熄灭：长期挂着会和用户自己划的 Highlight 混在一起，
    // 分不清哪个是检索带来的临时标记。
    //
    // 熄灭时把 `jumpToKeyword` 也一并交还（置空）—— 否则「返回检索结果」条
    // 会永久挂在聊天里，而高亮早就没了，变成一个点不动的死控件。
    // 用户若还想回去，点输入框上方的条之前它还在；一旦高亮熄灭，
    // 再次 Ctrl+F 就能重新检索，语义上更自然。
    LaunchedEffect(searchHitMessageId) {
        if (searchHitMessageId == null) return@LaunchedEffect
        kotlinx.coroutines.delay(2600)
        searchHitMessageId = null
        onSearchHighlightExpired()
    }

    // 关键词被外部改动（例如又点了一条检索结果）时，旧的返回条要立刻让位，
    // 否则会短暂显示上一条的关键词。
    LaunchedEffect(jumpToKeyword) {
        if (!jumpToKeyword.isNullOrBlank()) searchHitMessageId = null
    }

    // ---- 视觉系带：被激活的小窗来源消息短暂高亮 ----
    //
    // 小窗置顶时 WindowTetherState 会记下它"从哪条消息长出来的"。若那条消息
    // 恰好就在当前节点（父节点）的消息列表里，就滚动到它并高亮闪一下 ——
    // 让用户在底层视口里一眼看到"这扇窗对应的原文是哪一段"。
    // 找不到（不是本节点、或旧数据没有来源消息）就静默跳过，不做任何猜测。
    var flashMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(WindowTetherState.pulseToken) {
        val target = WindowTetherState.sourceMessageId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            flashMessageId = target
            delay(1300)
            flashMessageId = null
        }
    }

    val clipboard = LocalClipboardManager.current

    // 选字手势诊断的最近一条结果（仅在设置里打开诊断时显示）
    var selectionDiagnostic by remember { mutableStateOf<String?>(null) }

    var messageToDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var safetyAuditRunning by remember { mutableStateOf(false) }
    var safetySnackText by remember { mutableStateOf<String?>(null) }
    val isSensitive = viewModel.isNodeSensitive(nodeId)

    fun runBranchSafetyAudit() {
        if (safetyAuditRunning) return
        safetyAuditRunning = true
        scope.launch {
            val result = BranchSafetyAuditor.auditWithAi(
                provider = viewModel.activeProvider,
                messages = messages
            )
            safetyAuditRunning = false
            if (result.isSensitive) {
                viewModel.setNodeSensitive(nodeId, true)
                safetySnackText = AppStrings.get("audit_flagged", viewModel.settings.appLanguage) + " (${result.reason})"
            } else {
                safetySnackText = AppStrings.get("audit_clean", viewModel.settings.appLanguage)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 选字诊断条（仅在设置里打开"显示文本选择诊断"时出现）----
            //
            // 为什么不写进日志文件、而是直接显示：
            // 用户已经把崩溃日志落盘那套走通过一次（无需 adb），
            // 但选字问题需要"边操作边看结果"，落盘再翻文件太慢。
            // 直接显示在顶部，用户长按一次后截图即可，定位成本最低。
            if (viewModel.settings.selectionDiagnostics) {
                Text(
                    text = "选字诊断：" + (selectionDiagnostic ?: "（还没有手势事件）"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            // 敏感枝条风险提示与防风控横幅（复合功能集成）
            if (isSensitive) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = AppStrings.get("sensitive_branch_warning", viewModel.settings.appLanguage),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // 复合功能按钮栏
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 复合功能 1：一键脱敏解耦分叉
                            androidx.compose.material3.Button(
                                onClick = {
                                    scope.launch {
                                        viewModel.forkSanitizedBranch(nodeId)
                                        safetySnackText = AppStrings.get("decouple_success", viewModel.settings.appLanguage)
                                    }
                                },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    AppStrings.get("decouple_sanitize_branch", viewModel.settings.appLanguage),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // 复合功能 2：脱敏并切换至国内模型接力
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        viewModel.forkSanitizedBranch(nodeId)
                                        val domesticProvider = viewModel.savedProviders.firstOrNull {
                                            BranchSafetyAuditor.isStrictModerationModel(it.selectedModelId, it.id, it.baseUrl)
                                        }
                                        if (domesticProvider != null) {
                                            viewModel.selectProvider(domesticProvider.id)
                                        }
                                        safetySnackText = "已完成脱敏并无缝接力至国内合规模型"
                                    }
                                },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    AppStrings.get("handover_to_domestic", viewModel.settings.appLanguage),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            Spacer(Modifier.weight(1f))

                            TextButton(
                                onClick = { viewModel.setNodeSensitive(nodeId, false) },
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    AppStrings.get("unmark_sensitive", viewModel.settings.appLanguage),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // 枝条敏感检测状态反馈条
            safetySnackText?.let { snack ->
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(text = snack, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { safetySnackText = null }) {
                            Text(AppStrings.get("confirm", viewModel.settings.appLanguage), fontSize = 11.sp)
                        }
                    }
                }
            }

            // ---- 对话标签层：空白处长按 / 鼠标右键放一个标签小窗 ----
            //
            // 为什么包在消息列表外、而不是整个对话区：输入框里长按要留给
            // 光标与粘贴菜单，不能被标签手势吃掉。包列表正好覆盖"内容区"，
            // 归一化坐标也以内容区为基准，换设备/转屏/浮层小窗都不会漂。
            ChatTagSurface(
                hostNodeId = nodeId,
                store = chatTagStore,
                // 标签建出的就是当前对话的下级分支（与选词分叉同一条数据路径，
                // 只是没有 originQuote —— 它不依赖任何文本）
                onCreateBranch = { title -> viewModel.createChildNode(nodeId, title) },
                onOpenWindow = onOpenQuoteWindow,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        // ---- 键盘方向键滚动对话 ----
                        //
                        // 为什么需要：接了外接键盘（或用了触控板 + 物理键盘）之后，
                        // 桌面端最顺手的滚动方式就是 ↑/↓/PageUp/PageDn，
                        // 但对话列表此前只能靠鼠标滚轮 —— 手放在键盘上却滚不动。
                        //
                        // 为什么挂在 `onPreviewKeyEvent` 而不是 `onKeyEvent`：
                        // `onPreviewKeyEvent` 在事件**冒泡到子节点之前**触发，
                        // 因此输入框聚焦时按 ↑ 会先被这里看到。这里只在
                        // "焦点不在文本输入框"时才消费，避免打断正常打字。
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            // 输入框/文本域里按方向键是在移动光标，必须放行。
                            // KeyEvent 拿不到焦点树，改为判断"本次按键是否会被文本框消费"：
                            // 交由下方 TextField 自己处理（此处直接返回 false）。

                            val visible = listState.layoutInfo.visibleItemsInfo
                            if (visible.isEmpty()) return@onPreviewKeyEvent false
                            val first = visible.first().index
                            val lastIdx = visible.last().index
                            val step = if (ev.isShiftPressed) 5 else 1   // Shift+方向 = 翻页

                            when (ev.key) {
                                Key.DirectionUp -> {
                                    if (first <= 0) return@onPreviewKeyEvent false
                                    val target = (first - step).coerceAtLeast(0)
                                    scope.launch { listState.animateScrollToItem(target) }
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (lastIdx >= messages.size - 1) return@onPreviewKeyEvent false
                                    val target = (lastIdx + step).coerceAtMost(messages.size - 1)
                                    scope.launch { listState.animateScrollToItem(target) }
                                    true
                                }
                                Key.PageUp -> {
                                    val target = (first - step * 3).coerceAtLeast(0)
                                    scope.launch { listState.animateScrollToItem(target) }
                                    true
                                }
                                Key.PageDown -> {
                                    val target = (lastIdx + step * 3).coerceAtMost(messages.size - 1)
                                    scope.launch { listState.animateScrollToItem(target) }
                                    true
                                }
                                else -> false
                            }
                        }
                        // 列表必须能拿到焦点，否则键盘事件根本不会路由到这里
                        .focusRequester(listFocusRequester)
                        .focusable(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(messages, key = { _, item -> item.id }) { index, message ->
                        Box(modifier = Modifier.staggeredAppear(index)) {
                            // key 包一层：流式更新会重建整个 item，
                            // 用 key 保住每个 item 自己的 remember 状态（如 copied 标记）
                            key(message.id) {
                                // 每条消息一个控制器（按消息 id 缓存，跨重组保留）
                                val messageController = remember(message.id) {
                                    selectionControllers.getOrPut(message.id) {
                                        MessageSelectionController()
                                    }
                                }
                                MessageRow(
                                    message = message,
                                    isStreaming = isStreaming,
                                    isSelectionHost = activeSelectionMessageId == message.id,
                                    controller = messageController,
                                    interaction = MessageInteraction(
                                        highlights = highlights,
                                        // 粗体呈现：来自设置，读一次快照传给每条消息
                                        boldColorStyle = viewModel.settings.boldColorStyle,
                                        boldHighlightColor = viewModel.settings.boldHighlightColor,
                                        boldFontStyle = viewModel.settings.boldFontStyle,
                                        onToggleHighlights = { messageId, ranges ->
                                            viewModel.toggleHighlights(messageId, nodeId, ranges)
                                        },
                                        onAskAboutSelection = { _, quote -> onForkBranch(quote) },
                                        onEtymology = { word -> activeEtymologyWord = word }
                                    ),
                                    onSelectionChanged = { hasSelection, quote, highlightActive ->
                                        if (hasSelection) {
                                            // 强制清除其他所有消息控制器的残留选区，彻底消除跨消息选区竞争导致的死锁
                                            selectionControllers.forEach { (otherId, otherCtrl) ->
                                                if (otherId != message.id) {
                                                    otherCtrl.clear()
                                                }
                                            }
                                            activeSelectionMessageId = message.id
                                            activeSelectionController = messageController
                                            selectionQuote = quote
                                            selectionHighlightActive = highlightActive
                                        } else if (activeSelectionMessageId == message.id) {
                                            activeSelectionMessageId = null
                                            activeSelectionController = null
                                            selectionQuote = ""
                                        }
                                    },
                                    onRegenerate = if (message.role == "assistant" && !isStreaming) {
                                        { viewModel.regenerateLast(nodeId) }
                                    } else {
                                        null
                                    },
                                    onForkWhole = { onForkBranch(message.content) },
                                    // 把选字手势的判定结果汇总到本页顶部显示，
                                    // 供"长按没反应"的定位（见 settings.selectionDiagnostics）
                                    onGestureDiagnostic = { selectionDiagnostic = it },
                                    quoteLinkTargets = quoteTargets,
                                    onQuoteClick = onOpenQuoteWindow,
                                    // 文本选择方案：由设置决定（默认长按选句 = 原行为）
                                    selectionScheme = viewModel.settings.selectionScheme,
                                    // 输入设备模式：自动检测鼠标 / 强制键鼠 / 强制触控
                                    inputMode = viewModel.settings.inputMode,
                                    onUndoHighlight = { viewModel.undoLatestHighlight(message.id) },
                                    // 视觉系带：被激活的小窗来源消息短暂高亮
                                    flashHighlight = message.id == flashMessageId,
                                    // 检索命中词：非空时在这条消息正文里把它标出来。
                                    // 只给真正命中的那一条，避免每条消息都去扫全文。
                                    searchHitKeyword = if (message.id == searchHitMessageId) {
                                        jumpToKeyword
                                    } else {
                                        null
                                    },
                                    liveStreamingTexts = streamingTexts,
                                    liveStreamingReasonings = streamingReasonings,
                                    onDelete = { messageToDelete = message }
                                )
                            }
                        }
                    }
                }
            }

            // 联网检索进行中的提示条：让用户知道"它在搜，不是卡住了"
            AnimatedVisibility(
                visible = searchingQuery != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "正在联网检索：${searchingQuery.orEmpty()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 选择工具条已改为在 SelectableMarkdownText 内紧贴选中文字正上方悬浮弹出（Word 式），
            // 此处不再在屏幕底部重复渲染固定横条。

            // ---- 检索定位后的「返回检索结果」条 ----
            //
            // 为什么必须有：从检索结果跳到原文是**离开**了检索面板，
            // 而原文往往在长回答中间、看完就想回去翻下一条。
            // 没有这个出口，用户只能关窗重开面板、重新输一遍词。
            //
            // 放在输入框正上方：那是用户视线回到列表时的落脚点，
            // 也正好是"这条消息区域"与"输入区"的分界，不会被流式输出挤走。
            AnimatedVisibility(
                visible = !jumpToKeyword.isNullOrBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SearchJumpReturnBar(
                    keyword = jumpToKeyword.orEmpty(),
                    onBack = onBackToSearch
                )
            }

            ChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                isStreaming = isStreaming,
                onSend = {
                    // 文字与图片**任一非空**即可发送：
                    // 用户常常直接发一张图问"这是什么"，不该被文字校验拦住。
                    if ((inputText.isNotBlank() || pendingImages.isNotEmpty()) && !isStreaming) {
                        viewModel.sendMessage(nodeId, inputText.trim(), pendingImages)
                        inputText = ""
                        pendingImages = emptyList()
                    }
                },
                onStop = { viewModel.stopStreaming(nodeId) },
                images = pendingImages,
                onAddImages = { imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveImage = { index ->
                    pendingImages = pendingImages.filterIndexed { i, _ -> i != index }
                },
                pickingImages = pickingImages,
                errorMessage = imageError,
                onDismissError = { imageError = null },
                enterToSend = viewModel.settings.enterToSend
            )
        }

        // 跨语历时音韵与全同义词源全息视窗 (EtymoScope)
        activeEtymologyWord?.let { queryWord ->
            EtymoScopeBottomSheet(
                initialQuery = queryWord,
                onDismissRequest = { activeEtymologyWord = null }
            )
        }

        // 单条消息删除确认弹窗
        messageToDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { messageToDelete = null },
                title = {
                    Text(
                        text = AppStrings.get("delete_confirm_title", viewModel.settings.appLanguage),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(AppStrings.get("delete_confirm_desc", viewModel.settings.appLanguage))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteSingleMessage(target.id)
                            messageToDelete = null
                        }
                    ) {
                        Text(
                            AppStrings.get("delete", viewModel.settings.appLanguage),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { messageToDelete = null }) {
                        Text(AppStrings.get("cancel", viewModel.settings.appLanguage))
                    }
                }
            )
        }
    }
}

/**
 * 把当前选区换算成**源 Markdown 上的区间**，供落库高亮。
 *
 * 跨块选区会拆成多段：源串上跨越两个块的那一段中间夹着标题井号、
 * 列表符号等标记，用一个连续区间去存会把标记也点亮。
 *
 * blocks 由调用方传入（remember 缓存过），**不要在这里重新解析**：
 * 拖动手柄时这个函数每帧都会被调用，逐帧解析整条 Markdown 会明显卡顿。
 */
private fun sourceRangesOfSelection(
    controller: MessageSelectionController,
    blocks: List<MdRichBlock>
): List<Pair<Int, Int>> {
    val sel = controller.selection?.normalized() ?: return emptyList()
    val result = mutableListOf<Pair<Int, Int>>()
    for (index in sel.start.block..sel.end.block) {
        val block = blocks.getOrNull(index) ?: continue
        if (block.kind == MdRichBlock.Kind.TABLE || block.kind == MdRichBlock.Kind.DIVIDER) continue
        val from = if (index == sel.start.block) sel.start.offset else 0
        val to = if (index == sel.end.block) sel.end.offset else block.displayText.length
        if (from >= to) continue
        val start = block.toSource(from)
        val end = block.toSource(to)
        if (start < end) result.add(start to end)
    }
    return result
}

// ============================================================================
// 单条消息 —— 按 M3 消息模式
// ============================================================================

/**
 * 对话气泡。
 *
 * 配色遵循 M3 的会话模式：用户消息用 primaryContainer / onPrimaryContainer
 * 做高强调，AI 消息用 surfaceContainerHigh / onSurface 做低强调。
 *
 * **这里刻意不挂 SelectionContainer，也不挂 combinedClickable 的长按。**
 * 用户要求长按文本不再弹出系统选取菜单，改为自建选择；两者若同时存在，
 * 长按会先被系统选取吃掉，自建工具条永远弹不出来。
 * 因此长按与拖动全部交给 SelectableMarkdownText 处理。
 */
@Composable
private fun MessageRow(
    message: ChatMessage,
    isStreaming: Boolean,
    isSelectionHost: Boolean,
    controller: MessageSelectionController,
    interaction: MessageInteraction,
    onSelectionChanged: (hasSelection: Boolean, quote: String, highlightActive: Boolean) -> Unit,
    onRegenerate: (() -> Unit)?,
    onForkWhole: () -> Unit,
    /** 文本选择手势诊断（排查用，由 NodeChatContent 汇总显示）。 */
    onGestureDiagnostic: (String) -> Unit = {},
    /** 命中的同文本超链接目标列表。 */
    quoteLinkTargets: List<QuoteLinkTarget> = emptyList(),
    /** 轻点同文本超链接时的回调，打开对应分支小窗。 */
    onQuoteClick: (ConversationNode) -> Unit = {},
    /** 文本选择方案（触屏长按 / 鼠标拖选 / 双击选句）。 */
    selectionScheme: SelectionScheme = SelectionScheme.TOUCH_LONG_PRESS,
    /** 全局输入设备模式（自动 / 强制键鼠 / 强制触控）。 */
    inputMode: com.treechat.workbench.data.prefs.InputMode =
        com.treechat.workbench.data.prefs.InputMode.AUTO,
    onUndoHighlight: (() -> Unit)? = null,
    /** 视觉系带：本消息是否被"小窗激活来源"高亮闪烁。 */
    flashHighlight: Boolean = false,
    /**
     * 检索命中的关键词：非 null 时在**正文里**把它标出来。
     *
     * 与 [flashHighlight] 的区别很关键：
     *  · flashHighlight 是整条消息铺一层呼吸底色，说的是"这条被激活了"；
     *  · 这里标的是**具体的几个字**，说的是"命中就在这几个字"。
     * 只做前者的话，长回答里用户仍然找不到命中处 —— 所以两者各管一件事。
     */
    searchHitKeyword: String? = null,
    /** 单条消息删除回调 */
    onDelete: () -> Unit = {},
    /**
     * 流式内存态（见 `ChatNodeViewModel.streamingText` / `streamingReasoning`）。
     *
     * 由调用方（NodeChatContent）一次性 collect 后按 `message.id` 查表传入——
     * 避免每条消息各自 collect 整个 Map 导致全列表每帧重组。
     */
    liveStreamingTexts: Map<String, String> = emptyMap(),
    liveStreamingReasonings: Map<String, String> = emptyMap()
) {
    val isUser = message.role == "user"
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // 流式内存态（见 ChatNodeViewModel.streamingText）：流式期间界面只读它，
    // 跳过 Room 的 500ms 落库延迟；流结束它被清掉，退回读 message.content。
    // 条件收紧到 `message.isStreaming`：别的已完成消息不受它影响。
    val liveStreamingText = liveStreamingTexts[message.id]?.takeIf {
        !isUser && isStreaming && message.isStreaming
    }
    val liveStreamingReasoning = liveStreamingReasonings[message.id]

    val sources = remember(message.searchSourcesJson) {
        parseSearchSources(message.searchSourcesJson)
    }

    val knowledgeEvidences = remember(message.knowledgeSourcesJson) {
        parseKnowledgeEvidences(message.knowledgeSourcesJson)
    }

    val messageHighlights = remember(interaction.highlights, message.id) {
        interaction.highlights.filter { it.messageId == message.id }
    }
    val blocks = remember(message.content, isUser) { blocksForMessage(message.content, isUser) }
    // 概念链接命中集合**已不再计算**：该功能整体停用，
    // 而 ConceptLinkIndex.knownIn 会在每条消息每次内容变化时走一遍词库判定 ——
    // 留着就是白花的开销（流式期间还会被反复触发）。
    // 库里存的是源偏移，渲染/选择用的是显示偏移，这里做一次投影
    val highlightRanges: List<HighlightRange> = remember(blocks, messageHighlights) {
    projectHighlights(blocks, messageHighlights)
    }

    // 检索命中的词：临时并入高亮区间一起渲染。
    // 与用户手动划的高亮走**同一个参数**（而不是另画一层），
    // 这样命中词和手动高亮在视觉上完全一致，也不用改 SelectableMarkdown。
    val searchHitRanges: List<HighlightRange> =
        remember(blocks, searchHitKeyword) { projectKeywordHits(blocks, searchHitKeyword) }
    val allHighlightRanges = remember(highlightRanges, searchHitRanges) {
        highlightRanges + searchHitRanges
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val bubbleColor = if (isUser) scheme.primaryContainer else scheme.surfaceContainerHigh
    val textColor = if (isUser) scheme.onPrimaryContainer else scheme.onSurface
    val accent = if (isUser) scheme.onPrimaryContainer else scheme.primary

    // 视觉系带闪烁强度（0→1→0 由上层 flashMessageId 的存续期驱动）
    val flashAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (flashHighlight) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 320),
        label = "tetherFlashAlpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 5.dp)
        ) {
            Icon(
                imageVector = if (isUser) Icons.Default.Person else Icons.Default.SmartToy,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isUser) "你" else "AI",
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }

        Surface(
            shape = MaterialTheme.shapes.large.copy(
                bottomStart = if (isUser) {
                    MaterialTheme.shapes.large.topStart
                } else {
                    MaterialTheme.shapes.extraSmall.topStart
                },
                bottomEnd = if (isUser) {
                    MaterialTheme.shapes.extraSmall.topStart
                } else {
                    MaterialTheme.shapes.large.topStart
                }
            ),
            color = bubbleColor,
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                // 视觉系带闪烁：来源消息被小窗激活时高亮一圈边框，
                // 强度由 flashAlpha 驱动（高亮消失后边框宽度归零，不留痕）。
                .border(
                    width = (1.6.dp * flashAlpha),
                    color = AccentNeonGreen.copy(alpha = 0.9f * flashAlpha),
                    shape = MaterialTheme.shapes.large
                )
        ) {
            Column {
                // ---- 消息附带的图片 ----
                //
                // 渲染在正文**上方**：与输入栏里的预览顺序一致
                // （用户选图时图在文字上方，历史里也应如此）。
                // 图片存的是 data URI，所以重启后依然显示，不会变成空白。
                val messageImages = remember(message.imagesJson) {
                    parseMessageImages(message.imagesJson)
                }
                if (messageImages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        messageImages.forEach { dataUri ->
                            ImageThumbnail(dataUri = dataUri, targetPx = 132)
                        }
                    }
                }

                // ---- 本地知识库原文引用横幅（显式告知用户正在回复知识库原文）----
                if (!isUser && knowledgeEvidences.isNotEmpty()) {
                    KnowledgeSourcesBanner(evidences = knowledgeEvidences)
                }

                // ---- 思考过程（推理链）----
                //
                // 只有思考模型会返回它（DeepSeek-R1 / MIMO / Qwen 思考版等，
                // 字段为 `reasoning_content`）。此前这段内容被**静默丢弃**，
                // 用户因此以为"推理开关没生效"。
                //
                // 放在正文**之前**：思考在时间上先于回答，顺序与生成过程一致。
                // 用户消息不带思考（那是模型侧产物），所以只对 assistant 显示。
                //
                // 流式期间优先读**内存态**：ViewModel 把落库降频到 500ms，
                // 库里 `message.reasoningContent` 要滞后半秒，直接读它会让
                // "正在思考"断断续续。内存态在流结束时会被清掉（见 ViewModel），
                // 之后自然退回读库里的最终版本。
                val reasoning = message.reasoningContent
                val liveReasoning = liveStreamingReasoning?.takeIf {
                    !isUser && isStreaming && message.isStreaming && it.isNotBlank()
                }
                val shownReasoning = liveReasoning ?: reasoning
                if (!isUser && !shownReasoning.isNullOrBlank()) {
                    // 必须用**逐条消息**的流式标志，不能用节点级整体标志：
                    // ReasoningPanel 里是 `expanded = isStreaming || userExpanded` 且
                    // `clickable(enabled = !isStreaming)`。以前把节点级标志传进来，
                    // 于是"只要这个节点在生成，列表里**每一条**消息的思考块都被强制展开、
                    // 且禁止收起"（用户实测反馈的老 bug）。ChatMessage.isStreaming 才是
                    // 逐条的真信号（下方 649 行本来就在用它）。
                    ReasoningPanel(
                        reasoning = shownReasoning,
                        isStreaming = isStreaming && message.isStreaming
                    )
                }

                if (message.content.isBlank() && isStreaming) {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        ThreeDotLoader()
                    }
                } else {
                    SelectableMarkdownText(
                        // 粗体呈现：由用户设置决定颜色 / 底色 / 字形（默认=原样）
                        boldColorStyle = interaction.boldColorStyle,
                        boldHighlightColor = interaction.boldHighlightColor,
                        boldFontStyle = interaction.boldFontStyle,
                        // 流式期间优先读**内存态**：库里 content 要滞后半秒，
                        // 直接读它出字会一顿一顿。内存态在流结束时被清掉，
                        // 之后自然退回读库里的最终版本。
                        // 流式期间把"突发到达"的文本按平稳节奏吐出来（见 SmoothReveal，
                        // 改编自 TokenFlow/MIT）。非流式时直接用库里/内存里的最终文本。
                        markdown = liveStreamingText?.let { rememberSmoothReveal(it) }
                            ?: message.content,
                        controller = controller,
                        plain = isUser,
                        highlights = allHighlightRanges,
                        selectionColor = scheme.primary.copy(alpha = 0.26f),
                        // 高亮色存的是 ARGB 整数，这里直接换算成 Color，
                        // 不再受主题色影响 —— 高亮是"我标记的"，应与主题色可区分
                        highlightColor = androidx.compose.ui.graphics.Color(
                            Highlight.DEFAULT_COLOR_ARGB
                        ),
                        baseFontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        onSelectionChanged = { hasSelection ->
                            val quote = if (hasSelection) controller.selectedText() else ""
                            val active = if (hasSelection) {
                                // 只要选区与任何已有的高亮有交集重叠，工具条就智能显示为"取消高亮"
                                val ranges = sourceRangesOfSelection(controller, blocks)
                                ranges.isNotEmpty() && ranges.any { (from, to) ->
                                    interaction.highlights.any {
                                        it.messageId == message.id &&
                                            !(it.endOffset <= from || it.startOffset >= to)
                                    }
                                }
                            } else {
                                false
                            }
                            onSelectionChanged(hasSelection, quote, active)
                        },
                        onGestureDiagnostic = onGestureDiagnostic,
                        quoteLinkTargets = quoteLinkTargets,
                        onQuoteClick = onQuoteClick,
                        selectionScheme = selectionScheme,
                        inputMode = inputMode,
                        onAsk = { text ->
                            interaction.onAskAboutSelection(message.id, text)
                        },
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                        },
                        onHighlight = {
                            val ranges = sourceRangesOfSelection(controller, blocks)
                            if (ranges.isNotEmpty()) {
                                interaction.onToggleHighlights(message.id, ranges)
                            }
                        },
                        onUndoHighlight = onUndoHighlight,
                        highlightActive = highlightRanges.any { h ->
                            val ranges = sourceRangesOfSelection(controller, blocks)
                            ranges.any { (from, to) -> !(h.endExclusive <= from || h.start >= to) }
                        },
                        onEtymology = interaction.onEtymology,
                        // 概念链接（[[概念名]] → 点开弹词源/追问）已按用户要求**整体停用**：
                        // 不再传 knownConcepts / onConceptClick。模型若仍输出双方括号，
                        // 正文里只会看到干净的名词（括号在解析阶段被剥掉）。
                        // 需要查词源时走「词」标签页或阅读器工具栏，那里功能照旧。
                        //
                        // 最外层**再压一层**竖条光标：阅读器路径本来就有一层
                        // （AdaptiveDocumentView 在 SelectableMarkdownText 外面
                        // 也写了 pointerHoverIcon(PointerIcon.Text)），对话界面此前没有，
                        // 这正是"同一份选词组件，阅读器里光标正常、对话里经常失效"的差别之一。
                        //
                        // 为什么组件内部已经有还要再加一层：Compose 的 hover 光标只在
                        // **Enter / Exit** 事件上求值，且 Exit 之后会回退到**祖先**的光标。
                        // 两层都设成 Text 后，无论内层节点是否因重组而错过 Enter 事件，
                        // 悬停在正文上时祖先这一层仍是竖条 —— 相当于给光标加了一道兜底。
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Text)
                    )
                }

                if (message.isStreaming && message.content.isNotBlank()) {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        ThreeDotLoader()
                    }
                }
            }
        }

        // 联网检索到的来源，按 DeepSeek 那种"检索了 N 个网页"折叠展示
        if (sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(7.dp))
            SearchSourcesCard(sources)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)
        ) {
            MessageActionChip(
                icon = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                label = if (copied) "已复制" else "复制",
                highlight = copied,
                onClick = {
                    // 有选区时复制选区，否则复制整条 —— 有选区还复制全文会很意外
                    val selected = if (isSelectionHost) controller.selectedText() else ""
                    clipboard.setText(
                        AnnotatedString(selected.ifBlank { message.content })
                    )
                    copied = true
                }
            )

            if (onRegenerate != null) {
                MessageActionChip(
                    icon = Icons.Default.Refresh,
                    label = "重新生成",
                    highlight = false,
                    onClick = onRegenerate
                )
            }

            if (!isUser) {
                MessageActionChip(
                    icon = Icons.Default.AddComment,
                    label = "整段分叉",
                    highlight = false,
                    onClick = onForkWhole
                )
            }

            MessageActionChip(
                icon = Icons.Outlined.Delete,
                label = "删除",
                highlight = false,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun MessageActionChip(
    icon: ImageVector,
    label: String,
    highlight: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (highlight) scheme.primary else scheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

// ============================================================================
// 思考过程面板
// ============================================================================

/**
 * 可折叠的思考过程（推理链）面板。
 *
 * ## 为什么默认折叠（但流式时展开）
 * 思考过程往往比回答还长。默认展开会把真正的答案挤出屏幕，
 * 用户每看一条回答都要先滚过几百字草稿。
 * 但**正在生成时必须展开**：那时用户最关心的就是"它到底有没有在思考"，
 * 折叠起来会让人以为卡住了 —— 这正是此前"开了思考没反应"误解的形态。
 *
 * ## 为什么用独立底色
 * 视觉上必须与正文区分开：思考是草稿，不是结论。
 * 若与正文同色，用户会误把它当作回答的一部分。
 */
@Composable
private fun ReasoningPanel(reasoning: String, isStreaming: Boolean) {
    val scheme = MaterialTheme.colorScheme
    // 流式时强制展开；结束后回到用户自己的选择（默认收起）
    var userExpanded by remember { mutableStateOf(false) }
    val expanded = isStreaming || userExpanded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isStreaming) { userExpanded = !userExpanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isStreaming) {
                // 生成中：用动画点表示"还在思考"，比静态文字更能说明是活的
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = scheme.primary
                )
            } else {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isStreaming) "正在思考…" else "思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!isStreaming) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 9.dp)
            )
        }
    }
}

// ============================================================================
// 联网检索来源
// ============================================================================

private fun parseSearchSources(json: String?): List<SearchResult> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val type = object : TypeToken<List<SearchResult>>() {}.type
        Gson().fromJson<List<SearchResult>>(json, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun parseKnowledgeEvidences(json: String?): List<com.treechat.workbench.rag.KnowledgeRetriever.Evidence> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val type = object : TypeToken<List<com.treechat.workbench.rag.KnowledgeRetriever.Evidence>>() {}.type
        Gson().fromJson<List<com.treechat.workbench.rag.KnowledgeRetriever.Evidence>>(json, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 本地知识库原文引用横幅与可展开原文核对面板（纯 Material Design 3 组件，无 Emoji）。
 *
 * 显式告知用户「本回答正在调用并回复本地知识库原文」，点击即可逐段对照一字不差的教材原文。
 */
@Composable
private fun KnowledgeSourcesBanner(
    evidences: List<com.treechat.workbench.rag.KnowledgeRetriever.Evidence>
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .background(scheme.primaryContainer.copy(alpha = 0.55f))
            .border(1.dp, scheme.primary.copy(alpha = 0.35f), MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "正在回复本地知识库原文 · 精准命中 ${evidences.size} 处教材切片",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary
                )
                val distinctTitles = evidences.map { it.documentTitle }.distinct().joinToString("、")
                Text(
                    text = "来源：《$distinctTitles》",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (expanded) "收起原文" else "核对原文",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起原文" else "展开原文",
                tint = scheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                evidences.forEachIndexed { idx, ev ->
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = scheme.surface.copy(alpha = 0.9f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            val sectionPart = ev.section?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                            Text(
                                text = "切片 [${idx + 1}] 《${ev.documentTitle}》$sectionPart · 第 ${ev.orderIndex + 1} 段",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = ev.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 解析消息里附带的图片（data URI 数组）。
 *
 * 解析失败一律当作"没有图片"：图片是附加内容，
 * 一段坏 JSON 不该让整条消息甚至整个对话页渲染失败。
 */
private fun parseMessageImages(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val type = object : TypeToken<List<String>>() {}.type
        Gson().fromJson<List<String>>(json, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 检索来源卡片。
 *
 * 默认折叠：这些是"可核查的出处"，不是正文，全铺开会把回答挤下去。
 * 展开后给全 —— 序号、标题、完整链接，方便逐条核对。
 */
@Composable
private fun SearchSourcesCard(sources: List<SearchResult>) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "已检索 ${sources.size} 个网页",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sources.forEachIndexed { index, source ->
                        Row {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary,
                                modifier = Modifier.width(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = source.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = source.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.tertiary,
                                    maxLines = 1,
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

// ============================================================================
// 三点加载指示
// ============================================================================

@Composable
private fun ThreeDotLoader() {
    val scheme = MaterialTheme.colorScheme
    val transition = if (PerformanceMode.allowAmbientAnimation) {
        rememberInfiniteTransition(label = "dots")
    } else {
        null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(3) { index ->
            val alpha = if (transition != null) {
                val animated by transition.animateFloat(
                    initialValue = 0.22f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 620, delayMillis = index * 140),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dotAlpha$index"
                )
                animated
            } else {
                0.75f
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = alpha))
            )
        }
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "生成中",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================================
// 输入栏
// ============================================================================

/**
 * 「返回检索结果」横条。
 *
 * 只在从检索跳到原文期间出现（由 `jumpToKeyword` 非空驱动），
 * 点它把检索面板重新拉起来，并保留原来的关键词与范围选择。
 */
@Composable
private fun SearchJumpReturnBar(
    keyword: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable { onBack() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回检索结果",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "返回检索结果 · 命中「$keyword」",
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isStreaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 已附加的图片（压缩后的 data URI）。 */
    images: List<String> = emptyList(),
    onAddImages: () -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    /** 正在压缩图片（读盘 + 缩放 + JPEG 编码，可能耗时）。 */
    pickingImages: Boolean = false,
    /** 图片处理失败时的提示文案（可为空）。 */
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    /** 安卓键鼠适配：回车是否直接发送消息（Shift/Ctrl+Enter 保持换行）。 */
    enterToSend: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ---- 图片处理失败提示 ----
            // 明确告诉用户"哪一步没成功"，而不是静默地少一张图
            if (!errorMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissError) {
                        Text("知道了", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // ---- 已选图片的缩略图预览 ----
            //
            // 放在输入框**上方**而不是内部：文本框是单行高度的多行输入，
            // 把缩略图塞进去会让光标与图片抢位置。
            if (images.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    images.forEachIndexed { index, dataUri ->
                        Box {
                            ImageThumbnail(dataUri = dataUri)
                            // 删除角标：用半透明底衬，保证在任何图片上都看得见
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .clickable { onRemoveImage(index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "移除这张图片",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // ---- 添加图片 ----
                IconButton(
                    onClick = onAddImages,
                    // 生成中或正在压缩时禁用：避免与流式请求抢资源
                    enabled = !isStreaming && !pickingImages
                ) {
                    if (pickingImages) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = scheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加图片",
                            tint = if (isStreaming) {
                                scheme.onSurfaceVariant.copy(alpha = 0.4f)
                            } else {
                                scheme.onSurfaceVariant
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (enterToSend && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                if (event.isShiftPressed || event.isCtrlPressed) {
                                    // 组合键保留换行
                                    false
                                } else {
                                    // 回车直接发送，消费事件以避免插入额外换行符
                                    onSend()
                                    true
                                }
                            } else {
                                false
                            }
                        },
                    placeholder = {
                        Text(
                            text = if (images.isEmpty()) {
                                "在此分支继续追问…"
                            } else {
                                "就这张图提问（也可不写文字直接发送）…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant
                        )
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary,
                        unfocusedBorderColor = scheme.outlineVariant,
                        focusedContainerColor = scheme.surfaceContainerHighest,
                        unfocusedContainerColor = scheme.surfaceContainerHighest,
                        focusedTextColor = scheme.onSurface,
                        unfocusedTextColor = scheme.onSurface
                    ),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(10.dp))

                // 只有图片没有文字时也允许发送（用户会直接发图问"这是什么"），
                // 所以禁用条件必须同时考虑文字与图片。
                val canSend = text.isNotBlank() || images.isNotEmpty()

                FilledIconButton(
                    onClick = { if (isStreaming) onStop() else onSend() },
                    enabled = isStreaming || canSend,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    colors = if (isStreaming) {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = scheme.errorContainer,
                            contentColor = scheme.onErrorContainer
                        )
                    } else {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary
                        )
                    }
                ) {
                    Icon(
                        imageVector = if (isStreaming) {
                            Icons.Default.Stop
                        } else {
                            Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = if (isStreaming) "停止" else "发送",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * 把 data URI 画成缩略图。
 *
 * ## 为什么用 Android 的 BitmapFactory 解码
 * 项目没有引入图片加载库（Gradle 缓存里没有 Coil/Glide，
 * 且本项目约定"新依赖会导致依赖解析失败"），所以这里自己解码。
 *
 * ## 为什么必须限制采样率
 * 原图可能是 4000×3000，直接整张解码进内存会 OOM。
 * 这里用 `inSampleSize` 按目标边长（[targetPx]）降采样，
 * 只解出缩略图所需的像素量。
 *
 * ## 解码失败时的表现
 * 画一个占位方块而不是崩溃：图片数据可能来自旧版本或被损坏，
 * 一条坏图不该让整个对话页打不开。
 */
@Composable
private fun ImageThumbnail(dataUri: String, targetPx: Int = 96) {
    val scheme = MaterialTheme.colorScheme

    val bitmap = remember(dataUri) {
        runCatching {
            val parsed = com.treechat.workbench.rag.ImagePicker.splitDataUri(dataUri)
                ?: return@runCatching null
            val bytes = android.util.Base64.decode(parsed.second, android.util.Base64.DEFAULT)
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val options = android.graphics.BitmapFactory.Options().apply {
                // 取 2 的幂次采样率，使解出的图不小于目标尺寸
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx &&
                    bounds.outHeight / (sample * 2) >= targetPx
                ) {
                    sample *= 2
                }
                inSampleSize = sample
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .size(targetPx.dp)
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "已附加的图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "图片",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

