package com.treechat.workbench.etymology.engine

import java.text.Normalizer

/**
 * 历史 IPA / 拟音串 → 发音参数 的映射表与切分器。
 *
 * ## 这份表要解决什么
 * 中古音（廣韻诸家拟音、白-沙 Baxter 转写）与上古音（王力/李方桂/郑张尚芳/许思莱、
 * 白-沙 Baxter-Sagart 拟音）里出现的音标**远不止现代汉语拼音那一小撮**：送气/浊送气塞音、
 * 卷舌、硬腭、鼻音韵尾、以及大量附加符。旧合成器只认几个元音，遇到不认识的字符就静默跳过，
 * 于是输出空或极短的声音 —— 用户听到的"无音频"就是这个。
 *
 * ## 设计原则
 * 1. 覆盖实际会出现的一切符号：IPA 字母 + 附加符 + **Baxter 传统罗马字**（`trhjemX`、`yowng`、
 *    `sju`、`mak` 这种）——因为运行时真正喂进来的是 Baxter 记法，不是纯 IPA。
 * 2. **未知符号绝不静默**：走最近邻回退（默认央元音 / 通用响音），并把原始符号与回退去向
 *    记录进 [ParseResult.unknownSymbols]，界面以后可展示。空串之外的任何输入都能出声。
 * 3. 中文声调标记（五度符号、汉语调符 `꜀꜂꜄꜆꜊`、Baxter 的 `H`/`X`、塞音韵尾）单独解析，
 *    映射到可听的基频轮廓；**识别不到就按平调处理并计入未识别**（不假装做对了声调）。
 *
 * 参数含义：F1/F2/F3 为前三个共振峰中心频率 (Hz)；amp 为相对幅度；durMs 为基准时长。
 * 这些数值是发声学量级的近似（Peterson-Barney 元音区间 + 各家常表辅音 locus 的近似），
 * **只用于"能出声、随音段变化"，不代表拟音本身的准确性**（准确性由拟音数据决定）。
 */
object HistoricalIpaPhoneMap {

    enum class Kind { VOWEL, STOP, AFFRICATE, FRICATIVE, NASAL, LATERAL, APPROXIMANT, TRILL, TAP }

    /**
     * 一个音段的发音参数。
     * @param symbol 原始书写形式（可能是多字符，如 `tʂ`）
     * @param kind 发音方式
     * @param voiced 是否浊音（带声带振动）
     * @param f1/f2/f3 共振峰目标 (Hz)
     * @param amp 相对幅度 0..1
     * @param durMs 基准时长（毫秒，rate 之前）
     * @param aspirated 送气（塞音/塞擦音除阻后有噪声）
     * @param breathy 气声（浊送气 ʱ）
     * @param noise 是否带持续噪声源（擦音）
     * @param burst 是否带除阻爆破（塞音）
     * @param noiseCenterHz 噪声带通中心（擦音的关键区分量）
     * @param noiseQ 噪声带通 Q
     * @param checked 促声/入声标记（塞音韵尾 → 明显短促）
     * @param syllabic 是否自成音节（成音节辅音 / 舌尖元音）
     * @param trill 是否颤音（做幅度调制）
     * @param creaky 吱嘎声（紧喉）
     */
    data class Phone(
        val symbol: String,
        val kind: Kind,
        val voiced: Boolean,
        val f1: Double,
        val f2: Double,
        val f3: Double,
        val amp: Double,
        val durMs: Int,
        val aspirated: Boolean = false,
        val breathy: Boolean = false,
        val noise: Boolean = false,
        val burst: Boolean = false,
        val noiseCenterHz: Double = 2000.0,
        val noiseQ: Double = 3.0,
        val checked: Boolean = false,
        val syllabic: Boolean = false,
        val trill: Boolean = false,
        val creaky: Boolean = false
    )

    // ========================================================================
    // 元音（F1/F2/F3 近似，Peterson-Barney / Klatt 区间 + 汉语元音的常见取值）
    // ========================================================================
    private fun v(sym: String, f1: Double, f2: Double, f3: Double, amp: Double = 1.0, durMs: Int = 150): Phone =
        Phone(sym, Kind.VOWEL, true, f1, f2, f3, amp, durMs)

    // ========================================================================
    // 辅音辅助构造
    // ========================================================================
    private fun stop(sym: String, voiced: Boolean, f1: Double, f2: Double, f3: Double, durMs: Int = 70): Phone =
        Phone(sym, Kind.STOP, voiced, f1, f2, f3, if (voiced) 0.55 else 0.35, durMs, burst = true)

    private fun affricate(sym: String, voiced: Boolean, f2: Double, noiseHz: Double, durMs: Int = 95): Phone =
        Phone(sym, Kind.AFFRICATE, voiced, 350.0, f2, 2600.0, if (voiced) 0.5 else 0.4, durMs,
            noise = true, burst = true, noiseCenterHz = noiseHz, noiseQ = 3.5)

