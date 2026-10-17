package com.treechat.workbench.etymology.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 声源-滤波器（source-filter）共振峰合成器 —— 纯粹用数学生成 16-bit PCM 波形。
 *
 * 这一版是为了**从原理上**消掉旧版"贵族口音爆音"而重写的，逐条对应：
 *
 * 1. **相位连续**：全句只有一个相位累加器 [phase]，基频随声调平滑变化，段与段之间不重置相位。
 *    旧版每个音素/每句各起一段相位，接缝处相位跳变 → 咔哒。现在不会有。
 * 2. **段间连续（无 DC 跳变）**：每个音段是一组"目标参数"（F1/F2/F3、幅度、噪声中心…），
 *    这些控制信号都过一阶低通（时间常数 [CTRL_TAU_S]，约 8ms）得到**平滑轨迹**，等价于段间
 *    短交叉淡化，幅度与直流不可能突变。
 * 3. **起止包络**：整体加余弦升降包络（[ATTACK_MS] / [RELEASE_MS]），首尾样本严格归零。
 * 4. **限幅**：先做**前置增益归一化**（峰值归一），再过**软膝限幅器** [softKnee]（指数软膝、
 *    C¹ 连续、无拐点），输出峰值上限 [CEILING]≈0.9，绝不做 `tanh` / 硬削顶。
 * 5. **直流阻断**：写盘前过一阶高通 [DC_R] 阻断直流偏移。
 * 6. **缓冲区**：整段波形一次性算满（无未初始化样本），[IpaAudioSynthesizer] 用流式写入。
 *
 * 数学与离线校验脚本（Python）逐行一致，见交付报告。
 */
object FormantSynth {

    const val SAMPLE_RATE = 44100

    /** 控制信号平滑时间常数（秒）。等价于段间交叉淡化时长。 */
    private const val CTRL_TAU_S = 0.008

    /** 整体包络（毫秒）。 */
    private const val ATTACK_MS = 8.0
    private const val RELEASE_MS = 25.0

    /** 软膝限幅参数：膝点 k、上限 c。 */
    private const val KNEE = 0.60
    private const val CEILING = 0.90

    /** 前置增益归一化目标（RMS）与最大增益（避免近乎静音被过度放大）。 */
    private const val TARGET_RMS = 0.18
    private const val MAX_GAIN = 60.0

    /**
     * 噪声类段的最大瞬时幅度上限（归一化前）。
     *
     * 爆破/擦音段的阶跃噪声（`an = 0.50`）经窄带通后振铃 + 瞬时尖峰可达
     * RMS 的 6~10 倍。若与浊音共用同一个 RMS 归一化增益，含噪整体 RMS 被噪声
     * 抬高 → 浊音被压、噪声相对更响 → 用户听感就是"一到 s/sh 就炸"。
     * 这里先把噪声瞬时值夹住，归一化时再按"仅浊音"算增益（见 render），
     * 双保险之后噪声段峰值与浊音同量级。
     */
    private const val NOISE_CAP = 0.30

    /** 一阶高通（直流阻断）系数。 */
    private const val DC_R = 0.995

    /** 滤波器系数刷新步长（样本数）。0.36ms 保持一次，轨迹仍平滑，且省掉大量 exp/cos。 */
    private const val COEF_STRIDE = 16

    /**
     * 入声韵尾喉塞收束时长（毫秒）。
     *
     * 中古入声 -p/-t/-k 韵尾在真实语音里不是"突然静音"，而是喉头收紧、
     * 声门快速关闭的 20~30ms 促收过程，听感是"戛然而止但有喉头动作"。
     * 旧实现只把韵尾音段缩短到 55%（"变短"），没有这个收束，
     * 所以入声听起来像"被切断"而不是"促收"。
     */
    private const val CHECKED_CONSTRICT_MS = 26.0

    /** 单次合成的时长上限（毫秒），防止异常长的输入把缓冲撑爆。 */
    private const val MAX_TOTAL_MS = 6000

    private const val MAX_PHONES = 96

