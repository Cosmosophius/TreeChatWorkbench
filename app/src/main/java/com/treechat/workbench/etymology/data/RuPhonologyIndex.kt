package com.treechat.workbench.etymology.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 俄语（当代标准俄语 CSR）IPA 索引。
 *
 * 数据来源：`assets/ru_ipa.tsv`，从 **Wiktionary 的俄语词条提取**（Wiktextract 的
 * `kaikki.org` 俄语转储）只抽「俄文词 + IPA」两列，属于词典级真实音标 ——
 * 含重音符号 ˈ、软辅音颚化 ʲ、元音弱化（o/a→ɐ、e/я→ɪ）与词尾清化。
 *
 * 查表键做归一：小写 + 去掉所有空白。原因是 `vocab_ru.tsv` 里的词形带空格
 * （如 `cимпати чный`），而 Wiktionary 的词形不带，两者必须能对上。
 */
object RuPhonologyIndex {

    private const val TAG = "RuPhonologyIndex"
    private const val ASSET = "ru_ipa.tsv"

    const val UNRECORDED = "未收录"

    @Volatile
    private var map: Map<String, String> = emptyMap()

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
            val m = HashMap<String, String>(24_000)
            runCatching {
                context.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val col = line.split('\t')
                        if (col.size < 2) continue
                        val word = col[0].trim()
                        val ipa = col[1].trim()
                        if (word.isEmpty() || ipa.isEmpty()) continue
                        m[key(word)] = ipa
                    }
                }
            }.onFailure { Log.w(TAG, "读取 $ASSET 失败，俄语音标将全部落空：$it") }
            map = m
            isReady = true
            Log.i(TAG, "俄语音标索引就绪：${m.size} 词")
        }
    }

    /** 查音标；查不到返回 null（调用方按"未收录"处理，不要编）。 */
    fun ipaOf(word: String): String? = map[key(word)]

    private fun key(word: String): String =
        word.lowercase().filterNot { it.isWhitespace() }
}
