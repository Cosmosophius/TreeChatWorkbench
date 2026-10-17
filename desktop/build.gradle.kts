// Compose Desktop 壳（docs/PORTING.md M1 里程碑）。
// 版本口径（PORTING.md §5）：versionName 全平台同源，桌面预览尾缀 -desktop.N。
// 本模块只做「壳」：把窗口跑起来并接入 :core 的抽象，不含业务功能。
plugins {
    kotlin("jvm")
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 分发（与安卓 :app 同款插件）。
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core"))

    // currentOs = 当前开发/运行平台的桌面工件（本机为 macos-arm64）。
    // 出 linux-arm64 版时不改此处：JVM 字节码平台无关，仅 skiko 原生库随平台切换。
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.treechat.desktop.MainKt"
    }
}
