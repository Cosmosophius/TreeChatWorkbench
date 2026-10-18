// 桌面替身：data.db.AppDatabase —— sqlite-jdbc 顶替 Room（表结构与安卓 v10 一致，含同样的索引）。
// 不跑 migration：桌面库从 v10 起步、全新建表；安卓线动 migration 的纪律不约束这里。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.data.db

import android.content.Context
import com.treechat.desktop.DesktopAppEnvHolder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppDatabase private constructor(private val connection: Connection) {
    fun close() = runCatching { connection.close() }

    fun conversationDao(): ConversationDao = DesktopConversationDao(connection)
    fun folderDao(): FolderDao = DesktopFolderDao(connection)
    fun highlightDao(): HighlightDao = DesktopHighlightDao(connection)
    fun knowledgeDao(): KnowledgeDao = DesktopKnowledgeDao(connection)
    fun wordDao(): WordDao = DesktopWordDao(connection)

    /** Room 兼容面：共享代码有 `db.openHelper.writableDatabase.execSQL(...)` 的窄口。 */
    val openHelper: SupportOpenHelper get() = SupportOpenHelper(connection)

    private fun bootstrapSchema() {
        SCHEMA.forEach { sql ->
            try {
                connection.createStatement().use { it.execute(sql) }
            } catch (e: SQLException) {
                System.err.println("[desktop-db] schema failed: ${e.message}")
            }
        }
    }

    companion object {
        @JvmStatic
        @Volatile
        private var instance: AppDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: openOrCreate(DbFiles(context)).also { instance = it }
            }
        }

        private fun openOrCreate(dbFile: File): AppDatabase {
            dbFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            return AppDatabase(conn).also { it.bootstrapSchema() }
        }
    }
}

/** 数据库文件：桌面在 env.filesDir/database/ 下。 */
private fun DbFiles(context: Context): File =
    File(File(DesktopAppEnvHolder.env.filesDir.path, "database"), "tree_chat_workbench.db")

