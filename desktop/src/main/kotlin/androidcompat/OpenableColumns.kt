// 充面层：ContentResolver/FileIO（桌面 Uri=文件路径，展示名=文件名）。
@file:Suppress("unused")
package android.provider

object OpenableColumns {
    @JvmField
    val DISPLAY_NAME: String = "_display_name"

    @JvmField
    val SIZE: String = "_size"
}
