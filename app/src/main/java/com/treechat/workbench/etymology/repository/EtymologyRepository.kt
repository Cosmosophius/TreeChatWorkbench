package com.treechat.workbench.etymology.repository

import com.treechat.workbench.etymology.data.*
import com.treechat.workbench.etymology.data.EtymologyDataIndex
import com.treechat.workbench.etymology.data.ZhRuIndex
import com.treechat.workbench.etymology.rp.ConservativeRp

/**
 * 三语词源仓储单例 (EtymologyRepository)
 *
 * 数据口径：
 * 1. 音标与释义一律取自 `assets/vocab2.tsv`（英→中，含真实 IPA）与
 *    `assets/vocab_ru.tsv`（俄→中，第二列音标全表为空），经 [VocabIndex] 单次加载后查表；
 * 2. 词库未收录时返回"未收录"标注，不生成占位音标、不套用其它词的数据；
 * 3. 汉字音韵（普通话 IPA、中古广韵、上古四家拟音）只对下方 CMN_PRESET 收录的
 *    神/圣/道/光/智 五字给出，其余汉字只有库里查得的释义线索；
 * 4. 英语 `origin` / `evolution` 与俄语语源归属由词形启发式推断得出，非词库数据。
 */
object EtymologyRepository {

    /** 词库（两张 TSV）不含对应字段时的统一标注。 */
    private const val UNRECORDED = "未收录"
    private const val IPA_UNRECORDED = "（本词库未收录音标）"
    private const val IPA_TRAD_MISSING = "（无现代音标，无法推导）"
    private const val PINYIN_UNRECORDED = "（本词库未收录拼音）"
    private const val TONE_UNRECORDED = "（本词库未收录调值）"
    private const val TRANSLIT_UNRECORDED = "（本词库未收录转写）"
    private const val GLOSS_UNRECORDED = "词库未收录释义"
    private const val ORIGIN_UNRECORDED = "词库未收录语源"
    private const val REGISTER_BRIDGED = "据共有释义字桥接"
    private const val RARITY_BRIDGED = "词库相关词"
    private const val RARITY_RECORDED = "词库已收录"

    // 核心概念典范数据库（人工整理，仅以下五字）
    private val CMN_PRESET = mapOf(
        "神" to CmnPhonologyData(
            char = "神", pinyin = "shén", ipa = "[ʂən˧˥]", tone = "阳平 (35)",
            middleChinese = MiddleChineseData("船母 (ʑ)", "真韵 (开三)", "三等", "开口", "ʑiɪn", "d͡ʑiɪn", "食伦切"),
            oldChinese = OldChineseReconstruction("/*ljo/ 或 /*ɢlin/", "/*m-li[n]/", "/*djən/", "/*gljən/"),
            meaning = "神明，万物之造化本源；超绝玄妙"
        ),
        "圣" to CmnPhonologyData(
            char = "圣", pinyin = "shèng", ipa = "[ʂɤŋ˥˩]", tone = "去声 (51)",
            middleChinese = MiddleChineseData("审母/书母 (ɕ)", "清韵 (开三)", "三等", "开口", "ɕiæŋ", "ɕiɛŋ", "式正切"),
            oldChinese = OldChineseReconstruction("/*hljeŋs/", "/*k-l̥eŋ-s/", "/*sʰjyeŋs/", "/*sthjengs/"),
            meaning = "通达敏慧，最高尚纯全之德；超凡入圣"
        ),
        "道" to CmnPhonologyData(
            char = "道", pinyin = "dào", ipa = "[taʊ˥˩]", tone = "去声 (51)",
            middleChinese = MiddleChineseData("定母 (d)", "号韵 (开一)", "一等", "开口", "dɑu", "dɑu", "徒到切"),
            oldChinese = OldChineseReconstruction("/*luːs/", "/*k.lˤuʔ-s/", "/*dɔ̂g/", "/*dgəg/"),
            meaning = "所行之道，天地万物之本体规制与规律"
        ),
        "光" to CmnPhonologyData(
            char = "光", pinyin = "guāng", ipa = "[kuɑŋ˥˥]", tone = "阴平 (55)",
            middleChinese = MiddleChineseData("见母 (k)", "唐韵 (合一)", "一等", "合口", "kuɑŋ", "kuɑŋ", "古黄切"),
            oldChinese = OldChineseReconstruction("/*kʷaːŋ/", "/*kʷˤaŋ/", "/*kwɑŋ/", "/*kwang/"),
            meaning = "照亮黑暗之明辉、荣耀"
        ),
        "智" to CmnPhonologyData(
            char = "智", pinyin = "zhì", ipa = "[tʂʐ̩˥˩]", tone = "去声 (51)",
            middleChinese = MiddleChineseData("知母 (ʈ)", "寘韵 (开三)", "三等", "开口", "ʈiɛ", "ʈiè", "知义切"),
            oldChinese = OldChineseReconstruction("/*treːɡs/", "/*tre-s/", "/*ti̯eɡs/", "/*trjags/"),
            meaning = "智慧，鉴察通达之识"
        )
    )

