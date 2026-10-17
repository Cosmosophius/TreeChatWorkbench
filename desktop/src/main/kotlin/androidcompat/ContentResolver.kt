// 充面层：android.content.ContentResolver（桌面 Uri=文件路径）。
@file:Suppress("unused")
package android.content

class ContentResolver {
    fun openInputStream(uri: android.net.Uri?): java.io.InputStream? = try {
        val file = uri?.toFile() ?: return null
        if (file.exists()) file.inputStream() else null
    } catch (_: Exception) { null }

    fun query(
        uri: android.net.Uri?,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): android.database.Cursor? {
        if (uri == null) return null
        val name = uri.toFile().name
        val columnNames = projection?.toList().orEmpty().ifEmpty { listOf(android.provider.OpenableColumns.DISPLAY_NAME) }
        return SingleRowCursor(listOf(name), columnNames)
    }
}

/** 单行结果游标（文件展示名）。 */
class SingleRowCursor(
    private val row: List<String>,
    private val columns: List<String>,
) : android.database.Cursor {
    private var position = -1

    override fun getColumnIndex(columnName: String): Int = columns.indexOf(columnName)
    override fun getColumnIndexOrThrow(columnName: String): Int {
        val index = columns.indexOf(columnName)
        require(index >= 0) { "unknown column: $columnName" }
        return index
    }
    override fun moveToFirst(): Boolean { position = 0; return row.isNotEmpty() }
    override fun moveToNext(): Boolean {
        if (position + 1 < row.size) { position++; return true }
        return false
    }
    override fun getString(columnIndex: Int): String? = row.getOrNull(columnIndex)
    override fun close() {}
}
