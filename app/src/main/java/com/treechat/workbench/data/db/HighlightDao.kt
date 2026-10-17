package com.treechat.workbench.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.treechat.workbench.data.model.Highlight
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(highlight: Highlight)

    @Query("DELETE FROM message_highlights WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 取消高亮：按"同一条消息 + 同一个范围"删，避免同一段被重复标记。 */
    @Query(
        """
        DELETE FROM message_highlights
        WHERE message_id = :messageId AND start_offset = :start AND end_offset = :end
        """
    )
    suspend fun deleteExact(messageId: String, start: Int, end: Int)

    /**
     * 智能清除高亮：只要与传入的区间存在重叠（部分重叠或完全包含），就将其从数据库移除。
     * 彻底解决用户选区没有精确到单个字符就无法取消高亮的顽疾。
     */
    @Query(
        """
        DELETE FROM message_highlights
        WHERE message_id = :messageId
          AND NOT (end_offset <= :start OR start_offset >= :end)
        """
    )
    suspend fun deleteOverlapping(messageId: String, start: Int, end: Int)

    /** 撤回最近一次高亮 */
    @Query(
        """
        DELETE FROM message_highlights
        WHERE id IN (
            SELECT id FROM message_highlights
            WHERE message_id = :messageId
            ORDER BY created_at DESC LIMIT 1
        )
        """
    )
    suspend fun deleteLatest(messageId: String)

    /**
     * 取当前节点下所有消息的高亮。
     *
     * 之所以按 node_id 取而不是按 message_id 逐条查：一个节点的消息通常
     * 只有几条到几十条，一次取回来在内存里按 messageId 分组，
     * 比每条消息各发一次查询少很多次往返。
     */
    @Query("SELECT * FROM message_highlights WHERE node_id = :nodeId ORDER BY created_at ASC")
    fun observeForNode(nodeId: String): Flow<List<Highlight>>

    @Query("SELECT * FROM message_highlights WHERE message_id = :messageId")
    suspend fun getForMessage(messageId: String): List<Highlight>
}
