package com.treechat.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.treechat.core.AppEnv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

// M1 桌面壳：空 Compose Window。接受参数 --appenv-smoke 做无界面冒烟退出。
fun main(args: Array<String>) {
    if (args.contains("--appenv-smoke")) {
        smokeAppEnv()
        return
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "条畅（桌面壳 M1）",
            state = rememberWindowState(width = 640.dp, height = 420.dp),
        ) {
            // M1 只验证窗口与主题挂接，内容为占位；M2 起换成阅读功能。
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "条畅 — 桌面壳（M1）",
                        style = MaterialTheme.typography.headlineSmall,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun smokeAppEnv() {
    val env: AppEnv = DesktopAppEnv()
    println("[M1-smoke] platform=${env.platform}")
    println("[M1-smoke] filesDir=${env.filesDir.path} exists=${env.filesDir.exists()}")
    println("[M1-smoke] newUuid=${env.newUuid()}")
    val token = env.filesDir.resolve("m1-smoke.txt") as DesktopAppFile
    env.filesDir.exists() // 触发目录语义
    token.writeBytes("smoke".toByteArray())
    require(token.exists() && String(token.readBytes()) == "smoke") { "AppFile write/read failed" }
    println("[M1-smoke] AppFile roundtrip ok at ${token.path}")
    token.file.delete()
    env.setClipboardText("条畅 M1 smoke")
    println("[M1-smoke] clipboard set ok")
    println("[M1-smoke] openLink probe skipped (无头环境不弹浏览器)")
}
