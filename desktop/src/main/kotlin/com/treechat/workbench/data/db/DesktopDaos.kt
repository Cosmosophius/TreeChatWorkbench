// 桌面版 ConversationDao / FolderDao / HighlightDao：sqlite-jdbc 手写实现，
// SQL 逐条对齐安卓 @Query 契约（见 data/db/*Dao.kt）。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.data.db

import com.treechat.workbench.data.model.ChatMessage
import com.treechat.workbench.data.model.Conversation
import com.treechat.workbench.data.model.ConversationNode
import com.treechat.workbench.data.model.Folder
import com.treechat.workbench.data.model.Highlight
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// ================= 帮手（三 DAO 共用） =================

private fun <T> rows(st: PreparedStatement, mapper: (ResultSet) -> T): List<T> =
    st.executeQuery().use { rs ->
        val out = ArrayList<T>()
        while (rs.next()) out.add(mapper(rs))
        out
    }

private fun <T> firstOrNull(st: PreparedStatement, mapper: (ResultSet) -> T): T? =
    st.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }

private fun nodeOf(rs: ResultSet): ConversationNode = ConversationNode(
    id = rs.getString("id"),
    parentId = rs.getString("parent_id"),
    conversationId = rs.getString("conversation_id"),
    title = rs.getString("title") ?: "主对话",
    originQuote = rs.getString("origin_quote"),
    sourceMessageId = rs.getString("source_message_id"),
    createdAt = rs.getLong("created_at"),
    summary = rs.getString("summary"),
)

private fun nodeBinder(node: ConversationNode) = { st: PreparedStatement ->
    st.setString(1, node.id)
    st.setString(2, node.parentId)
    st.setString(3, node.conversationId)
    st.setString(4, node.title)
    st.setString(5, node.originQuote)
    st.setString(6, node.sourceMessageId)
    st.setLong(7, node.createdAt)
    st.setString(8, node.summary)
}

private fun messageOf(rs: ResultSet): ChatMessage = ChatMessage(
    id = rs.getString("id"),
    nodeId = rs.getString("node_id"),
    role = rs.getString("role") ?: "assistant",
    content = rs.getString("content") ?: "",
    createdAt = rs.getLong("created_at"),
    tokenCount = rs.getInt("token_count"),
    isStreaming = rs.getInt("is_streaming") == 1,
    searchSourcesJson = rs.getString("search_sources"),
    imagesJson = rs.getString("images_json"),
    reasoningContent = rs.getString("reasoning_content"),
    knowledgeSourcesJson = rs.getString("knowledge_sources"),
)

private suspend fun Connection.withIoWrite(sql: String, binder: (PreparedStatement) -> Unit, mask: Int) {
    withContext(Dispatchers.IO) {
        synchronized(this@withIoWrite) { exec(sql, binder) }
    }
    DbChanges.bump(mask)
}

// ================= ConversationDao =================

class DesktopConversationDao(private val connection: Connection) : ConversationDao {

    override suspend fun insertNode(node: ConversationNode) =
        connection.withIoWrite(
            "REPLACE INTO conversation_nodes (id, parent_id, conversation_id, title, origin_quote, source_message_id, created_at, summary) VALUES (?,?,?,?,?,?,?,?)",
            nodeBinder(node), DbChanges.NODES,
        )

    override suspend fun updateNode(node: ConversationNode) = insertNode(node)