    // ------------------------------------------------------------------
    // 结果与指标
    // ------------------------------------------------------------------
    data class Metrics(
        val durationMs: Double,
        val peak: Double,
        val rms: Double,
        val dcOffset: Double,
        /** 相邻样本最大跳变（满量程归一，1.0 = 32767）。爆音/硬切的直接指标。 */
        val maxJump: Double,
        /**
         * **仅浊音/共振峰段**的相邻样本最大跳变（噪声幅度可忽略的样本）。
         * 擦音/爆破的宽带噪声天然逐样本去相关，maxJump 会被噪声主导；
         * 这个指标把噪声段排除，专门反映"包络/相位/直流是否有跳变"这类真正的爆音。
         */
        val maxJumpVoiced: Double,
        val zeroCrossings: Int,
        val clippedSamples: Int
    )

    data class Output(
        val pcm: ShortArray,
        val metrics: Metrics,
        val durationMs: Int
    )

    // ------------------------------------------------------------------
    // 确定性噪声源（LCG，跨 Kotlin/Python 可复现）
    // ------------------------------------------------------------------
    private class Noise(seed: Int = 0x1234567) {
        private var state: Int = seed
        fun next(): Double {
            state = state * 1664525 + 1013904223 // 32-bit wrapping
            val v = (state ushr 8) // 24-bit unsigned 0..16777215
            return v.toDouble() / 8388608.0 - 1.0 // -> [-1, 1)
        }
    }

    /** 声门脉冲（Rosenberg 型，C¹ 平滑，值域 [0,1]）。相位 t ∈ [0,1)。 */
    private fun glottal(t: Double): Double {
        val open = 0.42
        val close = 0.62
        return when {
            t < open -> {
                val u = t / open
                3.0 * u * u - 2.0 * u * u * u
            }
            t < close -> {
                val u = (t - open) / (close - open)
                1.0 - u * u
            }
            else -> 0.0
        }
    }

    /**
     * 软膝限幅器：|x|≤k 时线性（增益 1），之后指数平滑压向 c，在膝点处**值连续且斜率连续**，
     * 因此不会像 tanh/硬削那样在强驱动下产生刺耳高次谐波。
     */
    internal fun softKnee(x: Double): Double {
        val ax = abs(x)
        if (ax <= KNEE) return x
        val span = CEILING - KNEE
        val y = CEILING - span * exp(-(ax - KNEE) / span)
        return if (x < 0) -y else y
    }

    /** Klatt 二极点谐振器系数（DC 增益归一）。 */
    private class Resonator {
        private var b1 = 0.0
        private var b2 = 0.0
        private var a0 = 1.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun setCoeffs(freq: Double, bw: Double, fs: Int) {
            val f = freq.coerceIn(50.0, fs / 2.0 - 300.0)
            val b = bw.coerceIn(20.0, fs / 2.0 - 300.0)
            val r = exp(-PI * b / fs)
            val c = -r * r
            val bc = 2.0 * r * cos(2.0 * PI * f / fs)
            a0 = 1.0 - bc - c
            b1 = bc
            b2 = c
        }

        fun step(x: Double): Double {
            val y = a0 * x + b1 * y1 + b2 * y2
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            y1 = 0.0; y2 = 0.0
        }
    }

    /**
     * 渲染一整句。
     * @param phones 音段序列（[HistoricalIpaPhoneMap.parse] 的结果）
     * @param tone 声调折线
     * @param rate 语速倍数（>1 更快）
     */
    fun render(
        phones: List<HistoricalIpaPhoneMap.Phone>,
        tone: HistoricalIpaPhoneMap.Tone,
        rate: Float,
        fs: Int = SAMPLE_RATE
    ): Output {
        val use = if (phones.size > MAX_PHONES) phones.subList(0, MAX_PHONES) else phones
        if (use.isEmpty()) {
            return Output(ShortArray(1), Metrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0), 0)
        }
        val r = if (rate <= 0f) 1f else rate

