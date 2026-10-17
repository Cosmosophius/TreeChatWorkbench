package com.treechat.workbench.etymology.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.etymology.data.CrossLinguisticAlignment
import com.treechat.workbench.etymology.data.EtymologyDataIndex
import com.treechat.workbench.etymology.data.GlossParser
import com.treechat.workbench.etymology.data.VocabIndex

/**
 * 词典「思维导图式并列卡片」板（DictionaryScreen 与 EtymoScopeBottomSheet 的**共用**组件）。
 *
 * 为什么要有这个文件：过去两个入口各写一套纵向信息流（音标一行、释义一行、词源一段），
 * 既重复又"不直观、浪费空间"。现在两者都用本文件的 [DictBoard]：
 *
 * - **并列**：[LazyVerticalGrid] 网格，同层卡片视觉等权、不互相挤压；
 * - **可展开**：每张卡片有独立标题栏，点标题栏折叠/展开，状态由调用方以
 *   `collapsedIds` 字符串持有（`rememberSaveable` 可存），不落库；
 * - **高效利用空间**：列数随屏宽变化（手机竖屏 2 列 → 平板/横屏 3~4 列），
 *   用户还能切「单列宽读」；卡片内是"标签 + 取值"的紧凑小行，不是横贯全屏的一行一个；
 * - **词语来源必须可见**：见 [buildDictCards] 里的「词源与来源」卡片，
 *   来源语族来自 [EtymologyDataIndex.originFamilies]（EtymWN 源词语言码，**查表**），
 *   词源关系来自 [EtymologyDataIndex.etymologyRelations]（**查表**），
 *   中古/上古各家的规则推导项一律标「规则推导」，查不到一律写"未收录"。
 *
 * 纪律：本文件**只汇集、不发明**。任何取值都来自既有索引；取不到就写"未收录"。
 */

// ============================================================================
// 卡片模型（纯数据，构建逻辑与 Compose 无关，便于核对"有没有编数据"）
// ============================================================================

/** 一个数据点的来源性质，直接决定界面上的角标。 */
enum class DictSourceKind {
    /** 查表：来自某份可溯源的数据集/词表。 */
    LOOKUP,

    /** 规则推导：由别的数据按算法算出（如"保守 RP"、拼音→IPA、各家切韵拟音）。 */
    DERIVED,

    /** 未收录：索引里没有，界面如实写"未收录"，绝不填猜测值。 */
    MISSING
}

/** 词条释义里的一条**词性分组**（如 `vt.` 下的若干个义项）。 */
data class GlossGroup(
    /** 原始词性标记，`vt.` 这种写法原样保留；空串表示原数据没写词性。 */
    val pos: String,
    /** 该词性下的义项，文字未改写。 */
    val senses: List<String>
)

/** 一个可点读的单词 chip（同义词分组里用）：词形 + 自身音标。 */
data class DictItem(
    val word: String,
    /** 词表里的音标，空白表示该词没收录音标（chip 仍显示，只是不可点读）。 */
    val ipa: String = ""
)

/** 卡片内的一行键值对。 */
data class DictRow(
    /** 左侧标签（渲染成 [Tag]）。 */
    val label: String,
    /** 右侧取值（音标类用等宽字体）。 */
    val value: String,
    /** 该取值的来源说明，逐条标注。 */
    val source: String? = null,
    /** 发音口音键（交给 [rememberSpeaker] 的同一个 speaker），null = 该行不发音。 */
    val accent: String? = null,
    /** 来源性质。 */
    val kind: DictSourceKind = DictSourceKind.LOOKUP,
    /**
     * 音标类字段的多个变体（旧式混排记录里并列的多个读音，见 [IpaDisplay]）。
     * 非空时**逐条分行展示**、每行各自可发音，不再当成一个整体字符串贴出来。
     */
    val variants: List<String> = emptyList(),
    /**
     * 这一行要展示的**成组词条**（同义词按义项分组后，一个义项下有多个单词）。
     * 非空时渲染成可换行的词条 chip，**点一下就朗读该词**；
     * 此时 [variants] 应保持为空，避免同一行既分行又排 chip。
     */
    val items: List<DictItem> = emptyList()
)

