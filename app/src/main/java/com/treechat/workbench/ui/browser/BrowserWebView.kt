package com.treechat.workbench.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.hypot

/**
 * 单次"点一下"的判定上限（毫秒）。与系统的长按阈值无关：这里只认"快速抬手"。
 */
private const val TAP_TIMEOUT_MS = 250L

/** Via 风格桌面端 Chrome User-Agent 伪装，触发各大网站的电脑版版面 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** 强制覆盖 viewport 为 1280px 桌面排版，并允许用户双指缩放查看全貌 */
private const val DESKTOP_VIEWPORT_SCRIPT = """
(function(){try{
  var vp = document.querySelector('meta[name="viewport"]');
  if(!vp){
    vp = document.createElement('meta');
    vp.name = 'viewport';
    (document.head||document.documentElement).appendChild(vp);
  }
  vp.setAttribute('content', 'width=1280, initial-scale=0.75, maximum-scale=4.0, user-scalable=yes');
}catch(e){}})()
"""

/**
 * WebView 宿主（Compose AndroidView 包裹）。
 *
 * 配置取向 —— "能读普通网页" 与 "别给它多余能力" 之间的最小集合：
 *
 * 开启：JavaScript、DOM Storage（大量阅读站在没有 DOM Storage 时白屏）。
 * 关闭/不开启：文件访问（allowFileAccess / allowContentAccess 全 false）、
 *   多窗口（target=_blank 就地打开，不弹新窗）、自动弹窗、
 *   地理定位、以及任何 `setAllowUniversalAccessFromFileURLs` 之类的放宽项。
 * 桥：只注册 [BrowserJsBridge] 一个对象，且只有一个白名单方法。
 *
 * 生命周期：WebView 必须在 onDispose 里 destroy()，否则会漏掉整个渲染进程与
 * 页面注册的监听器；这里同时摘掉 JS 桥并让 controller 脱离引用。
 *
 * @param onTapPage 页面上的"快速单击"（几乎没移动、很快抬手）回调。
 *   沉浸态用它把地址栏/导航条唤回来。**刻意不在 ACTION_DOWN/UP 上 consume()**：
 *   监听器只做观察，网页本身仍然完整收到这次点击 —— 沉浸读数时点链接不会失灵。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "ClickableViewAccessibility")
@Composable
internal fun BrowserWebView(
    controller: BrowserController,
    readerModeOn: Boolean,
    isDark: Boolean = false,
    onSelection: (BrowserSelection) -> Unit,
    onReaderModeRefused: () -> Unit,
    onTapPage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 桥在 factory 里只创建一次，因此回调必须走 rememberUpdatedState，
    // 否则它永远指向首次组合时的那个 lambda（典型的"状态更新丢失"坑）。
    val latestOnSelection by rememberUpdatedState(onSelection)
    val latestReaderMode by rememberUpdatedState(readerModeOn)
    val latestIsDark by rememberUpdatedState(isDark)
    val latestRefused by rememberUpdatedState(onReaderModeRefused)
    val latestOnTapPage by rememberUpdatedState(onTapPage)
    val bridge = remember {
        BrowserJsBridge(onSelection = { selection -> latestOnSelection(selection) })
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val existing = controller.webView
            if (existing != null) {
                (existing.parent as? ViewGroup)?.removeView(existing)
                existing
            } else {
                object : WebView(ctx) {
                    // 彻底消灭 Android 系统原生复制/分享/全选浮条，100% 独占使用条畅同款悬浮工具条
                    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null
                    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null
                }.apply {
                    settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // 不给本地文件能力：网页就算拿到桥也读不到 /sdcard 与 assets
                    allowFileAccess = false
                    allowContentAccess = false
                    // 不开双指缩放：选区矩形是"视口相对 CSS 像素"，缩放会让它与视觉
                    // 视口错位，而缩放不会触发选区重新上报 —— 工具条会飘到错的地方。
                    // 坐标换算的确定性优先；放大阅读走阅读模式。
                    setSupportZoom(false)
                    builtInZoomControls = false
                    loadWithOverviewMode = false
                    useWideViewPort = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    // 注意：setAllowUniversalAccessFromFileURLs / setGeolocationEnabled
                    // 一律保持平台默认（关闭），不在这里放宽。
                }

                // 只观察不消费的点按检测（见 onTapPage 的说明）
                val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
                setOnTouchListener(object : View.OnTouchListener {
                    private var downTime = 0L
                    private var downX = 0f
                    private var downY = 0f

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downTime = event.eventTime
                                downX = event.x
                                downY = event.y
                            }
                            MotionEvent.ACTION_UP -> {
                                val quick = event.eventTime - downTime <= TAP_TIMEOUT_MS
                                val still = hypot(event.x - downX, event.y - downY) <= touchSlop
                                if (quick && still) latestOnTapPage()
                            }
                        }
                        // 永远不消费：网页照常收到点击、滚动、长按选词
                        return false
                    }
                })

                webViewClient = object : WebViewClient() {

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        controller.currentUrl = url ?: ""
                        controller.isLoading = true
                        controller.lastError = null
                        controller.syncNavigationState()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        controller.currentUrl = url ?: ""
                        controller.isLoading = false
                        view?.title?.takeIf { it.isNotBlank() }?.let { controller.pageTitle = it }
                        // 每次文档更替后重新注入：监听器与注入样式都随旧文档销毁了
                        injectHooks(view, latestReaderMode, latestIsDark, latestRefused)
                        if (controller.desktopMode) {
                            view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                        }
                        controller.syncNavigationState()
                    }

                    override fun doUpdateVisitedHistory(
                        view: WebView?,
                        url: String?,
                        isReload: Boolean
                    ) {
                        controller.currentUrl = url ?: controller.currentUrl
                        controller.syncNavigationState()
                        injectHooks(view, latestReaderMode, latestIsDark, latestRefused)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        // 只关心主文档失败；子资源（图片/CDN）失败不该弹整页错误
                        if (request?.isForMainFrame != true) return
                        controller.isLoading = false
                        val reason = error?.description?.toString().orEmpty()
                            .ifBlank { "未知网络错误" }
                        controller.lastError = "网页加载失败：$reason"
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        // 常见 web scheme 就地加载；其余（mailto/tel/intent/自定义）不由
                        // WebView 处理，直接吞掉而不崩 —— 本工程不允许在本组件里
                        // 起任意外部 Activity。
                        return !(url.startsWith("http://") ||
                            url.startsWith("https://") ||
                            url.startsWith("about:") ||
                            url.startsWith("data:"))
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    /** 页面标题──由 ChromeClient 回调（WebViewClient 没有这个方法）。 */
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        title?.takeIf { it.isNotBlank() }?.let { controller.pageTitle = it }
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        controller.progress = newProgress
                    }
                }

                addJavascriptInterface(bridge, BrowserJs.BRIDGE_NAME)
                controller.webView = this
                tag = settings.userAgentString // 保存原生的移动端默认 UA
                loadUrl(BrowserJs.START_URL)
            }
        }
    },
        update = { webView ->
            val isDesktop = controller.desktopMode
            val originalUa = webView.tag as? String ?: webView.settings.userAgentString
            val targetUa = if (isDesktop) DESKTOP_UA else originalUa

            if (webView.settings.userAgentString != targetUa) {
                webView.settings.apply {
                    userAgentString = targetUa
                    useWideViewPort = true
                    loadWithOverviewMode = isDesktop
                    setSupportZoom(isDesktop)
                    builtInZoomControls = isDesktop
                    displayZoomControls = false
                }
                // UA 改变后重载当前页面以获取真实桌面排版
                if (controller.currentUrl.isNotBlank() && controller.currentUrl != BrowserJs.START_URL) {
                    webView.reload()
                }
            }

            // 深色模式状态动态推入：无论在阅读模式还是普通网页，深色切换即时生效
            if (latestReaderMode) {
                webView.evaluateJavascript(BrowserJs.readerDarkScript(latestIsDark), null)
            } else {
                webView.evaluateJavascript(BrowserJs.globalPageDarkScript(latestIsDark), null)
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            // 桌面级 Tab 保活：切 Tab 时绝不 destroy，仅脱离父容器挂载，
            // 完整保留内核 DOM 树、表单输入、滚动条位置与历史记录！
            controller.webView?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
            }
        }
    }
}

/**
 * 注入选词监听；若处于阅读模式则补回排版；若处于深色模式则强制注入 Via 经典保真反色滤镜。
 */
private fun injectHooks(view: WebView?, readerModeOn: Boolean, isDark: Boolean, onRefused: () -> Unit) {
    if (view == null) return
    if (readerModeOn) {
        view.evaluateJavascript(BrowserJs.readerModeEnableScript()) { value ->
            if (value?.trim('"') != "1") onRefused()
        }
        view.evaluateJavascript(BrowserJs.readerDarkScript(isDark), null)
    } else {
        // 普通网页模式：跟随应用深色强制注入 Via 经典保真反色滤镜
        view.evaluateJavascript(BrowserJs.globalPageDarkScript(isDark), null)
    }
    view.evaluateJavascript(BrowserJs.SELECTION_LISTENER, null)
}
