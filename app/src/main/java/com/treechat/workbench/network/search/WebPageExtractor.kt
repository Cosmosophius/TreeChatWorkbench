package com.treechat.workbench.network.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/**
 * 一篇网页的正文抽取结果。
 *
 * ## 语义约定（重要，与"诚实铁律"一致）
 * - [error] 为 `null` 时才算**抓取成功**，此时 [text] 一定非空；
 * - [error] 非 `null` 时 [text] 一律为空字符串，**绝不用空正文冒充成功**；
 * - [title] 可能在失败时仍有值（标题与正文是分开抽取的），仅供参考，不代表抓取成功。
 */
data class PageContent(
    val url: String,
    val title: String,
    val text: String,
    val error: String?
)

/** 抓取公开网页用的浏览器 UA：部分站点对默认的 okhttp UA 会直接返回 403。 */
private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

/** 单页最多读取的字节数：正文抽取用不到整站，读太多只会占内存、拖慢正则。 */
private const val MAX_BODY_BYTES = 1_200_000

/** 解码后参与正则的 HTML 字符上限（在字节上限之内再做一次字符级兜底）。 */
private const val MAX_HTML_CHARS = 600_000

/** 截断标注：截断处必须让模型知道"后面还有内容被砍了"，否则它会当作文章就是这样结尾。 */
private const val TRUNCATION_MARK = "\n……（已截断）"

private const val TITLE_MAX_CHARS = 200

/** 短于这个长度的 `<p>` 视为导航/按钮文字，不计入正文候选。 */
private const val MIN_PARAGRAPH_CHARS = 12

/** 正文候选总长度低于此值时，认为 `<p>` 启发式不可靠，退回整页文本。 */
private const val MIN_MAIN_TEXT_CHARS = 120

/** 两段 `<p>` 之间的 HTML 距离超过此值，就认为它们属于不同区块（中间隔着侧栏/评论等）。 */
private const val MAX_PARAGRAPH_GAP = 2500

/**
 * 只用 OkHttp 抓取一个网页并抽出**正文**（零新依赖：不引 Jsoup / HtmlCleaner）。
 *
 * 这是"让联网回答变详实"的关键一步：搜索接口只给一句摘要，
 * 把正文喂给模型，它才答得出细节。因此本文件的取舍都是围绕"尽力拿到真实正文"、
 * 同时"拿不到就如实说拿不到"展开的。
 *
 * ## 字符集判定顺序（决定会不会出乱码）
 * 1. **BOM**（`EF BB BF` / `FE FF` / `FF FE`）—— 最权威；
 * 2. 响应头 `Content-Type; charset=…`；
 * 3. HTML 里的 `<meta charset>` / `<meta http-equiv="Content-Type">`；
 * 4. **UTF-8 严格解码**（`REPORT` 模式，非法字节直接判定失败）；
 * 5. **GB18030** —— 中文站最常见的"没声明字符集的 GBK 页"；
 * 6. **ISO-8859-1** —— 永不报错的最后兜底。
 *
 * 每一步都用**严格模式**尝试，失败就顺延到下一个候选：这样"header 写着 utf-8 实际是 GBK"
 * 的页面不会解出一堆 `�`，而是被后续的 GB18030 救回来。
 *
 * ## 正文抽取（纯 Kotlin 正则/状态机）
 * 1. 先删整段非内容标签：`<script>/<style>/<noscript>/<svg>/<template>/<iframe>/<head>` 与注释；
 * 2. 再删明显非正文容器：`nav/aside/footer/header` 元素，以及 `class`/`id` 命中
 *    `nav|menu|sidebar|comment|footer|header|ad|cookie|share|related|recommend|breadcrumb|pagination|banner`
 *    的 `div`/`section`；
 * 3. 取 `<body>` 片段，用"`<p>` 文本累计最长的连续区块"作为正文候选
 *    （相隔超过 [MAX_PARAGRAPH_GAP] 视为不同区块）；
 * 4. 块级标签转换行 → 去标签 → 解实体 → 压缩连续空行；
 * 5. 候选太短则退回整页文本（有些资讯页正文并不在 `<p>` 里）。
 *
 * ## 失败语义
 * 任何失败（HTTP 非 2xx、超时、非文本响应、无正文）都**不抛异常**，
 * 而是把原因写进 [PageContent.error]。调用方据此如实标注"未取到正文"。
 *
 * @param url      目标地址（必须是 http/https 绝对地址）
 * @param maxChars 正文最大字符数，超出则截断并附 [TRUNCATION_MARK]
 * @param timeoutMs 本次抓取的时间上限（连接/读取/整体三层都是它）
 */
