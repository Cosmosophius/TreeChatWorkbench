plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.21" apply false
    // 桌面移植线（docs/PORTING.md M1）。版本统一在根声明，子模块不带版本引用，
    // 避免「plugin is already on the classpath with an unknown version」。
    kotlin("jvm") version "2.0.0" apply false
    id("org.jetbrains.compose") version "1.6.11" apply false
}
