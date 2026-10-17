package com.treechat.workbench.ui.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * 光标兜底：只要指针压在**文本区**上，就保证系统光标是工字竖条（I-Beam）。
 *
 * ## 为什么需要它（反编译 Compose 1.6.8 实测所得，不是猜测）
 *
 * `androidx.compose.ui.input.pointer.PointerHoverIconModifierNode` 的字节码确认三件事：
 *
 * 1. `onPointerEvent` **只在 `PointerEventPass.Main` 上、且只在 `Enter` / `Exit`
 *    两种事件类型**里更新光标；`Move` 一律直接返回。
 *    → 光标是"边沿触发"的：节点被重建而指针**没有离开过**时，新节点收不到
 *      `Enter`，永远没机会把自己的光标设上去。
 * 2. `onDetach()` 会调用 `displayIconFromAncestorNodeWithCursorInBoundsOrDefaultIcon()`；
 *    若此刻**没有任何祖先**处于"指针在其中"的状态，它就走 `displayDefaultIcon()`，
 *    即 `setIcon(null)` —— **把光标打回默认箭头**。
 *    → 消息流式刷新、列表项被回收复用、切 Tab 导致整棵子树 detach，都会触发这一步。
 * 3. 被打回 null 之后，**没有任何事件**会把它设回竖条：`Move` 不参与图标求值，
 *    指针若不动就永远停在箭头上。这正是反复出现的
 *    "工字光标变成箭头，然后就没法选词了"。
 *
 * ## 本兜底怎么修
 *
 * 在 `Final` 通道（三通道里的最后一道，晚于 `Main`）监听本节点收到的指针事件，
 * 用事件坐标判断指针是否**确实落在本节点范围内**；是、且窗口光标当前是"默认"（null），
 * 就把竖条补回去。补写发生在 `Final`，必然晚于 Compose 自己在 `Main` 里的
 * `setIcon(null)`，同一次事件内即可纠正，不必等下一次移动。
 *
 * 顺带一提：在事件分发之外调用 `View.setPointerIcon` 会被立刻下发给窗口
 *（`ViewRootImpl` 的 `mHandlingPointerEvent` 为 false 时直接调 session），
 * 所以补写是立即生效的，不需要额外一次移动。
 *
 * ## 为什么只补 null，不无条件压竖条
 *
 * 窗口边缘的缩放热区刻意用 `Crosshair`、标题栏用 `Hand`，它们与文本区几何重叠。
 * 无条件写竖条会把这两个光标顶掉。只在"当前是默认箭头"时才补，
 * 既治好本 bug，也不破坏别的控件刻意设置的光标。
 *
 * ## 已知残余（诚实说明）
 *
 * 节点被销毁、指针**同时完全静止**时没有任何事件可挂，这一次补不了；
 * 但指针只要动一下（用户发现光标不对必然要动），立刻恢复。
 * 公开 API 下到不了"零事件也保证"——Compose 没有开放"重新断言悬停光标"的入口。
 */
@Composable
fun Modifier.assureTextCursor(): Modifier {
    val view = LocalView.current
    val context = LocalContext.current
    val textIcon = remember(context) {
        android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_TEXT)
    }

    return this.pointerInput(textIcon) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull() ?: continue
                // 按下/拖动期间不参与：那属于选词或缩放热区的手势，别插手。
                if (change.pressed) continue
                val position = change.position
                val inside = position.x >= 0f && position.y >= 0f &&
                    position.x <= size.width.toFloat() &&
                    position.y <= size.height.toFloat()
                if (inside && view.pointerIcon == null) {
                    view.pointerIcon = textIcon
                }
            }
        }
    }
}