/** 一张并列卡片。 */
data class DictCard(
    /** 折叠状态的稳定 id（跨查询保持）。 */
    val id: String,
    val title: String,
    val icon: ImageVector,
    val subtitle: String? = null,
    /** 标题栏右侧角标，如"查表"/"规则推导"。 */
    val badge: String? = null,
    val badgeKind: DictSourceKind = DictSourceKind.LOOKUP,
    val rows: List<DictRow> = emptyList(),
    /** 卡片底部口径说明。 */
    val note: String? = null,
    /** rows 为空时展示的空态文案（必须写"未收录"）。 */
    val emptyText: String? = null,
    /**
     * 是否横贯整行（网格里占满 `maxLineSpan`）。
     * 只给「简明释义」这类**需要通栏阅读**的主卡用 —— 释义被切成窄列会更难读。
     */
    val fullWidth: Boolean = false
)

// ============================================================================
// 折叠状态（调用方持有，存成字符串以便 rememberSaveable）
// ============================================================================

/** 折叠状态串 → 已折叠的卡片 id 集合。 */
fun parseCollapsedIds(csv: String): Set<String> =
    if (csv.isBlank()) emptySet() else csv.split(',').filter { it.isNotBlank() }.toSet()

/**
 * 首次进入时的默认折叠项：只折叠"数据来源与许可"这类长参考卡片，
 * 让首屏尽量多放实体卡片（用户点开随时可见，展开后也照常显示）。
 * 调用方把它当作 `collapsedIds` 的**初值**即可（不要并进 [parseCollapsedIds]，
 * 否则用户展开后会被默认值又压回去）。
 */
const val DEFAULT_COLLAPSED_CARD_IDS: String = "coverage"

/** 折叠状态串取反一张卡片的折叠位。 */
fun toggleCollapsedId(csv: String, id: String): String {
    val set = parseCollapsedIds(csv).toMutableSet()
    if (!set.add(id)) set.remove(id)
    return set.joinToString(",")
}

// ============================================================================
// 组卡：把既有索引汇成并列卡片（本文件唯一的"数据搬运"处）
// ============================================================================

private const val MISSING = "未收录"

/** 生僻度里的 ★/☆ 是旧数据符号，界面上剥掉（零符号约束）。 */
private fun stripStars(text: String): String = text.trim().trim('★', '☆', ' ')

/**
 * 把一次查询的结果汇成并列卡片。
 *
 * 每张卡片只装**同一语言/同一历史层次**的数据 —— 不同语言的音标绝不塞进同一列。
 * 历史音（中古/上古/保守 RP）与规则推导项一律带 [DictSourceKind.DERIVED] 角标。
 */
