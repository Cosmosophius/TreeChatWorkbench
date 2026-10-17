// 桌面替身：etymology.engine.IpaAudioSynthesizer —— v1 不做音频（TTS/AudioTrack 属安卓）。
// 保持 API 面（DictionarySections 的发音按钮调用点在 M4 接线）。
@file:Suppress("unused", "MemberVisibilityCanBePrivate", "UNUSED_PARAMETER")
package com.treechat.workbench.etymology.engine

import android.content.Context
import android.content.Intent

object IpaAudioSynthesizer {
    var speechRate: Float = 1.0f

    data class VoiceStatus(
        val accentKey: String,
        val requested: Int?,
        val used: Int?,
        val ok: Boolean,
        val fellBack: Boolean,
        val message: String,
        val needsInstall: Boolean,
    )

    data class SynthReport(
        val accentKey: String,
        val input: String,
        val phoneCount: Int,
        val durationMs: Long,
        val peak: Float,
        val rms: Float,
        val dcOffset: Float,
        val maxJump: Float,
        val unknownSymbols: List<String>,
        val toneName: String,
        val toneRecognized: Boolean,
    )

    fun init(context: Context) {}
    fun statusFor(accent: String): VoiceStatus = VoiceStatus(accent, 0, 0, false, false, "桌面 v1 不做音频", false)
    fun lastStatusFor(accent: String): VoiceStatus = statusFor(accent)
    fun lastSynthReportFor(accent: String): SynthReport? = null
    fun installVoiceIntents(accent: String): List<Intent> = emptyList()

    suspend fun speak(word: String, ipa: String, accent: String, rateOverride: Float? = null) {}
}