    /**
     * 预设宏观概念的**精确触发词**。
     *
     * 旧版用的是 `contains("光")` 这类包含判断，于是「光滑」「光学」都会被当成
     * 单字「光」的概念栏展开 —— 看起来就像"多音节词只映射了第一个字"。
     * 这里改为精确匹配整词：只有查询词**就是**这些概念词时才展开预设栏，
     * 其余多字词一律走 [deriveChineseAlignment] 的整词逻辑。
     */
    private val HOLY_TRIGGERS = setOf("神", "圣", "聖", "神圣", "圣洁", "holy", "sacred", "святой", "священный")
    private val LIGHT_TRIGGERS = setOf("光", "光明", "光辉", "light", "свет")
    private val WISDOM_TRIGGERS = setOf("智", "智慧", "wise", "wisdom", "мудрость", "мудрый")

    fun resolve(rawQuery: String): CrossLinguisticAlignment {
        val q = extractCoreWord(rawQuery).trim()
        val lowerQ = q.lowercase()

        // 1. 若整词命中预设宏观概念，展开典范全息大栏
        if (lowerQ in HOLY_TRIGGERS) {
            return buildHolyAlignment(q)
        }
        if (lowerQ in LIGHT_TRIGGERS) {
            return buildLightAlignment(q)
        }
        if (lowerQ in WISDOM_TRIGGERS) {
            return buildWisdomAlignment(q)
        }

        // 2. 俄语西里尔字母智能推导
        if (isCyrillic(q)) {
            return deriveRussianAlignment(q)
        }

        // 3. 汉字智能推导
        if (isChinese(q)) {
            return deriveChineseAlignment(q)
        }

        // 4. 英语词：查内置英汉词表
        return deriveEnglishAlignment(q)
    }

    private fun extractCoreWord(text: String): String {
        val clean = text.trim()
        if (clean.length <= 15) return clean
        // 长句自动提取首个有效实词
        val words = clean.split(Regex("[\\s,，.。!！?？;；:：\"'“”‘’]+")).filter { it.isNotBlank() }
        return words.firstOrNull { it.length >= 2 } ?: clean.take(8)
    }

    private fun isCyrillic(text: String): Boolean {
        return text.any { it in '\u0400'..'\u04FF' }
    }

    private fun isChinese(text: String): Boolean {
        return text.any { it in '\u4E00'..'\u9FA5' }
    }

    /**
     * 英语词/词组：音标与释义查内置英汉词表，未收录时如实标注。
     *
     * 查询词可能是**多词短语**（如 `in spite of`）。词库的键是"单个词"，所以：
     * 1. 先按**整段**（原文 trim + 小写）查一次，命中就用它；
     * 2. 未命中且含空格时，**按空格切成词段逐段查**（既不是只取第一个 token，
     *    也不是把空格抹掉拼成一个根本不在词表里的假词）；
     * 3. 一段都没命中就如实写"未收录"，不编音标、不编释义。
     */
    private fun deriveEnglishAlignment(word: String): CrossLinguisticAlignment {
        val wholeKey = word.trim().lowercase()
        val tokens = englishTokens(word)
        val wholeEntry = VocabIndex.english(wholeKey)
        val resolved: List<Pair<String, VocabIndex.EnEntry>> = if (wholeEntry != null) {
            listOf(wholeKey to wholeEntry)
        } else {
            tokens.mapNotNull { t -> VocabIndex.english(t)?.let { t to it } }
        }
        val isPhrase = tokens.size > 1 || wholeKey.contains(' ')
        val primaryKey = resolved.firstOrNull()?.first ?: wholeKey
        val primaryGloss = resolved.firstOrNull()?.second?.gloss.orEmpty()

        // 真实词源：Etymological WordNet 的派生关系（eng:light ← ang:lēoht）。
        // **不做"按后缀猜语族"的启发式** —— 那是在假装有词源；查不到就如实写未收录。
        val relations = EtymologyDataIndex.etymologyRelations(primaryKey)

        // 目标词块：单字词一块；词组则每个命中的词段各一块（不再只留第一个 token）。
        val targets = resolved.mapIndexed { index, pair ->
            val key = pair.first
            val entry = pair.second
            val isPrimary = index == 0
            val evolutionText = buildString {
                if (isPrimary && relations.isNotEmpty()) {
                    append(relations.joinToString("；") { "${it.relation} ${it.source}" })
                    append("；")
                }
                append(describeGloss(entry.gloss))
            }
            EnglishEtymWord(
                word = entry.word.ifBlank { key },
                ipaModern = ipaOf(entry),
                ipaTrad = if (entry.ipa.isBlank()) IPA_UNRECORDED else tradIpaOf(entry.word, ipaOf(entry)),
                // 来源语族取自 EtymWN 的**源词语言码**（lat→拉丁、fro→古法语、grc→古希腊语、
                // ang→古英语…），是真实数据，不是按后缀猜的。
                origin = if (isPrimary) {
                    val fams = EtymologyDataIndex.originFamilies(primaryKey)
                    when {
                        fams.isNotEmpty() -> fams.joinToString(" / ")
                        relations.isNotEmpty() -> "词源关系（EtymWN）"
                        else -> ORIGIN_UNRECORDED
                    }
                } else ORIGIN_UNRECORDED,
                evolution = evolutionText,
                register = UNRECORDED,
                rarity = if (entry.word.length > 8) "进阶高阶 ★★★☆☆" else "常用核心 ★★★★★"
            )
        }

        // 一段都没命中：如实写"未收录"，不生成任何占位音标。
        if (targets.isEmpty()) {
            val miss = EnglishEtymWord(
                word = wholeKey,
                ipaModern = IPA_UNRECORDED,
                ipaTrad = IPA_UNRECORDED,
                origin = ORIGIN_UNRECORDED,
                evolution = if (isPhrase) {
                    "内置词表未收录该词组（${tokens.size} 个词段逐一查过，均未命中）"
                } else {
                    "内置词表未收录该词"
                },
                register = UNRECORDED,
                rarity = GLOSS_UNRECORDED
            )
            return CrossLinguisticAlignment(
                conceptId = "eng_$primaryKey",
                canonicalZh = wholeKey,
                summary = "【$wholeKey】本词库未收录该词；无音标、无释义可给出（不编造）。",
                cmnList = emptyList(),
                engCluster = EnglishCluster("英文词库未收录", wholeKey, listOf(miss)),
                rusCluster = null
            )
        }

        // 真实同义词：WordNet 同义集（只保留音标词表里也有的词，保证每条都有真实音标）。
        // 只取主词的，避免词组场景同义词爆炸。
        val synWords = EtymologyDataIndex.synonyms(primaryKey)
            .asSequence()
            .filter { it != primaryKey }
            .mapNotNull { syn ->
                VocabIndex.english(syn)?.let { englishWordOf(it).copy(origin = "同义词（WordNet）") }
            }
            .distinctBy { it.word }
            .filter { w -> targets.none { it.word == w.word } }
            .take(12)
            .toList()

        // 词形派生（ECDICT exchange）仍保留，它是真实数据、且与同义词互补。
        val derivedForms = derivedFormsOf(targets, targets.map { it.word } + synWords.map { it.word })

        val concept = if (isPhrase && wholeEntry == null) {
            "词组「$wholeKey」未收录整条，以下按词段逐项（${resolved.size}/${tokens.size} 命中）"
        } else {
            "同义词（WordNet）与词形派生（ECDICT）"
        }

        val summary = buildString {
            append("【$wholeKey】")
            if (isPhrase) append("按 ${resolved.size}/${tokens.size} 个词段命中。")
            if (primaryGloss.isNotBlank()) append("词库释义：$primaryGloss。") else append("本词库未收录释义。")
            append(
                if (synWords.isEmpty()) "同义词：WordNet 未收录。" else "同义词 ${synWords.size} 个（WordNet）。"
            )
            append(
                if (relations.isEmpty()) "词源：EtymWN 未收录派生关系。" else "词源关系 ${relations.size} 条（EtymWN）。"
            )
            append("音标取自内置英汉词表。")
        }

        return CrossLinguisticAlignment(
            conceptId = "eng_$primaryKey",
            canonicalZh = wholeKey,
            summary = summary,
            cmnList = presetEntriesIn(primaryGloss),
            engCluster = EnglishCluster(concept, wholeKey, targets + synWords + derivedForms),
            rusCluster = bridgeRussian(primaryGloss, limit = 2)
        )
    }