fun buildDictCards(
    alignment: CrossLinguisticAlignment,
    query: String,
    limitPerSection: Int = 6
): List<DictCard> {
    val lang = detectLang(query)
    val cards = ArrayList<DictCard>(12)

    val etyWord = etyWordFor(query, alignment)
    val relations = etyWord?.let { EtymologyDataIndex.etymologyRelations(it) }.orEmpty()
    val families = etyWord?.let { EtymologyDataIndex.originFamilies(it) }.orEmpty()

    val hanCount = alignment.cmnList.size
    val hanSuffix = if (hanCount > 1) "逐字（共 $hanCount 字）" else "单字"

    // ---------------- 简明释义（通栏卡）：按词性分组 + 义项编号 ----------------
    //
    // 词表原文本来就带词性和义项边界（`vt. 放弃, 抛弃；n. 放任` / `v. 1. 抛弃 2. 离弃`），
    // 过去被当成一个整串贴出来，读起来"一堆逗号不分主次"。这里交给 [GlossParser] 切开，
    // 只切分、不改字。释义需要通栏阅读，故 [DictCard.fullWidth] = true。
    val primaryWord = when (lang) {
        Lang.EN -> etyWord
        Lang.RU -> query.trim().lowercase()
        Lang.HAN -> null
    }
    if (primaryWord != null) {
        val rawGloss = when (lang) {
            Lang.RU -> VocabIndex.russian(primaryWord)?.gloss.orEmpty()
            else -> VocabIndex.english(primaryWord)?.gloss.orEmpty()
        }.trim()
        val groups = GlossParser.parse(rawGloss)
        cards += DictCard(
            id = "gloss",
            title = "简明释义",
            icon = Icons.Outlined.MenuBook,
            subtitle = "词头：$primaryWord · 词性分组 · 义项编号",
            badge = if (groups.isEmpty()) null else "查表",
            rows = groups.map { g ->
                DictRow(
                    label = if (g.pos.isEmpty()) "释义" else GlossParser.displayLabel(g.pos),
                    // value 供思维导图等其它消费者读取；界面上渲染的是下面带序号的 variants
                    value = g.senses.joinToString("；"),
                    source = if (g.pos.isEmpty()) null else GlossParser.posZh(g.pos),
                    kind = DictSourceKind.LOOKUP,
                    // 序号只用来计数，不改写原文
                    variants = g.senses.mapIndexed { i, s -> "${i + 1}. $s" }
                )
            },
            note = "词性标记与义项边界均切自词表原文（vocab2 / ECDICT），未改写、未增补、未合并近义词。",
            emptyText = "$MISSING：词表未收录该词释义原文。",
            fullWidth = true
        )
    }

    // ---------------- 汉语：现代 / 中古 / 上古 / 字义 ----------------
    if (alignment.cmnList.isNotEmpty()) {
        cards += DictCard(
            id = "cmn.modern",
            title = "现代汉语 · 普通话",
            icon = Icons.Outlined.GraphicEq,
            subtitle = hanSuffix,
            badge = "规则推导",
            badgeKind = DictSourceKind.DERIVED,
            rows = alignment.cmnList.flatMap { d ->
                buildList {
                    add(
                        DictRow(
                            label = "${d.char} 拼音",
                            value = if (isRecorded(d.pinyin)) d.pinyin else MISSING,
                            source = if (isRecorded(d.tone)) d.tone else null,
                            kind = if (isRecorded(d.pinyin)) DictSourceKind.LOOKUP else DictSourceKind.MISSING
                        )
                    )
                    add(
                        DictRow(
                            label = "${d.char} 普通话 IPA",
                            value = IpaDisplay.single(d.ipa),
                            source = "拼音→IPA 规则转写",
                            accent = "cmn_modern",
                            kind = if (isRecorded(d.ipa)) DictSourceKind.DERIVED else DictSourceKind.MISSING
                        )
                    )
                }
            },
            note = "拼音与调值来自内置词表；普通话 IPA 由拼音按规则转写（推导，非词典标注）。" +
                "「神/圣/道/光/智」五字的音标与调值为人工核对值。"
        )

        cards += DictCard(
            id = "cmn.mc",
            title = "中古音 · 切韵／廣韻",
            icon = Icons.Outlined.Translate,
            subtitle = "$hanSuffix · 试听为共振峰合成（非录音）",
            badge = "含规则推导",
            badgeKind = DictSourceKind.DERIVED,
            rows = alignment.cmnList.flatMap { d ->
                val mc = d.middleChinese
                buildList {
                    add(
                        DictRow(
                            "${d.char} 白-沙转写", IpaDisplay.single(mc.baxterMiddleChinese),
                            "Baxter-Sagart 2015（查表）",
                            "cmn_middle",
                            if (isRecorded(mc.baxterMiddleChinese)) DictSourceKind.LOOKUP else DictSourceKind.MISSING
                        )
                    )
                    add(
                        DictRow(
                            "${d.char} 廣韻地位", mc.guangyunPosition, "廣韻 · nk2028 CC0（查表）",
                            kind = if (isRecorded(mc.guangyunPosition)) DictSourceKind.LOOKUP else DictSourceKind.MISSING
                        )
                    )
                    if (isRecorded(mc.fanqie)) {
                        add(DictRow("${d.char} 反切", mc.fanqie, "廣韻 · nk2028 CC0（查表）"))
                    }
                    if (isRecorded(mc.initial)) add(DictRow("${d.char} 声纽", mc.initial, "廣韻系（人工核对）"))
                    if (isRecorded(mc.rhyme)) add(DictRow("${d.char} 韵部", mc.rhyme, "廣韻系（查表）"))
                    if (isRecorded(mc.grade)) add(DictRow("${d.char} 等呼", mc.grade, "廣韻系（人工核对）"))
                    listOf(
                        "高本漢" to mc.karlgren,
                        "王力" to mc.wangLi,
                        "潘悟雲" to mc.panWuyun,
                        "unt" to mc.unt,
                        "msoeg" to mc.msoeg
                    ).forEach { (name, v) ->
                        if (isRecorded(v)) {
                            add(
                                DictRow(
                                    "${d.char} $name", IpaDisplay.single(v),
                                    "规则推导 · tshet-uinh MIT", kind = DictSourceKind.DERIVED
                                )
                            )
                        }
                    }
                }
            },
            note = "「白-沙转写」「廣韻地位与反切」是查表数据；高本漢/王力/潘悟雲/unt/msoeg 五家是" +
                "按廣韻音韻地位**规则推导**的各家体系，各自标注、勿混为一谈。" +
                "郑张尚芳中古拟音本库未收录。"
        )

        cards += DictCard(
            id = "cmn.oc",
            title = "上古音 · 先秦",
            icon = Icons.Outlined.Translate,
            subtitle = "$hanSuffix · 试听为共振峰合成（非录音）",
            badge = "查表",
            badgeKind = DictSourceKind.LOOKUP,
            rows = alignment.cmnList.flatMap { d ->
                val oc = d.oldChinese
                buildList {
                    add(
                        DictRow(
                            "${d.char} 白-沙", IpaDisplay.single(oc.baxterSagart),
                            "Baxter-Sagart 2015（查表）", "cmn_old",
                            if (isRecorded(oc.baxterSagart)) DictSourceKind.LOOKUP else DictSourceKind.MISSING
                        )
                    )
                    listOf(
                        "王力" to oc.wangLi,
                        "李方桂" to oc.liFangkuei,
                        "郑张尚芳" to oc.zhengzhang,
                        "许思莱" to oc.schuessler
                    ).forEach { (name, v) ->
                        if (isRecorded(v)) {
                            add(DictRow("${d.char} $name", IpaDisplay.single(v), "rephon · MIT（学者拟音）"))
                        }
                    }
                }
            },
            note = "上古四家（王力 / 李方桂 / 郑张尚芳 / 许思莱）各成体系，**分别标注**、不合并成一句" +
                "「上古音 = xxx」；许思莱为 Old Chinese Minimal（CC0），白-沙为 Baxter-Sagart 2015 词表。" +
                "试听是共振峰合成近似，不是录音。"
        )

        cards += DictCard(
            id = "cmn.meaning",
            title = "字义",
            icon = Icons.Outlined.MenuBook,
            subtitle = hanSuffix,
            rows = alignment.cmnList.map { d ->
                DictRow(
                    d.char, d.meaning,
                    source = if (isRecorded(d.meaning)) "Baxter-Sagart 词表 / 词库释义" else null,
                    kind = if (isRecorded(d.meaning)) DictSourceKind.LOOKUP else DictSourceKind.MISSING
                )
            },
            emptyText = "$MISSING：本库未收录该字的释义。"
        )
    }

    // ---------------- 英语（对英语查询是主语言，对汉/俄查询是"对译词"） ----------------
    alignment.engCluster?.let { eng ->
        val isPrimary = lang == Lang.EN
        val prefix = if (isPrimary) "英语" else "英语 · 对译词"
        val words = eng.words.take(limitPerSection)
        val hidden = eng.words.size - words.size

        cards += DictCard(
            id = "en.modern",
            title = "$prefix · 现代音（Modern RP）",
            icon = Icons.Outlined.GraphicEq,
            subtitle = eng.concept,
            badge = "查表",
            rows = words.map { w ->
                DictRow(
                    label = w.word,
                    value = IpaDisplay.single(w.ipaModern),
                    source = stripStars(w.rarity).ifBlank { null },
                    accent = "en_modern",
                    kind = if (isRecorded(w.ipaModern)) DictSourceKind.LOOKUP else DictSourceKind.MISSING,
                    variants = IpaDisplay.variants(w.ipaModern)
                )
            },
            note = (if (hidden > 0) "共 ${eng.words.size} 项，已列前 ${words.size} 项。" else "") +
                "音标来自内置英汉词表（真实收录），不是推导；该表沿用 ECDICT 旧式混排记音，" +
                "显示前已把西里尔 ә/є、希腊 ε 换成 ə/ɛ，冒号换成 ː，并列的多个读音分行展示（见 IpaDisplay）。",
            emptyText = "$MISSING：内置英汉词表未收录该词。"
        )

        cards += DictCard(
            id = "en.trad",
            title = "$prefix · 保守 RP（1910s 传统贵族音）",
            icon = Icons.Outlined.GraphicEq,
            subtitle = "由现代 IPA 按音变规则推导",
            badge = "规则推导",
            badgeKind = DictSourceKind.DERIVED,
            rows = words.map { w ->
                DictRow(
                    label = w.word,
                    value = IpaDisplay.single(w.ipaTrad),
                    source = "ConservativeRp 音变规则",
                    accent = "en_trad",
                    kind = if (isRecorded(w.ipaTrad)) DictSourceKind.DERIVED else DictSourceKind.MISSING,
                    variants = IpaDisplay.variants(w.ipaTrad)
                )
            },
            note = DictCoverage.NO_HIST_EN + "；" + DictCoverage.HIST_EN_RULES + "；" + DictCoverage.HIST_AUDIO
        )

        cards += DictCard(
            id = "en.gloss",
            title = if (isPrimary) "英语 · 释义与词形派生" else "英语 · 对译释义",
            icon = Icons.Outlined.MenuBook,
            subtitle = eng.concept,
            rows = words.map { w ->
                DictRow(w.word, w.evolution.ifBlank { MISSING }, source = stripStars(w.origin).ifBlank { null })
            },
            emptyText = "$MISSING：本词库未收录该词释义。"
        )
    }

    // ---------------- 俄语 ----------------
    alignment.rusCluster?.let { rus ->
        val isPrimary = lang == Lang.RU
        val prefix = if (isPrimary) "俄语" else "俄语 · 对译词"
        val words = rus.words.take(limitPerSection)
        val hidden = rus.words.size - words.size

        cards += DictCard(
            id = "ru.modern",
            title = "$prefix · 标准音（CSR）",
            icon = Icons.Outlined.Public,
            subtitle = rus.concept,
            badge = "查表",
            rows = words.map { w ->
                DictRow(
                    label = w.word,
                    value = IpaDisplay.single(w.ipa),
                    source = "Wiktionary 俄语词条",
                    accent = "ru_standard",
                    kind = if (isRecorded(w.ipa)) DictSourceKind.LOOKUP else DictSourceKind.MISSING,
                    variants = IpaDisplay.variants(w.ipa)
                )
            },
            note = (if (hidden > 0) "共 ${rus.words.size} 项，已列前 ${words.size} 项。" else "") +
                "俄语 IPA 来自 Wiktionary/Wiktextract，是真实收录。",
            emptyText = "$MISSING：Wiktionary 未收录该词的俄语音标。"
        )

        cards += DictCard(
            id = "ru.gloss",
            title = if (isPrimary) "俄语 · 释义与对译" else "俄语 · 对译释义",
            icon = Icons.Outlined.Public,
            subtitle = rus.concept,
            rows = words.map { w ->
                DictRow(w.word, w.evolution.ifBlank { MISSING }, source = stripStars(w.origin).ifBlank { null })
            },
            emptyText = "$MISSING：本词库未收录该词释义。"
        )
    }

    // ---------------- 词源与来源（用户要求"必须可见的词语来源"） ----------------
    val etymSubtitle = when {
        etyWord == null -> null
        lang == Lang.EN -> "查询词：$etyWord"
        else -> "对应英文词「$etyWord」的词源（不是查询词本身）"
    }
    cards += DictCard(
        id = "etym",
        title = "词源与来源",
        icon = Icons.Default.AccountTree,
        subtitle = etymSubtitle,
        badge = if (families.isNotEmpty() || relations.isNotEmpty()) "查表" else null,
        rows = buildList {
            if (families.isNotEmpty()) {
                add(
                    DictRow(
                        label = "来源语族",
                        value = families.joinToString(" · "),
                        source = "EtymWN 源词语言码（查表）"
                    )
                )
            }
            relations.take(8).forEach { relation ->
                add(
                    DictRow(
                        label = relation.relation.removePrefix("rel:"),
                        value = relation.source,
                        source = "EtymWN（查表）"
                    )
                )
            }
        },
        note = if (relations.isNotEmpty()) {
            "来源语族由 EtymWN 的源词语言码归并（lat 拉丁 / fro 古法语 / grc 古希腊语 / ang 古英语…），" +
                "是查表结果，不是按词尾猜的。EtymWN 只提供关系三元组，不含词源叙述文本。"
        } else null,
        emptyText = when {
            etyWord == null -> "$MISSING：本查询语种没有 EtymWN 词源数据（EtymWN 只覆盖英语词）。"
            else -> "$MISSING：EtymWN 未收录「$etyWord」的词源派生关系（覆盖 38,237 词，并非全部）。"
        }
    )

    // ---------------- 形式变化（屈折） ----------------
    // 用户点名要看"形式变化"。数据来自 ECDICT 第 4 列的词形表
    // （s 复数 / p 过去式 / d 过去分词 / i 现在分词 / 3 第三人称单数 / r 比较级 / t 最高级），
    // 由 [Vocabulary.derivedForms] 解码成中文标签。这是**查表**结果；
    // 取不到就整张卡不显示（不编造形式）。这些屈折形式在词表里没有独立音标，
    // 因此**不给发音按钮**（宁可不给，也不做一个"听着像"的假音标）。
    if (etyWord != null) {
        val forms = runCatching { VocabIndex.derivedForms(etyWord) }.getOrDefault(emptyList())
        if (forms.isNotEmpty()) {
            cards += DictCard(
                id = "en.inflect",
                title = "形式变化（屈折）",
                icon = Icons.Outlined.Translate,
                subtitle = "词形：$etyWord",
                badge = "查表",
                rows = forms.map { DictRow(it.label, it.form, source = "ECDICT 词形表（查表）") },
                note = "码位含义：s 复数 / p 过去式 / d 过去分词 / i 现在分词 / " +
                    "3 第三人称单数 / r 比较级 / t 最高级。屈折形式在词表里没有独立音标，故不提供发音。"
            )
        }
    }

    // ---------------- 同义词（WordNet）：**按义项分组** ----------------
    //
    // 过去是一串扁平词表，"各个意思的同义词混在一起"。现在每个 WordNet synset（= 一个意思）
    // 单独成行：行首是词性，行的说明是该义项的英文释义并带组内序号，下面排该意思的同义词。
    // 同义词 chip 上带各自词表音标，点一下即可朗读该词。
    if (etyWord != null) {
        val senses = EtymologyDataIndex.senseGroups(etyWord)
        cards += DictCard(
            id = "syn",
            title = "同义词 · 按义项分组",
            icon = Icons.Outlined.MenuBook,
            subtitle = "目标词：$etyWord · ${senses.size} 个义项",
            badge = if (senses.isEmpty()) null else "查表",
            rows = senses.mapIndexed { index, group ->
                DictRow(
                    label = if (group.pos.isEmpty()) "义项" else GlossParser.displayLabel(group.pos),
                    // 其它消费者（思维导图）读 value，故仍给出可读的词串
                    value = group.members.joinToString(" · "),
                    source = "${index + 1}. ${group.gloss}",
                    accent = "en_modern",
                    kind = DictSourceKind.LOOKUP,
                    items = group.members.map { m ->
                        DictItem(word = m, ipa = VocabIndex.english(m)?.ipa?.trim().orEmpty())
                    }
                )
            },
            note = "每个义项一行，行的说明是 WordNet 自带的英文释义原样；" +
                "只列出词表内有音标的同词成员，不含查询词本身。同义词与上面的简体汉语义项编号**不同源**，请勿对号入座。",
            emptyText = "$MISSING：WordNet 未收录「$etyWord」的同义集成员（单成员 synset 如实为空，不拿近似词冒充）。"
        )
    }

    // ---------------- 数据来源与许可（把口径摆在明面上） ----------------
    cards += DictCard(
        id = "coverage",
        title = "数据来源与许可",
        icon = Icons.Outlined.Translate,
        subtitle = "界面每个数字的口径",
        rows = listOf(
            DictRow("汉语音韵", DictCoverage.HAN_SOURCES),
            DictRow("廣韻地位/反切", DictCoverage.HAN_MC_GUANGYUN),
            DictRow("中古拟音", DictCoverage.HAN_MC_RECON),
            DictRow("上古拟音", DictCoverage.HAN_OC_SCHOLARS),
            DictRow("英汉词表", DictCoverage.EN_LEXICON),
            DictRow("英语同义词", DictCoverage.EN_SYN),
            DictRow("词源关系", DictCoverage.ETYM),
            DictRow("俄语 IPA", DictCoverage.RU_IPA),
            DictRow("汉俄对译", DictCoverage.HAN_RU),
            DictRow("繁简", DictCoverage.TRAD_SIMP),
            DictRow("保守 RP", DictCoverage.NO_HIST_EN),
            DictRow("历史音试听", DictCoverage.HIST_AUDIO)
        ),
        note = "以上为各索引 KDoc 里登记的真实规模与许可；本库没有的字段一律显示「未收录」，不填猜测值。"
    )

    // ---------------- 排序：主语种在前，释义紧跟音标，参考资料最后 ----------------
    //
    // 卡片是**按语种分块造出来的**，但造出的顺序是"汉语块 → 英语块 → 俄语块"，
    // 查英语词时会先看到中古音、才看到自己查的词的音标与释义 —— 这就是"排列不符合逻辑"。
    // 这里按查询语种把主语种提到最前，再把释义、同义词、词源、数据来源依次排好。
    val order: List<String> = when (lang) {
        Lang.EN -> listOf(
            "gloss", "en.modern", "en.trad", "syn", "en.inflect", "en.gloss",
            "etym", "cmn.meaning", "cmn.modern", "cmn.mc", "cmn.oc",
            "ru.gloss", "ru.modern", "coverage"
        )
        Lang.RU -> listOf(
            "gloss", "ru.modern", "ru.gloss", "syn", "en.modern", "en.trad",
            "en.inflect", "en.gloss", "etym", "cmn.meaning", "cmn.modern",
            "cmn.mc", "cmn.oc", "coverage"
        )
        Lang.HAN -> listOf(
            "cmn.meaning", "cmn.modern", "cmn.mc", "cmn.oc",
            "en.modern", "en.trad", "gloss", "syn", "en.inflect", "en.gloss",
            "etym", "ru.modern", "ru.gloss", "coverage"
        )
    }
    cards.sortBy { card ->
        val i = order.indexOf(card.id)
        if (i < 0) Int.MAX_VALUE else i
    }

    return cards
}

