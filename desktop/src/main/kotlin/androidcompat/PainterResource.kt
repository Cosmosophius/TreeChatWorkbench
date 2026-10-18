// 充面层：androidx.compose.ui.res.painterResource(@DrawableRes Int) 的桌面矢量版。
// 对齐安卓 res/drawable 的两个使用点（LiveTextComposeOverlay 工具条角标）：
//  - ic_ip_chinese：汉语注音角标（圆 + 声调波纹）
//  - ic_zi_brush  ：手写笔刷（毛笔笔头）
// 与安卓的 24dp 矢量同语义、M3 风格；共享代码一行未改。
package androidx.compose.ui.res

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
private const val ICON_IP_CHINESE = 0x7F010001
private const val ICON_ZI_BRUSH = 0x7F010002

class VectorPlaceholderPainter(private val resourceId: Int) : Painter() {
    override val intrinsicSize: Size
        get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        val accent = Color(0xFF28C840)
        when (resourceId) {
            ICON_IP_CHINESE -> {
                // 外圈
                drawCircle(
                    color = accent,
                    radius = minOf(w, h) * 0.38f,
                    center = Offset(w / 2f, h / 2f),
                    style = Stroke(width = minOf(w, h) * 0.08f),
                )
                // 声调波纹（一声平 / 二声升的抽象线）
                val yBase = h * 0.60f
                drawLine(
                    color = accent,
                    start = Offset(w * 0.28f, yBase),
                    end = Offset(w * 0.72f, yBase - h * 0.14f),
                    strokeWidth = minOf(w, h) * 0.09f,
                )
                drawLine(
                    color = accent.copy(alpha = 0.55f),
                    start = Offset(w * 0.28f, yBase - h * 0.16f),
                    end = Offset(w * 0.72f, yBase - h * 0.16f),
                    strokeWidth = minOf(w, h) * 0.06f,
                )
            }
            ICON_ZI_BRUSH -> {
                // 笔杆（斜线）
                drawLine(
                    color = accent,
                    start = Offset(w * 0.26f, h * 0.74f),
                    end = Offset(w * 0.70f, h * 0.24f),
                    strokeWidth = minOf(w, h) * 0.10f,
                )
                // 笔头（三角锥指向左下）
                val tip = Path().apply {
                    moveTo(w * 0.22f, h * 0.80f)
                    lineTo(w * 0.34f, h * 0.66f)
                    lineTo(w * 0.20f, h * 0.68f)
                    close()
                }
                drawPath(tip, color = accent)
                // 笔尖触点
                drawCircle(
                    color = accent.copy(alpha = 0.5f),
                    radius = minOf(w, h) * 0.05f,
                    center = Offset(w * 0.17f, h * 0.86f),
                )
            }
        }
    }
}

fun painterResource(id: Int): Painter = VectorPlaceholderPainter(id)