    private fun fric(sym: String, voiced: Boolean, noiseHz: Double, f2: Double = 1400.0, durMs: Int = 95): Phone =
        Phone(sym, Kind.FRICATIVE, voiced, 400.0, f2, 2500.0, if (voiced) 0.4 else 0.35, durMs,
            noise = true, noiseCenterHz = noiseHz, noiseQ = 4.0)

    private fun nasal(sym: String, f2: Double, f3: Double = 2400.0, durMs: Int = 85): Phone =
        Phone(sym, Kind.NASAL, true, 250.0, f2, f3, 0.5, durMs)

    private fun lateral(sym: String, f2: Double, f3: Double, durMs: Int = 80): Phone =
        Phone(sym, Kind.LATERAL, true, 350.0, f2, f3, 0.65, durMs)

    private fun appr(sym: String, f1: Double, f2: Double, f3: Double, durMs: Int = 70): Phone =
        Phone(sym, Kind.APPROXIMANT, true, f1, f2, f3, 0.6, durMs)

    private fun trill(sym: String, f1: Double, f2: Double, f3: Double, durMs: Int = 85): Phone =
        Phone(sym, Kind.TRILL, true, f1, f2, f3, 0.6, durMs, trill = true)

    private fun tap(sym: String, f2: Double, durMs: Int = 45): Phone =
        Phone(sym, Kind.TAP, true, 400.0, f2, 2500.0, 0.5, durMs)

