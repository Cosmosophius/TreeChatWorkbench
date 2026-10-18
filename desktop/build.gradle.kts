// Compose Desktop 壳（docs/PORTING.md M2 里程碑：阅读）。
// 路线 B：共享源码直接编译进桌面（白名单 srcDir 指向 app 源码），
// Android 专属文件由 desktop 本地的「充面层」（package android.* / androidx.* 桩）
// 与「同 API 桌面替身」（DocumentFileReader/PdfPageSource/OcrEngine…）顶住。
// 共享源码一行不改，安卓发版路径零影响。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.HttpURLConnection
import java.net.URI

val appSourcesRoot = rootProject.file("app/src/main/java/com/treechat/workbench")

// M2 共享源白名单（PORTING.md §1 盘点后核定；被充面层顶住的才收进来）。
// 前两条是桌面本模块自己的目录（充面层 androidcompat/ 与桌面壳 com/），
// 与指向 app 源码的 srcDir 共用同一个 allow-list。
val m2SharedIncludes = listOf(
    "**/androidcompat/**",
    "**/com/**",
    "ui/selection/**",
    "ui/markdown/**",
    "ui/theme/**",
    "ui/motion/**",
    "ui/tree/**",
    "ui/window/**",
    "ui/common/**",
    "ui/components/**",
    "ui/chat/ChatNodeViewModel.kt",
    "ui/chat/PromptContextBudget.kt",
    "ui/chat/SmoothReveal.kt",
    "ui/chat/ChatMessageView.kt",
    "ui/explorer/**",
    "ui/knowledge/**",
    "ui/dictionary/**",
    "ui/notes/**",
    "ui/settings/**",
    "ui/search/**",
    "ui/desktop/**",
    "export/TalkTreeBundle.kt",
    "export/ZipExporter.kt",
    "ui/reader/**",
    "etymology/data/**",
    "etymology/repository/**",
    "etymology/rp/**",
    "etymology/ui/**",
    "etymology/engine/FormantSynth.kt",
    "etymology/engine/HistoricalIpaPhoneMap.kt",
    "data/model/**",
    "data/db/SearchModels.kt",
    "data/db/SearchProjections.kt",
    "data/db/ConversationSearchRepository.kt",
    "data/db/ConversationDao.kt",
    "data/db/FolderDao.kt",
    "data/db/HighlightDao.kt",
    "data/db/KnowledgeDao.kt",
    "data/db/WordDao.kt",
    "data/prefs/**",
    "data/reader/**",
    "network/**",
    "util/**",
    "export/TalkTreeBundle.kt",
    "task/**",
    "safety/**",
    "rag/**",
)

// 平台绑死、由桌面替身顶住的共享文件（替身在 desktop/src/main/kotlin 同包同名）。
val platformBoundExcludes = listOf(
    // 数据库由桌面 sqlite-jdbc 实现（substitute 同名 AppDatabase + Desktop*Dao）
    "rag/DocumentParser.kt",
    "rag/ImagePicker.kt",
    "rag/PdfTextExtractor.kt",
    "etymology/engine/IpaAudioSynthesizer.kt",
    "data/reader/Speech.kt",        // 桌面替身已接管（say / espeak-ng / espeak）
    "data/reader/CloudFolderStore.kt", // 桌面替身已接管（普通文件夹 + java.io.File，handoff 增量二）
    // GoogleDriveClient.kt 是纯 SAF 改造后的死代码（零引用），handoff 明令不移植
    "data/reader/GoogleDriveClient.kt",
    "ocr/OcrEngine.kt",
    "ocr/PdfOcr.kt",
    "ocr/PdfPageSource.kt",
    "ui/reader/DocumentFileReader.kt",
    "ui/reader/CoverExtractor.kt",
    "ui/selection/TextCursorGuard.kt",   // 桌面替身已接管（系统自带文本光标）
    "ui/common/FreePositionStore.kt",    // 桌面替身已接管（同 API Properties 版）
    "export/ZipExporter.kt",             // 桌面替身已接管（保存对话框）
    "util/AppEdition.kt",
)

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
    sourceSets.getByName("main").kotlin.apply {
        setIncludes(m2SharedIncludes)
        setExcludes(platformBoundExcludes)
        srcDir(appSourcesRoot)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // 共享源码依赖（与安卓侧同版本或兼容版本）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")   // SwingDispatcherFactory（实例由 Main 里反射注入）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")    // Dispatchers.setMain（公开 API）
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.json:json:20240303")           // org.json.JSONArray（Android 平台库的 JVM 原生件）
    implementation("com.ibm.icu:icu4j:74.2")           // MessageSelectionController 汉字分词（Maven Central 最新稳定）
    // Room 注解（仅 @Entity/@Index 编译期符号；桌面不用 Room 运行时）
    implementation("androidx.room:room-common:2.6.1")
    // AndroidViewModel 基类：androidx 把桌面 JVM 实现发布在 lifecycle-viewmodel-desktop 模块
    implementation("androidx.lifecycle:lifecycle-viewmodel-desktop:2.8.4")
    // PDF：桌面端 PDFBox（安卓侧是 com.tom-roush:pdfbox-android）
    implementation("org.apache.pdfbox:pdfbox:2.0.27")
    // M3 数据库（M3 里程碑接 UI）
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // M3 色板算法：从安卓 material 库 AAR 里抽出的 color/utilities 字节码（Hct/TonalPalette/Cam16…）
    implementation(files("libs/material-color-utilities-1.12.0-classes.jar"))
}

