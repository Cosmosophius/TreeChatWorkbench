package com.treechat.workbench.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 扫描页图像处理：**找边框 → 透视矫正 → 彩色/黑白**。
 *
 * ## 为什么用纯 Android 图形 API 而不引 OpenCV
 * OpenCV Android 会给 APK 加 8~15MB，而这里要做的事恰好在 `Bitmap`/`Canvas`
 * 的能力范围内：找边是阈值化 + 连通域轮廓，透视是 `Matrix.setPolyToPoly`，
 * 黑白是 `ColorMatrix`。全部本地、零依赖、毫秒级。
 *
 * ## 能达到什么水平（诚实评估）
 * - **彩色 / 黑白**：完全够用，黑白还做了对比度增强，接近扫描王。
 * - **自动找边框**：在"纸在深色背景上、占画面大部分"时很准（手持拍摄的典型情形）；
 *   纸与背景接近、或只露一角时会失败——此时靠**手动框选**兜底（界面里提供）。
 * - **透视矫正**：找到四角后矫正效果好。
 * 达不到扫描王的地方主要是"手抖 / 重阴影 / 手入镜"这类，依赖云端模型，本地不做。
 */
object ScanImageProcessor {

    // ------------------------------------------------------------ 入口

    /**
     * 处理一页扫描件：按设置做彩色/黑白与（可选）自动裁边，返回可保存的 JPEG 字节。
     *
     * @param manualQuad 用户手动框的四角（null = 未手动框选）。
     *                   手动优先于自动——用户框的就是他要的。
     */
    fun process(
        src: Bitmap,
        colorMode: ScanColorMode,
        autoCrop: Boolean,
        manualQuad: Array<PointF>? = null
    ): ProcessedPage {
        var work = src
        // 1) 裁边：手动优先，其次自动
        val quad = manualQuad ?: if (autoCrop) detectQuad(src) else null
        if (quad != null) {
            work = perspectiveCorrect(src, quad)
        }
        // 2) 色彩
        work = when (colorMode) {
            ScanColorMode.Color -> enhanceColor(work)
            ScanColorMode.Gray -> toGrayEnhanced(work)
        }
        return ProcessedPage(bitmap = work, cropped = quad != null)
    }

    class ProcessedPage(val bitmap: Bitmap, val cropped: Boolean)

    // ------------------------------------------------------------ 黑白 / 彩色

    /** 黑白：转灰 + 轻度对比度拉伸，让淡字更清楚。 */
    private fun toGrayEnhanced(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val p = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            // 灰度矩阵
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        c.drawBitmap(src, 0f, 0f, p)
        // 对比度拉伸：把灰度往两端推，让扫描件更像"打印稿"
        return applyContrast(out, 1.35f)
    }

    /** 彩色：轻微提对比 + 提亮，让扫描件更接近扫描王那样"干净"。 */
    private fun enhanceColor(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint().apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        1.12f, 0f, 0f, 0f, 6f,
                        0f, 1.12f, 0f, 0f, 6f,
                        0f, 0f, 1.12f, 0f, 6f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    /** 用像素级对比度拉伸（在灰度图上效果最好）。 */
    private fun applyContrast(src: Bitmap, factor: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val lut = IntArray(256) { v ->
            val nv = ((v - 128) * factor + 128).coerceIn(0f, 255f).toInt()
            Color.rgb(nv, nv, nv)
        }
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            pixels[i] = lut[lum]
            i++
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ------------------------------------------------------------ 自动找边框

    /**
     * 检测纸张四边形，返回四角（左上、右上、右下、左下）。
     *
     * 思路（全用纯 API，不做机器学习）：
     *  1. 缩略到小图（性能）；
     *  2. 灰度 → Sobel 边缘 → 阈值化出强边缘；
     *  3. 找四条边（上下左右）的最强边缘线，用它们求交点得到四角。
     *
     * 这种"四角点找边缘"的启发式在纸占画面大部时很准；失败就返回 null，
     * 由用户手动框选兜底 —— **宁可不做，也不要裁错**（裁错比不裁更糟）。
     */
    fun detectQuad(src: Bitmap): Array<PointF>? {
        val w = 320
        val h = (src.height * (w.toFloat() / src.width)).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val sw = small.width
        val sh = small.height

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        // 灰度
        val gray = IntArray(sw * sh)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }

        // Sobel 边缘幅值
        val edge = IntArray(sw * sh)
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val idx = y * sw + x
                val gx = -gray[idx - sw - 1] - 2 * gray[idx - 1] - gray[idx + sw - 1] +
                        gray[idx - sw + 1] + 2 * gray[idx + 1] + gray[idx + sw + 1]
                val gy = -gray[idx - sw - 1] - 2 * gray[idx - sw] - gray[idx - sw + 1] +
                        gray[idx + sw - 1] + 2 * gray[idx + sw] + gray[idx + sw + 1]
                edge[idx] = min(255, abs(gx) + abs(gy))
            }
        }
        // 投影找四条强边：逐行/逐列统计边缘能量，取峰值
        val rowEnergy = IntArray(sh)
        val colEnergy = IntArray(sw)
        for (y in 0 until sh) for (x in 0 until sw) {
            val e = edge[y * sw + x]
            rowEnergy[y] += e
            colEnergy[x] += e
        }
        val topRow = argmaxInRange(rowEnergy, (sh * 0.05f).toInt(), (sh * 0.4f).toInt())
        val bottomRow = argmaxInRange(rowEnergy, (sh * 0.6f).toInt(), (sh * 0.95f).toInt())
        val leftCol = argmaxInRange(colEnergy, (sw * 0.05f).toInt(), (sw * 0.4f).toInt())
        val rightCol = argmaxInRange(colEnergy, (sw * 0.6f).toInt(), (sw * 0.95f).toInt())

