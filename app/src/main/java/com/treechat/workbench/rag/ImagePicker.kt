package com.treechat.workbench.rag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 选图 + 压缩 + 转 data URI。
 *
 * ## 为什么必须压缩
 * 图片要**存进数据库**（用户要求全盘落库，且只有这样才能保证重启后历史图片还在），
 * 而 base64 又会再膨胀 33%。一张手机原图 3~5 MB，转成 data URI 就是 4~7 MB，
 * 存几张就能让数据库膨胀到几十 MB，检索和备份都会变慢。
 *
 * 所以入库前统一处理：
 * 1. **按长边缩放**（默认 1280px）：足够模型看清题目/图表文字，
 *    再大对识别准确率几乎没有增益；
 * 2. **JPEG 质量 80**：这是清晰度与体积的常见平衡点；
 * 3. **按 EXIF 旋转**：手机拍的图方向信息在 EXIF 里，不处理的话
 *    发给模型是躺倒的，模型认字会明显变差。
 *
 * ## 已知取舍
 * - 只输出 JPEG，因此**带透明通道的 PNG 会丢失透明**（变成黑底或白底）。
 *   教材照片与截图通常无所谓；若将来需要保透明，应改为按原格式分支处理。
 * - 不做多图拼接，一次一图，多张按顺序分别作为独立的 image 块发送。
 */
object ImagePicker {

    /** 缩放后的长边上限（px）。 */
    const val MAX_EDGE = 1280

    /** JPEG 压缩质量。 */
    const val JPEG_QUALITY = 80

    /** 单次最多允许附几张，防止用户一次选几十张把请求撑爆。 */
    const val MAX_IMAGES = 4

    class TooLargeException(message: String) : Exception(message)

    /**
     * 读入一张图，压缩后返回 data URI。
     *
     * @return 形如 `data:image/jpeg;base64,....`
     */
    fun toDataUri(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw TooLargeException("无法读取所选图片")

        // 先用 inJustDecodeBounds 拿原始尺寸，避免把超大图整张读进内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        }
        var bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOptions)
            ?: throw TooLargeException("图片解码失败（可能是不支持的格式）")

        // 按 EXIF 摆正方向：不做这一步，竖拍的照片发给模型是横的
        bitmap = applyExifRotation(raw, bitmap)

