// 桌面替身 com.treechat.workbench.ocr.OcrEngine：v1 不做真 OCR。
// 词框来源 = PdfPageSource 渲染时 PDFBox 登记的文字坐标（文字 PDF 真框，扫描件空盒）。
@file:Suppress("unused")
package com.treechat.workbench.ocr

import android.graphics.Bitmap
import com.treechat.workbench.ui.reader.OcrWordBox

object OcrEngine {
    /** 签名与安卓版一致；桌面用登记表代替模型识别。 */
    fun recognizeOrFallback(bmp: Bitmap): List<OcrWordBox> =
        OcrWordBoxRegistry.takeCurrent()

    fun recognizePlainText(bmp: Bitmap?): String? = null
}
