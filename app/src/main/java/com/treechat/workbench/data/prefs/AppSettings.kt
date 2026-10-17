package com.treechat.workbench.data.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.treechat.workbench.network.search.SearchBackend
import com.treechat.workbench.network.search.SearchConfig
import com.treechat.workbench.ui.selection.SelectionScheme
import com.treechat.workbench.ui.theme.ThemeMode
import com.treechat.workbench.ui.theme.ThemePalette

/**
 * 应用级设置。
 *
 * 全部用 mutableStateOf 承载：这些值会直接影响重组，
 * 只写 SharedPreferences 而不放进 Compose 状态的话，
 * 用户改完设置会"没反应"，要重启才生效。
 */
class AppSettings(private val context: Context) {

    // ---------------------------------------------------------------- 外观

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    var dynamicColor by mutableStateOf(true)
        private set

    /** 预设主题色；仅在关闭壁纸取色时生效。 */
    var themePalette by mutableStateOf(ThemePalette.DEFAULT)
        private set

    /**
     * 全局字体缩放（1.0 = 系统默认大小）。
     *
     * ## 为什么放在"外观"而不是"对话行为"
     * 它影响的是整个应用的文字（列表、设置、对话、思维导图卡片），
     * 不只是聊天区，所以属于外观设置。
     *
     * ## 取值范围为什么是 0.8 ~ 1.6
     * 下限 0.8：再小中文就难辨认；
     * 上限 1.6：再大长段落会频繁折行、卡片里的标题会挤爆。
     * 读取时**一律经过 [clampFontScale] 钳制** —— 配置文件可能被手工改过，
     * 也可能来自将来调整过范围的版本；不钳制会让界面直接不可用
     * （字大到看不全，用户又进不去设置改回来）。
     */
    var fontScale by mutableStateOf(1f)

    /**
     * 粗体呈现：颜色 / 底色 / 字形，三个维度独立可调。
     *
     * 动机：中文里 `FontWeight.Bold` 的实际差异很小（多数中文字体没有真 Bold 字重），
     * 模型输出里标粗的重点在长段落中经常"看不见"。这三项让用户能按自己的阅读场景
     * 把重点做强 —— 只改默认仍是原样，不影响现有观感。
     */
    var boldColorStyle by mutableStateOf(BoldColorStyle.DEFAULT)
    var boldHighlightColor by mutableStateOf(BoldHighlightColor.NONE)
    var boldFontStyle by mutableStateOf(BoldFontStyle.DEFAULT)
        private set

    /**
     * 界面语言。
     * 可选：zh-CN (简体汉语)、zh-TW (繁体汉语)、en (English)、ru (Русский)。
     */
    var appLanguage by mutableStateOf("zh-CN")
        private set

    // ------------------------------------------------------------ 对话行为

    /** 自定义系统提示词；为空时使用内置默认。 */
    var systemPrompt by mutableStateOf("")
        private set

    /**
     * 是否启用本地教材检索（RAG）。
     *
     * 默认**开启**：用户导入教材的目的就是让它参与回答。
     * 但必须提供开关 —— 检索到的原文会占 prompt token，
     * 有时用户只想普通聊天，或发现某本教材拖累了回答质量。
     * 没有教材文档时本开关不起作用（检索结果为空，不会拼任何内容）。
     */
    var knowledgeEnabled by mutableStateOf(true)
        private set

    /**
     * 是否显示"文本选择诊断"信息。
     *
     * ## 为什么需要这个开关
     * "长按没反应"有四种表现完全一样但修法完全不同的成因
     * （事件没到达 / 长按判定未通过 / 命中为空 / 选中了但没渲染）。
     * 把判定结果直接显示在界面上，用户截图即可让我定位，
     * 不必接 adb、也不必反复"改了再猜"。
     *
     * 默认**关闭**：正常使用不该看到这些内部信息。
     * 排查完成后应关掉。
     */
    var selectionDiagnostics by mutableStateOf(false)
        private set

    /**
     * 文本选择的交互方案。
     *
     * 默认 [SelectionScheme.TOUCH_LONG_PRESS] —— **保持项目原有行为**，
     * 因为方案是"按输入设备分流"的，默认值不该改变任何老用户的手感。
     *
     * 为什么必须可选：触屏上长按选句最自然，而鼠标/触控板用户期望的是
     * "按下拖动即选"。两者不能同时用一种手势判据实现，所以做成显式方案。
     */
    var selectionScheme by mutableStateOf(SelectionScheme.TOUCH_LONG_PRESS)
        private set

