// 充面层：把共享代码的 Bitmap.asImageBitmap() 桥到桌面 Compose（skia Image → toComposeImageBitmap）。
@file:Suppress("unused")
package androidx.compose.ui.graphics

import org.jetbrains.skia.Image

fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap {
    val bytes = java.io.ByteArrayOutputStream().use { bos ->
        javax.imageio.ImageIO.write(awtImage, "png", bos)
        bos.toByteArray()
    }
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
