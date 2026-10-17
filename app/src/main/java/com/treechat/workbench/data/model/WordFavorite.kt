package com.treechat.workbench.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 词典条目的语种归属。
 *
 * 存库时用 [name] 落到 `word_favorites.kind` 这一列（TEXT），
 * 上层读写都经这个枚举，避免各处手写字符串拼错（"HANZI"/"hanzi" 混用会让
 * 「同一个词是不是已收藏」的判断失灵）。
 */
enum class WordKind {
    /** 汉字（拼音 / 中古上古音韵）。 */
    HANZI,

    /** 英语单词（含真实音标与词形派生）。 */
    EN,

    /** 俄语单词（Wiktionary 真实 IPA）。 */
    RU;

    companion object {
        /** 按字符集判定一个查询词属于哪个语种。与 EtymologyRepository 的分派规则一致。 */
        fun detect(query: String): WordKind {
            val q = query.trim()
            if (q.any { it in '\u0400'..'\u04FF' }) return RU
            if (q.any { it in '\u4E00'..'\u9FA5' }) return HANZI
            return EN
        }

        /** 宽松解析：库里存的是字符串，读到未知值时回落到 [EN]，不抛异常。 */
        fun fromName(raw: String): WordKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: EN
    }
}

/**
 * 单词本收藏（词典模块的收藏夹）。
 *
 * ## 为什么要缓存拼音/音标/释义
 * 收藏夹要在**离线**状态下用 [com.treechat.workbench.ui.components.SwipeableCardStack]
 * 展示成一大张卡片（完整释义 + 音标）。若只存一个词、展示时再回查词库，
 * 一旦词库资源缺失或某个词后来查不到，卡片就会变成空白。
 * 收藏的那一刻这些值本来就在手上，顺手落库即可，不必二次查。
 *
 * ## 为什么 id 用「kind:word」而不是随机 UUID
 * 同一个词重复点收藏应当是**幂等地覆盖同一条**，而不是插出第二条。
 * 用确定性主键 + REPLACE，语义天然就是「一个词的收藏只有一条」，
 * 也不需要额外维护唯一索引。
 */
@Entity(
    tableName = "word_favorites",
    indices = [Index(value = ["word"])]
)
data class WordFavorite(
    @PrimaryKey
    val id: String,

    /** 被收藏的词（汉字 / 英文词 / 俄文词）。 */
    val word: String,

    /** 语种，取 [WordKind.name]。 */
    val kind: String,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),

    /** 缓存：汉字拼音（其它语种为空）。 */
    val pinyin: String? = null,

    /** 缓存：音标（汉字普通话 IPA / 英语音标 / 俄语 IPA）。 */
    val ipa: String? = null,

    /** 缓存：释义/概念摘要，供离线卡片展示。 */
    val gloss: String? = null
) {
    companion object {
        /** 确定性主键：一个 (语种, 词) 只会有一条收藏。 */
        fun keyOf(word: String, kind: WordKind): String = "${kind.name}:${word.trim()}"
    }
}

/**
 * 单词例句。
 *
 * ## 为什么同一个词允许多条
 * 用户对同一个词可以反复点「生成例句」，每次得到一句不同的用法。
 * 这是**追加**语义，不是覆盖 —— 主键是随机 UUID，`word` 上建普通索引，
 * 按词查询即可取回该词的全部例句（按生成时间升序）。
 */
@Entity(
    tableName = "word_examples",
    indices = [Index(value = ["word"])]
)
data class WordExample(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** 归属的词。 */
    val word: String,

    /** 例句原文（英语/俄语；汉字词则是包含该字的例句）。 */
    val text: String,

    /** 例句的中文翻译。 */
    val translation: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
