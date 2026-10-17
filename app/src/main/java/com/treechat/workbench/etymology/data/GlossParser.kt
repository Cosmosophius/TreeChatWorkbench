package com.treechat.workbench.etymology.data

/**
 * 词典释义的**结构化解析器**：把一整串释义拆成「按词性分组 + 组内逐条义项」。
 *
 * 之所以需要它：词表里 `vocab2.tsv` 的中文释义形如 `v. 1. 抛弃，放弃 2. 离弃(家园)`，
 * `ecdict.core.tsv` 的英文 def 形如 `vt. 放弃, 抛弃；n. 放任, 狂热` —— 两者都**已经带了词性
 * 和义项边界**，只是原界面把它们当一个整串贴出来，读起来就是"一堆逗号不分主次"。
 *
 * 这里只做**切分**，不做任何改写、不补词、不猜词性：
 * - 词性标记 [POS_TOKENS] 全部来自对本仓库两张词表的真实统计（见下方注释），不是凭记忆写的；
 * - 每条义项的**文字原样保留**（包括 `[计]` 这类领域标签与括号内说明）；
 * - 解析不出词性时如实返回空词性组，界面显示为不带词性的普通义项。
 *
 * 例：
 * ```
 * parse("vt. 放弃, 抛弃；n. 放任")
 * // [PosGroup("vt.", ["放弃", "抛弃"]), PosGroup("n.", ["放任"])]
 *
 * parse("v. 1. 抛弃，放弃 2. 离弃(家园、船)")
 * // [PosGroup("v.", ["抛弃，放弃", "离弃(家园、船)"])]
 * ```
 */
object GlossParser {

    /**
     * 词性标记白名单 —— 由 `vocab2.tsv` + `ecdict.core.tsv` 全表统计得到，
     * 频次从 n(37740) / a(14381) / vt(5631) 一直到 phr(1)，按出现次数由多到少。
     * 少见标记（vbl 名动词、pref 前缀、comb 组合形式等）也一并保留，避免被误判成"无词性"。
     */
    private val POS_TOKENS = setOf(
        "n", "a", "vt", "v", "vi", "adv", "abbr", "adj", "prep", "interj",
        "pron", "num", "conj", "int", "pl", "ad", "vbl", "aux", "art",
        "pref", "suff", "comb", "pers", "na", "phr", "modal"
    )

    /** 词性 → 中文名，用于界面上的词性标签（"名/动/形/副…"）。 */
    private val POS_ZH = mapOf(
        "n" to "名", "v" to "动", "vt" to "动·及物", "vi" to "动·不及物",
        "a" to "形", "adj" to "形", "adv" to "副", "pron" to "代",
        "prep" to "介", "conj" to "连", "num" to "数", "art" to "冠",
        "interj" to "叹", "int" to "叹", "abbr" to "缩", "aux" to "助",
        "pl" to "复数", "ad" to "副", "vbl" to "名动", "phr" to "短语",
        "pref" to "前缀", "suff" to "后缀", "comb" to "组合", "pers" to "人名",
        "na" to "名形", "modal" to "情态"
    )

    /** 一段同词性的义项集合。[pos] 为空串表示原数据没写词性（如实为空，不猜）。 */
    data class PosGroup(
        /** 原始词性标记（保留 `vt.` 这类写法，界面自行决定显示成中文还是原样）。 */
        val pos: String,
        /** 该词性下的义项，顺序与原文一致，文字未经改写。 */
        val senses: List<String>
    )

    /** 词性标记 → 界面标签（如 `vt.`），空词性返回空串。 */
    fun displayLabel(pos: String): String = if (pos.isEmpty()) "" else "$pos."

    /** 词性标记 → 中文简称（`vt.` → "动·及物"），未知词性返回原标记。 */
    fun posZh(pos: String): String =
        POS_ZH[pos] ?: if (pos.isEmpty()) "" else pos

