package com.treechat.workbench.ui.selection

/**
 * 文本选择的交互方案（用户可在「设置 → 文本选择」里切换）。
 *
 * ## 为什么做成"可选"而不是直接改成最好的那个
 * 这个项目的历史反复说明一件事：**选词好不好用高度依赖输入设备**。
 *
 * 触屏上「按住不动约 0.5 秒 → 选中所处那一句」很自然；
 * 但鼠标与触控板上，长按是反直觉的 —— 桌面用户的习惯是
 * 「按下 → 直接拖 → 松开」（拖选）与「双击选词」。
 *
 * 而现有实现的手势入口是 `awaitLongPressOrCancellation`，它要求指针
 * **按住且几乎不移动**。鼠标一"按下就拖"，中途的位移会取消长按判定，
 * 于是永远产生不了选区 —— 这是结构性原因，不是手感调参能解决的。
 *
 * 三种方案的判据都落在 [SelectableMarkdownText] 的手势入口里。
 * 默认仍是 [TOUCH_LONG_PRESS]（保持原行为），用户按自己的设备切换。
 *
 * ## 方案来源
 * [DOUBLE_TAP_ALL] 的思路借鉴开源工程
 * [K1rakishou/ComposeCustomTextSelection](https://github.com/K1rakishou/ComposeCustomTextSelection)，
 * 它解决的问题正是"把选词从长按改为双击"（其 README 自述只做了这一项，
 * 鼠标部分未实现、自定义手柄与自定义菜单标注为 Not implemented）。
 */
enum class SelectionScheme(val displayName: String, val description: String) {

    /**
     * 方案一：树聊自绘长按（默认方案）
     * 按住不动约 0.5 秒选中所处的一句，带有双圆点微调手柄。
     */
    TOUCH_LONG_PRESS(
        "自绘长按选句（默认）",
        "触屏按住约 0.5 秒选中一句，带有自绘双圆点手柄微调。传统手机手势。"
    ),

    /**
     * 方案二：Android 原生 SelectionContainer 架构（类微信/QQ/系统级文本选择）
     * 彻底交由 Android 系统和 Compose 原生选区接管：
     * 原生水滴游标手柄、原生放大镜 Magnifier、原生系统菜单与蓝牙键鼠全面兼容。
     */
    SYSTEM_NATIVE(
        "系统原生容器（类微信/QQ/原生水滴手柄）",
        "使用 Android 官方 SelectionContainer 原生架构接管：自带原生水滴手柄、放大镜 Magnifier、系统浮动菜单及完整键鼠支持。"
    ),

    /**
     * 方案三：开源阅读器 / 浏览器模式（即按即划自由拉选）
     * 借鉴开源电子书阅读器（Legado）与网页端浏览器：
     * 不等待长按，手指或触控板按下一旦滑动立即拉出选区，松手定格。
     */
    FREE_SWIPE(
        "阅读器自由滑选（即按即滑/无等待）",
        "类开源阅读器与浏览器模式：按下无需等待 0.5 秒，指尖滑动直接拉出连续选区，松手定格弹出操作条。"
    ),

    /**
     * 方案四：蓝牙鼠标 / 触控板极客增强
     * 触屏保留长按，检测到鼠标或触控板时自动转为桌面级模式：
     * 文本光标 I-beam、按下直接拖选、双击选句。
     */
    MOUSE_DESKTOP(
        "键鼠/触控板极客增强（外设推荐）",
        "触屏仍用长按；连接蓝牙鼠标或触控板时自动分流：显示文本工字光标、按住即拖选、双击选句。"
    ),

    /**
     * 方案五：双击快速选句（全平台）
     * 借鉴 ComposeCustomTextSelection：不分设备，双击直接选中句子，彻底避开滑动与长按等待。
     */
    DOUBLE_TAP(
        "双击快速选句（快截无等待）",
        "双击直接选中整句，长按不拦截任何手势，彻底避免上下滚动和长按冲突。"
    );

    companion object {
        /** 从持久化字符串还原，容错旧配置并退回默认方案。 */
        fun fromName(name: String?): SelectionScheme {
            if (name == "DOUBLE_TAP_ALL") return DOUBLE_TAP
            return runCatching { valueOf(name!!) }.getOrDefault(TOUCH_LONG_PRESS)
        }
    }
}
