package com.treechat.workbench.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

/**
 * 阅读器「句法嵌套切片 + 标注在原文上」的全部算法与绘制。
 *
 * ─────────────────────── 这一层最重要的一条纪律 ───────────────────────
 *
 * **绝不用本地正则/启发式去"补"一棵语法树。** 句法层级只能来自模型，
 * 本文件做的只有三件事：
 *   1. 把模型输出的 JSON 严格解析成 [SyntaxUnit]（解析不出来就如实失败，
 *      并把模型**原样输出**交给界面展示，绝不"猜一个大概的结构"）；
 *   2. 把已解析的单元**按顺序对齐**回原文（文字路径按字符偏移，实况选词
 *      路径按 OCR 词框顺序），对齐不上的单元如实列进 [SentenceToolState.unlocated]，
 *      不隐藏、不伪造位置；
 *   3. 用 Material 3 配色把层级画在原文/词框上（见 [SyntaxUnitBlock]）。
 *
 * 纯 Compose + Material 3，无 emoji。
 */

/** 一个句法单元。[level] 越小越靠外层：0 = 主句，1 = 直接嵌套于主句的从句/短语。 */
data class SyntaxUnit(
    val text: String,
    val level: Int,
    val role: String
)

/** level 的封顶值：再深的嵌套也按最深一档上色，避免出现无色的层级。 */
internal const val MAX_SYNTAX_LEVEL = 4

// ============================================================================
// 1. 解析：模型输出 → 结构化单元（严格，失败即失败）
// ============================================================================

/** 句法解析的结果。**只有 [Parsed] 才允许被画到原文上。** */
internal sealed interface SyntaxParseResult {
    /** 解析成功。 */
    data class Parsed(val units: List<SyntaxUnit>) : SyntaxParseResult

    /** 解析失败：给出可读原因 + 模型原样输出（界面必须把它照实显示出来）。 */
    data class Failed(val reason: String, val raw: String) : SyntaxParseResult
}

/**
 * 严格解析模型返回的句法 JSON。
 *
 * 唯一"宽容"的一步是剥掉 Markdown 代码块围栏与首尾空白 —— 那是**包装**，
 * 不是结构；结构本身（数组、每个元素的 text/level/role）一律严格校验，
 * 任何一项不合规都整份判失败，不做"跳过坏元素"的妥协（跳过会让界面画出一个
 * 看起来完整、实际缺斤少两的层级图，比诚实报错更糟）。
 */
internal fun parseSyntaxAnalysis(raw: String): SyntaxParseResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return SyntaxParseResult.Failed("模型没有返回任何内容", raw)

    val slice = extractJsonArray(trimmed)
        ?: return SyntaxParseResult.Failed(
            "模型输出里找不到 JSON 数组（期望形如 [ {\"text\":…,\"level\":0,\"role\":…} ]）",
            raw
        )

    val array = try {
        JSONArray(slice)
    } catch (e: Exception) {
        return SyntaxParseResult.Failed(
            "JSON 解析失败：${e.message ?: "格式不合法"}",
            raw
        )
    }
    if (array.length() == 0) {
        return SyntaxParseResult.Failed("JSON 数组是空的，没有切分出任何句法单元", raw)
    }

    val units = ArrayList<SyntaxUnit>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i)
            ?: return SyntaxParseResult.Failed(
                "第 ${i + 1} 个元素不是 JSON 对象（需要 text / level / role 三个字段）",
                raw
            )
        val text = obj.optString("text").trim()
        val role = obj.optString("role").trim()
        if (text.isEmpty()) return SyntaxParseResult.Failed("第 ${i + 1} 个元素的 text 是空的", raw)
        if (role.isEmpty()) return SyntaxParseResult.Failed("第 ${i + 1} 个元素的 role 是空的", raw)
        if (!obj.has("level")) return SyntaxParseResult.Failed("第 ${i + 1} 个元素缺少 level 字段", raw)
        val level = obj.optInt("level", -1)
        if (level < 0) {
            return SyntaxParseResult.Failed("第 ${i + 1} 个元素的 level 不是非负整数", raw)
        }
        units.add(SyntaxUnit(text = text, level = level.coerceAtMost(MAX_SYNTAX_LEVEL), role = role))
    }
    return SyntaxParseResult.Parsed(units)
}

