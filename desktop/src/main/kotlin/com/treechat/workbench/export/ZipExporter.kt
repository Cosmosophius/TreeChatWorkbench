// 桌面替身：export.ZipExporter —— 打包主体逐行复刻安卓版（同 Markdown 口径、
// 同 .talktree JSON、同目录递归），差异仅 shareFile：Intent 分享 → 保存对话框。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.export

import android.content.Context
import com.google.gson.GsonBuilder
import com.treechat.workbench.data.db.ConversationDao
import com.treechat.workbench.data.model.ChatMessage
import com.treechat.workbench.data.model.Conversation
import com.treechat.workbench.data.model.ConversationNode
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
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

object ZipExporter {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 打包导出选定的文件夹和对话至 ZIP 文件（同安卓签名）。 */
    suspend fun exportToZip(
        context: Context,
        conversationDao: ConversationDao,
        targetFolders: List<Folder>,
        targetConversations: List<Conversation>,
        allFolders: List<Folder> = emptyList(),
        allConversations: List<Conversation> = emptyList(),
        zipNamePrefix: String = "TalkTree_Export",
    ): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = File(exportDir, "${zipNamePrefix}_$timeStamp.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for (conv in targetConversations) {
                exportConversationToZip(conversationDao, conv, basePath = "", zos)
            }
            for (folder in targetFolders) {
                exportFolderRecursively(
                    conversationDao = conversationDao,
                    folder = folder,
                    allFolders = allFolders,
                    allConversations = allConversations,
                    basePath = "",
                    zos = zos,
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
        zos: ZipOutputStream,
    ) {
        val safeFolderName = sanitizeFileName(folder.name)
        val currentPath = if (basePath.isEmpty()) "$safeFolderName/" else "$basePath$safeFolderName/"

        zos.putNextEntry(ZipEntry(currentPath))
        zos.closeEntry()

        val childConversations = allConversations.filter { it.folderId == folder.id }
        for (conv in childConversations) {
            exportConversationToZip(conversationDao, conv, basePath = currentPath, zos)
        }

        val childFolders = allFolders.filter { it.parentFolderId == folder.id }
        for (subFolder in childFolders) {
            exportFolderRecursively(
                conversationDao = conversationDao,
                folder = subFolder,
                allFolders = allFolders,
                allConversations = allConversations,
                basePath = currentPath,
                zos = zos,
            )
        }
    }

    /** 单个会话导出为 MD 与 .talktree 写入 ZIP（共享 TalkTreeBundle 字段不变）。 */
    suspend fun exportConversationToZip(
        conversationDao: ConversationDao,
        conversation: Conversation,
        basePath: String,
        zos: ZipOutputStream,
    ) {
        val safeTitle = sanitizeFileName(conversation.title)
        val nodes = conversationDao.getNodesForConversation(conversation.id)
        val allMessages = mutableListOf<ChatMessage>()
        for (node in nodes) {
            allMessages.addAll(conversationDao.getMessagesForNode(node.id))
        }

        val mdContent = buildConversationMarkdown(conversation, nodes, allMessages)
        val mdEntryName = "$basePath$safeTitle.md"
        zos.putNextEntry(ZipEntry(mdEntryName))
        zos.write(mdContent.toByteArray(Charsets.UTF_8))
        zos.closeEntry()

        val bundle = TalkTreeBundle(
            version = 1,
            appName = "TalkTree",
            exportedAt = System.currentTimeMillis(),
            conversation = conversation,
            nodes = nodes,
            messages = allMessages,
        )
        val json = gson.toJson(bundle)
        val talkTreeEntryName = "$basePath$safeTitle.talktree"
        zos.putNextEntry(ZipEntry(talkTreeEntryName))
        zos.write(json.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildConversationMarkdown(
        conversation: Conversation,
        nodes: List<ConversationNode>,
        messages: List<ChatMessage>,
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
                    appendLine("### $roleLabel (${dateFormat.format(Date(msg.createdAt))})")
                    appendLine()
                    appendLine(msg.content)
                    appendLine()
                }
            }
            appendLine("---")
            appendLine()
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').ifBlank { "Item" }

    /** 桌面分享链接：Intent getSystemService 降级；弹「保存为」（默认原文件名）。 */
    fun shareFile(
        context: Context,
        file: File,
        mimeType: String = "application/zip",
        title: String = "分享导出的压缩包",
    ) {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser()
            chooser.dialogTitle = title
            chooser.selectedFile = File(file.name)
            chooser.fileFilter = FileNameExtensionFilter("压缩包 (*.zip)", "zip")
            val rc = chooser.showSaveDialog(null)
            if (rc == JFileChooser.APPROVE_OPTION) {
                runCatching {
                    val target = chooser.selectedFile
                    file.copyTo(
                        if (target.name.substringAfterLast('.', "") == "zip") target
                        else File(target.parentFile, "${target.name}.zip"),
                        overwrite = true,
                    )
                }
            }
        }
    }
}
