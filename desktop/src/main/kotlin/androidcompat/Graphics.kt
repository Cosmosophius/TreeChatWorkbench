// 充面层：android.graphics.{Bitmap, Typeface}
@file:Suppress("unused")
package android.graphics

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.OutputStream
import javax.imageio.ImageIO

/** Bitmap 充面：包着 BufferedImage，暴露共享代码（LiveTextPageView 等）用到的最小面。 */
class Bitmap private constructor(val awtImage: BufferedImage) {
    val width: Int get() = awtImage.width
    val height: Int get() = awtImage.height

    fun recycle() { /* JVM GC 兜底，无操作 */ }
    fun isRecycled(): Boolean = false

    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean = runCatching {
        val formatName = when (format) { CompressFormat.JPEG -> "jpg"; CompressFormat.PNG -> "png"; else -> "jpg" }
        javax.imageio.ImageIO.write(awtImage, formatName, stream)
        true
    }.getOrDefault(false)

    enum class CompressFormat { JPEG, PNG, WEBP }

    companion object {
        fun createBitmap(width: Int, height: Int, config_: Any? = null): Bitmap =
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).let(::Bitmap)

        fun createBitmap(source: Bitmap): Bitmap {
            val img = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
            val g = img.graphics
            g.drawImage(source.awtImage, 0, 0, null)
            g.dispose()
            return Bitmap(img)
        }

        /** 替身渲染产物直通工厂：PNG 字节 → 充面 Bitmap（PdfPageSource 用）。 */
        internal fun decodePng(bytes: ByteArray): Bitmap? = try {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            Bitmap(img)
        } catch (_: Exception) { null }
    }
}

/** BitmapFactory 充面（消息缩略图解码链路）。 */
object BitmapFactory {
    class Options {
        var inJustDecodeBounds: Boolean = false
        var inSampleSize: Int = 1
        var outWidth: Int = 0
        var outHeight: Int = 0
    }

    @JvmStatic
    fun decodeByteArray(data: ByteArray?, offset: Int, length: Int, opts: Options? = null): Bitmap? {
        if (data == null) return null
        val boundsOnly = opts?.inJustDecodeBounds == true
        val img = runCatching {
            javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data, offset, length))
        }.getOrNull()
        if (img == null) { if (opts != null) { opts.outWidth = 0; opts.outHeight = 0 }; return null }
        if (opts != null) { opts.outWidth = img.width; opts.outHeight = img.height }
        if (boundsOnly) return null
        val sample = opts?.inSampleSize?.coerceAtLeast(1) ?: 1
        if (sample <= 1) return bitmapOfImage(img)
        val scaled = BufferedImage(
            (img.width / sample).coerceAtLeast(1),
            (img.height / sample).coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
        val g = scaled.graphics
        g.drawImage(img, 0, 0, scaled.width, scaled.height, null)
        g.dispose()
        return bitmapOfImage(scaled)
    }

    @JvmStatic
    fun decodeFile(path: String?, opts: Options? = null): Bitmap? = try {
        decodeByteArray(java.io.File(path!!).readBytes(), 0, Int.MAX_VALUE, opts)
    } catch (_: Exception) { null }
}

internal fun bitmapOfImage(img: BufferedImage): Bitmap {
    val out = Bitmap.createBitmap(img.width, img.height)
    val g = out.awtImage.graphics
    g.drawImage(img, 0, 0, null)
    g.dispose()
    return out
}

/** 按名字造字体的充面：桌面上不做真实字族加载，由 [asAndroidFamily] 桥回 Compose 内建族。 */
class Typeface private constructor(val names: List<String>, val style: Int) {
    override fun toString(): String = "Typeface${names}"

    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3

        @JvmStatic fun create(familyName: String, style: Int): Typeface =
            Typeface(listOf(familyName), style)

        @JvmStatic fun create(family: Typeface, style: Int): Typeface = Typeface(family.names, style)
    }
}
