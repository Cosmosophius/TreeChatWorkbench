package com.treechat.workbench

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘。
 *
 * ## 为什么需要它
 * 用户报告"切回对话会闪退"，但我们**没有设备、拿不到 logcat**。
 * 仅靠静态读代码猜崩溃点已经失败过多轮（猜错过：网络超时、坐标系、
 * 手势竞争），继续猜下去既浪费时间又不可靠。
 *
 * 把崩溃堆栈写到**外部私有目录**（`Android/data/<包名>/files/crashes/`），
 * 用户用任意文件管理器就能打开并复制给我，不必接 adb。
 * 这是在没有调试环境的情况下唯一能拿到确定信息的手段。
 *
 * ## 为什么写外部私有目录而不是内部目录
 * 内部目录（`/data/data/...`）在未 root 的设备上用户无法访问。
 * 外部私有目录（`Android/data/<pkg>/files/`）在 Android 10 及以下可直接浏览，
 * Android 11+ 多数文件管理器也能进入 —— 且仍是应用私有、无需存储权限。
 *
 * ## 隐私
 * 只记录异常类型、消息与堆栈，**不记录消息正文、密钥或任何用户内容**。
 */
object CrashLogger {

    private const val DIR_NAME = "crashes"
    private const val MAX_FILES = 5

    /** 安装全局未捕获异常处理器。应在 Application.onCreate 里第一时间调用。 */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 写日志本身**绝不能再抛异常**，否则会掩盖原始崩溃
            runCatching { writeCrash(appContext, thread, throwable) }
            // 交回原处理器：让系统照常记录、结束进程（不要吞掉崩溃）
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context) ?: return
        if (!dir.exists()) dir.mkdirs()

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== TreeChat 崩溃报告 ===")
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            pw.println("线程: ${thread.name}")
            pw.println("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            pw.println("应用版本: ${appVersion(context)}")
            pw.println()
            pw.println("--- 异常堆栈 ---")
            throwable.printStackTrace(pw)
            pw.println()
            pw.println("--- 起因链 ---")
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 10) {
                pw.println("起因[$depth]: ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                depth++
            }
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        File(dir, "crash_$stamp.txt").writeText(sw.toString(), Charsets.UTF_8)
        pruneOldFiles(dir)
    }

    /** 崩溃日志目录；外部私有目录不可用时回落到内部目录。 */
    fun crashDir(context: Context): File? {
        val external = context.getExternalFilesDir(null)
        val base = external ?: context.filesDir
        return File(base, DIR_NAME)
    }

    /** 列出已有崩溃日志，最新的在前。 */
    fun list(context: Context): List<File> {
        val dir = crashDir(context) ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 读取一份崩溃日志内容（供界面展示/复制）。 */
    fun read(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrElse { "（读取失败：${it.localizedMessage}）" }

    fun clearAll(context: Context) {
        runCatching { crashDir(context)?.listFiles()?.forEach { it.delete() } }
    }

    /** 只保留最近几份，避免长期积累占空间。 */
    private fun pruneOldFiles(dir: File) {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrElse { "未知" }
}
