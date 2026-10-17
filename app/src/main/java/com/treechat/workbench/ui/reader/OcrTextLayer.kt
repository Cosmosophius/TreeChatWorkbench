package com.treechat.workbench.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.treechat.workbench.ui.selection.MessageSelectionController
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 扫描页的「文字层」：把 OCR 的词盒重建成**可选文本 + 逐字几何**。
 *
 * ## 为什么需要它
 * 词盒（[OcrWordBox]）只有"一整块的框 + 一段文字"，它答不出"第 7 个字在哪"。
 * 而对话界面的选词核心（`MessageSelectionController`）整套都建立在
 * "字符下标 ↔ 矩形"这个双向映射上：选区是字符区间，命中是点→下标，
 * 手柄是下标→矩形，高亮是区间→逐字底色。
 *
 * 缺了这个映射，盒模型上就只能"整盒进、整盒出"：
 * 点一下选一整块、拖动手柄只能拖外接矩形、永远选不到半个词。
 * 这正是"OCR 无法像对话界面那样选字"的结构性原因。
 *
 * ## 做法
 * 把词盒补成 **行 → 词 → 字** 的层级：一个词盒的宽度按字数均分给每个字，
 * 得到逐字矩形。汉字近等宽，这个近似基本成立；西文是"在词内均分"，
 * 误差被限制在单个词内部，不会跨词累积。有了逐字几何，选词就能与对话界面同构。
 *
 * ## 与"不可见 PDF 文字层"的关系
 * 开源界（OCRmyPDF / scanlayer / uncial-kmp）与 macOS 预览的做法都是
 * "先把 OCR 结果落成文字层，再从文字层取几何"。区别只在于：
 * 它们是把这个层写回 PDF（render mode 3）再读回来，我们这里是**内存模型**，
 * 省掉重写/重渲染一遍 PDF 的开销；代价是几何精度取决于上面那条均分近似。
 */
