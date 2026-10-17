// 桌面版 KnowledgeDao / WordDao：sqlite-jdbc 手写实现，SQL 逐条对齐安卓 @Query 契约。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.data.db

import com.treechat.workbench.data.model.KnowledgeChunk
import com.treechat.workbench.data.model.KnowledgeDocument
import com.treechat.workbench.data.model.KnowledgeGram
import com.treechat.workbench.data.model.WordExample
import com.treechat.workbench.data.model.WordFavorite
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private fun <T> rows(st: PreparedStatement, mapper: (ResultSet) -> T): List<T> =
    st.executeQuery().use { rs ->
        val out = ArrayList<T>()
        while (rs.next()) out.add(mapper(rs))
        out
    }

private fun <T> firstOrNull(st: PreparedStatement, mapper: (ResultSet) -> T): T? =
    st.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }

private fun documentOf(rs: ResultSet): KnowledgeDocument = KnowledgeDocument(
    id = rs.getString("id"),
    title = rs.getString("title") ?: "",
    fileName = rs.getString("fileName") ?: "",
    sourceType = rs.getString("sourceType") ?: "TEXT",
    charCount = rs.getInt("charCount"),
    chunkCount = rs.getInt("chunkCount"),
    enabled = rs.getInt("enabled") == 1,
    createdAt = rs.getLong("createdAt"),
    folderPath = rs.getString("folderPath") ?: "",
    indexingScheme = rs.getString("indexingScheme") ?: "LOCAL_OFFLINE",
)

private fun chunkOf(rs: ResultSet): KnowledgeChunk = KnowledgeChunk(
    id = rs.getString("id"),
    documentId = rs.getString("documentId"),
    orderIndex = rs.getInt("orderIndex"),
    text = rs.getString("text") ?: "",
    charStart = rs.getInt("charStart"),
    section = rs.getString("section"),
    embedding = rs.getString("embedding"),
)

private fun gramOf(rs: ResultSet): KnowledgeGram = KnowledgeGram(
    gram = rs.getString("gram"),
    chunkId = rs.getString("chunkId"),
    documentId = rs.getString("documentId"),
    freq = rs.getInt("freq"),
)

private suspend fun Connection.withIoWrite(sql: String, binder: (PreparedStatement) -> Unit, mask: Int) {
    withContext(Dispatchers.IO) {
        synchronized(this@withIoWrite) { exec(sql, binder) }
    }
    DbChanges.bump(mask)
}

class DesktopKnowledgeDao(private val connection: Connection) : KnowledgeDao {

    override fun observeDocuments(): Flow<List<KnowledgeDocument>> = observe(DbChanges.KNOWLEDGE) { getDocuments() }