    // ========================================================================
    // 单字符基础表（IPA 字母 + Baxter 罗马字）
    // 键是"基础符号"，附加符由 [applyModifiers] 处理。
    // ========================================================================
    private val BASE: Map<String, Phone> = buildMap {
        // ---- 元音 ----
        put("a", v("a", 750.0, 1300.0, 2500.0))
        put("ɑ", v("ɑ", 730.0, 1090.0, 2440.0))
        put("ɒ", v("ɒ", 700.0, 1050.0, 2400.0))
        put("æ", v("æ", 660.0, 1720.0, 2410.0))
        put("ɐ", v("ɐ", 700.0, 1300.0, 2500.0))
        put("ʌ", v("ʌ", 640.0, 1190.0, 2390.0))
        put("e", v("e", 400.0, 2000.0, 2550.0))
        put("ɛ", v("ɛ", 530.0, 1700.0, 2450.0))
        put("ø", v("ø", 400.0, 1750.0, 2200.0))
        put("ɘ", v("ɘ", 400.0, 1500.0, 2500.0))
        put("ɤ", v("ɤ", 420.0, 1250.0, 2300.0))
        put("ə", v("ə", 500.0, 1500.0, 2500.0, 0.9))
        put("ɵ", v("ɵ", 400.0, 1450.0, 2200.0))
        put("i", v("i", 280.0, 2300.0, 3000.0, 1.0, 140))
        put("ɪ", v("ɪ", 380.0, 2000.0, 2600.0, 0.9, 120))
        put("ɨ", v("ɨ", 330.0, 1500.0, 2300.0))
        put("ɯ", v("ɯ", 320.0, 1300.0, 2250.0))
        put("u", v("u", 310.0, 870.0, 2240.0))
        put("ʊ", v("ʊ", 400.0, 1000.0, 2400.0, 0.9, 120))
        put("ʉ", v("ʉ", 320.0, 1150.0, 2150.0))
        put("o", v("o", 400.0, 850.0, 2400.0))
        put("ɔ", v("ɔ", 500.0, 800.0, 2400.0))
        put("y", v("y", 280.0, 1900.0, 2400.0))
        put("ʏ", v("ʏ", 380.0, 1800.0, 2400.0, 0.9, 120))
        put("ɜ", v("ɜ", 500.0, 1400.0, 2400.0))
        put("ɞ", v("ɞ", 480.0, 1400.0, 2300.0))
        put("ɶ", v("ɶ", 700.0, 1600.0, 2400.0))
        put("ᴀ", v("ᴀ", 720.0, 1300.0, 2500.0))
        put("ᴇ", v("ᴇ", 480.0, 1800.0, 2500.0, 0.9))
        put("ᵚ", v("ᵚ", 350.0, 1250.0, 2250.0, 0.7, 110)) // Baxter 的 ɨ/ɯ 类滑音
        put("ᶤ", v("ᶤ", 330.0, 1500.0, 2300.0, 0.7, 110)) // 修饰小 ɨ
        // 舌尖元音（成音节擦音/近音）
        put("ɿ", v("ɿ", 400.0, 1400.0, 2400.0, 0.7, 130))
        put("ʅ", v("ʅ", 400.0, 1500.0, 2450.0, 0.7, 130))
        put("ɚ", v("ɚ", 500.0, 1400.0, 1700.0, 0.85, 140))
        // 鼻化单元音（Baxter 有用 ɑ̃ 一律由附加符处理，这里给 â 等带附加的常见键）
        put("â", v("â", 730.0, 1200.0, 2400.0))
        put("ā", v("ā", 750.0, 1300.0, 2500.0))
        put("ē", v("ē", 400.0, 2000.0, 2550.0))
        put("ī", v("ī", 280.0, 2300.0, 3000.0))
        put("ō", v("ō", 400.0, 850.0, 2400.0))
        put("ū", v("ū", 310.0, 870.0, 2240.0))
        put("ǐ", v("ǐ", 280.0, 2300.0, 3000.0))
        put("í", v("í", 280.0, 2300.0, 3000.0))

        // ---- 塞音 ----
        put("p", stop("p", false, 400.0, 700.0, 2200.0))
        put("b", stop("b", true, 400.0, 700.0, 2200.0, 60))
        put("t", stop("t", false, 400.0, 1700.0, 2600.0))
        put("d", stop("d", true, 400.0, 1700.0, 2600.0, 60))
        put("k", stop("k", false, 400.0, 1800.0, 2400.0))
        put("ɡ", stop("ɡ", true, 400.0, 1800.0, 2400.0, 60))
        put("g", stop("g", true, 400.0, 1800.0, 2400.0, 60)) // ASCII g
        put("c", stop("c", false, 350.0, 2300.0, 2900.0, 65)) // 硬腭塞音
        put("ɟ", stop("ɟ", true, 350.0, 2300.0, 2900.0, 60))
        put("q", stop("q", false, 400.0, 1100.0, 2000.0, 70)) // 小舌塞音
        put("ɢ", stop("ɢ", true, 400.0, 1100.0, 2000.0, 65))
        put("ʈ", stop("ʈ", false, 400.0, 1500.0, 2200.0)) // 卷舌塞音
        put("ɖ", stop("ɖ", true, 400.0, 1500.0, 2200.0, 60))
        put("ȶ", stop("ȶ", false, 350.0, 2100.0, 2800.0, 65)) // Baxter 的 /c/（龈腭塞音）
        put("ȡ", stop("ȡ", true, 350.0, 2100.0, 2800.0, 60))
        put("ʔ", Phone("ʔ", Kind.STOP, false, 400.0, 1200.0, 2200.0, 0.0, 55, burst = false))
        // 撇号：白-沙/廣韻记影母为 [']（喉塞音），必须发声而非被当分隔符丢掉
        put("'", Phone("'", Kind.STOP, false, 400.0, 1200.0, 2200.0, 0.0, 55, burst = false))
        put("ʼ", stop("ʼ", false, 400.0, 1200.0, 2200.0, 55))
        put("’", Phone("’", Kind.STOP, false, 400.0, 1200.0, 2200.0, 0.0, 55, burst = false))
        put("‘", Phone("‘", Kind.STOP, false, 400.0, 1200.0, 2200.0, 0.0, 55, burst = false))
        put("ʡ", stop("ʡ", false, 400.0, 1150.0, 2000.0, 70))

        // ---- 塞擦音 ----
        put("ts", affricate("ts", false, 1700.0, 6500.0))
        put("dz", affricate("dz", true, 1700.0, 6500.0))
        put("tʂ", affricate("tʂ", false, 1500.0, 3200.0))
        put("dʐ", affricate("dʐ", true, 1500.0, 3200.0))
        put("tɕ", affricate("tɕ", false, 2100.0, 4500.0))
        put("dʑ", affricate("dʑ", true, 2100.0, 4500.0))
        put("ʈʂ", affricate("ʈʂ", false, 1500.0, 3200.0))
        put("ɖʐ", affricate("ɖʐ", true, 1500.0, 3200.0))
        put("tʃ", affricate("tʃ", false, 1800.0, 3000.0))
        put("dʒ", affricate("dʒ", true, 1800.0, 3000.0))
        put("pf", affricate("pf", false, 1200.0, 2500.0))

        // ---- 擦音 ----
        put("ɸ", fric("ɸ", false, 900.0, 1200.0))
        put("β", fric("β", true, 900.0, 1200.0))
        put("f", fric("f", false, 2500.0, 1300.0))
        put("v", fric("v", true, 2500.0, 1300.0))
        put("θ", fric("θ", false, 5500.0, 1600.0))
        put("ð", fric("ð", true, 5500.0, 1600.0))
        put("s", fric("s", false, 6500.0, 1700.0))
        put("z", fric("z", true, 6500.0, 1700.0))
        put("ʃ", fric("ʃ", false, 3000.0, 1800.0))
        put("ʒ", fric("ʒ", true, 3000.0, 1800.0))
        put("ɕ", fric("ɕ", false, 4500.0, 2000.0))
        put("ʑ", fric("ʑ", true, 4500.0, 2000.0))
        put("ʂ", fric("ʂ", false, 3200.0, 1500.0))
        put("ʐ", fric("ʐ", true, 3200.0, 1500.0))
        put("ç", fric("ç", false, 3000.0, 2200.0))
        put("ʝ", fric("ʝ", true, 3000.0, 2200.0))
        put("x", fric("x", false, 1300.0, 1100.0))
        put("ɣ", fric("ɣ", true, 1300.0, 1100.0))
        put("χ", fric("χ", false, 1200.0, 1000.0))
        put("ʁ", fric("ʁ", true, 1100.0, 1000.0))
        put("ħ", fric("ħ", false, 1400.0, 1000.0))
        put("ʕ", fric("ʕ", true, 1400.0, 1000.0))
        put("h", fric("h", false, 1500.0, 1200.0, 80))
        put("ɦ", fric("ɦ", true, 1500.0, 1200.0, 80))
        put("ɧ", fric("ɧ", false, 2800.0, 1500.0))

        // ---- 鼻音 ----
        put("m", nasal("m", 1000.0))
        put("ɱ", nasal("ɱ", 1000.0))
        put("n", nasal("n", 1700.0))
        put("ɳ", nasal("ɳ", 1500.0, 2200.0))
        put("ɲ", nasal("ɲ", 2000.0, 2700.0))
        put("ȵ", nasal("ȵ", 2000.0, 2700.0, 90)) // Baxter 的 /ɲ/
        put("ŋ", nasal("ŋ", 1800.0, 2400.0))
        put("ɴ", nasal("ɴ", 1300.0, 2000.0))

        // ---- 边音 ----
        put("l", lateral("l", 1200.0, 2600.0))
        put("ɫ", lateral("ɫ", 1000.0, 2300.0))
        put("ɭ", lateral("ɭ", 1000.0, 2200.0))
        put("ʎ", lateral("ʎ", 2000.0, 2800.0))
        put("ȴ", lateral("ȴ", 2000.0, 2800.0))
        put("ɺ", tap("ɺ", 1200.0))

        // ---- 近音 ----
        put("ɹ", appr("ɹ", 350.0, 1300.0, 1700.0))
        put("ɻ", appr("ɻ", 350.0, 1200.0, 1600.0))
        put("j", appr("j", 280.0, 2300.0, 3000.0, 60))
        put("w", appr("w", 300.0, 700.0, 2200.0, 60))
        put("ɥ", appr("ɥ", 280.0, 1900.0, 2300.0, 60))
        put("ʋ", appr("ʋ", 350.0, 1000.0, 2200.0))
        put("ɰ", appr("ɰ", 320.0, 1300.0, 2250.0))
        put("ʍ", Phone("ʍ", Kind.FRICATIVE, false, 300.0, 800.0, 2200.0, 0.3, 75, noise = true, noiseCenterHz = 1400.0))
        put("y", appr("y", 280.0, 2100.0, 2900.0, 60)) // Baxter 借 y 记 /j/

        // ---- 颤音 / 闪音 ----
        put("r", trill("r", 350.0, 1300.0, 1700.0))
        put("ʀ", trill("ʀ", 350.0, 1100.0, 2000.0))
        put("ʙ", trill("ʙ", 400.0, 700.0, 2200.0))
        put("ɾ", tap("ɾ", 1700.0))

        // ---- Baxter / 白-沙 传统罗马字的补充字母 ----
        put("tr", stop("tr", false, 400.0, 1500.0, 2200.0))  // Baxter：卷舌塞音 [ʈ]
        put("dr", stop("dr", true, 400.0, 1500.0, 2200.0, 60)) // Baxter：[ɖ]
        put("ng", nasal("ng", 1800.0, 2400.0))                 // Baxter：[ŋ]
        put("cʰ", affricate("cʰ", false, 2100.0, 4500.0)) // 与 tɕʰ 同
        // 上古不确定前缀（大写占位）：给一个中性的可听音段
        put("C", stop("C", false, 400.0, 1500.0, 2400.0, 55))
        put("N", nasal("N", 1700.0))
        put("T", stop("T", false, 400.0, 1700.0, 2600.0, 55))
        put("P", stop("P", false, 400.0, 700.0, 2200.0, 55))
        put("K", stop("K", false, 400.0, 1800.0, 2400.0, 55))
        put("S", fric("S", false, 5000.0, 1700.0, 80))
        put("B", stop("B", true, 400.0, 700.0, 2200.0, 55))
        put("D", stop("D", true, 400.0, 1700.0, 2600.0, 55))
        put("G", stop("G", true, 400.0, 1800.0, 2400.0, 55))
        put("M", nasal("M", 1000.0))
        put("L", lateral("L", 1200.0, 2600.0))
        put("R", trill("R", 350.0, 1300.0, 1700.0))
        // 白-沙构拟里的其余大写占位/前置成分（不确定音素，给个中性可听音段）
        put("A", v("A", 700.0, 1300.0, 2500.0, 0.8, 120))
        put("E", v("E", 450.0, 1900.0, 2500.0, 0.8, 120))
        put("I", v("I", 300.0, 2200.0, 2900.0, 0.8, 120))
        put("O", v("O", 420.0, 900.0, 2400.0, 0.8, 120))
        put("U", v("U", 330.0, 900.0, 2250.0, 0.8, 120))
        put("V", appr("V", 320.0, 900.0, 2200.0, 70))
        put("W", appr("W", 300.0, 700.0, 2200.0, 70))
        put("F", fric("F", false, 2500.0, 1300.0, 80))
        put("Q", stop("Q", false, 400.0, 1100.0, 2000.0, 65))
        put("J", appr("J", 280.0, 2300.0, 3000.0, 60))
    }