// ============================================================================
// 卡片板
// ============================================================================

/**
 * 并列卡片板（两个入口共用）。
 *
 * @param collapsedIds 已折叠卡片 id 的逗号串，由调用方用 `rememberSaveable` 持有。
 * @param onToggleCard 点标题栏时回调，调用方用 [toggleCollapsedId] 更新 [collapsedIds]。
 * @param heroAction   词条头右下角的操作位（如收藏按钮）。
 * @param heroFooter   词条头底部的操作位（如"音标·词源（全量）"按钮）。
 * @param footer       卡片之后追加的整行内容（如"选词提问"与例句），与卡片同一滚动容器。
 */
@Composable
fun DictBoard(
    alignment: CrossLinguisticAlignment,
    query: String,
    speaker: (text: String, ipa: String, accent: String) -> Unit,
    collapsedIds: String,
    onToggleCard: (String) -> Unit,
    modifier: Modifier = Modifier,
    limitPerSection: Int = 6,
    onWordClick: ((String) -> Unit)? = null,
    onAskWord: ((title: String, question: String) -> Unit)? = null,
    heroAction: (@Composable () -> Unit)? = null,
    heroFooter: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val cards = remember(alignment, query, limitPerSection) {
        buildDictCards(alignment, query, limitPerSection)
    }
    val collapsed = remember(collapsedIds) { parseCollapsedIds(collapsedIds) }
    // 用户可强制单列宽读；默认按屏宽自动并列。跨配置变化保留。
    var singleColumn by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // "高效利用空间"：列数随可用宽度上升。
        val autoColumns = when {
            maxWidth < 300.dp -> 1
            maxWidth < 520.dp -> 2
            maxWidth < 780.dp -> 3
            else -> 4
        }
        val columns = if (singleColumn) 1 else autoColumns

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                DictHeroCard(
                    alignment = alignment,
                    query = query,
                    action = heroAction,
                    footer = heroFooter
                )
            }

            item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) {
                DictBoardToolbar(
                    cardCount = cards.size,
                    autoColumns = autoColumns,
                    singleColumn = singleColumn,
                    onSingleColumnChange = { singleColumn = it }
                )
            }

            cards.forEach { card ->
                item(
                    key = card.id,
                    // 通栏卡（简明释义）横贯整行：释义被切成窄列更难读
                    span = {
                        if (card.fullWidth) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    }
                ) {
                    DictCardView(
                        card = card,
                        expanded = card.id !in collapsed,
                        onToggle = { onToggleCard(card.id) },
                        speaker = speaker,
                        onWordClick = onWordClick,
                        onAskWord = onAskWord
                    )
                }
            }

            if (footer != null) {
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.fillMaxWidth()) { footer() }
                }
            }
        }
    }
}

