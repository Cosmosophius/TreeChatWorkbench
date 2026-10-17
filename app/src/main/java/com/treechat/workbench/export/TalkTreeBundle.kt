package com.treechat.workbench.export

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.treechat.workbench.data.model.ChatMessage
import com.treechat.workbench.data.model.Conversation
import com.treechat.workbench.data.model.ConversationNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 条畅私有跨平台档案格式（`.talktree`）。
 *
 * 格式规范：内部为标准 JSON，未来移植至 macOS/Windows/Linux 时直接共用此规范。
 */
data class TalkTreeBundle(
    val version: Int = 1,
    val appName: String = "TalkTree",
    val exportedAt: Long = System.currentTimeMillis(),
    val conversation: Conversation,
    val nodes: List<ConversationNode>,
    val messages: List<ChatMessage>
) {
    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        /**
         * 导出为 .talktree 档案文件并存入缓存导出目录。
         */
        suspend fun exportToFile(
            context: Context,
            conversation: Conversation,
            nodes: List<ConversationNode>,
            messages: List<ChatMessage>
        ): File = withContext(Dispatchers.IO) {
            val bundle = TalkTreeBundle(
                version = 1,
                appName = "TalkTree",
                exportedAt = System.currentTimeMillis(),
                conversation = conversation,
                nodes = nodes,
                messages = messages
            )
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeTitle = conversation.title
                .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                .trim('_')
                .ifBlank { "Conversation" }
            val file = File(exportDir, "${safeTitle}_${System.currentTimeMillis()}.talktree")
            file.writeText(gson.toJson(bundle), Charsets.UTF_8)
            file
        }

        /**
         * 从 InputStream 反序列化解析 .talktree 档案。
         */
        suspend fun importFromStream(stream: InputStream): TalkTreeBundle = withContext(Dispatchers.IO) {
            val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            gson.fromJson(json, TalkTreeBundle::class.java)
        }
    }
}
