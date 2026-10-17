// 充面层：android.os.Environment（桌面等价：用户目录下的标准目录）。
@file:Suppress("unused", "unused_parameter")
package android.os

import java.io.File

object Environment {
    const val DIRECTORY_DOWNLOADS = "Download"
    const val DIRECTORY_DOCUMENTS = "Documents"
    const val DIRECTORY_PICTURES = "Pictures"

    const val MEDIA_MOUNTED = "mounted"

    @JvmStatic
    fun getExternalStorageState(): String = MEDIA_MOUNTED

    @JvmStatic
    fun getExternalStoragePublicDirectory(type: String?): File =
        File(System.getProperty("user.home"), type ?: "Downloads")

    @JvmStatic
    fun getExternalStorageDirectory(): File = File(System.getProperty("user.home"))
}
