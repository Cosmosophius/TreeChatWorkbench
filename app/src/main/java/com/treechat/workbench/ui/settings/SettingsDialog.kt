package com.treechat.workbench.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.data.prefs.BoldColorStyle
import com.treechat.workbench.data.prefs.BoldHighlightColor
import com.treechat.workbench.data.prefs.BoldFontStyle
import com.treechat.workbench.data.prefs.InputMode
import com.treechat.workbench.network.client.AiStreamClient
import com.treechat.workbench.network.client.ConnTestResult
import com.treechat.workbench.network.protocol.AiModelInfo
import com.treechat.workbench.network.protocol.ProtocolType
import com.treechat.workbench.network.protocol.ProviderConfig
import com.treechat.workbench.network.protocol.ReasoningEffort
import com.treechat.workbench.network.search.SearchBackend
import com.treechat.workbench.network.search.SearchProbeResult
import com.treechat.workbench.network.search.WebSearchClient
import com.treechat.workbench.ui.selection.SelectionScheme
import com.treechat.workbench.ui.theme.AccentIceBlue
import com.treechat.workbench.ui.theme.AccentNeonGreen
import com.treechat.workbench.ui.theme.DangerRed
import com.treechat.workbench.ui.theme.DarkBackground
import com.treechat.workbench.ui.theme.DarkSurface
import com.treechat.workbench.ui.theme.DarkSurfaceVariant
import com.treechat.workbench.ui.theme.TextMuted
import com.treechat.workbench.ui.theme.TextPrimary
import com.treechat.workbench.ui.theme.TextSecondary
import com.treechat.workbench.ui.theme.ThemeMode
import com.treechat.workbench.ui.theme.ThemePalette
import com.treechat.workbench.ui.theme.WarningYellow
import com.treechat.workbench.ui.theme.WindowBorderColor
import com.treechat.workbench.ui.theme.previewColor
import com.treechat.workbench.util.TokenFormat
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class SettingsTab(
    val title: String,
    val icon: ImageVector
) {
    PROVIDER_AND_MODEL("服务商与模型", Icons.Default.Memory),
    APPEARANCE_AND_PREFS("外观与偏好", Icons.Default.Palette)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initialConfig: ProviderConfig,
    settings: AppSettings,
    /**
     * 已保存的**全部**服务商。
     *
     * 旧实现只传一份 `initialConfig`，保存时整体覆盖 —— 这正是
     * "改服务商就把上一个遗忘"的根源。现在把列表整个交给面板，
     * 面板可以在里面增删切换，保存时交回全集。
     */
    savedProviders: List<ProviderConfig> = listOf(initialConfig),
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    /**
     * 保存**整份列表** + 当前选中项。
     *
     * 与 onSave 的分工：onSave 仍然只报"这次编的是哪一条"（保持兼容），
     * 而这个回调把"保存后剩下的所有服务商"和"谁被选中"一起交回去，
     * 由 Activity 落盘。删除服务商只能通过它表达（onSave 表达不了"删了谁"）。
     */
    onSaveAll: (List<ProviderConfig>, String?) -> Unit = { _, _ -> }
) {
    val scope = rememberCoroutineScope()
    val client = remember { AiStreamClient() }

    // 可编辑的服务商列表。用快照列表以便原地增删。
    val providers = remember {
        mutableStateListOf<ProviderConfig>().apply {
            addAll(
                savedProviders.ifEmpty {
                    listOf(
                        initialConfig.takeIf { it.baseUrl.isNotBlank() }
                            ?: ProviderConfig.defaultDeepSeek()
                    )
                }
            )
        }
    }

    /** 当前正在编辑列表里的第几条。切换它时要整块重置下面的编辑状态。 */
    var editingIndex by remember {
        mutableStateOf(
            providers.indexOfFirst { it.id == initialConfig.id }.takeIf { it >= 0 } ?: 0
        )
    }

    /** 当前选中的设置标签页（保持在 key(editingIndex) 之外，避免切换服务商时跳页）。 */
    var selectedTab by remember { mutableStateOf(SettingsTab.PROVIDER_AND_MODEL) }

    // 切换服务商时，用 key() 重建整块编辑区，让下面所有 remember 状态
    // 都按新选中的那条初始化。不这样做会出现"切了服务商但输入框还是上一个的值"。
    key(editingIndex) {
        ProviderEditor(
            initialConfig = providers.getOrElse(editingIndex) { initialConfig },
            settings = settings,
            providers = providers,
            editingIndex = editingIndex,
            onSelectIndex = { index ->
                // -1 = 刚把当前编辑的那条删掉了。回落到第一条（列表不会为空：
                // 只剩一条时界面上不提供删除），而不是让编辑区停在一个不存在的下标上。
                editingIndex = if (index < 0) 0 else index.coerceAtMost(providers.lastIndex)
            },
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            client = client,
            scope = scope,
            onDismiss = onDismiss,
            onSave = onSave,
            onSaveAll = onSaveAll
        )
    }
}

