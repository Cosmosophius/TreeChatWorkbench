// 充面层：android.net.Uri（桌面以文件路径为本体）android.app.Application。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package android.net

import java.io.File

class Uri private constructor(val pathValue: String) {
    val scheme: String? get() = runCatching { java.net.URI(pathValue).scheme }.getOrNull()
        ?: if (pathValue.contains("://")) pathValue.substringBefore("://").takeIf { it.isNotEmpty() } else null
    val path: String? get() = runCatching { java.net.URI(pathValue).path }.getOrNull() ?: pathValue
    val encodedPath: String?
        get() = path
    val lastPathSegment: String?
        get() = path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: pathValue.substringAfterLast('/').takeIf { it.isNotEmpty() }

    fun toFile(): File = File(path ?: pathValue)

    override fun toString(): String = pathValue

    companion object {
        @JvmStatic
        fun parse(uri: String): Uri = Uri(uri)

        @JvmStatic
        fun fromFile(file: File): Uri = Uri("file://${file.absolutePath}")

        @JvmStatic
        fun fromPath(path: String): Uri = Uri(path)
    }
}
