package com.treechat.workbench.ui.reader

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 阅读器的句法标注卡（考研 AI 式呈现，内核是**我们的大模型切分**）。
 *
 * 参考软件是"规则分析"画一级近似、再由「神经网络句法」开关换精确结果；
 * 用户明确：它的启发式做得烂，**换成现有的自动选句 + 大模型切割**，
 * 但卡片样式照它做。因此这里只有一条数据源 ——
 * [SentenceToolState.units]（模型返回的 text / level / role，严格 JSON）。
 *
 * 卡片结构（与参考截图一致）：
 *   [标签链]  超长难句 / 长难句 · 从句 N · 嵌套 M 层
 *   [句子主干]  level=0 的单元
 *   [从句层次]  按定位顺序缩进成树
 *   [原句成分标注]  把各单元**叠加回原句**：角色配色 + 底衬 + 下划线
 *   [图例]  出现过的角色与颜色
 *   [本段操作]  翻译本段 / 复制本段 / 收起本段注释
 */

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
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val bodyReady = !analyzing && failureReason == null && units.isNotEmpty()
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
                    // 无任何可画内容：保持空，仅留操作行
                }

                else -> {
                    val tags = remember(sourceSentence, units) {
                        buildSyntaxCardTags(sourceSentence, units)
                    }

                    // ---- 标签链 ----
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text(tags.difficulty, fontSize = 11.sp) })
                        AssistChip(onClick = {}, label = { Text("从句 ${tags.clauseCount}", fontSize = 11.sp) })
                        AssistChip(onClick = {}, label = { Text("嵌套 ${tags.depth} 层", fontSize = 11.sp) })
                    }

                    // ---- 句子主干（level=0）----
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "句子主干",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant
                    )
                    // 主干 = 主语+谓语+宾语（成分级 units），没有成分级时退回整句
                    val trunk = units.filter { it.level == 0 && it.role.any { k -> k in "主谓宾" } }
                        .joinToString(" ") { it.text }
                        .ifBlank { units.firstOrNull { it.level == 0 }?.text ?: sourceSentence }
                    Text(
                        text = trunk,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    // ---- 从句层次（树：缩进 = level）----
                    val nested = locateUnits(sourceSentence, units)
                        .first
                        .filter { it.unit.level >= 1 }
                    if (nested.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "从句层次",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurfaceVariant
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            nested.forEach { item ->
                                val clause = item.unit.text
                                val cut = clause.indexOfFirst { it == ' ' }
                                val lead = if (cut > 0) clause.substring(0, cut) else clause
                                val rest = if (cut > 0) clause.substring(cut) else ""
                                // 树行 = 缩进(level) + └ + 引导词(角色色/加粗) + 其余(正文色)
                                // 与参考截图一致：色词本身就是"角色标签"，不再另写 role 文案
                                Row(
                                    modifier = Modifier.padding(
                                        start = ((item.unit.level.coerceAtLeast(1) - 1) * 14).dp
                                    )
                                ) {
                                    Text(
                                        text = "└ ",
                                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                        color = scheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = lead,
                                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = syntaxRoleColor(item.unit.role, scheme)
                                    )
                                    if (rest.isNotEmpty()) {
                                        Text(
                                            text = rest,
                                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                            color = scheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---- 原句成分标注（叠加回整句）----
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "原句成分标注 · 模型切分",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val annotated = remember(sourceSentence, units) {
                        val spans = locateUnits(sourceSentence, units).first
                        buildAnnotatedString {
                            append(sourceSentence)
                            spans.sortedBy { it.start }.forEach { item ->
                                val color = syntaxRoleColor(item.unit.role, scheme)
                                addStyle(
                                    SpanStyle(
                                        color = color,
                                        background = color.copy(alpha = 0.16f),
                                        textDecoration = TextDecoration.Underline
                                    ),
                                    item.start,
                                    minOf(item.end, sourceSentence.length)
                                )
                            }
                        }
                    }
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = scheme.onSurface
                    )

                    // ---- 图例 ----
                    val roles = units.map { it.role }.distinct()
                    if (roles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp, Alignment.Start)
                        ) {
                            roles.forEach { role ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    if (bodyReady) clipboard.setText(AnnotatedString(sourceSentence))
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