    /** 把查询串切成"小写 + 只留字母/连字符"的词段（去重、保序）。 */
    private fun englishTokens(word: String): List<String> =
        word.trim().lowercase()
            .split(Regex("\\s+"))
            .map { it.filter { c -> c.isLetter() || c == '-' } }
            .filter { it.isNotEmpty() }
            .distinct()

    /** 一组英文词的**真实词形派生**（ECDICT exchange，含词表内音标），排除已在 [exclude] 里的词。 */
    private fun derivedFormsOf(
        bases: List<EnglishEtymWord>,
        exclude: List<String>
    ): List<EnglishEtymWord> =
        bases.flatMap { ew -> VocabIndex.derivedForms(ew.word).map { ew.word to it } }
            .mapNotNull { pair ->
                val e = VocabIndex.english(pair.second.form) ?: return@mapNotNull null
                EnglishEtymWord(
                    word = e.word,
                    ipaModern = ipaOf(e),
                    ipaTrad = if (e.ipa.isBlank()) IPA_UNRECORDED else tradIpaOf(e.word, ipaOf(e)),
                    origin = "词形派生（ECDICT）",
                    evolution = "「${pair.first}」的${pair.second.label}",
                    register = REGISTER_BRIDGED,
                    rarity = RARITY_BRIDGED
                )
            }
            .distinctBy { it.word }
            .filter { d -> d.word !in exclude }

    /** 词库未收录该词（或该词无音标）时不给出音标，只标注。 */
    private fun ipaOf(entry: VocabIndex.EnEntry?): String {
        val raw = entry?.ipa?.trim().orEmpty()
        return if (raw.isEmpty()) IPA_UNRECORDED else "/$raw/"
    }

    /**
     * 传统音标（1910s~1940s 保守 RP）：由**现代 IPA** 按 [ConservativeRp] 的音变规则**推导**得到。
     * 这是规则近似，**不是**词典里的真实音标；没有现代音标时如实标"无法推导"，不凭空编。
     */
    private fun tradIpaOf(word: String, modernIpa: String): String {
        val raw = modernIpa.trim()
        if (raw.isEmpty() || raw.contains(UNRECORDED)) return IPA_TRAD_MISSING
        return ConservativeRp.convert(word, raw)
    }

    private fun describeGloss(gloss: String): String =
        if (gloss.isBlank()) GLOSS_UNRECORDED else "词库释义：$gloss"

