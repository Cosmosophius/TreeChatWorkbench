package com.treechat.workbench.ui.reader

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 页面标签（“微型小窗标签”）的本地存储。
 *
 * 用途：在扫描页/OCR 页上，于**正文旁边的任意空白处**放一个小标签，
 * 点它直接开一个小窗。这样即使 OCR 一个字都没认对（潦草手写、表格线、
 * 竖排、外文旧版书），用户依然能"指着这一块说事"——
 * 不再被"必须先认出正确的字才能选中它"卡住。
 *
 * 为什么落在 SharedPreferences 而不动 Room：
 * 这是纯界面层的位置注释（和自由摆放的图标坐标、封面偏好同类），
 * 不属于知识库的检索数据，也不参与切片索引；进 Room 只会带来表结构变动成本。
 */
class PageTagStore(private val context: Context) {

    /**
     * 一个页面标签。
     *
     * [x]/[y] 是**归一化坐标**（0..1，相对整页可视区），不是像素：
     * 页面会因为"单页 ↔ 三页"重排、旋转、窗口缩放而改变像素尺寸，
     * 存像素的话标签会跟着漂走甚至跑到页外；存比例则永远钉在同一个位置。
     */
    data class PageTag(
        val id: String,
        val docId: String,
        val page: Int,
        val x: Float,
        val y: Float,
        /** 页内序号（从 1 起），直接显示在标签上，便于口头指认。 */
        val label: Int
    )

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("reader_page_tags", Context.MODE_PRIVATE)

    private fun readAll(): MutableList<PageTag> {
        val json = prefs.getString(KEY_ALL, null) ?: return mutableListOf()
        return runCatching {
            val type = object : TypeToken<MutableList<PageTag>>() {}.type
            gson.fromJson<MutableList<PageTag>>(json, type) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    private fun writeAll(list: List<PageTag>) {
        prefs.edit().putString(KEY_ALL, gson.toJson(list)).apply()
    }

    /** 某一页的全部标签。 */
    fun load(docId: String, page: Int): List<PageTag> =
        readAll().filter { it.docId == docId && it.page == page }

    /** 在 [x],[y] 处新增一个标签，序号取该页现有标签数 +1。 */
    fun add(docId: String, page: Int, x: Float, y: Float): PageTag {
        val all = readAll()
        val label = all.count { it.docId == docId && it.page == page } + 1
        val tag = PageTag(
            id = "t_" + UUID.randomUUID().toString().take(8),
            docId = docId,
            page = page,
            x = x,
            y = y,
            label = label
        )
        all.add(tag)
        writeAll(all)
        return tag
    }

    fun remove(id: String) {
        val all = readAll()
        if (all.removeAll { it.id == id }) writeAll(all)
    }

    /** 删除一整份文档的标签（删除书籍时调用，避免留下孤儿记录）。 */
    fun removeDocument(docId: String) {
        val all = readAll()
        if (all.removeAll { it.docId == docId }) writeAll(all)
    }

    private companion object {
        const val KEY_ALL = "tags"
    }
}
