package com.treechat.workbench.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * PdfOcr —— 把扫描版 PDF 整本识别成纯文本，供「本地知识库」导入与阅读器使用。
 *
 * ── 为什么需要它 ──────────────────────────────────────────────────────
 * 知识库界面写着「直接导入 PDF / EPUB / TXT / MD 教材」，但旧 Kotlin 实现里
 * [com.treechat.workbench.rag.DocumentParser] 的 PDF 分支**根本没抽正文**：
 * 它只渲染一遍数出页数，然后返回一句「《已成功读取 PDF 档案》包含 N 页」。
 * 于是「导入成功」但知识库里一个字都没有。这里补上真正的一步 ——
 * PdfRenderer 逐页渲染 → [OcrEngine] 离线识别 → 拼成整本正文。
 *
 * ── 实现口径 ──────────────────────────────────────────────────────────
 *   渲染位图长边封顶 [MAX_SIDE]（再高只会更慢、不会更准），
 *   页数上限 [MAX_PAGES]（教材动辄几百页，不能无上限地跑）；
 *   被截断时会在正文尾部**明确写出截断**，不假装整本都识别完了。
 *
 * ── 诚实的失败语义 ────────────────────────────────────────────────────
 *   任何一步失败（PDF 打不开、临时文件写不进、一页都没识别出文字）都返回 **null**，
 *   由调用方回落到原来的提示。绝不返回空串 —— 那会让上层把「识别失败」
 *   当成「这本书是空的」。
 */
object PdfOcr {

    private const val TAG = "TalkTreeOCR"

    /** 整本最多识别多少页（超出部分截断，并在正文里写明）。 */
    const val MAX_PAGES = 500

    /** 渲染位图长边上限。A4 短边 595pt × 3 ≈ 1785px，需要短边上限 ≥ 1785。 */
    const val MAX_SIDE = 3600

    /**
     * 默认渲染倍率（A4 ≈ 144dpi）。
     *
     * 实测结论：3 倍（A4 ≈ 216dpi）并没有净改善，只是换了一批错字，
     * 耗时从 413ms/页 涨到 1327ms/页。所以默认仍是 2 倍，倍率做成可调项。
     */
    const val RENDER_SCALE = 2.0f

    /** 倍率可调文件名（放在 filesDir 下，便于不重新打包就做 A/B）。 */
    const val SCALE_FILE = "ocr_render_scale.txt"

    /**
     * 识别成功的正文抬头前缀。调用方据此判断「这份文档是走 OCR 出来的」（即扫描件），
     * 不要去猜文件大小。
     */
    const val HEADER_PREFIX = "（本文件由条畅离线 OCR 识别生成"

    /** 每识别完一页回调一次，便于界面把它接到 [OcrProgressBus] 或状态里。 */
    fun interface ProgressListener {
        fun onProgress(currentPage: Int, totalPages: Int, okPages: Int)
    }

