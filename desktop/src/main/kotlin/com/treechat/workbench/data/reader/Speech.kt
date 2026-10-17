// 桌面替身：data.reader.Speaker（同签名，桌面用 say（macOS）/ espeak-ng 或 espeak（Linux））。
// 数据源 Handoff「安卓线增量」节 1：词条 UI 不动，靠这个实现接线。
package com.treechat.workbench.data.reader

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/** 平台隔离接口（共享契约，与安卓版同签名；词条 UI 不动）。 */
interface Speaker {
    fun speak(text: String)
    val ready: Boolean
}

/** 桌面实现：优先 macOS `say`，回落 Linux `espeak-ng`/`espeak`；都不在则静默。 */
class DesktopSpeaker(context: Context) : Speaker {
    @Volatile private var engine: String? = null
    private val lock = Any()

    init {
        thread(name = "speech-probe", isDaemon = true) {
            val picked = pickEngine()
            synchronized(lock) { engine = picked }
            if (picked == null) {
                System.err.println("[desktop-speaker] 未找到 say / espeak-ng / espeak，发词静默")
            }
        }
    }

    override val ready: Boolean
        get() = synchronized(lock) { engine != null }

    override fun speak(text: String) {
        if (text.isBlank()) return
        val cmd = synchronized(lock) { engine } ?: return
        thread(name = "speech-speak", isDaemon = true) {
            runCatching {
                when (cmd) {
                    "say" -> Runtime.getRuntime().exec(arrayOf("say", text)).waitFor()
                    else -> Runtime.getRuntime().exec(arrayOf(cmd, text)).waitFor()
                }
            }
        }
    }
}

/** 探测可用引擎（mac say 优先；Linux espeak-ng > espeak）。 */
private fun pickEngine(): String? {
    val candidates = listOf("say", "espeak-ng", "espeak")
    val pathDirs = (System.getenv("PATH") ?: "/usr/bin:/bin:/usr/local/bin")
        .split(File.pathSeparator)
    for (name in candidates) {
        if (pathDirs.any { dir -> File(dir, name).canExecute() }) return name
    }
    return null
}

/**
 * 充面别名：共享 ReaderSidePanel 直接 new AndroidSpeaker(context)（安卓#line），
 * 桌面上对应的字节级实现就是 say/espeak 链路 —— 同名类接管，词条 UI 不动。
 */
class AndroidSpeaker(context: Context) : Speaker by DesktopSpeaker(context)

