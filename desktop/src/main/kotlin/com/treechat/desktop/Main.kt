// M3 桌面工作台入口：挂充面层 → DesktopWorkbenchApp（主窗 + 独立顶层小窗）。
// 保留 --appenv-smoke（无窗口冒烟）与 --open=<文件>（CLI 直开一本书，仍走 M2 阅读入口）。
package com.treechat.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.treechat.workbench.data.prefs.AppSettings

fun main(args: Array<String>) {
    DesktopAppEnvHolder.install(DesktopAppEnv())

    if (args.contains("--appenv-smoke")) {
        smokeAppEnv()
        return
    }

    val readerPath = args.filter { it.startsWith("--open=") }
        .map { java.io.File(it.removePrefix("--open=")) }
        .lastOrNull { it.exists() }

    application {
        if (readerPath != null) {
            // 兼容 CLI 直开阅读的流程（M2 生活方式）
            Window(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(width = 1280.dp, height = 830.dp),
                title = "条畅",
            ) {
                val settings = remember { AppSettings(DesktopAppEnvHolder.appContext) }
                DesktopReaderHost(
                    settings = settings,
                    env = DesktopAppEnvHolder.env,
                    onCloseRequest = { exitApplication() },
                    initialOpen = readerPath,
                )
            }
        } else {
            DesktopWorkbenchApp(
                settings = remember { AppSettings(DesktopAppEnvHolder.appContext) },
                env = DesktopAppEnvHolder.env,
                onCloseRequest = { exitApplication() },
            )
        }
    }
}

private fun smokeAppEnv() {
    val env = DesktopAppEnvHolder.env
    println("[M1-smoke] platform=${env.platform}")
    println("[M1-smoke] filesDir=${env.filesDir.path} exists=${env.filesDir.exists()}")
    println("[M1-smoke] newUuid=${env.newUuid()}")
    val token = env.filesDir.resolve("m1-smoke.txt") as DesktopAppFile
    token.writeBytes("smoke".toByteArray())
    require(token.exists() && String(token.readBytes()) == "smoke") { "AppFile write/read failed" }
    println("[M1-smoke] AppFile roundtrip ok at ${token.path}")
    token.file.delete()
    env.setClipboardText("条畅 M1 smoke")
    println("[M1-smoke] clipboard set ok")
}