/**
 * 单个服务商的编辑区（从服务商列表里切出来的那一块）。
 *
 * 抽成独立 Composable 是为了能用 `key(editingIndex)` 把它的全部内部状态
 * 跟着"当前编辑哪一条"一起重建 —— 状态重建是这里唯一可靠的切换手段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditor(
    initialConfig: ProviderConfig,
    settings: AppSettings,
    providers: MutableList<ProviderConfig>,
    editingIndex: Int,
    onSelectIndex: (Int) -> Unit,
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    client: AiStreamClient,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    onSaveAll: (List<ProviderConfig>, String?) -> Unit
) {
    var providerId by remember { mutableStateOf(initialConfig.id) }
    var displayName by remember { mutableStateOf(initialConfig.name) }
    var apiUrl by remember { mutableStateOf(initialConfig.baseUrl) }
    var protocolType by remember { mutableStateOf(initialConfig.protocolType) }
    var apiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var selectedModelId by remember { mutableStateOf(initialConfig.selectedModelId) }
    // 推理强度：按服务商保存。旧配置没有这个字段，走 orOff 兜底为"关闭"。
    var reasoningEffort by remember { mutableStateOf(initialConfig.reasoningEffortOrOff) }

    val models = remember {
        mutableStateListOf<AiModelInfo>().apply {
            addAll(
                initialConfig.availableModels.ifEmpty {
                    listOf(AiModelInfo("deepseek-chat", "DeepSeek-V3", 64_000L, 8_192L))
                }
            )
        }
    }

    var protocolExpanded by remember { mutableStateOf(false) }
    var backendExpanded by remember { mutableStateOf(false) }
    var schemeExpanded by remember { mutableStateOf(false) }
    var inputModeExpanded by remember { mutableStateOf(false) }
    var boldColorExpanded by remember { mutableStateOf(false) }
    var boldHighlightExpanded by remember { mutableStateOf(false) }
    var boldFontExpanded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnTestResult?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var fetchMessage by remember { mutableStateOf<String?>(null) }
    // 联网诊断的独立实例：用独立的 OkHttpClient，避免诊断的超时设置
    // 影响到正在进行的对话请求
    val searchClient = remember { WebSearchClient() }
    var probeRunning by remember { mutableStateOf(false) }
    var probeResults by remember { mutableStateOf<List<SearchProbeResult>>(emptyList()) }

    fun currentConfig() = ProviderConfig(
        id = providerId.ifBlank { "custom" },
        name = displayName.ifBlank { "自定义服务" },
        baseUrl = apiUrl.trim(),
        apiKey = apiKey.trim(),
        protocolType = protocolType,
        selectedModelId = selectedModelId,
        availableModels = models.toList(),
        reasoningEffort = reasoningEffort
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ---------- 顶栏 ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "工作台设置",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextMuted)
                    }
                }

                // ---------- Material Design 3 顶部分栏 TabRow ----------
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = DarkSurface,
                    contentColor = AccentNeonGreen,
                    divider = { Divider() }
                ) {
                    SettingsTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Tab(
                            selected = isSelected,
                            onClick = { onSelectTab(tab) },
                            selectedContentColor = AccentNeonGreen,
                            unselectedContentColor = TextMuted
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tab.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // ---------- 分栏内容区 ----------
                when (selectedTab) {
                    SettingsTab.PROVIDER_AND_MODEL -> {
                        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // ---------- 服务商切换栏（多服务商并存） ----------
                            //
                            // 为什么要有这一栏：旧实现只保存一份配置，配置第二个服务商就会
                            // 把第一个覆盖掉。现在所有服务商并存，需要一个地方"看见它们、
                            // 切换、删除"。切换用 key(editingIndex) 整块重建编辑区。
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkSurfaceVariant)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "服务商",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                // 横向滚动，服务商多了也不会把布局撑破
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    providers.forEachIndexed { index, provider ->
                                        val isEditing = index == editingIndex
                                        val label = provider.name.ifBlank { provider.id }
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(
                                                    if (isEditing) AccentNeonGreen.copy(alpha = 0.18f)
                                                    else DarkSurface
                                                )
                                                .border(
                                                    width = if (isEditing) 1.5.dp else 1.dp,
                                                    color = if (isEditing) AccentNeonGreen
                                                    else WindowBorderColor,
                                                    shape = RoundedCornerShape(7.dp)
                                                )
                                                .clickable { onSelectIndex(index) }
                                                .padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isEditing) AccentNeonGreen else TextPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = if (isEditing) FontWeight.Bold else FontWeight.Normal
                                            )
                                            // 只剩一个时不显示删除：删空之后没有任何服务商可用，
                                            // 而"新建"是另一件事，混在一起会让用户迷路。
                                            if (providers.size > 1) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "删除 $label",
                                                    tint = TextMuted,
                                                    modifier = Modifier
                                                        .size(13.dp)
                                                        .clickable {
                                                            providers.removeAt(index)
                                                            // 删掉当前编辑项时，选中位置回退一格；
                                                            // 传 -1（表示"被删掉了"）让上层决定新选中项
                                                            onSelectIndex(
                                                                when {
                                                                    index < editingIndex -> editingIndex - 1
                                                                    index == editingIndex -> -1
                                                                    else -> editingIndex
                                                                }
                                                            )
                                                            // 立刻把"删除"这一事实交回去：
                                                            // onSave 只报"编的是哪一条"，表达不了删除
                                                            onSaveAll(providers.toList(), null)
                                                        }
                                                )
                                            }
                                        }
                                    }
                                }
                                // 新增：另起一条，默认指向本地反代常见端口，省掉一次手打
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(AccentNeonGreen.copy(alpha = 0.14f))
                                        .clickable {
                                            val fresh = ProviderConfig(
                                                id = "provider-" + System.currentTimeMillis().toString().takeLast(5),
                                                name = "新服务商",
                                                baseUrl = "",
                                                apiKey = "",
                                                protocolType = ProtocolType.ANTHROPIC_MESSAGES,
                                                selectedModelId = "",
                                                availableModels = emptyList()
                                            )
                                            providers.add(fresh)
                                            onSelectIndex(providers.size - 1)
                                        }
                                        .padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        tint = AccentNeonGreen,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("添加", color = AccentNeonGreen, fontSize = 11.sp)
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = "支持任意兼容 OpenAI Chat Completions 或 Anthropic Messages 协议的接口，" +
                                        "包括官方端点、中转站与自部署服务。",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )

                                // ---------------- 模型服务商 ----------------
                                Text(
                                    text = "快速预设",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // 快速预设
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ProviderConfig.presets().forEach { preset ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(DarkSurfaceVariant)
                                                .border(1.dp, WindowBorderColor, RoundedCornerShape(8.dp))
                                                .clickable {
                                                    providerId = preset.id
                                                    displayName = preset.name
                                                    apiUrl = preset.baseUrl
                                                    protocolType = preset.protocolType
                                                    models.clear()
                                                    models.addAll(preset.availableModels)
                                                    selectedModelId = preset.selectedModelId
                                                    testResult = null
                                                }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(preset.name, color = TextPrimary, fontSize = 11.sp)
                                        }
                                    }
                                }

                                FieldLabel("Provider ID")
                                StyledTextField(providerId, { providerId = it }, "acme-gateway")
                                FieldHint("以小写字母开头的标识，用于在请求中唯一标识该服务商。")

                                FieldLabel("显示名称")
                                StyledTextField(displayName, { displayName = it }, "显示名称")

                                FieldLabel("API 地址")
                                StyledTextField(apiUrl, { apiUrl = it }, "https://api.deepseek.com/v1")
                                FieldHint("通常需要包含 /v1 后缀。")

                                FieldLabel("API 协议")
                                ExposedDropdownMenuBox(
                                    expanded = protocolExpanded,
                                    onExpandedChange = { protocolExpanded = !protocolExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = if (protocolType == ProtocolType.OPENAI_COMPATIBLE) {
                                            "OpenAI Chat Completions"
                                        } else {
                                            "Anthropic Messages"
                                        },
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded)
                                        },
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        colors = fieldColors()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = protocolExpanded,
                                        onDismissRequest = { protocolExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("OpenAI Chat Completions") },
                                            onClick = {
                                                protocolType = ProtocolType.OPENAI_COMPATIBLE
                                                protocolExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Anthropic Messages") },
                                            onClick = {
                                                protocolType = ProtocolType.ANTHROPIC_MESSAGES
                                                protocolExpanded = false
                                            }
                                        )
                                    }
                                }

                                FieldLabel("API 密钥")
                                StyledTextField(apiKey, { apiKey = it }, "输入 API 密钥", password = true)

                                // 连通性测试
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            testing = true
                                            testResult = null
                                            scope.launch {
                                                testResult = client.testConnection(currentConfig())
                                                testing = false
                                            }
                                        },
                                        enabled = !testing,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        if (testing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = AccentIceBlue
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("测试中…", fontSize = 11.sp, color = AccentIceBlue)
                                        } else {
                                            Icon(
                                                Icons.Default.NetworkCheck,
                                                contentDescription = null,
                                                tint = AccentIceBlue,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("测试连接", fontSize = 11.sp, color = AccentIceBlue)
                                        }
                                    }

                                    testResult?.let { result ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (result.ok) {
                                                    Icons.Default.Check
                                                } else {
                                                    Icons.Default.ErrorOutline
                                                },
                                                contentDescription = null,
                                                tint = if (result.ok) AccentNeonGreen else DangerRed,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = result.message,
                                                color = if (result.ok) AccentNeonGreen else DangerRed,
                                                fontSize = 10.5.sp,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }

                                Divider()

                                // ---------- 模型目录 ----------
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "模型目录",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                fetching = true
                                                fetchMessage = null
                                                scope.launch {
                                                    val fetched = client.fetchModels(currentConfig())
                                                    if (fetched.isEmpty()) {
                                                        fetchMessage =
                                                            "未获取到模型列表。该端点可能不提供 /models 接口，可手动添加。"
                                                    } else {
                                                        // 关键修复：用服务商上报的真实容量，而不是硬编码。
                                                        val existing = models.map { it.id }.toSet()
                                                        val added = fetched.filterNot { existing.contains(it.id) }
                                                        models.addAll(added)
                                                        // 已有的模型：只补容量，不覆盖用户手改过的名字
                                                        fetched.forEach { fresh ->
                                                            val index = models.indexOfFirst { it.id == fresh.id }
                                                            if (index >= 0) {
                                                                val current = models[index]
                                                                if (current.contextWindow != fresh.contextWindow ||
                                                                    current.maxOutputTokens != fresh.maxOutputTokens
                                                                ) {
                                                                    models[index] = current.copy(
                                                                        contextWindow = fresh.contextWindow,
                                                                        maxOutputTokens = fresh.maxOutputTokens
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        val sample = fetched.firstOrNull()
                                                        fetchMessage = buildString {
                                                            append("已获取 ${fetched.size} 个模型")
                                                            if (added.isNotEmpty()) append("，新增 ${added.size} 个")
                                                            if (sample != null) {
                                                                append("（容量取自服务商上报：上下文 ")
                                                                append(TokenFormat.format(sample.contextWindow))
                                                                append("，最大输出 ")
                                                                append(TokenFormat.format(sample.maxOutputTokens))
                                                                append("）")
                                                            }
                                                        }
                                                    }
                                                    fetching = false
                                                }
                                            }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        if (fetching) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 1.5.dp,
                                                color = TextMuted
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                        } else {
                                            Icon(
                                                Icons.Default.CloudDownload,
                                                contentDescription = null,
                                                tint = TextMuted,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                        }
                                        Text("获取可用模型", color = TextMuted, fontSize = 11.sp)
                                    }
                                }

                                fetchMessage?.let {
                                    Text(it, color = TextMuted, fontSize = 10.5.sp)
                                }

                                models.forEachIndexed { index, model ->
                                    val isActive = model.id == selectedModelId
                                    ModelCard(
                                        model = model,
                                        isActive = isActive,
                                        onSelect = { selectedModelId = model.id },
                                        onUpdate = { updated ->
                                            models[index] = updated
                                            if (isActive) selectedModelId = updated.id
                                        },
                                        onDelete = {
                                            models.removeAt(index)
                                            if (isActive) selectedModelId = models.firstOrNull()?.id ?: ""
                                        }
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        val newModel = AiModelInfo(
                                            id = "model-" + System.currentTimeMillis().toString().takeLast(4),
                                            name = "自定义模型",
                                            contextWindow = 128_000L,
                                            maxOutputTokens = 8_192L
                                        )
                                        models.add(newModel)
                                        if (selectedModelId.isBlank()) selectedModelId = newModel.id
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        tint = AccentNeonGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("添加模型", color = AccentNeonGreen, fontSize = 12.sp)
                                }

                                Divider()

                                // ---------------- 思考深度 / 推理强度 ----------------
                                //
                                // 按服务商保存（不是全局）：同一模型在不同中转站上对思考的支持
                                // 不一样，用户经常要针对"这一条链路"单独调。
                                Text(
                                    text = "思考深度（推理强度）",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when (protocolType) {
                                        ProtocolType.ANTHROPIC_MESSAGES ->
                                            "以 Anthropic 的 thinking.budget_tokens 下发；" +
                                                "选「关闭」时不发送该字段（最兼容）。"
                                        ProtocolType.OPENAI_COMPATIBLE ->
                                            "以 reasoning_effort 下发；" +
                                                "选「关闭」时不发送该字段（最兼容）。"
                                    },
                                    color = TextMuted,
                                    fontSize = 10.5.sp
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    ReasoningEffort.entries.forEach { option ->
                                        val isOn = option == reasoningEffort
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(
                                                    if (isOn) AccentNeonGreen.copy(alpha = 0.18f)
                                                    else DarkSurface
                                                )
                                                .border(
                                                    width = if (isOn) 1.5.dp else 1.dp,
                                                    color = if (isOn) AccentNeonGreen else WindowBorderColor,
                                                    shape = RoundedCornerShape(7.dp)
                                                )
                                                .clickable { reasoningEffort = option }
                                                .padding(horizontal = 11.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = option.label,
                                                color = if (isOn) AccentNeonGreen else TextPrimary,
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isOn) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                                // 把会被真正下发的值摊开给用户看，避免"设了但不知道发了什么"
                                Text(
                                    text = when (val effort = reasoningEffort) {
                                        ReasoningEffort.OFF -> "当前：不发送推理参数（兼容性最好）"
                                        else -> when (protocolType) {
                                            ProtocolType.ANTHROPIC_MESSAGES ->
                                                "当前：thinking.budget_tokens = " +
                                                    "${effort.anthropicBudgetTokens}（受 max_tokens 上限保护）"
                                            ProtocolType.OPENAI_COMPATIBLE ->
                                                "当前：reasoning_effort = \"${effort.openAiValue}\""
                                        }
                                    },
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )

                                Divider()
                                Spacer(modifier = Modifier.height(10.dp))

                                // ---------------- Embedding 向量模型服务商（知识库检索） ----------------
                                EmbeddingProviderSection(settings)

                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }

                    SettingsTab.APPEARANCE_AND_PREFS -> {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // ---------------- 联网搜索（原检索与知识库合并至此） ----------------
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = AccentIceBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "联网搜索",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "启用联网搜索",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "模型自行判断何时需要检索，检索到的网站会列在回答下方",
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
                                    )
                                }
                                Switch(
                                    checked = settings.searchEnabled,
                                    onCheckedChange = { settings.updateSearchEnabled(it) },
                                    colors = SwitchDefaults.colors(checkedTrackColor = AccentNeonGreen)
                                )
                            }

                            if (settings.searchEnabled) {
                                FieldLabel("搜索后端")
                                ExposedDropdownMenuBox(
                                    expanded = backendExpanded,
                                    onExpandedChange = { backendExpanded = !backendExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = settings.searchBackend.displayName,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = backendExpanded)
                                        },
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        colors = fieldColors()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = backendExpanded,
                                        onDismissRequest = { backendExpanded = false }
                                    ) {
                                        SearchBackend.entries.forEach { backend ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(backend.displayName, fontSize = 12.sp)
                                                },
                                                onClick = {
                                                    settings.updateSearchBackend(backend)
                                                    backendExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // 后端可靠性的实话提示 —— 免密钥方案是抓网页，本来就不保证稳定
                                Text(
                                    text = when (settings.searchBackend) {
                                        SearchBackend.NATIVE ->
                                            "跟随当前模型配置智能判断。若服务商（如小米/DeepSeek）官方接口未提供搜索端点，" +
                                                "将自动启用内置的高可用免配置直连管道，开箱即用。"
                                        SearchBackend.SOGOU ->
                                            "搜狗移动端免密钥直连搜索（国内极速推荐），秒级返回真实网页与摘要，抗风控稳定性极高。"
                                        SearchBackend.BING ->
                                            "抓取 Bing 结果页，无需密钥；若单次被拦截将自动由公共节点兜底。"
                                        SearchBackend.DUCKDUCKGO ->
                                            "抓取 DuckDuckGo 结果页，无需密钥。"
                                        SearchBackend.SEARXNG ->
                                            "调用 SearXNG 实例的 JSON 接口，若未填写地址将自动使用内置高可用公共节点池。"
                                        SearchBackend.TAVILY ->
                                            "第三方搜索 API，为 LLM 优化，稳定，但需要单独申请 Key（可能要付费）。"
                                    },
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                )

                                if (settings.searchBackend.requiresApiKey) {
                                    FieldLabel("搜索 API Key")
                                    StyledTextField(
                                        value = settings.searchApiKey,
                                        onValueChange = { settings.updateSearchApiKey(it) },
                                        placeholder = "tvly-...",
                                        password = true
                                    )
                                }

                                if (settings.searchBackend.requiresBaseUrl) {
                                    FieldLabel("SearXNG 实例地址")
                                    StyledTextField(
                                        value = settings.searxngBaseUrl,
                                        onValueChange = { settings.updateSearxngBaseUrl(it) },
                                        placeholder = "https://searx.example.com"
                                    )
                                }

                                settings.searchConfigIssue()?.let { issue ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = WarningYellow,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = issue,
                                            color = WarningYellow,
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp
                                        )
                                    }
                                }

                                Divider()

                                // ---------------- 联网诊断 ----------------
                                // 存在的意义：联网失败时用户只看到一句"原生搜索失败（HTTP 404）"，
                                // 无从判断到底是"中转站没有 /messages 端点"还是"密钥不对"。
                                // 这里把每个后端的**真实请求地址、状态码、返回片段**摊开。
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "联网诊断",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            probeRunning = true
                                            probeResults = emptyList()
                                            scope.launch {
                                                val results = searchClient.probe(
                                                    config = settings.searchConfig(),
                                                    provider = currentConfig()
                                                )
                                                probeResults = results
                                                probeRunning = false
                                            }
                                        },
                                        enabled = !probeRunning
                                    ) {
                                        Text(
                                            text = if (probeRunning) "测试中…" else "逐个后端测试",
                                            color = AccentNeonGreen,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                if (probeRunning) {
                                    FieldHint("正在依次请求各后端，约需几秒到十几秒（每个后端有独立超时）。")
                                }

                                if (probeResults.isNotEmpty()) {
                                    probeResults.forEach { result ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(DarkSurfaceVariant)
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (result.ok) {
                                                        Icons.Default.Check
                                                    } else {
                                                        Icons.Default.Warning
                                                    },
                                                    contentDescription = null,
                                                    tint = if (result.ok) AccentNeonGreen else WarningYellow,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = result.backend.displayName,
                                                    color = TextPrimary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = buildString {
                                                        append(result.httpCode?.toString() ?: "无响应")
                                                        append(" · ")
                                                        append(result.elapsedMs)
                                                        append("ms")
                                                    },
                                                    color = TextMuted,
                                                    fontSize = 10.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = "请求：${result.requestUrl}",
                                                color = TextMuted,
                                                fontSize = 9.5.sp,
                                                lineHeight = 13.sp
                                            )
                                            Text(
                                                text = result.detail,
                                                color = if (result.ok) TextSecondary else WarningYellow,
                                                fontSize = 9.5.sp,
                                                lineHeight = 13.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }
                                }
                            }

                            Divider()
                            Spacer(modifier = Modifier.height(6.dp))

                            // ---------------- 外观 ----------------
                            Text(
                                text = "外观模式",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                ThemeMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = settings.themeMode == mode,
                                        onClick = { settings.updateThemeMode(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = ThemeMode.entries.size
                                        )
                                    ) {
                                        Text(
                                            text = when (mode) {
                                                ThemeMode.SYSTEM -> "跟随系统"
                                                ThemeMode.LIGHT -> "亮色"
                                                ThemeMode.DARK -> "暗色"
                                            },
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            Divider()

                            // ---------------- 字体大小 ----------------
                            //
                            // 用 MaterialTheme.typography.* 取字号，而不是写死 xxx.sp：
                            // 这样这块预览文字本身就**跟随滑块缩放**，用户拖动时能
                            // 直接看到效果，不必退出去看别处。
                            FontScaleSetting(
                                scale = settings.fontScale,
                                // 拖动中只更新内存状态：立即重组，界面即时跟随
                                onScaleChange = { settings.updateFontScale(it) },
                                // 松手时持久化。updateFontScale 内部会先钳制再写盘，
                                // 所以这里直接传值即可，无需在 UI 层重复钳制。
                                onScaleCommit = { settings.updateFontScale(it) }
                            )

                            Divider()

                            // ---------------- 粗体呈现 ----------------
                            //
                            // 为什么需要这一组设置：中文里 `FontWeight.Bold` 的实际
                            // 差异很小（多数中文字体没有真正的 Bold 字重，合成加粗很弱），
                            // 于是模型输出里标粗的重点在长段落中经常"等于没标"。
                            // 这里把颜色 / 底色 / 字形三个维度独立交给用户 ——
                            // 只改默认仍是原样，不影响现有观感。
                            FieldLabel("粗体颜色（中文加粗常看不出差别，可改为上色）")
                            ExposedDropdownMenuBox(
                                expanded = boldColorExpanded,
                                onExpandedChange = { boldColorExpanded = !boldColorExpanded }
                            ) {
                                OutlinedTextField(
                                    value = settings.boldColorStyle.displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = boldColorExpanded
                                        )
                                    },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    colors = fieldColors()
                                )
                                ExposedDropdownMenu(
                                    expanded = boldColorExpanded,
                                    onDismissRequest = { boldColorExpanded = false }
                                ) {
                                    BoldColorStyle.entries.forEach { style ->
                                        DropdownMenuItem(
                                            text = { Text(style.displayName, fontSize = 12.sp) },
                                            onClick = {
                                                settings.updateBoldStyle(color = style)
                                                boldColorExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Text(
                                text = settings.boldColorStyle.description,
                                color = TextMuted,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )

                            FieldLabel("粗体高亮底色")
                            ExposedDropdownMenuBox(
                                expanded = boldHighlightExpanded,
                                onExpandedChange = { boldHighlightExpanded = !boldHighlightExpanded }
                            ) {
                                OutlinedTextField(
                                    value = settings.boldHighlightColor.displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = boldHighlightExpanded
                                        )
                                    },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    colors = fieldColors()
                                )
                                ExposedDropdownMenu(
                                    expanded = boldHighlightExpanded,
                                    onDismissRequest = { boldHighlightExpanded = false }
                                ) {
                                    BoldHighlightColor.entries.forEach { color ->
                                        DropdownMenuItem(
                                            text = { Text(color.displayName, fontSize = 12.sp) },
                                            onClick = {
                                                settings.updateBoldStyle(highlight = color)
                                                boldHighlightExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            FieldLabel("粗体字体")
                            ExposedDropdownMenuBox(
                                expanded = boldFontExpanded,
                                onExpandedChange = { boldFontExpanded = !boldFontExpanded }
                            ) {
                                OutlinedTextField(
                                    value = settings.boldFontStyle.displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = boldFontExpanded
                                        )
                                    },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    colors = fieldColors()
                                )
                                ExposedDropdownMenu(
                                    expanded = boldFontExpanded,
                                    onDismissRequest = { boldFontExpanded = false }
                                ) {
                                    BoldFontStyle.entries.forEach { font ->
                                        DropdownMenuItem(
                                            text = { Text(font.displayName, fontSize = 12.sp) },
                                            onClick = {
                                                settings.updateBoldStyle(font = font)
                                                boldFontExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Text(
                                text = settings.boldFontStyle.description,
                                color = TextMuted,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )

                            Divider()

                            // ---------------- 文本选择方案 ----------------
                            //
                            // 为什么让用户选而不是替他定死：
                            // 选词好不好用**高度依赖输入设备** —— 触屏上"长按选句"最自然，
                            // 而鼠标/触控板的自然操作是"按下即拖"。两者的手势判据互斥
                            //（长按要求按住不动，拖选要求立刻响应位移），
                            // 用一套逻辑无法兼顾，因此做成显式方案。
                            FieldLabel("输入设备模式（键鼠 / 触控板 / 触控）")
                            ExposedDropdownMenuBox(
                                expanded = inputModeExpanded,
                                onExpandedChange = { inputModeExpanded = !inputModeExpanded }
                            ) {
                                OutlinedTextField(
                                    value = settings.inputMode.displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = inputModeExpanded
                                        )
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    colors = fieldColors()
                                )
                                ExposedDropdownMenu(
                                    expanded = inputModeExpanded,
                                    onDismissRequest = { inputModeExpanded = false }
                                ) {
                                    InputMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.displayName, fontSize = 12.sp) },
                                            onClick = {
                                                settings.updateInputMode(mode)
                                                inputModeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Text(
                                text = settings.inputMode.description,
                                color = TextMuted,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )

                            FieldLabel("文本选择方案（自动分流；上面的强制档优先级更高）")
                            ExposedDropdownMenuBox(
                                expanded = schemeExpanded,
                                onExpandedChange = { schemeExpanded = !schemeExpanded }
                            ) {
                                OutlinedTextField(
                                    value = settings.selectionScheme.displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = schemeExpanded
                                        )
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    colors = fieldColors()
                                )
                                ExposedDropdownMenu(
                                    expanded = schemeExpanded,
                                    onDismissRequest = { schemeExpanded = false }
                                ) {
                                    // ⚠️ MOUSE_DESKTOP 必须**出现在列表里**（用户明确要求加回来）。
                                    //
                                    // 曾经把它过滤掉，理由是"已被上面的输入设备模式取代"。
                                    // 但两者语义并不等价，过滤掉会让老用户找不到自己用惯的方案：
                                    //   · 输入设备模式是**全局强制档**（不论设备一律按某档处理）；
                                    //   · MOUSE_DESKTOP 是**自动分流**方案 —— 触屏仍长按，
                                    //     只有真的检测到鼠标/触控板时才切换桌面级手感。
                                    // 想让"插鼠标自动变桌面级、拔掉自动回触屏"的用户，
                                    // 要的正是后者。两处并存不会打架：强制档的优先级在
                                    // SelectableMarkdown 里已明确高于方案（见那里的优先级注释）。
                                    SelectionScheme.entries.forEach { scheme ->
                                        DropdownMenuItem(
                                            text = { Text(scheme.displayName, fontSize = 12.sp) },
                                            onClick = {
                                                settings.updateSelectionScheme(scheme)
                                                schemeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Text(
                                text = settings.selectionScheme.description,
                                color = TextMuted,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )

                            // ---------------- 排查用开关 ----------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "显示文本选择诊断",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "排查用：在对话页显示长按选字的手势判定结果。正常使用时请关闭。",
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                                Switch(
                                    checked = settings.selectionDiagnostics,
                                    onCheckedChange = { settings.updateSelectionDiagnostics(it) },
                                    colors = SwitchDefaults.colors(checkedTrackColor = AccentNeonGreen)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // ---------------- 键鼠适配：回车键发送消息 ----------------
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "键鼠适配：回车键发送",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "开启后外接键盘按 Enter 发送，Shift+Enter 或 Ctrl+Enter 换行；关闭后 Enter 为普通换行。",
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
                                    )
                                }
                                Switch(
                                    checked = settings.enterToSend,
                                    onCheckedChange = { settings.updateEnterToSend(it) },
                                    colors = SwitchDefaults.colors(checkedTrackColor = AccentNeonGreen)
                                )
                            }

                            Divider()

                            // ---------------- 命名出处·素扉仿古宣纸卡片 ----------------
                            TreeChatSealCard()

                            Spacer(modifier = Modifier.height(10.dp))

                            Divider()
                            
                            // ---------------- 主题色 ----------------
                            val usingMonet = settings.dynamicColor &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

                            Text(
                                text = "主题色",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (usingMonet) {
                                    "当前由壁纸取色决定，下面的预设色暂不生效"
                                } else {
                                    "选一个种子色，全套配色由 Material 色彩算法推导，自动满足对比度要求"
                                },
                                color = TextMuted,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ThemePalette.entries.forEach { palette ->
                                    val selected = settings.themePalette == palette && !usingMonet
                                    val swatch = palette.previewColor()
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable(enabled = !usingMonet) {
                                                settings.updateThemePalette(palette)
                                            }
                                            .padding(vertical = 4.dp, horizontal = 2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .alpha(if (usingMonet) 0.4f else 1f)
                                                .clip(CircleShape)
                                                .background(swatch)
                                                .border(
                                                    width = if (selected) 3.dp else 1.dp,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.onSurface
                                                    } else {
                                                        MaterialTheme.colorScheme.outlineVariant
                                                    },
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    // 按色块亮度决定对勾颜色，任何种子色上都能看清
                                                    tint = if (swatch.luminance() > 0.5f) {
                                                        Color.Black
                                                    } else {
                                                        Color.White
                                                    },
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = palette.displayName,
                                            color = if (selected) TextPrimary else TextMuted,
                                            fontSize = 9.5.sp
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "壁纸取色（莫奈）",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            "从壁纸提取配色方案"
                                        } else {
                                            "需要 Android 12 及以上，当前版本不可用"
                                        },
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                                Switch(
                                    checked = settings.dynamicColor &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                                    onCheckedChange = { settings.updateDynamicColor(it) },
                                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                                    colors = SwitchDefaults.colors(checkedTrackColor = AccentNeonGreen)
                                )
                            }

                            Divider()
                            // ---------------- 界面语言（四语言体系） ----------------
                            Text(
                                text = "界面语言",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "支持简体汉语、繁体汉语、English 与 Русский",
                                color = TextMuted,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                com.treechat.workbench.util.AppStrings.SUPPORTED_LANGUAGES.forEachIndexed { index, (langCode, langName) ->
                                    SegmentedButton(
                                        selected = settings.appLanguage == langCode,
                                        onClick = { settings.updateAppLanguage(langCode) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = com.treechat.workbench.util.AppStrings.SUPPORTED_LANGUAGES.size
                                        )
                                    ) {
                                        Text(
                                            text = langName,
                                            fontSize = 10.5.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // ---------------- 开源鸣谢与项目引用 ----------------
                            OpenSourceAcknowledgmentsCard()

                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                // ---------- 底部常驻操作栏（取消 / 保存配置） ----------
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, WindowBorderColor)
                    ) {
                        Text(
                            text = "取消",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentNeonGreen)
                            .clickable {
                                val valid = models.filter { it.id.isNotBlank() }
                                val edited = currentConfig().copy(
                                    selectedModelId = selectedModelId.ifBlank {
                                        valid.firstOrNull()?.id.orEmpty()
                                    },
                                    availableModels = valid
                                )
                                // 把这次编辑写回列表（按 id 覆盖那一条，不产生重复项），
                                // 然后交回**整份列表** —— 这才是"多服务商并存"的关键：
                                // 只交回一条会让上层把其它服务商覆盖掉。
                                val index = providers.indexOfFirst { it.id == edited.id }
                                if (index >= 0) providers[index] = edited
                                else providers.add(edited)

                                onSave(edited)
                                onSaveAll(providers.toList(), edited.id)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "保存配置",
                            color = Color.Black,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 模型卡片
// ============================================================================

@Composable
private fun ModelCard(
    model: AiModelInfo,
    isActive: Boolean,
    onSelect: () -> Unit,
    onUpdate: (AiModelInfo) -> Unit,
    onDelete: () -> Unit
) {
    val overBudget = model.maxOutputTokens > model.contextWindow && model.contextWindow > 0

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(11.dp),
        color = if (isActive) DarkSurfaceVariant else DarkSurface,
        border = BorderStroke(
            if (isActive) 1.5.dp else 1.dp,
            if (isActive) AccentNeonGreen else WindowBorderColor
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 选中指示
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (isActive) AccentNeonGreen else Color.Transparent)
                        .border(
                            1.5.dp,
                            if (isActive) AccentNeonGreen else TextMuted,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isActive) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isActive) "当前使用" else "点击选用",
                    color = if (isActive) AccentNeonGreen else TextMuted,
                    fontSize = 10.5.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除模型",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1.3f)) {
                    FieldLabel("模型 ID")
                    StyledTextField(
                        value = model.id,
                        onValueChange = { onUpdate(model.copy(id = it)) },
                        placeholder = "deepseek-chat"
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    FieldLabel("显示名称")
                    StyledTextField(
                        value = model.name,
                        onValueChange = { onUpdate(model.copy(name = it)) },
                        placeholder = "显示名称"
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenField(
                    label = "上下文窗口",
                    value = model.contextWindow,
                    onValueChange = { onUpdate(model.copy(contextWindow = it)) },
                    modifier = Modifier.weight(1f)
                )
                TokenField(
                    label = "最大输出 token 数",
                    value = model.maxOutputTokens,
                    onValueChange = { onUpdate(model.copy(maxOutputTokens = it)) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (overBudget) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = WarningYellow,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "最大输出不能大于上下文窗口，绝大多数服务端会直接拒绝该请求",
                        color = WarningYellow,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("输入类型", fontSize = 11.sp, color = TextMuted)
                Spacer(modifier = Modifier.width(10.dp))
                Checkbox(
                    checked = true,
                    onCheckedChange = null,
                    enabled = false,
                    colors = CheckboxDefaults.colors(
                        checkedColor = TextMuted,
                        disabledCheckedColor = TextMuted
                    ),
                    modifier = Modifier.size(20.dp)
                )
                Text("文本", fontSize = 11.sp, color = TextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Checkbox(
                    checked = model.supportsVision,
                    onCheckedChange = { onUpdate(model.copy(supportsVision = it)) },
                    colors = CheckboxDefaults.colors(checkedColor = AccentNeonGreen),
                    modifier = Modifier.size(20.dp)
                )
                Text("图片", fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}

// ============================================================================
// Token 输入框
// ============================================================================

/**
 * 可编辑的 token 数量输入框。
 *
 * 关键实现点（这正是不做就会"改不了"的原因）：
 * - 输入过程中**不回写格式化结果**，保留用户原始输入（可以是 "128k"），
 *   否则每敲一个字符显示值就被重算覆盖，光标会来回跳。
 * - 失焦后才规整成 "128K" / "1M" 这类规范写法。
 * - 下方始终显示精确整数值，保证用户能核对到底存了多少（"这个一定要对"）。
 */
