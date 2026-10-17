package com.treechat.workbench.etymology.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 汉↔俄双向对译索引。
 *
 * 数据来源：`assets/han_ru.tsv`，列序 `中文词 \t 俄文词 \t 来源 \t 备注`（无表头）：
 *
 * - **来源 = `w`**：英文 Wiktionary 的**翻译表**（kaikki.org 的 English 转储）。
 *   同一个英文义项（sense）下同时列出的中文词与俄文词互为对译 —— 这是「同义项共现」，
 *   不是人造释义，且**绝不做跨义项的笛卡尔积**（否则会造出 银行(河岸义)↔берег 这类假对译）。
 *   备注列即该英文义项的原词，供人工回溯核对。
 * - **来源 = `v`**：本项目自带的 `assets/vocab_ru.tsv`（人工整理的俄→中表），备注列留空。
 *
 * 规模与体量：约 14.97 万条对译、汉 8.3 万 / 俄 4.8 万词头，文件 5.99 MB（< 6 MB 目标）。
 *
 * 形态约定：
 * - 俄文值**已去掉组合重音符**（U+0300–U+036F）。项目既有的 `vocab_ru.tsv`/`ru_ipa.tsv`
 *   一律不带重音，重音信息由 [RuPhonologyIndex] 的 IPA（ˈ）承担；统一形态也便于合并去重。
 * - 查表键的归一：中文侧 `trim()`；俄语侧与 [RuPhonologyIndex] 一致地
 *   `lowercase() + 去掉所有空白`，并额外剥掉组合重音符，因此**带重音与否都能命中**。
 *
 * 缺口（如实说明，取不到就返回空列表，绝不编造）：
 * 俄语侧无屈折还原（не ходил 这类变体不会归并到 ходить）；中文侧无繁简互转
 * （表里繁体、简体各自成条）。这些都交给调用方按其语义处理。
 */
object ZhRuIndex {

    private const val TAG = "ZhRuIndex"
    private const val ASSET = "han_ru.tsv"

    /** 来源标记：`w`=英文 Wiktionary 翻译表共现，`v`=项目自带 vocab_ru.tsv。 */
    const val SRC_WIKT_EN = "w"
    const val SRC_VOCAB_RU = "v"

    /** 中文词 -> 俄文词（多个）。 */
    @Volatile
    private var zhMap: Map<String, List<String>> = emptyMap()

    /** 归一后的俄文词 -> 中文词（多个）。 */
    @Volatile
    private var ruMap: Map<String, List<String>> = emptyMap()

    @Volatile
    var isReady: Boolean = false
        private set

    /** 已装载的对译条数，便于日志与自检。 */
    @Volatile
    var pairCount: Int = 0
        private set

    suspend fun ensureLoaded(context: Context) {
        if (isReady) return
        withContext(Dispatchers.IO) { loadBlocking(context.applicationContext) }
    }

    fun loadBlocking(context: Context) {
        synchronized(this) {
            if (isReady) return
            // 8.3 万 / 4.8 万词头，容量按 load factor 0.75 反推给足，避免扩容抖动。
            val zh = HashMap<String, MutableList<String>>(112_000)
            val ru = HashMap<String, MutableList<String>>(64_500)
            var n = 0
            runCatching {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 3) continue
                        val z = col[0].trim()
                        val r = col[1].trim()
                        if (z.isEmpty() || r.isEmpty()) continue
                        val zk = zhKey(z)
                        val rk = ruKey(r)
                        if (zk.isEmpty() || rk.isEmpty()) continue
                        zh.getOrPut(zk) { ArrayList(2) }.let { if (!it.contains(r)) it.add(r) }
                        ru.getOrPut(rk) { ArrayList(2) }.let { if (!it.contains(z)) it.add(z) }
                        n++
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET 失败，汉俄对译将全部落空：$it") }
            zhMap = zh
            ruMap = ru
            pairCount = n
            isReady = true
            Log.i(TAG, "汉俄词典索引就绪：$n 条对译（汉 ${zh.size} 词头 / 俄 ${ru.size} 词头）")
        }
    }

    /** 汉 → 俄。查不到返回空列表（调用方按"未收录"处理，不要编）。 */
    fun zhToRu(chinese: String): List<String> = zhMap[zhKey(chinese)].orEmpty()

    /** 俄 → 汉。查不到返回空列表。 */
    fun ruToZh(russian: String): List<String> = ruMap[ruKey(russian)].orEmpty()

    private fun zhKey(s: String): String = s.trim()

    /** 与 [RuPhonologyIndex] 的键规则一致（小写 + 去空白），并额外剥掉组合重音符。 */
    private fun ruKey(word: String): String = word.lowercase().filterNot { it.isWhitespace() || it.isCombiningAccent() }

    private fun Char.isCombiningAccent(): Boolean = code in 0x0300..0x036F
}
