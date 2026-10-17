package com.treechat.workbench

import android.app.Application
import com.treechat.workbench.etymology.engine.IpaAudioSynthesizer
import com.treechat.workbench.etymology.data.EtymologyDataIndex
import com.treechat.workbench.etymology.data.HanHistoricalIndex
import com.treechat.workbench.etymology.data.HanPhonologyIndex
import com.treechat.workbench.etymology.data.RuPhonologyIndex
import com.treechat.workbench.etymology.data.VocabIndex
import com.treechat.workbench.etymology.data.ZhRuIndex
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 目前做四件事：
 *
 * 1. **在最早的时机安装崩溃日志处理器**。
 *
 * 为什么需要它：用户报告"切回对话会闪退"，但开发环境没有设备、拿不到 logcat，
 * 仅靠静态读代码定位崩溃已经失败过多轮。把堆栈落盘到
 * `Android/data/com.treechat.workbench/files/crashes/` 后，
 * 用户用任意文件管理器就能打开并复制出来，不必接 adb。
 *
 * 必须在 onCreate 里**第一时间**安装：晚于任何可能抛异常的业务初始化，
 * 那段初始化崩了就采集不到。
 *
 * 2. **预热内置词库索引**。`vocab2.tsv` / `vocab_ru.tsv` 共约 1.8 万行，
 * 解析必须离开主线程，且整个进程只需一次；词源模块的查询是同步的，
 * 所以在这里提前读完、常驻内存。
 *
 * 3. **初始化语音合成**（现代普通话 / 英式英语 / 俄语走系统 TTS，中古·上古汉语
 * 与保守 RP 走本地共振峰合成）。
 *
 * 4. **初始化 PDFBox 资源**，否则 PDF 文本层抽不出来，导入会白白回落到整本 OCR。
 *
 * 注：本地大模型（llama.cpp / LiteRT / Vulkan）方案已按用户要求整体移除，
 * 因此这里不再有 [com.treechat.workbench.engine.local.LlamaLogManager] 与
 * `ReaderAppContext` 的初始化。
 */
class TreeChatApp : Application() {

    /** Application 活到进程结束，这里不需要取消。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // 必须在这里初始化：不 init 的话 tts 恒为 null，
        // 现代普通话/英音/俄语会被迫全部回落到共振峰合成（听感是机械音而非朗读）。
        IpaAudioSynthesizer.init(this)
        // 阅读器排版（字号 / 行距 / 护眼底色）：进程内读一次 SharedPreferences。
        // 不在这里读，正文第一次读到的是默认值，用户上次调好的排版要再打开一次面板才回来。
        com.treechat.workbench.ui.reader.ReaderTypographyStore.init(this)
        // PDFBox 的资源（glyphlist / AFM / 内置字体）从 assets 读取，必须先初始化；
        // 不初始化时 rag/PdfTextExtractor 会抽不到文本层，PDF 导入会白白回落到整本 OCR。
        // 进程级一次即可，放在这里不影响启动速度（只是赋值一个静态引用）。
        PDFBoxResourceLoader.init(this)
        ioScope.launch {
            VocabIndex.ensureLoaded(this@TreeChatApp)
            HanPhonologyIndex.ensureLoaded(this@TreeChatApp)
            HanHistoricalIndex.ensureLoaded(this@TreeChatApp)
            RuPhonologyIndex.ensureLoaded(this@TreeChatApp)
            ZhRuIndex.ensureLoaded(this@TreeChatApp)
            EtymologyDataIndex.ensureLoaded(this@TreeChatApp)
        }
    }
}
