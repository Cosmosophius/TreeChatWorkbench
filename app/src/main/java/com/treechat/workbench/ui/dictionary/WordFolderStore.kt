package com.treechat.workbench.ui.dictionary

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.treechat.workbench.data.prefs.AppSettings
import java.util.UUID

/**
 * 单词本的文件夹元数据。
 */
data class WordFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 单词本文件夹与归属关系的轻量持久化工具（SharedPreferences JSON）。
 *
 * 绝对不触碰 Room 数据库，防止新增表或列导致 schema 迁移事故。
 */
object WordFolderStore {

    private const val KEY_FOLDERS = "word_book_folders_json"
    private const val KEY_WORD_FOLDER_MAP = "word_book_folder_map_json"

    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)

    /** 获取所有自定义文件夹列表。 */
    fun getFolders(context: Context): List<WordFolder> {
        val raw = prefs(context).getString(KEY_FOLDERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WordFolder>>() {}.type
            gson.fromJson<List<WordFolder>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存自定义文件夹列表。 */
    fun saveFolders(context: Context, folders: List<WordFolder>) {
        prefs(context).edit().putString(KEY_FOLDERS, gson.toJson(folders)).apply()
    }

    /** 新建一个文件夹。 */
    fun addFolder(context: Context, name: String): WordFolder {
        val current = getFolders(context).toMutableList()
        val newFolder = WordFolder(name = name.trim())
        current.add(newFolder)
        saveFolders(context, current)
        return newFolder
    }

    /** 删除一个文件夹。 */
    fun deleteFolder(context: Context, folderId: String) {
        val current = getFolders(context).filterNot { it.id == folderId }
        saveFolders(context, current)
        // 移出已删除文件夹下的映射
        val map = getWordFolderMap(context).toMutableMap()
        val keysToRemove = map.filter { it.value == folderId }.keys
        keysToRemove.forEach { map.remove(it) }
        saveWordFolderMap(context, map)
    }

    /** 重命名文件夹。 */
    fun renameFolder(context: Context, folderId: String, newName: String) {
        val current = getFolders(context).map {
            if (it.id == folderId) it.copy(name = newName.trim()) else it
        }
        saveFolders(context, current)
    }

    /** 获取单词到文件夹的映射表：wordKey (WordFavorite.id) -> folderId。 */
    fun getWordFolderMap(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_WORD_FOLDER_MAP, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(raw, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 保存单词与文件夹的映射关系。 */
    fun saveWordFolderMap(context: Context, map: Map<String, String>) {
        prefs(context).edit().putString(KEY_WORD_FOLDER_MAP, gson.toJson(map)).apply()
    }

    /** 将某个单词移入指定文件夹（folderId 为 null 表示从文件夹中移出，回到未分组）。 */
    fun assignWordToFolder(context: Context, wordId: String, folderId: String?) {
        val map = getWordFolderMap(context).toMutableMap()
        if (folderId == null) {
            map.remove(wordId)
        } else {
            map[wordId] = folderId
        }
        saveWordFolderMap(context, map)
    }
}