internal class OcrTextLayer(
    val text: String,
    private val charRects: List<Rect>,
    private val charLines: IntArray,
    val lineCount: Int,
    /**
     * 每个字符来自**哪个词盒**（下标对应传入 [buildOcrTextLayer] 的原始列表）。
     *
     * `-1` 表示这个字符是我们自己插进去的分隔符（行间的 `\n`、词间的空格），
     * 它不对应任何词盒。
     *
     * ## 为什么必须有它
     *
     * 同文本超链接要在 OCR 页面上标出"哪几个词盒被引用过"。
     * 早先的做法是在浮层里**另写一套**拼接（按行把词盒首尾相连、不带分隔符）
     * 再去找引用文本 —— 而选区文本来自本层的 [text]（带空格与换行）。
     * 两套拼法不同源，凡是跨词、跨行的引用**必然匹配不上**，
     * 表现就是"提问后页面上一点标记都没有"。
     *
     * 有了这张表，链接匹配就能直接在本层的 [text] 上做，
     * 与选区**同一个字符串**，从此不可能对不上。
     */
    private val charBoxIndex: IntArray = IntArray(text.length) { -1 }
) {
    val isEmpty: Boolean get() = text.isEmpty()

    /** 取字符区间对应的文本。 */
    fun textOf(range: IntRange): String {
        if (text.isEmpty()) return ""
        val a = range.first.coerceIn(0, text.length)
        val b = (range.last + 1).coerceIn(a, text.length)
        return text.substring(a, b)
    }

    /**
     * 字符区间覆盖到的**词盒下标集合**（相对传入 [buildOcrTextLayer] 的原始列表）。
     *
     * 插入的分隔符（`-1`）自动跳过 —— 它们没有对应的词盒，
     * 标链接时不该凭空多出一个高亮。
     */
    fun boxIndexesIn(range: IntRange): Set<Int> {
        if (text.isEmpty()) return emptySet()
        val a = range.first.coerceIn(0, text.length - 1)
        val b = range.last.coerceIn(a, text.length - 1)
        val out = LinkedHashSet<Int>()
        for (i in a..b) {
            val idx = charBoxIndex.getOrNull(i) ?: -1
            if (idx >= 0) out.add(idx)
        }
        return out
    }

    /**
     * 点 → 字符下标；点不到任何字（真空白）返回 null。
     *
     * 两级判定：先看是否直接压在某个字框里；压不中（指头有肉、压在两字缝里）
     * 就按"到字框的矩形距离"取最近的一个，且纵向权重更高 ——
     * 免得光标停在两行之间时被隔壁行抢走。
     */
    fun indexAt(p: Offset): Int? {
        if (isEmpty) return null
        for (i in text.indices) {
            val r = charRects[i]
            if (r.width > 0f && r.contains(p)) return i
        }
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in text.indices) {
            val r = charRects[i]
            if (r.width <= 0f) continue
            val dx = when {
                p.x < r.left -> r.left - p.x
                p.x > r.right -> p.x - r.right
                else -> 0f
            }
            val dy = when {
                p.y < r.top -> r.top - p.y
                p.y > r.bottom -> p.y - r.bottom
                else -> 0f
            }
            val score = dy * 4f + dx
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) return null
        // 容差约两个字高：再远就认为用户点的是空白，不该硬认一个字。
        val limit = max(charRects[best].height, 12f) * 2.2f
        return if (bestScore <= limit) best else null
    }

    /**
     * 下标 → 所在词的区间（**闭区间**）。
     *
     * 复用对话界面那一个分词函数（[MessageSelectionController.wordBounds]：
     * 西文取整串、汉字走 ICU 词典），保证"点一个词"在两边的边界完全一致。
     * 另外强制**不跨行**：识别出的换行处必须断词。
     */
    fun wordRange(index: Int): IntRange {
        if (isEmpty) return 0..0
        val i = index.coerceIn(0, text.length - 1)
        val bounds = runCatching { MessageSelectionController.wordBounds(text, i) }
            .getOrDefault(i to (i + 1))
        var start = bounds.first.coerceIn(0, text.length)
        var endExclusive = bounds.second.coerceIn(start, text.length)
        val line = charLines.getOrElse(i) { 0 }
        while (start < endExclusive && charLines.getOrElse(start) { line } != line) start++
        while (endExclusive > start && charLines.getOrElse(endExclusive - 1) { line } != line) endExclusive--
        if (endExclusive <= start) {
            start = i
            endExclusive = min(i + 1, text.length)
        }
        return start..(endExclusive - 1).coerceAtLeast(start)
    }

    /**
     * 选区的高亮矩形：**同一行里连续的字合并成一条**。
     *
     * 这是文本选择的经典画法（一行一条横条），也是对话界面高亮的形状。
     * 逐字画矩形会在字与字之间留下细缝，看起来像一排碎块。
     */
    fun rectsFor(range: IntRange): List<Rect> {
        if (isEmpty) return emptyList()
        val a = range.first.coerceIn(0, text.length - 1)
        val b = range.last.coerceIn(a, text.length - 1)
        val out = ArrayList<Rect>()
        var cur: Rect? = null
        var curLine = Int.MIN_VALUE
        for (i in a..b) {
            val r = charRects[i]
            if (r.width <= 0f) continue
            val line = charLines.getOrElse(i) { 0 }
            val prev = cur
            if (prev == null || line != curLine) {
                if (prev != null) out.add(prev)
                cur = r
                curLine = line
            } else {
                cur = Rect(
                    left = min(prev.left, r.left),
                    top = min(prev.top, r.top),
                    right = max(prev.right, r.right),
                    bottom = max(prev.bottom, r.bottom)
                )
            }
        }
        cur?.let { out.add(it) }
        return out
    }

    /** 选区外框（工具条定位用）。 */
    fun boundsFor(range: IntRange): Rect? {
        val rs = rectsFor(range)
        if (rs.isEmpty()) return null
        var acc = rs.first()
        for (r in rs.drop(1)) {
            acc = Rect(
                left = min(acc.left, r.left),
                top = min(acc.top, r.top),
                right = max(acc.right, r.right),
                bottom = max(acc.bottom, r.bottom)
            )
        }
        return acc
    }

    /**
     * 选区两端的**水滴手柄**位置：起点在首个字的左下、终点在末个字的右下。
     *
     * 与对话界面同形、同语义（"这两端可以拉"），而不是盒模型那四个外接角 ——
     * 四个角对文本选区是没有意义的，用户不知道该拖哪一个。
     */
    fun handleCenters(range: IntRange): Pair<Offset, Offset>? {
        val rs = rectsFor(range)
        if (rs.isEmpty()) return null
        val first = rs.first()
        val last = rs.last()
        return Offset(first.left, first.bottom) to Offset(last.right, last.bottom)
    }
}

