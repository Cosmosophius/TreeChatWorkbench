// 充面层：android.os.{SystemClock, Build}
@file:Suppress("unused")
package android.os

object SystemClock {
    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun currentThreadTimeMillis(): Long = ProcessCpuTrack()
    @JvmStatic fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
    private fun ProcessCpuTrack(): Long = System.currentTimeMillis()
}

object Build {
    // 保持 < 31：Theme.kt 的 dynamicColor 分支走「从 seed 生成」路径
    const val SDK_INT = 30
    const val MANUFACTURER = "TreeChatWorkbench"
    const val MODEL = "Desktop"
    const val BRAND: String = MANUFACTURER
    const val DEVICE: String = MODEL
    const val FINGERPRINT = "com.treechat.workbench/desktop"

    object VERSION {
        const val RELEASE = "desktop-facade"
        const val SDK_INT = Build.SDK_INT
    }

    object VERSION_CODES {
        const val S = 31
        const val S_V2 = 32
        const val TIRAMISU = 33
        const val N = 24
        const val O = 26
        const val O_MR1 = 27
        const val P = 28
        const val Q = 29
        const val R = 30
    }
}
