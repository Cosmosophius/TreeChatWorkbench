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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain

fun main(args: Array<String>) {
    DesktopAppEnvHolder.install(DesktopAppEnv())
    installMainDispatcher()

    if (args.contains("--appenv-smoke")) {
        smokeAppEnv()
        return
    }

    if (args.contains("--ocr-smoke")) {
        smokeOcr()
        return
    }

    if (args.contains("--db-smoke")) {
        smokeDb()
        return
    }

    val seedDemo = args.contains("--seed-demo")
    if (seedDemo) {
        DesktopDemoState.latestConvId = seedDemoConversation()
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

/** 扫描件 OCR 冒烟：生成一张带文字的位图 → 走 OcrEngine（外部进程链路）→ 打印词框数。 */
private fun smokeOcr() {
    val img = java.awt.image.BufferedImage(800, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, 800, 200)
    g.color = java.awt.Color.BLACK
    g.font = java.awt.Font("PingFang SC", java.awt.Font.PLAIN, 48)
    g.drawString("条畅桌面 OCR 验证 Hello", 40, 120)
    g.dispose()

    val engine = com.treechat.workbench.ocr.OcrEngine.detectEngine()
    println("[ocr-smoke] engine=$engine")
    val bmp = android.graphics.Bitmap.decodePng(
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
    )
    val boxes = com.treechat.workbench.ocr.OcrEngine.recognizeOrFallback(bmp!!)
    println("[ocr-smoke] boxes=${boxes.size}")
    boxes.take(6).forEach { println("[ocr-smoke]   '${it.text}' ${it.bounds.left.toInt()},${it.bounds.top.toInt()} ${it.bounds.width.toInt()}x${it.bounds.height.toInt()}") }
}

/** 数据层冒烟：建 conversation/node/message → 观察流读回 → 打印（验证 DAO 流面）。 */
private fun smokeDb() {
    val db = com.treechat.workbench.data.db.AppDatabase.getInstance(DesktopAppEnvHolder.appContext)
    val dao = db.conversationDao()
    val folderDao = db.folderDao()
    runBlocking {
        val convId = java.util.UUID.randomUUID().toString()
        folderDao.insertConversation(
            com.treechat.workbench.data.model.Conversation(
                id = convId, title = "smoke 会话",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            )
        )
        val node = com.treechat.workbench.data.model.ConversationNode(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = convId, title = "主对话",
            originQuote = "测试引用文本",
        )
        dao.insertNode(node)
        dao.insertMessage(
            com.treechat.workbench.data.model.ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                nodeId = node.id, role = "user", content = "这里写着 测试引用文本 一段",
            )
        )
        val readBack = dao.getNodesForConversation(convId)
        println("[db-smoke] nodes=${readBack.size} title=${readBack.firstOrNull()?.title}")
        val msgs = dao.getMessagesForNode(node.id)
        println("[db-smoke] messages=${msgs.size} content=${msgs.firstOrNull()?.content?.take(20)}")
        val chain = dao.getAncestorChain(node.id)
        println("[db-smoke] ancestorChain=${chain.size}")
        val hits = dao.searchMessageContent("测试引用", null, 10)
        println("[db-smoke] searchHits=${hits.size}")
        val quoted = dao.observeQuotedNodes().first()
        println("[db-smoke] quotedNodes=${quoted.size}")
        folderDao.deleteConversationById(convId)
        println("[db-smoke] cleaned, conversations left=${folderDao.getAllFolders().size}")
    }
}

/** --seed-demo 的演示会话 id（供 WorkbenchApp 启动时直接打开）。 */
object DesktopDemoState {
    @Volatile var latestConvId: String? = null
}

/** 造一个「带分叉引用」的演示会话：验证同文本超链接的标识与 hover 预览（数据面 + UI 面）。 */
private fun seedDemoConversation(): String {
    val db = com.treechat.workbench.data.db.AppDatabase.getInstance(DesktopAppEnvHolder.appContext)
    val dao = db.conversationDao()
    val folderDao = db.folderDao()
    return runBlocking {
        val convId = java.util.UUID.randomUUID().toString()
        folderDao.insertConversation(
            com.treechat.workbench.data.model.Conversation(
                id = convId, title = "超链接演示",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            )
        )
        val root = com.treechat.workbench.data.model.ConversationNode(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = convId, title = "主对话", originQuote = "",
        )
        dao.insertNode(root)
        dao.insertMessage(
            com.treechat.workbench.data.model.ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                nodeId = root.id, role = "user",
                content = "帮我总结一下：这条消息里写着「假设引用文本」的内容。",
            )
        )
        dao.insertMessage(
            com.treechat.workbench.data.model.ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                nodeId = root.id, role = "assistant",
                content = "回答正文：这句话里包含「假设引用文本」，所以这几处应当出现同文本超链接标识（下划线）。",
            )
        )
        val fork = com.treechat.workbench.data.model.ConversationNode(
            id = java.util.UUID.randomUUID().toString(),
            conversationId = convId, parentId = root.id,
            title = "分叉：假设引用文本", originQuote = "假设引用文本",
        )
        dao.insertNode(fork)
        dao.insertMessage(
            com.treechat.workbench.data.model.ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                nodeId = fork.id, role = "user", content = "针对这个引用的提问。",
            )
        )
        convId
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
