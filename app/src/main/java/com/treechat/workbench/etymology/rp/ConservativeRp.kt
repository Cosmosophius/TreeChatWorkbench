package com.treechat.workbench.etymology.rp

/**
 * 1910s~1940s 保守 RP（Conservative RP / Daniel Jones 体系）的**规则式近似转写**。
 *
 * ## 先说结论（考据见 `etymology/RP_1910s_NOTES.md`）
 * 公开**没有**可直接下载、可直接查表的 Conservative RP 音标数据或现成转换器：
 * - Daniel Jones《EPD》1917 初版本身已入公有领域（Internet Archive 有扫描件），但**只有书页
 *   扫描/OCR**，没有经过校对的机器可读音标词典；OCR 对音标符号并不可靠。
 * - 现代 RP 的词典有开源版（如 eSpeak-ng 的 `en-GB-x-rp`、Read Lexicon），但它们描述的是
 *   **当代** RP，不是 1917~1940 的保守 RP，也没有一套"现代 RP → 保守 RP"的公开数据。
 *
 * ## 所以这里是什么
 * 不是"查表得到的真实音标"，而是把**音源音标**按一组有文献依据的音变规则推演到近似的
 * 保守 RP 记音。每条规则都在 [RULES] 里注明依据。代码与界面必须标注
 * **"规则推导的近似，不是词典数据"** —— 绝不把它混进"真实音标"字段冒充数据源。
 *
 * ## 2026-10 更正（重要）
 * 上一版直接拿词表第 2 列当"现代 IPA"来做替换，**实际上几乎全部规则都没生效**：
 * 那一列是 ECDICT 的**旧式混排记音**，不是 IPA —— 全表 46,578 条里有 **32,013 条用的是
 * 西里尔字母 `ә`(U+04D9)**、373 条用西里尔 `є`(U+0454)、44 条用希腊 `ε`(U+03B5)，
 * 长度用 ASCII `:` 而不是 `ː`。规则里写的是 IPA 码点（`ə` U+0259 / `ɛ` U+025B），
 * 于是 `ɜː→əː`、`ɛː→eə`、`aɪ→ai` 这些**在真实数据上一次都匹配不到**（实测 R3 触发 0 次、
 * R2 仅 17 次、R7 仅 6 次）。现在改为**先归一化字符，再逐条施加规则**。
 *
 * 输入：词表音标（旧式记音或 IPA 都可以，可带 `/.../` 或 `[...]`）。
 * 输出：同一包裹样式下的保守 RP 近似转写（输出统一为 IPA 用字）。
 */
object ConservativeRp {

    /** 一条可追溯的音变规则。 */
    data class Rule(
        val id: String,
        val change: String,
        val basis: String
    )