/** 取出最外层 JSON 数组：先剥代码块围栏，再取第一个 `[` 到最后一个 `]`。 */
private fun extractJsonArray(text: String): String? {
    var body = text
    if (body.startsWith("```")) {
        // ```json ... ``` / ``` ... ```
        body = body.removePrefix("```").trimStart()
        val firstNewline = body.indexOf('\n')
        if (firstNewline >= 0 && firstNewline < 12) {
            body = body.substring(firstNewline + 1)
        }
        val fenceEnd = body.lastIndexOf("```")
        if (fenceEnd >= 0) body = body.substring(0, fenceEnd)
    }
    val start = body.indexOf('[')
    val end = body.lastIndexOf(']')
    if (start < 0 || end <= start) return null
    return body.substring(start, end + 1)
}

// ============================================================================
// 2. 对齐：单元 → 原文 / 词框
// ============================================================================

/** 已定位到原文的一段。 */
internal data class LocatedUnit(val start: Int, val end: Int, val unit: SyntaxUnit)

/**
 * 把单元按顺序锚回 [source] 的字符区间。
 *
 * 用游标向前推进（而不是全文 indexOf）：同一个片段在文中出现多次时，
 * 只有顺序推进才能把它落到"这一次说的那一处"。推进不到（模型改写了原文）
 * 的单元原样进第二返回值，由界面如实列出。
 */
internal fun locateUnits(
    source: String,
    units: List<SyntaxUnit>
): Pair<List<LocatedUnit>, List<SyntaxUnit>> {
    if (source.isEmpty() || units.isEmpty()) return emptyList<LocatedUnit>() to units.toList()
    val located = ArrayList<LocatedUnit>(units.size)
    val unlocated = ArrayList<SyntaxUnit>()
    var cursor = 0
    for (unit in units) {
        if (unit.text.isEmpty()) {
            unlocated.add(unit)
            continue
        }
        val fromCursor = source.indexOf(unit.text, cursor)
        val index = if (fromCursor >= 0) fromCursor else source.indexOf(unit.text)
        if (index < 0) {
            unlocated.add(unit)
            continue
        }
        located.add(LocatedUnit(index, index + unit.text.length, unit))
        cursor = index + unit.text.length
    }
    return located to unlocated
}

/** 一个词条在句法结果里的归属。 */
internal data class WordMark(
    val unitIndex: Int,
    val level: Int,
    val role: String,
    /** 是否是该单元命中的第一个词（角色小标签只挂在首词上，避免刷屏）。 */
    val isFirstOfUnit: Boolean
)

/**
 * 把句法单元按顺序对齐到**已按阅读顺序排好的**词条文本上。
 *
 * 判据是"去掉空白后前缀匹配"：OCR/原文里词与词之间可能有空格，而模型给出的
 * 片段通常按原文抄写，去掉空白后能一一对上。对不上的单元整份放弃（返回 null），
 * 不做近似匹配 —— 近似匹配会把标注画到不相干的词上。
 */
internal fun alignUnitsToWords(units: List<SyntaxUnit>, words: List<String>): List<WordMark?> {
    val marks = arrayOfNulls<WordMark>(words.size)
    if (units.isEmpty() || words.isEmpty()) return marks.toList()
    var w = 0
    units.forEachIndexed { unitIndex, unit ->
        val target = stripSpace(unit.text)
        if (target.isEmpty()) return@forEachIndexed
        val startWord = w
        val acc = StringBuilder()
        var matched = false
        while (w < words.size) {
            acc.append(stripSpace(words[w]))
            val current = acc.toString()
            if (current.isNotEmpty() && target.startsWith(current)) {
                matched = true
                w++
                if (current.length >= target.length) break
            } else {
                break
            }
        }
        if (!matched) {
            w = startWord
            return@forEachIndexed
        }
        for (i in startWord until w) {
            marks[i] = WordMark(unitIndex, unit.level, unit.role, i == startWord)
        }
    }
    return marks.toList()
}

