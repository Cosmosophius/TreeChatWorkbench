package com.treechat.workbench.ui.browser

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * 网页回传给 Kotlin 的一次选词结果。
 *
 * 矩形单位是 **视口相对的 CSS 像素**（直接来自 `Range.getClientRects()`），
 * 还不是宿主 View 像素 —— 换算见 WebBrowserScreen 里的 selectionGeometry。
 *
 * 声明为 public（而非 internal）：它出现在 [BrowserJsBridge] 的公开构造函数签名里，
 * 而桥类必须 public —— 被 JS 反射调用的类与 `@JavascriptInterface` 方法不能是
 * internal（Kotlin 会给 internal 声明做名字改写），这一点与 A 线
 * WebReaderActivity.AndroidBridge 的写法一致。
 */
data class BrowserSelection(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** 1 CSS 像素等于几个物理像素，由页面侧给出（window.devicePixelRatio）。 */
    val factor: Float,
    val pageUrl: String,
    val pageTitle: String
)

/**
 * 注入网页的**最小** JS 桥。
 *
 * 安全纪律：
 * - 只暴露一个对象、一个方法 —— `reportSelection`，别的一概不给。
 *   没有 `openUrl` / `exec` / `eval` / `readFile` 这类能被页面反向驱动的入口；
 * - 不启用文件访问（`allowFileAccess = false`），所以即使页面拿到桥也碰不到本地文件；
 * - 入参只当**纯文本**解析，长度截断，解析失败直接丢弃，不抛给网页；
 * - JS 桥回调发生在 WebView 自己的 JavaBridge 线程上，Compose 状态必须回主线程改，
 *   这里统一 post 到主线程。
 *
 * R8 说明：`@JavascriptInterface` 方法的 keep 规则由 AGP 内置规则提供
 * （与 A 线 WebReaderActivity.AndroidBridge 同一套机制，那边同样没有手写 keep），
 * 无需在本工程 proguard 里另写。
 */
class BrowserJsBridge(
    private val onSelection: (BrowserSelection) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun reportSelection(json: String) {
        val parsed = runCatching { parseSelection(json) }.getOrNull() ?: return
        mainHandler.post { onSelection(parsed) }
    }

    private companion object {

        /** 选文上限：工具条只用到全文，再长也没有意义，截断防大字符串贴爆。 */
        const val MAX_TEXT = 4000

        fun parseSelection(json: String): BrowserSelection {
            val obj = JSONObject(json)
            return BrowserSelection(
                text = obj.optString("text", "").take(MAX_TEXT),
                left = obj.optDouble("left", 0.0).toFloat(),
                top = obj.optDouble("top", 0.0).toFloat(),
                right = obj.optDouble("right", 0.0).toFloat(),
                bottom = obj.optDouble("bottom", 0.0).toFloat(),
                factor = obj.optDouble("factor", 0.0).toFloat(),
                pageUrl = obj.optString("url", ""),
                pageTitle = obj.optString("title", "")
            )
        }
    }
}