@Composable
private fun TokenField(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(TokenFormat.format(value)) }
    var focused by remember { mutableStateOf(false) }

    // 外部值变化且当前未在编辑时，同步显示
    LaunchedEffect(value, focused) {
        if (!focused) {
            val formatted = TokenFormat.format(value)
            if (formatted != text) text = formatted
        }
    }

    Column(modifier = modifier) {
        FieldLabel(label)
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                // 解析成功即刻同步真实值；解析失败保持上一次有效值，不写入 0
                TokenFormat.parse(input)?.let(onValueChange)
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    val wasFocused = focused
                    focused = state.isFocused
                    if (wasFocused && !state.isFocused) {
                        // 失焦：规整显示
                        text = TokenFormat.format(value)
                    }
                },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = fieldColors(),
            placeholder = { Text("128K", fontSize = 12.sp, color = TextMuted) }
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "= ${String.format(java.util.Locale.US, "%,d", value)} tokens",
            color = TextMuted,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================================
// 通用小组件
// ============================================================================

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun FieldHint(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 3.dp)
    )
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(WindowBorderColor)
    )
}

@Composable
private fun StyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 12.sp, color = TextMuted) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = fieldColors(),
        singleLine = true,
        visualTransformation = if (password) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        }
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentIceBlue,
    unfocusedBorderColor = WindowBorderColor,
    focusedContainerColor = DarkBackground,
    unfocusedContainerColor = DarkBackground,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedPlaceholderColor = TextMuted,
    unfocusedPlaceholderColor = TextMuted
)

