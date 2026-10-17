// 充面层：android.util.Base64（桌面走 java.util.Base64）。
@file:Suppress("unused")
package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2
    const val NO_PADDING = 1
    const val NO_PADDING_OR_WRAP = 1 or 2
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray?, flags: Int): String {
        if (input == null) return ""
        val padded = java.util.Base64.getEncoder().encodeToString(input)
        if (flags and URL_SAFE != 0) {
            return java.util.Base64.getUrlEncoder().encodeToString(input)
        }
        return if (flags and NO_PADDING != 0) {
            java.util.Base64.getEncoder().withoutPadding().encodeToString(input)
        } else {
            padded
        }
    }

    @JvmStatic
    fun decode(str: String?, flags: Int): ByteArray {
        if (str == null) return ByteArray(0)
        return runCatching { java.util.Base64.getDecoder().decode(str) }
            .getOrElse { java.util.Base64.getMimeDecoder().decode(str) }
    }

    @JvmStatic
    fun encodeToString(input: ByteArray?, offset: Int, length: Int, flags: Int): String =
        encodeToString(input?.copyOfRange(offset, offset + length), flags)
}
