// 充面层：android.app.Activity（沉浸态/横竖屏赋值的窄口）。
@file:Suppress("unused", "unused_parameter", "unused_return")
package android.app

import android.content.Context
import android.view.Window

open class Activity : Context() {
    val window: Window
        get() = Window()

    var requestedOrientation: Int = -1

    override fun toString(): String = "DesktopActivity"
}

