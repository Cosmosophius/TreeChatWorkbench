// 充面层：androidx.compose.ui.res.painterResource(@DrawableRes Int) 的桌面占位版。
// 共享代码只有两处调用（LiveTextComposeOverlay 的印刷体注音/手写角标图标）。
@file:Suppress("unused")
package androidx.compose.ui.res

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

class ResourcePlaceholderPainter : Painter() {
    override val intrinsicSize: Size
        get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        // 占位：真实 24dp 矢量在 M5 补上（资源管线走 compose resources 时统一收口）
    }
}

fun painterResource(id: Int): Painter = ResourcePlaceholderPainter()