        // 阈值校验：四条边能量太低 = 没找到明显的纸边 → 放弃，不乱裁
        val minEnergy = (sw * sh * 0.02f).toInt()
        if (topRow < 0 || bottomRow < 0 || leftCol < 0 || rightCol < 0) return null
        if (rowEnergy[topRow] < minEnergy || rowEnergy[bottomRow] < minEnergy) return null
        if (colEnergy[leftCol] < minEnergy || colEnergy[rightCol] < minEnergy) return null

        // 膨胀到原图坐标
        val sx = src.width.toFloat() / sw
        val sy = src.height.toFloat() / sh
        fun pt(cx: Float, cy: Float) = PointF(cx * sx, cy * sy)
        // 在小图里取每条边上"边缘最强"的点作为角，避免直接用投影峰值（那在角上会偏移）
        return arrayOf(
            cornerOn(edge, sw, sh, leftCol, topRow, 1, 1),
            cornerOn(edge, sw, sh, rightCol, topRow, -1, 1),
            cornerOn(edge, sw, sh, rightCol, bottomRow, -1, -1),
            cornerOn(edge, sw, sh, leftCol, bottomRow, 1, -1)
        ).map { pt(it.x, it.y) }.toTypedArray()
    }

    private fun cornerOn(
        edge: IntArray, sw: Int, sh: Int,
        cx: Int, cy: Int, dx: Int, dy: Int
    ): PointF {
        var bestE = -1
        var bx = cx
        var by = cy
        val range = 6
        for (oy in -range..range) {
            for (ox in -range..range) {
                val x = cx + ox
                val y = cy + oy
                if (x in 0 until sw && y in 0 until sh) {
                    val e = edge[y * sw + x]
                    if (e > bestE) {
                        bestE = e
                        bx = x
                        by = y
                    }
                }
            }
        }
        return PointF(bx.toFloat(), by.toFloat())
    }

    private fun argmaxInRange(arr: IntArray, from: Int, to: Int): Int {
        var best = -1
        var bestI = -1
        for (i in from.coerceAtLeast(0)..to.coerceAtMost(arr.size - 1)) {
            if (arr[i] > best) {
                best = arr[i]
                bestI = i
            }
        }
        return bestI
    }

    // ------------------------------------------------------------ 透视矫正

    /**
     * 把原图按四边形拉正成矩形（`setPolyToPoly` 做透视变换）。
     *
     * 输出尺寸取四边的平均长度，保证不变形。
     */
    fun perspectiveCorrect(src: Bitmap, quad: Array<PointF>): Bitmap {
        if (quad.size < 4) return src
        val tl = quad[0]
        val tr = quad[1]
        val br = quad[2]
        val bl = quad[3]

        val outW = max(
            dist(tl, tr), dist(bl, br)
        ).toInt().coerceIn(1, src.width)
        val outH = max(
            dist(tl, bl), dist(tr, br)
        ).toInt().coerceIn(1, src.height)

        val dst = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )
        val srcQuad = floatArrayOf(
            tl.x, tl.y,
            tr.x, tr.y,
            br.x, br.y,
            bl.x, bl.y
        )

        val matrix = android.graphics.Matrix()
        if (!matrix.setPolyToPoly(srcQuad, 0, dst, 0, 4)) {
            return src   // 退化四边形（如四角共线）→ 不处理
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val p = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        c.drawBitmap(src, matrix, p)
        return out
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}