    override suspend fun getNodeById(id: String): ConversationNode? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM conversation_nodes WHERE id = ? LIMIT 1").use {
                it.setString(1, id); firstOrNull(it, ::nodeOf)
            }
        }
    }

    override fun observeNodeById(id: String): Flow<ConversationNode?> =
        observe(DbChanges.NODES) { getNodeById(id) }

    override suspend fun getRootNodeForConversation(conversationId: String): ConversationNode? =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT * FROM conversation_nodes WHERE conversation_id = ? AND parent_id IS NULL ORDER BY created_at ASC LIMIT 1"
                ).use { it.setString(1, conversationId); firstOrNull(it, ::nodeOf) }
            }
        }

    override fun observeNodesForConversation(conversationId: String): Flow<List<ConversationNode>> =
        observe(DbChanges.NODES) { getNodesForConversation(conversationId) }

    override fun observeQuotedNodes(): Flow<List<ConversationNode>> =
        observe(DbChanges.NODES) {
            withContext(Dispatchers.IO) {
                synchronized(connection) {
                    connection.prepareStatement(
                        "SELECT * FROM conversation_nodes WHERE origin_quote IS NOT NULL AND TRIM(origin_quote) <> '' ORDER BY created_at DESC"
                    ).use { rows(it, ::nodeOf) }
                }
            }
        }

    override suspend fun getNodesForConversation(conversationId: String): List<ConversationNode> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT * FROM conversation_nodes WHERE conversation_id = ? ORDER BY created_at ASC"
                ).use { it.setString(1, conversationId); rows(it, ::nodeOf) }
            }
        }

    override fun observeChildrenNodes(parentId: String): Flow<List<ConversationNode>> =
        observe(DbChanges.NODES) {
            withContext(Dispatchers.IO) {
                synchronized(connection) {
                    connection.prepareStatement(
                        "SELECT * FROM conversation_nodes WHERE parent_id = ? ORDER BY created_at ASC"
                    ).use { it.setString(1, parentId); rows(it, ::nodeOf) }
                }
            }
        }

    override suspend fun deleteNodeById(id: String) =
        connection.withIoWrite("DELETE FROM conversation_nodes WHERE id = ?", { it.setString(1, id) }, DbChanges.NODES)

    override suspend fun deleteNodesByConversation(conversationId: String) =
        connection.withIoWrite("DELETE FROM conversation_nodes WHERE conversation_id = ?", { it.setString(1, conversationId) }, DbChanges.NODES)

    override suspend fun countNodesForConversation(conversationId: String): Int =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement("SELECT COUNT(*) FROM conversation_nodes WHERE conversation_id = ?").use {
                    it.setString(1, conversationId)
                    it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }
        }

    override suspend fun insertMessage(message: ChatMessage) =
        connection.withIoWrite(
            "REPLACE INTO chat_messages (id, node_id, role, content, created_at, token_count, is_streaming, search_sources, images_json, reasoning_content, knowledge_sources) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            { st ->
                st.setString(1, message.id)
                st.setString(2, message.nodeId)
                st.setString(3, message.role)
                st.setString(4, message.content)
                st.setLong(5, message.createdAt)
                st.setInt(6, message.tokenCount)
                st.setInt(7, if (message.isStreaming) 1 else 0)
                st.setString(8, message.searchSourcesJson)
                st.setString(9, message.imagesJson)
                st.setString(10, message.reasoningContent)
                st.setString(11, message.knowledgeSourcesJson)
            },
            DbChanges.MESSAGES,
        )

    override suspend fun updateMessage(message: ChatMessage) = insertMessage(message)

    override fun observeMessagesForNode(nodeId: String): Flow<List<ChatMessage>> =
        observe(DbChanges.MESSAGES) { getMessagesForNode(nodeId) }

    override suspend fun getMessagesForNode(nodeId: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement("SELECT * FROM chat_messages WHERE node_id = ? ORDER BY created_at ASC").use {
                    it.setString(1, nodeId); rows(it, ::messageOf)
                }
            }
        }

    override suspend fun deleteMessage(messageId: String) =
        connection.withIoWrite("DELETE FROM chat_messages WHERE id = ?", { it.setString(1, messageId) }, DbChanges.MESSAGES)

    override suspend fun getNodeIdForMessage(messageId: String): String? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT node_id FROM chat_messages WHERE id = ? LIMIT 1").use {
                it.setString(1, messageId); firstOrNull(it) { rs -> rs.getString(1) }
            }
        }
    }

    override suspend fun conversationIdsContaining(keyword: String): List<String> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT DISTINCT n.conversation_id FROM chat_messages m JOIN conversation_nodes n ON n.id = m.node_id WHERE m.content LIKE '%' || ? || '%' LIMIT 300"
                ).use { it.setString(1, keyword); rows(it) { rs -> rs.getString(1) } }
            }
        }

    override suspend fun searchNodeTitles(keyword: String, conversationId: String?, limit: Int): List<NodeTitleHit> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT id, conversation_id AS conversationId, parent_id AS parentId, title, created_at AS createdAt FROM conversation_nodes WHERE title LIKE '%' || ? || '%' AND (? IS NULL OR conversation_id = ?) LIMIT ?"
                ).use {
                    it.setString(1, keyword)
                    it.setString(2, conversationId)
                    it.setString(3, conversationId)
                    it.setInt(4, limit)
                    rows(it) { rs ->
                        NodeTitleHit(
                            id = rs.getString("id"),
                            conversationId = rs.getString("conversationId"),
                            parentId = rs.getString("parentId"),
                            title = rs.getString("title"),
                            createdAt = rs.getLong("createdAt"),
                        )
                    }
                }
            }
        }

    override suspend fun searchMessageContent(keyword: String, conversationId: String?, limit: Int): List<MessageContentHit> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT m.id AS messageId, m.node_id AS nodeId, m.role AS role, m.content AS content, m.created_at AS createdAt, n.conversation_id AS conversationId, n.title AS nodeTitle FROM chat_messages m JOIN conversation_nodes n ON n.id = m.node_id WHERE m.content LIKE '%' || ? || '%' AND (? IS NULL OR n.conversation_id = ?) ORDER BY m.created_at DESC LIMIT ?"
                ).use {
                    it.setString(1, keyword)
                    it.setString(2, conversationId)
                    it.setString(3, conversationId)
                    it.setInt(4, limit)
                    rows(it) { rs ->
                        MessageContentHit(
                            messageId = rs.getString("messageId"),
                            nodeId = rs.getString("nodeId"),
                            role = rs.getString("role"),
                            content = rs.getString("content"),
                            createdAt = rs.getLong("createdAt"),
                            conversationId = rs.getString("conversationId"),
                            nodeTitle = rs.getString("nodeTitle"),
                        )
                    }
                }
            }
        }

    override suspend fun getAllConversationSummaries(): List<ConversationSummary> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement("SELECT id, title, created_at AS createdAt FROM conversations ORDER BY created_at DESC").use {
                    rows(it) { rs ->
                        ConversationSummary(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            createdAt = rs.getLong("createdAt"),
                        )
                    }
                }
            }
        }

    override suspend fun getLatestUserMessage(conversationId: String): String? =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT content FROM chat_messages WHERE node_id IN (SELECT id FROM conversation_nodes WHERE conversation_id = ?) AND role = 'user' ORDER BY created_at DESC LIMIT 1"
                ).use {
                    it.setString(1, conversationId); firstOrNull(it) { rs -> rs.getString(1) }
                }
            }
        }

    override suspend fun getAncestorChain(targetNodeId: String): List<ConversationNode> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    """
                    WITH RECURSIVE AncestorTree AS (
                        SELECT id, parent_id, conversation_id, title, origin_quote,
                               source_message_id, created_at, summary, 0 AS depth
                        FROM conversation_nodes
                        WHERE id = ?

                        UNION ALL

                        SELECT p.id, p.parent_id, p.conversation_id, p.title, p.origin_quote,
                               p.source_message_id, p.created_at, p.summary, a.depth + 1
                        FROM conversation_nodes p
                        INNER JOIN AncestorTree a ON p.id = a.parent_id
                    )
                    SELECT id, parent_id, conversation_id, title, origin_quote,
                           source_message_id, created_at, summary
                    FROM AncestorTree
                    ORDER BY depth DESC
                    """
                ).use {
                    it.setString(1, targetNodeId); rows(it, ::nodeOf)
                }
            }
        }
}