    /** 释义里出现的、CMN_PRESET 收录过的汉字，按预设顺序返回其音韵数据（同样经 [cmnDataOf] 补齐数据集字段）。 */
    private fun presetEntriesIn(gloss: String): List<CmnPhonologyData> =
        // CMN_PRESET 的 key 是汉字**字符串**，而 cmnDataOf 收的是 Char：
        // 取 key 的首字再补齐字段，与 map 的键一一对应。
        CMN_PRESET.keys.filter { gloss.contains(it) }
            .mapNotNull { key -> key.firstOrNull()?.let { cmnDataOf(it) } }

    /** 释义里出现的汉字，交给调用方筛选。 */
    private fun cjkCharsOf(text: String): List<Char> =
        text.toCharArray().filter { it in '\u4E00'..'\u9FA5' }.distinct()

    private fun bridgeEnglish(gloss: String, excludeKey: String, limit: Int): List<EnglishEtymWord> =
        bridgeKeys(gloss, excludeKey, limit, { VocabIndex.englishWordsForChar(it) }) {
            VocabIndex.english(it)?.gloss
        }.mapNotNull { key -> VocabIndex.english(key)?.let { englishWordOf(it) } }

    private fun bridgeRussian(gloss: String, limit: Int): RussianCluster? {
        val words = bridgeKeys(gloss, null, limit, { VocabIndex.russianWordsForChar(it) }) {
            VocabIndex.russianByKey(it)?.gloss
        }.mapNotNull { key -> VocabIndex.russianByKey(key)?.let { russianWordOf(it) } }
        if (words.isEmpty()) return null
        return RussianCluster("释义与英文词高度重合的俄语词", "俄语桥接", words)
    }

    /** 汉语单字 → 俄语：直接用反向索引给出的候选，不做释义重合度筛选。 */
    private fun russianWordsForChar(ch: Char, limit: Int): RussianCluster? {
        val words = VocabIndex.russianWordsForChar(ch).take(limit)
            .mapNotNull { key -> VocabIndex.russianByKey(key)?.let { russianWordOf(it) } }
        if (words.isEmpty()) return null
        return RussianCluster("释义含「$ch」的俄语词", ch.toString(), words)
    }

    /**
     * 取与 [gloss] 共有汉字数 >= 2、且共有数不低于较短一方释义字数一半的外文词。
     * 只说明两词释义用字高度重合，不代表义项等价。
     */
    private fun bridgeKeys(
        gloss: String,
        excludeKey: String?,
        limit: Int,
        candidatesForChar: (Char) -> List<String>,
        glossOf: (String) -> String?
    ): List<String> {
        if (gloss.isBlank() || limit <= 0) return emptyList()
        val sourceChars = cjkCharsOf(gloss).toHashSet()
        val hits = HashMap<String, Int>()
        for (ch in sourceChars) {
            for (key in candidatesForChar(ch)) {
                if (key == excludeKey) continue
                hits[key] = (hits[key] ?: 0) + 1
            }
        }
        return hits.entries
            .filter { entry ->
                val candidateChars = glossOf(entry.key)?.let { cjkCharsOf(it).size } ?: 0
                candidateChars > 0 && entry.value >= 2 &&
                    entry.value * 2 >= minOf(sourceChars.size, candidateChars)
            }
            .map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
            .take(limit)
            .map { it.first }
    }

    private fun englishWordOf(entry: VocabIndex.EnEntry): EnglishEtymWord = EnglishEtymWord(
        word = entry.word,
        ipaModern = ipaOf(entry),
        ipaTrad = if (entry.ipa.isBlank()) IPA_UNRECORDED else tradIpaOf(entry.word, ipaOf(entry)),
        origin = ORIGIN_UNRECORDED,
        evolution = describeGloss(entry.gloss),
        register = REGISTER_BRIDGED,
        rarity = RARITY_BRIDGED
    )

    private fun russianWordOf(entry: VocabIndex.RuEntry): RussianEtymWord = RussianEtymWord(
        word = entry.word,
        translit = TRANSLIT_UNRECORDED,
        ipa = IPA_UNRECORDED,
        origin = ORIGIN_UNRECORDED,
        evolution = describeGloss(entry.gloss),
        register = REGISTER_BRIDGED,
        rarity = RARITY_BRIDGED
    )

