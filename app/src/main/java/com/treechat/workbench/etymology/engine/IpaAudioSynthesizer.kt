package com.treechat.workbench.etymology.engine

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 三语倍速发音引擎（IpaAudioSynthesizer）。
 *
 * 两条路：
 * 1. **系统 TTS**（[speakWithTts]）：现代英语（en-GB）、现代标准俄语（ru-RU）、普通话（zh-CN）。
 *    每次调用显式选 voice，命中的是哪一支、有没有回落、缺不缺语音，都如实记进 [VoiceStatus]。
 * 2. **共振峰物理合成**（[FormantSynth]）：中古汉语、上古汉语、1910s~1940s 保守 RP 贵族音，
 *    以及**系统缺 voice 时**给现代语言的兜底音频。
 *
 * 三条硬性约束（对应本轮修复）：
 * - 历史音（中古/上古 IPA）**必须有音频**：符号表 [HistoricalIpaPhoneMap] 覆盖拟音里会出现的一切
 *   符号，未知符号走最近邻回退并如实上报，绝不静默丢字符导致"无音频"。
 * - 保守 RP **不得爆音**：见 [FormantSynth] 的相位连续 / 段间平滑 / 软膝限幅 / 直流阻断。
 * - 俄语**优先俄语原生 TTS**；确无俄语 voice 时，既明确提示"缺俄语语音、点此安装"，
 *   又给出本地合成替代音（并在状态里标明"这是本地合成，不是系统俄语语音"）。
 */
object IpaAudioSynthesizer {

    private const val TAG = "IpaAudioSynthesizer"

    @Volatile
    var speechRate: Float = 1.0f

    private var tts: TextToSpeech? = null

    @Volatile
    private var isTtsReady = false

    /** TTS 初始化完成信号：未就绪时点击发音要能等待/重试，而不是直接失败。 */
    @Volatile
    private var readyLatch: CountDownLatch? = null

    private var appContext: Context? = null

    /** 界面自检结论：界面据此**如实**展示"这一口音实际用了哪支 voice、有没有回落"。 */
    data class VoiceStatus(
        val accentKey: String,
        val requested: String,
        val used: String,
        val ok: Boolean,
        val fellBack: Boolean,
        val message: String,
        val needsInstall: Boolean
    )

    /**
     * 一次合成的自检结论（历史音 / 降级音用）。
     * [unknownSymbols] 是**被最近邻回退替代的原始符号**（去重）——界面以后可据此提示
     * "本串里有 N 个符号没有专门建模"。绝不静默。
     */
    data class SynthReport(
        val accentKey: String,
        val input: String,
        val phoneCount: Int,
        val durationMs: Int,
        val peak: Double,
        val rms: Double,
        val dcOffset: Double,
        val maxJump: Double,
        val unknownSymbols: List<String>,
        val toneName: String,
        val toneRecognized: Boolean
    )

    private val TARGETS: Map<String, Triple<String, String, String>> = mapOf(
        "en_modern" to Triple("en", "GB", "英式英语"),
        "ru_standard" to Triple("ru", "RU", "俄语"),
        "cmn_modern" to Triple("zh", "CN", "汉语普通话")
    )

    @Volatile
    private var voices: List<Voice> = emptyList()

    @Volatile
    private var lastSpoken: Map<String, VoiceStatus> = emptyMap()