    /** 多字符键（按列表顺序尝试匹配；送气位 `h`/`ʰ` 由附加符循环单独处理）。 */
    private val MULTI: List<String> = listOf(
        "ʈʂ", "ɖʐ", "tʂ", "dʐ", "tɕ", "dʑ", "tʃ", "dʒ",
        "tr", "dr", "ts", "dz", "ng", "pf", "cʰ"
    )

    /** 送气的书面形式（跟在塞音/塞擦音后 → 送气）。 */
    private const val ASPIRATION = "ʰh"
    /** 气声（浊送气）。 */
    private const val BREATHY = "ʱ"

    /**
     * 结构/分隔符：解析时静默跳过，不算未知符号。
     * 白-沙用 `* [ ] ( ) - .` 表示构拟、可选成分与语素边界；`<r>` 为填音/中缀；
     * `ˈ ˌ` 是重音符号；`H` / `X` 是 Baxter 的**声调字母**（去/上，声调单独解析）；
     * `͡` 是连音符（t͡s 之类，跳过即成 t+s 两个音段）；数字与问号感叹号是校对残留。
     * 注意：撇号 **不** 在此列 —— 白-沙/廣韻用 `'` 记影母 [?]，必须发音。
     */
    private const val STRUCTURAL = "*/[]()-.·+‿|=,;:\"…–—⟦⟧{}⟨⟩⁽⁾ˈˌˑHX͡<>?!~&0123456789ǀǁǂǃ"

