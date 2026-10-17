package com.treechat.workbench.ui.tree

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import java.io.File

/**
 * 思维导图**布局存档**：把整张画布的排版（每张卡片的位置、尺寸、视口缩放与平移）
 * 存成一个有名字的快照，随时恢复。
 *
 * ## 与已有的"自动保存布局"和"二次点击复原"分别是什么
 * - `GraphLayoutStore`：**自动**保存当前布局，重启后回到上次的样子。它没有名字、
 *   只能有一份，用户看不到也管不了 —— 是"系统替我记着"。
 * - `graphState.restoreLayout()`：撤销上一次"重新布局"，**只在内存里、只有一份、
 *   只能用一次**。点完就没了。
 * - 本类：**用户手动命名、多份并存、跨重启、想恢复哪份恢复哪份**。
 *   用于"这张图我摆了很久，先存一版，改坏了再回来"这类场景。
 *
 * ## 为什么用文件而不是加一张 Room 表
 * 项目硬约束是绝对不新增/修改 Room 表、列或 migration。
 * 这里存的是"若干份可丢弃的快照"，用 JSON 文件最合适：
 * 删一个存档就是删一个文件，不留孤儿数据。
 */
/**
 * 一份布局存档。
 *
 * 位置/尺寸用 `List<Float>` 而不是嵌套对象，是为了让 Gson 的 JSON 更紧凑
 * （存档可能几十份，每份上百个节点）；读回来时再转成 Offset/Size。
 */
data class LayoutArchive(
    val id: String,
    val conversationKey: String,
    val title: String,
    val createdAt: Long,
    val nodeCount: Int,
    val scale: Float,
    val panX: Float,
    val panY: Float,
    /** nodeId -> [x, y] */
    val positions: Map<String, List<Float>>,
    /** nodeId -> [width, height] */
    val sizes: Map<String, List<Float>>,
    val userPlaced: List<String>
) {
    /** 展示用时间戳（不用完整日期，列表里短一点）。 */
    fun timeLabel(): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = createdAt
        return "%02d-%02d %02d:%02d".format(
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }
}

class LayoutArchiveStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "layout_archives")
    private val gson = Gson()

    // 显式标注类型：写成 `by mutableStateOf<List<LayoutArchive>>(...)` 时，
    // 编译器会在解析 `<` 时与比较表达式产生歧义而报一堆看不懂的错。
    var archives: List<LayoutArchive> by mutableStateOf(emptyList())
        private set

    init {
        refresh()
    }

    /** 当前会话的存档（列表永远按会话过滤，避免跨会话看到别人的排版）。 */
    fun listFor(conversationId: String?): List<LayoutArchive> {
        val key = conversationId ?: "no-conv"
        val all = archives
        return all.filter { it.conversationKey == key }
            .sortedByDescending { it.createdAt }
    }

    /**
     * 把当前画布状态存成一个命名存档。
     *
     * 同名会**覆盖**而不是产生第二份 —— 用户连点两次"保存"时，
     * 得到两个只差几秒的同名存档没有任何意义。
     */
    fun save(
        conversationId: String?,
        title: String,
        state: GraphViewState
    ): LayoutArchive {
        if (!dir.exists()) dir.mkdirs()
        val key = conversationId ?: "no-conv"
        val clean = title.trim().ifBlank { "未命名存档" }
        val existing = listFor(conversationId).firstOrNull { it.title == clean }

        val archive = LayoutArchive(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            conversationKey = key,
            title = clean,
            createdAt = System.currentTimeMillis(),
            nodeCount = state.positions.size,
            scale = state.scale,
            panX = state.pan.x,
            panY = state.pan.y,
            positions = state.positions.mapValues { (_, off) -> listOf(off.x, off.y) },
            sizes = state.nodeSizes.mapValues { (_, s) -> listOf(s.width, s.height) },
            userPlaced = state.userPlaced.toList()
        )
        File(dir, "${archive.id}.json").writeText(gson.toJson(archive))
        refresh()
        return archive
    }

    /**
     * 恢复某个存档到画布。
     *
     * 返回 false 表示这个存档里的节点**已经被删掉了一部分**，
     * 此时只恢复还存在的那些 —— 不能因为少了一张卡就整个恢复失败。
     */
    fun restore(archive: LayoutArchive, state: GraphViewState): Boolean {
        if (archive.positions.isEmpty()) return false

        // 先按存档清空，再逐个写回仍存在的节点。
        state.positions.clear()
        state.nodeSizes.clear()
        state.userPlaced.clear()

        var restored = 0
        archive.positions.forEach { (nodeId, xy) ->
            if (xy.size >= 2) {
                state.positions[nodeId] = androidx.compose.ui.geometry.Offset(xy[0], xy[1])
                restored++
            }
            archive.sizes[nodeId]?.let { wh ->
                if (wh.size >= 2) {
                    state.nodeSizes[nodeId] = androidx.compose.ui.geometry.Size(wh[0], wh[1])
                }
            }
        }
        // userPlaced 只保留存档里记下的、且当前仍存在的节点；
        // 否则这些 id 会永久留在"用户摆过"的名单里，让后续自动布局不敢覆盖它们。
        state.userPlaced.addAll(
            archive.userPlaced.filter { it in state.positions.keys }
        )

        state.scale = archive.scale
        state.pan = androidx.compose.ui.geometry.Offset(archive.panX, archive.panY)
        state.hasLaidOut = true
        // 恢复出来的就是"用户摆的"，清掉单份撤销备份，避免与存档语义混淆
        state.discardLayoutBackup()
        return restored > 0
    }

    fun delete(archiveId: String) {
        File(dir, "$archiveId.json").delete()
        refresh()
    }

    fun rename(archiveId: String, title: String) {
        val f = File(dir, "$archiveId.json")
        if (!f.exists()) return
        runCatching {
            val a = gson.fromJson(f.readText(), LayoutArchive::class.java)
            val updated = a.copy(title = title.trim().ifBlank { "未命名存档" })
            f.writeText(gson.toJson(updated))
        }
        refresh()
    }

    companion object {
        /** 存档名的默认建议：带时间，用户不想起名时直接用。 */
        fun suggestTitle(conversationId: String?): String {
            val cal = java.util.Calendar.getInstance()
            val hh = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val mm = cal.get(java.util.Calendar.MINUTE)
            return "排版 %02d:%02d".format(hh, mm)
        }
    }

    private fun refresh() {
        archives = if (!dir.exists()) emptyList() else
            dir.listFiles { f -> f.name.endsWith(".json") }
                .orEmpty()
                .mapNotNull { runCatching { gson.fromJson(it.readText(), LayoutArchive::class.java) }.getOrNull() }
    }
}