// ================= FolderDao =================

private fun folderOf(rs: ResultSet): Folder = Folder(
    id = rs.getString("id"),
    parentFolderId = rs.getString("parent_folder_id"),
    name = rs.getString("name") ?: "",
    sortOrder = rs.getInt("sort_order"),
    createdAt = rs.getLong("created_at"),
    isPinned = rs.getInt("is_pinned") == 1,
    isFavorite = rs.getInt("is_favorite") == 1,
)

private fun folderBinder(folder: Folder) = { st: PreparedStatement ->
    st.setString(1, folder.id)
    st.setString(2, folder.parentFolderId)
    st.setString(3, folder.name)
    st.setInt(4, folder.sortOrder)
    st.setLong(5, folder.createdAt)
    st.setInt(6, if (folder.isPinned) 1 else 0)
    st.setInt(7, if (folder.isFavorite) 1 else 0)
}

private fun conversationOf(rs: ResultSet): Conversation = Conversation(
    id = rs.getString("id"),
    folderId = rs.getString("folder_id"),
    title = rs.getString("title") ?: "",
    sourceOrigin = rs.getString("source_origin"),
    createdAt = rs.getLong("created_at"),
    updatedAt = rs.getLong("updated_at"),
    isPinned = rs.getInt("is_pinned") == 1,
    isFavorite = rs.getInt("is_favorite") == 1,
)

