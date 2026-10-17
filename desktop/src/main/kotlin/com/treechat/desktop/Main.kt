// M2 桌面阅读壳入口：挂充面层 → 启动书架/阅读窗口。
// 保留 --appenv-smoke（无窗口冒烟，验证 AppEnv 全链路）。
package com.treechat.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.treechat.workbench.data.prefs.AppSettings

fun main(args: Array<String>) {
    DesktopAppEnvHolder.install(DesktopAppEnv())

    if (args.contains("--appenv-smoke")) {
        smokeAppEnv()
        return
    }

    application {
        val toOpen = args.filter { it.startsWith("--open=") }
            .map { java.io.File(it.removePrefix("--open=")) }
            .lastOrNull { it.exists() }
        DesktopReaderHost(
            settings = remember { AppSettings(DesktopAppEnvHolder.appContext) },
            env = DesktopAppEnvHolder.env,
            onCloseRequest = { exitApplication() },
            initialOpen = toOpen,
        )
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
