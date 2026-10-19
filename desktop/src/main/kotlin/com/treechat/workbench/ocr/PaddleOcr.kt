// 桌面内置 OCR：PaddleOCR PP-OCRv4 (mobile) ONNX 管线。
// 两个 onnx 从 jar 资源读取（desktop/app-resources/paddleocr/），onnxruntime jar 自带
// 全平台原生库（osx-aarch64 / linux-aarch64 / linux-x64）——零外部依赖、零外部进程。
// 几何 = det(DBNet) 概率图 + 连通域；文字 = rec(CTC)，字典内嵌于 onnx metadata。
package com.treechat.workbench.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.compose.ui.geometry.Rect
import com.treechat.workbench.ui.reader.OcrWordBox
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.util.UUID

object PaddleOcr {
    private const val DET_MODEL = "paddleocr/ch_PP-OCRv4_det_infer.onnx"
    private const val REC_MODEL = "paddleocr/ch_PP-OCRv4_rec_infer.onnx"
    private const val DET_LIMIT = 960
    private const val REC_HEIGHT = 48
    private const val REC_WIDTH_MAX = 320
    private const val DET_THRESH = 0.3f
    private const val MIN_BOX_PIXELS = 40

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val detSession: OrtSession by lazy {
        env.createSession(readJar(DET_MODEL), sessionOpts())
    }
    private val recSession: OrtSession by lazy {
        env.createSession(readJar(REC_MODEL), sessionOpts())
    }

    private fun readJar(path: String): ByteArray {
        val stream = PaddleOcr::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("缺少内置 OCR 模型资源：$path")
        return stream.use { it.readBytes() }
    }

