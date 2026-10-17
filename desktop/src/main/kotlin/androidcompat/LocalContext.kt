// 充面层：androidx.compose.ui.platform.LocalContext 的桌面版（常驻非空，指充面 Context）。
// 桌面 CMP 1.6.11 的 ui-desktop 未提供 LocalContext（已实测 jar 零命中），故此处纯增量。
@file:Suppress("unused")
package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext: ProvidableCompositionLocal<Context> =
    staticCompositionLocalOf { com.treechat.desktop.DesktopAppEnvHolder.appContext }
