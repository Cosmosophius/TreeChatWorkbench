package com.treechat.workbench.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.ui.chat.ChatNodeViewModel
import kotlinx.coroutines.CancellationException

/**
 * 阅读器「选句翻译」的小窗内容。
 *
 * ## 为什么是悬浮小窗而不是对话框
 * 翻译常常要对照原文读。对话框是**阻塞**的：它压在正文上，用户没法一边看原文
 * 一边看译文，关掉才能继续读。所以这里只做一个**内容 Composable**，由
 * [com.treechat.workbench.ui.window.FloatingWindowManager] 装进它自己的小窗
 * —— 于是拖拽、缩放、最小化、全屏全部由既有小窗机制负责，这里一行都不用写。
 *
 * ## 走的是哪条模型通路
 * [ChatNodeViewModel.streamSentenceTranslation]：与对话**完全相同**的那条
 * `aiClient.runChat` 通路，因此当前选中的服务商是本地 NPU 离线模型时会自动
 * 路由到端侧引擎，不需要另配一套。结果**流式**追加，不做"转圈等整段"。
 *
 * ## 诚实
 * 失败时把错误原因与模型**已经产出的部分**原样留在界面上（不吞掉、不伪装成
 * "正在翻译"），并提供"重试"。模型不可用（未挂载本地模型 / 云端无密钥）时
 * 根本不会开这个窗 —— 调用方会先给出明确原因（见 BookshelfScreen）。
 *
 * 纯 Compose + Material 3，无 emoji。
 */

/** 译文目标语言。 */
enum class TranslateTarget(val displayName: String) {
    // 界面用语纪律：写「简体汉语」，不写「中文」（中文不是一种语言）。
    // 枚举名 ZH 是代码标识符、不显示给用户，保持原样。
    ZH("简体汉语"),
    EN("英文"),
    RU("俄文")
}

@Composable
fun SentenceTranslationWindowContent(
    viewModel: ChatNodeViewModel,
    sourceText: String,
    initialTarget: TranslateTarget = TranslateTarget.ZH,
    /**
     * 提交一句追问（把这句翻译升级成对话）。
     *
     * 由宿主实现：创建该书的选句分支节点并以**对话小窗**打开 ——
     * 引用这句原文、可继续多轮。为 null 时输入行整体不可用。
     */
    onFollowUp: ((question: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    var target by remember(sourceText) { mutableStateOf(initialTarget) }
    var output by remember(sourceText) { mutableStateOf("") }
    var error by remember(sourceText) { mutableStateOf<String?>(null) }
    var running by remember(sourceText) { mutableStateOf(false) }
    var attempt by remember(sourceText) { mutableIntStateOf(0) }
    var copied by remember(sourceText) { mutableStateOf(false) }

    /**
     * 对齐后的句对；非 null = 逐句对照模式（P5）。
     * null 且无误 = 正在请求；请求失败自动回退整段流式（output/Error 路径不变）。
     */
    var aligned by remember(sourceText) {
        mutableStateOf<List<ChatNodeViewModel.TranslationPair>?>(null)
    }
    var followUp by remember(sourceText) { mutableStateOf("") }

    val provider = viewModel.activeProvider

    // 换目标语言 / 点重试都会重新起一次流；窗口关掉（离开组合）即自动取消。
    LaunchedEffect(target, attempt, sourceText) {
        output = ""
        error = null
        copied = false
        aligned = null
        running = true
        // P5：先走**逐句对齐**（不流式，收完解析）；契约不遵/网络失败 →
        // 自动回退旧的整段流式路径。两条路都诚实暴露失败，不伪装成功。
        try {
            val pairs = viewModel.requestSentenceTranslationAligned(
                sourceText, target.displayName
            )
            aligned = pairs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            aligned = null
            try {
                viewModel.streamSentenceTranslation(sourceText, target.displayName)
                    .collect { delta -> output += delta }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                error = t.localizedMessage ?: "翻译失败"
            }
        }
        running = false
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        // ---- 目标语言 ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TranslateTarget.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == target,
                    onClick = { target = candidate },
                    label = {
                        Text(
                            text = candidate.displayName,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ---- 原文（只读预览）----
        Text(
            text = "原文",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = sourceText,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 译文（流式）----
        Text(
            text = "译文（${target.displayName}）",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))

        if (running && output.isEmpty() && aligned == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(6.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (aligned != null) {
                // ---- P5 逐句对照：原句浅灰小字在上，译文正文在下，细线分隔 ----
                aligned!!.forEachIndexed { i, pair ->
                    if (i > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        androidx.compose.material3.HorizontalDivider(
                            thickness = 0.5.dp,
                            color = scheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Text(
                        text = pair.src,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                    Text(
                        text = pair.dst,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = scheme.onSurface,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            } else if (output.isNotEmpty()) {
                Text(
                    text = output + if (running) " …" else "",
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = scheme.onSurface
                )
            } else if (aligned == null && error == null) {
                Text(
                    text = "正在翻译…",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }

            // 诚实：失败原因与已产出的部分都留着，不伪装成成功
            error?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "翻译失败：$message",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onErrorContainer
                        )
                        if (output.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "以下是模型已经产出的部分（可能不完整）：",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // ---- 追问：小窗里直接继续对话 ----
        //
        // 为什么有它：用户对一句话常有一连串问题（"这句里 X 是什么意思""为什么
        // 这样断句"）。旧设计只有译文，问新问题得退出小窗重新选词；
        // 现在提交追问会**创建选句分支**（挂在这本书的根对话树里），
        // 以真正的对话小窗打开 —— 引用原文、可继续多轮。
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = followUp,
                onValueChange = { followUp = it },
                singleLine = true,
                placeholder = { Text("对这句追问…", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                enabled = followUp.isNotBlank() && onFollowUp != null,
                onClick = {
                    val q = followUp.trim()
                    if (q.isNotEmpty()) {
                        followUp = ""
                        onFollowUp?.invoke(q)
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "提交追问"
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = output.isNotEmpty() || aligned != null,
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            aligned?.joinToString("\n\n") { it.src + "\n" + it.dst } ?: output
                        )
                    )
                    copied = true
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(if (copied) "已复制" else "复制译文")
            }
            TextButton(
                enabled = !running,
                onClick = { attempt++ }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(if (running) "翻译中…" else "重试")
            }
        }

        Text(
            text = "模型：${provider.name} · ${provider.selectedModelId}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
