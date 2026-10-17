package com.treechat.workbench.etymology.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置离线词库的只读索引（单次加载，常驻内存）。
 *
 * 数据来源仅限 `app/src/main/assets/` 下的三张 TSV，不做任何人工补写：
 * - `vocab2.tsv`      英→中（考研词表）。列序：`词 \t 音标 \t 中文释义 \t 标签 \t 等级`
 * - `ecdict.core.tsv` 英→中（ECDICT 按词频裁剪出的常用词核心，约 5.7 万条，同列序的前三列）
 * - `vocab_ru.tsv`    俄→中。列序：`词 \t (始终为空) \t 中文释义 \t 标签 \t 等级`
 *
 * 两张英语表**合并**使用：先铺 `vocab2`，再用 `ecdict.core` 补齐它没有的词，
 * 并给音标为空的行补上音标（考研表里有 4000 余行没有音标）。
 * 单靠 `vocab2` 时 light / holy / love 这类常用词都查不到，只能显示"未收录"。
 *
 * 第二列在俄语表中全表为空，因此俄语一侧只有释义、没有音标来源，取用时需按"未收录"处理。
 *
 * `charToEnglish` / `charToRussian` 是派生索引：把"中文释义"列里出现过的汉字
 * 反查回所属词条。它只反映"该字出现在该词条的释义里"，不代表义项等价。
 */
object VocabIndex {

    private const val TAG = "VocabIndex"

    private const val ASSET_EN = "vocab2.tsv"
    private const val ASSET_EN_CORE = "ecdict.core.tsv"
    private const val ASSET_RU = "vocab_ru.tsv"

    /** 单个汉字保留的候选词条上限：释义长句里"的/一"一类高频字否则会把索引撑大。 */
    private const val MAX_PER_CHAR = 48

    /** [exchange] 是 ECDICT 的**真实词形/派生关系**串（如 `d:abandoned/p:abandoned/3:abandons`）。 */
    data class EnEntry(
        val word: String,
        val ipa: String,
        val gloss: String,
        val exchange: String = "",
        /**
         * 考纲标签（**仅考研词表 vocab2.tsv** 的第 4 列，逗号分隔）。
         *
         * 现存 token 只有五种：`cet6` / `ielts` / `toefl` / `gre` / `ext`（超纲词）。
         * —— 2026-10-17 用户明确：四级、考研、中考、高考类目**不需要**，
         * 本地块的 tsv 也确实不携带这些标签；阅读器的生词匹配按这份清单做。
         */
        val tags: Set<String> = emptySet()
    )
    data class RuEntry(val word: String, val gloss: String)

    @Volatile private var enMap: Map<String, EnEntry> = emptyMap()
    @Volatile private var ruMap: Map<String, RuEntry> = emptyMap()
    @Volatile private var charToEnglish: Map<String, List<String>> = emptyMap()
    @Volatile private var charToRussian: Map<String, List<String>> = emptyMap()

    @Volatile
    var isReady: Boolean = false
        private set

    /** 供 Application 在 IO 线程调用；重复调用无副作用。 */
    suspend fun ensureLoaded(context: Context) {
        if (isReady) return
        withContext(Dispatchers.IO) { loadBlocking(context.applicationContext) }
    }

