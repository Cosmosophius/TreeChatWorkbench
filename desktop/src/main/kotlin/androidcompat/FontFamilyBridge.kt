// 充面层：让共享代码里的 FontFamily(android.graphics.Typeface) / Bitmap.asImageBitmap()
// 在桌面侧照常解析（MarkdownOffsets 楷体/仿宋档、LiveTextPageView 页位图）。
@file:Suppress("unused")
package androidx.compose.ui.text.font

fun FontFamily(typeface: android.graphics.Typeface): FontFamily {
    // 桌面 v1：不加载真楷体/仿宋文件，按字族语义映射到内建族。
    val first = typeface.names.firstOrNull()?.lowercase().orEmpty()
    return when {
        first.contains("kaiti") || first.contains("楷") -> FontFamily.Serif
        first.contains("fangsong") || first.contains("仿") -> FontFamily.Serif
        first.contains("hei") || first.contains("黑") -> FontFamily.SansSerif
        else -> FontFamily.SansSerif
    }
}