    // ========================================================================
    // 附加符（combining / modifier letters）
    // ========================================================================
    private const val MOD_NAZAL = '̃'
    private const val MOD_DEV = '̥'
    private const val MOD_DEV2 = '̊'
    private const val MOD_BREATHY = '̤'
    private const val MOD_CREAKY = '̰'
    private const val MOD_SYLLABIC = '̩'
    private const val MOD_NONSYL = '̯'
    private const val MOD_UNRELEASED = '̚'
    private const val MOD_LABIAL = 'ʷ'
    private const val MOD_PALATAL = 'ʲ'
    private const val MOD_PHARYN = 'ˤ'
    private const val MOD_RHOTIC = '˞'
    private const val MOD_RETRACT = '˗'
    private const val MOD_ADVANCE = '˖'
    private const val MOD_LONG = 'ː'
    private const val MOD_LONG2 = '꞉'
    // 附加符（含 NFD 分解出的组合重音符，王力/潘悟雲/unt 诸家用它们标调）
    private const val MOD_RETRACT_TACK = '̙'   // U+0319 右钩，后缩
    private const val MOD_LOWERED = '̞'       // U+031E 下沉
    private const val MOD_BREVE = '̆'         // U+0306 短音
    private const val MOD_ACUTE = '́'         // U+0301 阳平/升
    private const val MOD_GRAVE = '̀'         // U+0300 去声/降
    private const val MOD_CIRC = '̂'          // U+0302 上声/降升
    private const val MOD_CARON = '̌'         // U+030C 上声
    private const val MOD_MACRON = '̄'        // U+0304 平
    private const val MOD_DIAER = '̈'         // U+0308 分音符（无语调意义）
    private const val MOD_OVERLAY = '̽'

    private fun applyModifiers(base: Phone, mods: String): Phone {
        if (mods.isEmpty()) return base
        var p = base
        for (m in mods) {
            p = when (m) {
                MOD_NAZAL -> p.copy(f3 = p.f3 * 0.92, amp = p.amp * 0.92)
                MOD_DEV, MOD_DEV2 -> p.copy(voiced = false)
                MOD_BREATHY -> p.copy(breathy = true)
                MOD_CREAKY -> p.copy(creaky = true)
                MOD_SYLLABIC -> p.copy(syllabic = true, durMs = maxOf(p.durMs, 140))
                MOD_NONSYL -> p.copy(syllabic = false, durMs = (p.durMs * 0.6).toInt().coerceAtLeast(40))
                MOD_UNRELEASED -> p.copy(burst = false, durMs = (p.durMs * 0.7).toInt().coerceAtLeast(35))
                MOD_LABIAL -> p.copy(f2 = p.f2 * 0.82, f3 = p.f3 * 0.92)
                MOD_PALATAL -> p.copy(f2 = p.f2 * 1.18)
                MOD_PHARYN -> p.copy(f1 = p.f1 * 0.9, f2 = p.f2 * 0.82)
                MOD_RHOTIC -> p.copy(f3 = p.f3 * 0.72)
                MOD_RETRACT -> p.copy(f2 = p.f2 * 0.88)
                MOD_ADVANCE -> p.copy(f2 = p.f2 * 1.1)
                MOD_RETRACT_TACK -> p.copy(f2 = p.f2 * 0.9)
                MOD_LOWERED -> p.copy(f1 = p.f1 * 1.06)
                MOD_BREVE -> p.copy(durMs = (p.durMs * 0.72).toInt().coerceAtLeast(45))
                MOD_LONG, MOD_LONG2 -> p.copy(durMs = (p.durMs * 1.6).toInt())
                // 重音类附加符只对声调有意义，不改发音参数
                MOD_ACUTE, MOD_GRAVE, MOD_CIRC, MOD_CARON, MOD_MACRON, MOD_DIAER, MOD_OVERLAY -> p
                else -> p
            }
        }
        return p
    }

