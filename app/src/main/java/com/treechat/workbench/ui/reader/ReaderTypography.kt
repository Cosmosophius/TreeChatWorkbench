package com.treechat.workbench.ui.reader

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.treechat.workbench.data.prefs.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 阅读器正文的护眼底色（降低蓝光 / 降低对比强度的常用纸质色）。
 *
 * 说明：这几个色值是**本工程的排版选择**，不是引自某项研究结论，
 * 故不标注出处；它们只是把背景从纯白/纯黑挪到暖色低对比区间。
 */
enum class EyeCareTheme(val label: String, val description: String) {
    DEFAULT("跟随主题", "不加任何底色，直接用应用当前主题的纸面"),
    PAPER("羊皮纸", "暖米黄，接近纸张观感，白天长时间阅读用"),
    GREEN("豆沙绿", "低饱和绿，把白底亮度压下来，缓解刺眼"),
    NIGHT("夜间减光", "更暗的暖色，夜里把整体亮度压低一档")
}

/**
 * 阅读器排版参数。全部可在阅读器底栏「Aa」里调，调完即时生效并被记住。
 *
 * @param fontScale 正文字号倍率（相对基准 [BASE_FONT_SP]），钳制在 [MIN_FONT_SCALE]~[MAX_FONT_SCALE]。
 * @param lineHeightMultiplier 行距倍率（行高 = 字号 × 该值），钳制在 [MIN_LINE_HEIGHT]~[MAX_LINE_HEIGHT]。
 * @param eyeCare 护眼底色。
 */
data class ReaderTypography(
    val fontScale: Float = 1.0f,
    val lineHeightMultiplier: Float = DEFAULT_LINE_HEIGHT,
    val eyeCare: EyeCareTheme = EyeCareTheme.DEFAULT,
    /**
     * 电脑对开排版（像 Via / 桌面版阅读器对开双页）：
     * 开启后在宽屏或双页模式下按对开双栏排布正文，像纸质书展开一样左右并排。
     */
    val desktopLayout: Boolean = false,
    /**
     * 翻页动画三档（关闭 / 滑页 / 仿真翻页）。
     * 默认仿真翻页：新用户不用进面板就能直接看到效果，不喜欢再关。
     */
    val pageTurn: PageTurnMode = PageTurnMode.CURL
) {

    /** 正文字号（sp）。 */
    val fontSizeSp: Float get() = BASE_FONT_SP * fontScale

    /** 行高（sp）：行高 = 字号 × 行距倍率，字号变大时行距同步放大，不会挤在一起。 */
    val lineHeightSp: Float get() = fontSizeSp * lineHeightMultiplier

    companion object {
        const val BASE_FONT_SP = 16f
        const val MIN_FONT_SCALE = 0.8f
        const val MAX_FONT_SCALE = 1.8f
        const val MIN_LINE_HEIGHT = 1.2f
        const val MAX_LINE_HEIGHT = 2.2f
        const val DEFAULT_LINE_HEIGHT = 1.55f
    }
}

/** 护眼底色解析结果：正文纸面底色 + 其上的文字色。 */
data class EyeCareColors(val paper: Color, val onPaper: Color)

/**
 * 把 [EyeCareTheme] 解析成实际颜色。
 *
 * @param isDark 当前是否深色主题 —— 同一个护眼主题在浅色/深色下给两套色，
 *   否则"豆沙绿"在深色模式下会变成一块刺眼的亮斑。
 * @return `null` 表示不干预（[EyeCareTheme.DEFAULT]，交回给 M3 主题自身的纸面色）。
 */
fun EyeCareTheme.resolve(isDark: Boolean): EyeCareColors? = when (this) {
    EyeCareTheme.DEFAULT -> null
    EyeCareTheme.PAPER -> if (isDark) {
        EyeCareColors(Color(0xFF26201A), Color(0xFFE6DCC8))
    } else {
        EyeCareColors(Color(0xFFF6F0E2), Color(0xFF3A3226))
    }
    EyeCareTheme.GREEN -> if (isDark) {
        EyeCareColors(Color(0xFF1C2A1F), Color(0xFFD5E3D1))
    } else {
        EyeCareColors(Color(0xFFCCE8CF), Color(0xFF22331F))
    }
    EyeCareTheme.NIGHT -> if (isDark) {
        EyeCareColors(Color(0xFF100E0C), Color(0xFFC9C0B0))
    } else {
        EyeCareColors(Color(0xFFE9E3D8), Color(0xFF4A4238))
    }
}

