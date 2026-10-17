// 桌面替身：ui.reader.CoverExtractor（PDFBox 渲封面 → JPEG 缓存文件）。
// 对共享代码保持安卓版 API 面：findCached / deleteCache / extract / ReaderCoverPrefs。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.ui.reader

import android.content.Context
import android.graphics.Bitmap
import com.treechat.workbench.ocr.PdfPageSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CoverExtractor {
    fun coverDir(context: Context): File = File(context.filesDir, "reader_covers")

    fun findCached(context: Context, documentId: String): File? =
        File(coverDir(context), "$documentId.jpg").takeIf { it.isFile }

    fun deleteCache(context: Context, documentId: String) {
        runCatching { File(coverDir(context), "$documentId.jpg").delete() }
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
                        try {
                            val img = source.renderPage(0, 1.05f) ?: return@withContext null
                            img.compress(Bitmap.CompressFormat.JPEG, 86, out.outputStream())
                            out.takeIf { it.isFile }
                        } finally {
                            source.close()
                        }
                    }
                    doc.format == ReaderFormat.EPUB -> {
                        val bytes = java.util.zip.ZipInputStream(java.io.FileInputStream(File(doc.filePath))).use { zip ->
                            var entry = zip.nextEntry
                            var first: ByteArray? = null
                            while (entry != null) {
                                val name = entry.name.lowercase()
                                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) {
                                    first = zip.readBytes()
                                    break
                                }
                                entry = zip.nextEntry
                            }
                            first
                        }
                        if (bytes != null) {
                            out.writeBytes(bytes)
                            out.takeIf { it.isFile }
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
}

/** 封面偏好（桌面版：Properties 落盘，与安卓 keys 一致）。 */
class ReaderCoverPrefs(private val context: Context) {
    var disableCrop: Boolean = context.getSharedPreferences("treechat_settings", Context.MODE_PRIVATE)
        .getBoolean("reader_disable_cover_crop", false)
        set(value) {
            field = value
            context.getSharedPreferences("treechat_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("reader_disable_cover_crop", value).apply()
        }
    var pdfCoverEnabled: Boolean = context.getSharedPreferences("treechat_settings", Context.MODE_PRIVATE)
        .getBoolean("reader_pdf_cover_enabled", false)
        set(value) {
            field = value
            context.getSharedPreferences("treechat_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("reader_pdf_cover_enabled", value).apply()
        }

    /** 对齐安卓版的面：ReaderScreen 用 updateDisableCrop / updatePdfCover。 */
    fun updateDisableCrop(value: Boolean) { disableCrop = value }
    fun updatePdfCover(value: Boolean) { pdfCoverEnabled = value }
}
