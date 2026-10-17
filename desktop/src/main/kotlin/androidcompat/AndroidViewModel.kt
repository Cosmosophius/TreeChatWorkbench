// 充面层：androidx.lifecycle.AndroidViewModel（桌面 lifecycle-viewmodel-desktop 有 ViewModel 无 AndroidViewModel）。
package androidx.lifecycle

import android.app.Application

abstract class AndroidViewModel(private val appInstance: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getApplication(): T = appInstance as T
}
