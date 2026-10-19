// 桌面替身：ocr.OcrEngine —— 扫描件 OCR 的桌面实现。
// 快路径：文字 PDF 由 PdfPageSource 渲染时经 PDFBox 登记真词框（OcrWordBoxRegistry）。
// 扫描件：位图 → 外部 OCR 进程 —— macOS 用系统 Vision（ttocr CLI，离线、随 dmg 分发）；
// Linux 用 tesseract（用户自装，检测到即启用）。共享选词链路一行不改。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.ocr

import android.graphics.Bitmap
import com.treechat.workbench.ui.reader.OcrWordBox
import androidx.compose.ui.geometry.Rect
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import com.google.gson.JsonParser

object OcrEngine {

    /** 安卓版签名：给一张页位图，返回词框列表（像素坐标系）。 */
    fun recognizeOrFallback(bmp: Bitmap): List<OcrWordBox> {
        val registered = OcrWordBoxRegistry.takeCurrent()
        if (registered.isNotEmpty()) return registered
        return recognizeBitmap(bmp)
    }

    fun recognizePlainText(bmp: Bitmap?): String? {
        if (bmp == null) return null
        val boxes = recognizeBitmap(bmp)
        return if (boxes.isEmpty()) null else boxes.joinToString(" ") { it.text }
    }

    /** 真识别：内置 PP-OCR（jar 自带模型，双平台同一套）优先；外部进程兜底。 */
    internal fun recognizeBitmap(bmp: Bitmap?): List<OcrWordBox> {
        if (bmp == null) return emptyList()
        runCatching {
            val paddle = PaddleOcr.recognize(bmp.awtImage)
            if (paddle.isNotEmpty()) return paddle
        }
        val engine = detectEngine() ?: return emptyList()
        val png = File(System.getProperty("java.io.tmpdir"), "ttocr-${UUID.randomUUID()}.png")
        try {
            ImageIO.write(bmp.awtImage, "png", png)
            return when (engine) {
                "ttocr" -> runVision(png, bmp.width, bmp.height)
                else -> runTesseract(png)
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            png.delete()
        }
    }

    /** 引擎探测：mac → ttocr（jar 内资源提取/邻目录/PATH）；linux → tesseract（PATH）。 */
    internal fun detectEngine(): String? {
        if (DesktopOcrCache.engine != null) return DesktopOcrCache.engine
        val isMac = System.getProperty("os.name", "").lowercase().contains("mac")
        if (isMac) {
            if (extractTtocrFromResources() || locateTtocrFile() != null) {
                DesktopOcrCache.engine = "ttocr"
                return "ttocr"
            }
        }
        val tesseractOnPath = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .any { dir -> File(dir, "tesseract").canExecute() }
        if (tesseractOnPath) {
            DesktopOcrCache.engine = "tesseract"
            return "tesseract"
        }
        if (!isMac && locateTtocrFile() != null) {
            // 非 mac 机器上明确提供的 ttocr（例如本地调试），照常启用
            DesktopOcrCache.engine = "ttocr"
            return "ttocr"
        }
        return null
    }

    /** jar 内 bin/ttocr 资源 → tmp 可执行（mac 打包自带）。 */
    private fun extractTtocrFromResources(): Boolean = runCatching {
        val bytes = OcrEngine::class.java.classLoader.getResourceAsStream("ttocr")?.readBytes()
            ?: return false
        val dst = File(System.getProperty("java.io.tmpdir"), "ttocr-treechat")
        dst.writeBytes(bytes)
        runCatching { Runtime.getRuntime().exec(arrayOf("chmod", "+x", dst.absolutePath)).waitFor() }
        DesktopOcrCache.ttocrPath = dst.absolutePath
        true
    }.getOrDefault(false)

    /** macOS Vision：ttocr 输出 JSON 数组（归一化、自顶坐标）。 */
    private fun runVision(png: File, width: Int, height: Int): List<OcrWordBox> {
        val binary = locateTtocr() ?: return emptyList()
        val proc = ProcessBuilder(binary.absolutePath, png.absolutePath).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val arr = runCatching {
            JsonParser.parseString(out).asJsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.asJsonObject
            val text = obj.get("text")?.asString ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            val x = (obj.get("x")?.asDouble ?: 0.0).toFloat() * width
            val y = (obj.get("y")?.asDouble ?: 0.0).toFloat() * height
            val w = (obj.get("w")?.asDouble ?: 0.0).toFloat() * width
            val h = (obj.get("h")?.asDouble ?: 0.0).toFloat() * height
            OcrWordBox(id = "vision_${UUID.randomUUID()}", bounds = Rect(x, y, x + w, y + h), text = text, confidence = 0.95f)
        }
    }

    private fun locateTtocr(): File? = DesktopOcrCache.ttocrPath?.let { File(it) } ?: locateTtocrFile()

    private fun locateTtocrFile(): File? {
        System.getProperty("compose.application.resources.dir")?.let { d ->
            val f = File(d, "ttocr"); if (f.canExecute()) return f
        }
        listOf(File("desktop/app-resources/ttocr"), File("app-resources/ttocr")).forEach { f ->
            if (f.canExecute()) return f
        }
        (System.getenv("PATH") ?: "").split(File.pathSeparator).forEach { d ->
            val f = File(d, "ttocr"); if (f.canExecute()) return f
        }
        return null
    }

    /** linux tesseract：TSV 输出，level=5 行是词。 */
    private fun runTesseract(png: File): List<OcrWordBox> {
        val proc = ProcessBuilder("tesseract", png.absolutePath, "stdout", "tsv").start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().split('\t').map { it.trim() }
        val iLevel = header.indexOf("level"); val iLeft = header.indexOf("left")
        val iTop = header.indexOf("top"); val iW = header.indexOf("width")
        val iH = header.indexOf("height"); val iText = header.indexOf("text")
        if (iLevel < 0 || iLeft < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val cols = line.split('\t')
            if (cols.size <= maxOf(iText, iH)) return@mapNotNull null
            if (cols[iLevel].trim() != "5") return@mapNotNull null
            val text = cols[iText].trim()
            if (text.isBlank()) return@mapNotNull null
            val left = cols[iLeft].trim().toFloatOrNull() ?: return@mapNotNull null
            val top = cols[iTop].trim().toFloatOrNull() ?: return@mapNotNull null
            val w = cols[iW].trim().toFloatOrNull() ?: return@mapNotNull null
            val h = cols[iH].trim().toFloatOrNull() ?: return@mapNotNull null
            OcrWordBox(id = "tess_${UUID.randomUUID()}", bounds = Rect(left, top, left + w, top + h), text = text, confidence = 0.8f)
        }
    }
}

/** 探测结果缓存（进程级一次即可）。 */
internal object DesktopOcrCache {
    @Volatile var engine: String? = null
    @Volatile var ttocrPath: String? = null
}
