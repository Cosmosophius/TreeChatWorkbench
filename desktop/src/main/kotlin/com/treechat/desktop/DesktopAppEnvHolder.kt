// 充面层挂接点：桌面 Main 启动时先把 DesktopAppEnv 挂进来，
// 之后所有充面层（Context.filesDir/prefs/assets）都指向它。
package com.treechat.desktop

import android.content.DesktopAppContext

object DesktopAppEnvHolder {
    lateinit var env: DesktopAppEnv
        private set

    /** 进程级唯一充面 Context（共享代码经 LocalContext / 构造参数拿到它）。 */
    val appContext: DesktopAppContext by lazy { DesktopAppContext() }

    fun install(environment: DesktopAppEnv) {
        env = environment
    }
}
