package com.treechat.workbench.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.treechat.workbench.data.model.ChatMessage
import com.treechat.workbench.data.model.Conversation
import com.treechat.workbench.data.model.ConversationNode
import com.treechat.workbench.data.model.Folder
import com.treechat.workbench.data.model.Highlight
import com.treechat.workbench.data.model.KnowledgeChunk
import com.treechat.workbench.data.model.KnowledgeDocument
import com.treechat.workbench.data.model.KnowledgeGram
import com.treechat.workbench.data.model.WordExample
import com.treechat.workbench.data.model.WordFavorite

@Database(
    entities = [
        ConversationNode::class,
        ChatMessage::class,
        Folder::class,
        Conversation::class,
        Highlight::class,
        KnowledgeDocument::class,
        KnowledgeChunk::class,
        KnowledgeGram::class,
        WordFavorite::class,
        WordExample::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun folderDao(): FolderDao
    abstract fun highlightDao(): HighlightDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun wordDao(): WordDao

    companion object {

        /** v1 -> v2：消息新增联网检索来源列。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN search_sources TEXT")
            }
        }

        /**
         * v2 -> v3：引入文件夹与会话两层。
         *
         * 全部用增量迁移，历史对话一条不丢：
         * 先把已存在的那棵分支树整体归入一个「默认会话」，
         * 用户升级后看到的仍然是原来的对话内容，只是它现在有归属了。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `folders` (
                        `id` TEXT NOT NULL,
                        `parent_folder_id` TEXT,
                        `name` TEXT NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`parent_folder_id`) REFERENCES `folders`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folders_parent_folder_id` " +
                        "ON `folders` (`parent_folder_id`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `id` TEXT NOT NULL,
                        `folder_id` TEXT,
                        `title` TEXT NOT NULL,
                        `source_origin` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`folder_id`) REFERENCES `folders`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversations_folder_id` " +
                        "ON `conversations` (`folder_id`)"
                )

                db.execSQL("ALTER TABLE `conversation_nodes` ADD COLUMN `conversation_id` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_nodes_conversation_id` " +
                        "ON `conversation_nodes` (`conversation_id`)"
                )

                // 把历史的那棵分支树收进一个默认会话（若存在根节点）
                db.execSQL(
                    """
                    INSERT INTO `conversations`
                        (`id`, `folder_id`, `title`, `source_origin`, `created_at`, `updated_at`)
                    SELECT 'legacy-conversation', NULL, `title`, '迁移自旧版本',
                           `created_at`, `created_at`
                    FROM `conversation_nodes`
                    WHERE `parent_id` IS NULL
                    ORDER BY `created_at` ASC
                    LIMIT 1
                    """.trimIndent()
                )

                // 回填归属。若上一步没有插入任何会话，子查询返回 NULL，等同于不修改。
                db.execSQL(
                    """
                    UPDATE `conversation_nodes`
                    SET `conversation_id` = (SELECT `id` FROM `conversations` LIMIT 1)
                    WHERE `conversation_id` IS NULL
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 -> v4：新增笔记高亮表。
         *
         * 只建一张新表 + 两个索引，**不触碰任何历史数据**，
         * 因此这一步没有丢数据的可能（与 2→3 的回填型迁移不同）。
         *
         * 外键挂在 chat_messages 上并 CASCADE：消息删除时高亮一并清掉。
         * Room 会核对建表语句与实体声明是否一致 —— 索引名必须严格是
         * `index_<表名>_<列名>`，列顺序与外键子句也要与实体对齐，
         * 否则升级到 v4 时会直接抛 IllegalStateException。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_highlights` (
                        `id` TEXT NOT NULL,
                        `message_id` TEXT NOT NULL,
                        `node_id` TEXT NOT NULL,
                        `start_offset` INTEGER NOT NULL,
                        `end_offset` INTEGER NOT NULL,
                        `color_argb` INTEGER NOT NULL,
                        `note` TEXT,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`message_id`) REFERENCES `chat_messages`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_highlights_message_id` " +
                        "ON `message_highlights` (`message_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_highlights_node_id` " +
                        "ON `message_highlights` (`node_id`)"
                )
            }
        }

        /**
         * v4 -> v5：新增本地知识库三张表（教材 RAG）。
         *
         * 与 3→4 一样，**只建新表与索引，不触碰任何历史数据**，
         * 因此这一步没有丢数据的可能。
         *
         * ⚠️ Room 会逐字核对建表语句与实体声明：索引名必须是
         * `index_<表名>_<列名>`（复合索引用下划线连接两列），
         * 列顺序与外键子句也要与实体一致。不一致会在升级时抛
         * IllegalStateException，把用户直接挡在应用之外。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 文档
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `knowledge_documents` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `charCount` INTEGER NOT NULL,
                        `chunkCount` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_documents_title` " +
                        "ON `knowledge_documents` (`title`)"
                )

                // 切块
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `knowledge_chunks` (
                        `id` TEXT NOT NULL,
                        `documentId` TEXT NOT NULL,
                        `orderIndex` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `charStart` INTEGER NOT NULL,
                        `section` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`documentId`) REFERENCES `knowledge_documents`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_documentId` " +
                        "ON `knowledge_chunks` (`documentId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_chunks_documentId_orderIndex` " +
                        "ON `knowledge_chunks` (`documentId`, `orderIndex`)"
                )

                // 2 字组倒排索引
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `knowledge_grams` (
                        `gram` TEXT NOT NULL,
                        `chunkId` TEXT NOT NULL,
                        `documentId` TEXT NOT NULL,
                        `freq` INTEGER NOT NULL,
                        PRIMARY KEY(`gram`, `chunkId`),
                        FOREIGN KEY(`chunkId`) REFERENCES `knowledge_chunks`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_grams_documentId` " +
                        "ON `knowledge_grams` (`documentId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_grams_chunkId` " +
                        "ON `knowledge_grams` (`chunkId`)"
                )
            }
        }

        /**
         * v5 -> v6：消息新增图片列（随消息发送的图片，存 data URI 的 JSON 数组）。
         *
         * 单列 ALTER，不触碰历史数据；历史消息该列为 NULL，表示没有图片。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN images_json TEXT")
            }
        }

        /**
         * v6 -> v7：消息新增"思考过程"列。
         *
         * 用来存思考模型（DeepSeek-R1 / MIMO / Qwen 思考版等）返回的
         * `reasoning_content`。此前这类内容被**静默丢弃**，
         * 用户因此以为"推理开关没生效"。
         *
         * 单列 ALTER，不触碰历史数据；历史消息该列为 NULL，表示当时没有思考内容
         * （或当时的版本还不支持记录它）。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reasoning_content TEXT")
            }
        }

        /**
         * v7 -> v8：
         * 1. 文件夹与会话支持置顶 (is_pinned) 与收藏 (is_favorite)；
         * 2. 知识库文档支持多级文件夹归属路径 (folderPath)；
         * 3. 聊天消息支持持久化保存命中的本地知识库原文切片 (knowledge_sources)。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE knowledge_documents ADD COLUMN folderPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN knowledge_sources TEXT")
            }
        }

        /**
         * v8 -> v9：
         * 1. 知识库文档新增索引方案标识列 (indexingScheme: LOCAL_OFFLINE / CLOUD_EMBEDDING 等)；
         * 2. 知识库切片新增高维向量列 (embedding: 逗号分隔的浮点数组字符串)。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE knowledge_documents ADD COLUMN indexingScheme TEXT NOT NULL DEFAULT 'LOCAL_OFFLINE'")
                db.execSQL("ALTER TABLE knowledge_chunks ADD COLUMN embedding TEXT")
            }
        }

        /**
         * v9 -> v10：新增词典模块两张表（单词本收藏 + 例句）。
         *
         * 与 3→4、4→5 同一套路：**只建新表与索引，不触碰任何历史数据**，
         * 因此这一步没有丢数据的可能。
         *
         * ⚠️ Room 会逐字核对建表语句与实体声明：索引名必须是
         * `index_<表名>_<列名>`，列顺序、可空性（`TEXT` 与 `TEXT NOT NULL`）
         * 也要与实体一致（见 WordFavorite / WordExample）。不一致会在升级时抛
         * IllegalStateException，把用户直接挡在应用之外。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 单词本收藏：确定性主键（kind:word），同词重复收藏幂等覆盖
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `word_favorites` (
                        `id` TEXT NOT NULL,
                        `word` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        `pinyin` TEXT,
                        `ipa` TEXT,
                        `gloss` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_word_favorites_word` " +
                        "ON `word_favorites` (`word`)"
                )

                // 例句：随机主键，一个词可以有多条（重复生成是追加，不是覆盖）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `word_examples` (
                        `id` TEXT NOT NULL,
                        `word` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `translation` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_word_examples_word` " +
                        "ON `word_examples` (`word`)"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tree_chat_workbench.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