    /**
     * 全局输入设备模式（自动检测 / 强制键鼠 / 强制触控）。
     *
     * 为什么需要它：选词手感是否"桌面级"完全取决于输入设备，而自动检测在
     * 部分 ROM 上拿不到可靠的指针类型（触控板常被报成 Touch）。用户要求
     * 有一个开关能强制指定，避免"插着鼠标却仍是长按选词"。
     */
    var inputMode by mutableStateOf(InputMode.AUTO)
        private set

    fun updateInputMode(mode: InputMode) {
        inputMode = mode
        write { putString(KEY_INPUT_MODE, mode.name) }
    }

    /**
     * 思维导图画布上的**滚轮语义**（滚轮缩放 / 滚轮平移两套方案对调）。
     *
     * 为什么做成可切换：两类用户对"滚轮"的肌肉记忆是冲突的 ——
     *   · 看思维导图/地图的人期望**滚轮 = 缩放**（Ctrl+滚轮才平移），
     *     这也是 macOS 触控板与 Figma/Miro 一类白板工具的默认；
     *   · 浏览长文档的人期望**滚轮 = 上下滚动**（Ctrl+滚轮才缩放），
     *     这是浏览器与 PDF 阅读器的默认。
     * 项目里两种习惯都有人用（本 App 同时有阅读器与画布），
     * 因此给一个显式开关，并把默认值定为**滚轮缩放**
     * （见 [GraphWheelMode.WHEEL_ZOOM] 的说明）。
     */
    var graphWheelMode by mutableStateOf(GraphWheelMode.WHEEL_ZOOM)
        private set

    fun updateGraphWheelMode(mode: GraphWheelMode) {
        graphWheelMode = mode
        write { putString(KEY_GRAPH_WHEEL_MODE, mode.name) }
    }

    /** 一键对调两套滚轮方案（画布右下角那个切换按钮走这里）。 */
    fun toggleGraphWheelMode() {
        updateGraphWheelMode(
            if (graphWheelMode == GraphWheelMode.WHEEL_ZOOM) {
                GraphWheelMode.WHEEL_PAN
            } else {
                GraphWheelMode.WHEEL_ZOOM
            }
        )
    }

    /**
     * 安卓外接键鼠适配：回车键发送消息。
     * 开启后按 Enter 发送消息，Shift+Enter 或 Ctrl+Enter 换行；关闭后 Enter 为普通换行。
     * 默认开启（符合桌面键鼠与外接键盘直觉）。
     */
    var enterToSend by mutableStateOf(true)
        private set

    // ------------------------------------------------------------ 联网搜索

    var searchEnabled by mutableStateOf(false)
        private set

    var searchBackend by mutableStateOf(SearchBackend.NATIVE)
        private set

    var searchApiKey by mutableStateOf("")
        private set

    var searxngBaseUrl by mutableStateOf("")
        private set

    var searchMaxResults by mutableStateOf(5)
        private set

    // ------------------------------------------------------------ 云端 RAG API 双方案配置

    var cloudRagEnabled by mutableStateOf(false)
        private set

    var ragIndexingScheme by mutableStateOf(com.treechat.workbench.rag.RagIndexingScheme.LOCAL_OFFLINE)
        private set

    var cloudRagBaseUrl by mutableStateOf("https://api.openai.com/v1")
        private set

    var cloudRagApiKey by mutableStateOf("")
        private set

    var cloudRagEmbeddingModel by mutableStateOf("text-embedding-3-small")
        private set

