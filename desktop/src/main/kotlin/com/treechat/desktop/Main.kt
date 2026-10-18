// M3 桌面工作台入口：挂充面层 → DesktopWorkbenchApp（主窗 + 独立顶层小窗）。
// 保留 --appenv-smoke（无窗口冒烟）与 --open=<文件>（CLI 直开一本书，仍走 M2 阅读入口）。
package com.treechat.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.treechat.workbench.data.prefs.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain

fun main(args: Array<String>) {
    DesktopAppEnvHolder.install(DesktopAppEnv())
    installMainDispatcher()

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

/**
 * JVM 桌面的 Main 派送器安装（Handoff 实况：coroutines 内部 FastServiceLoader 在本环境
 * 枚举不到 jar 内的 META-INF/services——探针实测空列表）。
 * 路径：反射调 kotlinx-coroutines-swing 公开的 SwingDispatcherFactory.createDispatcher，
 * 再用 kotlinx-coroutines-test 的公开 API Dispatchers.setMain 挂上（AWT EDT）。
 * 幂等（重复调用 harmless）；smoke 里会校验不再 [missing]。
 */
private fun installMainDispatcher() {
    runCatching {
        val cls = Class.forName("kotlinx.coroutines.swing.SwingDispatcherFactory")
        val factory = cls.getDeclaredConstructor().newInstance()
        val create = cls.methods.first { it.name == "createDispatcher" } as java.lang.reflect.Method
        val dispatcher = create.invoke(factory, emptyList<Any?>()) as kotlinx.coroutines.MainCoroutineDispatcher
        Dispatchers.setMain(dispatcher)
    }.onFailure {
        System.err.println("[desktop-main] Dispatchers.setMain 注入失败: $it")
    }
}

private fun smokeAppEnv() {
    val env = DesktopAppEnvHolder.env
    println("[M1-smoke] platform=${env.platform}")
    println("[M1-smoke] Main dispatcher=${kotlinx.coroutines.Dispatchers.Main}")
    runCatching {
        val loader = DesktopApplication::class.java.classLoader
        val list = loader.getResources("META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory").asSequence().toList()
        println("[M1-smoke] factory service urls~count=${list.size} : ${list}")
    }.onFailure {
        println("[M1-smoke] factory resources probe failed: $it")
    }
    runCatching {
        val loaderClass = Class.forName("kotlinx.coroutines.internal.FastServiceLoader")
        val instance = loaderClass.getDeclaredField("INSTANCE").get(null)
        val m = loaderClass.getDeclaredMethod("loadMainDispatcherFactory\$kotlinx_coroutines_core")
        m.isAccessible = true
        val factories = m.invoke(instance) as List<*>
        println("[M1-smoke] kotlin fastloader factories=${factories.map { it?.javaClass?.name }}")
    }.onFailure {
        println("[M1-smoke] kotlin fastloader probe failed: $it")
    }
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
