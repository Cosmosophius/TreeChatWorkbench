package com.treechat.workbench.etymology.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 英语**真实同义词**与**真实词源关系**的只读索引（单次加载，常驻内存）。
 *
 * 两张资产都是**可溯源的外部数据集**，按本仓库词表（`ecdict.core.tsv` ∪ `vocab2.tsv`，
 * 合计 57,985 词）裁剪后落盘，不含任何启发式推断、不含人工补写：
 *
 * - `wordnet_senses.tsv` —— 来源 **WordNet 3.0**（Princeton University，2006，
 *   `https://wordnetcode.princeton.edu/3.0/WNdb-3.0.tar.gz`）。
 *   **逐 synset（同义集 = 一个意思）各占一行**，因此同义词是**按义项分组**的，
 *   不再把不同意思的同义词混成一串。列序：`词 \t 词性 \t 义项释义 \t 同义词1,同义词2,…`。
 *   - 词性取 WordNet `ss_type`（n/v/a/r，卫星形容词 s 归入 a）；
 *   - 义项释义取该 synset gloss 的第一分句，**英文原样**、限 56 字符，不翻译不改写；
 *   - 同义词**只保留词表里有的词**、且不含查询词本身，每组最多 10 个，
 *     因此每个同义词都能显示真实音标与释义；
 *   - 只保留"至少还有一个词表同义词"的义项（没有成员就没有可显示的内容，如实不列），
 *     每词最多 8 个义项，按组内词表成员数由多到少排序，常用义项在前。
 *   覆盖 23,857 词、42,514 个义项组。WordNet 里单成员 synset（如 beautiful）
 *   没有同义集成员，如实为空，不拿近似词（similar-to）冒充。
 *   由 `scripts/gen_wn_senses.py` 从官方 WNdb-3.0 的 `data.{noun,verb,adj,adv}`
 *   与本仓库词表（`ecdict.core.tsv` ∪ `vocab2.tsv`）生成，可复跑复核。
 *
 * - `etym_relations.tsv` —— 来源 **Etymological WordNet**（etymwn，Gerard de Melo，
 *   2013-02-08 版，`http://etym.org/etymwn-20130208.zip`，CC-BY-SA 3.0），
 *   由英语 Wiktionary 抽出的**真实词源派生关系**。只保留形如 `eng:<词>` 且该词在
 *   词表里的行，列序：`现代英文词 \t 关系 \t 源语言:源词`。
 *   关系取 `rel:etymology`（词源）、`rel:is_derived_from`（派生自）、
 *   `rel:etymologically_related`（同源），每词最多 8 条。
 *   覆盖 38,237 词（占词表 67.1%）。
 *
 * **注意**：这两份数据只提供**关系/集合**，**不含词源叙述文本（prose）**。
 * 调用方不得据此生成词源故事，只应如实展示"X 派生自 Y"这样的有据陈述。
 */
object EtymologyDataIndex {

    private const val TAG = "EtymologyDataIndex"

    private const val ASSET_SYN = "wordnet_senses.tsv"
    private const val ASSET_ETY = "etym_relations.tsv"

    /** Etymological WordNet 的一条词源关系：[relation] 为关系名（如 rel:etymology），[source] 为"语言码: 源词"。 */
    data class EtymologyRelation(val relation: String, val source: String)

    /**
     * WordNet 的**一个义项（synset）**：这个词在这个意思下有哪些同义词。
     *
     * [gloss] 是 WordNet 自带的英文释义原样（第一分句），用作界面上"这一组是哪个意思"的标题；
     * [pos] 是词性（n/v/a/r）；[members] 只含词表内、且不含查询词本身的同义词。
     */
    data class SynSenseGroup(
        val pos: String,
        val gloss: String,
        val members: List<String>
    )

    @Volatile
    private var synMap: Map<String, List<String>> = emptyMap()

    @Volatile
    private var senseMap: Map<String, List<SynSenseGroup>> = emptyMap()

    @Volatile
    private var etyMap: Map<String, List<EtymologyRelation>> = emptyMap()

    @Volatile
    var isReady: Boolean = false
        private set

    suspend fun ensureLoaded(context: Context) {
        if (isReady) return
        withContext(Dispatchers.IO) { loadBlocking(context.applicationContext) }
    }