    init {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        themeMode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        themePalette = ThemePalette.fromName(prefs.getString(KEY_THEME_PALETTE, null))
        fontScale = clampFontScale(prefs.getFloat(KEY_FONT_SCALE, 1f))
        boldColorStyle = BoldColorStyle.fromName(prefs.getString(KEY_BOLD_COLOR, null))
        boldHighlightColor = BoldHighlightColor.fromName(prefs.getString(KEY_BOLD_HL, null))
        boldFontStyle = BoldFontStyle.fromName(prefs.getString(KEY_BOLD_FONT, null))
        systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty()
        knowledgeEnabled = prefs.getBoolean(KEY_KNOWLEDGE_ENABLED, true)
        selectionDiagnostics = prefs.getBoolean(KEY_SELECTION_DIAGNOSTICS, false)
        selectionScheme = runCatching {
            SelectionScheme.valueOf(prefs.getString(KEY_SELECTION_SCHEME, SelectionScheme.TOUCH_LONG_PRESS.name)!!)
        }.getOrDefault(SelectionScheme.TOUCH_LONG_PRESS)
        inputMode = runCatching {
            InputMode.valueOf(prefs.getString(KEY_INPUT_MODE, InputMode.AUTO.name)!!)
        }.getOrDefault(InputMode.AUTO)
        graphWheelMode = runCatching {
            GraphWheelMode.valueOf(
                prefs.getString(KEY_GRAPH_WHEEL_MODE, GraphWheelMode.WHEEL_ZOOM.name)!!
            )
        }.getOrDefault(GraphWheelMode.WHEEL_ZOOM)
        enterToSend = prefs.getBoolean(KEY_ENTER_TO_SEND, true)
        searchEnabled = prefs.getBoolean(KEY_SEARCH_ENABLED, false)
        searchBackend = runCatching {
            SearchBackend.valueOf(prefs.getString(KEY_SEARCH_BACKEND, SearchBackend.NATIVE.name)!!)
        }.getOrDefault(SearchBackend.NATIVE)
        searchApiKey = prefs.getString(KEY_SEARCH_API_KEY, "").orEmpty()
        searxngBaseUrl = prefs.getString(KEY_SEARXNG_URL, "").orEmpty()
        searchMaxResults = prefs.getInt(KEY_SEARCH_MAX_RESULTS, 5)

        cloudRagEnabled = prefs.getBoolean(KEY_CLOUD_RAG_ENABLED, false)
        ragIndexingScheme = runCatching {
            com.treechat.workbench.rag.RagIndexingScheme.valueOf(
                prefs.getString(KEY_RAG_INDEXING_SCHEME, com.treechat.workbench.rag.RagIndexingScheme.LOCAL_OFFLINE.name)!!
            )
        }.getOrDefault(com.treechat.workbench.rag.RagIndexingScheme.LOCAL_OFFLINE)
        cloudRagBaseUrl = prefs.getString(KEY_CLOUD_RAG_BASE_URL, "https://api.openai.com/v1").orEmpty().ifBlank { "https://api.openai.com/v1" }
        cloudRagApiKey = prefs.getString(KEY_CLOUD_RAG_API_KEY, "").orEmpty()
        cloudRagEmbeddingModel = prefs.getString(KEY_CLOUD_RAG_EMBEDDING_MODEL, "text-embedding-3-small").orEmpty().ifBlank { "text-embedding-3-small" }
        appLanguage = prefs.getString(KEY_APP_LANGUAGE, "zh-CN").orEmpty().ifBlank { "zh-CN" }
    }

    fun updateAppLanguage(lang: String) {
        appLanguage = lang
        write { putString(KEY_APP_LANGUAGE, lang) }
    }

