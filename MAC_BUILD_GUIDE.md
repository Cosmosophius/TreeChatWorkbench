# 条畅 / TreeChat Workbench — macOS 源码构建指南

> 本文档随源码包一同交付。目标：让你在 Mac 上**彻底脱离 smali**，用 Gradle 源码继续修。
> 交接文档在 `docs/` 目录，架构说明见 `README_ARCHITECTURE.md`。

---

## 一、这份包是什么

`TreeChatWorkbench` 是条畅（应用名"条畅"，包名 `com.treechat.workbench`）的
**纯原生 Kotlin + Jetpack Compose 源码工程**，对应版本 **v1.24.0**（versionCode 24）。

它不是反编译产物，而是逐版演进的真源码：

| 项目 | 值 |
|---|---|
| 语言 | 100% Kotlin + Jetpack Compose |
| Kotlin 源文件 | 65 个 `.kt` |
| 原生代码 | 2 个 `.cpp`（`llama-jni.cpp` + CMake 构建脚本） |
| 包名 / applicationId | `com.treechat.workbench` |
| compileSdk / targetSdk | 34 |
| minSdk | 29（Android 10） |
| ABI | 仅 `arm64-v8a` |
| 数据库 | Room 2.6.1 |
| 网络 | OkHttp 4.12.0 + okhttp-sse（OpenAI 兼容 + Anthropic 双协议流式） |

**已验证**：本包在 Windows 端以 `gradle 8.7 + JDK 21` 完整跑通 `assembleRelease`，
产出 `app-release.apk` = **6,254,564 字节**，与交付目录中的
`条畅_TalkTree_v1.24.0_release.apk` **逐字节同尺寸**。也就是说，这份源码就是产出该正式包的那份源码，没有版本漂移。

---

## 二、为什么 smali 走不通

这不是"smali 不好用"，而是**这套代码的结构决定了改 smali 必然反复出错**：

1. **Compose 编译器插件**会把每个 `@Composable` 函数改写成带
   `Composer` / `$changed` 参数的合成函数，还有 `$composer`、`RecomposeScope` 等。
   源码里一行布局，smali 里是几十行寄存器操作。
2. **KSP 代码生成**：Room 的 `AppDatabase_Impl`、`*Dao_Impl` 全是构建期生成，
   smali 里改完，下次构建就被覆盖。
3. **Kotlin 协程**编译成状态机（`invokeSuspend` + `label` 跳转），流式 SSE 的
   逐字推送在 smali 里几乎是不可读的跳转表。
4. **R8 / 混淆**：release 包字段名被混淆，smali 里看到的 `a`、`b` 与源码
   毫无对应关系——而这恰恰是你们已经踩过的坑
   （见 `app/proguard-rules.pro` 里记录的 Gson 泛型擦除闪退）。

结论：**用源码改**，改完直接构建。smali 只适合做"取证"（确认某个包里到底有没有某个东西），
不适合做修改载体。

---

## 三、macOS 环境准备

### 1. JDK 21（必需）

工程用 `JavaVersion.VERSION_21`，Kotlin `jvmTarget = 21`。JDK 17 会编译失败。

```bash
# 推荐 Temurin 21
brew install --cask temurin@21

# 验证
java -version        # 应显示 21.x
/usr/libexec/java_home -v 21
```

如果装了多个 JDK，设一下：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### 2. Android SDK

```bash
brew install --cask android-commandlinetools

export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --install "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125" "cmake;3.22.1"
```

或者直接装 Android Studio（推荐，见第五节），在 SDK Manager 里勾选上述四项。

### 3. 必需的 SDK 组件

| 组件 | 版本 | 说明 |
|---|---|---|
| `platforms;android-34` | 34 | compileSdk |
| `build-tools;34.0.0` | 34.0.0 | 打包与签名 |
| `ndk;26.1.10909125` | 26.1.10909125 | 编译 `libllama.so` |
| `cmake;3.22.1` | 3.22.1 | `build.gradle.kts` 里写死了这个版本 |

> ⚠️ **NDK 版本务必对齐**。`app/build.gradle.kts` 里**没有**写 `ndkVersion`，
> 于是 AGP 8.5.2 会用它的默认值 `26.1.10909125`。若你 Mac 上装的是别的 NDK，
> 轻则警告、重则原生编译行为与 Windows 端不一致。
> **建议**：在 `app/build.gradle.kts` 的 `android { }` 里显式加一行
> `ndkVersion = "26.1.10909125"`
> 这样两端可复现。（本次交付未擅自改动该文件。）

