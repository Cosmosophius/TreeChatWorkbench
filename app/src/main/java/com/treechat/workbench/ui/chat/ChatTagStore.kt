package com.treechat.workbench.ui.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 对话界面里的「标签小窗」存储。
 *
 * ## 为什么要有它
 * 对话界面此前只有一条路能开下级分支：**先选中一段文字**，再由引用文本
 * 反查同文本超链接。这条路有两个绕不过去的限制：
 *  1. 必须先有"能选中的文本"——纯图片消息、代码块、表格、空白提问都开不了；
 *  2. 分支的身份由**引用文本**决定，两段话文字一样就会指向同一个分支，
 *     文字改了链接就断。
 *
 * 标签小窗换一种锚定方式：**锚在屏幕位置 + 用户自己起的名字**。
 * 它不依赖任何文本，于是"我想就这块地方单开一支"这件事永远成立。
 *
 * ## 为什么存 SharedPreferences 而不是 Room
 * 与阅读器的 `PageTagStore` 同理：这是纯界面层的位置注释（和窗口坐标、
 * 封面偏好同类），不属于知识库检索数据，也不参与切片索引。
 * 进 Room 要动表结构——纪律不允许，收益也没有。
 *
 * ## 坐标为什么存比例
 * [x]/[y] 是相对对话内容区的归一化坐标（0..1）。同一份对话会在手机、
 * 平板横屏、浮层小窗三种尺寸下显示；存像素的话，换设备/转屏/拉窗口
 * 标签就会漂到别处，存比例则永远钉在同一个相对位置。
 */
class ChatTagStore(private val context: Context) {

    /**
     * 一个对话标签。
     *
     * [nodeId] 是该标签**已经建出来的**下级分支节点 id，首次点开时才创建，
     * 创建后回填。为 null 表示"这个标签还没建过分支"。
     *
     * 为什么要存 nodeId：同一个标签反复点应该是"打开那扇窗"，
     * 而不是每点一次就在树里多插一个空分支。用户改名字也不重建节点。
     */
    data class ChatTag(
        val id: String,
        /** 标签挂在哪个对话节点上（当前正在看的这条分支）。 */
        val hostNodeId: String,
        val name: String,
        val x: Float,
        val y: Float,
        /** 已创建的下级分支节点 id；null = 尚未创建。 */
        val nodeId: String? = null
    )

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("chat_tags", Context.MODE_PRIVATE)

    private fun readAll(): MutableList<ChatTag> {
        val json = prefs.getString(KEY_ALL, null) ?: return mutableListOf()
        return runCatching {
            val type = object : TypeToken<MutableList<ChatTag>>() {}.type
            gson.fromJson<MutableList<ChatTag>>(json, type) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    private fun writeAll(list: List<ChatTag>) {
        prefs.edit().putString(KEY_ALL, gson.toJson(list)).apply()
    }

    /** 某个对话节点上的全部标签。 */
    fun load(hostNodeId: String): List<ChatTag> =
        readAll().filter { it.hostNodeId == hostNodeId }

    fun add(hostNodeId: String, name: String, x: Float, y: Float): ChatTag {
        val all = readAll()
        val tag = ChatTag(
            id = "ct_" + UUID.randomUUID().toString().take(8),
            hostNodeId = hostNodeId,
            name = name,
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f)
        )
        all.add(tag)
        writeAll(all)
        return tag
    }

    /** 回填/更新标签对应的分支节点（首次创建分支后调用）。 */
    fun attachNode(tagId: String, nodeId: String) {
        val all = readAll()
        val i = all.indexOfFirst { it.id == tagId }
        if (i < 0) return
        all[i] = all[i].copy(nodeId = nodeId)
        writeAll(all)
    }

    fun rename(tagId: String, name: String) {
        val all = readAll()
        val i = all.indexOfFirst { it.id == tagId }
        if (i < 0) return
        all[i] = all[i].copy(name = name)
        writeAll(all)
    }

    fun remove(tagId: String) {
        val all = readAll()
        if (all.removeAll { it.id == tagId }) writeAll(all)
    }

    private companion object {
        const val KEY_ALL = "tags"
    }
}