    /**
     * 俄语词/词组：中文释义查内置俄汉词表，汉俄对译查 [ZhRuIndex]，音标查 [RuPhonologyIndex]。
     *
     * 词组（含空格）同样"整段优先、逐词段回落"：先把整段当词查一次，
     * 未命中再按空格切成词段逐段查（而不是只取第一个 token）。
     *
     * **不再按词形猜语源** —— 旧版用 `щ/жд` 猜"原始斯拉夫/古教会斯拉夫"是编词源，
     * 与诚实铁律冲突；`VocabIndex`/`RuPhonologyIndex`/`ZhRuIndex` 都没有俄语词源字段，
     * 因此这里一律把 origin 标为"未收录"，不冒充有据。
     */
    private fun deriveRussianAlignment(word: String): CrossLinguisticAlignment {
        val whole = word.trim()
        val tokens = whole.split(Regex("\\s+"))
            .map { it.filter { c -> c.isLetter() } }
            .filter { it.isNotEmpty() }
            .distinct()
        val wholeEntry = VocabIndex.russian(whole)
        val resolved: List<Pair<String, VocabIndex.RuEntry>> = if (wholeEntry != null) {
            listOf(whole.filter { it.isLetter() } to wholeEntry)
        } else {
            tokens.mapNotNull { t -> VocabIndex.russian(t)?.let { t to it } }
        }
        val isPhrase = tokens.size > 1 || whole.contains(' ')

        if (resolved.isEmpty()) {
            val miss = RussianEtymWord(
                word = whole,
                translit = TRANSLIT_UNRECORDED,
                ipa = IPA_UNRECORDED,
                origin = ORIGIN_UNRECORDED,
                evolution = if (isPhrase) {
                    "俄汉词库与 Wiktionary 俄语条目均未收录该词组（${tokens.size} 个词段逐一查过）"
                } else {
                    "俄汉词库与 Wiktionary 俄语条目均未收录该词"
                },
                register = UNRECORDED,
                rarity = GLOSS_UNRECORDED
            )
            return CrossLinguisticAlignment(
                conceptId = "rus_${whole.filter { it.isLetter() }}",
                canonicalZh = whole,
                summary = "【$whole】本词库未收录该词，无释义可给出（不编造）。",
                cmnList = emptyList(),
                engCluster = null,
                rusCluster = RussianCluster("俄语词库未收录", whole, listOf(miss))
            )
        }

        // 每条命中的俄语词：真实 IPA（Wiktionary）+ 真实汉俄对译（han_ru.tsv）。
        val rusWords = resolved.map { pair -> russianResolvedWord(pair.first, pair.second) }
        val primaryGloss = resolved.first().second.gloss

        val sameGlossRu = bridgeKeys(primaryGloss, null, 2, { VocabIndex.russianWordsForChar(it) }) {
            VocabIndex.russianByKey(it)?.gloss
        }.mapNotNull { key -> VocabIndex.russianByKey(key)?.let { russianWordOf(it) } }

        val engWords = bridgeKeys(primaryGloss, null, 3, { VocabIndex.englishWordsForChar(it) }) {
            VocabIndex.english(it)?.gloss
        }.mapNotNull { key -> VocabIndex.english(key)?.let { englishWordOf(it) } }

        val ipaNote = if (RuPhonologyIndex.ipaOf(resolved.first().first) != null) {
            "音标来自 Wiktionary 俄语词条。"
        } else {
            "音标未收录。"
        }
        val summary = buildString {
            append("【$whole】")
            if (isPhrase) append("按 ${resolved.size}/${tokens.size} 个词段命中。")
            append("词库释义：$primaryGloss。")
            append(ipaNote)
            append("俄语词源：本词库未收录（不按词形猜测）。")
        }

        return CrossLinguisticAlignment(
            conceptId = "rus_${whole.filter { it.isLetter() }}",
            canonicalZh = whole,
            summary = summary,
            cmnList = presetEntriesIn(primaryGloss),
            engCluster = if (engWords.isEmpty()) null
            else EnglishCluster("释义含相同汉字的英文词", whole, engWords),
            rusCluster = RussianCluster(
                if (isPhrase) "俄语词段逐项（Wiktionary IPA + 汉俄对译）" else "俄语词条（Wiktionary IPA + 汉俄对译）",
                whole,
                rusWords + sameGlossRu
            )
        )
    }

    /** 单个俄语词 → 词条块：真实 IPA + 真实汉俄对译；都没有就如实标"未收录"。 */
    private fun russianResolvedWord(key: String, entry: VocabIndex.RuEntry): RussianEtymWord {
        val zh = ZhRuIndex.ruToZh(key)
        val gloss = buildString {
            append(entry.gloss)
            if (zh.isNotEmpty()) {
                if (isNotEmpty()) append("；")
                append("汉俄词典：").append(zh.take(8).joinToString("、"))
            }
            if (isEmpty()) append(GLOSS_UNRECORDED)
        }
        val realIpa = RuPhonologyIndex.ipaOf(key)
        return RussianEtymWord(
            word = entry.word.ifBlank { key },
            translit = TRANSLIT_UNRECORDED,
            ipa = realIpa?.let { "[$it]" } ?: IPA_UNRECORDED,
            origin = if (zh.isNotEmpty()) "汉俄对译（Wiktionary）" else ORIGIN_UNRECORDED,
            evolution = describeGloss(gloss),
            register = UNRECORDED,
            rarity = RARITY_RECORDED
        )
    }

    /**
     * 汉语：**多音节/多字输入按整个词处理**，而不是只用第一个字。
     */
    /** CC-CEDICT 义项里混着的语法标记与英语功能词；留着会把真正的义项挤出前三位。 */
    private val ENGLISH_STOPWORDS = setOf(
        "to", "of", "in", "on", "at", "by", "for", "with", "and", "or", "as", "it",
        "be", "is", "are", "was", "were", "the", "a", "an", "this", "that", "one",
        "cl", "classifier", "bound", "form", "variant", "see", "used", "old", "lit",
        "fig", "coll", "esp", "etc", "sb", "sth", "usu", "also", "same", "name",
        "abbr", "archaic", "dialect", "surname", "japanese", "korean", "un"
    )

