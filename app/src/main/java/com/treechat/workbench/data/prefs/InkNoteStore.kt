package com.treechat.workbench.data.prefs

import android.content.Context
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 活体手写笔记白板（[InkNoteBoard]）的本地持久化仓库。
 *
 * - 支持将「多维工作台」的思维导图一键快照为可手写批注、可继续拖动缩放卡片的笔记白板；
 * - 支持一键**复制副本**（用于多轮复习、默写自测或不同思路推演）、重命名、删除；
 * - 反序列化采用 `Array<InkNoteBoard>::class.java` 强类型数组，不依赖泛型 `TypeToken`，
 *   结合 `proguard-rules.pro` 中对 `data.prefs.**` 的保留规则，100% 免疫 R8 混淆问题。
 */
class InkNoteStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadAllBoards(): List<InkNoteBoard> {
        val raw = prefs.getString(KEY_BOARDS_JSON, null) ?: return emptyList()
        return runCatching {
            val array = gson.fromJson(raw, Array<InkNoteBoard>::class.java)
            array?.toList()?.sortedByDescending { it.updatedAt } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun saveAllBoards(boards: List<InkNoteBoard>) {
        prefs.edit()
            .putString(KEY_BOARDS_JSON, gson.toJson(boards.toTypedArray()))
            .apply()
    }

    fun saveBoard(board: InkNoteBoard) {
        val current = loadAllBoards().toMutableList()
        val index = current.indexOfFirst { it.id == board.id }
        val touched = board.copy(updatedAt = System.currentTimeMillis())
        if (index >= 0) {
            current[index] = touched
        } else {
            current.add(0, touched)
        }
        saveAllBoards(current)
        setActiveBoardId(touched.id)
    }

    /**
     * 一键复制副本：深拷贝整张白板的所有卡片位置、尺寸与已有手写笔迹，生成独立的新副本。
     */
    fun duplicateBoard(boardId: String): InkNoteBoard? {
        val current = loadAllBoards().toMutableList()
        val source = current.firstOrNull { it.id == boardId } ?: return null
        val now = System.currentTimeMillis()
        val copyTitle = if (source.title.contains("副本")) {
            "${source.title} · 新"
        } else {
            "${source.title} (副本)"
        }
        val cloned = source.copy(
            id = UUID.randomUUID().toString(),
            title = copyTitle,
            createdAt = now,
            updatedAt = now,
            cards = source.cards.map { it.copy() },
            strokes = source.strokes.map { stroke ->
                stroke.copy(
                    id = UUID.randomUUID().toString(),
                    points = stroke.points.map { it.copy() }
                )
            }
        )
        current.add(0, cloned)
        saveAllBoards(current)
        setActiveBoardId(cloned.id)
        return cloned
    }

    fun renameBoard(boardId: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        val current = loadAllBoards().toMutableList()
        val index = current.indexOfFirst { it.id == boardId }
        if (index >= 0) {
            current[index] = current[index].copy(
                title = trimmed,
                updatedAt = System.currentTimeMillis()
            )
            saveAllBoards(current)
        }
    }

    fun deleteBoard(boardId: String) {
        val current = loadAllBoards().filterNot { it.id == boardId }
        saveAllBoards(current)
        if (getActiveBoardId() == boardId) {
            setActiveBoardId(current.firstOrNull()?.id)
        }
    }

    fun getActiveBoardId(): String? = prefs.getString(KEY_ACTIVE_BOARD_ID, null)

    fun setActiveBoardId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE_BOARD_ID, id).apply()
    }

    companion object {
        private const val PREFS_NAME = "treechat_ink_notes"
        private const val KEY_BOARDS_JSON = "ink_boards_v1"
        private const val KEY_ACTIVE_BOARD_ID = "active_ink_board_id"

        fun formatDefaultTitle(conversationTitle: String?): String {
            val base = conversationTitle?.takeIf { it.isNotBlank() } ?: "思维导图"
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            return "$base · 批注 ($stamp)"
        }
    }
}
