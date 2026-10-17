package com.treechat.workbench.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.treechat.workbench.data.model.Conversation
import com.treechat.workbench.data.model.Folder
import kotlinx.coroutines.flow.Flow

/**
 * 文件夹与会话的存取。
 *
 * 这里把「全部文件夹」和「全部会话」整体读出来，由上层在 Kotlin 里组装树、
 * 计算路径与过滤当前目录 —— 而不是写递归 CTE 或按目录分页查。
 * 理由：文件夹与会话的数量级是几十到几百，全量加载的成本远低于
 * 维护多套递归 SQL 与分页状态的复杂度；而且切换目录时不会有加载空窗。
 */
@Dao
interface FolderDao {

    // ---------------------------------------------------------------- 文件夹

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder)

    @Update
    suspend fun updateFolder(folder: Folder)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolderById(id: String)

    @Query("SELECT * FROM folders ORDER BY is_pinned DESC, sort_order ASC, created_at ASC")
    fun observeAllFolders(): Flow<List<Folder>>

    @Query("SELECT * FROM folders ORDER BY is_pinned DESC, sort_order ASC, created_at ASC")
    suspend fun getAllFolders(): List<Folder>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: String): Folder?

    @Query("SELECT * FROM folders WHERE parent_folder_id = :parentId")
    suspend fun getChildFolders(parentId: String): List<Folder>

    // ------------------------------------------------------------------ 会话

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation)

    @Update
    suspend fun updateConversation(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    @Query("SELECT * FROM conversations ORDER BY is_pinned DESC, updated_at DESC")
    fun observeAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): Conversation?

    /**
     * 按来源标记查会话。
     *
     * 用途：文献/网页对话"一本书一棵树"，用 `sourceOrigin`（如「来自文献·书名」）
     * 定位已有那棵，避免重复建树或误认同名的手工会话。只读查询。
     */
    @Query("SELECT * FROM conversations WHERE source_origin = :sourceOrigin LIMIT 1")
    suspend fun getConversationBySourceOrigin(sourceOrigin: String): Conversation?

    /** 会话有新消息时调用，用于把最近活跃的对话排到前面。 */
    @Query("UPDATE conversations SET updated_at = :timestamp WHERE id = :id")
    suspend fun touchConversation(id: String, timestamp: Long)

    @Query("UPDATE conversations SET folder_id = :folderId WHERE id = :id")
    suspend fun moveConversationToFolder(id: String, folderId: String?)

    @Query("UPDATE folders SET parent_folder_id = :parentId WHERE id = :id")
    suspend fun moveFolder(id: String, parentId: String?)
}
