package com.treechat.workbench.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.treechat.workbench.ui.reader.OcrWordBox
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * OcrEngine —— 扫描件实况选词（LiveText）与知识库导入共用的离线文字识别。
 *
 * 这一版把旧补丁线里的 `com.treechat.workbench.split.OcrEngine`（Java + 反射 +
 * 为 d8 服务的 static 嵌套回调）整体搬到 Kotlin：底层仍是**谷歌官方 ML Kit
 * 离线中文识别**（`com.google.mlkit:text-recognition-chinese`，bundled 版，
 * 模型随 APK 分发，不联网、不走 Play 服务下载），只是把反射构造 WordItem
 * 换成直接产出 [OcrWordBox]。
 *
 * ── 坐标口径（不要改错） ────────────────────────────────────────────────
 * 传入的 bitmap 是 PdfRenderer 以「页面尺寸 × 渲染倍率」渲染出来的，
 * 返回的包围盒也在**同一个 bitmap 像素坐标系**里。屏幕坐标由调用方统一换算，
 * 这里不做任何缩放；改了这里高亮框就会整体偏移。
 *
 * ── 失败时的行为 ────────────────────────────────────────────────────────
 * 初始化失败 / 超时 / 报错时**不抛异常、不返回空**，而是回退到几何投影算法
 * （能画出高亮条，但选中文本为空），保证即使模型没进包，阅读器也不失去划词能力。
 * 是否真的用上了识别文本，看日志 TAG「TalkTreeOCR」。
 */
object OcrEngine {

    private const val TAG = "TalkTreeOCR"

    /** 单页识别超时。扫描页通常 0.3–3s；超过这个数说明卡住了，回退。 */
    private const val TIMEOUT_MS = 30_000L

    const val LANG_CHINESE = "zh"
    const val LANG_RUSSIAN = "ru"
    const val LANG_LATIN = "en"

    @Volatile
    private var currentLanguage: String = LANG_CHINESE

    @Volatile
    private var russianLexicon: List<String> = emptyList()

    @Volatile
    private var recognizer: TextRecognizer? = null

    @Volatile
    private var initFailed = false

    /** 最近一次失败原因（成功则为 null）。界面上用它做诊断提示，不做假数据。 */
    @Volatile
    var lastError: String? = null
        private set

    fun setOcrLanguage(lang: String?) {
        currentLanguage = when {
            lang.equals(LANG_RUSSIAN, ignoreCase = true) || lang.equals("russian", ignoreCase = true) -> {
                Log.i(TAG, "OCR 语种已切换至: 俄语与西里尔文 (Russian / Cyrillic)")
                LANG_RUSSIAN
            }
            lang.equals(LANG_LATIN, ignoreCase = true) || lang.equals("latin", ignoreCase = true) -> {
                Log.i(TAG, "OCR 语种已切换至: 通用拉丁文 (Latin)")
                LANG_LATIN
            }
            else -> {
                Log.i(TAG, "OCR 语种已切换至: 中英文默认 (Chinese & Latin)")
                LANG_CHINESE
            }
        }
    }

    fun getOcrLanguage(): String = currentLanguage

    fun setRussianCustomLexicon(lexicon: List<String>?) {
        if (lexicon != null) {
            russianLexicon = lexicon.toList()
            Log.i(TAG, "已加载俄语自定义词库规则，词条数: ${russianLexicon.size}")
        }
    }

    fun isAvailable(): Boolean = ensureRecognizer() != null

    @Synchronized
    private fun ensureRecognizer(): TextRecognizer? {
        recognizer?.let { return it }
        if (initFailed) return null
        return try {
            val options = ChineseTextRecognizerOptions.Builder().build()
            TextRecognition.getClient(options).also {
                recognizer = it
                Log.i(TAG, "ML Kit 中文文字识别器已就绪（bundled 离线模型）")
            }
        } catch (th: Throwable) {
            initFailed = true
            lastError = describe(th)
            Log.e(TAG, "ML Kit 文字识别器初始化失败，将回退到几何投影： $lastError")
            null
        }
    }

    /* ==================== 对外的两个入口 ==================== */

    /**
     * 识别一页并产出词盒列表。**永远非 null**：最差是几何投影的结果，最坏是空列表。
     */
    suspend fun recognizeOrFallback(bitmap: Bitmap?): List<OcrWordBox> {
        if (bitmap == null) return emptyList()
        val items = recognize(bitmap)
        if (!items.isNullOrEmpty()) return items
        Log.w(TAG, "ML Kit 没有产出任何文本块，回退到几何投影（划词仍可用，但选中文本为空）")
        return geometricFallback(bitmap)
    }