/** v10 全量建表 SQL（列名/类型与安卓 Room schema 对齐，含索引与外键）。 */
private val SCHEMA: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS conversation_nodes (
        id TEXT NOT NULL PRIMARY KEY,
        parent_id TEXT,
        conversation_id TEXT,
        title TEXT NOT NULL,
        origin_quote TEXT,
        source_message_id TEXT,
        created_at INTEGER NOT NULL,
        summary TEXT,
        FOREIGN KEY (parent_id) REFERENCES conversation_nodes(id) ON DELETE CASCADE
    )""",
    "CREATE INDEX IF NOT EXISTS index_conversation_nodes_parent_id ON conversation_nodes (parent_id)",
    "CREATE INDEX IF NOT EXISTS index_conversation_nodes_created_at ON conversation_nodes (created_at)",
    "CREATE INDEX IF NOT EXISTS index_conversation_nodes_conversation_id ON conversation_nodes (conversation_id)",
    """
    CREATE TABLE IF NOT EXISTS chat_messages (
        id TEXT NOT NULL PRIMARY KEY,
        node_id TEXT NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        token_count INTEGER NOT NULL,
        is_streaming INTEGER NOT NULL,
        search_sources TEXT,
        images_json TEXT,
        reasoning_content TEXT,
        knowledge_sources TEXT,
        FOREIGN KEY (node_id) REFERENCES conversation_nodes(id) ON DELETE CASCADE
    )""",
    "CREATE INDEX IF NOT EXISTS index_chat_messages_node_id ON chat_messages (node_id)",
    "CREATE INDEX IF NOT EXISTS index_chat_messages_created_at ON chat_messages (created_at)",
    """
    CREATE TABLE IF NOT EXISTS folders (
        id TEXT NOT NULL PRIMARY KEY,
        parent_folder_id TEXT,
        name TEXT NOT NULL,
        sort_order INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        is_pinned INTEGER NOT NULL,
        is_favorite INTEGER NOT NULL,
        FOREIGN KEY (parent_folder_id) REFERENCES folders(id) ON DELETE CASCADE
    )""",
    "CREATE INDEX IF NOT EXISTS index_folders_parent_folder_id ON folders (parent_folder_id)",
    """
    CREATE TABLE IF NOT EXISTS conversations (
        id TEXT NOT NULL PRIMARY KEY,
        folder_id TEXT,
        title TEXT NOT NULL,
        source_origin TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        is_pinned INTEGER NOT NULL,
        is_favorite INTEGER NOT NULL,
        FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE SET NULL
    )""",
    "CREATE INDEX IF NOT EXISTS index_conversations_folder_id ON conversations (folder_id)",
    """
    CREATE TABLE IF NOT EXISTS message_highlights (
        id TEXT NOT NULL PRIMARY KEY,
        message_id TEXT NOT NULL,
        node_id TEXT NOT NULL,
        start_offset INTEGER NOT NULL,
        end_offset INTEGER NOT NULL,
        color_argb INTEGER NOT NULL,
        note TEXT,
        created_at INTEGER NOT NULL,
        FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE
    )""",
    "CREATE INDEX IF NOT EXISTS index_message_highlights_message_id ON message_highlights (message_id)",
    "CREATE INDEX IF NOT EXISTS index_message_highlights_node_id ON message_highlights (node_id)",
    """
    CREATE TABLE IF NOT EXISTS knowledge_documents (
        id TEXT NOT NULL PRIMARY KEY,
        title TEXT NOT NULL,
        fileName TEXT NOT NULL,
        sourceType TEXT NOT NULL,
        charCount INTEGER NOT NULL,
        chunkCount INTEGER NOT NULL,
        enabled INTEGER NOT NULL,
        createdAt INTEGER NOT NULL,
        folderPath TEXT NOT NULL DEFAULT '',
        indexingScheme TEXT NOT NULL DEFAULT 'LOCAL_OFFLINE'
    )""",
    "CREATE INDEX IF NOT EXISTS index_knowledge_documents_title ON knowledge_documents (title)",
    """
    CREATE TABLE IF NOT EXISTS knowledge_chunks (
        id TEXT NOT NULL PRIMARY KEY,
        documentId TEXT NOT NULL,
        orderIndex INTEGER NOT NULL,
        text TEXT NOT NULL,
        charStart INTEGER NOT NULL,
        section TEXT,
        embedding TEXT,
        FOREIGN KEY (documentId) REFERENCES knowledge_documents(id) ON DELETE CASCADE
    )""",
    "CREATE INDEX IF NOT EXISTS index_knowledge_chunks_documentId ON knowledge_chunks (documentId)",
    "CREATE INDEX IF NOT EXISTS index_knowledge_chunks_documentId_orderIndex ON knowledge_chunks (documentId, orderIndex)",
    """
    CREATE TABLE IF NOT EXISTS knowledge_grams (
        gram TEXT NOT NULL,
        chunkId TEXT NOT NULL,
        documentId TEXT NOT NULL,
        freq INTEGER NOT NULL,
        PRIMARY KEY (gram, chunkId),
        FOREIGN KEY (chunkId) REFERENCES knowledge_chunks(id) ON DELETE CASCADE
    )""",
    "CREATE INDEX IF NOT EXISTS index_knowledge_grams_documentId ON knowledge_grams (documentId)",
    "CREATE INDEX IF NOT EXISTS index_knowledge_grams_chunkId ON knowledge_grams (chunkId)",
    """
    CREATE TABLE IF NOT EXISTS word_favorites (
        id TEXT NOT NULL PRIMARY KEY,
        word TEXT NOT NULL,
        kind TEXT NOT NULL,
        added_at INTEGER NOT NULL,
        pinyin TEXT,
        ipa TEXT,
        gloss TEXT
    )""",
    "CREATE INDEX IF NOT EXISTS index_word_favorites_word ON word_favorites (word)",
    """
    CREATE TABLE IF NOT EXISTS word_examples (
        id TEXT NOT NULL PRIMARY KEY,
        word TEXT NOT NULL,
        text TEXT NOT NULL,
        translation TEXT NOT NULL,
        created_at INTEGER NOT NULL
    )""",
    "CREATE INDEX IF NOT EXISTS index_word_examples_word ON word_examples (word)",
)

/** 所有写操作后按表位掩码 bump，驱动 observe* Flow 重新读库（对位 Room 的 invalidation tracker）。 */
object DbChanges {
    const val NODES = 1
    const val MESSAGES = 2
    const val FOLDERS = 4
    const val CONVERSATIONS = 8
    const val HIGHLIGHTS = 16
    const val KNOWLEDGE = 32
    const val WORDS = 64

    private val pulse = kotlinx.coroutines.flow.MutableStateFlow(0L)

    fun bump(tables: Int) {
        pulse.value = pulse.value + tables
    }

    /** 组合观察流：位掩码命中即发出（调用方每次发射重读一次查询）。 */
    fun flowFor(tables: Int): kotlinx.coroutines.flow.Flow<Long> =
        pulse
            .filter { change -> change != 0L }   // 任一 bump 触发重读（查询都很廉价，不做精确掩码过滤）
            .onStart { emit(0L) }   // 首发保证订阅者立即拿到一次数据
}

/** Room 兼容窄口。 */
class SupportOpenHelper(private val connection: Connection) {
    val writableDatabase: SupportDatabase get() = SupportDatabase(connection)
}

class SupportDatabase(private val connection: Connection) {
    fun execSQL(sql: String, bindArgs: Array<out Any?>?) {
        synchronized(connection) {
            connection.prepareStatement(sql).use { st ->
                bindArgs?.forEachIndexed { i, arg ->
                    when (arg) {
                        null -> st.setString(i + 1, null)
                        is String -> st.setString(i + 1, arg)
                        is Int -> st.setInt(i + 1, arg)
                        is Long -> st.setLong(i + 1, arg)
                        is Boolean -> st.setInt(i + 1, if (arg) 1 else 0)
                        is Float -> st.setFloat(i + 1, arg)
                        is Double -> st.setDouble(i + 1, arg)
                        is ByteArray -> st.setBytes(i + 1, arg)
                        else -> st.setObject(i + 1, arg)
                    }
                }
                st.executeUpdate()
            }
        }
    }
}

