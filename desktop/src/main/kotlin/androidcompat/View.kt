// 充面层：android.view.{View,Window,PointerIcon} + LocalView —— 沉浸态/光标用的最小面。
@file:Suppress("unused", "unused_parameter")
package android.view

import android.content.Context

open class View {
    var pointerIcon: Any? = null
    var systemUiVisibility: Int = 0

    fun setOnTouchListener(l: Any?) = Unit
    fun setOnClickListener(l: Any?) = Unit
    fun requestFocus() = Unit
}

class Window {
    val decorView: View get() = View()
}

class PointerIcon {
    companion object {
        const val TYPE_TEXT = 1000

        @JvmStatic
        fun getSystemIcon(context: Context?, type: Int): PointerIcon = PointerIcon()
    }
}
