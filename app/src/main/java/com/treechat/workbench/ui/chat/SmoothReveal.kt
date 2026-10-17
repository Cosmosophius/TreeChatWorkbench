/*
 * MIT License
 *
 * Copyright (c) 2026 Most Maturapoj
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.treechat.workbench.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.first

/**
 * 流式文本的**平滑显示**（改编自 TokenFlow 的 `SmoothReveal`，MIT 许可）。
 *
 * 出处：https://github.com/maturapoj/TokenFlow
 * （`presentation/chat/streaming/SmoothReveal.kt`，Copyright (c) 2026 Most Maturapoj）
 * 许可证全文见本文件头部；条畅在此之上做了两处本地化改动（见下）。
 *
 * ## 它解决的问题
 * 服务端的 SSE 是**突发**的：可能一次塞来二十个分片，然后停一会儿。
 * 直接把网络到达的文本画出来，观感是"一顿一顿地跳"，而不是"匀速吐字"。
 * 这里把已到达但还没显示的文本当作**积压量**，按帧匀速补上：
 * 积压越多补得越快（[CATCH_UP_SECONDS] 秒内追平），但永不慢于
 * [MIN_CHARS_PER_SECOND]；没有积压时**不请求帧**（旧消息零开销）。
 *
 * ## 本项目的两处改动（与原实现的差异，如实标注）
 * 1. **每帧最多推进一次、且限制最小间隔**：原实现每个 vsync 都推进。
 *    本项目在模拟器上的实测结论是"整屏重绘"才是流式卡顿的主因
 *    （`Slow issue draw commands` 92 次 vs `Slow UI thread` 36 次），
 *    因此这里把推进频率压到 [MIN_UPDATE_INTERVAL_MS]（约 30fps）——
 *    观感上仍是匀速吐字，但把重绘次数降下来。这是个有意的取舍。
 * 2. 保持原实现的 emoji 安全（不在代理对中间切断）与"首帧全量显示"
 *    （旋转屏幕或回看历史消息时不该重新吐一遍）。
 */

/** 再慢也不能慢于这个速度，避免短尾巴"滴答"得太久。 */
private const val MIN_CHARS_PER_SECOND = 80f

/** 目标是这么长时间内追平当前积压：突发越大补得越快。 */
private const val CATCH_UP_SECONDS = 0.5f

/** 本地化改动：两次推进之间的最小间隔，约 30fps（原实现为每个 vsync）。 */
private const val MIN_UPDATE_INTERVAL_MS = 33L

private fun revealRate(backlog: Int): Float = max(MIN_CHARS_PER_SECOND, backlog / CATCH_UP_SECONDS)

/** 把 [end] 推过低代理项，保证 emoji 不会被切成两半。 */
private fun safeEnd(text: String, end: Int): Int =
    if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end + 1 else end

/**
 * 返回 [target] 的一个前缀，以平稳且随积压自适应的速度增长。
 *
 * 首次组合时已存在的文本**一次性显示**（旋转屏幕、回看旧消息都是这种情形）；
 * 帧循环只在有积压时运行，空闲消息不请求帧。
 */
@Composable
fun rememberSmoothReveal(target: String): String {
    val latest = rememberUpdatedState(target)
    var shown by remember { mutableIntStateOf(target.length) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { latest.value.length }.first { it != shown }
            if (latest.value.length < shown) {
                // 文本被替换（重新生成）→ 立刻跟上，不吐旧的
                shown = latest.value.length
                continue
            }
            var carry = 0f
            var last = withFrameNanos { it }
            var lastPush = last
            while (shown < latest.value.length) {
                withFrameNanos { now ->
                    val backlog = latest.value.length - shown
                    carry += revealRate(backlog) * (now - last) / 1_000_000_000f
                    last = now
                    val due = (now - lastPush) / 1_000_000L >= MIN_UPDATE_INTERVAL_MS
                    val step = carry.toInt()
                    if (step > 0 && due) {
                        shown = min(latest.value.length, shown + step)
                        carry -= step
                        lastPush = now
                    }
                }
            }
        }
    }

    val end = safeEnd(target, min(shown, target.length))
    return target.substring(0, end)
}