    /**
     * 规则清单（顺序即应用顺序；[apply] 按此逐条施加）。
     * 依据均为 RP 音系史的资料性描述，不涉及任何需要逐条抄录的词典数据。
     */
    val RULES: List<Rule> = listOf(
        Rule("R0", "旧式记音 → IPA 用字", "词表第 2 列是 ECDICT 旧式混排记音：西里尔 ә/є、希腊 ε、ASCII 冒号长度。不先归一化，下面所有规则都匹配不到（这是上一版失效的直接原因）"),
        Rule("R1", "/əu/、/əʊ/ → /ou/", "Jones 的《EPD》早期版把 GOAT 记作 ou；/əʊ/ 是后来的记法。真实词表里 GOAT 写作 әu，故两种写法都要收"),
        Rule("R2", "/ɜː/ → /əː/", "早期 RP 把 NURSE 记作 əː；ɜː 为后起（Gimson 之后）"),
        Rule("R3", "/ɛː/ → /eə/", "早期 RP 的 SQUARE 是 eə 双元音；ɛː 为后起的单元音化记法"),
        Rule("R4", "双元音尾 /ɪ/ → /i/", "早期记音用 [i] 作 FACE/PRICE/CHOICE 的尾滑音：aɪ→ai、ɔɪ→ɔi、eɪ→ei"),
        Rule("R5", "/ɒ/ → /ɔ/", "Jones 用 ɔ 记 LOT；ɒ 为后起记法"),
        Rule("R6", "CLOTH 类 /ɒ/ → /ɔː/", "CLOTH 词类（off/cloth/lost/cross/long/gone 等）在 RP 中读长 ɔː（lexical set）。**只覆盖一张部分列举表**，表外的词按一般 /ɒ/→/ɔ/ 处理"),
        Rule("R7", "词首 wh- 的 /w/、/hw/ → /ʍ/", "保守 RP 保留 which/witch 的对立（清音 ʍ）。注意真实词表把 which 写作 hwitʃ（不是 witʃ），故 hw 与 w 两种写法都要收"),
        Rule("R8", "HAPPY 尾 /i/ → /ɪ/", "**有争议**：Wells 把 /ɪ/ 称作保守形式，但 Jones 本人记的是 /i/。本实现默认施加（宁可偏保守），界面与文档都应把它标为争议项"),
        Rule(
            "R10", "FORCE 类 /ɔː/ → /ɔə/（新增）",
            "FORCE 的 /ɔə/ 来自「/ɔ/ + 后续 /r/ 脱落」，只出现在 r 不再拼读的位置；故判据为：拼写含 or/oor/our/oar/ore，且记音里有 ɒː/ɔː，且记音里**没有 r**。" +
                "thought/law/all 这类 THOUGHT 词不带 r 拼写，不会被误改；story/orange 这类 r 仍拼读的也不改"
        ),
        Rule(
            "R9", "/ʊə/(CURE) 与 /ɪə/(NEAR) 不做恢复（限制说明）",
            "CURE 在保守 RP 中尚未并入 /ɔː/，但若输入已是合并后的现代记法就无法从前端恢复，如实不做处理；" +
                "NEAR 在**本词表里已经写作 iə**，本身就是保守形式，无需改动"
        )
    )

    /**
     * CLOTH 词类（lexical set 的部分列举，用于 R6）。
     * 这不是穷举表，覆盖不到的词按一般 /ɒ/→/ɔ/ 处理（宁可少改，不臆造）。
     */
    private val CLOTH: Set<String> = setOf(
        "off", "offal", "cloth", "lost", "loss", "cross", "across", "soft", "soften",
        "often", "coffee", "cough", "trough", "broth", "froth", "moth", "boss", "cost",
        "frost", "dog", "long", "gone", "song", "wrong", "strong", "scone", "toff"
    )

    /** HAPPY 元音的词尾拼写（R8）。 */
    private val HAPPY_SPELLING = Regex("(y|ey|ee|ie|ies|ees|eys)$")

    /** 尾滑音的前接元音：这些后面的 /i/ 属于双元音尾，不当 HAPPY 元音处理。 */
    private const val OFFGLIDE_PRECEDERS = "aɔe"

    /**
     * FORCE 判据用的拼写片段（R10）。
     * 这些是 `/ɔː/ ← /ɔr/` 的常见载体；是否真的算 FORCE 还要看记音里有没有 r（见 [apply]）。
     */
    private val FORCE_SPELLING = Regex("oar|oor|our|ore|or")

    /** 记音里一旦出现这些，就说明 r 仍在拼读（不是 FORCE 的 /ɔə/）。 */
    private val RETAINED_R = charArrayOf('r', 'ɹ', 'ɾ', 'ɻ', 'ʀ')

    /**
     * 长后元音 `ɔː`/`ɒː` 的**紧邻下一音**是不是 r。
     *
     * 只查紧邻位置，不能扫全串：`record` / `report` / `restore` 这些词以 `re-` 开头，
     * 串里本来就有 r，但那个 r 与 FORCE 的长元音无关；扫全串会把它们全部误判成"r 还在"。
     * 真正的条件是"该 /r/ 已脱落"，也就是长元音后面**直接**跟辅音或词尾。
     */
    private fun longVowelFollowedByR(s: String): Boolean {
        for (i in 1 until s.length - 1) {
            if (s[i] == '\u02D0') {
                val prev = s[i - 1]
                if (prev == '\u0254' || prev == '\u0252') {
                    val next = s[i + 1]
                    if (next in RETAINED_R) return true
                }
            }
        }
        return false
    }

