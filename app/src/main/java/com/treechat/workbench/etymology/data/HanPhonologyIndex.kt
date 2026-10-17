package com.treechat.workbench.etymology.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 汉语历时音韵索引：拼音（含调）、中古音、上古音。
 *
 * 数据来源只有一份：`assets/han_phonology.tsv`，由 **Baxter-Sagart《Old Chinese:
 * A New Reconstruction》2015 词表**（`BaxterSagartOC2015-10-13.tsv`，4,960 条）整理而来。
 * 列序：`字 \t 拼音 \t 中古(白-沙转写) \t 上古(白-沙拟音) \t 英文释义`。
 *
 * 归属必须说清楚：中古音与上古音**都是 Baxter-Sagart 的转写/拟音**，
 * 不是王力、郑张尚芳或李方桂的系统 —— 因此取用时写进模型的 `baxterMiddleChinese`
 * 与 `baxterSagart` 两个字段，绝不塞进别的学者名下。
 * 声纽/韵部/等呼/反切（广韵那一套）本数据集里没有，一律如实标"未收录"。
 *
 * 普通话 IPA 不是查来的，而是**按拼音规则转写**得到：声母表 + 韵母表 + 五度调值，
 * 并对 zhi/chi/shi/ri/zi/ci/si 与 j/q/x + ü 等拼写特例单独处理。
 */
object HanPhonologyIndex {

    private const val TAG = "HanPhonologyIndex"
    private const val ASSET = "han_phonology.tsv"

    /** CC-CEDICT 汉英词典（单字条），列序：`字 \t 拼音 \t 英文义项(/分隔)`。 */
    private const val ASSET_EN = "han_en.tsv"

    const val UNRECORDED = "未收录"

    data class HanEntry(
        val char: String,
        val pinyin: String,
        val middleChineseBaxter: String,
        val oldChineseBaxterSagart: String,
        val gloss: String
    )

    @Volatile
    private var map: Map<String, HanEntry> = emptyMap()