    private fun deriveChineseAlignment(word: String): CrossLinguisticAlignment {
        val chars = hanCharsInOrder(word)
        val multiChar = chars.size > 1

        // ---------- 汉语区块：数据集是**单字级**（白-沙按字头），逐字给出 ----------
        val cmnList = chars.map { cmnDataOf(it) }

        // ---------- 汉 → 英 ----------
        // 英文侧**没有词级数据源**（CC-CEDICT 汉英表按字头）。因此：
        // 单字给该字的真实义项；多字如实标注"整词英文词级未收录，以下按单字拆解"，
        // 绝不把逐字结果伪装成整词结果。
        val engCluster: EnglishCluster? = if (!multiChar) {
            val ch = chars.firstOrNull() ?: word.first()
            val direct = cedictEnglishWords(ch)
            val engWords = if (direct.isNotEmpty()) direct
            else VocabIndex.englishWordsForChar(ch).take(3)
                .mapNotNull { key ->
                    VocabIndex.english(key)?.let { englishWordOf(it).copy(origin = "反查（释义含该字）") }
                }
            val derived = derivedFormsOf(engWords, engWords.map { it.word })
            if (engWords.isEmpty() && derived.isEmpty()) null
            else EnglishCluster(
                "释义含「$ch」的英文词（CC-CEDICT）及真实词形派生",
                ch.toString(),
                engWords + derived
            )
        } else {
            val perChar = chars.flatMap { cedictEnglishWords(it) }.distinctBy { it.word }.take(6)
            val derived = derivedFormsOf(perChar, perChar.map { it.word })
            if (perChar.isEmpty() && derived.isEmpty()) null
            else EnglishCluster(
                "整词「$word」英文词级未收录，以下按单字拆解",
                word,
                perChar + derived
            )
        }

        // ---------- 汉 → 俄（整词优先，逐字回落） ----------
        val rusCluster = buildZhRuCluster(word, chars)

        val recorded = chars.count { ch ->
            HanPhonologyIndex.of(ch)?.let {
                it.middleChineseBaxter != UNRECORDED || it.oldChineseBaxterSagart != UNRECORDED
            } == true
        }
        val summary = buildString {
            append("【$word】")
            if (multiChar) append("多音节词，共 ${chars.size} 字：${chars.joinToString("、")}。")
            append("音韵数据（白-沙 2015）收录 $recorded/${chars.size} 字。")
            append(
                when {
                    ZhRuIndex.zhToRu(word).isNotEmpty() -> "汉俄对译命中整词。"
                    multiChar -> "整词未收录汉俄对译，俄语按单字拆解。"
                    else -> "汉俄对译未收录该字。"
                }
            )
            if (multiChar) append("英文无词级数据源，按单字给出。")
        }

        return CrossLinguisticAlignment(
            conceptId = "cmn_$word",
            canonicalZh = word,
            summary = summary,
            cmnList = cmnList,
            engCluster = engCluster,
            rusCluster = rusCluster
        )
    }

    /** 查询串里的汉字，按出现顺序去重（多音节词的字序即读音顺序）。 */
    private fun hanCharsInOrder(text: String): List<Char> =
        text.toCharArray().filter { it in '\u4E00'..'\u9FA5' }.distinct()

    /**
     * CC-CEDICT 的真实英文义项（单字）→ 只保留同时存在于音标词表里的英文词，
     * 于是每条都带真实音标。反查索引（"释义里含这个字"）不退到这里，它只作兜底。
     */
    private fun cedictEnglishWords(ch: Char): List<EnglishEtymWord> =
        HanPhonologyIndex.englishSenses(ch)
            .asSequence()
            // 义项是 "god; deity" / "(bound form) peerless" 这类写法：
            // 先剥掉括号里的语法说明，再切成单个英文词，并剔掉 to/of/CL 一类功能词，
            // 否则前三个位置会被 "to"、"bound"、"form" 占掉，真正的义项反而看不到。
            .map { it.replace(Regex("\\([^)]*\\)"), " ") }
            .flatMap { it.split(Regex("[^A-Za-z-]+")).asSequence() }
            .map { it.trim().lowercase() }
            .filter { it.length in 2..24 && it !in ENGLISH_STOPWORDS }
            .mapNotNull { g -> VocabIndex.english(g)?.let { englishWordOf(it).copy(origin = "CC-CEDICT 义项") } }
            .distinctBy { it.word }
            .take(3)
            .toList()

