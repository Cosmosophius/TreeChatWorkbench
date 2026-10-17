package com.treechat.workbench.network.search

import java.net.URLDecoder
import java.util.Base64

/**
 * 免密钥搜索引擎的 HTML 结果解析器（**零第三方依赖**）。
 *
 * ## 为什么不引 Jsoup / HtmlCleaner 之类的库
 * 这些引擎的搜索页是**服务端渲染好的静态 HTML**，没有需要 DOM 树、CSS 选择器或
 * 容错解析器才能处理的结构。真正需要的只是"从固定 class 名的片段里抠三样东西：
 * 标题、链接、摘要"。为此引入一个几百 KB 的 HTML 解析库，既抬高安装包体积，
 * 又给未来升级埋一个版本坑。工程里已有 okhttp + gson，用正则做**片段级**提取足够。
 *
 * ## 覆盖的结构
 * 1. **DuckDuckGo HTML 端点** `https://html.duckduckgo.com/html/`
 *    - `a.result__a` —— 标题 + 跳转链接（href 常是 `//duckduckgo.com/l/?uddg=…`）
 *    - `a.result__snippet` / `div.result__snippet` —— 摘要
 * 2. **DuckDuckGo Lite 端点** `https://lite.duckduckgo.com/lite/`
 *    - `a.result-link` + `td.result-snippet`，两者在表格里交替出现，按出现顺序配对
 * 3. **Bing 结果页** —— `li.b_algo` / `div.b_algo` 结果块 + 块内 `h2 > a` 标题 + `p` 摘要
 * 4. **跳转链接还原**
 *    - DuckDuckGo：`//duckduckgo.com/l/?uddg=<urlencoded>` → 真实 URL
 *    - Bing：`https://www.bing.com/ck/a?…&u=a1<base64url>` → 真实 URL
 *    - 协议相对地址 `//host/path` → 补 `https:`
 *
 * ## 刻意不做的事
 * - 不执行 JS、不跟随后续渲染（这些端点返回的本来就是静态 HTML）；
 * - 不还原短链、不抓取落地页正文 —— 那些是"读网页"能力，不是"搜索"能力。
 *
 * ## 稳健性取向
 * 解析取不到结果时**返回空列表**，绝不抛异常：调用方据此判断"这个端点没有可用结果"，
 * 从而继续降级到下一个端点。整条通路的"是否失败"由调用方（WebSearchClient）决定，
 * 解析器只负责"尽力提取"。
 */
internal object HtmlSearchResultParser {

    private val TAG_RE = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val WS_RE = Regex("\\s+")
    private val NUMERIC_ENTITY_RE = Regex("&#(x|X)?([0-9a-fA-F]+);")

    /** 开标签里的 href（单/双引号、属性顺序都兼容）。 */
    private val HREF_RE = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /** DuckDuckGo 跳转参数。 */
    private val UDDG_RE = Regex("""[?&]uddg=([^&"'#]+)""")

    /** Bing 跳转参数。 */
    private val BING_U_RE = Regex("""[?&]u=([^&"'#]+)""")

    private const val MAX_TITLE_LEN = 200

    // ------------------------------------------------------------------ 通用工具

    /** 去标签 + 解实体 + 折叠空白。标题/摘要都是单行文本，这已经够用。 */
    fun stripTags(raw: String): String =
        WS_RE.replace(decodeEntities(TAG_RE.replace(raw, " ")), " ").trim()