    fun loadBlocking(context: Context) {
        synchronized(this) {
            if (isReady) return

            val sense = HashMap<String, MutableList<SynSenseGroup>>(24_000)
            runCatching {
                context.assets.open(ASSET_SYN).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 4) continue
                        val word = key(col[0])
                        if (word.isEmpty()) continue
                        val pos = col[1].trim()
                        val gloss = col[2].trim()
                        val members = col[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (gloss.isEmpty() || members.isEmpty()) continue
                        sense.getOrPut(word) { ArrayList(4) }.add(SynSenseGroup(pos, gloss, members))
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET_SYN 失败，同义词将全部落空：$it") }

            // 扁平同义词表由义项分组派生：按"同现义项数"由多到少取前 12 个，
            // 与分组数据同源，不会出现两份数据打架的情况。
            val syn = HashMap<String, List<String>>(sense.size)
            for ((word, groups) in sense) {
                val counts = HashMap<String, Int>(32)
                for (g in groups) for (m in g.members) counts[m] = (counts[m] ?: 0) + 1
                syn[word] = counts.entries
                    .map { it.key to it.value }
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                    .take(12)
                    .map { it.first }
            }

            val ety = HashMap<String, MutableList<EtymologyRelation>>(48_000)
            runCatching {
                context.assets.open(ASSET_ETY).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 3) continue
                        val word = key(col[0])
                        val rel = col[1].trim()
                        val src = col[2].trim()
                        if (word.isEmpty() || rel.isEmpty() || src.isEmpty()) continue
                        ety.getOrPut(word) { ArrayList(4) }.add(EtymologyRelation(rel, src))
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET_ETY 失败，词源关系将全部落空：$it") }

            synMap = syn
            senseMap = sense
            etyMap = ety
            isReady = true
            Log.i(
                TAG,
                "词源数据索引就绪：义项组 ${sense.values.sumOf { it.size }} 组 / ${sense.size} 词，" +
                    "词源关系 ${ety.size} 词"
            )
        }
    }

    /**
     * WordNet 3.0 同义词（**跨义项合并后的扁平列表**，按同现义项数排序）。
     * 查不到返回空表（调用方按"未收录"处理，不要编）。
     *
     * 需要"按意思分组"时用 [senseGroups]。
     */
    fun synonyms(word: String): List<String> = synMap[key(word)].orEmpty()

    /**
     * WordNet 3.0 **按义项分组**的同义词：每一组就是"这个词的某一个意思"，
     * [SynSenseGroup.gloss] 是该意思的英文释义，[SynSenseGroup.members] 是该意思下的同义词。
     *
     * 顺序 = 常用义项在前（生成时按组内词表成员数排序）；查不到返回空表。
     */
    fun senseGroups(word: String): List<SynSenseGroup> = senseMap[key(word)].orEmpty()

    /**
     * Etymological WordNet 的词源关系（结构化三元组）；查不到返回空表。
     * 只描述"该词与某源词存在某种真实词源关系"，不含叙述文本。
     */
    fun etymologyRelations(word: String): List<EtymologyRelation> = etyMap[key(word)].orEmpty()

    /** 同 [etymologyRelations]，但拼成可读串（如 `rel:etymology ← ang: leoht`）。 */
    fun etymologyRelationStrings(word: String): List<String> =
        etymologyRelations(word).map { "${it.relation} ← ${it.source}" }

    private fun key(word: String): String = word.trim().lowercase()

    /**
     * 语言码 → 语族/来源中文名。
     *
     * 这是**真实数据**：EtymWN 的每条派生关系都带源词语言码（如 `la:` 拉丁、`fro:` 古法语、
     * `grc:` 古希腊语、`ang:` 古英语）。用户要求"外文单词来源必须标注日耳曼语/法语之类"，
     * 于是这里把源语言码归到语族，**不再用按后缀猜的做法**。
     */
    private val LANG_FAMILY: Map<String, String> = mapOf(
        "lat" to "拉丁语", "la" to "拉丁语",
        "fro" to "古法语", "frm" to "中古法语", "fra" to "法语", "fr" to "法语",
        "xno" to "盎格鲁-诺曼语", "pro" to "古普罗旺斯语", "roa" to "罗曼语族",
        "ita" to "意大利语", "spa" to "西班牙语", "por" to "葡萄牙语", "roh" to "罗曼什语",
        "grc" to "古希腊语", "el" to "希腊语",
        "ang" to "古英语", "enm" to "中古英语", "non" to "古诺斯语", "is" to "冰岛语",
        "gem" to "日耳曼语族", "gmh" to "中古高地德语", "goh" to "古高地德语",
        "deu" to "德语", "nld" to "荷兰语", "got" to "哥特语", "osx" to "古撒克逊语",
        "swe" to "瑞典语", "dan" to "丹麦语",
        "cel" to "凯尔特语", "gle" to "爱尔兰语", "wlm" to "中古威尔士语",
        "sla" to "斯拉夫语族", "rus" to "俄语", "chu" to "古教会斯拉夫语",
        "heb" to "希伯来语", "ara" to "阿拉伯语", "pes" to "波斯语", "san" to "梵语",
        "jpn" to "日语", "kor" to "朝鲜语", "zho" to "汉语", "tur" to "土耳其语"
    )

    /**
     * 由**真实派生关系**推出该词的来源语族（按出现次数降序，最多 [limit] 个）。
     * 查不到返回空表 —— 不猜、不编。
     */
    fun originFamilies(word: String, limit: Int = 3): List<String> {
        val relations = etymologyRelations(word)
        if (relations.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        for (r in relations) {
            val code = r.source.substringBefore(':').lowercase()
            val family = LANG_FAMILY[code] ?: continue
            counts[family] = (counts[family] ?: 0) + 1
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
}
