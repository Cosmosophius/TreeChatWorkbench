package com.treechat.workbench.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.treechat.workbench.data.model.ConversationNode
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 对话界面的「标签小窗」交互层。
 *
 * ## 为什么需要它（与同文本超链接互补，而不是取代）
 * 同文本超链接要求"先选中一段文字"，于是三类场景天然开不了分支：
 * 纯图片消息、代码块/表格、以及"这块空白处我想单开一支"。
 * 标签小窗把锚定方式换成**位置 + 用户起的名字**，不再依赖任何文本，
 * 也不再因为"两段话文字相同"而指向同一个分支。
 *
 * ## 手势判据：为什么空白处才算
 * 长按在**可选中文本**上必须留给选词（`SelectableMarkdownText` 已在
 * Main pass 消费了按下事件），所以这里以 `down.isConsumed` 作为分流：
 * 被消费 = 按在字上 → 让位；未被消费 = 空白 → 才进入长按计时。
 * 这与阅读器里"起点有没有可选文本"是同一套判据，两处手感一致。
 *
 * 右键（鼠标/触控板）不等长按计时，按下即触发——桌面习惯。
 *
 * ## 为什么画在内容之上、手势挂在内容之外
 * 图钉要始终看得见（拖选时也不该被高亮糊掉），所以作为兄弟层后绘制；
 * 而长按判据要拿到"子节点是否已消费按下"，所以 pointerInput 挂在
 * 外层 Box 上（Compose 的 Main pass 由内向外，父层看到的是子层处理后的状态）。
 */
@Composable
fun ChatTagSurface(
    hostNodeId: String,
    store: ChatTagStore,
    /** 建一个下级分支节点（标题即标签名）。返回 null 表示宿主已失效。 */
    onCreateBranch: suspend (title: String) -> ConversationNode?,
    /** 打开该分支的小窗（复用同文本超链接那条开窗链路）。 */
    onOpenWindow: (ConversationNode) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var tags by remember(hostNodeId) { mutableStateOf(store.load(hostNodeId)) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    // 待命名的新标签：归一化坐标（0..1）。非 null 时弹命名框。
    var pendingPos by remember { mutableStateOf<Offset?>(null) }
    var draftName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun beginNaming(posInBox: Offset, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        pendingPos = Offset(
            (posInBox.x / size.width).coerceIn(0f, 1f),
            (posInBox.y / size.height).coerceIn(0f, 1f)
        )
        draftName = "标签 ${tags.size + 1}"
    }

    Box(
        modifier = modifier
            .onSizeChanged { surfaceSize = it }
            .pointerInput(hostNodeId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // ---- 右键：按下即触发，不等长按 ----
                    //
                    // 按钮状态在 `PointerEvent` 上，不在 `PointerInputChange` 上
                    // （Compose 1.6 的 PointerInputChange 没有 buttons 成员），
                    // 所以取作用域里的 `currentEvent`。
                    if (down.type == PointerType.Mouse &&
                        currentEvent.buttons.isSecondaryPressed
                    ) {
                        down.consume()
                        beginNaming(down.position, size)
                        return@awaitEachGesture
                    }

                    // 按在可选文本上 → 交给选词，这里完全不介入。
                    if (down.isConsumed) return@awaitEachGesture

                    // ---- 长按空白：计时内没有移动、也没有被别处消费 ----
                    val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Final)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if ((ch.position - down.position).getDistance() >
                                viewConfiguration.touchSlop
                            ) {
                                moved = true
                                break
                            }
                        }
                        !moved
                    }
                    if (held == true) beginNaming(down.position, size)
                }
            }
    ) {
        content()

        // ---- 图钉层：实心圆 + 白描边 + 名字，与阅读器的位置标签同一套表达 ----
        tags.forEach { tag ->
            val scheme = MaterialTheme.colorScheme
            Surface(
                shape = RoundedCornerShape(50),
                color = scheme.primary.copy(alpha = 0.92f),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, scheme.surface),
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (tag.x * surfaceSize.width).toInt(),
                            (tag.y * surfaceSize.height).toInt()
                        )
                    }
                    .clickable {
                        // 已有分支 → 直接打开；还没有 → 用当前名字建一个再打开
                        scope.launch {
                            val existingId = tag.nodeId
                            if (existingId != null) {
                                // 分支已在树里：交给宿主按 nodeId 找开/置顶
                                onOpenWindow(
                                    ConversationNode(
                                        id = existingId,
                                        parentId = hostNodeId,
                                        title = tag.name,
                                        originQuote = ""
                                    )
                                )
                            } else {
                                val child = onCreateBranch(tag.name) ?: return@launch
                                store.attachNode(tag.id, child.id)
                                tags = store.load(hostNodeId)
                                onOpenWindow(child)
                            }
                        }
                    }
                    .pointerInput(tag.id) {
                        // 长按图钉 = 移除标签（分支本身留在树里，不删数据）
                        awaitEachGesture {
                            val d = awaitFirstDown(requireUnconsumed = false)
                            val removed = withTimeoutOrNull(
                                viewConfiguration.longPressTimeoutMillis
                            ) {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Final)
                                    val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                                    if (!ch.pressed) break
                                    if ((ch.position - d.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) return@withTimeoutOrNull false
                                }
                                true
                            }
                            if (removed == true) {
                                store.remove(tag.id)
                                tags = store.load(hostNodeId)
                            }
                        }
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(scheme.surface, CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = tag.name,
                        color = scheme.surface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    // ---- 命名框：文本框在顶部，确认后建分支并归入当前对话的下属分支 ----
    pendingPos?.let { pos ->
        Dialog(onDismissRequest = { pendingPos = null }) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "新建标签小窗",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "它会挂到当前对话的下级分支，点图钉即可打开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        singleLine = true,
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = { pendingPos = null }) { Text("取消") }
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            onClick = {
                                val name = draftName.trim()
                                if (name.isEmpty()) return@TextButton
                                val created = store.add(hostNodeId, name, pos.x, pos.y)
                                tags = store.load(hostNodeId)
                                pendingPos = null
                                // 立刻把分支建出来并打开：用户点完命名就该看到那扇窗，
                                // 而不是"放了个图钉但还得再点一下"。
                                scope.launch {
                                    val child = onCreateBranch(name) ?: return@launch
                                    store.attachNode(created.id, child.id)
                                    tags = store.load(hostNodeId)
                                    onOpenWindow(child)
                                }
                            }
                        ) {
                            Text("创建", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