/**
 * 由 OCR 词盒构建文字层。没有任何可用文字时返回 null
 * （此时调用方应退回盒模型或提示"本页还没识别"）。
 */
internal fun buildOcrTextLayer(boxes: List<OcrWordBox>): OcrTextLayer? {
    // 保留**原始下标**：过滤掉的空盒会让下标错位，
    // 而链接匹配要拿这个下标回查原始 boxes 列表。
    val usable = boxes.withIndex().filter { (_, b) ->
        b.text.isNotBlank() && b.bounds.width > 1f && b.bounds.height > 1f
    }.map { (idx, b) -> idx to b }
    if (usable.isEmpty()) return null

    // ① 按纵坐标聚成行（同行判据取行高的 60%，与 pageText 同一套）
    val sorted = usable.sortedWith(compareBy({ it.second.bounds.top }, { it.second.bounds.left }))
    val lines = ArrayList<MutableList<Pair<Int, OcrWordBox>>>()
    for ((idx, b) in sorted) {
        val line = lines.lastOrNull()
        val anchorTop = line?.firstOrNull()?.second?.bounds?.top
        val sameLine = anchorTop != null && line != null &&
            abs(b.bounds.top - anchorTop) <=
            maxOf(b.bounds.height, line.first().second.bounds.height) * 0.6f
        if (sameLine) line.add(idx to b) else lines.add(mutableListOf(idx to b))
    }

    // ② 行内按横坐标排序后拼字符串，同时登记每个字的矩形与来源词盒
    val sb = StringBuilder()
    val rects = ArrayList<Rect>()
    val lineOf = ArrayList<Int>()
    val boxOf = ArrayList<Int>()
    lines.forEachIndexed { lineIndex, raw ->
        val row = raw.sortedBy { it.second.bounds.left }
        if (lineIndex > 0) {
            val top = row.first().second.bounds.top
            // 换行符本身没有几何，给一个零宽矩形（命中判定会跳过零宽）
            sb.append('\n')
            rects.add(Rect(0f, top, 0f, top))
            lineOf.add(lineIndex)
            boxOf.add(-1)
        }
        row.forEachIndexed { i, (boxIdx, box) ->
            val t = box.text.trim()
            if (t.isEmpty()) return@forEachIndexed
            if (i > 0 && needsSpaceBetween(row[i - 1].second.text.trim().lastOrNull(), t.first())) {
                sb.append(' ')
                // 空格用两个盒之间的空隙表示，同样零宽以外的最小宽度
                val gapLeft = row[i - 1].second.bounds.right
                rects.add(Rect(gapLeft, box.bounds.top, box.bounds.left, box.bounds.bottom))
                lineOf.add(lineIndex)
                boxOf.add(-1)
            }
            val n = t.length
            val w = box.bounds.width / n
            t.forEachIndexed { ci, ch ->
                sb.append(ch)
                rects.add(
                    Rect(
                        left = box.bounds.left + w * ci,
                        top = box.bounds.top,
                        right = box.bounds.left + w * (ci + 1),
                        bottom = box.bounds.bottom
                    )
                )
                lineOf.add(lineIndex)
                boxOf.add(boxIdx)
            }
        }
    }
    if (sb.isEmpty()) return null
    return OcrTextLayer(sb.toString(), rects, lineOf.toIntArray(), lines.size, boxOf.toIntArray())
}

/**
 * 词间要不要补一个空格：CJK 相邻不补，只要有一侧是 ASCII（拉丁字母/数字/半角标点）
 * 就补。与 `LiveTextPageView.pageText` 用同一条判据，保证两条路径拼出来的文字一致。
 */
private fun needsSpaceBetween(prev: Char?, next: Char?): Boolean {
    if (prev == null || next == null) return false
    return prev.code < 128 || next.code < 128
}
