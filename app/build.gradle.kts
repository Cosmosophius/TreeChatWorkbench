plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.treechat.workbench"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.treechat.workbench"
        minSdk = 29
        targetSdk = 34
        versionCode = 81
        versionName = "1.41.29"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("compat") {
            // 必须与设备上已安装版本**同一张证书**（apk_inspect/release.keystore，
            // PKCS12 / alias=talktree），否则新包无法覆盖安装，只能卸载重装 ——
            // 卸载会连带清掉用户书库、笔记与本地模型配置。
            // 用 debug keystore 签出来的包证书不同，装不上去。
            val releaseKeystore = rootProject.file("../apk_inspect/release.keystore")
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = "123456"
                keyAlias = "talktree"
                keyPassword = "123456"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            // 关键：release 必须开 R8。
            // debug 包不做优化，Compose 在 debug 下的帧时间可以是 release 的数倍；
            // 开 R8 是"丝滑"的前提，不是可选项。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (rootProject.file("../apk_inspect/release.keystore").exists()) {
                signingConfig = signingConfigs.getByName("compat")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("Boolean", "FEATURE_DICTIONARY", "true")
            buildConfigField("String", "EDITION_NAME", "\"Full\"")
        }
        create("lite") {
            dimension = "edition"
            buildConfigField("Boolean", "FEATURE_DICTIONARY", "false")
            buildConfigField("String", "EDITION_NAME", "\"Lite\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "lib/arm64-v8a/*.so"
        }
    }
}

dependencies {
    // AndroidX & KTX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose BOM & Core UI
    val composeBom = platform("androidx.compose:compose-bom:2024.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")

    // Material 色彩算法（Hct / TonalPalette）。
    // 只用它的 color.utilities 子包，从任意种子色生成完整 M3 配色 ——
    // 自己手写十六进制配色无法保证满足 M3 的对比度配对规则。
    // 注意：该库 minSdk 为 21，低于本应用的 29，不会抬高安装门槛。
    implementation("com.google.android.material:material:1.12.0")

    // ViewModel & Lifecycle for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // Room Database (Tree-based conversation store)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Networking & SSE for AI Streaming
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 谷歌 ML Kit 离线中文文字识别（bundled：模型随 APK 分发，不联网、不走 Play 服务下载）。
    // 扫描版 PDF 导入（ocr/PdfOcr.kt）与阅读器实况选词（ocr/OcrEngine.kt）都依赖它。
    // 离线模型资产见 assets/mlkit-google-ocr-models/**，
    // native 流水线见 jniLibs/arm64-v8a/libmlkit_google_ocr_pipeline.so。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 注：谷歌 LiteRT-LM 端侧推理引擎（NPU/GPU/CPU 多后端）的依赖
    // （tools/litertlm-aar/nomod/litertlm-classes.jar）与对应的 keep 规则
    // 已随"本地大模型整体移除"一并删除。OCR 不受影响：它走的是上面的 ML Kit。

    // PDF 文本层「直抽」快路径（Apache PDFBox 的 Android 移植，Apache-2.0）。
    // 文字版 PDF（电子书 / 导出的讲义）带文本层，直接抽字符即可；
    // 只有抽不到（扫描件、字体缺 ToUnicode CMap）才回落 PdfOcr 整本 OCR
    // —— 见 rag/PdfTextExtractor.kt 与 rag/DocumentParser.kt 的 .pdf 分支。
    //
    // 版本 2.0.27.0：Maven Central 上该库**当前最新的已发布版本**
    // （对齐 Apache PDFBox 2.0.27；更早的 1.8.x 不适用）。
    // group 用的是连字符 `com.tom-roush`，而代码里的包名是下划线 `com.tom_roush`
    // —— 这两个写法不同名，写错 group 会直接 "Failed to resolve"。
    //
    // 代价（已知且已接受）：它会带入 bouncycastle 1.72（bcprov/bcpkix/bcutil）
    // 三个传递依赖，加上 glyphlist/AFM/内置字体的 assets，APK 会明显变大。
    // 这条依赖的 `proguard.txt` 会被 AGP 自动合并，加密相关的反射 keep 规则无需手写。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Debugging
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
