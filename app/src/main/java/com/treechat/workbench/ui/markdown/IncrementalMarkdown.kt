package com.treechat.workbench.ui.markdown

/**
 * 流式渲染的**增量 Markdown 解析器**。
 *
 * ## 解决什么问题
 * 流式期间文本每 16ms 增长一次。旧实现是 `remember(markdown) { parse(整篇) }`，
 * 于是每帧都把**整篇**重新解析一遍：三千字的回答 ≈ 每秒重解析三千字 × 60 次，
 * 再叠加全量重新排版，模拟器实测每帧耗时从 13ms 恶化到 **500ms**（≈ 2 FPS），
 * 表现就是"吐字一顿一顿、越到后面越卡"。
 *
 * ## 怎么解决
 * Markdown 的块是**由空行分隔**的，所以一篇正在增长的文本可以切成：
 *   - **稳定前缀**：最后一个（不在代码围栏内的）空行之前的部分 —— 这些块已经写完，
 *     解析结果**缓存下来原样复用**；
 *   - **尾巴**：空行之后的部分 —— 每帧只重新解析这一小段。
 *
 * 单帧代价从 O(全文) 降到 O(尾巴)，且已解析的块对象**按引用复用** ——
 * 上层据此可以跳过它们的位置快照重建（见 SelectableMarkdown 的注册逻辑）。
 *
 * ## 边界与保守回退
 * - 代码围栏（``` / ~~~）内部的空行**不算**块边界：围栏未闭合时空行只是代码里的一行；
 * - 文本出现 `\r`（会被解析器归一化、导致偏移错位）、或不再是"只追加"（重新生成、
 *   切换消息）时，**整体重来**——正确性优先，宁可多花一帧也不给错位留机会。
 *
 * 本类**不是线程安全的**：它只在 Compose 的合成线程里被调用。
 */
internal class IncrementalMarkdownParser {

    /** 上一帧看到的完整文本；用于判断"是否只追加"。 */
    private var lastText = ""

    /** 行扫描进度：已处理到哪个字符（总是停在某个换行符之后）。 */
    private var scannedUpTo = 0

    /** 当前是否处于代码围栏内部。 */
    private var inFence = false

    /** 稳定前缀的长度：最后一个"围栏外的空行"之后的位置。只增不减。 */
    private var boundary = 0

    /** 已经并入 [prefixBlocks] 的源长度。 */
    private var parsedUpTo = 0

    /** 稳定前缀的解析结果（按引用复用，不随帧重建）。 */
    private var prefixBlocks: List<MdRichBlock> = emptyList()

    /** 上一次的完整结果，供文本未变时直接返回。 */
    private var cached: List<MdRichBlock> = emptyList()

    fun blocks(text: String): List<MdRichBlock> {
        if (text == lastText) return cached

        // 非追加式变化、或含 \r（会被解析器归一化成 \n，导致块偏移整体错位）→ 保守重来
        if (!text.startsWith(lastText) || text.indexOf('\r') >= 0) {
            reset()
        }
        lastText = text

        advanceScan(text)

        // 前缀前进：只解析"新变为稳定"的那一段，追加到缓存
        if (boundary > parsedUpTo) {
            val fragment = text.substring(parsedUpTo, boundary)
            val added = parseMarkdownWithOffsets(fragment).map { it.shiftBy(parsedUpTo) }
            prefixBlocks = if (prefixBlocks.isEmpty()) added else prefixBlocks + added
            parsedUpTo = boundary
        }

        // 尾巴：每帧重解析这一小段（通常只有最后一个块）
        val tail = if (text.length > boundary) {
            parseMarkdownWithOffsets(text.substring(boundary)).map { it.shiftBy(boundary) }
        } else {
            emptyList()
        }

        cached = if (tail.isEmpty()) prefixBlocks else prefixBlocks + tail
        return cached
    }

    private fun reset() {
        lastText = ""
        scannedUpTo = 0
        inFence = false
        boundary = 0
        parsedUpTo = 0
        prefixBlocks = emptyList()
        cached = emptyList()
    }

    /**
     * 扫描 [scannedUpTo] 之后**已换行结束**的整行，更新围栏状态与稳定边界。
     *
     * 只看完整行：末尾那个还没写完的半行留到下次 —— 否则一行的中间被当成块边界，
     * 会把半个块冻结进前缀。
     */
    private fun advanceScan(text: String) {
        var lineStart = scannedUpTo
        // 若是上一帧留下的半行，它的起点要回退到该行开头
        if (lineStart > 0 && text[lineStart - 1] != '\n') {
            val prevNl = text.lastIndexOf('\n', lineStart - 1)
            lineStart = if (prevNl < 0) 0 else prevNl + 1
        }
        var i = lineStart
        var lastSafeBoundary = boundary
        while (i < text.length) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) break // 半行：留到下一帧
            val line = text.substring(i, nl)
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
            } else if (!inFence && line.isBlank()) {
                // 围栏外的空行 = 上一块结束 → 此处之后是稳定前缀
                lastSafeBoundary = nl + 1
            }
            i = nl + 1
        }
        scannedUpTo = i
        if (lastSafeBoundary > boundary) boundary = lastSafeBoundary
    }
}

/** 把片段解析出的块平移到整篇的源坐标上。 */
private fun MdRichBlock.shiftBy(offset: Int): MdRichBlock =
    if (offset == 0) this else copy(
        sourceStart = sourceStart + offset,
        sourceEndExclusive = sourceEndExclusive + offset
    )
