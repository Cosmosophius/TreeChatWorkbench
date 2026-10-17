package com.treechat.workbench.scan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import java.io.File

/**
 * 扫描会话 = 一次"把一堆纸变成一本书"的工作。
 *
 * ## 为什么要存"会话"而不是直接生成 PDF
 * 用户提的核心需求是**拍一半、保存、之后继续拍**。
 * 如果每次拍照都立刻合成一个 PDF，就没法"接着上次那本继续"——
 * 多页合成需要等到最后，而等到最后就意味着中途任何中断都会丢前面所有页。
 *
 * 所以拆成两层：
 *   · **会话**（本类）：拍一页存一页，随时可退出可续；
 *   · **PDF**：只在用户点"合成"时才生成，生成后仍保留会话（可继续加页再合成）。
 *
 * ## 落盘策略：每页一个独立文件
 * 页图存在 `filesDir/scans/<sessionId>/page_<序号>.jpg`，会话元数据单独存一份 JSON。
 * 这样**单页损坏不会拖垮整本**，中途被系统杀进程也只丢当前这一页。
 * 不用 SQLite：这里要新增的是"文件 + 一份索引"，建表反而更重，
 * 且 Room 的迁移纪律要求绝对不动表结构（本项目硬约束）。
 */
class ScanSessionStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ---- 进程内状态（界面直接 observe）----

    var current by mutableStateOf<ScanSession?>(null)
        private set

    /** 全部会话（书架里的"扫描草稿"列表用）。 */
    var sessions by mutableStateOf<List<ScanSession>>(emptyList())
        private set

    init {
        sessions = loadAll()
    }

    // ---------------------------------------------------------------- 会话生命周期

    /** 新建一个空会话（还未拍任何页）。同名会话会复用，避免"扫描 1/扫描 2/扫描 3"。 */
    fun newSession(title: String? = null): ScanSession {
        val s = ScanSession(
            id = java.util.UUID.randomUUID().toString(),
            title = title ?: nextAutoTitle()
        )
        dirOf(s.id).mkdirs()
        persist(s)
        refreshList()
        current = s
        return s
    }

    fun openSession(id: String) {
        current = load(id)
        refreshList()
    }

    /**
     * 关闭当前会话（回到书架）。
     *
     * **不清任何东西**：这正是"续拍"的语义 —— 关掉只是离开，
     * 下次从会话列表里再点进来就继续。
     */
    fun closeCurrent() {
        current = null
    }

    fun deleteSession(id: String) {
        dirOf(id).deleteRecursively()
        val meta = File(app.filesDir, "$META_DIR/$id.json")
        meta.delete()
        if (current?.id == id) current = null
        refreshList()
    }

    fun renameSession(id: String, title: String) {
        val s = load(id) ?: return
        persist(s.copy(title = title))
        refreshList()
        if (current?.id == id) current = s.copy(title = title)
    }

    // ---------------------------------------------------------------- 页面增删排序

    /**
     * 追加一页。**每拍一张立刻调用**，调用完成即已落盘。
     *
     * @param jpeg 编码好的页图字节（调用方负责压缩，见 ScanImageProcessor）
     * @return 新的页数
     */
    fun appendPage(scanSession: ScanSession, jpeg: ByteArray): Int {
        val s = scanSession
        val page = ScanPage(index = s.pages.size + 1)
        File(dirOf(s.id), fileNameOf(page)).writeBytes(jpeg)
        val updated = s.copy(pages = s.pages + page, updatedAt = System.currentTimeMillis())
        persist(updated)
        if (current?.id == updated.id) current = updated
        refreshList()
        return updated.pages.size
    }

    fun deletePage(scanSession: ScanSession, index: Int) {
        val s = scanSession
        val page = s.pages.firstOrNull { it.index == index } ?: return
        File(dirOf(s.id), fileNameOf(page)).delete()
        val kept = s.pages.filterNot { it.index == index }
            .mapIndexed { i, p -> p.copy(index = i + 1) }   // 重排序号，文件名同步改名
        // 先把被删页之后的页序号改掉，需要重命名文件
        val old = s.pages.filter { it.index > index }
        val newIndexById = kept.associateBy { it.id }
        old.forEach { p ->
            newIndexById[p.id]?.let { np ->
                File(dirOf(s.id), fileNameOf(p)).renameTo(File(dirOf(s.id), fileNameOf(np)))
            }
        }
        val updated = s.copy(pages = kept, updatedAt = System.currentTimeMillis())
        persist(updated)
        if (current?.id == updated.id) current = updated
        refreshList()
    }

    /** 把某页往上/往下移一位（调整拍摄顺序）。 */
    fun movePage(scanSession: ScanSession, index: Int, delta: Int) {
        val s = scanSession
        val list = s.pages.toMutableList()
        val from = list.indexOfFirst { it.index == index }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return
        list.add(to, list.removeAt(from))
        val renumbered = list.mapIndexed { i, p -> p.copy(index = i + 1) }
        // 序号变了 → 文件名跟着改
        renumbered.forEachIndexed { i, np ->
            val oldFile = File(dirOf(s.id), fileNameOf(list.getOrNull(i) ?: np))
            if (oldFile.exists() && oldFile.name != fileNameOf(np)) {
                oldFile.renameTo(File(dirOf(s.id), fileNameOf(np)))
            }
        }
        val updated = s.copy(pages = renumbered, updatedAt = System.currentTimeMillis())
        persist(updated)
        if (current?.id == updated.id) current = updated
        refreshList()
    }

    // ---------------------------------------------------------------- 内部

    private fun nextAutoTitle(): String {
        var n = 1
        val titles = loadAll().map { it.title }.toSet()
        while ("扫描件 $n" in titles) n++
        return "扫描件 $n"
    }

    private fun dirOf(sessionId: String): File = File(app.filesDir, "scans/$sessionId")

    private fun fileNameOf(page: ScanPage) = "page_${page.index}.jpg"

    private fun pageFile(sessionId: String, page: ScanPage) = File(dirOf(sessionId), fileNameOf(page))

    fun pageFileOf(sessionId: String, page: ScanPage): File = pageFile(sessionId, page)

    private fun metaFile(id: String) = File(app.filesDir, "$META_DIR/$id.json")

    /** 供界面直接改会话设置（彩色/黑白、自动裁边）并落盘。 */
    fun persistSession(s: ScanSession) {
        persist(s.copy(updatedAt = System.currentTimeMillis()))
        if (current?.id == s.id) current = s
        refreshList()
    }

    private fun persist(s: ScanSession) {
        val dir = File(app.filesDir, META_DIR)
        if (!dir.exists()) dir.mkdirs()
        metaFile(s.id).writeText(gson.toJson(s))
    }

    private fun load(id: String): ScanSession? = runCatching {
        val f = metaFile(id)
        if (!f.exists()) return@runCatching null
        gson.fromJson(f.readText(), ScanSession::class.java)
    }.getOrNull()

    private fun loadAll(): List<ScanSession> {
        val dir = File(app.filesDir, META_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { runCatching { gson.fromJson(it.readText(), ScanSession::class.java) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    private fun refreshList() {
        sessions = loadAll()
    }

    private companion object {
        const val PREFS = "treechat_scan"
        const val META_DIR = "scan_meta"
    }
}