        // 二次精确缩放到长边上限（inSampleSize 只能按 2 的幂次降采样，可能仍偏大）
        bitmap = scaleToMaxEdge(bitmap, MAX_EDGE)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()

        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /** 计算 2 的幂次降采样比，使解码后的图不小于目标长边（避免二次放大）。 */
    internal fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxEdge && h / 2 >= maxEdge) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        val target = Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (target != src) src.recycle()
        return target
    }

    /**
     * 按 EXIF 方向信息旋转。
     *
     * ## 为什么自己解析 EXIF 而不用 androidx.exifinterface
     * 本机构建环境**无法下载新的依赖**（Gradle 缓存里没有 exifinterface，
     * 外网域名被拦），引进来会直接编译失败。而需要的信息只有一项
     * ——TAG_ORIENTATION（0x0112）——所以自己从 JPEG 的 APP1 段读出来即可，
     * 约 60 行、零依赖，也符合本项目"能自研就不引库"的一贯取舍
     * （自研 Markdown、自研 PDF 导出同理）。
     *
     * 只处理 JPEG；PNG 没有这种方向标记（其方向在单独的 eXIf chunk 里，
     * 实践中手机相册导出的 PNG 极少带方向，这里不做）。
     */
    private fun applyExifRotation(rawBytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = readExifOrientation(rawBytes)
        val matrix = Matrix()
        when (orientation) {
            3 -> matrix.postRotate(180f)      // ROTATE_180
            6 -> matrix.postRotate(90f)       // ROTATE_90
            8 -> matrix.postRotate(270f)      // ROTATE_270
            2 -> matrix.postScale(-1f, 1f)    // FLIP_HORIZONTAL
            4 -> matrix.postScale(1f, -1f)    // FLIP_VERTICAL
            else -> return bitmap             // 1 / 0 / 未标注：保持原样
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        }.getOrElse { bitmap }
    }

    /**
     * 从 JPEG 字节里读出 EXIF 方向值（1~8），读不到返回 0。
     *
     * JPEG 结构：FFD8 开头，随后是若干段，每段 FFE0~FFEF，段内前两字节是大端长度。
     * 方向信息在 APP1（FFE1）里的 "Exif\0\0" 之后，是一个 TIFF 头（II/MM + 42 + 偏移），
     * 再往下是 IFD0 的条目，每条 12 字节：tag(2) type(2) count(4) value(4)。
     */
    internal fun readExifOrientation(bytes: ByteArray): Int {
        try {
            if (bytes.size < 4) return 0
            if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 0 // 不是 JPEG

            var offset = 2
            while (offset + 4 <= bytes.size) {
                if (bytes[offset] != 0xFF.toByte()) return 0
                val marker = bytes[offset + 1].toInt() and 0xFF
                // SOS(DA) 之后是压缩数据，不再有 metadata
                if (marker == 0xDA) return 0
                val segmentLength = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
                if (segmentLength < 2) return 0

                // APP1 且以 "Exif\0\0" 开头
                if (marker == 0xE1 && offset + 4 + 6 <= bytes.size) {
                    val isExif = bytes[offset + 4] == 'E'.code.toByte() &&
                        bytes[offset + 5] == 'x'.code.toByte() &&
                        bytes[offset + 6] == 'i'.code.toByte() &&
                        bytes[offset + 7] == 'f'.code.toByte() &&
                        bytes[offset + 8] == 0.toByte() &&
                        bytes[offset + 9] == 0.toByte()
                    if (isExif) {
                        val tiff = offset + 10
                        val orientation = readOrientationFromTiff(bytes, tiff)
                        if (orientation != 0) return orientation
                    }
                }
                offset += 2 + segmentLength
            }
        } catch (e: Exception) {
            // 解析失败就当没有方向信息：图片照发，只是可能躺着
            return 0
        }
        return 0
    }

    /** 在 TIFF 头之后遍历 IFD0，找 tag 0x0112。 */
    private fun readOrientationFromTiff(b: ByteArray, tiffStart: Int): Int {
        if (tiffStart + 8 > b.size) return 0
        val littleEndian = when {
            b[tiffStart] == 'I'.code.toByte() && b[tiffStart + 1] == 'I'.code.toByte() -> true
            b[tiffStart] == 'M'.code.toByte() && b[tiffStart + 1] == 'M'.code.toByte() -> false
            else -> return 0
        }
        fun u16(pos: Int): Int {
            if (pos + 2 > b.size) return -1
            val a = b[pos].toInt() and 0xFF
            val c = b[pos + 1].toInt() and 0xFF
            return if (littleEndian) (c shl 8) or a else (a shl 8) or c
        }
        fun u32(pos: Int): Long {
            if (pos + 4 > b.size) return -1
            val v = if (littleEndian) {
                (b[pos].toInt() and 0xFF).toLong() or
                    ((b[pos + 1].toInt() and 0xFF).toLong() shl 8) or
                    ((b[pos + 2].toInt() and 0xFF).toLong() shl 16) or
                    ((b[pos + 3].toInt() and 0xFF).toLong() shl 24)
            } else {
                ((b[pos].toInt() and 0xFF).toLong() shl 24) or
                    ((b[pos + 1].toInt() and 0xFF).toLong() shl 16) or
                    ((b[pos + 2].toInt() and 0xFF).toLong() shl 8) or
                    (b[pos + 3].toInt() and 0xFF).toLong()
            }
            return v
        }
        if (u16(tiffStart + 2) != 42) return 0   // TIFF 魔数
        val ifdOffset = u32(tiffStart + 4).toInt()
        val ifd = tiffStart + ifdOffset
        val count = u16(ifd)
        if (count <= 0 || count > 512) return 0
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > b.size) return 0
            val tag = u16(entry)
            if (tag == 0x0112) {
                val value = u16(entry + 8)
                if (value in 1..8) return value
                return 0
            }
        }
        return 0
    }

    /** 从 data URI 里拆出纯 base64 与 media type（发给模型时要用）。 */
    fun splitDataUri(dataUri: String): Pair<String, String>? {
        if (!dataUri.startsWith("data:")) return null
        val comma = dataUri.indexOf(',')
        if (comma <= 0) return null
        val header = dataUri.substring(5, comma)          // 例如 image/jpeg;base64
        val payload = dataUri.substring(comma + 1)
        val mediaType = header.substringBefore(';').ifBlank { "image/jpeg" }
        return mediaType to payload
    }
}