private fun stripSpace(s: String): String = s.filterNot { it.isWhitespace() }

// ============================================================================
// 3. 实况选词路径：词框上的标注
// ============================================================================

/** 实况选词路径要画在某个词框上的标注。 */
data class SyntaxBoxMark(
    val boxId: String,
    val level: Int,
    val role: String,
    /** 是否在该框旁边显示角色小标签。 */
    val showRoleLabel: Boolean
)

/**
 * 由（内容坐标的）词框与句法单元算出每个词框的标注。
 *
 * 排序判据（先上后左）与 [pageText] 还原文字层时**同一套**，否则"文字层的顺序"
 * 与"标注的顺序"会错位，表现为整段标注整体往前/往后挪一个词。
 */
internal fun buildSyntaxBoxMarks(
    boxes: List<OcrWordBox>,
    units: List<SyntaxUnit>
): List<SyntaxBoxMark> {
    if (boxes.isEmpty() || units.isEmpty()) return emptyList()
    val order = boxes.indices.sortedWith(
        compareBy({ boxes[it].bounds.top }, { boxes[it].bounds.left })
    )
    val marks = alignUnitsToWords(units, order.map { boxes[it].text })
    val out = ArrayList<SyntaxBoxMark>()
    order.forEachIndexed { position, boxIndex ->
        val mark = marks[position] ?: return@forEachIndexed
        out.add(
            SyntaxBoxMark(
                boxId = boxes[boxIndex].id,
                level = mark.level,
                role = mark.role,
                showRoleLabel = mark.isFirstOfUnit
            )
        )
    }
    return out
}

// ============================================================================
// 4. 配色：层级用色深浅 + 左侧层级竖线
// ============================================================================

/** 某一层级的色相。0..3 各占一个 M3 角色色，>3 循环回来。 */
internal fun syntaxLevelHue(scheme: androidx.compose.material3.ColorScheme, level: Int): Color {
    val safe = (level.coerceAtLeast(0)) % 4
    return when (safe) {
        0 -> scheme.primary
        1 -> scheme.secondary
        2 -> scheme.tertiary
        else -> scheme.inversePrimary
    }
}

/** 某一层级的底色：层级越深越淡，避免深层嵌套把正文压得看不清。 */
internal fun syntaxLevelFill(scheme: androidx.compose.material3.ColorScheme, level: Int): Color {
    val depth = level.coerceIn(0, MAX_SYNTAX_LEVEL)
    val alpha = (0.30f - depth * 0.05f).coerceAtLeast(0.10f)
    return syntaxLevelHue(scheme, level).copy(alpha = alpha)
}

// ============================================================================
// 5. 现场状态：当前被标注的选区
// ============================================================================

/**
 * 阅读器里的「选句翻译 / 句法标注」现场状态（一次只服务一个选区）。
 *
 * 带上 [documentId] 与 [pageIndex]：标注只画在**产生它的那一页**上。少了这两个
 * 字段，翻到别的页时同一句话会被"蹭"上标注，用户会以为程序乱标。
 */
class SentenceToolState {
    /** 产生本次标注的选区原文。 */
    var sourceText by mutableStateOf("")

    /** 产生本次标注的文档 id（空表示当前没有标注）。 */
    var documentId by mutableStateOf("")

    /** 产生本次标注的 0 基页号。 */
    var pageIndex by mutableIntStateOf(-1)

    /** 已解析成功的句法单元（空表示当前没有可画的标注）。 */
    var units by mutableStateOf<List<SyntaxUnit>>(emptyList())

    /** 未能定位回原文的单元（如实列出，不隐藏）。 */
    var unlocated by mutableStateOf<List<SyntaxUnit>>(emptyList())

    /** 是否正在请求模型。 */
    var analyzing by mutableStateOf(false)

    /** 最近一次分析失败的原因（非空表示上一次分析没成功）。 */
    var failureReason by mutableStateOf<String?>(null)

    val hasAnnotation: Boolean get() = units.isNotEmpty()

