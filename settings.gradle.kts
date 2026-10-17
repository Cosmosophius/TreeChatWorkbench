pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "TreeChatWorkbench"
include(":app")
// 桌面移植线（docs/PORTING.md §3 路线 B）：
//  - :core     纯 JVM 共享代码（AppEnv 等跨平台抽象），安卓侧后续逐步接入
//  - :desktop  Compose Desktop 壳（M1：空 Compose Window 跑起来）
// 此 include 不改变 :app 的任何行为；M1 收尾会回验安卓双 flavor 离线编译。
include(":core", ":desktop")
