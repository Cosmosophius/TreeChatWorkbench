package com.treechat.desktop

import com.treechat.core.AppEnv
import com.treechat.core.AppFile
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.util.UUID

/**
 * 桌面（JVM）侧的 [AppEnv] 实现，M1 首个落地平台实现。
 * 目录基点用用户主目录下 .treechat/（后续再按 XDG 规范归位）。
 */
class DesktopAppEnv(
    override val platform: String = run {
        val os = System.getProperty("os.name", "unknown").lowercase()
        val arch = System.getProperty("os.arch", "unknown")
        val osName = when {
            os.contains("linux") -> "linux"
            os.contains("mac") -> "macos"
            else -> os
        }
        "$osName-$arch"
    },
) : AppEnv {
    override val filesDir: AppFile = DesktopAppFile(
        File(System.getProperty("user.home"), ".treechat/files")
    )

    override fun newUuid(): String = UUID.randomUUID().toString()

    override fun setClipboardText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun openLink(url: String): Boolean {
        return try {
            // AWT 的托盘/剪贴板调用会隐式初始化 ToolKit，窗口 app 本身已在 AWT 线程组里，无需额外设置。
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                // 无头/受限环境下的兜底：交给浏览器进程尝试（失败也不抛出）。
                ProcessBuilder("xdg-open", url).start()
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** [AppFile] 的 java.io.File 实现。 */
class DesktopAppFile(val file: File) : AppFile {
    override val path: String = file.absolutePath

    override fun resolve(child: String): AppFile = DesktopAppFile(File(file, child))

    override fun exists(): Boolean = file.exists()

    override fun readBytes(): ByteArray = file.readBytes()

    override fun writeBytes(data: ByteArray) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeBytes(data)
    }
}
