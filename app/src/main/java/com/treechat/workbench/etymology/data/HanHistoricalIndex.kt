package com.treechat.workbench.etymology.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 汉语**中古 / 上古音韵扩充**索引（在 `han_phonology.tsv` 之外**并列**新增，绝不覆盖既有资产）。
 *
 * 两张资产各自只有一份真实来源，字段与许可逐条写死在这里，任何调用方都不许把
 * 甲家的拟音塞进乙家的字段：
 *
 * ### 1. `han_mc_guangyun.tsv` —— 中古（切韵/廣韻）
 * 列序：`字 \t 韻目 \t 音韻地位 \t 反切 \t 高本漢 \t 王力 \t 潘悟雲 \t unt \t msoeg`
 *
 * - **韻目 / 音韻地位 / 反切**：来源 **nk2028/tshet-uinh-data** 的 `韻書/廣韻.csv`
 *   （`https://github.com/nk2028/tshet-uinh-data`，**CC0-1.0**，可自由使用）。
 *   这是澤存堂本《廣韻》逐字条目的真实音韵地位与反切，**不是拟音、不是猜测**。
 *   多音字把各读音用 ` / ` 并列。
 * - **高本漢 / 王力 / 潘悟雲 / unt / msoeg 五栏拟音**：由 **nk2028/tshet-uinh-examples**
 *   的拟音推导器（`karlgren.js` / `wangli.js` / `panwuyun.js` / `unt.js` / `msoeg_v8.js`）按
 *   上表的「音韻地位」**逐条算出**（MIT 许可）。也就是说这几栏是"各家已发表的切韵拟音体系
 *   的规则化实现"，**不是**我从纸质书里逐条抄的 —— 界面与文档必须标"规则推导"。
 *   各栏保留了推导器自己的记音记号（如王力的 `꜀꜂꜄꜆` 调号、潘悟雲的 `˧˥˩` 调值），未做二次改写。
 *
 * ### 2. `han_oc_scholars.tsv` —— 上古（先秦）
 * 列序：`字 \t 王力 \t 李方桂 \t 郑张尚芳 \t 许思莱`
 *
 * 来源 **direct-phonology/rephon**（`https://github.com/direct-phonology/rephon`，**MIT**）
 * 已整理的各家上古拟音；rephon 的 `data/raw/source_statement.txt` 逐份记录了上游与许可：
 * - 王力：李珍華、周長楫《漢字古今音表·修訂本》（中華書局 1999）所载王力体系上古拟音；
 * - 李方桂：《上古音研究》体系上古拟音；
 * - 郑张尚芳：《上古音系》体系上古拟音；
 * - 许思莱（Schuessler）：Old Chinese Minimal reconstruction，原始数据由作者以 **CC0-1.0**
 *   发布在 Zenodo（DOI 10.5281/zenodo.14567004）。
 *
 * **许可提醒（如实登记）**：王力、李方桂两份 xlsx 由 rephon 转自**台湾中研院「小學堂」**
 * 的数字化成果；rephon 与「小學堂」文件编号体系均标注为 CC0-1.0，但「小學堂」官网本身
 * 声明"数据库整体受著作权保护、授权方式仍在研议"。故这两栏属于**许可存在歧义**的第三方
 * 整理数据，取用时必须在界面标出来源，**不得**当作本公司/本项目自有数据。
 *
 * 白-沙（Baxter-Sagart）一份仍在 `han_phonology.tsv` 里，本索引**不重复收录**，避免两处冲突。
 */
object HanHistoricalIndex {

    private const val TAG = "HanHistoricalIndex"

    /** 中古（廣韻 + 多家拟音）。 */
    private const val ASSET_MC = "han_mc_guangyun.tsv"

    /** 上古（王力 / 李方桂 / 郑张尚芳 / 许思莱）。 */
    private const val ASSET_OC = "han_oc_scholars.tsv"

    const val UNRECORDED = "未收录"

    /** 廣韻一条真实音韵记录 + 五家规则推导的中古拟音。 */
    data class McEntry(
        val rhyme: String,        // 廣韻韻目（如 質 / 耕）
        val position: String,     // 音韻地位（如 影開三A真入）：母·呼·等·類·韻·調
        val fanqie: String,       // 反切（如 於悉）
        val karlgren: String,     // 高本漢拟音（规则推导）
        val wangLi: String,       // 王力拟音（规则推导）
        val panWuyun: String,     // 潘悟雲拟音（规则推导）
        val unt: String,          // unt 拟音（规则推导）
        val msoeg: String         // msoeg 拟音（规则推导）
    )

    /** 上古四家拟音（真实整理数据）。 */
    data class OcEntry(
        val wangLi: String,       // 王力（上古）
        val liFangkuei: String,   // 李方桂（上古）
        val zhengzhang: String,   // 郑张尚芳（上古）
        val schuessler: String    // 许思莱 OCM（上古）
    )

    @Volatile
    private var mcMap: Map<String, McEntry> = emptyMap()

    @Volatile
    private var ocMap: Map<String, OcEntry> = emptyMap()

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
            val mc = HashMap<String, McEntry>(16_384)
            runCatching {
                context.assets.open(ASSET_MC).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val c = line.split('\t')
                        if (c.size < 9) continue
                        val ch = c[0].trim()
                        if (ch.length != 1) continue
                        mc[ch] = McEntry(
                            rhyme = c[1].trim().ifEmpty { UNRECORDED },
                            position = c[2].trim().ifEmpty { UNRECORDED },
                            fanqie = c[3].trim().ifEmpty { UNRECORDED },
                            karlgren = c[4].trim().ifEmpty { UNRECORDED },
                            wangLi = c[5].trim().ifEmpty { UNRECORDED },
                            panWuyun = c[6].trim().ifEmpty { UNRECORDED },
                            unt = c[7].trim().ifEmpty { UNRECORDED },
                            msoeg = c[8].trim().ifEmpty { UNRECORDED }
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET_MC 失败，中古廣韻与中古拟音将落空：$it") }

            val oc = HashMap<String, OcEntry>(16_384)
            runCatching {
                context.assets.open(ASSET_OC).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val c = line.split('\t')
                        if (c.size < 5) continue
                        val ch = c[0].trim()
                        if (ch.length != 1) continue
                        oc[ch] = OcEntry(
                            wangLi = c[1].trim().ifEmpty { UNRECORDED },
                            liFangkuei = c[2].trim().ifEmpty { UNRECORDED },
                            zhengzhang = c[3].trim().ifEmpty { UNRECORDED },
                            schuessler = c[4].trim().ifEmpty { UNRECORDED }
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET_OC 失败，上古多家拟音将落空：$it") }

            mcMap = mc
            ocMap = oc
            isReady = true
            Log.i(TAG, "历史音韵索引就绪：中古 ${mc.size} 字（廣韻音韻地位+5 家拟音），上古 ${oc.size} 字（4 家）")
        }
    }

    fun middleChinese(ch: Char): McEntry? = mcMap[ch.toString()]

    fun oldChinese(ch: Char): OcEntry? = ocMap[ch.toString()]
}
