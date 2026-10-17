package com.treechat.workbench.ocr

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * PdfPageSource —— 阅读器「实况选词」用的**按页渲染源**。
 *
 * [PdfOcr] 是「整本识别成纯文本」的批处理入口，每次 [PdfOcr.extractText] 都会
 * 自己开一份 `PdfRenderer` 再关掉；阅读器要的是**同一本书反复按页渲染**，
 * 反复开关 PdfRenderer 既慢又浪费（每次都要重读 xref）。这里把打开/关闭
 * 收拢成一个持有者：**一个文档一份**，由调用方 remember 并在退出/换书时 close。
 *
 * ── 线程与生命周期 ────────────────────────────────────────────────────
 * `PdfRenderer` 不是线程安全的，而实况模式可能同时并排渲染 1~3 页（多列）。
 * 所以 [renderPage] 内部用一把 [Mutex] 把渲染串行化，并自己切到
 * [Dispatchers.IO]（渲染一页几十毫秒，绝不能挂在主线程）。调用方只负责
 * suspend 调用，不用自己包 withContext。
 *
 * ── 诚实语义 ──────────────────────────────────────────────────────────
 * 文件不存在 / 不是可渲染 PDF / 页号越界 / 已关闭 —— 一律返回 **null**，
 * 由界面回落到文本渲染。绝不抛异常，也绝不返回一张空白位图假装成功。
 */
class PdfPageSource private constructor(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor
) : Closeable {

    /** 真实页数（PdfRenderer 口径，1 基页号 = 本值 - 1）。 */
    val pageCount: Int = renderer.pageCount

    private val mutex = Mutex()

    @Volatile
    private var closed = false

    /**
     * 渲染 [pageIndex]（**0 基**）一页并返回位图，长边按 [PdfOcr.MAX_SIDE] 封顶。
     * 必须在协程里调用；失败返回 null。
     */
    suspend fun renderPage(pageIndex: Int, scale: Float): Bitmap? {
        if (closed) return null
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        return mutex.withLock {
            if (closed) return@withLock null
            withContext(Dispatchers.IO) {
                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(pageIndex)
                    PdfOcr.renderPage(page, scale)
                } catch (th: Throwable) {
                    Log.w(TAG, "渲染第 ${pageIndex + 1} 页失败：$th")
                    null
                } finally {
                    try {
                        page?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            renderer.close()
        } catch (_: Throwable) {
        }
        try {
            pfd.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "TalkTreeOCR"

        /** 打开失败（文件不存在/不可渲染）返回 null，界面据此回落到文本渲染。 */
        fun open(filePath: String): PdfPageSource? {
            if (filePath.isBlank()) return null
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "实况选词：PDF 文件不存在，回落到文本渲染：$filePath")
                return null
            }
            val desc = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (th: Throwable) {
                Log.w(TAG, "实况选词：打开 PDF 失败，回落到文本渲染：$th")
                return null
            }
            return try {
                PdfPageSource(PdfRenderer(desc), desc)
            } catch (th: Throwable) {
                Log.w(TAG, "实况选词：PdfRenderer 初始化失败，回落到文本渲染：$th")
                try {
                    desc.close()
                } catch (_: Throwable) {
                }
                null
            }
        }
    }
}