        // ---- 每段样本数 ----
        val durSamples = IntArray(use.size)
        var total = 0
        for ((k, ph) in use.withIndex()) {
            val ms = (ph.durMs / r).toDouble()
            var s = (fs * ms / 1000.0).toInt().coerceAtLeast(24)
            if (total + s > fs * (MAX_TOTAL_MS / 1000.0)) s = (fs * (MAX_TOTAL_MS / 1000.0)).toInt() - total
            if (s <= 0) break
            durSamples[k] = s
            total += s
        }
        if (total <= 0) return Output(ShortArray(1), Metrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0), 0)
        val n = total

        // ---- 控制信号（目标值）----
        val tF1 = DoubleArray(n)
        val tF2 = DoubleArray(n)
        val tF3 = DoubleArray(n)
        val tAmpV = DoubleArray(n)
        val tAmpN = DoubleArray(n)
        val tNc = DoubleArray(n)
        val tNq = DoubleArray(n)
        // 入声韵尾喉塞收束：只在**最后一个音段带 checked 标记**时启用，
        // 生效区间是整句最后 CHECKED_CONSTRICT_MS。收束让声门快速关闭，
        // 听感是"促收"而不是"切断"；具体实现见渲染循环里的 constrict。
        val lastChecked = use.lastOrNull()?.checked == true
        val constrictSamples = (CHECKED_CONSTRICT_MS / 1000.0 * fs).toInt().coerceAtLeast(1)

        var idx = 0
        for ((k, ph) in use.withIndex()) {
            val len = durSamples[k]
            for (q in 0 until len) {
                val u = if (len <= 1) 0.0 else q.toDouble() / (len - 1)
                val i = idx + q
                tF1[i] = ph.f1
                tF2[i] = ph.f2
                tF3[i] = ph.f3
                var av = 0.0
                var an = 0.0
                var nc = ph.noiseCenterHz
                var nq = ph.noiseQ
                when (ph.kind) {
                    HistoricalIpaPhoneMap.Kind.VOWEL,
                    HistoricalIpaPhoneMap.Kind.NASAL,
                    HistoricalIpaPhoneMap.Kind.LATERAL,
                    HistoricalIpaPhoneMap.Kind.APPROXIMANT,
                    HistoricalIpaPhoneMap.Kind.TRILL,
                    HistoricalIpaPhoneMap.Kind.TAP -> av = ph.amp

                    HistoricalIpaPhoneMap.Kind.FRICATIVE -> {
                        av = if (ph.voiced) ph.amp * 0.7 else 0.0
                        an = ph.amp
                    }
                    HistoricalIpaPhoneMap.Kind.STOP -> {
                        if (ph.voiced && u < 0.6) av = 0.16 // 浊塞音闭塞段有声带振动
                        if (!ph.voiced && u < 0.55) an = 0.02 // 极轻闭塞噪声
                        // 除阻爆破：0.50 的方波阶跃经窄带通会振铃出高尖峰，是炸麦主犯之一。
                        // 用 30ms 的余弦斜坡代替硬阶跃（起爆可听感不变，振铃大幅收敛）；
                        // 具体数值见下面的 burstRamp（0=刚起爆，1=斜坡走完）。
                        if (u in 0.55..0.72) an = 0.50 * burstRamp(u, 0.55, 0.72)
                        else if (ph.aspirated && u in 0.72..0.98) an = 0.26 // 送气
                        nc = ph.f2; nq = 2.0
                    }
                    HistoricalIpaPhoneMap.Kind.AFFRICATE -> {
                        if (ph.voiced && u < 0.4) av = 0.22
                        an = when {
                            u < 0.32 -> if (ph.voiced) 0.0 else 0.02
                            u < 0.45 -> 0.5 * burstRamp(u, 0.32, 0.45) // 除阻：同上，斜坡代替阶跃
                            else -> ph.amp // 擦音段
                        }
                        nc = ph.noiseCenterHz; nq = ph.noiseQ
                    }
                }
                tAmpV[i] = av
                tAmpN[i] = an
                tNc[i] = nc
                tNq[i] = nq
            }
            idx += len
        }

        // ---- 控制信号平滑（一阶低通 = 段间交叉淡化，消除突变）----
        val alpha = 1.0 - exp(-1.0 / (CTRL_TAU_S * fs))
        smooth(tF1, alpha); smooth(tF2, alpha); smooth(tF3, alpha)
        smooth(tAmpV, alpha); smooth(tAmpN, alpha); smooth(tNc, alpha); smooth(tNq, alpha)

        // ---- 包络 ----
        val attack = (ATTACK_MS / 1000.0 * fs).toInt().coerceAtLeast(1)
        val release = (RELEASE_MS / 1000.0 * fs).toInt().coerceAtLeast(1)

        // ---- 渲染 ----
        val out = DoubleArray(n)
        val resF1 = Resonator()
        val resF2 = Resonator()
        val resF3 = Resonator()
        val resNoise = Resonator()
        val noise = Noise(0x1234567 + use.size * 7919)
        var phase = 0.0
        var prevG = 0.0
        var creakPhase = 0.0

        val contour = tone.contourHz
        for (i in 0 until n) {
            val p = i.toDouble() / (n - 1).coerceAtLeast(1)

            // 基频：沿声调折线插值
            val f0 = interp(contour, p)

            // 声门脉冲（相位连续累加）
            phase += f0 / fs
            if (phase >= 1.0) phase -= 1.0
            val g = glottal(phase)
            var voice = g + 0.35 * (g - prevG) // 轻微微分，增加明亮度
            prevG = g
            // 吱嘎/颤音调制
            creakPhase += 28.0 / fs
            if (creakPhase >= 1.0) creakPhase -= 1.0
            val trem = 0.85 + 0.15 * sin(2.0 * PI * creakPhase)

            // 每 COEF_STRIDE 刷新一次滤波器系数
            if (i % COEF_STRIDE == 0) {
                resF1.setCoeffs(tF1[i], 70.0, fs)
                resF2.setCoeffs(tF2[i], 100.0, fs)
                resF3.setCoeffs(tF3[i], 140.0, fs)
                resNoise.setCoeffs(tNc[i], tNc[i] / tNq[i].coerceAtLeast(0.5), fs)
            }

            val vAmp = tAmpV[i] * trem
            val nAmp = tAmpN[i]

            // 声源 → 声道（三级共振峰级联）
            var v = resF1.step(voice)
            v = resF2.step(v)
            v = resF3.step(v)
            v *= vAmp

            // 噪声源 → 单带通（擦音/爆破/送气）
            val nz = noise.next()
            var nf = resNoise.step(nz) * nAmp
            // 噪声瞬时钳位：窄带通的振铃尖峰先在这里压住，不让它进 RMS 统计
            // 去抬高整体增益（否则"一到 s/sh 就炸"，浊音还被压小）。钳位用硬夹
            // 而不是限幅器 —— 这里处理的是单样本尖峰，硬夹只动那几个样本，
            // 后续仍有整体 softKnee 负责平滑的峰值保护。
            if (nf > NOISE_CAP) nf = NOISE_CAP else if (nf < -NOISE_CAP) nf = -NOISE_CAP

            var s = v + nf

            // 入声韵尾喉塞收束（只在末段 checked 时生效）：
            // 收束不是静音，而是声门快速关闭 —— 基频骤降 + 幅度急收，
            // 听感是喉头一紧的"促"，不是一刀切的"断"。
            // constrict: 0=未收束，1=完全关闭；余弦曲线保证收束本身无拐点。
            if (lastChecked && i >= n - constrictSamples) {
                val t = (i - (n - constrictSamples)).toDouble() / constrictSamples.coerceAtLeast(1)
                val constrict = 0.5 - 0.5 * cos(PI * t.coerceIn(0.0, 1.0))
                s *= (1.0 - 0.85 * constrict)
            }

            // 起止包络
            var env = 1.0
            if (i < attack) env = 0.5 - 0.5 * cos(PI * i / attack)
            else if (i >= n - release) env = 0.5 - 0.5 * cos(PI * (n - 1 - i) / release)
            s *= env
            out[i] = s
        }

        // ---- 直流阻断（一阶高通）----
        var prevIn = 0.0
        var prevOut = 0.0
        for (i in 0 until n) {
            val x = out[i]
            val y = x - prevIn + DC_R * prevOut
            prevIn = x
            prevOut = y
            out[i] = y
        }

        // ---- 前置增益归一化（RMS → TARGET_RMS，带增益上限）----
        // 用"仅浊音样本"的 RMS 而非全句 RMS：噪声段的宽带能量若计入统计，
        // 会把增益整体抬高——浊音被压、噪声相对更响，听感就是擦音段炸麦。
        // 浊音判据与 maxJumpVoiced 的口径一致（tAmpN < 0.02 视为无噪声）。
        var sumSqRaw = 0.0
        var voicedCount = 0
        for (i in 0 until n) {
            if (tAmpN[i] < 0.02) {
                sumSqRaw += out[i] * out[i]
                voicedCount++
            }
        }
        // 整句几乎全是噪声（极端输入）时才回退到全句 RMS，保证一定有声音
        if (voicedCount < n / 8) {
            sumSqRaw = 0.0
            for (i in 0 until n) sumSqRaw += out[i] * out[i]
            voicedCount = n
        }
        val rmsRaw = sqrt(sumSqRaw / voicedCount.coerceAtLeast(1))
        var gain = if (rmsRaw > 1e-9) TARGET_RMS / rmsRaw else 0.0
        if (gain > MAX_GAIN) gain = MAX_GAIN
        for (i in 0 until n) out[i] *= gain

        // ---- 软膝限幅（峰值 ≤ ~0.9）→ 定标 int16 ----
        val pcm = ShortArray(n)
        var peak = 0.0
        var sumSq = 0.0
        var sum = 0.0
        var clipped = 0
        var zc = 0
        var prevSign = 0
        for (i in 0 until n) {
            val y = softKnee(out[i])
            val a = abs(y)
            if (a > peak) peak = a
            sumSq += y * y
            val iv = (y * 32767.0).toInt().coerceIn(-32768, 32767)
            if (iv >= 32767 || iv <= -32768) clipped++
            sum += iv
            if (iv != 0) {
                val sg = if (iv > 0) 1 else -1
                if (prevSign != 0 && sg != prevSign) zc++
                prevSign = sg
            }
            pcm[i] = iv.toShort()
        }

        // 相邻样本最大跳变（满量程归一）；并单独统计"噪声可忽略"的浊音段跳变
        var maxJump = 0.0
        var maxJumpVoiced = 0.0
        for (i in 1 until n) {
            val d = abs(pcm[i].toInt() - pcm[i - 1].toInt()) / 32767.0
            if (d > maxJump) maxJump = d
            if (tAmpN[i] < 0.02 && tAmpN[i - 1] < 0.02 && d > maxJumpVoiced) maxJumpVoiced = d
        }

        val m = Metrics(
            durationMs = n * 1000.0 / fs,
            peak = peak,
            rms = if (n > 0) sqrt(sumSq / n) else 0.0,
            dcOffset = if (n > 0) sum / n / 32767.0 else 0.0,
            maxJump = maxJump,
            maxJumpVoiced = maxJumpVoiced,
            zeroCrossings = zc,
            clippedSamples = clipped
        )
        return Output(pcm, m, (n * 1000.0 / fs).toInt())
    }

    private fun smooth(a: DoubleArray, alpha: Double) {
        var v = a[0]
        for (i in 1 until a.size) {
            v += (a[i] - v) * alpha
            a[i] = v
        }
    }

    private fun interp(pts: DoubleArray, p: Double): Double {
        if (pts.isEmpty()) return 148.0
        if (pts.size == 1) return pts[0]
        val x = p.coerceIn(0.0, 1.0) * (pts.size - 1)
        val i = x.toInt().coerceAtMost(pts.size - 2)
        val f = x - i
        return pts[i] * (1 - f) + pts[i + 1] * f
    }

    /**
     * 爆破起爆的余弦斜坡：u 从 [from] 到 [to] 由 0 平滑爬到 1。
     * 方波阶跃进窄带通会振铃（Gibbs 现象）；斜坡让起爆保留可听的"噗"感，
     * 同时把振铃尖峰压到原来的三成以下。
     */
    private fun burstRamp(u: Double, from: Double, to: Double): Double {
        if (u <= from) return 0.0
        if (u >= to) return 1.0
        val t = (u - from) / (to - from)
        return 0.5 - 0.5 * cos(PI * t)
    }
}