    /** 解 HTML 实体（命名实体 + 十进制 + 十六进制数字实体）。 */
    fun decodeEntities(raw: String): String {
        if (raw.indexOf('&') < 0) return raw
        var text = raw
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&hellip;", "…")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&lsquo;", "'")
            .replace("&rsquo;", "'")
            .replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")
            .replace("&middot;", "·")
            .replace("&times;", "×")
        // 数字实体（如 &#39; &#x27; &#8217;）交给统一的数值解码，避免逐条枚举。
        text = NUMERIC_ENTITY_RE.replace(text) { match ->
            val isHex = match.groupValues[1].isNotEmpty()
            val code = match.groupValues[2].toIntOrNull(if (isHex) 16 else 10)
            code?.let { runCatching { String(Character.toChars(it)) }.getOrDefault(match.value) }
                ?: match.value
        }
        return text
    }

    /** 查询串是否明显是中日韩文本（用于给 DuckDuckGo 选区域参数）。 */
    fun looksCjk(text: String): Boolean =
        text.any { it.code in 0x2E80..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF }

    /**
     * 把跳转链接还原成真实 URL。
     *
     * 还原不出真实地址、或最终指向搜索引擎自身的内链（广告/统计跳板）时返回空串，
     * 让调用方直接丢弃这一条 —— 把 `duckduckgo.com/l/?uddg=…` 这种地址塞给模型毫无意义。
     */
    fun unwrapRedirect(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        var url = decodeEntities(rawUrl.trim())

        // 1) DuckDuckGo：//duckduckgo.com/l/?uddg=<urlencoded 的真实地址>
        UDDG_RE.find(url)?.groupValues?.get(1)?.let { encoded ->
            val decoded = urlDecode(encoded)
            if (decoded.isNotBlank()) url = decoded
        }

        // 2) Bing：//www.bing.com/ck/a?…&u=a1<base64url>（a1 前缀表示 base64 编码）
        if (url.contains("bing.com/ck/a", ignoreCase = true)) {
            BING_U_RE.find(url)?.groupValues?.get(1)?.let { token ->
                if (token.startsWith("a1")) {
                    decodeBase64Url(token.substring(2))?.let { if (it.isNotBlank()) url = it }
                }
            }
        }

        // 3) 协议相对地址
        if (url.startsWith("//")) url = "https:$url"

        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return ""
        }

        // 4) 丢掉仍然指向搜索站自身的内链（广告位、统计跳板、站内导航）
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().lowercase()
        if (host.endsWith("duckduckgo.com") && (url.contains("/l/") || url.contains("/y.js"))) return ""
        if (host.endsWith("bing.com") && (url.contains("/ck/a") || url.contains("/search"))) return ""
        if (host.isEmpty()) return ""

        return url
    }

    // ------------------------------------------------------- DuckDuckGo HTML 端点

    /**
     * 解析 `https://html.duckduckgo.com/html/?q=…` 的结果页。
     *
     * 逐条扫描标题锚点，再把**它之后、下一条标题之前**的第一段摘要配对给它 ——
     * 比"把所有标题和所有摘要各自收集成两个列表再按下标配对"稳，
     * 因为页面上偶尔会混入没有摘要的条目（如限流提示、相关搜索），
     * 按下标配对会从此整体错位。
     */
    fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        if (html.isBlank()) return emptyList()

        val titleAnchorOpen = Regex("""<a\b[^>]*\bresult__a\b[^>]*>""", RegexOption.IGNORE_CASE)
        val snippetRe = Regex(
            """<(?:a|div|td)\b[^>]*\b(?:result__snippet|result-snippet)\b[^>]*>(.*?)</(?:a|div|td)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val anchors = titleAnchorOpen.findAll(html).toList()
        val snippets = snippetRe.findAll(html).toList()

        val out = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        anchors.forEachIndexed { index, anchor ->
            val tagEnd = anchor.range.last
            val nextAnchorStart = anchors.getOrNull(index + 1)?.range?.first ?: html.length
            // 窗口上限：避免在畸形 HTML（缺少 </a>）上把整页当标题吃掉。
            val windowEnd = minOf(nextAnchorStart, tagEnd + 600)

            val closeIdx = html.indexOf("</a>", tagEnd)
            val titleEnd = if (closeIdx in (tagEnd + 1) until windowEnd) closeIdx else windowEnd
            val title = stripTags(html.substring(tagEnd + 1, titleEnd)).take(MAX_TITLE_LEN)

            val url = unwrapRedirect(HREF_RE.find(anchor.value)?.groupValues?.get(1).orEmpty())
            if (title.isBlank() || url.isEmpty() || !seen.add(url)) return@forEachIndexed

            val snippet = snippets.firstOrNull { it.range.first in (tagEnd + 1) until windowEnd }
                ?.groupValues?.get(1)
                ?.let { stripTags(it) }
                .orEmpty()

            out.add(SearchResult(title, url, snippet))
        }
        return out
    }

    // ------------------------------------------------------- DuckDuckGo Lite 端点

    /**
     * 解析 `https://lite.duckduckgo.com/lite/?q=…` 的结果页。
     *
     * lite 版是纯表格布局：结果链接（`a.result-link`）与其摘要（`td.result-snippet`）
     * 在 HTML 里交替出现。这里按"链接锚点位置"向右找**最近的一段摘要**，
     * 而不是像旧实现那样把两个列表按下标硬配 —— 页面上任何一处多/少一个摘要
     * 都会让旧实现从那一行起整体错位（摘要全部张冠李戴）。
     */
    fun parseDuckDuckGoLite(html: String): List<SearchResult> {
        if (html.isBlank()) return emptyList()

        val linkAnchorOpen = Regex("""<a\b[^>]*\bresult-link\b[^>]*>""", RegexOption.IGNORE_CASE)
        val snippetRe = Regex(
            """<td\b[^>]*\bresult-snippet\b[^>]*>(.*?)</td>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val out = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        linkAnchorOpen.findAll(html).forEach { anchor ->
            val tagEnd = anchor.range.last
            val windowEnd = minOf(html.length, tagEnd + 4000)

            val closeIdx = html.indexOf("</a>", tagEnd)
            val titleEnd = if (closeIdx in (tagEnd + 1) until windowEnd) closeIdx else windowEnd
            val title = stripTags(html.substring(tagEnd + 1, titleEnd)).take(MAX_TITLE_LEN)

            val url = unwrapRedirect(HREF_RE.find(anchor.value)?.groupValues?.get(1).orEmpty())
            if (title.isBlank() || url.isEmpty() || !seen.add(url)) return@forEach

            val snippet = snippetRe.findAll(html, tagEnd + 1)
                .firstOrNull()
                ?.groupValues?.get(1)
                ?.let { stripTags(it) }
                .orEmpty()

            out.add(SearchResult(title, url, snippet))
        }
        return out
    }

    // ---------------------------------------------------------------- Bing 结果页

    /**
     * 解析 Bing 结果页（`cn.bing.com` / `www.bing.com`）。
     *
     * 先按结果块（`li.b_algo` / `div.b_algo`）切段，在段内取标题与摘要；
     * 若一条都没切出来（Bing 换过 class 名的历史发生过），退化为全页
     * "`h2 > a` + 非 bing.com 域" 的宽松提取，至少还能拿到标题与链接。
     */
    fun parseBing(html: String): List<SearchResult> {
        if (html.isBlank()) return emptyList()

        val blockOpen = Regex(
            """<(?:li|div)\b[^>]*class\s*=\s*["'][^"']*\bb_algo\b[^"']*["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val headingOpen = Regex("""<h2\b[^>]*>\s*<a\b[^>]*>""", RegexOption.IGNORE_CASE)
        val paraRe = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        val out = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        val blocks = blockOpen.findAll(html).toList()
        blocks.forEachIndexed { index, block ->
            val start = block.range.last + 1
            val end = blocks.getOrNull(index + 1)?.range?.first ?: minOf(html.length, start + 8000)
            val segment = html.substring(start, end)

            val heading = headingOpen.find(segment) ?: return@forEachIndexed
            val headEnd = heading.range.last
            val closeIdx = segment.indexOf("</a>", headEnd)
            val titleEnd = if (closeIdx in (headEnd + 1) until segment.length) closeIdx else minOf(segment.length, headEnd + 400)
            val title = stripTags(segment.substring(headEnd + 1, titleEnd)).take(MAX_TITLE_LEN)

            val url = unwrapRedirect(HREF_RE.find(heading.value)?.groupValues?.get(1).orEmpty())
            if (title.isBlank() || url.isEmpty() || !seen.add(url)) return@forEachIndexed

            val snippet = paraRe.findAll(segment, headEnd)
                .firstOrNull()
                ?.groupValues?.get(1)
                ?.let { stripTags(it) }
                .orEmpty()

            out.add(SearchResult(title, url, snippet))
        }

        if (out.isNotEmpty()) return out

        // 宽松兜底：整页 h2 > a
        val looseHeading = Regex("""<h2\b[^>]*>\s*<a\b[^>]*>""", RegexOption.IGNORE_CASE)
        looseHeading.findAll(html).forEach { heading ->
            val headEnd = heading.range.last
            val windowEnd = minOf(html.length, headEnd + 400)
            val closeIdx = html.indexOf("</a>", headEnd)
            val titleEnd = if (closeIdx in (headEnd + 1) until windowEnd) closeIdx else windowEnd
            val title = stripTags(html.substring(headEnd + 1, titleEnd)).take(MAX_TITLE_LEN)
            val url = unwrapRedirect(HREF_RE.find(heading.value)?.groupValues?.get(1).orEmpty())
            if (title.isBlank() || url.isEmpty() || !seen.add(url)) return@forEach
            out.add(SearchResult(title, url, ""))
        }
        return out
    }

    // ------------------------------------------------------------------ 内部工具

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault("")

    private fun decodeBase64Url(value: String): String? = runCatching {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }.getOrNull()
}
