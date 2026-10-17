package com.treechat.workbench.etymology.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treechat.workbench.etymology.data.CrossLinguisticAlignment
import com.treechat.workbench.etymology.engine.IpaAudioSynthesizer
import kotlinx.coroutines.launch

/**
 * 词典三语共用的**基础件**（口径常量、语种判定、发音触发器、语音自检、标签）。
 *
 * 展示层（并列可展开卡片）自 2026-10 起统一在 [com.treechat.workbench.etymology.ui.DictBoard]，
 * 词典 Tab（`ui/dictionary/DictionaryScreen.kt`）与选词面板（[EtymoScopeBottomSheet]）
 * **共用同一套卡片**，不再各写一条纵向信息流。本文件只保留两边都要用的最小公共面。
 *
 * 设计约束不变：
 * - **零 emoji**，全部用 Material 矢量图标；
 * - 每个数据点标来源，查不到一律写"未收录"并说明覆盖范围，**绝不编造**。
 */
object DictCoverage {
    /** 以下数字均为各 data 索引 KDoc 里记录的真实规模，用于如实标注覆盖。 */
    const val HAN_PHONOLOGY = "白-沙《Old Chinese》2015 词表 · 中古转写/上古拟音 · 覆盖 3,955 字"
    const val HAN_MC_GUANGYUN = "廣韻音韻地位 + 反切 · 12,722 字（nk2028/tshet-uinh-data，CC0）"
    const val HAN_MC_RECON = "中古拟音（规则推导）：高本漢·王力·潘悟雲·unt·msoeg（tshet-uinh-examples，MIT）"
    const val HAN_OC_SCHOLARS = "上古拟音：王力·李方桂·郑张尚芳·许思莱 · 14,315 字（rephon，MIT）"
    const val HAN_SOURCES = "中古/上古音：白-沙 2015 为转写与拟音；廣韻音韻地位·反切与王力·李方桂·" +
        "郑张尚芳·许思莱等拟音来自标注的开源数据集（许可见各自索引 KDoc）；本库没有的字段一律标“未收录”"
    const val HAN_RU = "汉俄对译 149,678 条 · 词条级（英文 Wiktionary 按义项配对）"
    const val EN_LEXICON = "音标与释义：内置英汉词表（vocab2 13,806 + ECDICT 核心 56,992）"
    const val EN_SYN = "同义词：WordNet 3.0 · 23,857 词 / 42,514 个义项组（按 synset 分组）"
    const val ETYM = "词源派生关系：Etymological WordNet · 38,237 词"
    const val RU_IPA = "俄语 IPA：Wiktionary 俄语词条（Wiktextract）"
    const val TRAD_SIMP = "繁简：数据集按字头收录（简繁混排），无繁简互转数据源"
    const val NO_HIST_EN = "非词典数据：由现代 IPA 按音变规则推导的保守 RP 近似（规则推导，非真实音标）"
    const val HIST_EN_RULES = "规则：əʊ→ou、ɜː→əː、ɒ→ɔ/ɔː(CLOTH)、wh→ʍ、happy→ɪ 等（详见 RP_1910s_NOTES.md）"
    const val HIST_AUDIO = "历史音试听为共振峰合成近似，非录音"
}

/** 查询语的语种（与 [com.treechat.workbench.etymology.repository.EtymologyRepository.resolve] 的分派规则一致）。 */
enum class Lang { HAN, EN, RU }

/** 按字符集判断查询语种：先西里尔，再汉字，否则英语。 */
fun detectLang(query: String): Lang {
    val q = query.trim()
    if (q.any { it in '\u0400'..'\u04FF' }) return Lang.RU
    if (q.any { it in '\u4E00'..'\u9FA5' }) return Lang.HAN
    return Lang.EN
}

/** 语种徽标文案。 */
fun langLabel(lang: Lang): String = when (lang) {
    Lang.HAN -> "汉语"
    Lang.EN -> "英语"
    Lang.RU -> "俄语"
}

/** 该字段是否真的有数据（而不是"未收录"或"（本库未收…）"这类占位）。 */
internal fun isRecorded(value: String): Boolean =
    value.isNotBlank() && !value.contains("未收录") && !value.startsWith("（")

/**
 * 词源分区要展示的目标英文词：
 * 英语查询取查询词本身；其它语种取映射出的**首个英文词**（如实标注是"对应英文词"）。
 * 取不到就返回 null（分区里写"不适用"）。
 */
fun etyWordFor(query: String, alignment: CrossLinguisticAlignment): String? =
    if (detectLang(query) == Lang.EN) {
        query.trim().lowercase().filter { it.isLetter() || it == '-' || it == ' ' }.trim().ifBlank { null }
    } else {
        alignment.engCluster?.words?.firstOrNull()?.word
    }

/** 词源分区是否适用：有目标词、且（英语查询，或该查询确实映射出了英文词）。 */
fun etyApplicableFor(query: String, alignment: CrossLinguisticAlignment, etyWord: String?): Boolean =
    etyWord != null && (detectLang(query) == Lang.EN || alignment.engCluster != null)

/** 统一的发音触发器：轻触发声 + haptic；历史音用 [IpaAudioSynthesizer] 的共振峰合成降级。 */
@Composable
fun rememberSpeaker(
    onSpoken: () -> Unit = {}
): (text: String, ipa: String, accent: String) -> Unit {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    return { text, ipa, accent ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            IpaAudioSynthesizer.speak(text, ipa, accent)
            // 发音后回调一次，让界面刷新"实际用了哪支 voice / 有没有回落"。
            onSpoken()
        }
    }
}

/**
 * 系统语音自检：把**每种口音实际选中的 voice 与是否回落**如实摆出来。
 * 缺语音时不安静地拿美音冒充英音，而是写明"回落到 en-US（美音）"并给一条可操作的
 * "安装语音"入口（TTS 引擎的安装语音数据 → CHECK_TTS_DATA → 系统 TTS 设置页）。
 *
 * @param refreshKey 每次发音后自增，驱动重新读取 [IpaAudioSynthesizer] 的最新结论。
 */
@Composable
fun VoiceSelfCheck(refreshKey: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accents = listOf(
        "en_modern" to "英式英语",
        "cmn_modern" to "汉语 · 普通话",
        "ru_standard" to "俄语"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        accents.forEach { (accent, name) ->
            val status = remember(refreshKey, accent) { IpaAudioSynthesizer.lastStatusFor(accent) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (status.ok) Icons.Outlined.GraphicEq else Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (status.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$name：${status.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.ok) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                if (status.needsInstall) {
                    TextButton(onClick = {
                        // 逐个尝试可操作的入口，第一个能被系统接管的即用。
                        for (intent in IpaAudioSynthesizer.installVoiceIntents(accent)) {
                            try {
                                context.startActivity(intent)
                                break
                            } catch (_: Exception) {
                                // 试下一个入口
                            }
                        }
                    }) {
                        Text("安装语音", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** 小标签（来源 / 标签行 / 词源关系名）。 */
@Composable
fun Tag(text: String, container: androidx.compose.ui.graphics.Color? = null) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = container ?: MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