    fun loadBlocking(context: Context) {
        synchronized(this) {
            if (isReady) return

            val en = HashMap<String, EnEntry>(80_000)
            val charEn = HashMap<String, MutableList<String>>(4_096)
            runCatching {
                context.assets.open(ASSET_EN).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 3) continue
                        val key = col[0].trim().lowercase()
                        if (key.isEmpty()) continue
                        val gloss = col[2].trim()
                        // 考纲标签（col[3]，逗号分隔）与等级（col[4]）是阅读器
                        // 「生词匹配」的数据源，加载时顺手收下 —— 等级列
                        // 是"每个标签对应一个星"的成对串，这里只保 tags。
                        val tags = if (col.size > 4) {
                            col[3].split(',').map { it.trim().lowercase() }
                                .filter { it.isNotEmpty() }.toSet()
                        } else {
                            emptySet()
                        }
                        en[key] = EnEntry(col[0].trim(), col[1].trim(), gloss, tags = tags)
                        indexGlossChars(gloss, key, charEn)
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET_EN 失败：$it") }

            // 常用词核心表：补 vocab2 缺的词，并回填它缺失的音标。
            // 两表冲突时以 vocab2（考研口径）为准，只补空缺，不覆盖。
            runCatching {
                context.assets.open(ASSET_EN_CORE).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 3) continue
                        val key = col[0].trim().lowercase()
                        if (key.isEmpty()) continue
                        val gloss = col[2].trim()
                        val exchange = if (col.size > 3) col[3].trim() else ""
                        val existing = en[key]
                        if (existing == null) {
                            en[key] = EnEntry(col[0].trim(), col[1].trim(), gloss, exchange)
                            indexGlossChars(gloss, key, charEn)
                        } else {
                            en[key] = existing.copy(
                                ipa = if (existing.ipa.isEmpty()) col[1].trim() else existing.ipa,
                                exchange = if (existing.exchange.isEmpty()) exchange else existing.exchange
                            )
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET_EN_CORE 失败，常用词仍可能查不到：$it") }

            val ru = HashMap<String, RuEntry>(8_000)
            val charRu = HashMap<String, MutableList<String>>(2_048)
            runCatching {
                context.assets.open(ASSET_RU).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 3) continue
                        val word = col[0].trim()
                        if (word.isEmpty()) continue
                        val key = russianKey(word)
                        val gloss = col[2].trim()
                        ru[key] = RuEntry(word, gloss)
                        indexGlossChars(gloss, key, charRu)
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET_RU 失败，俄汉查表将全部落空：$it") }

            enMap = en
            ruMap = ru
            charToEnglish = charEn
            charToRussian = charRu
            isReady = true
        }
    }

    fun english(word: String): EnEntry? = enMap[word.trim().lowercase()]

    /** 阅读器「生词匹配」的考纲类目（token 是 vocab2.tsv 第 4 列里的硬标识）。 */
    data class ExamCategory(val token: String, val label: String)

    /** 考纲清单（顺序即侧栏展示顺序）。`ext`（超纲词）不是考纲，刻意不入选。 */
    val EXAM_CATEGORIES: List<ExamCategory> = listOf(
        ExamCategory("cet6", "六级"),
        ExamCategory("ielts", "雅思"),
        ExamCategory("toefl", "托福"),
        ExamCategory("gre", "GRE")
    )

    /** 词的考纲标签（未收录返回空集）。 */
    fun examTags(word: String): Set<String> =
        enMap[word.trim().lowercase()]?.tags.orEmpty()

    /**
     * 在已启用 [tokens] 的考纲下，返回会命中的**词形集合**（小写键）。
     *
     * 供阅读器整页匹配用：一次算好，逐词只需查这个集合。
     * 词库约 13,807 行，整表过滤一次的成本可以忽略（只在切换考纲时调用）。
     */
    fun examWordsFor(tokens: Set<String>): Set<String> {
        if (tokens.isEmpty()) return emptySet()
        val out = HashSet<String>(4_096)
        enMap.forEach { (key, entry) ->
            if (entry.tags.any { it in tokens }) out.add(key)
        }
        return out
    }

    fun russian(word: String): RuEntry? = ruMap[russianKey(word)]

    /** 索引内部保存的是规范化键，反查时用它。 */
    fun russianByKey(key: String): RuEntry? = ruMap[key]

    /**
     * 真实词形派生关系。取自 ECDICT 的 exchange 列，**不是推测**：
     * 0=原型、s=复数、p=过去式、d=过去分词、i=现在分词、3=第三人称单数、r=比较级、t=最高级。
     */
    data class DerivedForm(val label: String, val form: String)

    private val EXCHANGE_LABELS = mapOf(
        "0" to "原型", "s" to "复数", "p" to "过去式", "d" to "过去分词",
        "i" to "现在分词", "3" to "第三人称单数", "r" to "比较级", "t" to "最高级"
    )

    fun derivedForms(word: String): List<DerivedForm> {
        val ex = enMap[word.trim().lowercase()]?.exchange.orEmpty()
        if (ex.isEmpty()) return emptyList()
        return ex.split('/').mapNotNull { seg ->
            val i = seg.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val form = seg.substring(i + 1).trim()
            if (form.isEmpty()) return@mapNotNull null
            DerivedForm(EXCHANGE_LABELS[seg.substring(0, i)] ?: seg.substring(0, i), form)
        }
    }

    /** 释义中出现过该汉字的英文词（按词表顺序，最多 [MAX_PER_CHAR] 个）。 */
    fun englishWordsForChar(ch: Char): List<String> = charToEnglish[ch.toString()].orEmpty()

    /** 释义中出现过该汉字的俄语词（按词表顺序，最多 [MAX_PER_CHAR] 个）。 */
    fun russianWordsForChar(ch: Char): List<String> = charToRussian[ch.toString()].orEmpty()

    /** 俄语表的词形在表内带空格（如 `cимпати чный`），查表前统一去掉空白。 */
    private fun russianKey(word: String): String =
        word.lowercase().filterNot { it.isWhitespace() }

    private fun indexGlossChars(
        gloss: String,
        key: String,
        sink: HashMap<String, MutableList<String>>
    ) {
        if (gloss.isEmpty()) return
        val seen = HashSet<Char>()
        for (ch in gloss) {
            if (ch < '\u4E00' || ch > '\u9FA5') continue
            if (!seen.add(ch)) continue
            val bucket = sink.getOrPut(ch.toString()) { ArrayList(4) }
            if (bucket.size < MAX_PER_CHAR && key !in bucket) bucket.add(key)
        }
    }
}