    /**
     * 改名注意：不能叫 setXxx —— Kotlin 会为 `var xxx` 生成同名的 JVM setter，
     * 签名碰撞会触发 Platform declaration clash。
     */
    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        write { putString(KEY_THEME_MODE, mode.name) }
    }

    fun updateDynamicColor(enabled: Boolean) {
        dynamicColor = enabled
        write { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
    }

    /** 更新粗体呈现的三个维度。 */
    fun updateBoldStyle(
        color: BoldColorStyle = boldColorStyle,
        highlight: BoldHighlightColor = boldHighlightColor,
        font: BoldFontStyle = boldFontStyle
    ) {
        boldColorStyle = color
        boldHighlightColor = highlight
        boldFontStyle = font
        write {
            putString(KEY_BOLD_COLOR, color.name)
            putString(KEY_BOLD_HL, highlight.name)
            putString(KEY_BOLD_FONT, font.name)
        }
    }

    fun updateThemePalette(palette: ThemePalette) {
        themePalette = palette
        write { putString(KEY_THEME_PALETTE, palette.name) }
    }

    /**
     * 更新全局字体缩放。
     *
     * 注意方法名不能叫 `setFontScale`：Kotlin 会为 `var fontScale` 生成同名 JVM setter，
     * 签名碰撞会触发 Platform declaration clash（本项目已有先例，见类文档说明）。
     */
    fun updateFontScale(scale: Float) {
        val safe = clampFontScale(scale)
        fontScale = safe
        write { putFloat(KEY_FONT_SCALE, safe) }
    }

    fun updateSystemPrompt(prompt: String) {
        systemPrompt = prompt
        write { putString(KEY_SYSTEM_PROMPT, prompt) }
    }

    fun updateKnowledgeEnabled(enabled: Boolean) {
        knowledgeEnabled = enabled
        write { putBoolean(KEY_KNOWLEDGE_ENABLED, enabled) }
    }

    fun updateSelectionDiagnostics(enabled: Boolean) {
        selectionDiagnostics = enabled
        write { putBoolean(KEY_SELECTION_DIAGNOSTICS, enabled) }
    }

    fun updateSelectionScheme(scheme: SelectionScheme) {
        selectionScheme = scheme
        write { putString(KEY_SELECTION_SCHEME, scheme.name) }
    }

    fun updateEnterToSend(enabled: Boolean) {
        enterToSend = enabled
        write { putBoolean(KEY_ENTER_TO_SEND, enabled) }
    }

    fun updateSearchEnabled(enabled: Boolean) {
        searchEnabled = enabled
        write { putBoolean(KEY_SEARCH_ENABLED, enabled) }
    }

    fun updateSearchBackend(backend: SearchBackend) {
        searchBackend = backend
        write { putString(KEY_SEARCH_BACKEND, backend.name) }
    }

    fun updateSearchApiKey(key: String) {
        searchApiKey = key
        write { putString(KEY_SEARCH_API_KEY, key) }
    }

    fun updateSearxngBaseUrl(url: String) {
        searxngBaseUrl = url
        write { putString(KEY_SEARXNG_URL, url) }
    }

    fun updateSearchMaxResults(count: Int) {
        searchMaxResults = count.coerceIn(1, 10)
        write { putInt(KEY_SEARCH_MAX_RESULTS, searchMaxResults) }
    }

    fun updateCloudRagEnabled(enabled: Boolean) {
        cloudRagEnabled = enabled
        write { putBoolean(KEY_CLOUD_RAG_ENABLED, enabled) }
    }

    fun updateRagIndexingScheme(scheme: com.treechat.workbench.rag.RagIndexingScheme) {
        ragIndexingScheme = scheme
        write { putString(KEY_RAG_INDEXING_SCHEME, scheme.name) }
    }

    fun updateCloudRagBaseUrl(url: String) {
        cloudRagBaseUrl = url
        write { putString(KEY_CLOUD_RAG_BASE_URL, url) }
    }

    fun updateCloudRagApiKey(key: String) {
        cloudRagApiKey = key
        write { putString(KEY_CLOUD_RAG_API_KEY, key) }
    }

    fun updateCloudRagEmbeddingModel(model: String) {
        cloudRagEmbeddingModel = model
        write { putString(KEY_CLOUD_RAG_EMBEDDING_MODEL, model) }
    }

    fun cloudRagConfig(): com.treechat.workbench.rag.CloudRagConfig = com.treechat.workbench.rag.CloudRagConfig(
        enabled = cloudRagEnabled,
        scheme = ragIndexingScheme,
        baseUrl = cloudRagBaseUrl,
        apiKey = cloudRagApiKey,
        embeddingModel = cloudRagEmbeddingModel
    )

    /** 组装给网络层用的只读配置。 */
    fun searchConfig(): SearchConfig = SearchConfig(
        enabled = searchEnabled,
        backend = searchBackend,
        apiKey = searchApiKey,
        searxngBaseUrl = searxngBaseUrl,
        maxResults = searchMaxResults
    )

    /**
     * 判断当前搜索配置是否可用。
     * 返回 null 表示可用；否则返回不可用的原因，用于在界面上直接提示。
     */
    fun searchConfigIssue(): String? = when {
        !searchEnabled -> null
        searchBackend.requiresApiKey && searchApiKey.isBlank() ->
            "已开启联网搜索，但未填写 ${searchBackend.displayName} 的 API Key"
        searchBackend.requiresBaseUrl && searxngBaseUrl.isBlank() ->
            "已开启联网搜索，但未填写 SearXNG 实例地址"
        else -> null
    }

    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply(block).apply()
    }

    companion object {
        const val PREFS = "treechat_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_THEME_PALETTE = "theme_palette"
        private const val KEY_BOLD_COLOR = "bold_color_style"
        private const val KEY_BOLD_HL = "bold_highlight_color"
        private const val KEY_BOLD_FONT = "bold_font_style"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_KNOWLEDGE_ENABLED = "knowledge_enabled"
        private const val KEY_SELECTION_DIAGNOSTICS = "selection_diagnostics"
        private const val KEY_SELECTION_SCHEME = "selection_scheme"
        private const val KEY_INPUT_MODE = "input_mode"
        private const val KEY_GRAPH_WHEEL_MODE = "graph_wheel_mode"
        private const val KEY_ENTER_TO_SEND = "enter_to_send"
        private const val KEY_SEARCH_ENABLED = "search_enabled"
        private const val KEY_SEARCH_BACKEND = "search_backend"
        private const val KEY_SEARCH_API_KEY = "search_api_key"
        private const val KEY_SEARXNG_URL = "searxng_url"
        private const val KEY_SEARCH_MAX_RESULTS = "search_max_results"
        private const val KEY_CLOUD_RAG_ENABLED = "cloud_rag_enabled"
        private const val KEY_RAG_INDEXING_SCHEME = "rag_indexing_scheme"
        private const val KEY_CLOUD_RAG_BASE_URL = "cloud_rag_base_url"
        private const val KEY_CLOUD_RAG_API_KEY = "cloud_rag_api_key"
        private const val KEY_CLOUD_RAG_EMBEDDING_MODEL = "cloud_rag_embedding_model"
        private const val KEY_APP_LANGUAGE = "app_language"

        /** 字体缩放下限：再小汉字就难辨认了。 */
        const val MIN_FONT_SCALE = 0.8f

        /** 字体缩放上限：再大长段落会频繁折行、卡片标题会挤爆。 */
        const val MAX_FONT_SCALE = 1.6f

        /**
         * 把任意输入钳制到合法范围。
         *
         * 必须容错 NaN：`NaN.coerceIn(a, b)` 返回 NaN（coerceIn 的比较对 NaN 全为 false，
         * 会原样返回），NaN 上的 `sp` 会让文字直接不显示 —— 界面上表现为"字全没了"，
         * 而用户此时又进不去设置改回来。所以这里显式挡掉。
         */
        fun clampFontScale(value: Float): Float {
            if (value.isNaN()) return 1f
            return value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        }

        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个多维思维树 AI 助手。请使用结构清晰的 Markdown 回答，" +
                "合理使用标题、列表与代码块，避免空泛的套话。"
    }
}