suspend fun fetchAndExtract(url: String, maxChars: Int, timeoutMs: Long): PageContent =
    withContext(Dispatchers.IO) {
        val target = url.trim()
        if (target.isEmpty() ||
            (!target.startsWith("http://", ignoreCase = true) &&
                !target.startsWith("https://", ignoreCase = true))
        ) {
            return@withContext PageContent(url, "", "", "无效地址")
        }

        try {
            val request = Request.Builder()
                .url(target)
                .addHeader("User-Agent", BROWSER_UA)
                .addHeader(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7"
                )
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
                .build()

            // 每次调用按调用方给的预算重新配超时。newBuilder() 复用同一个连接池/线程池，
            // 不产生额外开销。三层超时都给满：任一环节卡住都能在 timeoutMs 内放弃。
            val callClient = httpClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            callClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext PageContent(target, "", "", "HTTP ${response.code}")
                }

                val contentType = response.body?.contentType()
                val mime = contentType?.let { "${it.type}/${it.subtype}".lowercase() }.orEmpty()
                // 只接受文本类响应：PDF/图片/压缩包解出来只会是乱码，不如直接说明不支持。
                if (mime.isNotEmpty() &&
                    !mime.startsWith("text/") &&
                    mime != "application/xhtml+xml"
                ) {
                    return@withContext PageContent(
                        target,
                        "",
                        "",
                        "不是文本页面（Content-Type: $mime）"
                    )
                }

                val bytes = readBytesAtMost(response.body, MAX_BODY_BYTES)
                sniffNonText(bytes)?.let { reason ->
                    return@withContext PageContent(target, "", "", reason)
                }

                val (html, charsetName) = decodeWithBestCharset(bytes, contentType?.charset(null))
                if (html.isBlank()) {
                    return@withContext PageContent(target, "", "", "空响应")
                }
                android.util.Log.i(
                    "TreeChatExtract",
                    "抓取 ${target.take(80)} 成功（${bytes.size} 字节，字符集 $charsetName）"
                )

                val capped = html.take(MAX_HTML_CHARS)
                val title = extractTitle(capped)
                val text = extractMainText(cleanHtml(capped))
                if (text.isBlank()) {
                    return@withContext PageContent(target, title, "", "页面未抽取到正文")
                }
                return@withContext PageContent(target, title, truncate(text, maxChars), null)
            }
        } catch (cancel: CancellationException) {
            // 协程被取消（用户停止 / 外层整体预算到点）必须原样上抛，
            // 否则调用方的 withTimeoutOrNull 会失效。
            throw cancel
        } catch (e: Exception) {
            PageContent(target, "", "", describeException(e))
        }
    }

/**
 * 抽取单页正文用的客户端：**只**管连接复用与重定向，真正的超时在每次调用时按预算覆盖。
 * 顶层 `val` 用 `by lazy` 只建一次，避免每个网页都新建一个连接池。
 */
private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

// ------------------------------------------------------------------ 响应读取与嗅探

/** 最多读 [limit] 字节：防止某些站点返回无限流或超大页把内存吃光。 */
private fun readBytesAtMost(body: ResponseBody?, limit: Int): ByteArray {
    if (body == null) return ByteArray(0)
    val input = body.byteStream()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (total < limit) {
        val read = input.read(buffer)
        if (read < 0) break
        val keep = minOf(read, limit - total)
        if (keep > 0) out.write(buffer, 0, keep)
        total += read
    }
    return out.toByteArray()
}

/**
 * 识别"这根本不是文本页面"。
 *
 * Content-Type 可能缺失或撒谎（有些站点整站返回 `text/html` 实际是下载文件），
 * 所以在解码前再做一次**魔数 + NUL 字节**嗅探，避免把 PDF/图片当网页。
 * 带 BOM 的 UTF-16 文本天然含 NUL，因此先放行 BOM。
 */
private fun sniffNonText(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return "空响应"
    if (bomCharset(bytes) != null) return null

    val head = String(bytes, 0, minOf(bytes.size, 8), Charsets.ISO_8859_1)
    if (head.startsWith("%PDF")) return "不是文本页面（PDF）"
    if (head.startsWith("PK\u0003\u0004")) return "不是文本页面（压缩包或 Office 文档）"
    if (head.startsWith("\u0089PNG") || head.startsWith("GIF8") ||
        head.startsWith("\u00FF\u00D8\u00FF") || head.startsWith("RIFF") ||
        head.startsWith("OggS") || head.startsWith("fLaC")
    ) {
        return "不是文本页面（二进制文件）"
    }
    val probe = minOf(bytes.size, 5120)
    for (i in 0 until probe) {
        if (bytes[i] == 0.toByte()) return "不是文本页面（二进制内容）"
    }
    return null
}

