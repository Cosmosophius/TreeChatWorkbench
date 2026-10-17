package com.treechat.workbench.ui.reader

import android.content.Context
import com.treechat.workbench.data.prefs.InkCardSnapshot
import com.treechat.workbench.data.prefs.InkNoteBoard
import com.treechat.workbench.data.prefs.InkNoteStore
import java.util.UUID

/**
 * 阅读器与条畅手写笔记/卡片笔记系统 (InkNoteStore) 的双向桥接器。
 * 实现“划线即笔记”、“摘录即卡片”，完全复用本体数据模型与存储。
 */
class ReaderNoteBridge(private val context: Context) {

    private val inkNoteStore = InkNoteStore(context)

    /**
     * 将选中的文本划线与批注同步保存至对应的书本笔记白板中。
     * 若该书尚无笔记白板，则自动创建命名为《书名》读书笔记的白板。
     *
     * @param cardTitle 卡片标题覆盖项。默认 null 时沿用"第 N 页摘录"——
     *   那是给分页文档（PDF/EPUB）用的说法。内置网页浏览器没有"页"这个概念，
     *   必须能把它换成"网页划线摘录"，否则白板上会出现"第 1 页摘录"这种假信息。
     */
    fun saveHighlightToNote(
        documentId: String,
        bookTitle: String,
        pageIndex: Int,
        selectedQuote: String,
        userComment: String,
        cardTitle: String? = null
    ): String {
        val boards = inkNoteStore.loadAllBoards().toMutableList()
        val boardTitle = "《$bookTitle》读书笔记"
        val existingBoard = boards.find { it.sourceConversationId == "book_$documentId" }

        val cardId = UUID.randomUUID().toString()
        val newCard = InkCardSnapshot(
            id = cardId,
            parentId = null,
            title = cardTitle ?: "第 ${pageIndex + 1} 页摘录",
            quote = selectedQuote,
            summary = userComment.ifBlank { "划线重点标记" },
            x = 80f + (existingBoard?.cards?.size ?: 0) * 24f,
            y = 120f + (existingBoard?.cards?.size ?: 0) * 36f,
            width = 320f,
            height = 180f
        )

        val updatedBoard = if (existingBoard != null) {
            existingBoard.copy(
                cards = existingBoard.cards + newCard,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            InkNoteBoard(
                id = UUID.randomUUID().toString(),
                title = boardTitle,
                sourceConversationId = "book_$documentId",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                cards = listOf(newCard),
                strokes = emptyList()
            )
        }

        inkNoteStore.saveBoard(updatedBoard)
        return cardId
    }

    /**
     * 获取指定书籍下的所有摘录卡片。
     */
    fun getNotesForBook(documentId: String): List<InkCardSnapshot> {
        val boards = inkNoteStore.loadAllBoards()
        val board = boards.find { it.sourceConversationId == "book_$documentId" }
        return board?.cards ?: emptyList()
    }
}