    /**
     * 单个汉字的完整音韵数据。
     *
     * 五字预设只用来提供**人工核对过的廣韻事实**（声纽/韵部/等呼/反切）与释义；
     * 各家**拟音**一律改取可溯源的数据集（见 [HanHistoricalIndex]），
     * 不再沿用预设里那几条无出处的写法。数据集没有的字段一律"未收录"，
     * 不拿别人的系统冒充、也不猜。
     */
    private fun cmnDataOf(ch: Char): CmnPhonologyData {
        val han = HanPhonologyIndex.of(ch)
        val mc = HanHistoricalIndex.middleChinese(ch)
        val oc = HanHistoricalIndex.oldChinese(ch)
        val preset = CMN_PRESET[ch.toString()]
        val (ipaFromPinyin, toneFromPinyin) =
            han?.let { HanPhonologyIndex.pinyinToIpa(it.pinyin) } ?: (IPA_UNRECORDED to TONE_UNRECORDED)
        val engWords = cedictEnglishWords(ch)
        return CmnPhonologyData(
            char = ch.toString(),
            pinyin = preset?.pinyin ?: han?.pinyin ?: HanPhonologyIndex.cedictPinyin(ch) ?: PINYIN_UNRECORDED,
            ipa = preset?.ipa ?: ipaFromPinyin,
            tone = preset?.tone ?: toneFromPinyin,
            // 中古：广韵声纽/韵部/等呼/反切取预设（人工核对）或廣韻数据集；拟音取数据集。
            middleChinese = MiddleChineseData(
                initial = preset?.middleChinese?.initial ?: UNRECORDED,
                rhyme = preset?.middleChinese?.rhyme ?: (mc?.rhyme ?: UNRECORDED),
                grade = preset?.middleChinese?.grade ?: UNRECORDED,
                round = preset?.middleChinese?.round ?: UNRECORDED,
                wangLi = mc?.wangLi ?: UNRECORDED,
                zhengzhang = UNRECORDED, // 郑张中古拟音本库未收录（不拿上古值冒充）
                fanqie = preset?.middleChinese?.fanqie ?: (mc?.fanqie ?: UNRECORDED),
                baxterMiddleChinese = han?.middleChineseBaxter
                    ?: preset?.middleChinese?.baxterMiddleChinese ?: UNRECORDED,
                guangyunPosition = mc?.position ?: UNRECORDED,
                karlgren = mc?.karlgren ?: UNRECORDED,
                panWuyun = mc?.panWuyun ?: UNRECORDED,
                unt = mc?.unt ?: UNRECORDED,
                msoeg = mc?.msoeg ?: UNRECORDED
            ),
            // 上古：白-沙取 2015 词表；王力/李方桂/郑张/许思莱取 rephon 整理数据。
            oldChinese = OldChineseReconstruction(
                zhengzhang = oc?.zhengzhang ?: UNRECORDED,
                baxterSagart = han?.oldChineseBaxterSagart
                    ?: preset?.oldChinese?.baxterSagart ?: UNRECORDED,
                wangLi = oc?.wangLi ?: UNRECORDED,
                liFangkuei = oc?.liFangkuei ?: UNRECORDED,
                schuessler = oc?.schuessler ?: UNRECORDED
            ),
            meaning = preset?.meaning ?: when {
                han != null && han.gloss.isNotBlank() -> "Baxter-Sagart 词表释义：${han.gloss}"
                engWords.isNotEmpty() -> "词库中释义含该字的英文词：${engWords.joinToString("、") { it.word }}"
                else -> UNRECORDED
            }
        )
    }

    /**
     * 汉→俄：**整词优先**用真实对译（`han_ru.tsv`，词条级，Wiktionary 按义项配对），
     * 整词未收录再**逐字回落**，两种情况分别写进 concept（GUI 直接把它当分区副标题）。
     * 对译表也没有时才退到"释义含该字"的反查，同样标注清楚是反查而非词级对译。
     */
    private fun buildZhRuCluster(word: String, chars: List<Char>): RussianCluster? {
        val wholePairs = ZhRuIndex.zhToRu(word)
        if (wholePairs.isNotEmpty()) {
            return RussianCluster(
                "汉俄对译（整词，Wiktionary）",
                word,
                wholePairs.take(8).map { ru -> ruWordOfZh(ru, "对应简体汉语「$word」") }
            )
        }
        val perChar = chars.flatMap { ch -> ZhRuIndex.zhToRu(ch.toString()).map { ch to it } }
            .distinctBy { it.second }
            .take(8)
        if (perChar.isNotEmpty()) {
            return RussianCluster(
                "逐字拆解（整词「$word」未收录于汉俄对译）",
                word,
                perChar.map { p -> ruWordOfZh(p.second, "对应单字「${p.first}」") }
            )
        }
        val firstCh = chars.firstOrNull() ?: return null
        val fallback = russianWordsForChar(firstCh, limit = 3) ?: return null
        return RussianCluster(
            "释义含「$firstCh」的俄语词（反查，非词级对译）",
            word,
            fallback.words
        )
    }

    /** 一个汉→俄对译的俄语词条块：真实 IPA（Wiktionary）+ 该对译的对应关系。 */
    private fun ruWordOfZh(ru: String, note: String): RussianEtymWord = RussianEtymWord(
        word = ru,
        translit = TRANSLIT_UNRECORDED,
        ipa = RuPhonologyIndex.ipaOf(ru)?.let { "[$it]" } ?: IPA_UNRECORDED,
        origin = "汉俄对译（Wiktionary）",
        evolution = note,
        register = REGISTER_BRIDGED,
        rarity = RARITY_BRIDGED
    )

