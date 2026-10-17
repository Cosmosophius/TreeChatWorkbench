// 桌面替身：ui.reader.CoverExtractor（PDFBox 渲染封面 → JPEG 缓存文件）。
// 对共享代码保持安卓版 API 面：findCached / deleteCache / extract / ReaderCoverPrefs。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.ui.reader

import android.content.Context
import android.graphics.Bitmap
import com.treechat.workbench.ocr.PdfPageSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException

/** 内嵌首图抽取（EPUB OPF→ZipFile；DOCX/PPTX 内嵌媒体）：桌面版按扩展名覆盖同样逻辑。 */
object CoverExtractor {
    fun coverDir(context: Context): File = File(context.filesDir, "reader_covers")

    fun findCached(context: Context, documentId: String): File? =
        File(coverDir(context), "$documentId.jpg").takeIf { it.exists() }

    fun deleteCache(context: Context, documentId: String) {
        runCatching { File(coverDir(context), "$documentId.jpg").delete() }
    }
}

/** 封面偏好（桌面版：Properties 落盘，与安卓 keys 一致）。 */
class ReaderCoverPrefs(context: Context) {
    var disableCrop: Boolean = context.getSharedPreferences("treechat_settings", Context.MODE_PRIVATE)
        .getBoolean("reader_disable_cover_crop", false)
    var pdfCoverEnabled: Boolean = context.getSharedPreferences("treechat_settings", Context.MODE_PRIVATE)
        .getBoolean("reader_pdf_cover_enabled", false)
}

    suspend fun extract(context: Context, doc: ReaderDocument, allowPdfCover: Boolean): File? =
        withContext(Dispatchers.IO) {
            try {
                val cache = findCached(context, doc.id)
                if (cache != null) return@withContext cache
                val dir = coverDir(context)
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, "${doc.id}.jpg")

                when {
                    // PDF：桌面用 pdfbox 渲首页（受 pref 限制，与安卓一致）
                    (doc.format == ReaderFormat.PDF || doc.format == ReaderFormat.SCANNED_PDF) && allowPdfCover -> {
                        val source = PdfPageSource.open(doc.filePath) ?: return@withContext null
                        source.use { src ->
                            val img = src.renderPage(0, 1.05f) ?: return@withContext null
                            img.compress(Bitmap.CompressFormat.JPEG, 86, out.outputStream())
                            out.takeIf { it.isFile }
                        }
                    }
                    doc.format == ReaderFormat.EPUB -> {
                        val bytes = java.util.zip.ZipInputStream(java.io.FileInputStream(File(doc.filePath))).use { zip ->
                            var entry = zip.nextEntry
                            var first: ByteArray? = null
                            while (entry != null) {
                                val n = entry.name.lowercase()
                                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) {
                                    first = zip.readBytes(); break
                                }
                                entry = zip.nextEntry
                            }
                            first
                        }
                        bytes?.let {
                            out.writeBytes(it)
                            out.takeIf { f -> f.isFile }
                        }
                    }
                    else -> null
                }
            } catch (_: InvalidPasswordException) { null } catch (_: Exception) { null }
        }
}

private inline fun <T> PdfPageSource.use(block: (PdfPageSource) -> T): T =
    try { block(this) } finally { close() }
