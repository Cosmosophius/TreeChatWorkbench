// 桌面替身：data.reader.CloudFolderStore（handoff「增量二」第 1 点的指定做法）。
// SAF 树 URI 在桌面等价物 = 一个普通文件夹。同签名：folderUri/hasFolder/setFolder/
// clear/listChildren/findByName/putFile/readFile/delete + CloudItem。
// 「documentId」语义 = 文件的绝对路径；「树 URI」= "file://<目录绝对路径>"。
// 书架云盘 UI（拖书上传 / 面板 / 导入书架 / 存文件 / 换文件夹）原样复用。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.data.reader

import android.content.Context
import android.net.Uri
import java.io.File

class CloudFolderStore(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("treechat_cloud", Context.MODE_PRIVATE)

    /** 用户选定的目录（桌面：file:// URI）；null = 还没选。 */
    fun folderUri(): Uri? =
        prefs.getString(KEY_TREE, null)?.let { Uri.parse(it) }

    fun hasFolder(): Boolean = folderUri() != null

    private fun folderFile(): File? = folderUri()?.toFile()

    /** 记录用户选定的目录（SAF 的 takePersistableUriPermission 在桌面为空操作）。 */
    fun setFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_TREE, treeUri.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TREE).apply()
    }

    /** 目录里的一个条目。 */
    data class CloudItem(
        val documentId: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val mime: String,
    )

    /** 列出目录内容（按修改时间倒序）。目录失效（被用户删掉）返回 null。 */
    fun listChildren(): List<CloudItem>? {
        val dir = folderFile() ?: return null
        if (!dir.isDirectory) return null
        return dir.listFiles()?.map { child ->
            CloudItem(
                documentId = child.absolutePath,
                name = child.name,
                size = if (child.isFile) child.length() else 0L,
                lastModified = child.lastModified(),
                mime = guessMime(child.name),
            )
        }?.sortedByDescending { it.lastModified }
    }

    /** 按名字找现有文档（同名校验用）。找不到返回 null。 */
    fun findByName(name: String): Uri? {
        val dir = folderFile() ?: return null
        val target = File(dir, name)
        return if (target.isFile) Uri.fromFile(target) else null
    }

    /** 写入一个文件：同名覆盖内容，不存在则新建。目录失效抛出（调用方提示重选）。 */
    fun putFile(name: String, mime: String, bytes: ByteArray): Uri {
        val dir = folderFile() ?: throw IllegalStateException("尚未选择云端文件夹")
        if (!dir.isDirectory) {
            throw IllegalStateException("云端文件夹已失效，请重新选择")
        }
        val target = File(dir, name)
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        target.writeBytes(bytes)
        return Uri.fromFile(target)
    }

    /** 读取一个云端文档到字节数组。 */
    fun readFile(id: String): ByteArray {
        val file = File(id)
        if (!file.isFile) throw IllegalStateException("云端文件读取失败")
        return file.readBytes()
    }

    /** 删除一个云端文档（面板上的删除键）。 */
    fun delete(id: String) {
        File(id).takeIf { it.isFile }?.delete()
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }

    companion object {
        private const val KEY_TREE = "cloud_tree_uri"
    }
}
