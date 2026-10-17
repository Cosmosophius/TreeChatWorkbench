// 充面层：android.app.Application + androidx.lifecycle.AndroidViewModel。
// 桌面侧 Application 是唯一充面 Context 的别名；ViewModel 用共享的 lifecycle-viewmodel 基类。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package android.app

import android.content.Context

open class Application : Context() {
    override fun toString(): String = "DesktopApp"
}