    private fun buildHolyAlignment(q: String): CrossLinguisticAlignment {
        return CrossLinguisticAlignment(
            conceptId = "holiness",
            canonicalZh = "神圣",
            summary = "超验神圣体验、敬畏与至洁之德（汉：上古至德通明；英：日耳曼充盈本根与拉丁法律奉献；俄：原始斯拉夫光明神圣与南斯拉夫教会斯拉夫雅言）",
            cmnList = listOfNotNull(CMN_PRESET["神"], CMN_PRESET["圣"]),
            engCluster = EnglishCluster(
                "神圣 / 圣洁 / 尊崇", "神圣",
                listOf(
                    EnglishEtymWord("holy", "/ˈhəʊli/", ConservativeRp.convert("holy", "/ˈhəʊli/"), "Proto-Germanic", "PIE *kailo- (完整) → Proto-Germanic *hailagaz", "核心基石", "常用 ★★★★★"),
                    EnglishEtymWord("sacred", "/ˈseɪkrɪd/", ConservativeRp.convert("sacred", "/ˈseɪkrɪd/"), "Old French via Latin", "Latin sacer (神圣) → Old French sacré", "庄严典礼", "常用 ★★★★☆"),
                    EnglishEtymWord("hallowed", "/ˈhæləʊd/", ConservativeRp.convert("hallowed", "/ˈhæləʊd/"), "Old English", "Old English hālgian (祝圣)", "庄严文学", "进阶 ★★★☆☆"),
                    EnglishEtymWord("sacrosanct", "/ˈsækrəʊsæŋkt/", ConservativeRp.convert("sacrosanct", "/ˈsækrəʊsæŋkt/"), "Classical Latin", "Latin sacro + sanctus (罗马保民官神圣不可侵犯权)", "极庄重法律", "高阶生僻 ★★☆☆☆"),
                    EnglishEtymWord("numinous", "/ˈnjuːmɪnəs/", ConservativeRp.convert("numinous", "/ˈnjuːmɪnəs/"), "Latin", "Latin numen (神之意志)；鲁道夫·奥托超验敬畏", "神学与哲学", "极生僻学术 ★☆☆☆☆"),
                    EnglishEtymWord("hieratic", "/ˌhaɪəˈrætɪk/", ConservativeRp.convert("hieratic", "/ˌhaɪəˈrætɪk/"), "Ancient Greek", "Greek hieratikós ← hierós (祭司崇圣)", "古代祭司专有", "极生僻文辞 ★☆☆☆☆"),
                    EnglishEtymWord("hierophantic", "/ˌhaɪərəʊˈfæntɪk/", ConservativeRp.convert("hierophantic", "/ˌhaɪərəʊˈfæntɪk/"), "Ancient Greek", "Greek hieros + phainein = 揭示至圣奥秘的大祭司", "极限神秘学", "罕见生僻 ☆☆☆☆☆")
                )
            ),
            rusCluster = RussianCluster(
                "神圣 / 圣洁", "神圣",
                listOf(
                    RussianEtymWord("святой", "svyatóy", "[svʲɪˈtoj]", "Proto-Slavic", "PIE *ḱwen- → Proto-Slavic *svętъ", "斯拉夫本土基石", "常用 ★★★★★"),
                    RussianEtymWord("священный", "svyashchénnyy", "[svʲɪˈɕːenːɨj]", "Old Church Slavonic", "古教会斯拉夫语礼拜祭祀神圣不可侵犯", "庄严崇高文体", "核心高频 ★★★★☆"),
                    RussianEtymWord("сакральный", "sakrál'nyy", "[sɐˈkralʲnɨj]", "Latin", "借自拉丁语 sacralis", "学术哲学", "进阶书面 ★★★☆☆")
                )
            )
        )
    }

    private fun buildLightAlignment(q: String): CrossLinguisticAlignment {
        return CrossLinguisticAlignment(
            conceptId = "luminosity",
            canonicalZh = "光明",
            summary = "破除黑暗之明辉与荣耀（汉：见母唐韵古音；英：印欧*lewk-根系演变；俄：斯拉夫*světъ光芒与尘世合一）",
            cmnList = listOfNotNull(CMN_PRESET["光"]),
            engCluster = EnglishCluster("光明 / 璀璨", "光", listOf(
                EnglishEtymWord("light", "/laɪt/", ConservativeRp.convert("light", "/laɪt/"), "Proto-Germanic", "PIE *lewk- → Old English lēoht", "本土基石", "常用 ★★★★★"),
                EnglishEtymWord("luminous", "/ˈluːmɪnəs/", ConservativeRp.convert("luminous", "/ˈluːmɪnəs/"), "Latin", "Latin lumen (光芒)", "高雅书面", "进阶中频 ★★★☆☆"),
                EnglishEtymWord("fulgent", "/ˈfʊldʒənt/", ConservativeRp.convert("fulgent", "/ˈfʊldʒənt/"), "Classical Latin", "Latin fulgere (闪电般闪烁发光)", "古典修辞", "极生僻典雅 ★☆☆☆☆")
            )),
            rusCluster = null
        )
    }

    private fun buildWisdomAlignment(q: String): CrossLinguisticAlignment {
        return CrossLinguisticAlignment(
            conceptId = "wisdom",
            canonicalZh = "智慧",
            summary = "鉴照洞察与博雅哲思（汉：知母寘韵三等；英：日耳曼见闻人生之智与拉丁学者博雅；俄：长者深思与索菲亚神圣智慧）",
            cmnList = listOfNotNull(CMN_PRESET["智"]),
            engCluster = EnglishCluster("智慧 / 睿智", "智", listOf(
                EnglishEtymWord("wise", "/waɪz/", ConservativeRp.convert("wise", "/waɪz/"), "Proto-Germanic", "PIE *weyd- (看见/知晓) → Old English wīs", "本土根词", "常用 ★★★★★"),
                EnglishEtymWord("sagacious", "/səˈɡeɪʃəs/", ConservativeRp.convert("sagacious", "/səˈɡeɪʃəs/"), "Classical Latin", "Latin sagax (嗅觉灵敏、洞察敏锐)", "政治洞见", "进阶高难 ★★☆☆☆"),
                EnglishEtymWord("sapient", "/ˈseɪpiənt/", ConservativeRp.convert("sapient", "/ˈseɪpiənt/"), "Latin", "Latin sapere (辨识通晓) → Homo sapiens", "人类学哲学", "高阶生僻 ★★☆☆☆")
            )),
            rusCluster = null
        )
    }
}
