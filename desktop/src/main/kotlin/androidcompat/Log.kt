// 充面层：android.util.Log（stderr）、android.os.{SystemClock, Build}
@file:Suppress("unused")
package android.util

object Log {
    var verboseStderr: Boolean = true

    @JvmStatic fun d(tag: String, msg: String): Int = println2("D/$tag", msg)
    @JvmStatic fun i(tag: String, msg: String): Int = println2("I/$tag", msg)
    @JvmStatic fun w(tag: String, msg: String): Int = println2("W/$tag", msg)
    @JvmStatic fun e(tag: String, msg: String): Int = println2("E/$tag", msg)
    @JvmStatic fun v(tag: String, msg: String): Int = println2("V/$tag", msg)
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int = println2("W/$tag", "$msg\n${tr?.stackTraceToString() ?: ""}")
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int = println2("E/$tag", "$msg\n${tr?.stackTraceToString() ?: ""}")
    @JvmStatic fun wtf(tag: String, msg: String): Int = println2("F/$tag", msg)
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = verboseStderr
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""

    private fun println2(level: String, msg: String): Int {
        if (!verboseStderr) return 0
        System.err.println("$level: $msg")
        return msg.length
    }
}