---

## 四、构建步骤

```bash
cd TreeChatWorkbench

# 1) 指定 SDK 路径（local.properties 是机器相关的，包里已刻意不含，需你自己生成）
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 2) 给 wrapper 执行权限（zip 传输会丢掉权限位，这一步不能省）
chmod +x gradlew

# 3) 构建
./gradlew assembleRelease      # 正式包
./gradlew assembleDebug        # 调试包
./gradlew installRelease       # 直接装到已连接的设备
```

首次构建需要联网下载 Gradle 8.7 发行版与全套依赖，**约 3～10 分钟**；
之后增量构建通常在一分钟以内。

**产物路径**：`app/build/outputs/apk/release/app-release.apk`

### 依赖下载与镜像

`settings.gradle.kts` 里已经把**阿里云镜像放在最前面**（`pluginManagement` 与
`dependencyResolutionManagement` 两处都是），其次才是 `google()` 和 `mavenCentral()`：

```kotlin
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/public") }
```

国内网络一般不用改。若你在中国大陆以外、或想改用别的镜像，改这两处即可。

---

## 五、强烈建议用 Android Studio

这套代码里最大的文件接近 **102 KB 单文件**
（`ui/knowledge/KnowledgeManagerDialog.kt`、`ui/settings/SettingsDialog.kt`），
`MainActivity.kt` 68 KB，`ui/tree/TreeTopologyView.kt` 70 KB。

命令行构建只能告诉你"第几行有错"，但在这种体量的文件里定位就是折磨。
Android Studio 能给你：编译错误即点即跳、Compose 预览、重构改名、断点调试。

```
Android Studio → Open → 选择 TreeChatWorkbench 目录（不是 app 子目录）
```

打开后按提示 `Sync Project with Gradle Files` 即可。

---

## 六、四个会咬人的坑（务必先看）

### 坑 1：release 用的是 debug 签名，Mac 上覆盖安装会失败

`app/build.gradle.kts` 里有个 `compat` 签名配置，**故意用 `~/.android/debug.keystore`
给 release 包签名**。这样 release 包能直接覆盖安装此前 debug 签名的版本，
不必先卸载（卸载会连带清掉用户已填好的 API 配置）。

**问题在于**：Mac 上首次构建时，`~/.android/debug.keystore` 不存在或与 Windows
上那个不是同一份，生成的签名不同 → 尝试覆盖安装现有 APK 会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

**对策（三选一）**：

1. 先在设备上卸载旧版再装（会丢用户数据，不推荐日常用）；
2. 把 Windows 端 `C:\Users\26258\.android\debug.keystore` 拷到 Mac 的
   `~/.android/debug.keystore`，两端签名保持一致（**推荐**）；
3. 正经做法：生成独立 release keystore，把签名配置改成从
   `keystore.properties`（不进版本库）读取。对外发布本来就该这么做，
   源码注释里也写了这句。

### 坑 2：`jniLibs/arm64-v8a` 是空目录，这是**故意的**

`libllama.so` 由 CMake 从 `app/src/main/cpp/llama-jni.cpp` **现场编译**，
随构建自动打进 APK 的 `lib/arm64-v8a/`。

**不要**手工把 `.so` 拷进 `jniLibs/`——那会让同一个库经
jniLibs 与 CMake 两条路径进入打包，触发 duplicate library 冲突。
目录下的 `.gitkeep` 里有同样的说明。

### 坑 3：`local.properties` 不在包里

它记录的是 Windows 的 SDK 路径（`sdk.dir=C:\Users\26258\...`），
在 Mac 上是错值，且它是机器相关文件、不该进版本库。
按第四节第 1 步自己生成即可。

### 坑 4：别把缓存和产物带进版本库

工程里会出现 `.gradle/`、`.kotlin/`、`app/build/`、`app/.cxx/`，
这些都不该提交。建议加一份 `.gitignore`：

```gitignore
.gradle/
.kotlin/
build/
app/build/
app/.cxx/
local.properties
.idea/
*.iml
*.jks
*.keystore
```

---

## 七、这份包对 v1.24.0 的现状

### 已在包内、且经构建验证

- 全部 65 个 `.kt` 源码，包含 v1.24.0 的交接增量改动
  （Word 式浮动提问栏 `FloatingSelectionToolbar`、25dp 水滴手柄 + 48dp 热区）
