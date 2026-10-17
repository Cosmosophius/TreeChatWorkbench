package com.treechat.workbench.ui.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.treechat.workbench.data.prefs.AppSettings

/**
 * 阅读器「实况选词」的选词方式。
 *
 * ## 为什么要单独一套，而不是直接复用 [com.treechat.workbench.ui.selection.SelectionScheme]
 * 那套方案是给**文字层**（SelectableMarkdown / 消息卡片里的 Text）用的，
 * 判据落在 `awaitLongPressOrCancellation` + 自绘手柄上；而实况选词的底层是
 * **一张位图上的 OCR 词盒**，没有 TextLayoutResult 可以命中，两者的手势入口
 * 不是一回事。所以这里给实况选词单独一套可选方式，并且**默认跟随**用户
 * 在「设置 → 文本选择方式」里已经选过的方案（见 [LiveSelectionPrefs]）。
 *
 * 纯 Compose + Material 3，无 emoji。
 */
enum class LiveSelectionMethod(
    /** 下拉菜单里的整行标题。 */
    val displayName: String,
    /** 底栏按钮上的短标签（三个字以内，否则底栏会挤）。 */
    val shortLabel: String,
    /** 下拉菜单里的说明。 */
    val description: String
) {

    /**
     * 条畅选词（默认）：对齐条畅对话界面的选词手感。
     * 支持水滴手柄自由拖拉长按、支持多词整句、键鼠自动工字竖条光标。
     */
    TALKTREE(
        "条畅选词（手柄选字）",
        "条畅",
        "对齐条畅对话界面的选词手感：水滴手柄自由拖拉长按、支持多词整句、键鼠自动工字竖条光标。"
    ),

    /**
     * 实况框选（自绘）：类似电脑选文件一样的框选器。
     * 在扫描页图像上长按拖出矩形，与矩形相交的 OCR 词会被快速选中，支持边看原图边快速圈选。
     */
    BOX_DRAG(
        "电脑框选（自绘）",
        "框选",
        "长按进入框选再拖出矩形；类似电脑选文件一样的框选器，支持边看原图边快速圈选。"
    );

    companion object {
        /** 从持久化字符串还原，容错旧配置/脏数据并默认退回条畅选词。 */
        fun fromName(name: String?): LiveSelectionMethod = when (name) {
            "BOX_DRAG" -> BOX_DRAG
            "TALKTREE", "SYSTEM_NATIVE" -> TALKTREE
            else -> TALKTREE
        }
    }
}

/**
 * 实况选词方式的本机记忆（默认使用条畅选词）。
 */
class LiveSelectionPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)

    var method by mutableStateOf(seed())
        private set

    fun update(value: LiveSelectionMethod) {
        method = value
        prefs.edit().putString(KEY_LIVE_SELECTION_METHOD, value.name).apply()
    }

    private fun seed(): LiveSelectionMethod {
        val saved = prefs.getString(KEY_LIVE_SELECTION_METHOD, null)
        if (saved != null) return LiveSelectionMethod.fromName(saved)
        // 默认情况下使用条畅选词
        return LiveSelectionMethod.TALKTREE
    }

    private companion object {
        const val KEY_LIVE_SELECTION_METHOD = "live_text_selection_method"
    }
}