private fun bomCharset(bytes: ByteArray): Charset? = when {
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
    else -> null
}

// ------------------------------------------------------------------ 字符集判定

/** 从 HTML 头部（前 4KB 的 ASCII 视图）里找 `<meta charset>` 或 `http-equiv` 里的 charset。 */
private val META_CHARSET_RE = Regex(
    """<meta[^>]*?charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""",
    RegexOption.IGNORE_CASE
)

private fun metaCharsetName(bytes: ByteArray): String? {
    val prefix = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
    return META_CHARSET_RE.find(prefix)?.groupValues?.get(1)?.trim()
}

/** 严格解码：字节流里只要有非法序列就返回 `null`，交给下一个候选字符集。 */
private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .removePrefix("\uFEFF")
} catch (e: CharacterCodingException) {
    null
}

/**
 * 按"BOM → 响应头 → meta → UTF-8 → GB18030 → ISO-8859-1"顺序做**严格解码**，
 * 返回第一个成功的 `(正文, 字符集名)`。ISO-8859-1 永不失败，所以一定会返回值。
 */
private fun decodeWithBestCharset(bytes: ByteArray, headerCharset: Charset?): Pair<String, String> {
    val candidates = LinkedHashMap<String, Charset>()
    fun offer(charset: Charset?) {
        if (charset == null) return
        val name = charset.name()
        if (!candidates.containsKey(name)) candidates[name] = charset
    }
    offer(bomCharset(bytes))
    offer(headerCharset)
    metaCharsetName(bytes)?.let { name ->
        offer(runCatching { Charset.forName(name) }.getOrNull())
    }
    listOf("UTF-8", "GB18030", "ISO-8859-1").forEach { name ->
        offer(runCatching { Charset.forName(name) }.getOrNull())
    }

    for ((name, charset) in candidates) {
        strictDecode(bytes, charset)?.let { return it to name }
    }
    return String(bytes, Charsets.ISO_8859_1) to "ISO-8859-1"
}

// ------------------------------------------------------------------ HTML 清理与抽取

/** 一个 `<p>` 段落在源 HTML 中的位置与纯文本（用于找"连续最长区块"）。 */
private class Para(val start: Int, val end: Int, val text: String)

