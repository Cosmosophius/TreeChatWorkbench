package com.treechat.workbench.ui.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 阅读器的句法标注卡（大模型切分内核 + 全新交互呈现）。
 *
 * ## 为什么不用参考软件那种"树一摊、句一摊"的画法
 *
 * 参考软件（考研 AI）把「从句层次树」和「原句成分标注」画成两个各自独立的视图：
 * 看树时不知道每个从句压着原句哪几个字，看原句时颜色对不上图例要来回扫。
 * 中间的**对应关系全靠脑补** —— 这是最不直观的一环。
 *
 * 这里改成**点哪联哪**的单卡交互：
 *   · 点树的某一行 → 原句里该单元的片段点亮，其余全部压暗；
 *   · 点原句的某个片段 → 对应的树行浮起（描边 + 提亮），其余行褪色；
 *   · 点图例色点 → 跳到该角色的第一个单元；再点同一处 = 取消聚焦。
 * 结构与原文的映射由交互直接给出，不再需要心算。
 *
 * ## 数据契约（不变）
 * 内核仍是 [SentenceToolState.units]：模型返回的扁平数组
 * `{text(逐字原文), level(0=主句,1=直接嵌套…), role(中文角色)}`，
 * 由 [locateUnits] 按顺序锚回原文下标 —— 层级 + 锚点 = 树。
 * 严格解析：任何字段不合规整份失败；对不回原文的片段照实计数（不伪造位置）。
 */
@Composable
fun SyntaxSentenceCard(
    /** 被分析的整句原文。 */
    sourceSentence: String,
    units: List<SyntaxUnit>,
    /** 未能定位回原文的单元数（照实提示，不隐藏）。 */
    unlocatedCount: Int,
    /** 分析失败原因（非空时卡片退化为提示条）。 */
    failureReason: String?,
    analyzing: Boolean,
    onTranslate: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    Surface(
        color = scheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            when {
                analyzing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在请模型切分句法…",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                failureReason != null -> {
                    Text(
                        text = "句法切分失败：$failureReason",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error
                    )
                }

                units.isEmpty() -> {
                    // 无可画内容：仅保留操作行
                }

                else -> {
                    val located = remember(sourceSentence, units) {
                        locateUnits(sourceSentence, units)
                    }.first
                    /** 当前聚焦的单元（located 列表下标；null = 不聚焦）。 */
                    var focus by remember(sourceSentence, units) {
                        androidx.compose.runtime.mutableStateOf<Int?>(null)
                    }
                    val focused: Int? = focus

                    val tags = remember(sourceSentence, units) {
                        buildSyntaxCardTags(sourceSentence, units)
                    }

                    // ---- 标签链 ----
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text(tags.difficulty, fontSize = 11.sp) })
                        AssistChip(onClick = {}, label = { Text("从句 ${tags.clauseCount}", fontSize = 11.sp) })
                        AssistChip(onClick = {}, label = { Text("嵌套 ${tags.depth} 层", fontSize = 11.sp) })
                    }

                    // ---- 句子骨架：主语+谓语+宾语，逐成分着色 ----
                    val trunkUnits = located.withIndex().filter { it.value.unit.level == 0 }
                    if (trunkUnits.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            CardSectionLabel("主干")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = buildAnnotatedString {
                                    trunkUnits.forEachIndexed { order, (idx, item) ->
                                        val color = syntaxRoleColor(item.unit.role, scheme)
                                        if (order > 0) append("  ")
                                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
                                            append(item.unit.text)
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // ---- 原句成分标注：可点，聚焦时其余压暗 ----
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        CardSectionLabel("原句")
                        Spacer(modifier = Modifier.width(8.dp))
                        SyntaxInteractiveSentence(
                            source = sourceSentence,
                            located = located,
                            focus = focused,
                            onFocusChange = { focus = it }
                        )
                    }

                    // ---- 结构树：行卡式，点行聚焦；聚焦行描边提亮 ----
                    val tree = located.withIndex().filter { it.value.unit.level >= 1 }
                    if (tree.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            CardSectionLabel("结构")
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                tree.forEach { (idx, item) ->
                                    SyntaxTreeRow(
                                        unit = item.unit,
                                        isFocused = focused == idx,
                                        isDimmed = focused != null && focused != idx,
                                        onClick = { focus = if (focused == idx) null else idx }
                                    )
                                }
                            }
                        }
                    }

                    // ---- 图例：点色点跳到该角色第一个单元 ----
                    val roles = units.map { it.role }.distinct()
                    if (roles.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start)
                        ) {
                            roles.forEach { role ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        focus = if (focus != null) null
                                        else located.indexOfFirst { it.unit.role == role }
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .background(
                                                syntaxRoleColor(role, scheme),
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = role,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (unlocatedCount > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "另有 $unlocatedCount 个片段未能与原文逐字对上，未画入上图。",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.outline
                        )
                    }
                }
            }

            // ---- 本段操作（翻译 / 复制 / 收起）----
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTranslate) {
                    Icon(
                        imageVector = Icons.Outlined.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("翻译本段", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    if (units.isNotEmpty()) clipboard.setText(AnnotatedString(sourceSentence))
                }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制本段", fontSize = 12.sp)
                }
                TextButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("收起本段注释", fontSize = 12.sp)
                }
            }
        }
    }
}