- `proguard-rules.pro`：包含 R8 的 keep 规则，特别是
  `data.prefs.**` 那条——它修的是一个**真实线上闪退**
  （`GraphLayoutSnapshot` 的 `List<Float>?` 被 R8 抹掉泛型 →
  Gson 读成 Double → `ClassCastException`）。**删掉这条会复发。**
- 原生层：`CMakeLists.txt` + `llama-jni.cpp`，能正常编出 `libllama.so`
- Gradle wrapper 三件套（`gradlew` 已归一为 LF 行尾，Mac 可直接执行）

### 构建期的已知噪音（不影响构建）

Kotlin 编译会报 **14 条 `Icons.Filled.*` 弃用告警**（`MenuBook`、`ViewList`、
`ArrowBack`、`DriveFileMove`、`HelpOutline`），提示改用
`Icons.AutoMirrored.Filled.*`。这是纯告警，构建照常出包。
想清掉就按提示换掉引用即可。

### 需要你继续处理的部分

你在 Mac 上"已经修了很多、还得继续修"的那些，包内**不含**对应改动——
本包是 v1.24.0 的源码基线。你在 Mac 上的修法建议改为：

1. 用这份源码在 Mac 上构建出一个基线 APK；
2. 与你手上 Mac 反编译后修改过的版本做行为对照，把**已修的改动**逐个回收进源码；
3. 之后一律在源码上改。

`docs/` 下有三个交接文档（v1.24.0 增量、v1.23.0、总交接），
记录了各版本的改动动机与踩坑，回收改动时对着看能省很多事。

---

## 八、Windows 端验证记录（供你核对复现）

| 项 | 值 |
|---|---|
| Gradle | 8.7（`distributionUrl` 指向 `gradle-8.7-bin.zip`） |
| Android Gradle Plugin | 8.5.2 |
| Kotlin / Compose 插件 | 2.0.0 |
| KSP | 2.0.0-1.0.21 |
| JDK | Microsoft OpenJDK 21.0.10 |
| NDK | 26.1.10909125 |
| CMake | 3.22.1 |
| 构建命令 | `gradle -p <proj> assembleRelease --no-daemon` |
| 结果 | `BUILD SUCCESSFUL in 4m 24s`，54 tasks executed |
| 产物 | `app-release.apk`，6,254,564 字节 |
| 签名 | CN=Android Debug（符合源码 `compat` 配置的设计意图） |
| APK 内原生库 | `lib/arm64-v8a/` 下 2 个 `.so`（1,330,832 与 32,688 字节） |

---

## 九、包内文件清单

```
TreeChatWorkbench/
├── MAC_BUILD_GUIDE.md              ← 本文档
├── README_ARCHITECTURE.md      ← 架构与三大设计原则说明
├── build.gradle.kts            ← 根构建设置（AGP/Kotlin/KSP 版本）
├── settings.gradle.kts         ← 模块与仓库（含阿里云镜像）
├── gradle.properties
├── gradlew / gradlew.bat       ← wrapper 脚本（gradlew 已为 LF）
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties   ← 指向 gradle-8.7-bin.zip
├── docs/                       ← 历史交接文档
│   ├── handoff_v1.24.0_increment.md
│   ├── handoff_v1.23.0.md
│   └── handoff_MASTER.md
└── app/
    ├── build.gradle.kts        ← 应用模块配置（签名、R8、CMake、依赖）
    ├── proguard-rules.pro      ← R8 keep 规则（含闪退修复记录）
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/                ← CMakeLists.txt + llama-jni.cpp
        ├── java/com/treechat/workbench/   ← 65 个 .kt
        ├── jniLibs/arm64-v8a/  ← 空目录（见坑 2）
        └── res/
```

共 95 个文件，约 1.45 MB（未压缩）。

### 关于文件名

包内**所有文件名与目录名一律用 ASCII**（`MAC_BUILD_GUIDE.md`、
`handoff_v1.24.0_increment.md` 等），文件**内容仍是完整中文**。

原因：本包在 Windows 上打包时，PowerShell 5.1 存在一个已知缺陷——
把非 ASCII 中文文件名交给压缩 API 时会按 ANSI 码页二次编码，
导致条目名损坏（实测出现过"总"字被替换成 `?`、解包报
"路径中具有非法字符"而中途失败）。改用 ASCII 文件名可彻底规避，
在任何系统上都能完整解包。中文说明都在文本内容里，没有任何丢失。