compose.desktop {
    application {
        mainClass = "com.treechat.desktop.MainKt"
        nativeDistributions {
            // M5 打包：本机出 macOS dmg；linux-arm64 用 fat jar + 启动脚本（jpackage 跨机不行）
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "条畅"
            packageVersion = "1.41.0"
            modules("java.instrument", "java.management", "jdk.unsupported", "java.naming", "java.sql")
        }
    }
}

// ---- 词库 TSV 桥接（M2-2）----
// etymology/data/* 全走 android.content.Context.assets.open("vocab2.tsv") 等：
// 充面层把 assets.readed 读到 classpath 的 assets/ 下，这里把 app 的 TSV 挂进
// desktop 资源目录（不复制进仓库、不复制 mlkit/web/books 等 40MB 无关资产）。
val assetsBridgeDir = layout.buildDirectory.dir("assets-bridge")
val copyDictAssets = tasks.register<Copy>("copyDictAssets") {
    from(rootProject.file("app/src/main/assets")) {
        include("*.tsv")
        into("assets")
    }
    into(assetsBridgeDir)
}

sourceSets.getByName("main").resources.srcDir(assetsBridgeDir)

tasks.matching { it.name.lowercase().contains("processResources") }.configureEach {
    dependsOn(copyDictAssets)
}

// ---- M5 打包：linux-arm64 便携 zip ----
// jpackage 无法在 macOS 上交叉打 Linux 目标；做法 = 主 jar + 全部 runtime 类 jar 打成 zip，
// 附带启动脚本（JDK 21+ 检测），并补拉 linux-arm64 的 skiko 原生库（本机 runtimeClasspath 只含 macos）。

val skikoLinuxArm64Version = "0.8.4"   // 对齐 CMP 1.6.11 的 skiko 原生版本（运行时 classpath 可见 macos 同版）
val skikoLinuxArm64Url = "https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-linux-arm64/$skikoLinuxArm64Version/skiko-awt-runtime-linux-arm64-$skikoLinuxArm64Version.jar"

val downloadSkikoLinuxArm64 = tasks.register("downloadSkikoLinuxArm64") {
    val targetFile = layout.buildDirectory.file("skiko-linux/skiko-awt-runtime-linux-arm64-$skikoLinuxArm64Version.jar")
    outputs.file(targetFile)
    onlyIf { !targetFile.get().asFile.isFile }
    doLast {
        val dest = targetFile.get().asFile
        dest.parentFile.mkdirs()
        val conn = URI(skikoLinuxArm64Url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        try {
            conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
    }
}

val linuxDistDir = layout.buildDirectory.dir("linux-arm64-dist")
val collectLinuxRuntimeJars = tasks.register<Copy>("collectLinuxRuntimeJars") {
    dependsOn(tasks.named("jar"), downloadSkikoLinuxArm64)
    from(configurations.getByName("runtimeClasspath"))
    from(downloadSkikoLinuxArm64.map { it.outputs })
    into(linuxDistDir.map { it.dir("lib") })
}

val packageLinuxArm64Zip = tasks.register<Zip>("packageLinuxArm64Zip") {
    dependsOn(collectLinuxRuntimeJars)
    archiveFileName = "talktree-linux-arm64-1.41.0-desktop.3.zip"
    destinationDirectory = layout.projectDirectory.dir("../release/desktop")
    from(linuxDistDir)                                              // lib/**
    from(layout.projectDirectory.dir("scripts/linux")) { include("talktree.sh") } // 启动脚本
    from(rootProject.file("app/src/main/assets")) { include("*.tsv"); into("assets") } // 词库桥
}