/**
 * 全局输入设备模式。
 *
 * 为什么必须可强制：选词手势在"触屏"与"鼠标"下是两套判据（长按 vs 按下即拖）。
 * 自动检测依赖系统上报的指针类型，而在部分 ROM / 远程桌面 / 投屏环境下，
 * 触控板或鼠标会被错报成 Touch，于是"插着鼠标却还是长按选词"。
 * 用户要求提供一个全局开关：自动检测 / 强制键鼠 / 强制触控。
 */
enum class InputMode(val displayName: String, val description: String) {
    /** 自动检测：检测到键鼠即用桌面级（与内置浏览器同一套手感），触屏用选定的触屏方案。 */
    AUTO(
        "自动检测（推荐）",
        "检测到鼠标或触控板即切换为桌面级选词：工字光标、按下即拖出选区、双击选词、三击选句，" +
            "不显示水滴手柄（与内置浏览器的选词手感一致）。触屏则使用下面选定的触屏方案。" +
            "若没检测出来（个别设备会把手写板报成触摸），用下面的强制项自己指定。"
    ),

    /** 强制键鼠：不论什么设备，一律按桌面级处理。 */
    FORCE_DESKTOP(
        "强制键鼠操作",
        "不论接的是什么设备，一律按桌面级处理：工字光标、按下拖出选区、双击选词、三击选句、无水滴手柄。" +
            "检测不准时用它。"
    ),