    @Volatile
    private var lastSynth: Map<String, SynthReport> = emptyMap()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts == null) {
            val latch = CountDownLatch(1)
            readyLatch = latch
            tts = TextToSpeech(context.applicationContext) { status ->
                isTtsReady = (status == TextToSpeech.SUCCESS)
                if (isTtsReady) {
                    refreshVoices()
                    Log.i(TAG, "系统 TTS 就绪，可用 voice ${voices.size} 支（en-GB 命中 ${hasCountry("en", "GB")}，ru 命中 ${hasLanguage("ru")}）")
                } else {
                    Log.w(TAG, "系统 TTS 初始化失败，状态码 $status")
                }
                latch.countDown()
            }
        }
    }

    /**
     * 等待 TTS 就绪；未就绪时最多等 [timeoutMs]，仍不成就尝试重建一次引擎再等一程。
     * 用于"onInit 还没回调就点了发音"的场景。返回最终是否就绪。
     */
    private fun awaitTtsReady(timeoutMs: Long = 1200L): Boolean {
        if (isTtsReady) return true
        readyLatch?.let {
            runCatching { it.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }
        if (isTtsReady) return true
        // 再重建一次（首次构建失败/被系统回收时有效）
        val ctx = appContext
        if (!isTtsReady && ctx != null && tts == null) {
            init(ctx)
            readyLatch?.let { runCatching { it.await(800L, TimeUnit.MILLISECONDS) } }
        }
        return isTtsReady
    }

    private fun refreshVoices() {
        voices = runCatching { tts?.voices?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    private fun allVoices(): List<Voice> {
        if (voices.isEmpty()) refreshVoices()
        return voices
    }

    private fun hasCountry(lang: String, country: String): Boolean =
        allVoices().any { it.locale.language.equals(lang, true) && it.locale.country.equals(country, true) }

    private fun hasLanguage(lang: String): Boolean =
        allVoices().any { it.locale.language.equals(lang, true) }

    /** 一支 voice 的可读标签：`en-GB / en-gb-x-rp`。 */
    private fun label(v: Voice?): String {
        if (v == null) return "无可用语音"
        val tag = v.locale.toLanguageTag()
        return if (v.name.isNullOrBlank()) tag else "$tag / ${v.name}"
    }

    private fun countryHuman(country: String): String = when (country.uppercase()) {
        "GB" -> "英式英语 (en-GB)"
        "US" -> "美式英语 (en-US)"
        "RU" -> "俄语 (ru-RU)"
        "CN" -> "汉语普通话 (zh-CN)"
        else -> "$country"
    }

    private data class Pick(
        val voice: Voice?,
        val label: String,
        val ok: Boolean,
        val fellBack: Boolean,
        val needsInstall: Boolean,
        val message: String
    )

    /**
     * 显式选 voice，判定链（越靠前越优先）：
     * 1. 语言 + 国家精确命中（如 ru-RU）
     * 2. 同语言任意 voice 兜底（如 ru-KZ、ru 无国家）——仍是**原生俄语**，如实说明不是 ru-RU
     * 3. 都没有 → 无 voice，`needsInstall=true`
     */
    private fun pickVoice(lang: String, country: String): Pick {
        val vs = allVoices()
        if (vs.isEmpty()) {
            return Pick(
                null, "无可用语音", false, false, true,
                "系统文字转语音引擎未报告任何可用语音，无法用系统语音朗读该语言。"
            )
        }
        val exact = vs.firstOrNull {
            it.locale.language.equals(lang, true) && it.locale.country.equals(country, true)
        }
        if (exact != null) {
            return Pick(exact, label(exact), true, false, false, "已用目标语音：${label(exact)}")
        }
        // 同语言兜底（哪怕 country 不是目标国家）
        val sameLang = vs.filter { it.locale.language.equals(lang, true) }
        val target = countryHuman(country)
        if (sameLang.isNotEmpty()) {
            val fb = sameLang.first()
            val caveat = when {
                lang.equals("en", true) && fb.locale.country.equals("US", true) -> "（这是美音，不是英音）"
                else -> "（同语言，但不是 $target 的地域）"
            }
            return Pick(
                fb, label(fb), false, true, false,
                "系统未安装$target 语音，当前用同语言的 ${label(fb)}$caveat"
            )
        }
        return Pick(
            null, "无 $lang voice", false, false, true,
            "系统未安装$target 语音，请到系统设置安装该语言（$lang）的语音数据。"
        )
    }

    private fun Pick.toStatus(accent: String, human: String): VoiceStatus = VoiceStatus(
        accentKey = accent,
        requested = human,
        used = label,
        ok = ok,
        fellBack = fellBack,
        message = message,
        needsInstall = needsInstall
    )

    /** 界面用：某口音**当前**的语音自检结论（不需要真的发音）。 */
    fun statusFor(accent: String): VoiceStatus {
        val t = TARGETS[accent] ?: return VoiceStatus(
            accent, accent, "本地共振峰合成", true, false,
            "该口音由内置共振峰合成器生成，不使用系统 TTS（无对应语音可安装）。", false
        )
        if (!isTtsReady) {
            return VoiceStatus(
                accent, "${t.third} (${t.first}-${t.second})", "系统 TTS 未就绪", false, false,
                "系统文字转语音引擎尚未就绪；发音时会自动等待/重试，仍不可用则回落到本地共振峰合成（会如实标注）。",
                false
            )
        }
        return pickVoice(t.first, t.second).toStatus(accent, "${t.third} (${t.first}-${t.second})")
    }

    fun lastStatusFor(accent: String): VoiceStatus = lastSpoken[accent] ?: statusFor(accent)

    /** 最近一次**合成**（历史音/降级音）的自检结论；没合成过则返回 null。 */
    fun lastSynthReportFor(accent: String): SynthReport? = lastSynth[accent]

    /**
     * 可操作的"去装语音"入口，按优先级返回：TTS 引擎的安装语音数据活动 →
     * 带目标语言的 CHECK_TTS_DATA → 系统 TTS 设置页。界面挑第一个能 startActivity 的用。
     */
    fun installVoiceIntents(accent: String): List<Intent> {
        val t = TARGETS[accent] ?: return emptyList()
        val locale = Locale(t.first, t.second)
        val out = ArrayList<Intent>(3)
        out += Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        out += Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            .putExtra(TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR, locale.toLanguageTag())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        out += Intent("com.android.settings.TTS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return out
    }

    /**
     * 播放发音主入口。
     * @param word 原文词语
     * @param ipa IPA / 拟音串
     * @param accent "cmn_modern", "cmn_middle", "cmn_old", "en_modern", "en_trad", "ru_standard"
     * @param rateOverride 独立倍速（若传入则优先使用）
     */
    suspend fun speak(
        word: String,
        ipa: String,
        accent: String,
        rateOverride: Float? = null
    ) = withContext(Dispatchers.Default) {
        val rate = rateOverride ?: speechRate

        when (accent) {
            "cmn_modern" -> if (!speakWithTts(word, "cmn_modern", rate)) {
                markLocalFallback("cmn_modern", "汉语普通话", "系统无汉语普通话语音")
                synthesize("cmn_modern", ipa, rate)
            }

            // 中古广韵音：物理共振峰还原（声调由串内调号/调符/Baxter H·X 决定）
            "cmn_middle" -> synthesize("cmn_middle", ipa, rate)

            // 上古周秦古音
            "cmn_old" -> synthesize("cmn_old", ipa, rate)

            "en_modern" -> if (!speakWithTts(word, "en_modern", rate)) {
                markLocalFallback("en_modern", "英式英语", "系统无英式英语语音")
                synthesize("en_modern", ipa, rate)
            }

            // 1910s~1940s 传统贵族英音 (Conservative RP)：无对应 TTS 语音，只能用共振峰合成
            "en_trad" -> synthesize("en_trad", ipa, rate)

            // 当代标准俄语 (CSR)：优先系统原生 ru-RU TTS
            "ru_standard" -> if (!speakWithTts(word, "ru_standard", rate)) {
                markLocalFallback("ru_standard", "俄语", "系统无俄语原生语音")
                synthesize("ru_standard", ipa, rate)
            }

            else -> synthesize(accent, ipa, rate)
        }
    }

    /**
     * 记录"这一口音退到了本地共振峰合成"。
     * **两个都要给**：既明确缺哪种语音（[VoiceStatus.needsInstall] 引导安装），
     * 又说明播放的是本地合成音（不是系统原生语音）。
     */
    private fun markLocalFallback(accent: String, human: String, reason: String) {
        // 保留 TTS 侧已推断出的更具体的信息（若可用）
        val prior = lastSpoken[accent]
        val detail = prior?.message?.takeIf { it.isNotBlank() } ?: reason
        lastSpoken = lastSpoken + (accent to VoiceStatus(
            accentKey = accent,
            requested = human,
            used = "本地共振峰合成（不是系统语音）",
            ok = false,
            fellBack = true,
            message = "$detail。已改用**本地共振峰合成**发声（不是系统原生语音）；点『安装语音』可安装系统语音。",
            needsInstall = true
        ))
    }

    /**
     * 用系统 TTS 发音：**每次调用都先显式选 voice**，再 setVoice + setLanguage，
     * 最后 setSpeechRate 并 speak。返回 false 表示这台机器确实没有该语言的语音
     * （调用方据此回落到共振峰合成，绝不假装成功）。
     */
    private fun speakWithTts(text: String, accent: String, rate: Float): Boolean {
        val target = TARGETS[accent] ?: return false

        // onInit 未回调就点了发音：等待/重试
        if (!awaitTtsReady()) {
            Log.w(TAG, "TTS 未就绪（等待超时），回落本地合成")
            return false
        }

        val engine = tts ?: return false
        val pick = pickVoice(target.first, target.second)
        lastSpoken = lastSpoken + (accent to pick.toStatus(accent, "${target.third} (${target.first}-${target.second})"))
        val chosen = pick.voice
        if (chosen == null) {
            // 连同语言的 voice 都没有：再用 Locale 问一次语言可用性，不行就如实失败。
            val loc = Locale(target.first, target.second)
            val available = runCatching { engine.isLanguageAvailable(loc) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (available < TextToSpeech.LANG_AVAILABLE) {
                Log.w(TAG, "无 ${target.third} 语音（isLanguageAvailable=$available），回落共振峰合成")
                return false
            }
        }
        return try {
            if (chosen != null) {
                engine.voice = chosen           // 先显式选定 voice
                engine.language = chosen.locale // 再用同一 locale 确认 language
                Log.i(TAG, "$accent -> ${label(chosen)}")
            } else {
                engine.language = Locale(target.first, target.second)
            }
            engine.setSpeechRate(rate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "etym_${System.currentTimeMillis()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TTS 播放异常: ${e.message}")
            false
        }
    }

    // ========================================================================
    // 共振峰合成路径
    // ========================================================================

    private fun effectiveTone(
        accent: String,
        parsed: HistoricalIpaPhoneMap.Tone,
        ipa: String
    ): HistoricalIpaPhoneMap.Tone {
        if (parsed.recognized) return parsed
        // 非声调语言的默认语调轮廓（不足以声调语言看待，仅作可听语调）
        //
        // 点数与 HistoricalIpaPhoneMap 的五点曲线看齐：render 里按整句时长线性
        // 插值，点越多、跨音段衔接越平滑。这里的语调本就是示意性的默认轮廓，
        // 多给两个过渡点不引入任何新断言。
        return when (accent) {
            "en_trad" -> HistoricalIpaPhoneMap.Tone(
                "贵族腔滑降（默认）", doubleArrayOf(148.0, 145.5, 143.0, 137.5, 132.0, 124.0, 118.0), false, "", false
            )
            "en_modern" -> HistoricalIpaPhoneMap.Tone(
                "英语陈述（默认）", doubleArrayOf(150.0, 147.5, 145.0, 141.5, 138.0), false, "", false
            )
            "ru_standard" -> HistoricalIpaPhoneMap.Tone(
                "俄语陈述（默认）", doubleArrayOf(150.0, 148.0, 146.0, 142.0, 138.0), false, "", false
            )
            "cmn_old" -> HistoricalIpaPhoneMap.Tone(
                "上古默认平调", doubleArrayOf(150.0, 149.0, 148.0, 146.5, 145.0), false, "", false
            )
            else -> parsed
        }
    }

    /**
     * 合成并播放一段历史/降级音频。**任何非空输入都能出声**：
     * 解析结果至少含一个音段；未知符号走最近邻回退并记入 [SynthReport.unknownSymbols]。
     */
    private fun synthesize(accent: String, ipa: String, rate: Float) {
        val parsed = HistoricalIpaPhoneMap.parse(ipa)
        val tone = effectiveTone(accent, parsed.tone, ipa)
        val out = FormantSynth.render(parsed.phones, tone, rate)

        lastSynth = lastSynth + (accent to SynthReport(
            accentKey = accent,
            input = ipa,
            phoneCount = parsed.phones.size,
            durationMs = out.durationMs,
            peak = out.metrics.peak,
            rms = out.metrics.rms,
            dcOffset = out.metrics.dcOffset,
            maxJump = out.metrics.maxJump,
            unknownSymbols = parsed.unknownSymbols,
            toneName = tone.name,
            toneRecognized = tone.recognized
        ))
        Log.i(
            TAG,
            "合成 $accent: 音段=${parsed.phones.size} 时长=${out.durationMs}ms 峰值=${"%.3f".format(out.metrics.peak)} " +
                "RMS=${"%.3f".format(out.metrics.rms)} 最大跳变=${"%.3f".format(out.metrics.maxJump)} " +
                "未知符号=${parsed.unknownSymbols} 声调=${tone.name}"
        )
        playPcmDirect(out.pcm)
    }

    // ========================================================================
    // 音频焦点 + AudioTrack 流式播放
    // ========================================================================

    private fun requestAudioFocus(attrs: AudioAttributes): AudioFocusRequest? {
        val ctx = appContext ?: return null
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else null
        } catch (t: Throwable) {
            Log.w(TAG, "申请音频焦点失败（忽略）：$t")
            null
        }
    }

    private fun abandonAudioFocus(req: AudioFocusRequest?) {
        val ctx = appContext ?: return
        if (req == null) return
        try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req)
        } catch (_: Throwable) {
        }
    }

    private val playLock = Any()

    @Volatile
    private var activeTrack: AudioTrack? = null

    /**
     * 用 AudioTrack **流式**播放（MODE_STREAM + 分块阻塞写）。
     * 相比旧版的 MODE_STATIC 一次性写：不受静态缓冲上限限制、不会因超限而无声，
     * 且写指针只覆盖已算满的样本（缓冲区全部计算填充，无未初始化/旧数据）。
     */
    private fun playPcmDirect(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = requestAudioFocus(attrs)
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                FormantSynth.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) throw IllegalStateException("getMinBufferSize 返回 $minBuf")
            val bufferBytes = maxOf(minBuf, FormantSynth.SAMPLE_RATE / 10 * 2) // ≈100ms

            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(FormantSynth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack 未能初始化")
            }
            // 新的播放抢占旧的（旧 track stop 会让其阻塞写返回错误而退出）
            synchronized(playLock) {
                activeTrack?.let { runCatching { it.stop(); it.release() } }
                activeTrack = track
            }
            val me = track
            track.play()
            var off = 0
            while (off < pcm.size) {
                val w = me.write(pcm, off, minOf(4096, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                if (w <= 0) break
                off += w
            }
            // 让缓冲排空后释放
            val remainMs = ((pcm.size - off).toLong() * 1000L / FormantSynth.SAMPLE_RATE) + 120L
            Thread {
                try {
                    Thread.sleep(remainMs.coerceAtLeast(120L))
                    synchronized(playLock) {
                        if (activeTrack === me) activeTrack = null
                    }
                    runCatching { me.stop(); me.release() }
                } catch (_: Throwable) {
                } finally {
                    abandonAudioFocus(focus)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 播放失败: ${e.message}")
            runCatching { track?.release() }
            abandonAudioFocus(focus)
        }
    }
}