    /** 开始一次新的分析：清掉旧标注，进入"分析中"。 */
    fun begin(documentId: String, pageIndex: Int, sourceText: String) {
        this.documentId = documentId
        this.pageIndex = pageIndex
        this.sourceText = sourceText
        units = emptyList()
        unlocated = emptyList()
        failureReason = null
        analyzing = true
    }

    fun succeed(units: List<SyntaxUnit>, unlocated: List<SyntaxUnit>) {
        this.units = units
        this.unlocated = unlocated
        failureReason = null
        analyzing = false
    }

    fun fail(reason: String) {
        units = emptyList()
        unlocated = emptyList()
        failureReason = reason
        analyzing = false
    }

    fun clear() {
        sourceText = ""
        documentId = ""
        pageIndex = -1
        units = emptyList()
        unlocated = emptyList()
        failureReason = null
        analyzing = false
    }
}

// ============================================================================
// 6. 绘制：文字路径（原文段落上按层级叠加）
// ============================================================================

/**
 * 把一段原文按句法层级渲染出来。
 *
 * 视觉方案（三件套，共同表达"这一片属于第几层"）：
 *   1. **左侧层级竖线**：竖线左缩进 = level × 12dp，颜色 = 该层级色相；
 *   2. **底色**：该片文字铺一层该层级色相的淡底（越深越淡）；
 *   3. **下划线**：该片文字下沿一条 1.5dp 的层级色实线；
 *   4. **role 小标签**：在该片文字上方以 labelSmall 标出角色名。
 *
 * 未被任何单元覆盖的原文照常按普通正文渲染 —— 保证"原文一字不改、一字不丢"。
 */
@Composable
internal fun SyntaxAnnotatedText(
    source: String,
    units: List<SyntaxUnit>,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val located = remember(source, units) { locateUnits(source, units).first }

    Column(modifier = modifier) {
        var cursor = 0
        located.forEach { block ->
            if (block.start > cursor) {
                Text(
                    text = source.substring(cursor, block.start),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 24.sp,
                        letterSpacing = 0.3.sp
                    ),
                    color = scheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            SyntaxUnitBlock(unit = block.unit, modifier = Modifier.padding(vertical = 2.dp))
            cursor = maxOf(cursor, block.end)
        }
        if (cursor < source.length) {
            Text(
                text = source.substring(cursor),
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 24.sp,
                    letterSpacing = 0.3.sp
                ),
                color = scheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/** 单个句法单元：层级竖线 + 淡底 + 下划线 + 角色标签。 */
@Composable
private fun SyntaxUnitBlock(unit: SyntaxUnit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val hue = syntaxLevelHue(scheme, unit.level)
    val fill = syntaxLevelFill(scheme, unit.level)
    val indent = (unit.level.coerceIn(0, MAX_SYNTAX_LEVEL) * 12).dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 层级竖线：越深越细，视觉上"越往里越轻"
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(hue, RoundedCornerShape(1.5.dp))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .background(fill, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = "L${unit.level} · ${unit.role}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = hue
            )
            Text(
                text = unit.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 24.sp,
                    letterSpacing = 0.3.sp
                ),
                color = scheme.onSurface,
                modifier = Modifier.drawBehind {
                    val stroke = 1.5.dp.toPx()
                    drawLine(
                        color = hue,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke
                    )
                }
            )
        }
    }
}

// ============================================================================
// 7. 失败窗口：句法解析失败时如实展示模型原样输出
// ============================================================================

/**
 * 「句法解析失败」小窗内容。
 *
 * 纪律：这里**一个字都不加工** —— 只说清为什么判失败，然后把模型的原始输出
 * 原样贴出来。不截断成"看起来像结构"的假象，也不用正则硬抽一棵树。
 */
@Composable
fun SyntaxFailureWindowContent(
    reason: String,
    rawModelOutput: String,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "未能切分句法结构",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.error
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "原因：$reason",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "没有在原文上画任何标注（宁可不出图，也不画一张猜出来的层级图）。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "模型原样输出（未作任何加工）",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = rawModelOutput.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp
                ),
                color = scheme.onSurface,
                modifier = Modifier.padding(8.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(rawModelOutput))
                copied = true
            }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(if (copied) "已复制" else "复制原样输出")
            }
        }
    }
}