/**
 * 阅读器排版设置的中心状态（进程内单例 + SharedPreferences 持久化）。
 *
 * 为什么用单例 StateFlow 而不是 CompositionLocal：
 * 正文渲染分散在 `AdaptiveDocumentView` / `LiveTextPageView` 等多个深层
 * Composable 里，且都需要**即时响应**设置面板的改动。用 StateFlow 让任何
 * 读取方 `collectAsState()` 后自动重组，不必层层传参、也不必改动这些组件的
 * 参数表（对既有代码的侵入最小）。
 *
 * 持久化只写 5 个**新**的 SharedPreferences 键，不动任何既有键，
 * 也不新增 Room 列/表/迁移。
 */
object ReaderTypographyStore {

    private const val KEY_FONT_SCALE = "reader_font_scale"
    private const val KEY_LINE_HEIGHT = "reader_line_height_mult"
    private const val KEY_EYE_CARE = "reader_eye_care"
    private const val KEY_DESKTOP_LAYOUT = "reader_desktop_layout"

    /** 翻页动画档位（[PageTurnMode] 的 name），键名风格与 `reader_font_scale` 一致。 */
    private const val KEY_PAGE_TURN = "reader_page_turn"

    private val _state = MutableStateFlow(ReaderTypography())
    val state: StateFlow<ReaderTypography> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    /** 进程内读一次即可；重复调用是安全的空操作。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _state.value = read(context)
    }

    fun update(context: Context, typography: ReaderTypography) {
        val sanitized = typography.copy(
            fontScale = typography.fontScale.coerceIn(
                ReaderTypography.MIN_FONT_SCALE,
                ReaderTypography.MAX_FONT_SCALE
            ),
            lineHeightMultiplier = typography.lineHeightMultiplier.coerceIn(
                ReaderTypography.MIN_LINE_HEIGHT,
                ReaderTypography.MAX_LINE_HEIGHT
            )
        )
        _state.value = sanitized
        write(context, sanitized)
    }

    fun reset(context: Context) = update(context, ReaderTypography())

    private fun prefs(context: Context) =
        context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): ReaderTypography {
        val sp = runCatching { prefs(context) }.getOrNull() ?: return ReaderTypography()
        val scale = sp.getFloat(KEY_FONT_SCALE, 1.0f)
            .coerceIn(ReaderTypography.MIN_FONT_SCALE, ReaderTypography.MAX_FONT_SCALE)
        val lineHeight = sp.getFloat(KEY_LINE_HEIGHT, ReaderTypography.DEFAULT_LINE_HEIGHT)
            .coerceIn(ReaderTypography.MIN_LINE_HEIGHT, ReaderTypography.MAX_LINE_HEIGHT)
        val eyeCare = runCatching {
            EyeCareTheme.valueOf(sp.getString(KEY_EYE_CARE, null) ?: "")
        }.getOrDefault(EyeCareTheme.DEFAULT)
        val desktop = sp.getBoolean(KEY_DESKTOP_LAYOUT, false)
        // 翻页动画读不到/读坏了就落回"仿真翻页"：宁可多放一次动画，也别给一个空档位。
        val pageTurn = runCatching {
            PageTurnMode.valueOf(sp.getString(KEY_PAGE_TURN, null) ?: "")
        }.getOrDefault(PageTurnMode.CURL)
        return ReaderTypography(scale, lineHeight, eyeCare, desktop, pageTurn)
    }

    private fun write(context: Context, t: ReaderTypography) {
        runCatching {
            prefs(context).edit()
                .putFloat(KEY_FONT_SCALE, t.fontScale)
                .putFloat(KEY_LINE_HEIGHT, t.lineHeightMultiplier)
                .putString(KEY_EYE_CARE, t.eyeCare.name)
                .putBoolean(KEY_DESKTOP_LAYOUT, t.desktopLayout)
                .putString(KEY_PAGE_TURN, t.pageTurn.name)
                .apply()
        }
    }
}

/** 读取当前阅读器排版设置（可组合内使用，改动会自动触发重组）。 */
@Composable
fun rememberReaderTypography(): ReaderTypography {
    val value by ReaderTypographyStore.state.collectAsState()
    return value
}
