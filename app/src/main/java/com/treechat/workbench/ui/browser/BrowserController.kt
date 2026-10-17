package com.treechat.workbench.ui.browser

import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 内置浏览器的状态持有者。
 *
 * 为什么不让各 Composable 各自 remember 一堆状态：地址栏、导航按钮、进度条、
 * 「本页生成对话」按钮读的是同一份"当前页"信息（url / title / 能否后退），
 * 分散存放必然出现"地址栏显示 A 而标题栏显示 B"的不一致。
 *
 * 这里只持有状态与对 WebView 的转发调用；WebView 本身由 BrowserWebView 创建与销毁。
 * 所有方法都必须在主线程调用（Compose 事件回调天然满足）。
 */
@Stable
class BrowserController {

    /** 当前 WebView 实例；未挂载或已销毁时为 null。 */
    internal var webView: WebView? = null

    /** 当前页面地址（含 about:blank）。 */
    var currentUrl by mutableStateOf("")

    /** 当前页面标题；拿不到时为空串，由调用方回落到域名。 */
    var pageTitle by mutableStateOf("")

    /** 加载进度 0..100。 */
    var progress by mutableIntStateOf(0)

    /** 是否正在加载（用于刷新/停止按钮的形态切换）。 */
    var isLoading by mutableStateOf(false)

    var canGoBack by mutableStateOf(false)

    var canGoForward by mutableStateOf(false)

    /** 主文档加载失败的原因（已本地化的描述），无错误时为 null。 */
    var lastError by mutableStateOf<String?>(null)

    /**
     * 电脑模式（桌面版 UA + 1280px 宽视口 + 双指缩放，对齐 Via 浏览器）。
     */
    var desktopMode by mutableStateOf(false)

    fun loadUrl(url: String) {
        lastError = null
        webView?.loadUrl(url)
    }

    fun goBack() {
        val wv = webView ?: return
        if (wv.canGoBack()) wv.goBack()
    }

    fun goForward() {
        val wv = webView ?: return
        if (wv.canGoForward()) wv.goForward()
    }

    fun reload() {
        webView?.reload()
    }

    fun stopLoading() {
        webView?.stopLoading()
    }

    /** 执行一段 JS；[onResult] 非空时在**主线程**收到原始返回值（含引号的 JSON 文本）。 */
    fun evaluate(script: String, onResult: ((String) -> Unit)? = null) {
        val wv = webView ?: return
        if (onResult == null) {
            wv.evaluateJavascript(script, null)
        } else {
            wv.evaluateJavascript(script) { value -> onResult(value ?: "") }
        }
    }

    /** 由 BrowserWebView 在 onDispose 里调用，防止窗口已销毁还往 WebView 上打命令。 */
    internal fun detach() {
        webView = null
        isLoading = false
    }

    /** 从 WebView 同步导航可用性（WebView 没有可观察的 canGoBack 状态）。 */
    internal fun syncNavigationState() {
        val wv = webView ?: return
        canGoBack = wv.canGoBack()
        canGoForward = wv.canGoForward()
    }
}