private val COMMENT_RE = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val DROP_BLOCK_RE = Regex(
    "<(script|style|noscript|svg|template|iframe|head)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val DROP_SEMANTIC_RE = Regex(
    "<(nav|aside|footer|header)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val DROP_JUNK_CONTAINER_RE = Regex(
    "<(div|section)\\b[^>]*(?:class|id)\\s*=\\s*[\"'][^\"']*" +
        "(?:nav|menu|sidebar|side-bar|comment|footer|header|\\bads?\\b|advert|cookie|" +
        "share|related|recommend|breadcrumb|pagination|banner)[^\"']*[\"'][^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val BODY_RE = Regex(
    "<body\\b[^>]*>(.*?)</body\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val TITLE_RE = Regex(
    "<title[^>]*>(.*?)</title\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val PARAGRAPH_RE = Regex(
    "<p\\b[^>]*>(.*?)</p\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** 块级标签边界：转成换行，保证段落不会糊成一坨。 */
private val BLOCK_BOUNDARY_RE = Regex(
    "<\\s*/?\\s*(?:p|div|br|li|ul|ol|tr|td|th|table|h[1-6]|section|article|blockquote|" +
        "pre|hr|figure|figcaption|main|dl|dt|dd)\\b[^>]*>",
    RegexOption.IGNORE_CASE
)
private val TAG_RE = Regex("<[^>]*>", RegexOption.DOT_MATCHES_ALL)

/** 行内空白（含全角空格与 `&nbsp;` 解出的 NBSP），用于折行前的压缩。 */
private val INLINE_WS_RE = Regex("[ \\t\\u000B\\f\\r\\u00A0\\u3000]+")

/** 删除整段非内容块（顺序：注释 → script/style 等 → 语义化页眉页脚 → 垃圾容器）。 */
private fun cleanHtml(html: String): String {
    var cleaned = COMMENT_RE.replace(html, " ")
    cleaned = DROP_BLOCK_RE.replace(cleaned, " ")
    cleaned = DROP_SEMANTIC_RE.replace(cleaned, " ")
    cleaned = DROP_JUNK_CONTAINER_RE.replace(cleaned, " ")
    return cleaned
}

private fun extractTitle(html: String): String =
    TITLE_RE.find(html)?.groupValues?.get(1)
        ?.let { normalizeInline(it) }
        .orEmpty()
        .take(TITLE_MAX_CHARS)

/**
 * 正文抽取主流程：优先用"累计最长的连续 `<p>` 区块"，不可靠时退回整页文本。
 */
private fun extractMainText(cleanedHtml: String): String {
    val body = BODY_RE.find(cleanedHtml)?.groupValues?.get(1) ?: cleanedHtml

    val paras = ArrayList<Para>()
    for (match in PARAGRAPH_RE.findAll(body)) {
        val text = normalizeInline(match.groupValues[1])
        if (text.length >= MIN_PARAGRAPH_CHARS) {
            paras.add(Para(match.range.first, match.range.last, text))
        }
    }

    if (paras.isNotEmpty()) {
        var bestStart = 0
        var bestEnd = 0
        var bestLen = 0
        var curStart = 0
        var curLen = 0
        for (i in paras.indices) {
            if (i > 0 && paras[i].start - paras[i - 1].end > MAX_PARAGRAPH_GAP) {
                if (curLen > bestLen) {
                    bestLen = curLen
                    bestStart = curStart
                    bestEnd = i - 1
                }
                curStart = i
                curLen = 0
            }
            curLen += paras[i].text.length
        }
        if (curLen > bestLen) {
            bestLen = curLen
            bestStart = curStart
            bestEnd = paras.size - 1
        }
        if (bestLen >= MIN_MAIN_TEXT_CHARS) {
            return paras.subList(bestStart, bestEnd + 1).joinToString("\n\n") { it.text }
        }
        if (paras.size == 1 && paras[0].text.length >= MIN_MAIN_TEXT_CHARS) {
            return paras[0].text
        }
    }

    // 退回整页：有些资讯页/博客把正文直接铺在 div 里，没有 <p>。
    return normalizeBlockText(body)
}

/** 单行文本（标题、`<p>` 内容）：去标签、解实体、把空白压成单空格。 */
private fun normalizeInline(raw: String): String {
    if (raw.isEmpty()) return ""
    val noTags = TAG_RE.replace(raw, " ")
    return INLINE_WS_RE.replace(HtmlSearchResultParser.decodeEntities(noTags), " ").trim()
}

/** 整页文本：块级标签先转换行，再逐行去标签/解实体/折空白，并保留段落间单个空行。 */
private fun normalizeBlockText(html: String): String {
    val withBreaks = BLOCK_BOUNDARY_RE.replace(html, "\n")
    val noTags = TAG_RE.replace(withBreaks, " ")
    return collapseLines(HtmlSearchResultParser.decodeEntities(noTags))
}

private fun collapseLines(raw: String): String {
    val out = StringBuilder()
    var pendingBlank = false
    for (line in raw.split('\n')) {
        val trimmed = INLINE_WS_RE.replace(line, " ").trim()
        if (trimmed.isEmpty()) {
            if (out.isNotEmpty()) pendingBlank = true
            continue
        }
        if (out.isNotEmpty()) out.append(if (pendingBlank) "\n\n" else "\n")
        pendingBlank = false
        out.append(trimmed)
    }
    return out.toString()
}

/** 截断到 [maxChars]，并在末尾注明被截断（模型据此知道内容不完整）。 */
private fun truncate(text: String, maxChars: Int): String {
    if (maxChars <= 0 || text.length <= maxChars) return text
    return text.take(maxChars) + TRUNCATION_MARK
}

/** 把异常翻译成用户/模型看得懂的一句原因。绝不在这里编造内容。 */
private fun describeException(e: Exception): String = when (e) {
    is SocketTimeoutException -> "超时"
    is InterruptedIOException -> "超时"
    is java.net.UnknownHostException -> "域名无法解析"
    is java.net.ConnectException -> "连接被拒绝"
    is javax.net.ssl.SSLException -> "TLS 握手失败"
    is IllegalArgumentException -> "地址格式不合法"
    is java.io.IOException -> "网络错误：${e.message ?: e::class.java.simpleName}"
    else -> e.message ?: e::class.java.simpleName
}
