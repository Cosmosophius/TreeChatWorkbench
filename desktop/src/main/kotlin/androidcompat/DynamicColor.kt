// 充面层：androidx.compose.material3 动态色函数的桌面版（共享代码只在 SDK_INT>=31 分支引用，
// 桌面充面 Build 恒为 30 走不到，必须存在仅为满足编译）。
@file:Suppress("unused")
package androidx.compose.material3

import android.content.Context

@Suppress("UNUSED_PARAMETER")
fun dynamicDarkColorScheme(context: Context): ColorScheme =
    darkColorScheme()

@Suppress("UNUSED_PARAMETER")
fun dynamicLightColorScheme(context: Context): ColorScheme =
    lightColorScheme()