    /** 强制触控：不论什么设备，一律按触屏方案处理（兼容老手机习惯）。 */
    FORCE_TOUCH(
        "强制触控操作",
        "不论接的是什么设备，一律按触屏处理：使用下面选定的触屏选词方案（长按选句等），显示水滴手柄。"
    ),

    /**
     * 强制触控板：桌面级选词，但**纵向双指滑动永远视为滚动**。
     *
     * 为什么需要单列一档：触控板既不是鼠标也不是触屏 ——
     *   · 取词手感与鼠标一致（按下拖选、双击选词、三击选句、无水滴手柄）；
     *   · 但双指上下滑是它最常用的**滚动**手势，绝不能被判成拖选。
     * 系统在 Android 上把触控板双指滚动合成成"一个鼠标指针的滚轮事件"，
     * 与真鼠标的拖选在事件类型上**区分不开**（都是 PointerType.Mouse），
     * 因此"自动检测"无法把它们分开，只能由用户显式声明"我在用触控板"。
     */
    FORCE_TRACKPAD(
        "强制触控板操作",
        "按桌面级选词处理（按下拖选、双击选词、三击选句、无水滴手柄），" +
            "但**纵向双指滑动一律当作滚动**，不会误选成文字。" +
            "触控板双指滚动在系统里与鼠标拖动难以区分时用这一档。"
    ),

    /**
     * 强制触控板鼠标：桌面级指针 + **气泡（水滴手柄）滑动选词**。
     *
     * 与 [FORCE_TRACKPAD] 的唯一区别：**保留水滴手柄与选区气泡**。
     *   · [FORCE_TRACKPAD] / [FORCE_DESKTOP] 是"浏览器式"桌面手感 ——
     *     按下直接拖出选区、松手定格，全程不画手柄（桌面系统本就没有）；
     *   · 本档则是"触控板鼠标 + 手柄"的混合：同样按下即拖、无需等待长按，
     *     但选词过程中**始终有水滴手柄可拨**，松手后还能抓住手柄微调边界 ——
     *     这正是用户在手机上熟悉的"气泡滑动选词"手感，只是触发改成了即时拖动。
     *
     * 为什么单列一档：手柄在真鼠标上是纯干扰（用户要的是浏览器手感），
     * 而用触控板/无线鼠标的用户里有一批恰恰希望**边拖边看到手柄**，
     * 好做精确的边界微调。两种偏好互斥，只能由用户显式选择。
     *
     * 纵向双指滑动同样一律当作滚动（与 [FORCE_TRACKPAD] 一致），
     * 因为这一档的使用场景仍是触控板。
     */
    FORCE_TRACKPAD_BUBBLE(
        "强制触控板鼠标（气泡滑动选词）",
        "桌面级指针：显示工字光标、按下即拖、无需等待长按；" +
            "但选词全程保留水滴手柄与选区气泡，松手后可抓手柄微调边界 —— " +
            "即「气泡滑动选词」手感。纵向双指滑动仍一律当作滚动。"
    );

    companion object {
        fun fromName(name: String?): InputMode =
            runCatching { valueOf(name!!) }.getOrDefault(AUTO)
    }
}

/**
 * 思维导图画布上「滚轮」的语义。
 *
 * 两套方案是**对调关系**（滚轮做什么，Ctrl+滚轮就做另一件），
 * 因此枚举只有两个值，切换即对调 —— 不做"三轮"那种三态设计。
 *
 * 为什么默认是 [WHEEL_ZOOM]：本画布是**思维导图**（空间化浏览），
 * 不是长文档。在思维导图/白板/地图这类界面里，滚轮=缩放是主流约定
 * （Figma、Miro、macOS 触控板、各类地图应用），而滚轮=平移是
 * **纵向长文档**的约定。用户此前反馈"滚轮在画布里变成了上下滚动"，
 * 正是这两套约定冲突的表现。默认给画布场景的那一套，需要文档式手感的人
 * 用切换按钮换到 [WHEEL_PAN] 即可（选择会被记住）。
 */
enum class GraphWheelMode(val displayName: String, val description: String) {
    /** 滚轮 = 缩放（以光标为中心），Ctrl+滚轮 = 上下平移。 */
    WHEEL_ZOOM(
        "滚轮缩放（导图习惯）",
        "滚轮直接缩放画布（以光标位置为中心），按住 Ctrl 再滚轮则上下平移。"
    ),

