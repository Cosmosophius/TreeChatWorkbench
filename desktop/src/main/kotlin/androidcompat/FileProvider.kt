// 充面层：Intent 拓展 + FileProvider（导出走桌面文件保存，Android 分享链路降级）。
@file:Suppress("unused", "UNCHECKED_CAST", "unused_parameter")
package androidx.core.content

import android.content.Context
import android.content.Intent
import android.net.Uri

object FileProvider {
    @JvmStatic
    fun getUriForFile(context: Context, authority: String, file: java.io.File): Uri =
        Uri.fromFile(file)
}
