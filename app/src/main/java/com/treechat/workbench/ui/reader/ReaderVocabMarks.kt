package com.treechat.workbench.ui.reader

import com.treechat.workbench.etymology.data.VocabIndex

/**
 * 阅读器「生词匹配」的纯函数层。
 *
 * ## 口径
 * 整页文本按英文词形扫描，逐词查 [VocabIndex] 的考纲标签列；
 * 词的标签与**当前选中的考纲类目**有交集才算生词。查不到原形的不标
 * （派生变形 don't/running 不展开 —— 数据源没有变形表参与匹配，宁可少标不误标）。
 */

/** 一处生词命中（原文整词下标）。 */
data class VocabHit(
    val word: String,
    /** 原文里这个整词的起始下标（左闭右开按 [endExclusive] 记）。 */
    val start: Int,
    val endExclusive: Int,
    /** 该词命中的考纲 token（用于侧栏展示）。 */
    val tags: List<VocabIndex.ExamCategory>
)

private val ENGLISH_WORD_RE = Regex("[A-Za-z][A-Za-z'’\\-]*")

/**
 * 在 [text] 里找考纲生词的全部出现位置。
 *
 * @param activeCategories 当前启用的考纲类目（token 集合）；空集合 = 用户已关闭考纲。
 * @param tagsOf 词 → 标签（由调用方从 VocabIndex 取，便于注入缓存）。
 */
fun findVocabHits(
    text: String,
    activeCategories: Set<String>,
    tagsOf: (word: String) -> Set<String>
): List<VocabHit> {
    if (text.isEmpty() || activeCategories.isEmpty()) return emptyList()
    val out = ArrayList<VocabHit>()
    for (m in ENGLISH_WORD_RE.findAll(text)) {
        val word = m.value
        val lower = word.lowercase()
        val tags = tagsOf(lower)
        val hit = tags.filter { it in activeCategories }
        if (hit.isNotEmpty()) {
            out.add(
                VocabHit(
                    word = word,
                    start = m.range.first,
                    endExclusive = m.range.last + 1,
                    tags = VocabIndex.EXAM_CATEGORIES.filter { it.token in hit }
                )
            )
        }
    }
    return out
}

/**
 * OCR 词条命中：把考纲生词落到**词盒 id** 列表。
 *
 * 判据与 [findVocabHits] 同一数据源（VocabIndex 的标签列），只是单位变成词盒：
 * 一个词盒内只要有一个整词命中即整盒命中（词条里的词框基本就是整词；
 * 偶尔多词混在一盒时整盒标注 —— 少标不误标，宁可窄一点）。
 */
fun findVocabBoxes(
    boxes: List<OcrWordBox>,
    activeCategories: Set<String>
): List<String> {
    if (boxes.isEmpty() || activeCategories.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    boxes.forEach { box ->
        val hit = ENGLISH_WORD_RE.findAll(box.text).any { m ->
            VocabIndex.examTags(m.value).any { it in activeCategories }
        }
        if (hit) out.add(box.id)
    }
    return out
}

/**
 * 侧边栏「生词」页要展示的**去重**列表（同一词只出现一次，保留首次位置）。
 */
fun dedupeVocabHits(hits: List<VocabHit>): List<VocabHit> {
    val seen = HashSet<String>()
    return hits.filter { seen.add(it.word.lowercase()) }
}