    /**
     * @param pdf PDF 原始字节（知识库导入入口手里就是这个）
     * @param listener 进度回调，可为 null
     * @return 识别出的纯文本；失败返回 null
     */
    suspend fun extractText(
        context: Context,
        pdf: ByteArray?,
        listener: ProgressListener? = null
    ): String? {
        if (pdf == null || pdf.isEmpty()) return null
        if (!OcrEngine.isAvailable()) {
            Log.e(TAG, "ML Kit 识别器不可用：${OcrEngine.lastError}")
            return null
        }

        val dir = File(context.cacheDir, "pdf_ocr")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "创建临时目录失败: $dir")
            return null
        }
        val tmp = File(dir, "import_${System.currentTimeMillis()}.pdf")
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            FileOutputStream(tmp).use { os ->
                os.write(pdf)
                os.flush()
            }
            pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            val total = renderer.pageCount
            val limit = minOf(total, MAX_PAGES)
            val renderScale = resolveRenderScale(context)
            val sb = StringBuilder()
            var okPages = 0
            val t0 = System.currentTimeMillis()
            Log.i(
                TAG,
                "开始识别 PDF：共 $total 页，本次处理 $limit 页" +
                    if (limit < total) "（超出 $MAX_PAGES 页的部分不会识别）" else ""
            )
            listener?.onProgress(0, limit, 0)

            for (i in 0 until limit) {
                var page: PdfRenderer.Page? = null
                var bmp: Bitmap? = null
                try {
                    page = renderer.openPage(i)
                    bmp = renderPage(page, renderScale)
                    if (bmp != null) {
                        val text = OcrEngine.recognizePlainText(bmp)
                        if (!text.isNullOrEmpty()) {
                            sb.append("【第 ").append(i + 1).append(" 页】\n")
                            sb.append(text).append("\n\n")
                            okPages++
                        }
                    }
                } catch (th: Throwable) {
                    Log.w(TAG, "第 ${i + 1} 页识别失败（跳过）：$th")
                } finally {
                    bmp?.recycle()
                    try {
                        page?.close()
                    } catch (_: Throwable) {
                    }
                }
                if ((i + 1) % 10 == 0) {
                    Log.i(
                        TAG,
                        "识别进度 ${i + 1}/$limit，已成功 $okPages 页，用时 " +
                            "${(System.currentTimeMillis() - t0) / 1000}s"
                    )
                }
                if ((i + 1) % 3 == 0 || i + 1 == limit) {
                    listener?.onProgress(i + 1, limit, okPages)
                }
            }

            val body = sb.toString().trim()
            if (body.isEmpty()) {
                Log.e(TAG, "整本没有识别出任何文字（okPages=0）")
                return null
            }
            listener?.onProgress(limit, limit, okPages)
            val head = StringBuilder()
                .append(HEADER_PREFIX).append("：谷歌 ML Kit 简体/繁体汉语识别，离线运行）\n")
                .append("（原 PDF 共 ").append(total).append(" 页，成功识别 ").append(okPages).append(" 页")
                .apply { if (limit < total) append("；本次只处理了前 ").append(limit).append(" 页，其余未识别") }
                .append("）\n\n")
            Log.i(
                TAG,
                "PDF 识别完成：成功 $okPages/$limit 页，用时 ${(System.currentTimeMillis() - t0) / 1000}s，" +
                    "正文 ${body.length} 字"
            )
            return head.toString() + body
        } catch (th: Throwable) {
            Log.e(TAG, "PDF OCR 失败：$th")
            return null
        } finally {
            try {
                renderer?.close()
            } catch (_: Throwable) {
            }
            try {
                pfd?.close()
            } catch (_: Throwable) {
            }
            if (tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "临时文件删除失败（不影响结果）: $tmp")
            }
        }
    }

    /**
     * 实际使用的渲染倍率：优先读 `filesDir/ocr_render_scale.txt`（便于不重新打包就做 A/B），
     * 读不到就用 [RENDER_SCALE]。取值夹在 1.0 ~ 6.0。
     */
    fun resolveRenderScale(context: Context?): Float {
        if (context == null) return RENDER_SCALE
        return try {
            val f = File(context.filesDir, SCALE_FILE)
            if (!f.exists()) return RENDER_SCALE
            val line = f.readText(Charsets.UTF_8).lineSequence().firstOrNull()
            val v = line?.trim()?.toFloatOrNull()
            if (v == null || v < 1.0f || v > 6.0f) {
                Log.w(TAG, "倍率文件内容非法（$line），改用默认 $RENDER_SCALE")
                RENDER_SCALE
            } else {
                Log.i(TAG, "按 files/$SCALE_FILE 使用渲染倍率 $v")
                v
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读倍率文件失败，用默认 $RENDER_SCALE：$t")
            RENDER_SCALE
        }
    }

    /** 按 [MAX_SIDE] 与默认倍率渲染一页。 */
    fun renderPage(page: PdfRenderer.Page): Bitmap? = renderPage(page, RENDER_SCALE)

    fun renderPage(page: PdfRenderer.Page, targetScale: Float): Bitmap? {
        val pw = page.width
        val ph = page.height
        if (pw <= 0 || ph <= 0) return null
        val longSide = maxOf(pw, ph)
        val shortSide = minOf(pw, ph)
        var scale = targetScale
        if (longSide * scale > MAX_SIDE) scale = MAX_SIDE.toFloat() / longSide
        if (shortSide * scale > MAX_SIDE / 2) {
            scale = minOf(scale, (MAX_SIDE / 2).toFloat() / shortSide)
        }
        if (scale < 1.0f) scale = 1.0f
        val w = maxOf(1, Math.round(pw * scale))
        val h = maxOf(1, Math.round(ph * scale))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }
}
