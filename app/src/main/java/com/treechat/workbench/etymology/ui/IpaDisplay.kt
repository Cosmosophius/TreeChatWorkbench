package com.treechat.workbench.etymology.ui

/**
 * 音标**展示层**归一化（不改任何数据文件，只在画到屏幕之前换字）。
 *
 * 背景（实测）：内置英汉词表的第 2 列沿用 ECDICT 的**旧式混排记音**，不是规范 IPA ——
 * 全表 46,578 条有音标的词条里，
 * - 32,013 条（68.7%）用**西里尔** `ә`（U+04D9）代替 IPA 的 `ə`（U+0259）；
 * - 373 条用西里尔 `є`（U+0454）、44 条用希腊 `ε`（U+03B5），都该是 `ɛ`（U+025B）；
 * - 长度标记写 ASCII `:` 而不是 `ː`（U+02D0）；
 * - 10.4%（4,846 条）在一条记录里并列了多个读音，用空格或 `. ` 分隔（如 `puә. pɒ:`）。
 *
 * 于是用户在词典里看到 `'hәuli` 这种"西里尔 schwa"就像乱码。这里只做**字符替换**与
 * **多变体拆分**两件事：[normalize] 换字，[variants] 切分。原始字符的语义不做任何改写，
 * 也不把旧式记音"翻译"成别的体系。
 *
 * 纪律：本文件**只**用于音标字段（释义、词源叙述等其它字段不要走这里）。
 */
object IpaDisplay {

    /** 逐字符替换表：旧式/异体记音 → 规范 IPA 字符。 */
    private val CHAR_FIX: Map<Char, Char> = mapOf(
        '\u04D9' to '\u0259', // ә (西里尔 schwa) → ə
        '\u0454' to '\u025B', // є (西里尔 ye)    → ɛ
        '\u03B5' to '\u025B', // ε (希腊 epsilon)  → ɛ
        '\u025D' to '\u025C', // ɝ (卷舌元音)      → ɜ
        ':' to '\u02D0'       // ASCII 冒号 → ː 长度标记
    )

    /**
     * 把音标串里的旧式/异体字符换成规范 IPA 字符。
     * 只做字符级替换，不改长度、不删标记，空串原样返回。
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        var needsWork = false
        for (ch in raw) {
            if (CHAR_FIX.containsKey(ch)) {
                needsWork = true
                break
            }
        }
        if (!needsWork) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) sb.append(CHAR_FIX[ch] ?: ch)
        return sb.toString()
    }

    /**
     * 把一个音标字段拆成若干**变体**（每个变体都已 [normalize]）。
     *
     * 旧式记录会用空白把并列的多个读音连在一起（`puә. pɒ:`），直接贴出来会被当成一个
     * 乱码长串；这里按空白切成多个变体，交由界面逐条展示。
     * 空白串返回空表（调用方按"未收录"处理）。
     */
    fun variants(raw: String): List<String> =
        raw.trim()
            .split(Regex("\\s+"))
            .asSequence()
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    /**
     * 归一化后再拼回一个字符串（字间不加空格，保持原样）。
     * 界面需要"一行显示一个字段"时用它；需要多变体分行时用 [variants]。
     */
    fun single(raw: String): String = normalize(raw.trim())
}