    override suspend fun getDocuments(): List<KnowledgeDocument> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM knowledge_documents ORDER BY createdAt DESC").use {
                rows(it, ::documentOf)
            }
        }
    }

    override suspend fun getDocument(id: String): KnowledgeDocument? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM knowledge_documents WHERE id = ?").use {
                it.setString(1, id); firstOrNull(it, ::documentOf)
            }
        }
    }

    override suspend fun documentCount(): Int = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT COUNT(*) FROM knowledge_documents").use {
                it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override suspend fun insertDocument(document: KnowledgeDocument) =
        connection.withIoWrite(
            "REPLACE INTO knowledge_documents (id, title, fileName, sourceType, charCount, chunkCount, enabled, createdAt, folderPath, indexingScheme) VALUES (?,?,?,?,?,?,?,?,?,?)",
            { st ->
                st.setString(1, document.id)
                st.setString(2, document.title)
                st.setString(3, document.fileName)
                st.setString(4, document.sourceType)
                st.setInt(5, document.charCount)
                st.setInt(6, document.chunkCount)
                st.setInt(7, if (document.enabled) 1 else 0)
                st.setLong(8, document.createdAt)
                st.setString(9, document.folderPath)
                st.setString(10, document.indexingScheme)
            },
            DbChanges.KNOWLEDGE,
        )

    override suspend fun setDocumentEnabled(id: String, enabled: Boolean) =
        connection.withIoWrite("UPDATE knowledge_documents SET enabled = ? WHERE id = ?", {
            it.setInt(1, if (enabled) 1 else 0); it.setString(2, id)
        }, DbChanges.KNOWLEDGE)

    override suspend fun setDocumentFolderPath(id: String, folderPath: String) =
        connection.withIoWrite("UPDATE knowledge_documents SET folderPath = ? WHERE id = ?", {
            it.setString(1, folderPath); it.setString(2, id)
        }, DbChanges.KNOWLEDGE)

    override suspend fun renameDocument(id: String, title: String) =
        connection.withIoWrite("UPDATE knowledge_documents SET title = ? WHERE id = ?", {
            it.setString(1, title); it.setString(2, id)
        }, DbChanges.KNOWLEDGE)

    override suspend fun setFolderDocumentsEnabled(folderPath: String, folderPrefix: String, enabled: Boolean) =
        connection.withIoWrite(
            "UPDATE knowledge_documents SET enabled = ? WHERE folderPath = ? OR folderPath LIKE ?",
            { it.setInt(1, if (enabled) 1 else 0); it.setString(2, folderPath); it.setString(3, "$folderPrefix%") },
            DbChanges.KNOWLEDGE,
        )

    override suspend fun documentIdsContaining(keyword: String): List<String> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT DISTINCT documentId FROM knowledge_chunks WHERE text LIKE '%' || ? || '%' LIMIT 300"
            ).use { it.setString(1, keyword); rows(it) { rs -> rs.getString(1) } }
        }
    }

    override suspend fun getChunksForDocument(documentId: String): List<KnowledgeChunk> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT * FROM knowledge_chunks WHERE documentId = ? ORDER BY orderIndex ASC"
            ).use { it.setString(1, documentId); rows(it, ::chunkOf) }
        }
    }

    override suspend fun deleteDocument(id: String) =
        connection.withIoWrite("DELETE FROM knowledge_documents WHERE id = ?", { it.setString(1, id) }, DbChanges.KNOWLEDGE)

    override suspend fun deleteGramsForDocument(documentId: String) =
        connection.withIoWrite("DELETE FROM knowledge_grams WHERE documentId = ?", { it.setString(1, documentId) }, DbChanges.KNOWLEDGE)

    override suspend fun deleteChunksForDocument(documentId: String) =
        connection.withIoWrite("DELETE FROM knowledge_chunks WHERE documentId = ?", { it.setString(1, documentId) }, DbChanges.KNOWLEDGE)

    override suspend fun updateDocumentChunkCount(id: String, count: Int) =
        connection.withIoWrite("UPDATE knowledge_documents SET chunkCount = ? WHERE id = ?", {
            it.setInt(1, count); it.setString(2, id)
        }, DbChanges.KNOWLEDGE)

    override suspend fun getDocumentById(id: String): KnowledgeDocument? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM knowledge_documents WHERE id = ? LIMIT 1").use {
                it.setString(1, id); firstOrNull(it, ::documentOf)
            }
        }
    }

    override suspend fun insertChunks(chunks: List<KnowledgeChunk>) = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement(
                "REPLACE INTO knowledge_chunks (id, documentId, orderIndex, text, charStart, section, embedding) VALUES (?,?,?,?,?,?,?)"
            ).use { st ->
                chunks.forEach { chunk ->
                    st.setString(1, chunk.id)
                    st.setString(2, chunk.documentId)
                    st.setInt(3, chunk.orderIndex)
                    st.setString(4, chunk.text)
                    st.setInt(5, chunk.charStart)
                    st.setString(6, chunk.section)
                    st.setString(7, chunk.embedding)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
        DbChanges.bump(DbChanges.KNOWLEDGE)
    }

    override suspend fun getChunk(id: String): KnowledgeChunk? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM knowledge_chunks WHERE id = ?").use {
                it.setString(1, id); firstOrNull(it, ::chunkOf)
            }
        }
    }

    override suspend fun getChunks(ids: List<String>): List<KnowledgeChunk> {
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT * FROM knowledge_chunks WHERE id IN (${ids.joinToString(",") { "?" }})"
                ).use {
                    ids.forEachIndexed { i, v -> it.setString(i + 1, v) }
                    rows(it, ::chunkOf)
                }
            }
        }
    }

    override suspend fun getNeighbourChunks(documentId: String, fromIndex: Int, toIndex: Int): List<KnowledgeChunk> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT * FROM knowledge_chunks WHERE documentId = ? AND orderIndex BETWEEN ? AND ? ORDER BY orderIndex"
                ).use {
                    it.setString(1, documentId); it.setInt(2, fromIndex); it.setInt(3, toIndex)
                    rows(it, ::chunkOf)
                }
            }
        }

    override suspend fun insertGrams(grams: List<KnowledgeGram>) = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement(
                "REPLACE INTO knowledge_grams (gram, chunkId, documentId, freq) VALUES (?,?,?,?)"
            ).use { st ->
                grams.forEach { gram ->
                    st.setString(1, gram.gram)
                    st.setString(2, gram.chunkId)
                    st.setString(3, gram.documentId)
                    st.setInt(4, gram.freq)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
        DbChanges.bump(DbChanges.KNOWLEDGE)
    }

    override suspend fun findGrams(grams: List<String>, limit: Int): List<KnowledgeGram> {
        if (grams.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT gram, chunkId, documentId, freq FROM knowledge_grams WHERE gram IN (${grams.joinToString(",") { "?" }}) LIMIT ?"
                ).use {
                    grams.forEachIndexed { i, v -> it.setString(i + 1, v) }
                    it.setInt(grams.size + 1, limit)
                    rows(it, ::gramOf)
                }
            }
        }
    }

    override suspend fun findEnabledGrams(grams: List<String>, limit: Int): List<KnowledgeGram> {
        if (grams.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT g.gram AS gram, g.chunkId AS chunkId, g.documentId AS documentId, g.freq AS freq FROM knowledge_grams g INNER JOIN knowledge_chunks c ON c.id = g.chunkId INNER JOIN knowledge_documents d ON d.id = c.documentId WHERE g.gram IN (${grams.joinToString(",") { "?" }}) AND d.enabled = 1 LIMIT ?"
                ).use {
                    grams.forEachIndexed { i, v -> it.setString(i + 1, v) }
                    it.setInt(grams.size + 1, limit)
                    rows(it, ::gramOf)
                }
            }
        }
    }

    override suspend fun gramCountOf(documentId: String): Int = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT COUNT(*) FROM knowledge_grams WHERE documentId = ?").use {
                it.setString(1, documentId)
                it.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override suspend fun getEnabledChunksWithEmbeddings(): List<KnowledgeChunk> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement(
                "SELECT c.* FROM knowledge_chunks c INNER JOIN knowledge_documents d ON d.id = c.documentId WHERE d.enabled = 1 AND c.embedding IS NOT NULL AND c.embedding != ''"
            ).use { rows(it, ::chunkOf) }
        }
    }

    override suspend fun getEnabledChunksWithEmbeddingsInFolder(folderPath: String, folderPrefix: String): List<KnowledgeChunk> =
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement(
                    "SELECT c.* FROM knowledge_chunks c INNER JOIN knowledge_documents d ON d.id = c.documentId WHERE d.enabled = 1 AND c.embedding IS NOT NULL AND c.embedding != '' AND (d.folderPath = ? OR d.folderPath LIKE ?)"
                ).use {
                    it.setString(1, folderPath); it.setString(2, "$folderPrefix%")
                    rows(it, ::chunkOf)
                }
            }
        }

    override suspend fun updateChunkEmbedding(chunkId: String, embedding: String) =
        connection.withIoWrite("UPDATE knowledge_chunks SET embedding = ? WHERE id = ?", {
            it.setString(1, embedding); it.setString(2, chunkId)
        }, DbChanges.KNOWLEDGE)

    override suspend fun insertDocumentWithIndex(
        document: KnowledgeDocument,
        chunks: List<KnowledgeChunk>,
        grams: List<KnowledgeGram>,
    ) = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.autoCommit = false
            try {
                insertDocumentInternal(document)
                chunks.forEach { chunkInternal(it) }
                grams.forEach { gramInternal(it) }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
        DbChanges.bump(DbChanges.KNOWLEDGE)
    }

    private fun insertDocumentInternal(document: KnowledgeDocument) {
        connection.prepareStatement(
            "REPLACE INTO knowledge_documents (id, title, fileName, sourceType, charCount, chunkCount, enabled, createdAt, folderPath, indexingScheme) VALUES (?,?,?,?,?,?,?,?,?,?)"
        ).use { st ->
            st.setString(1, document.id); st.setString(2, document.title); st.setString(3, document.fileName)
            st.setString(4, document.sourceType); st.setInt(5, document.charCount); st.setInt(6, document.chunkCount)
            st.setInt(7, if (document.enabled) 1 else 0); st.setLong(8, document.createdAt)
            st.setString(9, document.folderPath); st.setString(10, document.indexingScheme)
            st.executeUpdate()
        }
    }

    private fun chunkInternal(chunk: KnowledgeChunk) {
        connection.prepareStatement(
            "REPLACE INTO knowledge_chunks (id, documentId, orderIndex, text, charStart, section, embedding) VALUES (?,?,?,?,?,?,?)"
        ).use { st ->
            st.setString(1, chunk.id); st.setString(2, chunk.documentId); st.setInt(3, chunk.orderIndex)
            st.setString(4, chunk.text); st.setInt(5, chunk.charStart); st.setString(6, chunk.section)
            st.setString(7, chunk.embedding)
            st.executeUpdate()
        }
    }

    private fun gramInternal(gram: KnowledgeGram) {
        connection.prepareStatement(
            "REPLACE INTO knowledge_grams (gram, chunkId, documentId, freq) VALUES (?,?,?,?)"
        ).use { st ->
            st.setString(1, gram.gram); st.setString(2, gram.chunkId); st.setString(3, gram.documentId)
            st.setInt(4, gram.freq)
            st.executeUpdate()
        }
    }

    override suspend fun safeDeleteDocument(id: String) = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM knowledge_grams WHERE documentId = ?").use {
                    it.setString(1, id); it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM knowledge_chunks WHERE documentId = ?").use {
                    it.setString(1, id); it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM knowledge_documents WHERE id = ?").use {
                    it.setString(1, id); it.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
        DbChanges.bump(DbChanges.KNOWLEDGE)
    }

    override suspend fun safeClearDocumentIndex(id: String) = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM knowledge_grams WHERE documentId = ?").use {
                    it.setString(1, id); it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM knowledge_chunks WHERE documentId = ?").use {
                    it.setString(1, id); it.executeUpdate()
                }
                connection.prepareStatement("UPDATE knowledge_documents SET chunkCount = 0 WHERE id = ?").use {
                    it.setString(1, id); it.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
        DbChanges.bump(DbChanges.KNOWLEDGE)
    }

    override suspend fun deleteChunk(chunkId: String) =
        connection.withIoWrite("DELETE FROM knowledge_chunks WHERE id = ?", { it.setString(1, chunkId) }, DbChanges.KNOWLEDGE)

    override suspend fun deleteGramsForChunk(chunkId: String) =
        connection.withIoWrite("DELETE FROM knowledge_grams WHERE chunkId = ?", { it.setString(1, chunkId) }, DbChanges.KNOWLEDGE)

    override suspend fun safeDeleteChunk(chunkId: String, documentId: String) = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM knowledge_grams WHERE chunkId = ?").use {
                    it.setString(1, chunkId); it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM knowledge_chunks WHERE id = ?").use {
                    it.setString(1, chunkId); it.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE knowledge_documents SET chunkCount = (SELECT COUNT(*) FROM knowledge_chunks WHERE documentId = ?) WHERE id = ?"
                ).use {
                    it.setString(1, documentId); it.setString(2, documentId); it.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
        DbChanges.bump(DbChanges.KNOWLEDGE)
    }
}