// ============================================================================
// 全局字体大小
// ============================================================================

/**
 * 全局字体大小滑块。
 *
 * ## 为什么预览文字刻意用 `MaterialTheme.typography.*` 取字号
 * 这块文字会被上层的 `TreeChatTheme(fontScale = ...)` 缩放，
 * 所以用户拖动滑块时能**当场看到效果**，不必关掉设置去别处确认。
 * 若这里写死 `xxx.sp`，预览就不跟随，滑块看起来会像"没生效"。
 *
 * ## 为什么用 `onValueChangeFinished` 而不是 `onValueChange` 落盘
 * `onValueChange` 在拖动过程中每个像素都触发一次，
 * 直接写 SharedPreferences 会在一秒内产生几十次磁盘写入。
 * 拖动时只更新内存状态（界面即时响应），**松手时**才持久化。
 *
 * ## 复位的必要性
 * 用户可能把字拖到很小/很大后发现不合适，若没有复位入口，
 * 又处于小屏设备上，可能连"恢复默认"的按钮都找不到。
 *
 * 边界值来源：`AppSettings.MIN_FONT_SCALE` / `MAX_FONT_SCALE`，
 * 与读取时的钳制逻辑共用同一组常量，避免两处范围不一致。
 */
@Composable
private fun FontScaleSetting(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    onScaleCommit: (Float) -> Unit
) {
    val minScale = AppSettings.MIN_FONT_SCALE
    val maxScale = AppSettings.MAX_FONT_SCALE
    val isDefault = abs(scale - 1f) < 0.005f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "字体大小",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "影响整个应用的文字大小（列表、设置、对话与卡片）",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
            Text(
                text = "${(scale * 100).roundToInt()}%",
                color = AccentNeonGreen,
                // 百分比数字用等宽感更强的 bodyMedium，避免位数变化时左右跳动
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (!isDefault) {
                TextButton(
                    onClick = {
                        onScaleChange(1f)
                        onScaleCommit(1f)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("复位", fontSize = 11.sp, color = AccentIceBlue)
                }
            }
        }

        // 用 Slider 的默认配色：它会自动从主题的 primary 派生
        // 滑轨/滑块/刻度各状态颜色，随明暗主题与预设主题色一起变化。
        Slider(
            value = scale.coerceIn(minScale, maxScale),
            // 拖动中：只更新内存状态，界面即时重排（不写盘）
            onValueChange = { onScaleChange(it) },
            // 松手时：用最新值持久化（避免拖动过程几十次磁盘写入）
            onValueChangeFinished = { onScaleCommit(scale) },
            valueRange = minScale..maxScale,
            // 步长 0.05：从 80% 到 160% 共 17 档，足够细也不会拖起来太累
            steps = 15,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "示例：这是一段用于预览效果的正文文字，拖动滑块可即时对比。",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// ============================================================================
// 素扉风格诗词卡片（仿古籍书扉、宣纸底、朱砂印、金石骨架）
// ============================================================================

@Composable
fun TreeChatSealCard() {
    SuFeiClassicsCard()
}

@Composable
fun SuFeiClassicsCard() {
    // 这里以前是 `isSystemInDarkTheme()` —— 读的是**系统**深色，而不是应用自己的主题设置。
    // 结果：系统深色 + 应用设成亮色时，这张卡片依旧漆黑（用户实测反馈）。
    // 改成完全跟随 MaterialTheme，既跟应用主题走，也符合 M3 配色。
    val scheme = MaterialTheme.colorScheme
    // 素扉古典宣纸纸色：浅色模式为温润熟宣纸色（#F6F3EB），深色模式为古朴玄墨色（#1B201F）
    val paperColor = scheme.surfaceContainerLow
    val paperBorder = scheme.outlineVariant
    val sealRed = Color(0xFFB33928) // 经典朱砂印泥色

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = paperColor,
        border = BorderStroke(1.dp, paperBorder),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶端题签与朱砂印记
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "【 题 签 · 命 名 出 处 】",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 朱砂仿印章小方块
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(sealRed)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "大雅",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 《诗经·大雅》原文引录
            Text(
                text = "「瑟彼玉瓒，黄流在中。\n岂弟君子，福禄攸降。\n榛楛济济，条枚畅畅。」",
                fontSize = 13.5.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Serif,
                textAlign = TextAlign.Center,
                color = scheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 释文与多维思维树之寓意
            Text(
                text = "——《诗经·大雅·文王之什·旱麓》\n\n" +
                    "【释意】条枚畅畅，枝条畅通蔓茂也。\n" +
                    "《毛诗正义》：“言万物盛茂，枝条舒畅。”\n" +
                    "寓意条畅之思维脉络：枝繁叶茂、本固枝荣、多维开窗、畅达无碍。",
                fontSize = 11.sp,
                lineHeight = 16.5.sp,
                fontFamily = FontFamily.Serif,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 开源鸣谢与致敬卡片（Open Source Acknowledgments & Credits）。
 *
 * 规范陈列本项目引用的开源项目、学术语料与设计致敬来源，
 * 严格遵守各上游项目的开源许可证与署名要求，保障在 GitHub 等平台合规开源。
 */
@Composable
fun OpenSourceAcknowledgmentsCard() {
    val scheme = MaterialTheme.colorScheme

    val credits = listOf(
        CreditItem(
            name = "Apache PDFBox (Android)",
            author = "The Apache Software Foundation",
            license = "Apache-2.0",
            description = "条畅扫描工作台用它把拍摄的页面合成为**带文字层**的 PDF —— " +
                "图片作为 XObject 嵌入页面，OCR 识别到的词按坐标以不可见渲染模式写入内容流，" +
                "使产出的扫描件可全文检索、可复制文字、也能直接进 RAG 索引。"
        ),
        CreditItem(
            name = "Google ML Kit Text Recognition",
            author = "Google LLC",
            license = "Apache-2.0",
            description = "端侧 OCR 引擎（简体中文 / 拉丁 / 西里尔），为实况选词与扫描文字层共用同一套识别能力。"
        ),
        CreditItem(
            name = "TokenFlow",
            author = "Most Maturapoj",
            license = "MIT License",
            description = "流式文本的平滑显示（SmoothReveal）：按积压量自适应吐字节奏、" +
                "emoji 不被切断、空闲不请求帧。条畅的对话流式渲染改编自此实现。"
        ),
        CreditItem(
            name = "IconPark",
            author = "ByteDance (字节跳动)",
            license = "Apache-2.0",
            description = "为条畅提供典雅的中式传统与现代交互矢量图标库支持。"
        ),
        CreditItem(
            name = "nk2028 廣韻数据库",
            author = "nk2028 / tshet-uinh-data",
            license = "CC0 1.0 Universal",
            description = "提供 12,722 字中古漢語廣韻音韻地位、小韵反切与声纽完整数据集。"
        ),
        CreditItem(
            name = "rephon 上古拟音库",
            author = "rephon",
            license = "MIT License",
            description = "提供王力、李方桂、郑张尚芳、许思莱四家 14,315 字上古音拟音推导。"
        ),
        CreditItem(
            name = "Princeton WordNet 3.0",
            author = "Princeton University",
            license = "BSD-Style License",
            description = "提供权威英文同义词集 (Synsets) 结构与义项层级分组体系。"
        ),
        CreditItem(
            name = "Etymological WordNet",
            author = "Gerard de Melo",
            license = "CC-BY-SA 3.0",
            description = "提供 38,237 词的跨语种词源来源语族与历史派生关系网络。"
        ),
        CreditItem(
            name = "PDFBox-Android",
            author = "Tom Roush",
            license = "Apache-2.0",
            description = "提供跨平台文档解析、多格式文字流抽取与排版核心支持。"
        ),
        CreditItem(
            name = "WriteBuddy",
            author = "henni99 (GitHub: henni99/WriteBuddy)",
            license = "Apache-2.0",
            description = "为条畅手写笔记与阅读批注提供记忆遮挡胶带 (Tape) 交互与压感处理灵感。"
        ),
        CreditItem(
            name = "DrawBox",
            author = "Akshay Sharma (GitHub: akshay2211/DrawBox)",
            license = "Apache-2.0",
            description = "为 Compose 矢量绘图引擎、平滑贝塞尔插值与撤销重做架构提供参考。"
        ),
        CreditItem(
            name = "Koodo Reader",
            author = "Koodo Reader Community",
            license = "GPL-3.0 (设计致敬)",
            description = "为书架封面网格提取、深色护眼排版与沉浸阅读交互提供设计灵感。"
        ),
        CreditItem(
            name = "Mozilla Readability",
            author = "Mozilla Foundation (GitHub: mozilla/readability)",
            license = "Apache-2.0",
            description = "为条畅内置浏览器提供 Firefox Reader View 核心正文抽取、链接密度打分与精美排版算法。"
        ),
        CreditItem(
            name = "Via 浏览器 & Dark Reader",
            author = "Via Browser Community & Dark Reader (MIT)",
            license = "MIT / 开源致敬",
            description = "为网页智能深色模式色彩反转、媒体插图二次保真与桌面 UA 视口提供算法参考。"
        ),
        CreditItem(
            name = "素扉仿古宣纸卡片",
            author = "《诗经·大雅·旱麓》",
            license = "诗词出处",
            description = "“榛楛济济，条枚畅畅”，作为应用命名的出处，寓意枝条舒畅通达、本固枝荣。"
        )
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "开源鸣谢与致敬 · Acknowledgments",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface
                    )
                    Text(
                        text = "本项目尊重并严格遵守开源协议，感恩开源社区的无私奉献",
                        fontSize = 10.sp,
                        color = scheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            credits.forEach { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = item.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = scheme.secondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 6.dp)
                        ) {
                            Text(
                                text = item.license,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = scheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "作者/出处：${item.author}",
                        fontSize = 10.sp,
                        color = scheme.outline
                    )
                    Text(
                        text = item.description,
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant,
                        lineHeight = 13.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private data class CreditItem(
    val name: String,
    val author: String,
    val license: String,
    val description: String
)

@Composable
private fun EmbeddingProviderSection(settings: AppSettings) {
    var embeddingBaseUrl by remember(settings.cloudRagBaseUrl) { mutableStateOf(settings.cloudRagBaseUrl) }
    var embeddingApiKey by remember(settings.cloudRagApiKey) { mutableStateOf(settings.cloudRagApiKey) }
    var embeddingModel by remember(settings.cloudRagEmbeddingModel) { mutableStateOf(settings.cloudRagEmbeddingModel) }

    Surface(
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, WindowBorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = AccentNeonGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Embedding 向量模型服务商（知识库检索）",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "用于将教材与书籍切片转化为语义向量，支持独立配置向量厂商与模型",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            FieldLabel("Embedding API 地址 (Base URL)")
            StyledTextField(
                value = embeddingBaseUrl,
                onValueChange = {
                    embeddingBaseUrl = it
                    settings.updateCloudRagBaseUrl(it)
                },
                placeholder = "https://api.openai.com/v1"
            )

            FieldLabel("Embedding API 密钥 (API Key)")
            StyledTextField(
                value = embeddingApiKey,
                onValueChange = {
                    embeddingApiKey = it
                    settings.updateCloudRagApiKey(it)
                },
                placeholder = "sk-...",
                password = true
            )

            FieldLabel("Embedding 模型名称 (Model)")
            StyledTextField(
                value = embeddingModel,
                onValueChange = {
                    embeddingModel = it
                    settings.updateCloudRagEmbeddingModel(it)
                },
                placeholder = "text-embedding-3-small / BAAI/bge-m3"
            )
        }
    }
}