    /** 滚轮 = 上下平移，Ctrl+滚轮 = 缩放。 */
    WHEEL_PAN(
        "滚轮平移（文档习惯）",
        "滚轮上下平移画布，按住 Ctrl 再滚轮则缩放。与浏览器/PDF 阅读器一致。"
    );

    companion object {
        fun fromName(name: String?): GraphWheelMode =
            runCatching { valueOf(name!!) }.getOrDefault(WHEEL_ZOOM)
    }
}


/**
 * 粗体的**颜色**呈现方式。
 *
 * 为什么要有它：`fontWeight = Bold` 在很多中文字体上**几乎看不出差别**
 * （系统里的中文字体大多没有真正的 Bold 字重，合成加粗很弱），
 * 于是模型输出的重点在正文里"消失了"。这里把颜色作为一个独立维度交给用户。
 */
enum class BoldColorStyle(val displayName: String, val description: String) {
    /** 保持原样：只加粗字重，不改颜色。 */
    DEFAULT(
        "不加粗颜色（仅字重）",
        "粗体只提高字重，不改变颜色。与多数编辑器的默认行为一致。"
    ),

    /** 用主色上色，与链接色刻意区分开。 */
    ACCENT(
        "主题强调色",
        "粗体文字用主题强调色显示。醒目且跟随明暗主题自动适配。"
    ),

    /** 铺一层淡底衬，最显眼。 */
    HIGHLIGHT(
        "高亮底色",
        "粗体文字铺一层淡色底衬（类荧光笔），在长段落里最容易一眼找到。"
    ),

    /** 用暖色系（橙红）上色。 */
    WARM(
        "暖色强调",
        "粗体文字用橙红色显示，在黑白正文中对比最强，适合需要快速定位重点。"
    );

    companion object {
        fun fromName(name: String?): BoldColorStyle =
            runCatching { valueOf(name!!) }.getOrDefault(DEFAULT)
    }
}

/** 粗体可选的**高亮底色**。 */
enum class BoldHighlightColor(val displayName: String, val argb: Long) {
    /** 不用底色。 */
    NONE("不铺底色", 0L),
    YELLOW("鹅黄", 0xFFFFF3B0),
    GREEN("浅绿", 0xFFC8E6C9),
    BLUE("天蓝", 0xFFBBDEFB),
    PURPLE("淡紫", 0xFFE1BEE7),
    PINK("浅粉", 0xFFF8BBD0);

    companion object {
        fun fromName(name: String?): BoldHighlightColor =
            runCatching { valueOf(name!!) }.getOrDefault(NONE)
    }
}

/**
 * 粗体的**字形**。
 *
 * 换成楷体/仿宋这类有明显笔画差异的中文字体，粗体的辨识度远高于加粗字重 ——
 * 这是"让重点看得出来"最省事也最对症的办法。
 */
enum class BoldFontStyle(val displayName: String, val description: String) {
    /** 系统默认字体 + Bold 字重。 */
    DEFAULT("系统默认（加粗）", "保持系统默认字体，只提高字重。"),

    /** 黑体：无衬线，中文里字形差异最明显。 */

    /** 宋体：衬线，最接近印刷正文。 */
    SERIF(
        "宋体",
        "衬线宋体。最正式的印刷字形，配合颜色与底色叠加时层次清楚。"
    ),

    /** 楷体：需要系统装有「楷体/KaiTi」字族才生效，缺失时静默回退到默认。 */
    KAI(
        "楷体",
        "楷体（KaiTi/STKaiti）。系统需装有该字族；缺失时自动回退默认字体。"
    ),

    /** 仿宋：需要系统装有「仿宋/FangSong」字族。 */
    FANGSONG(
        "仿宋",
        "仿宋（FangSong/STFangsong）。横细竖粗，接近印刷排版；系统需装有该字族。"
    ),

    /** 黑体加重：无衬线。 */
    HEI(
        "黑体",
        "黑体（无衬线）。中文里笔画粗细差异最大，重点最醒目。"
    );
    companion object {
        fun fromName(name: String?): BoldFontStyle =
            runCatching { valueOf(name!!) }.getOrDefault(DEFAULT)
    }
}
