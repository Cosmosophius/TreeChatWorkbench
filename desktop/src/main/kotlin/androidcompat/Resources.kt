// 充面层：android.util.res.Resources/DisplayMetrics（ReaderScreen 书架摆位算 px→dp）。
@file:Suppress("unused")
package android.util

class DisplayMetrics {
    var density: Float = 2f
    var densityDpi: Int = 320
    var widthPixels: Int = 1280
    var heightPixels: Int = 830
}

object DesktopResources {
    val displayMetrics = DisplayMetrics()
}