    // ========================================================================
    // 声调
    // ========================================================================
    /** 声调解析结论。contourHz 是按时间等分采样的基频折线。 */
    data class Tone(
        val name: String,
        val contourHz: DoubleArray,
        val recognized: Boolean,
        val raw: String,
        /** 促声/入声：整段要明显短促。 */
        val checked: Boolean
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val BARS = mapOf(
        '˥' to 5, '˦' to 4, '˧' to 3, '˨' to 2, '˩' to 1,
        '¹' to 1, '²' to 2, '³' to 3, '⁴' to 4, '⁵' to 5
    )

    private const val BASE_F0 = 148.0

    private fun hzFromLevel(level: Int): Double = 110.0 + (level - 1) * 22.0

    /**
     * 五点声调曲线：起点 - 1/4 - 中点 - 3/4 - 终点。
     *
     * 三点折线在两点之间是直线，听感是"折"出来的；五点让转折自带过渡，
     * 尤其是上声的降升谷底与去声的先平后降，更接近方言调查里的真实调型。
     * 中间三点按二次贝塞尔由 (from, mid, to) 内插，保证谷/峰严格落在中点。
     */
    private fun fivePoint(from: Double, mid: Double, to: Double): DoubleArray {
        fun bez(a: Double, b: Double, c: Double, t: Double): Double {
            val u = 1.0 - t
            return u * u * a + 2.0 * u * t * b + t * t * c
        }
        return doubleArrayOf(
            from,
            bez(from, mid, to, 0.25),
            mid,
            bez(from, mid, to, 0.75),
            to
        )
    }

    /**
     * 解析声调。识别顺序：
     * 1. 五度调符序列（`˧˥` / `˥˩`）→ 直接映射折线（**识别**）
     * 2. 汉语调符 `꜀`(平) `꜂`(上) `꜄`(去) `꜆`/`꜊`(入) → 平/升/降/促（**识别**）
     * 3. Baxter 末尾/任意位置 `H`(去) / `X`(上) → 降/升（**识别**）
     * 4. 组合重音符 `ˊ ˇ ˋ ˉ`（王力/潘悟雲/unt 诸家标调）（**识别**）
     * 5. 白-沙上古音的**声调来源**：`-s` 后缀 → 去声；`-ʔ`/喉塞尾 → 上声；
     *    p/t/k 口腔塞尾 → 入声（**识别**，依据 Baxter-Sagart 的 *-s / *-ʔ 对应）
     * 6. 都不命中 → 平调，recognized=false，raw 记入未识别（不假装做对了声调）
     */
    fun parseTone(ipa: String, hasStopCoda: Boolean, glottalCoda: Boolean, finalS: Boolean): Tone {
        // 1. 五度调符
        val levels = StringBuilder()
        for (ch in ipa) if (BARS.containsKey(ch)) levels.append(BARS[ch])
        if (levels.isNotEmpty()) {
            val contour = DoubleArray(levels.length) { hzFromLevel(levels[it] - '0') }
            return Tone("调值 ${levels}", contour, true, levels.toString(), hasStopCoda)
        }
        // 2. 汉语调符
        for (ch in ipa) {
            when (ch) {
                '꜀', '꜁' -> return Tone("平声", fivePoint(BASE_F0, BASE_F0, BASE_F0 - 3), true, ch.toString(), hasStopCoda)
                '꜂', '꜃' -> return Tone("上声(升)", fivePoint(BASE_F0 - 25, BASE_F0, BASE_F0 + 28), true, ch.toString(), hasStopCoda)
                '꜄', '꜅' -> return Tone("去声(降)", fivePoint(BASE_F0 + 32, BASE_F0 - 6, BASE_F0 - 45), true, ch.toString(), hasStopCoda)
                '꜆', '꜇', '꜊' -> return Tone("入声(促)", fivePoint(BASE_F0 + 10, BASE_F0 - 2, BASE_F0 - 10), true, ch.toString(), true)
            }
        }
        // 3. Baxter H / X
        if (ipa.contains('H')) return Tone("去声(降)", fivePoint(BASE_F0 + 30, BASE_F0 - 5, BASE_F0 - 42), true, "H", hasStopCoda)
        if (ipa.contains('X')) return Tone("上声(升)", fivePoint(BASE_F0 - 22, BASE_F0, BASE_F0 + 26), true, "X", hasStopCoda)
        // 4. 组合重音符（王力/潘悟雲/unt 诸家用 ˊˇˋˆ 标调；NFD 后为组合符）
        if (ipa.indexOf(MOD_ACUTE) >= 0) return Tone("重音符号ˊ(升)", fivePoint(BASE_F0 - 20, BASE_F0, BASE_F0 + 24), true, "ˊ", hasStopCoda)
        if (ipa.indexOf(MOD_CIRC) >= 0 || ipa.indexOf(MOD_CARON) >= 0) return Tone("重音符号ˇ(降升)", fivePoint(BASE_F0, BASE_F0 - 26, BASE_F0 + 18), true, "ˇ", hasStopCoda)
        if (ipa.indexOf(MOD_GRAVE) >= 0) return Tone("重音符号ˋ(降)", fivePoint(BASE_F0 + 28, BASE_F0 - 4, BASE_F0 - 40), true, "ˋ", hasStopCoda)
        if (ipa.indexOf(MOD_MACRON) >= 0) return Tone("重音符号ˉ(平)", fivePoint(BASE_F0, BASE_F0, BASE_F0 - 2), true, "ˉ", hasStopCoda)
        // 5. 白-沙上古声调来源
        if (finalS) return Tone("去声(降)·*-s", fivePoint(BASE_F0 + 26, BASE_F0 - 4, BASE_F0 - 38), true, "-s", hasStopCoda)
        if (glottalCoda) return Tone("上声(升)·*-ʔ", fivePoint(BASE_F0 - 22, BASE_F0, BASE_F0 + 24), true, "-ʔ", false)
        // 6. 口腔塞音韵尾 → 入声
        if (hasStopCoda) return Tone("入声(促)", fivePoint(BASE_F0 + 8, BASE_F0 - 2, BASE_F0 - 10), true, "塞尾", true)
        // 5. 未识别 → 平调
        return Tone("平调(未识别声调)", fivePoint(BASE_F0, BASE_F0, BASE_F0 - 2), false, "", false)
    }

    // ========================================================================
    // 解析结果
    // ========================================================================
    data class ParseResult(
        val phones: List<Phone>,
        /** 未能直接映射、由最近邻回退替代的原始符号（去重、保序）。 */
        val unknownSymbols: List<String>,
        val tone: Tone
    ) {
        val totalDurationMs: Int
            get() = phones.sumOf { it.durMs }
    }

    /**
     * 把一条拟音/音标串切成音段序列。
     * @return 至少含一个音段（输入全不可解析时，返回一个默认央元音，保证"一定有声音"）。
     */
    fun parse(ipa: String): ParseResult {
        val phones = ArrayList<Phone>()
        val unknown = LinkedHashSet<String>()
        if (ipa.isBlank()) {
            return ParseResult(listOf(FALLBACK_PHONE("(空)")), unknown.toList(), parseTone("", false, false, false))
        }
        // NFD 归一：预组合带调字母（é / ǎ / â …）分解成 基字母 + 组合符，组合符由下面的
        // 附加符逻辑统一处理，声调记号也能被 parseTone 看到。
        val s = Normalizer.normalize(ipa, Normalizer.Form.NFD)

        var i = 0
        val n = s.length
        while (i < n) {
            val ch = s[i]
            // 跳过结构符 / 空白 / 声调符（声调单独解析）/ CJK 汉字
            if (ch.isWhitespace() || STRUCTURAL.indexOf(ch) >= 0 || BARS.containsKey(ch) ||
                isChineseToneLetter(ch) || isCjk(ch)
            ) {
                i++
                continue
            }

            // 孤立附加符（前面是结构符/被跳过的字符）：白-沙 `*[N-t]ˤə…` 里的 ˤ
            // 实际修饰其前的音段，这里挂到已产出的最后一个音段上；没有则静默跳过。
            if (isModifier(ch)) {
                if (phones.isNotEmpty()) {
                    phones[phones.size - 1] = applyModifiers(phones.last(), ch.toString())
                }
                i++
                continue
            }
            // 孤立送气/气声符：ʰ/ʱ 永远挂到前一个音段；ASCII `h` 只有在紧跟塞音/塞擦音后
            // 才算送气（否则它是独立的喉擦音 [h]，必须发音，不能吞掉）。
            val orphanAsp = ch == 'ʰ' || ch == 'ʱ' ||
                (ch == 'h' && phones.isNotEmpty() &&
                    (phones.last().kind == Kind.STOP || phones.last().kind == Kind.AFFRICATE))
            if (orphanAsp) {
                if (phones.isNotEmpty()) {
                    phones[phones.size - 1] = phones.last().copy(aspirated = true)
                }
                i++
                continue
            }

            // 多字符键最长匹配
            val multi = MULTI.firstOrNull { k -> s.startsWith(k, i) }
            val token: String
            var next: Int
            if (multi != null) {
                token = multi
                next = i + multi.length
            } else {
                token = ch.toString()
                next = i + 1
            }

            val base = BASE[token]
            if (base == null) {
                // 未知基础符号：最近邻回退（绝不静默）
                unknown += token
                phones += FALLBACK_PHONE(token)
                i = next
                continue
            }

            // 收拢其后紧跟的附加符（包含送气位）
            val mods = StringBuilder()
            var j = next
            var aspirated = false
            var breathyMod = false
            while (j < n) {
                val c = s[j]
                if (ASPIRATION.indexOf(c) >= 0) {
                    // h / ʰ 只对塞音、塞擦音是送气；对其他字母（如单独的 h 已单独成段）不吞
                    if (base.kind == Kind.STOP || base.kind == Kind.AFFRICATE) {
                        aspirated = true; j++; continue
                    } else break
                }
                if (BREATHY.indexOf(c) >= 0) {
                    if (base.kind == Kind.STOP || base.kind == Kind.AFFRICATE) {
                        aspirated = true; breathyMod = true; j++; continue
                    } else break
                }
                if (isModifier(c)) {
                    mods.append(c); j++; continue
                }
                break
            }
            var p = applyModifiers(base, mods.toString())
            p = p.copy(
                symbol = s.substring(i, j),
                aspirated = p.aspirated || aspirated,
                breathy = p.breathy || breathyMod
            )
            if (p.checked) {
                // 促声韵尾：短促
                p = p.copy(durMs = (p.durMs * 0.6).toInt().coerceAtLeast(45))
            }
            phones += p
            i = j
        }

        if (phones.isEmpty()) {
            // 串里全是结构符 → 仍然给一个音，避免"无音频"
            phones += FALLBACK_PHONE("(无音段)")
        }
        // 促声/入声 = 以**口腔塞音韵尾**（p/t/k/ʈ/ȶ…）收尾；喉塞音 [?] 收尾（白-沙上声来源）
        // 只作喉塞收束，**不**当作入声，也**不**声明声调——避免假装做对了声调。
        val last = phones.last()
        val lastSym = last.symbol
        val glottalCoda = lastSym.contains('ʔ') || lastSym.contains('\'') ||
            lastSym.contains('’') || lastSym.contains('‘')
        val oralStopCoda = last.kind == Kind.STOP && !glottalCoda
        if (oralStopCoda) {
            phones[phones.size - 1] = last.copy(checked = true, durMs = (last.durMs * 0.55).toInt().coerceAtLeast(40))
        }
        // 白-沙上古 `-s` 后缀（去声来源）：形如 `...-s` 结尾。
        val finalS = FINAL_S.containsMatchIn(s)
        return ParseResult(phones, unknown.toList(), parseTone(s, oralStopCoda, glottalCoda, finalS))
    }

    /** 末位 `-s`（白-沙去声来源）。用 `\z` 结尾锚避免 `$`（Kotlin 字符串模板干扰）。 */
    private val FINAL_S = Regex("-\\s*[sS]\\s*\\z")

    private fun isCjk(c: Char): Boolean =
        c.code in 0x3400..0x4DBF || c.code in 0x4E00..0x9FFF || c.code in 0xF900..0xFAFF

    private fun isChineseToneLetter(c: Char): Boolean =
        c == '꜀' || c == '꜁' || c == '꜂' || c == '꜃' || c == '꜄' || c == '꜅' ||
            c == '꜆' || c == '꜇' || c == '꜊' || c == '꜋'

    /** 是否为附加符/修饰符字母。 */
    fun isModifier(c: Char): Boolean = when (c) {
        MOD_NAZAL, MOD_DEV, MOD_DEV2, MOD_BREATHY, MOD_CREAKY, MOD_SYLLABIC, MOD_NONSYL,
        MOD_UNRELEASED, MOD_LABIAL, MOD_PALATAL, MOD_PHARYN, MOD_RHOTIC, MOD_RETRACT,
        MOD_ADVANCE, MOD_LONG, MOD_LONG2,
        MOD_RETRACT_TACK, MOD_LOWERED, MOD_BREVE, MOD_ACUTE, MOD_GRAVE, MOD_CIRC,
        MOD_CARON, MOD_MACRON, MOD_DIAER, MOD_OVERLAY -> true
        else -> false
    }

    /** 最近邻回退：未知符号统一退到可听的央元音 [ə]（并保留原始符号名）。 */
    private fun FALLBACK_PHONE(sym: String): Phone =
        Phone(sym, Kind.VOWEL, true, 500.0, 1500.0, 2500.0, 0.7, 115)

    /** 俄语 IPA 里常见的重音/次重音符号等，属于结构符。 */
    fun isStructural(c: Char): Boolean =
        c.isWhitespace() || STRUCTURAL.indexOf(c) >= 0 || BARS.containsKey(c) || isChineseToneLetter(c)
}
