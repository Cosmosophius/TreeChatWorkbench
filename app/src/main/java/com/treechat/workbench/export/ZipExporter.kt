package com.treechat.workbench.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import com.treechat.workbench.data.db.ConversationDao
import com.treechat.workbench.data.db.FolderDao
import com.treechat.workbench.data.model.Conversation
import com.treechat.workbench.data.model.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文件夹与会话的树状打包压缩导出器（ZIP）。
 *
 * 支持单个对话、单个文件夹以及多选条目的递归全量打包：
 * - 保持文件夹层级目录；
 * - 每个对话生成阅读友好的完整 Markdown 记录文档（.md）；
 * - 同时包含机器可无损还原的 .talktree 跨平台私有档案；
 * - 自动调起系统分享组件。
 */
object ZipExporter {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 打包导出选定的文件夹和对话至 ZIP 文件。
     */
    suspend fun exportToZip(
        context: Context,
        conversationDao: ConversationDao,
        targetFolders: List<Folder>,
        targetConversations: List<Conversation>,
        allFolders: List<Folder> = emptyList(),
        allConversations: List<Conversation> = emptyList(),
        zipNamePrefix: String = "TalkTree_Export"
    ): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = File(exportDir, "${zipNamePrefix}_$timeStamp.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // 处理顶层指定导出的会话
            for (conv in targetConversations) {
                exportConversationToZip(conversationDao, conv, basePath = "", zos)
            }

            // 处理指定导出的文件夹及其包含的子孙
            for (folder in targetFolders) {
                exportFolderRecursively(
                    conversationDao = conversationDao,
                    folder = folder,
                    allFolders = allFolders,
                    allConversations = allConversations,
                    basePath = "",
                    zos = zos
                )
            }
        }

        zipFile
    }

    private suspend fun exportFolderRecursively(
        conversationDao: ConversationDao,
        folder: Folder,
        allFolders: List<Folder>,
        allConversations: List<Conversation>,
        basePath: String,
        zos: ZipOutputStream
    ) {
        val safeFolderName = sanitizeFileName(folder.name)
        val currentPath = if (basePath.isEmpty()) "$safeFolderName/" else "$basePath$safeFolderName/"

        // 写入目录条目
        zos.putNextEntry(ZipEntry(currentPath))
        zos.closeEntry()

        // 导出该文件夹下的所有直接会话
        val childConversations = allConversations.filter { it.folderId == folder.id }
        for (conv in childConversations) {
            exportConversationToZip(conversationDao, conv, basePath = currentPath, zos)
        }

        // 递归查找子文件夹
        val childFolders = allFolders.filter { it.parentFolderId == folder.id }
        for (subFolder in childFolders) {
            exportFolderRecursively(
                conversationDao = conversationDao,
                folder = subFolder,
                allFolders = allFolders,
                allConversations = allConversations,
                basePath = currentPath,
                zos = zos
            )
        }
    }

    /**
     * 把单个会话导出为 MD 和 .talktree 写入 ZIP 流中
     */
    suspend fun exportConversationToZip(
        conversationDao: ConversationDao,
        conversation: Conversation,
        basePath: String,
        zos: ZipOutputStream
    ) {
        val safeTitle = sanitizeFileName(conversation.title)
        val nodes = conversationDao.getNodesForConversation(conversation.id)
        val allMessages = mutableListOf<com.treechat.workbench.data.model.ChatMessage>()

        for (node in nodes) {
            val msgs = conversationDao.getMessagesForNode(node.id)
            allMessages.addAll(msgs)
        }

        // 1. 生成并写入 Markdown 文档
        val mdContent = buildConversationMarkdown(conversation, nodes, allMessages)
        val mdEntryName = "$basePath$safeTitle.md"
        zos.putNextEntry(ZipEntry(mdEntryName))
        zos.write(mdContent.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        // 2. 生成并写入 .talktree 档案文件
        val bundle = TalkTreeBundle(
            version = 1,
            appName = "TalkTree",
            exportedAt = System.currentTimeMillis(),
            conversation = conversation,
            nodes = nodes,
            messages = allMessages
        )
        val json = gson.toJson(bundle)
        val talkTreeEntryName = "$basePath$safeTitle.talktree"
        zos.putNextEntry(ZipEntry(talkTreeEntryName))
        zos.write(json.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildConversationMarkdown(
        conversation: Conversation,
        nodes: List<com.treechat.workbench.data.model.ConversationNode>,
        messages: List<com.treechat.workbench.data.model.ChatMessage>
    ): String = buildString {
        appendLine("# ${conversation.title}")
        appendLine()
        appendLine("> 创建时间：${dateFormat.format(Date(conversation.createdAt))}")
        appendLine("> 导出时间：${dateFormat.format(Date())}")
        appendLine("> 分支节点数：${nodes.size}")
        appendLine()
        appendLine("---")
        appendLine()

        val messagesByNode = messages.groupBy { it.nodeId }

        for ((index, node) in nodes.withIndex()) {
            appendLine("## 分支 ${index + 1}：${node.title}")
            if (!node.originQuote.isNullOrBlank()) {
                appendLine()
                appendLine("> **引用上下文**：${node.originQuote}")
                appendLine()
            }
            appendLine()

            val nodeMessages = messagesByNode[node.id].orEmpty()
            if (nodeMessages.isEmpty()) {
                appendLine("*(该分支暂无消息记录)*")
                appendLine()
            } else {
                for (msg in nodeMessages) {
                    val roleLabel = when (msg.role) {
                        "user" -> "👤 用户"
                        "assistant" -> "🤖 AI"
                        else -> "⚙️ ${msg.role}"
                    }
                    val timeStr = dateFormat.format(Date(msg.createdAt))
                    appendLine("### $roleLabel ($timeStr)")
                    appendLine()
                    appendLine(msg.content)
                    appendLine()
                }
            }
            appendLine("---")
            appendLine()
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "Item" }
    }

    /**
     * 调起系统分享打开导出的 ZIP
     */
    fun shareFile(context: Context, file: File, mimeType: String = "application/zip", title: String = "分享导出的压缩包") {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