    /** 字 -> (拼音, 英文义项列表)。来源 CC-CEDICT，是**真实词典义项**，不是反查猜测。 */
    @Volatile
    private var enMap: Map<String, Pair<String, List<String>>> = emptyMap()

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
            val m = HashMap<String, HanEntry>(4_096)
            runCatching {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 5) continue
                        val ch = col[0].trim()
                        if (ch.length != 1) continue
                        val py = col[1].trim()
                        if (py.isEmpty()) continue
                        m[ch] = HanEntry(
                            char = ch,
                            pinyin = py,
                            middleChineseBaxter = col[2].trim().ifEmpty { UNRECORDED },
                            oldChineseBaxterSagart = col[3].trim().ifEmpty { UNRECORDED },
                            gloss = col[4].trim()
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET 失败，汉字音韵将全部落空：$it") }
            val en = HashMap<String, Pair<String, List<String>>>(10_240)
            runCatching {
                context.assets.open(ASSET_EN).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 3) continue
                        val ch = col[0].trim()
                        if (ch.length != 1) continue
                        val senses = col[2].split('/').map { it.trim() }.filter { it.isNotEmpty() }
                        if (senses.isEmpty()) continue
                        en[ch] = col[1].trim() to senses
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET_EN 失败，汉英义项将落空：$it") }
            map = m
            enMap = en
            isReady = true
            Log.i(TAG, "汉语音韵索引就绪：${m.size} 字（含 CC-CEDICT 汉英单字 ${en.size} 条）")
        }
    }

    fun of(ch: Char): HanEntry? = map[ch.toString()]

    /** CC-CEDICT 的真实英文义项（如 水 -> ["water", ...]）。 */
    fun englishSenses(ch: Char): List<String> = enMap[ch.toString()]?.second.orEmpty()

    /** CC-CEDICT 的拼音（Baxter-Sagart 只覆盖 3955 字，这里能补更多字）。 */
    fun cedictPinyin(ch: Char): String? = enMap[ch.toString()]?.first?.takeIf { it.isNotEmpty() }

    // ========================================================================
    // 拼音 → 普通话 IPA（规则转写，非查表）
    // ========================================================================

    private val INITIALS: List<Pair<String, String>> = listOf(
        "zh" to "tʂ", "ch" to "tʂʰ", "sh" to "ʂ",
        "b" to "p", "p" to "pʰ", "m" to "m", "f" to "f",
        "d" to "t", "t" to "tʰ", "n" to "n", "l" to "l",
        "g" to "k", "k" to "kʰ", "h" to "x",
        "j" to "tɕ", "q" to "tɕʰ", "x" to "ɕ",
        "r" to "ʐ", "z" to "ts", "c" to "tsʰ", "s" to "s"
    )

    /** 韵母表（含拼写归一后的形式）。 */
    private val FINALS: Map<String, String> = mapOf(
        "a" to "a", "o" to "o", "e" to "ɤ", "i" to "i", "u" to "u", "ü" to "y",
        "ai" to "aɪ", "ei" to "eɪ", "ao" to "ɑʊ", "ou" to "oʊ",
        "an" to "an", "en" to "ən", "ang" to "ɑŋ", "eng" to "ɤŋ", "ong" to "ʊŋ",
        "er" to "aɚ",
        "ia" to "ia", "ie" to "iɛ", "iao" to "iɑʊ", "iou" to "ioʊ",
        "ian" to "iɛn", "in" to "in", "iang" to "iɑŋ", "ing" to "iŋ", "iong" to "yʊŋ",
        "ua" to "ua", "uo" to "uo", "uai" to "uaɪ", "uei" to "ueɪ",
        "uan" to "uan", "uen" to "uən", "uang" to "uɑŋ", "ueng" to "uɤŋ",
        "üe" to "yɛ", "üan" to "yɛn", "ün" to "yn"
    )

    private val TONES: Map<Int, Pair<String, String>> = mapOf(
        1 to ("55" to "阴平 (55)"),
        2 to ("35" to "阳平 (35)"),
        3 to ("214" to "上声 (214)"),
        4 to ("51" to "去声 (51)"),
        0 to ("2" to "轻声 (2)")
    )

    /** 带调符号 → 调类。用于从 Baxter-Sagart 的 `py` 列还原声调。 */
    private val TONE_MARKS: Map<Char, Int> = mapOf(
        'ā' to 1, 'á' to 2, 'ǎ' to 3, 'à' to 4,
        'ē' to 1, 'é' to 2, 'ě' to 3, 'è' to 4,
        'ī' to 1, 'í' to 2, 'ǐ' to 3, 'ì' to 4,
        'ō' to 1, 'ó' to 2, 'ǒ' to 3, 'ò' to 4,
        'ū' to 1, 'ú' to 2, 'ǔ' to 3, 'ù' to 4,
        'ǖ' to 1, 'ǘ' to 2, 'ǚ' to 3, 'ǜ' to 4
    )

    /** 去掉声调符号并返回 (无声调拼音, 调类)。 */
    fun stripTone(pinyin: String): Pair<String, Int> {
        if (pinyin.isEmpty()) return "" to 0
        val sb = StringBuilder(pinyin.length)
        var tone = 0
        for (ch in pinyin) {
            val t = TONE_MARKS[ch]
            if (t != null) {
                tone = t
                sb.append(
                    when (ch) {
                        'ā', 'á', 'ǎ', 'à' -> 'a'
                        'ē', 'é', 'ě', 'è' -> 'e'
                        'ī', 'í', 'ǐ', 'ì' -> 'i'
                        'ō', 'ó', 'ǒ', 'ò' -> 'o'
                        'ū', 'ú', 'ǔ', 'ù' -> 'u'
                        else -> 'ü'
                    }
                )
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().lowercase() to tone
    }

    /**
     * 把一条带调拼音转成严式 IPA（含五度调值）。
     * @return (IPA, 调类名称)；无法转写时返回 ("未收录", "未收录")
     */
    fun pinyinToIpa(pinyin: String): Pair<String, String> {
        if (pinyin.isBlank()) return UNRECORDED to UNRECORDED
        val (base0, tone) = stripTone(pinyin)
        var base = base0.replace("v", "ü")
        if (base.isEmpty()) return UNRECORDED to UNRECORDED

        // —— 拼写归一（拼音正交法的省略写法在下表里补回本形）——
        // j/q/x/y + u 实为 ü
        if (base.startsWith("ju") || base.startsWith("qu") || base.startsWith("xu") ||
            base.startsWith("yu")
        ) {
            base = base.substring(0, 1) + "ü" + base.substring(2)
        }
        base = when {
            base.startsWith("yi") -> "i" + base.substring(2)
            base.startsWith("ya") -> "ia" + base.substring(2)
            base.startsWith("ye") -> "ie" + base.substring(2)
            base.startsWith("yo") -> "io" + base.substring(2)
            base.startsWith("yu") -> "ü" + base.substring(2)
            base.startsWith("wu") -> "u" + base.substring(2)
            base.startsWith("wa") -> "ua" + base.substring(2)
            base.startsWith("wo") -> "uo" + base.substring(2)
            base.startsWith("we") -> "ue" + base.substring(2)
            else -> base
        }
        base = base
            .replace("iou", "iou").replace("iu", "iou")
            .replace("uei", "uei").replace("ui", "uei")
            .replace("uen", "uen").replace("un", "uen")
            .replace("iong", "iong")
            .replace("ong", "ʊŋ")

        // —— 特殊韵：舌尖元音 ——
        val apical = mapOf(
            "zhi" to "ʈʂɻ̩", "chi" to "tʂʰɻ̩", "shi" to "ʂɻ̩", "ri" to "ɻ̩",
            "zi" to "tsɹ̩", "ci" to "tsʰɹ̩", "si" to "sɹ̩"
        )
        val (tonal, toneName) = TONES[tone] ?: ("" to UNRECORDED)

        apical[base]?.let { return "[$it$tonal]" to toneName }

        // —— 声母 + 韵母 ——
        val initial = INITIALS.firstOrNull { base.startsWith(it.first) }
        val initialIpa = initial?.second ?: ""
        val finalPart = if (initial != null) base.substring(initial.first.length) else base
        val finalIpa = FINALS[finalPart] ?: FINALS[finalPart.replace("ü", "u")] ?: ""

        if (finalIpa.isEmpty()) return UNRECORDED to UNRECORDED
        return "[$initialIpa$finalIpa$tonal]" to toneName
    }
}
