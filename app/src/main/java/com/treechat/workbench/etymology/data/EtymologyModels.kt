package com.treechat.workbench.etymology.data

/**
 * 中古汉语（切韵/广韵音系）拟音数据
 *
 * 字段按**学者归属**分开，不得互相顶替：
 * [wangLi] 是王力拟音、[zhengzhang] 是郑张尚芳拟音、
 * [baxterMiddleChinese] 是 **Baxter 的中古音转写**（来自 Baxter-Sagart 2015 词表）。
 * 声纽/韵部/等呼/反切来自广韵系资料，Baxter-Sagart 词表里没有，缺时一律写"未收录"。
 *
 * [guangyunPosition] / [fanqie] 与 [karlgren] / [panWuyun] / [unt] / [msoeg] 来自
 * `han_mc_guangyun.tsv`（见 [HanHistoricalIndex]）：前者是廣韻真实音韻地位与反切，
 * 后者是由 tshet-uinh-examples 推导器**规则推导**的各家切韵拟音（非逐条抄录）。
 */
data class MiddleChineseData(
    val initial: String,      // 声纽（如：船母 ʑ、知母 ʈ、透母 tʰ）
    val rhyme: String,        // 韵部（如：真韵、先韵、清韵）
    val grade: String,        // 等呼（如：三等、一等）
    val round: String,        // 开闭口（如：开口、合口）
    val wangLi: String,       // 王力拟音（中古）
    val zhengzhang: String,   // 郑张尚芳拟音（中古）
    val fanqie: String,       // 反切（如：食伦切）
    val baxterMiddleChinese: String = "未收录", // Baxter 中古音转写（Baxter-Sagart 2015）
    val guangyunPosition: String = "未收录",    // 廣韻音韻地位（母·呼·等·類·韻·調，nk2028/tshet-uinh-data CC0）
    val karlgren: String = "未收录",            // 高本漢中古拟音（规则推导，tshet-uinh-examples MIT）
    val panWuyun: String = "未收录",            // 潘悟雲中古拟音（规则推导）
    val unt: String = "未收录",                 // unt 中古拟音（规则推导）
    val msoeg: String = "未收录"                // msoeg 中古拟音（规则推导）
)

/**
 * 上古汉语（周秦古音）多套权威拟音对照
 *
 * [wangLi] / [liFangkuei] / [zhengzhang] / [schuessler] 四栏来自 `han_oc_scholars.tsv`
 * （见 [HanHistoricalIndex]，rephon 整理的**各家真实拟音**）；
 * [baxterSagart] 来自 Baxter-Sagart 2015 词表（`han_phonology.tsv`）。
 */
data class OldChineseReconstruction(
    val zhengzhang: String,   // 郑张尚芳拟音
    val baxterSagart: String, // 白一平-沙加尔拟音 (Baxter-Sagart 2014)
    val wangLi: String,       // 王力上古拟音
    val liFangkuei: String,   // 李方桂拟音
    val schuessler: String = "未收录" // 许思莱 Old Chinese Minimal（CC0 / Zenodo）
)

/**
 * 单个汉字的历时音韵与现代国际音标全息数据
 */
data class CmnPhonologyData(
    val char: String,
    val pinyin: String,
    val ipa: String,          // 普通话严式 IPA + 五度调值 (如 [ʂən˧˥])
    val tone: String,         // 调类 (如 阳平 35)
    val middleChinese: MiddleChineseData,
    val oldChinese: OldChineseReconstruction,
    val meaning: String
)

/**
 * 英语单词双口音与全域词源数据
 */
data class EnglishEtymWord(
    val word: String,
    val ipaModern: String,    // 现代通用英音 (Modern RP)
    val ipaTrad: String,      // 1910s~1940s 传统贵族英音 (Conservative RP)
    val origin: String,       // 语族归属 (Proto-Germanic, Latin, Old French, Ancient Greek)
    val evolution: String,    // 词根流变 (PIE 印欧根与借词路径)
    val register: String,     // 语体文风 (如 宗教圣言、典雅文学、学术哲学)
    val rarity: String        // 生僻度评级 (常用 ★★★★★ 至 极端罕词 ☆☆☆☆☆)
)

/**
 * 英语特定概念的同义词族谱网（含常用与极端生僻词）
 */
data class EnglishCluster(
    val concept: String,
    val conceptZh: String,
    val words: List<EnglishEtymWord>
)

/**
 * 俄语单词标准 IPA 与斯拉夫/外来借词源流数据
 */
data class RussianEtymWord(
    val word: String,
    val translit: String,
    val ipa: String,          // 当代标准俄语 (CSR) 严式 IPA (含重音、软辅音颚化 ʲ、元音弱化)
    val origin: String,       // 语源谱系 (Proto-Slavic 原始斯拉夫, Old Church Slavonic 古教会斯拉夫, 希腊借词)
    val evolution: String,    // 语言流变注释
    val register: String,     // 语体文风
    val rarity: String        // 生僻度评级
)

/**
 * 俄语概念同义词族谱网
 */
data class RussianCluster(
    val concept: String,
    val conceptZh: String,
    val words: List<RussianEtymWord>
)

/**
 * 跨语言核心概念枢纽（汉-英-俄三语对齐实体）
 */
data class CrossLinguisticAlignment(
    val conceptId: String,
    val canonicalZh: String,
    val summary: String,
    val cmnList: List<CmnPhonologyData>,
    val engCluster: EnglishCluster?,
    val rusCluster: RussianCluster?
)
