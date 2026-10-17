package com.treechat.workbench.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale

/**
 * 阅读排版自定义：字号 / 行距 / 护眼底色 / 翻页动画。
 *
 * - 三项都**即时生效**（改动直接写进 [ReaderTypographyStore]，正文读取方自动重组），
 *   点「完成」只是关面板，不是"提交"。
 * - 预览区直接复用正文的同一套计算（[ReaderTypography.fontSizeSp] / [lineHeightSp]
 *   与 [EyeCareTheme.resolve]），所以预览与真实正文一致，不会出现"预览好看、正文不是那样"。
 * - 用 Dialog 而不是 ModalBottomSheet：万一这个面板将来被放进某个 Dialog 里宿主，
 *   底部弹层会被独立窗口压住（本工程已踩过这个坑），Dialog 没有这个限制。
 */
@Composable
fun ReaderTypographyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val typography = rememberReaderTypography()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "阅读排版",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface
                )
                Text(
                    text = "字号、行距、护眼底色与翻页动画都会立刻作用到正文，并被记住。",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ---------------- 字号 ----------------
                SliderRow(
                    label = "字号",
                    valueText = String.format(Locale.US, "%.1f sp", typography.fontSizeSp),
                    value = typography.fontScale,
                    valueRange = ReaderTypography.MIN_FONT_SCALE..ReaderTypography.MAX_FONT_SCALE,
                    onValueChange = {
                        ReaderTypographyStore.update(context, typography.copy(fontScale = it))
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ---------------- 行距 ----------------
                SliderRow(
                    label = "行距",
                    valueText = String.format(Locale.US, "%.2f 倍", typography.lineHeightMultiplier),
                    value = typography.lineHeightMultiplier,
                    valueRange = ReaderTypography.MIN_LINE_HEIGHT..ReaderTypography.MAX_LINE_HEIGHT,
                    onValueChange = {
                        ReaderTypographyStore.update(context, typography.copy(lineHeightMultiplier = it))
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ---------------- 护眼底色 ----------------
                Text(
                    text = "护眼底色",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "浅色与深色主题下各有一套对应色，避免「护眼绿」在夜间的深色界面里变成亮斑。",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                val themes = EyeCareTheme.entries
                themes.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { theme ->
                            FilterChip(
                                selected = typography.eyeCare == theme,
                                onClick = {
                                    ReaderTypographyStore.update(context, typography.copy(eyeCare = theme))
                                },
                                label = { Text(theme.label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = typography.eyeCare.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ---------------- 电脑模式（对开排版） ----------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "电脑模式（对开双页）",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurface
                        )
                        Text(
                            text = "像 PC 桌面版阅读器一样双页并排阅读，适合横屏或大屏。",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = typography.desktopLayout,
                        onCheckedChange = {
                            ReaderTypographyStore.update(context, typography.copy(desktopLayout = it))
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ---------------- 翻页动画 ----------------
                Text(
                    text = "翻页动画",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "点按正文左右分区、键盘方向键与横向拖动都会翻页；这里决定翻页时的观感。" +
                        "实况选词（PDF/扫描件）不参与动画，仍是即时换页。",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 一排三枚 FilterChip，与既有的护眼底色选择同一套交互。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PageTurnMode.entries.forEach { mode ->
                        FilterChip(
                            selected = typography.pageTurn == mode,
                            onClick = {
                                ReaderTypographyStore.update(context, typography.copy(pageTurn = mode))
                            },
                            label = { Text(mode.label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = typography.pageTurn.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ---------------- 实时预览 ----------------
                Text(
                    text = "预览",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                TypographyPreview(typography = typography)

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { ReaderTypographyStore.reset(context) }) {
                        Text("恢复默认")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss) {
                        Text("完成")
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = scheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

/**
 * 预览卡：底色、字号、行高全部走与正文相同的解析函数。
 * 文字内容只说明这件事本身，不塞任何"示例正文"式伪内容。
 */
@Composable
private fun TypographyPreview(typography: ReaderTypography) {
    val scheme = MaterialTheme.colorScheme
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val eyeCare = typography.eyeCare.resolve(isDark)

    val paper = eyeCare?.paper ?: scheme.surface
    val onPaper = eyeCare?.onPaper ?: scheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = paper),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "字号与行距按当前设置实时预览；护眼底色只改变纸面与文字的对比，" +
                    "不改变文档内容。改完这里的显示，正文与它一致。",
                color = onPaper,
                textAlign = TextAlign.Justify,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = typography.fontSizeSp.sp,
                    lineHeight = typography.lineHeightSp.sp
                )
            )
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = "正文字号 ${String.format(Locale.US, "%.1f", typography.fontSizeSp)} sp · " +
            "行高 ${String.format(Locale.US, "%.1f", typography.lineHeightSp)} sp",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = scheme.onSurfaceVariant
    )
}