private fun conversationBinder(conversation: Conversation) = { st: PreparedStatement ->
    st.setString(1, conversation.id)
    st.setString(2, conversation.folderId)
    st.setString(3, conversation.title)
    st.setString(4, conversation.sourceOrigin)
    st.setLong(5, conversation.createdAt)
    st.setLong(6, conversation.updatedAt)
    st.setInt(7, if (conversation.isPinned) 1 else 0)
    st.setInt(8, if (conversation.isFavorite) 1 else 0)
}

class DesktopFolderDao(private val connection: Connection) : FolderDao {

    override suspend fun insertFolder(folder: Folder) =
        connection.withIoWrite(
            "REPLACE INTO folders (id, parent_folder_id, name, sort_order, created_at, is_pinned, is_favorite) VALUES (?,?,?,?,?,?,?)",
            folderBinder(folder), DbChanges.FOLDERS,
        )

    override suspend fun updateFolder(folder: Folder) = insertFolder(folder)

    override suspend fun deleteFolderById(id: String) =
        connection.withIoWrite("DELETE FROM folders WHERE id = ?", { it.setString(1, id) }, DbChanges.FOLDERS)

    override fun observeAllFolders(): Flow<List<Folder>> =
        observe(DbChanges.FOLDERS) { getAllFolders() }

    override suspend fun getAllFolders(): List<Folder> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM folders ORDER BY is_pinned DESC, sort_order ASC, created_at ASC").use {
                rows(it, ::folderOf)
            }
        }
    }

    override suspend fun getFolderById(id: String): Folder? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM folders WHERE id = ? LIMIT 1").use {
                it.setString(1, id); firstOrNull(it, ::folderOf)
            }
        }
    }

    override suspend fun getChildFolders(parentId: String): List<Folder> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM folders WHERE parent_folder_id = ?").use {
                it.setString(1, parentId); rows(it, ::folderOf)
            }
        }
    }

    override suspend fun insertConversation(conversation: Conversation) =
        connection.withIoWrite(
            "REPLACE INTO conversations (id, folder_id, title, source_origin, created_at, updated_at, is_pinned, is_favorite) VALUES (?,?,?,?,?,?,?,?)",
            conversationBinder(conversation), DbChanges.CONVERSATIONS,
        )

    override suspend fun updateConversation(conversation: Conversation) = insertConversation(conversation)

    override suspend fun deleteConversationById(id: String) =
        connection.withIoWrite("DELETE FROM conversations WHERE id = ?", { it.setString(1, id) }, DbChanges.CONVERSATIONS)

    override fun observeAllConversations(): Flow<List<Conversation>> =
        observe(DbChanges.CONVERSATIONS) { getAllConversations() }

    private suspend fun getAllConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM conversations ORDER BY is_pinned DESC, updated_at DESC").use {
                rows(it, ::conversationOf)
            }
        }
    }

    override suspend fun getConversationById(id: String): Conversation? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM conversations WHERE id = ? LIMIT 1").use {
                it.setString(1, id); firstOrNull(it, ::conversationOf)
            }
        }
    }

    override suspend fun getConversationBySourceOrigin(sourceOrigin: String): Conversation? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM conversations WHERE source_origin = ? LIMIT 1").use {
                it.setString(1, sourceOrigin); firstOrNull(it, ::conversationOf)
            }
        }
    }

    override suspend fun touchConversation(id: String, timestamp: Long) =
        connection.withIoWrite("UPDATE conversations SET updated_at = ? WHERE id = ?", {
            it.setLong(1, timestamp); it.setString(2, id)
        }, DbChanges.CONVERSATIONS)

    override suspend fun moveConversationToFolder(id: String, folderId: String?) =
        connection.withIoWrite("UPDATE conversations SET folder_id = ? WHERE id = ?", {
            it.setString(1, folderId); it.setString(2, id)
        }, DbChanges.CONVERSATIONS)

    override suspend fun moveFolder(id: String, parentId: String?) =
        connection.withIoWrite("UPDATE folders SET parent_folder_id = ? WHERE id = ?", {
            it.setString(1, parentId); it.setString(2, id)
        }, DbChanges.FOLDERS)
}

