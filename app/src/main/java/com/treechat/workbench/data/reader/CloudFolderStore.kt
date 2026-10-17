package com.treechat.workbench.data.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF 持久化云盘目录（**零 OAuth、零 SDK**）。
 *
 * ## 机制
 * 用户在系统选择器（ACTION_OPEN_DOCUMENT_TREE）里选一次云端文件夹——
 * 比如 Drive 里建的"条畅"——应用通过 takePersistableUriPermission 拿到
 * **跨重启的读写权**。此后上传/列出/下载全部直接对这个树 URI 进行，
 * 不再弹任何选择器，也没有令牌、没有过期、没有 Client ID。
 *
 * 为什么这条路比 OAuth 好（用户已验证 SAF 能打开谷歌硬盘）：
 * 授权交给用户登录的那个 Drive 提供方，本软件对"是不是谷歌"完全无感 ——
 * 欢太云/坚果云/任何出现在系统选择器里的目录都能当这个文件夹用；
 * 桌面移植时把本类换成"普通文件夹"实现即可，UI 不动。
 *
 * 全部方法走 ContentResolver + DocumentsContract（框架件，无新依赖）。
 */
class CloudFolderStore(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("treechat_cloud", Context.MODE_PRIVATE)

    /** 用户选定的目录（树 URI）；null = 还没选。 */
    fun folderUri(): Uri? =
        prefs.getString(KEY_TREE, null)?.let { Uri.parse(it) }

    fun hasFolder(): Boolean = folderUri() != null

    /** 记录用户选定的目录，并持久化权限（跨重启可读写）。 */
    fun setFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
        val mime: String
    )

    private fun childrenUri(tree: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )

    /** 列出目录内容（按修改时间倒序）。目录失效（被用户在 Drive 里删掉）返回 null。 */
    fun listChildren(): List<CloudItem>? {
        val tree = folderUri() ?: return null
        return try {
            val out = ArrayList<CloudItem>()
            context.contentResolver.query(
                childrenUri(tree),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    out.add(
                        CloudItem(
                            documentId = cur.getString(0),
                            name = cur.getString(1) ?: "",
                            size = cur.getLong(2),
                            lastModified = cur.getLong(3),
                            mime = cur.getString(4) ?: ""
                        )
                    )
                }
            }
            out.sortedByDescending { it.lastModified }
        } catch (e: Exception) {
            // 典型情形：云端文件夹被删/账号登出 —— 权限仍在但读不到
            null
        }
    }

    /** 按**名字**找现有文档（同名校验用）。找不到返回 null。 */
    fun findByName(name: String): Uri? {
        val tree = folderUri() ?: return null
        return try {
            context.contentResolver.query(
                childrenUri(tree),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                            tree, cur.getString(0)
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写入一个文件：同名**覆盖**内容，不存在则新建。
     * 返回该文档的 URI；目录失效抛出（调用方提示重新选文件夹）。
     */
    fun putFile(name: String, mime: String, bytes: ByteArray): Uri {
        val tree = folderUri()
            ?: throw IllegalStateException("尚未选择云端文件夹")
        val existing = findByName(name)
        if (existing != null) {
            // "wt" = 截断重写；对 Drive 提供方等价于覆盖
            context.contentResolver.openOutputStream(existing, "wt")?.use { out ->
                out.write(bytes)
            } ?: throw IllegalStateException("云端文件写入失败（打开输出流被拒）")
            return existing
        }
        val created = DocumentsContract.createDocument(
            context.contentResolver, tree, mime, name
        ) ?: throw IllegalStateException("云端文件创建失败（提供方拒绝）")
        context.contentResolver.openOutputStream(created, "wt")?.use { out ->
            out.write(bytes)
        } ?: throw IllegalStateException("云端文件写入失败")
        return created
    }

    /** 读取一个云端文档到字节数组。 */
    fun readFile(id: String): ByteArray {
        val tree = folderUri() ?: throw IllegalStateException("尚未选择云端文件夹")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return context.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("云端文件读取失败")
    }

    /** 删除一个云端文档（面板上的删除键）。 */
    fun delete(id: String) {
        val tree = folderUri() ?: throw IllegalStateException("尚未选择云端文件夹")
        DocumentsContract.deleteDocument(
            context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(tree, id)
        )
    }

    companion object {
        private const val KEY_TREE = "cloud_tree_uri"
    }
}