/** 词条头（整行卡片）：词形 + 语种 + 数据口径 + 操作位。 */
@Composable
private fun DictHeroCard(
    alignment: CrossLinguisticAlignment,
    query: String,
    action: (@Composable () -> Unit)?,
    footer: (@Composable ColumnScope.() -> Unit)?
) {
    val lang = detectLang(query)
    val headword = alignment.canonicalZh.ifBlank { query.trim() }
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = headword,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Tag(langLabel(lang), MaterialTheme.colorScheme.secondaryContainer)
                if (action != null) {
                    Spacer(Modifier.width(2.dp))
                    action()
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = alignment.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (footer != null) {
                Spacer(Modifier.height(4.dp))
                footer()
            }
        }
    }
}

/** 并列度工具条：切换单列/并列，并显示卡片数。 */
@Composable
private fun DictBoardToolbar(
    cardCount: Int,
    autoColumns: Int,
    singleColumn: Boolean,
    onSingleColumnChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "并列度",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        FilterChip(
            selected = !singleColumn,
            onClick = { onSingleColumnChange(false) },
            label = { Text("并列 $autoColumns 列", fontSize = 11.sp) }
        )
        FilterChip(
            selected = singleColumn,
            onClick = { onSingleColumnChange(true) },
            label = { Text("单列宽读", fontSize = 11.sp) }
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$cardCount 张卡片",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 一张可折叠的并列卡片。 */
@Composable
private fun DictCardView(
    card: DictCard,
    expanded: Boolean,
    onToggle: () -> Unit,
    speaker: (text: String, ipa: String, accent: String) -> Unit,
    onWordClick: ((String) -> Unit)? = null,
    onAskWord: ((title: String, question: String) -> Unit)? = null
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "dictCardChevron"
    )
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // 标题栏：点它折叠/展开
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = card.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    card.subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                card.badge?.let { badge ->
                    DictBadge(badge, card.badgeKind)
                    Spacer(Modifier.width(2.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "折叠" else "展开",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevron),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(4.dp))

                if (card.rows.isEmpty()) {
                    Text(
                        text = card.emptyText ?: "$MISSING：本卡片无数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    card.rows.forEach { row ->
                        DictRowView(
                            row = row,
                            speaker = speaker,
                            onWordClick = onWordClick,
                            onAskWord = onAskWord
                        )
                    }
                }

                card.note?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/**
 * 一行紧凑键值对：标签行 + 取值行（音标等宽）。
 *
 * 若该行带 [DictRow.variants]（旧式混排记录并列的多个读音），**逐条分行**渲染，
 * 每行各自有发音按钮，发音时把该变体单独交给合成器 —— 不把 `puə. pɔː` 当成一整个串贴出来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictRowView(
    row: DictRow,
    speaker: (text: String, ipa: String, accent: String) -> Unit,
    onWordClick: ((String) -> Unit)? = null,
    onAskWord: ((title: String, question: String) -> Unit)? = null
) {
    val shown = if (row.variants.isNotEmpty()) row.variants else listOf(row.value)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tag(row.label)
            row.source?.let { src ->
                Spacer(Modifier.width(5.dp))
                Text(
                    text = src,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 成组词条（同义词按义项分组）：换成 chip 排布，支持点击打开反查、长按条畅提问
        if (row.items.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                row.items.forEach { item ->
                    DictItemChip(
                        item = item,
                        accent = row.accent,
                        speaker = speaker,
                        onWordClick = onWordClick,
                        onAskWord = onAskWord
                    )
                }
            }
            return@Column
        }

        shown.forEach { value ->
            val recorded = isRecorded(value)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (recorded) FontWeight.Medium else FontWeight.Normal,
                    color = if (recorded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                if (recorded && row.accent != null) {
                    IconButton(
                        onClick = { speaker(row.label, value, row.accent) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VolumeUp,
                            contentDescription = "发音",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 同义词分组里的一个词条 chip：词形 + 自身音标。
 *
 * 满足用户要求：
 * 1. 点击直接打开反查该词，深入查词；
 * 2. 长按可发起条畅提问；
 * 3. 带有独立发音按钮。
 */
@Composable
private fun DictItemChip(
    item: DictItem,
    accent: String?,
    speaker: (text: String, ipa: String, accent: String) -> Unit,
    onWordClick: ((String) -> Unit)? = null,
    onAskWord: ((title: String, question: String) -> Unit)? = null
) {
    val ipa = item.ipa.trim()
    val talkable = ipa.isNotEmpty() && accent != null
    var showMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.pointerInput(item.word) {
                detectTapGestures(
                    onTap = {
                        if (onWordClick != null) {
                            onWordClick(item.word)
                        } else if (talkable) {
                            speaker(item.word, ipa, accent)
                        }
                    },
                    onLongPress = {
                        showMenu = true
                    }
                )
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = item.word,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (ipa.isNotEmpty()) {
                        Text(
                            text = "/" + IpaDisplay.single(ipa) + "/",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                if (talkable) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { speaker(item.word, ipa, accent) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VolumeUp,
                            contentDescription = "发音",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("针对此词提问（条畅）", fontSize = 12.sp) },
                onClick = {
                    showMenu = false
                    onAskWord?.invoke(
                        item.word,
                        "请深入解析单词「${item.word}」的核心义项、语境用法、派生词族与同义辨析。"
                    )
                }
            )
            DropdownMenuItem(
                text = { Text("查看完整词条释义", fontSize = 12.sp) },
                onClick = {
                    showMenu = false
                    onWordClick?.invoke(item.word)
                }
            )
            DropdownMenuItem(
                text = { Text("复制单词", fontSize = 12.sp) },
                onClick = {
                    showMenu = false
                    clipboard.setText(AnnotatedString(item.word))
                }
            )
        }
    }
}

/** 来源角标：查表 / 规则推导 / 未收录。 */
@Composable
fun DictBadge(text: String, kind: DictSourceKind) {
    val container = when (kind) {
        DictSourceKind.LOOKUP -> MaterialTheme.colorScheme.surfaceContainerHighest
        DictSourceKind.DERIVED -> MaterialTheme.colorScheme.tertiaryContainer
        DictSourceKind.MISSING -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Tag(text, container)
}