// ================= HighlightDao =================

private fun highlightOf(rs: ResultSet): Highlight = Highlight(
    id = rs.getString("id"),
    messageId = rs.getString("message_id"),
    nodeId = rs.getString("node_id"),
    startOffset = rs.getInt("start_offset"),
    endOffset = rs.getInt("end_offset"),
    colorArgb = rs.getInt("color_argb"),
    note = rs.getString("note"),
    createdAt = rs.getLong("created_at"),
)

class DesktopHighlightDao(private val connection: Connection) : HighlightDao {

    override suspend fun insert(highlight: Highlight) =
        connection.withIoWrite(
            "REPLACE INTO message_highlights (id, message_id, node_id, start_offset, end_offset, color_argb, note, created_at) VALUES (?,?,?,?,?,?,?,?)",
            { st ->
                st.setString(1, highlight.id)
                st.setString(2, highlight.messageId)
                st.setString(3, highlight.nodeId)
                st.setInt(4, highlight.startOffset)
                st.setInt(5, highlight.endOffset)
                st.setInt(6, highlight.colorArgb)
                st.setString(7, highlight.note)
                st.setLong(8, highlight.createdAt)
            },
            DbChanges.HIGHLIGHTS,
        )

    override suspend fun deleteById(id: String) =
        connection.withIoWrite("DELETE FROM message_highlights WHERE id = ?", { it.setString(1, id) }, DbChanges.HIGHLIGHTS)

    override suspend fun deleteExact(messageId: String, start: Int, end: Int) =
        connection.withIoWrite(
            "DELETE FROM message_highlights WHERE message_id = ? AND start_offset = ? AND end_offset = ?",
            { it.setString(1, messageId); it.setInt(2, start); it.setInt(3, end) },
            DbChanges.HIGHLIGHTS,
        )

    override suspend fun deleteOverlapping(messageId: String, start: Int, end: Int) =
        connection.withIoWrite(
            "DELETE FROM message_highlights WHERE message_id = ? AND NOT (end_offset <= ? OR start_offset >= ?)",
            { it.setString(1, messageId); it.setInt(2, start); it.setInt(3, end) },
            DbChanges.HIGHLIGHTS,
        )

    override suspend fun deleteLatest(messageId: String) =
        connection.withIoWrite(
            "DELETE FROM message_highlights WHERE id IN (SELECT id FROM message_highlights WHERE message_id = ? ORDER BY created_at DESC LIMIT 1)",
            { it.setString(1, messageId) },
            DbChanges.HIGHLIGHTS,
        )

    override fun observeForNode(nodeId: String): Flow<List<Highlight>> = observe(DbChanges.HIGHLIGHTS) {
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT * FROM message_highlights WHERE node_id = ? ORDER BY created_at ASC"
                ).use { it.setString(1, nodeId); rows(it, ::highlightOf) }
            }
        }
    }

    override suspend fun getForMessage(messageId: String): List<Highlight> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM message_highlights WHERE message_id = ?").use {
                it.setString(1, messageId); rows(it, ::highlightOf)
            }
        }
    }
}