/** 卡片分区的小标签（主干 / 原句 / 结构），竖排贴齐首行。 */
@Composable
private fun CardSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 3.dp)
    )
}

/**
 * 可点的整句标注：聚焦单元高亮、其余压暗；点片段切换聚焦。
 */
@Composable
private fun SyntaxInteractiveSentence(
    source: String,
    located: List<LocatedUnit>,
    focus: Int?,
    onFocusChange: (Int?) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var layout by remember { androidx.compose.runtime.mutableStateOf<TextLayoutResult?>(null) }

    val annotated = remember(source, located, focus) {
        buildAnnotatedString {
            append(source)
            located.forEachIndexed { idx, item ->
                val base = syntaxRoleColor(item.unit.role, scheme)
                when {
                    focus == null -> addStyle(
                        SpanStyle(
                            color = base,
                            background = base.copy(alpha = 0.14f),
                            textDecoration = TextDecoration.Underline
                        ),
                        item.start, minOf(item.end, source.length)
                    )
                    focus == idx -> addStyle(
                        SpanStyle(
                            color = base,
                            background = base.copy(alpha = 0.42f),
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline
                        ),
                        item.start, minOf(item.end, source.length)
                    )
                    else -> addStyle(
                        // 非聚焦的压暗：结构一眼可读的关键
                        SpanStyle(color = scheme.onSurface.copy(alpha = 0.32f)),
                        item.start, minOf(item.end, source.length)
                    )
                }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
        color = scheme.onSurface,
        onTextLayout = { layout = it },
        modifier = Modifier
            .pointerInput(located) {
                detectTapGestures { pos ->
                    val l = layout ?: return@detectTapGestures
                    val offset = l.getOffsetForPosition(pos)
                    val hit = located.indexOfFirst {
                        offset >= it.start && offset < it.end
                    }
                    onFocusChange(if (hit >= 0) hit else null)
                }
            }
    )
}

/**
 * 结构树的一行：左层级竖线 + 深度底色 + 引导词着色 + 右端角色签。
 */
@Composable
private fun SyntaxTreeRow(
    unit: SyntaxUnit,
    isFocused: Boolean,
    isDimmed: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val hue = syntaxRoleColor(unit.role, scheme)
    val depth = unit.level.coerceAtLeast(1) - 1
    val bg by animateColorAsState(
        when {
            isFocused -> hue.copy(alpha = 0.26f)
            isDimmed -> hue.copy(alpha = 0.05f)
            else -> hue.copy(alpha = 0.13f - depth * 0.02f)
        },
        label = "syntaxRowBg"
    )

    val clause = unit.text
    val cut = clause.indexOfFirst { it == ' ' }
    val lead = if (cut > 0) clause.substring(0, cut) else clause
    val rest = if (cut > 0) clause.substring(cut) else ""

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp, top = 1.dp, bottom = 1.dp)
            .background(bg, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(start = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .background(hue, RoundedCornerShape(1.5.dp))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = lead,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
            fontWeight = FontWeight.SemiBold,
            color = if (isDimmed) hue.copy(alpha = 0.45f) else hue
        )
        if (rest.isNotEmpty()) {
            Text(
                text = rest,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                color = scheme.onSurface.copy(alpha = if (isDimmed) 0.4f else 0.85f),
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Text(
            text = unit.role,
            style = MaterialTheme.typography.labelSmall,
            color = if (isDimmed) hue.copy(alpha = 0.45f) else hue,
            modifier = Modifier.padding(start = 6.dp, end = 4.dp)
        )
    }
}


/** 角色名 → 颜色。模型输出的 role 是自由文本，按关键词取色；认不出走中性色。 */
internal fun syntaxRoleColor(
    role: String,
    scheme: androidx.compose.material3.ColorScheme
): Color {
    val r = role.trim()
    return when {
        r.contains("主语") -> scheme.primary
        r.contains("谓语") -> scheme.error
        r.contains("宾语") -> Color(0xFF43A047)
        r.contains("定语") -> Color(0xFF8D6E63)
        r.contains("状语") -> Color(0xFF00ACC1)
        r.contains("补语") -> Color(0xFFF57C00)
        r.contains("从句") -> Color(0xFF795548)
        r.contains("并列") || r.contains("连接") -> scheme.secondary
        else -> scheme.onSurfaceVariant
    }
}

/** 标签链：句长决定难句级，从句数与最深嵌套由单元算。 */
internal data class SyntaxCardTags(
    val difficulty: String,
    val clauseCount: Int,
    val depth: Int
)

internal fun buildSyntaxCardTags(source: String, units: List<SyntaxUnit>): SyntaxCardTags {
    val clauseCount = units.count { it.level >= 1 }
    val depth = units.maxOfOrNull { it.level } ?: 0
    val difficulty = when {
        source.length > 90 -> "超长难句"
        source.length > 40 -> "长难句"
        else -> "难句"
    }
    return SyntaxCardTags(difficulty, clauseCount, depth)
}