    private fun sessionOpts() = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    /** 给一页位图，返回行级词框列表（像素坐标系，与安卓 ML Kit 同语义）。 */
    fun recognize(source: BufferedImage): List<OcrWordBox> {
        if (source.width < 8 || source.height < 8) return emptyList()
        return try {
            // ---- det：等比缩放到长边 <= 960，且宽高必须是 32 的倍数（DBNet FPN 约束）----
            val ds = minOf(1f, DET_LIMIT.toFloat() / maxOf(source.width, source.height))
            val rawW = (source.width * ds).toInt().coerceAtLeast(32)
            val rawH = (source.height * ds).toInt().coerceAtLeast(32)
            val w = ((rawW + 31) / 32) * 32
            val h = ((rawH + 31) / 32) * 32
            val dsW = w.toFloat() / source.width
            val dsH = h.toFloat() / source.height

            val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            small.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(source, 0, 0, w, h, null)
                dispose()
            }

            val probs = runDet(toChw(small), w, h)
            val boxes = extractBoxes(probs, w, h)

            // ---- rec：每行框裁剪（回原图坐标）→ CTC ----
            val dict = loadDict()
            boxes.mapNotNull { box ->
                // DBNet 检测出的 kernel 是收缩后的文本核（默认 shrink 0.4），
                // 必须在上下左右按行高 50% 向外扩张（unclip），才能完整包含汉字上下笔画
                val unclipY = (box.bottom - box.top) * 0.60f
                val unclipX = (box.bottom - box.top) * 0.50f
                val origLeft = (box.left - unclipX) / dsW
                val origTop = (box.top - unclipY) / dsH
                val origRight = (box.right + unclipX) / dsW
                val origBottom = (box.bottom + unclipY) / dsH

                val l = origLeft.toInt().coerceIn(0, source.width - 1)
                val t = origTop.toInt().coerceIn(0, source.height - 1)
                val r = origRight.toInt().coerceIn(l + 2, source.width)
                val b = origBottom.toInt().coerceIn(t + 2, source.height)
                val crop = source.getSubimage(l, t, r - l, b - t)
                val text = recognizeLine(crop, dict)
                if (text.isBlank()) {
                    null
                } else {
                    OcrWordBox(
                        id = "ppocr_${UUID.randomUUID()}",
                        bounds = Rect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()),
                        text = text,
                        confidence = 0.95f,
                    )
                }
            }
        } catch (e: Exception) {
            System.err.println("[ppocr] 识别失败: ${e.message}")
            emptyList()
        }
    }

    // ---------- det ----------
    private fun runDet(chw: FloatArray, w: Int, h: Int): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            detSession.run(mapOf("x" to t)).use { out ->
                val tensor = out.get(0) as OnnxTensor
                // shape [1,1,H,W]
                val shape = tensor.info.shape
                val H = shape[2].toInt(); val W = shape[3].toInt()
                val buf = tensor.floatBuffer
                val flat = FloatArray(H * W)
                buf.get(flat)
                flat
            }
        }

    /** DB 概率图 → 8 连通域 → 行框（含阈值与最小面积过滤）。 */
    private fun extractBoxes(probs: FloatArray, w: Int, h: Int): List<Box> {
        val visited = BooleanArray(w * h)
        val out = ArrayList<Box>()
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (visited[i] || probs[i] < DET_THRESH) continue
                // BFS
                var minL = x; var maxR = x; var minT = y; var maxB = y
                var count = 0
                val queue = ArrayDeque<Int>()
                queue.add(i); visited[i] = true
                while (queue.isNotEmpty()) {
                    val p = queue.removeFirst()
                    count++
                    val px = p % w; val py = p / w
                    if (px < minL) minL = px; if (px > maxR) maxR = px
                    if (py < minT) minT = py; if (py > maxB) maxB = py
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx; val ny = py + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val ni = ny * w + nx
                        if (!visited[ni] && probs[ni] >= DET_THRESH) {
                            visited[ni] = true; queue.add(ni)
                        }
                    }
                }
                if (count < MIN_BOX_PIXELS) continue
                val height = maxB - minT + 1
                if (height < 8) continue   // 过滤碎片（印刷行高一般 >= 10px @960）
                out += Box(minL, minT, maxR, maxB)
            }
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    // ---------- rec ----------
    private fun recognizeLine(crop: BufferedImage, dict: List<String>): String {
        // 高度归一 48，宽等比，保留真实长宽比（PP-OCRv4 动态宽）
        val rh = REC_HEIGHT
        val rw = maxOf(16, (crop.width * REC_HEIGHT.toFloat() / crop.height).toInt().coerceAtMost(2048))
        val prep = BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB)
        prep.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(crop, 0, 0, rw, rh, null)
            dispose()
        }
        val chw = toChw(prep)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, REC_HEIGHT.toLong(), rw.toLong())).use { t ->
            recSession.run(mapOf("x" to t)).use { out ->
                val tensor = out.get(0) as OnnxTensor
                val shape = tensor.info.shape   // [1, T, C]
                val T = shape[1].toInt(); val C = shape[2].toInt()
                val buf = tensor.floatBuffer
                val sb = StringBuilder()
                var prev = 0
                for (tIdx in 0 until T) {
                    var best = 0; var bestVal = Float.NEGATIVE_INFINITY
                    val base = (tIdx * C)
                    for (c in 0 until C) {
                        val v = buf.get(base + c)
                        if (v > bestVal) { bestVal = v; best = c }
                    }
                    if (best != 0 && best != prev) {
                        val ch = dict.getOrNull(best - 1) ?: ""
                        if (ch.isNotEmpty()) sb.append(ch)
                    }
                    prev = best
                }
                sb.toString()
            }
        }
    }

    /** 字典：onnx metadata 的 `character` 字段（RapidOCR 转换产物），blank=index0。 */
    private var dictCache: List<String>? = null

    private fun loadDict(): List<String> {
        dictCache?.let { return it }
        val raw = recSession.metadata?.customMetadata?.get("character")
            ?: return emptyList()
        val chars = raw.split("\n").map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        val dict = chars + listOf(" ")
        dictCache = dict
        return dict
    }

    /** HWC(int pixels) → CHW float，归一化 (x/255 - 0.5)/0.5。 */
    private fun toChw(img: BufferedImage): FloatArray {
        val w = img.width; val h = img.height
        val rgb = img.getRGB(0, 0, w, h, null, 0, w)
        val out = FloatArray(3 * w * h)
        var rIdx = 0
        var gIdx = w * h
        var bIdx = 2 * w * h
        var scale = 1f / 255f
        for (p in rgb) {
            out[rIdx++] = (((p shr 16) and 0xFF) * scale - 0.5f) / 0.5f
            out[gIdx++] = (((p shr 8) and 0xFF) * scale - 0.5f) / 0.5f
            out[bIdx++] = ((p and 0xFF) * scale - 0.5f) / 0.5f
        }
        return out
    }
}