    /**
     * 旧式记音 → IPA 用字。**这是上一版失效的根因**：词表用西里尔/希腊字母冒充 IPA 字形，
     * 不归一化的话，凡是按 IPA 码点写的规则全部匹配不到。
     *
     * 同时把 ASCII `:` 统一成 `ː`，使后续规则只需处理一种长度写法。
     */
    private fun normalizeLegacy(s: String): String = s
        .replace('\u04D9', '\u0259') // ә (西里尔 schwa) → ə
        .replace('\u0454', '\u025B') // є (西里尔 ukrainian ie) → ɛ
        .replace('\u03B5', '\u025B') // ε (希腊 epsilon) → ɛ
        .replace('\u025D', '\u025C') // ɝ → ɜ（个别美式残留）
        .replace(':', '\u02D0')      // : → ː

    /**
     * 由**拼写**与来源音标推出保守 RP 近似转写。
     * @param spelling 英文原词（用于 wh-、HAPPY 尾、FORCE 这类"拼写驱动"的规则）
     * @param modernIpa 来源音标，可带 `/.../` 或 `[...]`，可为旧式记音
     */
    fun convert(spelling: String, modernIpa: String): String {
        val raw = modernIpa.trim()
        if (raw.isEmpty()) return raw

        val (prefix, suffix) = when {
            raw.length >= 2 && raw.startsWith("/") && raw.endsWith("/") -> "/" to "/"
            raw.length >= 2 && raw.startsWith("[") && raw.endsWith("]") -> "[" to "]"
            else -> "" to ""
        }
        var body = raw
        if (prefix.isNotEmpty()) body = body.substring(1, body.length - 1).trim()

        val sp = spelling.trim().lowercase()

        // 一条记音里可能并列多个变体（词表里用空格分隔，例如 "puә. pɒ:"）。
        // 旧实现把整串当一个整体替换，会把两个变体的音拼成第三种读法；
        // 这里按空白切成变体**逐个处理**，再用同样的分隔符拼回去。
        val converted = body.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { apply(it, sp) }

        return if (prefix.isNotEmpty()) prefix + converted + suffix else converted
    }

    /** 对**单个变体**施加全部规则。 */
    private fun apply(variant: String, sp: String): String {
        var s = normalizeLegacy(variant)

        // R10 FORCE：必须先于 R5/R6 —— R5 会把 ɒ 变成 ɔ，之后就分不清原来的 ɒː 了。
        // 判据：拼写含 or/oor/our/oar/ore，记音里有 ɔː/ɒː，且**该长元音紧邻的下一个音不是 r**。
        val hasForceSpelling = FORCE_SPELLING.containsMatchIn(sp)
        val hasLongBackVowel =
            s.contains("\u0254\u02D0") || s.contains("\u0252\u02D0")
        if (hasForceSpelling && hasLongBackVowel && !longVowelFollowedByR(s)) {
            s = s.replace("\u0254\u02D0", "\u0254\u0259") // ɔː → ɔə
            s = s.replace("\u0252\u02D0", "\u0254\u0259") // ɒː → ɔə
        }

        // R2 NURSE
        s = s.replace("ɜː", "əː")
        // R1 GOAT（旧式记音写作 әu，归一化后是 əu；IPA 写法是 əʊ）
        s = s.replace("əʊ", "ou").replace("əu", "ou")
        // R3 SQUARE
        s = s.replace("ɛː", "eə")
        // R4 双元音尾滑音
        s = s.replace("aɪ", "ai").replace("ɔɪ", "ɔi").replace("eɪ", "ei")
        // R5 / R6 LOT 与 CLOTH
        s = if (sp in CLOTH) s.replace("ɒ", "ɔː") else s.replace("ɒ", "ɔ")
        // R7 词首 wh- 的 ʍ（真实词表写 hw，IPA 写法是 w，两种都收）
        if (sp.startsWith("wh")) {
            s = when {
                s.startsWith("hw") -> "ʍ" + s.substring(2)
                s.startsWith("w") -> "ʍ" + s.substring(1)
                else -> s
            }
        }
        // R8 HAPPY 尾元音
        if (HAPPY_SPELLING.containsMatchIn(sp) && s.endsWith("i") && s.length >= 2) {
            val before = s[s.length - 2]
            if (before !in OFFGLIDE_PRECEDERS) s = s.dropLast(1) + "ɪ"
        }

        return s
    }
}
