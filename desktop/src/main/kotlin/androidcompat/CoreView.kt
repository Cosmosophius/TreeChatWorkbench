// 充面层：androidx.core.view.{WindowCompat, WindowInsetsCompat, WindowInsetsControllerCompat}。
// 桌面无系统栏，沉浸链路全部退化为 no-op（保留 API 面让 ReaderScreen 共享源原样编译）。
@file:Suppress("unused", "unused_parameter", "MemberVisibilityCanBePrivate")
package androidx.core.view

object WindowCompat {
    @JvmStatic
    fun getInsetsController(
        window: android.view.Window?,
        view: android.view.View?,
    ): WindowInsetsControllerCompat = WindowInsetsControllerCompat()

    @JvmStatic
    fun setDecorFitsSystemWindows(window: android.view.Window?, decorFitsSystemWindows: Boolean) {}
}

object WindowInsetsCompat {
    object Type {
        const val SYSTEM_BARS: Int = 1
        const val STATUS_BARS: Int = 2
        const val NAVIGATION_BARS: Int = 4
        const val IME: Int = 8

        fun systemBars(): Int = SYSTEM_BARS
        fun statusBars(): Int = STATUS_BARS
        fun navigationBars(): Int = NAVIGATION_BARS
        fun ime(): Int = IME
    }
}

class WindowInsetsControllerCompat {
    var systemBarsBehavior: Int = 0
    var isAppearanceLightStatusBars: Boolean = false

    fun hide(type: Int) {}
    fun show(type: Int) {}

    companion object {
        const val BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: Int = 2
        const val BEHAVIOR_DEFAULT: Int = 0
    }
}
