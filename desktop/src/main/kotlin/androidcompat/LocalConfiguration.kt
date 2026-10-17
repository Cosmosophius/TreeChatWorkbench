// 充面层：androidx.compose.ui.platform.LocalConfiguration 的桌面版。
@file:Suppress("unused")
package androidx.compose.ui.platform

import android.content.res.Configuration
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

val LocalConfiguration: ProvidableCompositionLocal<Configuration> =
    staticCompositionLocalOf { Configuration() }

/** 桌面 Main 把窗口尺寸写进该单例（比安卓的 system Configuration 更新路径简单）。 */
object DesktopConfigurationHolder {
    @Volatile
    var current: Configuration = Configuration()

    fun update(widthDp: Int, heightDp: Int) {
        current.screenWidthDp = widthDp
        current.screenHeightDp = heightDp
    }
}
