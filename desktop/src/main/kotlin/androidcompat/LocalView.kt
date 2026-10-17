// 充面层：androidx.compose.ui.platform.LocalView 桌面版（ReaderScreen 沉浸态读 LocalView）。
@file:Suppress("unused")
package androidx.compose.ui.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

val LocalView: ProvidableCompositionLocal<android.view.View> =
    staticCompositionLocalOf { android.view.View() }