    /**
     * 只做识别，不做回退。失败返回 null。
     */
    suspend fun recognize(bitmap: Bitmap?): List<OcrWordBox>? {
        if (bitmap == null) return null
        val result = runRecognition(bitmap) ?: return null
        val out = ArrayList<OcrWordBox>()
        var elements = 0
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val txt = el.text
                    if (txt.isBlank()) continue
                    val box = el.boundingBox ?: continue
                    out.add(
                        OcrWordBox(
                            id = "w${elements}_${box.left}_${box.top}",
                            bounds = androidx.compose.ui.geometry.Rect(
                                box.left.toFloat(),
                                box.top.toFloat(),
                                box.right.toFloat(),
                                box.bottom.toFloat()
                            ),
                            text = txt
                        )
                    )
                    elements++
                }
            }
        }
        Log.i(TAG, "实况选词识别完成：块=${result.textBlocks.size}，词元=$elements")
        return out.ifEmpty { null }
    }

    /**
     * 只要文字、不要坐标：把整页按「块 → 行」拼成纯文本。
     * 本地知识库导入扫描版 PDF 时走这条路（见 [PdfOcr]）。
     * 失败返回 **null**（不返回空串，免得把「没识别出来」伪装成「这页是空的」）。
     */
    suspend fun recognizePlainText(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        val result = runRecognition(bitmap) ?: return null
        val sb = StringBuilder()
        val blocks = result.textBlocks
        for ((i, block) in blocks.withIndex()) {
            for (line in block.lines) {
                val t = line.text
                if (t.isNotBlank()) sb.append(t.trim()).append('\n')
            }
            if (i < blocks.size - 1) sb.append('\n')
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * 真正跑一次 ML Kit 识别；失败返回 null 并记下 [lastError]。
     * recognize / recognizePlainText 都走这里，保证两条路的超时与失败语义一致。
     */
    private suspend fun runRecognition(bitmap: Bitmap): Text? {
        val rec = ensureRecognizer() ?: return null
        val t0 = System.currentTimeMillis()
        val text: Text? = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                // ML Kit 的 Task 用 suspendCancellableCoroutine 直接等，避免额外引
                // kotlinx-coroutines-play-services。
                suspendCancellableCoroutine { cont ->
                    rec.process(image)
                        .addOnSuccessListener { r -> if (cont.isActive) cont.resume(r) }
                        .addOnFailureListener { e ->
                            lastError = describe(e)
                            if (cont.isActive) cont.resume(null)
                        }
                }
            } catch (th: Throwable) {
                lastError = describe(th)
                Log.e(TAG, "提交识别任务失败：$lastError")
                null
            }
        }
        if (text == null) {
            if (lastError == null) {
                lastError = "识别超时（${TIMEOUT_MS}ms）"
                Log.e(TAG, lastError!!)
            }
            return null
        }
        Log.i(
            TAG,
            "识别完成：位图=${bitmap.width}x${bitmap.height}，耗时=${System.currentTimeMillis() - t0}ms"
        )
        lastError = null
        return text
    }

    /* ==================== 回退：几何投影算法（逐条保留旧语义） ==================== */

    /**
     * 与旧 PdfLiveTextReaderView 的 performFastLiveTextOCR 等价：
     * 灰度阈值扫出每行暗像素行程，把行程当「词」的矩形，文本填一个空格占位。
     * 坐标口径与识别路径一致（bitmap 像素）。
     */
    fun geometricFallback(bitmap: Bitmap): List<OcrWordBox> {
        val items = ArrayList<OcrWordBox>()
        val width = bitmap.width
        val height = bitmap.height

        val sampleStepY = maxOf(2, height / 800)
        val sampleStepX = maxOf(4, width / 400)

        var inLine = false
        var lineTop = 0
        val minDarkPixels = width / (sampleStepX * 12)
        var seq = 0

        var y = 0
        while (y < height) {
            var darkCount = 0
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if ((r + g + b) / 3 < 185) darkCount++
                x += sampleStepX
            }
            if (darkCount >= minDarkPixels) {
                if (!inLine) {
                    inLine = true
                    lineTop = y
                }
            } else if (inLine) {
                inLine = false
                val lineBottom = y
                val lineHeight = lineBottom - lineTop
                if (lineHeight in 12..120) {
                    val midY = (lineTop + lineBottom) / 2
                    val wordStepX = maxOf(2, width / 600)
                    var inWord = false
                    var wordLeft = 0
                    var wx = 0
                    while (wx < width) {
                        val pixel = bitmap.getPixel(wx, midY)
                        val gray = (((pixel shr 16) and 0xff) + ((pixel shr 8) and 0xff) + (pixel and 0xff)) / 3
                        val isDark = gray < 195
                        if (isDark) {
                            if (!inWord) {
                                inWord = true
                                wordLeft = wx
                            }
                        } else if (inWord) {
                            inWord = false
                            val wordRight = wx
                            if (wordRight - wordLeft >= 10) {
                                items.add(
                                    OcrWordBox(
                                        id = "g${seq++}",
                                        bounds = androidx.compose.ui.geometry.Rect(
                                            wordLeft.toFloat(),
                                            lineTop.toFloat(),
                                            wordRight.toFloat(),
                                            lineBottom.toFloat()
                                        ),
                                        text = " ",
                                        confidence = 0f
                                    )
                                )
                            }
                        }
                        wx += wordStepX
                    }
                }
            }
            y += sampleStepY
        }
        return items
    }

    private fun describe(th: Throwable?): String {
        if (th == null) return "未知错误"
        val m = th.message
        return th.javaClass.simpleName + if (m == null) "" else "：$m"
    }
}
