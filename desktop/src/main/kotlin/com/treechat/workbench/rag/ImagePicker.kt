// 桌面替身：rag.ImagePicker（AiStreamClient 只用 splitDataUri/MAX_IMAGES）。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.rag

object ImagePicker {
    const val MAX_IMAGES = 4

    /** "data:image/jpeg;base64,xxxx" → (mediaType, base64)；拆不开返回 null。 */
    fun splitDataUri(dataUri: String): Pair<String, String>? {
        val marker = ";base64,"
        val idx = dataUri.indexOf(marker)
        if (idx <= 0 || !dataUri.startsWith("data:")) return null
        val mediaType = dataUri.removePrefix("data:").substring(0, idx - 5).takeIf { it.isNotEmpty() }
            ?: "application/octet-stream"
        val payload = dataUri.substring(idx + marker.length).trim()
        if (payload.isBlank()) return null
        return mediaType to payload
    }

    fun toDataUri(bytes: ByteArray, mediaType: String = "image/jpeg"): String =
        "data:$mediaType;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}