    /**
     * 解析一整串释义。返回空列表表示原文为空。
     *
     * 解析步骤（每步都只做切分，不改字）：
     * 1. 去掉 `词库释义：` 这类前缀（[EtymologyRepository] 会给词条拼上）；
     * 2. 按 `；`/`;` 切成段，**括号内的分号不切**；
     * 3. 每段取段首词性标记（须在白名单内），取不到就并入上一组或自成无词性组；
     * 4. 段内若已有 `1. 2. 3.` 显式编号则按编号切，否则按括号外的逗号切。
     */
    fun parse(raw: String): List<PosGroup> {
        val text = stripPrefix(raw)
        if (text.isBlank()) return emptyList()

        val groups = ArrayList<PosGroup>()

        for (segment in splitOutsideBrackets(text, '；', ';')) {
            val seg = segment.trim()
            if (seg.isEmpty()) continue

            val (pos, body) = splitLeadingPos(seg)
            val senses = splitSenses(body)
            if (senses.isEmpty()) continue

            val last = groups.lastOrNull()
            // 两种情况并入紧邻的上一组，其余各立一组（只跟上一组比，绝不回溯，
            // 否则 `n. a；v. b；n. c` 会被塞回第一段、打乱原文顺序）：
            //   1) 同词性连写；
            //   2) 省略词性的延续 —— 以汉字或 `[领域标签]` 开头的段落。
            val samePos = pos != null && last != null && last.pos == pos
            val continuation = pos == null && last != null && last.pos.let { p ->
                p.isEmpty() || seg.first().isCjkOrBracket()
            }

            when {
                samePos || continuation ->
                    groups[groups.lastIndex] = last.copy(senses = last.senses + senses)
                else -> groups.add(PosGroup(pos ?: "", senses))
            }
        }

        return groups
    }

    /** 该字符是否为汉字或领域标签的左方括号（用于判定"省略词性的延续段"）。 */
    private fun Char.isCjkOrBracket(): Boolean =
        this == '[' || this in '一'..'鿿' || this in '㐀'..'䶿'

    /** 去掉词条释义前的说明性前缀（只认这类固定写法，不会误伤正文）。 */
    private fun stripPrefix(raw: String): String {
        var s = raw.trim()
        for (p in listOf("词库释义：", "词库释义:", "释义：", "释义:")) {
            if (s.startsWith(p)) {
                s = s.substring(p.length).trim()
                break
            }
        }
        return s
    }

    /**
     * 取段首词性标记。返回 `null` 表示这段没有词性。
     * 只认形如 `vt.` / `v.` 的**整段标记**（后面必须紧跟内容或空白），
     * 因此 `apple.` 这类以点结尾的正文不会被误判成词性。
     */
    private fun splitLeadingPos(seg: String): Pair<String?, String> {
        val m = Regex("^([A-Za-z]{1,6})\\.(?=\\s|[^A-Za-z])").find(seg) ?: return null to seg
        val token = m.groupValues[1].lowercase()
        if (token !in POS_TOKENS) return null to seg
        return token to seg.substring(m.range.last + 1).trimStart()
    }

    /**
     * 切分一组内的义项。
     *
     * 优先级：**显式编号 > 括号外逗号 > 整段一条**。
     * 括号外的逗号才切 —— `(家园、船只、飞机等)` 里的顿号、`"he said, then left"` 里的
     * 逗号都不会把一条义项劈成两半。
     */
    private fun splitSenses(body: String): List<String> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()

        // 1. 显式编号：`1. ` `2. ` ……（至少两处才算，避免把 `3.14` 之类当编号）
        val numbered = splitByOrdinal(text)
        if (numbered != null) return numbered

        // 2. 括号外的逗号
        val byComma = splitOutsideBrackets(text, ',', '，', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (byComma.size > 1) return byComma

        // 3. 一条
        return listOf(text).filter { it.isNotEmpty() }
    }

    /** 找到 `1. 2. …` 这样的显式编号并按它切；编号不足两处返回 null。 */
    private fun splitByOrdinal(text: String): List<String>? {
        val ordinals = Regex("(?<![0-9.])([1-9][0-9]?)\\.\\s+").findAll(text).toList()
        if (ordinals.size < 2) return null
        // 第一个编号必须在开头附近，否则前面还有一截正文要单列
        val parts = ArrayList<String>(ordinals.size)
        val firstStart = ordinals.first().range.first
        if (firstStart > 0) {
            val head = text.substring(0, firstStart).trim()
            if (head.isNotEmpty()) parts.add(head)
        }
        for (i in ordinals.indices) {
            val start = ordinals[i].range.last + 1
            val end = if (i + 1 < ordinals.size) ordinals[i + 1].range.first else text.length
            val piece = text.substring(start, end).trim().trim('；', ';').trim()
            if (piece.isNotEmpty()) parts.add(piece)
        }
        return if (parts.size >= 2) parts else null
    }

    /**
     * 在**括号外**按给定分隔符切分（中英文括号、方括号都算一层深度）。
     * 深度不为 0 时该字符原样保留，保证括号内说明不被切开。
     */
    private fun splitOutsideBrackets(text: String, vararg seps: Char): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        for (ch in text) {
            when (ch) {
                '(', '（', '[' -> { depth++; sb.append(ch) }
                ')', '）', ']' -> { if (depth > 0) depth--; sb.append(ch) }
                else -> if (depth == 0 && ch in seps) {
                    parts.add(sb.toString()); sb.setLength(0)
                } else sb.append(ch)
            }
        }
        parts.add(sb.toString())
        return parts
    }
}
