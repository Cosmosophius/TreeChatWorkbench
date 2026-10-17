package com.treechat.workbench.ui.reader

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 电子书与文档资源池管理器。
 * 统一管理书架、文档状态、与条畅 RAG 知识库及 OCR 任务队列的联动。
 */
class ReaderDocumentPool(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("reader_document_pool", Context.MODE_PRIVATE)
    private val booksDir = File(context.filesDir, "reader_books").apply { if (!exists()) mkdirs() }

    val documents = mutableStateListOf<ReaderDocument>()

    init {
        loadDocuments()
    }

    private fun loadDocuments() {
        val json = prefs.getString("documents_list", null)
        if (json.isNullOrBlank()) return

        val decoded = runCatching {
            val type = object : TypeToken<List<ReaderDocument>>() {}.type
            gson.fromJson<List<ReaderDocument>>(json, type).orEmpty()
        }.getOrDefault(emptyList())

        // ① 正常路径：字段名与当前构建一致（已由 proguard 的 -keep 固定）。
        val usable = decoded.filter { it.decodeOk() }
        if (decoded.isNotEmpty() && usable.size == decoded.size) {
            documents.clear()
            documents.addAll(usable)
            return
        }

        // ② 迁移路径：这份 JSON 是**被混淆过字段名**的旧构建写下的（键是 a/b/c…），
        //    用当前字段名读会全部落空，format 为 null 会让书架在组合期 NPE。
        //    旧构建的键按 ReaderDocument 的**声明顺序**依次为 a..o，据此按位置复原。
        val salvaged = runCatching { salvageLegacyDocuments(json) }.getOrDefault(emptyList())
        documents.clear()
        documents.addAll(salvaged)
        if (salvaged.isNotEmpty()) persistDocuments()
    }

    /**
     * 旧构建字段名被 R8 混淆时留下的记录。键与声明顺序一一对应：
     * id,title,filePath,format,fileSize,pageCount,currentPage,isScanned,isOcrProcessed,
     * ocrProgress,isOcrRunning,isRagIndexed,ragChunkCount,lastReadTimestamp,sampleText。
     * 只在这一条路径上使用字母键，读不到就返回空 —— 宁可书架是空的，也不留会让界面崩的记录。
     */
    private fun salvageLegacyDocuments(json: String): List<ReaderDocument> {
        val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
        val out = ArrayList<ReaderDocument>(arr.size())
        for (el in arr) {
            val o = el.asJsonObject
            fun s(k: String): String =
                o.get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
            fun i(k: String, def: Int): Int =
                o.get(k)?.takeIf { !it.isJsonNull }?.asInt ?: def
            fun l(k: String, def: Long): Long =
                o.get(k)?.takeIf { !it.isJsonNull }?.asLong ?: def
            fun b(k: String, def: Boolean): Boolean =
                o.get(k)?.takeIf { !it.isJsonNull }?.asBoolean ?: def

            val id = s("a")
            val title = s("b")
            if (id.isBlank() || title.isBlank()) continue
            val format = runCatching { ReaderFormat.valueOf(s("d")) }.getOrNull() ?: continue
            out.add(
                ReaderDocument(
                    id = id,
                    title = title,
                    filePath = s("c"),
                    format = format,
                    fileSize = l("e", 0L),
                    pageCount = i("f", 1).coerceAtLeast(1),
                    currentPage = i("g", 1).coerceAtLeast(1),
                    isScanned = b("h", false),
                    isOcrProcessed = b("i", false),
                    ocrProgress = o.get("j")?.takeIf { !it.isJsonNull }?.asFloat ?: 0f,
                    isOcrRunning = b("k", false),
                    isRagIndexed = b("l", false),
                    ragChunkCount = i("m", 0),
                    lastReadTimestamp = l("n", System.currentTimeMillis()),
                    sampleText = s("o")
                )
            )
        }
        return out
    }

    /**
     * Gson 绕过构造函数，字段对不上时会留下 null。用可空读法判定一条记录是否可用：
     * 任何一处抛 NPE（说明字段是 null）即判为不可用。
     */
    private fun ReaderDocument.decodeOk(): Boolean = try {
        id.isNotBlank() && title.isNotBlank() && format.name.isNotEmpty()
    } catch (_: NullPointerException) {
        false
    }

    private fun persistDocuments() {
        val json = gson.toJson(documents.toList())
        prefs.edit().putString("documents_list", json).apply()
    }

    fun updateProgress(docId: String, page: Int) {
        val index = documents.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = documents[index]
            documents[index] = doc.copy(
                currentPage = page.coerceIn(1, doc.pageCount),
                lastReadTimestamp = System.currentTimeMillis()
            )
            persistDocuments()
        }
    }

    fun updateRagStatus(docId: String, isIndexed: Boolean, chunkCount: Int) {
        val index = documents.indexOfFirst { it.id == docId }
        if (index != -1) {
            documents[index] = documents[index].copy(
                isRagIndexed = isIndexed,
                ragChunkCount = chunkCount
            )
            persistDocuments()
        }
    }

    fun updateOcrStatus(docId: String, isRunning: Boolean, progress: Float, isProcessed: Boolean) {
        val index = documents.indexOfFirst { it.id == docId }
        if (index != -1) {
            documents[index] = documents[index].copy(
                isOcrRunning = isRunning,
                ocrProgress = progress,
                isOcrProcessed = isProcessed
            )
            persistDocuments()
        }
    }

    /** 用识别出的正文替换文档内容，并同步重算页数。 */
    fun replaceDocumentContent(docId: String, text: String, isOcrProcessed: Boolean) {
        val index = documents.indexOfFirst { it.id == docId }
        if (index == -1) return
        val doc = documents[index]
        val pages = ((text.length + DocumentFileReader.CHARS_PER_PAGE - 1) /
            DocumentFileReader.CHARS_PER_PAGE).coerceAtLeast(1)
        documents[index] = doc.copy(
            sampleText = text,
            pageCount = maxOf(doc.pageCount, pages),
            isOcrProcessed = isOcrProcessed,
            isOcrRunning = false,
            ocrProgress = 1f
        )
        persistDocuments()
    }

    /** 重命名一份文档的展示标题（与 Room 侧的重命名一起调用，保持两处标题一致）。 */
    fun renameDocument(docId: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        val index = documents.indexOfFirst { it.id == docId }
        if (index != -1) {
            documents[index] = documents[index].copy(title = trimmed)
            persistDocuments()
        }
    }

    fun addDocument(doc: ReaderDocument) {
        documents.add(0, doc)
        persistDocuments()
    }

    fun removeDocument(docId: String) {
        documents.removeAll { it.id == docId }
        persistDocuments()
    }
}