// ================= WordDao =================

private fun favoriteOf(rs: ResultSet): WordFavorite = WordFavorite(
    id = rs.getString("id"),
    word = rs.getString("word") ?: "",
    kind = rs.getString("kind") ?: "EN",
    addedAt = rs.getLong("added_at"),
    pinyin = rs.getString("pinyin"),
    ipa = rs.getString("ipa"),
    gloss = rs.getString("gloss"),
)

class DesktopWordDao(private val connection: Connection) : WordDao {

    override suspend fun upsertFavorite(favorite: WordFavorite) =
        connection.withIoWrite(
            "REPLACE INTO word_favorites (id, word, kind, added_at, pinyin, ipa, gloss) VALUES (?,?,?,?,?,?,?)",
            { st ->
                st.setString(1, favorite.id)
                st.setString(2, favorite.word)
                st.setString(3, favorite.kind)
                st.setLong(4, favorite.addedAt)
                st.setString(5, favorite.pinyin)
                st.setString(6, favorite.ipa)
                st.setString(7, favorite.gloss)
            },
            DbChanges.WORDS,
        )

    override suspend fun deleteFavorite(word: String, kind: String) =
        connection.withIoWrite("DELETE FROM word_favorites WHERE word = ? AND kind = ?", {
            it.setString(1, word); it.setString(2, kind)
        }, DbChanges.WORDS)

