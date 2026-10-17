// 充面层：androidx.compose.ui.platform.LocalConfiguration + android.content.res.Configuration。
// 桌面默认给一个 1280x800的逻辑屏（dp）视图，从窗口实际尺寸摊派在 Main 里更新。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package android.content.res

class Configuration {
    var screenWidthDp: Int = 1280
    var screenHeightDp: Int = 800
    var densityDpi: Int = 160
    var orientation: Int = ORIENTATION_LANDSCAPE

    companion object {
        const val ORIENTATION_UNDEFINED = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}
