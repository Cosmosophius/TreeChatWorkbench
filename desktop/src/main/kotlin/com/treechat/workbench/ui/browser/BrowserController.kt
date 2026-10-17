// 桌面替身：ui.browser.BrowserController（M4 独立窗口方案ове接管）。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.ui.browser

class BrowserController {
    var currentUrl: String = "about:blank"
    var isLoading: Boolean = false
    fun loadOrSearch(queryOrUrl: String) { currentUrl = queryOrUrl }
}