    override fun observeFavorites(): Flow<List<WordFavorite>> = observe(DbChanges.WORDS) {
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement("SELECT * FROM word_favorites ORDER BY added_at DESC").use {
                    it.executeQuery().use { rs ->
                        val out = ArrayList<WordFavorite>()
                        while (rs.next()) out.add(favoriteOf(rs))
                        out
                    }
                }
            }
        }
    }

    override suspend fun getAllFavorites(): List<WordFavorite> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM word_favorites ORDER BY added_at DESC").use {
                it.executeQuery().use { rs ->
                    val out = ArrayList<WordFavorite>()
                    while (rs.next()) out.add(favoriteOf(rs))
                    out
                }
            }
        }
    }

    override suspend fun getFavorite(word: String, kind: String): WordFavorite? = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM word_favorites WHERE word = ? AND kind = ? LIMIT 1").use {
                it.setString(1, word); it.setString(2, kind)
                firstOrNull(it, ::favoriteOf)
            }
        }
    }

    override suspend fun insertExample(example: WordExample) =
        connection.withIoWrite(
            "REPLACE INTO word_examples (id, word, text, translation, created_at) VALUES (?,?,?,?,?)",
            { st ->
                st.setString(1, example.id)
                st.setString(2, example.word)
                st.setString(3, example.text)
                st.setString(4, example.translation)
                st.setLong(5, example.createdAt)
            },
            DbChanges.WORDS,
        )

    override suspend fun deleteExample(id: String) =
        connection.withIoWrite("DELETE FROM word_examples WHERE id = ?", { it.setString(1, id) }, DbChanges.WORDS)

    override fun observeExamples(word: String): Flow<List<WordExample>> = observe(DbChanges.WORDS) {
        withContext(Dispatchers.IO) {
            synchronized(connection) {
                connection.prepareStatement("SELECT * FROM word_examples WHERE word = ? ORDER BY created_at ASC").use {
                    it.setString(1, word)
                    it.executeQuery().use { rs ->
                        val out = ArrayList<WordExample>()
                        while (rs.next()) {
                            out.add(
                                WordExample(
                                    id = rs.getString("id"),
                                    word = rs.getString("word") ?: "",
                                    text = rs.getString("text") ?: "",
                                    translation = rs.getString("translation") ?: "",
                                    createdAt = rs.getLong("created_at"),
                                )
                            )
                        }
                        out
                    }
                }
            }
        }
    }

    override suspend fun getExamples(word: String): List<WordExample> = withContext(Dispatchers.IO) {
        synchronized(connection) {
            connection.prepareStatement("SELECT * FROM word_examples WHERE word = ? ORDER BY created_at ASC").use {
                it.setString(1, word)
                it.executeQuery().use { rs ->
                    val out = ArrayList<WordExample>()
                    while (rs.next()) {
                        out.add(
                            WordExample(
                                id = rs.getString("id"),
                                word = rs.getString("word") ?: "",
                                text = rs.getString("text") ?: "",
                                translation = rs.getString("translation") ?: "",
                                createdAt = rs.getLong("created_at"),
                            )
                        )
                    }
                    out
                }
            }
        }
    }
}
