package com.treechat.workbench.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.treechat.workbench.data.model.WordExample
import com.treechat.workbench.data.model.WordFavorite
import kotlinx.coroutines.flow.Flow

/**
 * 词典模块的存取：单词本收藏 + 例句。
 *
 * 收藏用确定性主键 + REPLACE（同词重复收藏即覆盖那一条），
 * 例句用随机主键 + REPLACE（同一个词可以有多条，重复生成是**追加**）。
 */
@Dao
interface WordDao {

    // ---------------------------------------------------------------- 收藏夹

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(favorite: WordFavorite)

    @Query("DELETE FROM word_favorites WHERE word = :word AND kind = :kind")
    suspend fun deleteFavorite(word: String, kind: String)

    @Query("SELECT * FROM word_favorites ORDER BY added_at DESC")
    fun observeFavorites(): Flow<List<WordFavorite>>

    @Query("SELECT * FROM word_favorites ORDER BY added_at DESC")
    suspend fun getAllFavorites(): List<WordFavorite>

    @Query("SELECT * FROM word_favorites WHERE word = :word AND kind = :kind LIMIT 1")
    suspend fun getFavorite(word: String, kind: String): WordFavorite?

    // ------------------------------------------------------------------ 例句

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExample(example: WordExample)

    @Query("DELETE FROM word_examples WHERE id = :id")
    suspend fun deleteExample(id: String)

    /** 某个词的全部例句，按生成时间升序 —— 先写的排前面。 */
    @Query("SELECT * FROM word_examples WHERE word = :word ORDER BY created_at ASC")
    fun observeExamples(word: String): Flow<List<WordExample>>

    @Query("SELECT * FROM word_examples WHERE word = :word ORDER BY created_at ASC")
    suspend fun getExamples(word: String): List<WordExample>
}
