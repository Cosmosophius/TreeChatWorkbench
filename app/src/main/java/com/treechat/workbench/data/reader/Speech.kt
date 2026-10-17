package com.treechat.workbench.data.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 词条发音的**平台隔离层**。
 *
 * ## 为什么抽接口
 * 发词是 Android 专属能力（TextToSpeech）。Linux/macOS 移植时只需要给
 * [Speaker] 一个桌面实现（`say` / `speech-dispatcher` / espeak），
 * 词条 UI 一行都不用动 —— 平台差异被关在这一个接口后面。
 *
 * 桌面实现由 desktop-port 会话按同一签名补齐；本文件里的 Android 实现保持唯一。
 */
interface Speaker {
    /** 念一段文本（词条页用它读词头；长句也允许，引擎自己截）。 */
    fun speak(text: String)

    /** 引擎是否已就绪（未就绪时按钮应禁用或静默忽略）。 */
    val ready: Boolean
}

/** Android 实现：系统 TextToSpeech，英文语音读词头。 */
class AndroidSpeaker(context: Context) : Speaker {

    private val engine = TextToSpeech(context.applicationContext) { status ->
        readyState = status == TextToSpeech.SUCCESS
    }
    @Volatile private var readyState = false

    override val ready: Boolean get() = readyState

    override fun speak(text: String) {
        if (!readyState || text.isBlank()) return
        engine.apply {
            runCatching { language = Locale.US }
            setSpeechRate(0.95f)
            speak(text, TextToSpeech.QUEUE_FLUSH, null, "treechat-entry")
        }
    }
}
