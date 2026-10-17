// 充面层：android.database.Cursor（java.io.Closeable 直接实现）。
@file:Suppress("unused")
package android.database

interface Cursor : java.io.Closeable {
    fun getColumnIndex(columnName: String): Int
    fun getColumnIndexOrThrow(columnName: String): Int
    fun moveToFirst(): Boolean
    fun getString(columnIndex: Int): String?
    fun moveToNext(): Boolean
    override fun close()
}
