package com.treechat.workbench.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.treechat.workbench.data.model.KnowledgeChunk
import com.treechat.workbench.data.model.KnowledgeDocument
import com.treechat.workbench.data.model.KnowledgeGram
import kotlinx.coroutines.flow.Flow

/**
 * 本地知识库（教材 RAG）的数据访问。
 *
 * 全部按"落库"设计：正文块与倒排索引都在 SQLite 里，检索直接查库，
 * 不把整本书或整份索引读进内存 —— 用户要求"不管多大都要全盘落库"，
 * 而且几 MB 教材的 2 字组会有数十万条，放内存既浪费又容易被系统杀掉。
 */
@Dao
interface KnowledgeDao {

    // ---------------------------------------------------------------- 文档

    @Query("SELECT * FROM knowledge_documents ORDER BY createdAt DESC")
    fun observeDocuments(): Flow<List<KnowledgeDocument>>

    @Query("SELECT * FROM knowledge_documents ORDER BY createdAt DESC")
    suspend fun getDocuments(): List<KnowledgeDocument>

    @Query("SELECT * FROM knowledge_documents WHERE id = :id")
    suspend fun getDocument(id: String): KnowledgeDocument?

    @Query("SELECT COUNT(*) FROM knowledge_documents")
    suspend fun documentCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: KnowledgeDocument)

    @Query("UPDATE knowledge_documents SET enabled = :enabled WHERE id = :id")
    suspend fun setDocumentEnabled(id: String, enabled: Boolean)

    @Query("UPDATE knowledge_documents SET folderPath = :folderPath WHERE id = :id")
    suspend fun setDocumentFolderPath(id: String, folderPath: String)

    @Query("UPDATE knowledge_documents SET title = :title WHERE id = :id")
    suspend fun renameDocument(id: String, title: String)

    @Query("UPDATE knowledge_documents SET enabled = :enabled WHERE folderPath = :folderPath OR folderPath LIKE :folderPrefix")
    suspend fun setFolderDocumentsEnabled(folderPath: String, folderPrefix: String, enabled: Boolean)

    /**
     * 全文搜索：切片正文命中关键词的**文档 id** 列表（只读查询，不改表结构）。
     *
     * 用途：书架的"搜书"——除了书名，还能把"书里提到过这个词"的书找出来。
     */
    @Query(
        """
        SELECT DISTINCT documentId FROM knowledge_chunks
        WHERE text LIKE '%' || :keyword || '%'
        LIMIT 300
        """
    )
    suspend fun documentIdsContaining(keyword: String): List<String>

    @Query("SELECT * FROM knowledge_chunks WHERE documentId = :documentId ORDER BY orderIndex ASC")
    suspend fun getChunksForDocument(documentId: String): List<KnowledgeChunk>

    /**
     * 删除整本书。
     *
     * 只删 documents 这一行即可：chunks 与 grams 都有
     * `ON DELETE CASCADE` 外键（grams 通过 chunkId 关联的 chunks，
     * 而 chunks 级联自 documents），所以不会留下孤儿索引。
     */
    @Query("DELETE FROM knowledge_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    /**
     * 明确优先清理该文档的倒排索引条目（利用 documentId 索引瞬间完成，避免 CASCADE 触发数十万行深层递归外键扫描锁库）。
     */
    @Query("DELETE FROM knowledge_grams WHERE documentId = :documentId")
    suspend fun deleteGramsForDocument(documentId: String)

    /**
     * 仅删除文档的切片与索引（保留文档记录本身）
     */
    @Query("DELETE FROM knowledge_chunks WHERE documentId = :documentId")
    suspend fun deleteChunksForDocument(documentId: String)

    @Query("UPDATE knowledge_documents SET chunkCount = :count WHERE id = :id")
    suspend fun updateDocumentChunkCount(id: String, count: Int)

    @Query("SELECT * FROM knowledge_documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: String): KnowledgeDocument?

    // ---------------------------------------------------------------- 切块

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<KnowledgeChunk>)

    @Query("SELECT * FROM knowledge_chunks WHERE id = :id")
    suspend fun getChunk(id: String): KnowledgeChunk?

    @Query("SELECT * FROM knowledge_chunks WHERE id IN (:ids)")
    suspend fun getChunks(ids: List<String>): List<KnowledgeChunk>

    /**
     * 取某一块的相邻块，用于回答时补上下文。
     *
     * 单块检索有时会把一句话切成两半（比如"光合作用分为"与"光反应和暗反应"），
     * 只给模型半句会让它答偏。取前后各 [radius] 块一并给它。
     */
    @Query(
        """
        SELECT * FROM knowledge_chunks
        WHERE documentId = :documentId
          AND orderIndex BETWEEN :fromIndex AND :toIndex
        ORDER BY orderIndex
        """
    )
    suspend fun getNeighbourChunks(
        documentId: String,
        fromIndex: Int,
        toIndex: Int
    ): List<KnowledgeChunk>

    // ---------------------------------------------------------------- 索引

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrams(grams: List<KnowledgeGram>)

    /**
     * 按 2 字组批量查倒排表。
     *
     * [limit] 是重要的保护：像"的结"这种极常见 gram 可能命中成千上万个块，
     * 不设上限会把整个结果集读进内存。取前 N 条已足够打分排序
     * （教材里真正有区分度的 gram 命中数远小于此）。
     */
    @Query(
        """
        SELECT gram, chunkId, documentId, freq FROM knowledge_grams
        WHERE gram IN (:grams)
        LIMIT :limit
        """
    )
    suspend fun findGrams(grams: List<String>, limit: Int): List<KnowledgeGram>

    /** 只取参与检索的文档下的索引行，避免把已停用文档的块召回来。 */
    @Query(
        """
        SELECT g.gram AS gram, g.chunkId AS chunkId, g.documentId AS documentId, g.freq AS freq
        FROM knowledge_grams g
        INNER JOIN knowledge_chunks c ON c.id = g.chunkId
        INNER JOIN knowledge_documents d ON d.id = c.documentId
        WHERE g.gram IN (:grams) AND d.enabled = 1
        LIMIT :limit
        """
    )
    suspend fun findEnabledGrams(grams: List<String>, limit: Int): List<KnowledgeGram>

    @Query("SELECT COUNT(*) FROM knowledge_grams WHERE documentId = :documentId")
    suspend fun gramCountOf(documentId: String): Int

    // ---------------------------------------------------------------- 向量数据访问 (云端方案)

    /**
     * 查询所有已启用文档中带有向量数据的切片（用于云端方案余弦相似度计算）。
     */
    @Query(
        """
        SELECT c.* FROM knowledge_chunks c
        INNER JOIN knowledge_documents d ON d.id = c.documentId
        WHERE d.enabled = 1 AND c.embedding IS NOT NULL AND c.embedding != ''
        """
    )
    suspend fun getEnabledChunksWithEmbeddings(): List<KnowledgeChunk>

    /**
     * 查询指定文件夹下（或根目录）已启用文档中带有向量数据的切片。
     */
    @Query(
        """
        SELECT c.* FROM knowledge_chunks c
        INNER JOIN knowledge_documents d ON d.id = c.documentId
        WHERE d.enabled = 1 AND c.embedding IS NOT NULL AND c.embedding != ''
          AND (d.folderPath = :folderPath OR d.folderPath LIKE :folderPrefix)
        """
    )
    suspend fun getEnabledChunksWithEmbeddingsInFolder(
        folderPath: String,
        folderPrefix: String
    ): List<KnowledgeChunk>

    /**
     * 批量更新切片的向量数据。
     */
    @Query("UPDATE knowledge_chunks SET embedding = :embedding WHERE id = :chunkId")
    suspend fun updateChunkEmbedding(chunkId: String, embedding: String)

    // ---------------------------------------------------------------- 组合

    /**
     * 一次事务里写入一份文档的元信息、全部切块与全部索引。
     *
     * 必须在一个事务里：中途失败若留下"文档已建但索引不全"的状态，
     * 用户会看到书在列表里却怎么也搜不到，比整本导入失败更难排查。
     */
    @Transaction
    suspend fun insertDocumentWithIndex(
        document: KnowledgeDocument,
        chunks: List<KnowledgeChunk>,
        grams: List<KnowledgeGram>
    ) {
        insertDocument(document)
        insertChunks(chunks)
        insertGrams(grams)
    }
    /**
     * 一次事务里安全删除整本教材文档及其全部切片与索引。
     *
     * 为什么分步手动删除而不是单靠外键 CASCADE：
     * SQLite 外键级联删除在大规模表（几万到十几万行 grams）时，会对每一行执行外键触发器检查，
     * 产生严重的磁盘 I/O 阻塞并长时间锁定数据库。通过 documentId 索引显式分步删除，耗时可从数秒降至毫秒级。
     */
    @Transaction
    suspend fun safeDeleteDocument(id: String) {
        deleteGramsForDocument(id)
        deleteChunksForDocument(id)
        deleteDocument(id)
    }

    /**
     * 一次事务里清空文档的切片与索引（保留文档记录本身）。
     */
    @Transaction
    suspend fun safeClearDocumentIndex(id: String) {
        deleteGramsForDocument(id)
        deleteChunksForDocument(id)
        updateDocumentChunkCount(id, 0)
    }

    // ---------------------------------------------------------------- 单段切片
    // 允许逐段删除误切/无用的切片。倒排索引项必须一起删，
    // 否则检索会命中一条正文已经不存在的 2-gram 记录。

    @Query("DELETE FROM knowledge_chunks WHERE id = :chunkId")
    suspend fun deleteChunk(chunkId: String)

    @Query("DELETE FROM knowledge_grams WHERE chunkId = :chunkId")
    suspend fun deleteGramsForChunk(chunkId: String)

    /** 一次事务里删除单段切片及其索引项，并同步该文档的切片计数。 */
    @Transaction
    suspend fun safeDeleteChunk(chunkId: String, documentId: String) {
        deleteGramsForChunk(chunkId)
        deleteChunk(chunkId)
        updateDocumentChunkCount(documentId, getChunksForDocument(documentId).size)
    }
}
