# 条畅 Linux ARM64 / macOS 移植评估提纲

> 给接手移植的会话：开工先读本文件与 `HANDOFF.md`，然后 `git checkout desktop-port`。
> 本文件只做盘点与方案，不做实现；实现按第四节的顺序推进，每个稳定阶段回合并 master。

## 0. 目标与形态

单一代码库、多平台目标（Compose Multiplatform 或"桌面模块 + 纯 JVM 核心"两条路线见 §3），
同一 `versionName`，桌面产物进 `release/desktop/`。**不做源码 fork 双线维护。**

## 1. Android 专属依赖盘点（必须替换或隔离的）

| 依赖 | Android 现状 | 桌面替代 | 工作量级 |
|---|---|---|---|
| 数据库 | Room（纪律：桌面**可以**换库，安卓的"不改表/列/migration"纪律仍只约束安卓） | SQLDelight（JVM 原生）或低层 sqlite-jdbc | 中：抽 Repository 接口，desktop 实现 |
| PDF 渲染 | PdfRenderer（框架 API） | PDFBox / pdfium-jvm | 中：`Image` 源从 bitmap 换成 JVM 位图 |
| OCR | ML Kit（Google 闭源 so） | 桌面 v1 **不做**扫描档；v2 评估 onnx PaddleOCR | 小（v1 直接禁用） |
| 本地 LLM | llm.cpp CPU + LiteRT-LM NPU | llama.cpp 重编为 macOS/Linux .dylib/.so；**NPU 引擎直接砍掉** | 中：JNI 重新编译 + ABI 配置 |
| 云端 LLM | aiClient HTTP | 原样可用（纯 Kotlin HTTP） | 无 |
| SharedPreferences | 框架 API | JVM Properties / kotlinx 文件 | 小：包一层接口 |
| Assets | AAPT assets | JVM classpath resources | 小：路径拼接隔离 |
| `Context` 全局 | 到处传 | 抽 `AppEnv` 接口（文件目录/UUID/剪贴板/打开链接） | 中：这是**最大**的一处横切 |
| 浮层小窗/对话框 | Compose 自有机制 | Compose Desktop Window API 重做浮层 | 大：交互面最广，放最后 |

## 2. 已知的好消息

- 选词核心（`MessageSelectionController` / `SelectableMarkdown`）是纯 Compose 逻辑，可直接进 common；
  桌面只有鼠标/触控板，`InputMode` 的触屏档在桌面等价于"用不上但保留"。
- 表格/引文/超链接渲染全部走同一份 Markdown 管线，无平台依赖。
- 词典（VocabIndex etymology tsv）是纯文件解析，直接可用。
- 网络层、房间外labeled prefs（SharedPreferences 系 ReaderPanelState 等）逐个包接口即可。

## 3. 路线决策（开工第一件事，二选一）

- **A. Compose Multiplatform**：`commonMain / androidMain / desktopMain` 三源集。
  工作量大但一次到位；代码库 130+ 文件全要过一遍归属。
- **B. 桌面独立 Gradle 模块 + "核心直链"**：先让 JVM 版编译过（把 Android 专属类换成桌面桩/实现），
  UI 层直接 import 共享包。速度最快，先见"竖起来的壳"，再逐步归位到 KMP 正形。
  建议：**先 B 后 A**（B 是 A 的前半段，不浪费工作量）。

## 4. 里程碑（每完成一个，回合并 master 并验安卓双 flavor 仍能编译）

1. **M1 壳**：桌面模块编译通过；跑起一个空 Compose Window；`AppEnv` 抽象落地。
2. **M2 阅读**：EPUB/TXT 打开 + 分页 + SelectableMarkdownText 选词全通。
3. **M3 对话**：云端模型通路 + 消息树 UI + 高亮/词源管线。
4. **M4 词库/生词**：VocabIndex 考纲匹配 + 生词侧栏。
5. **M5 工程化版本**：句法 API + 同文本超链接 + 笔记，然后打包 dmg/AppImage。
   （本机无 Linux 环境：Linux ARM64 需另一台机器或交叉编译/CI，先做 macOS。）

## 5. 版本与交付（沿用既有约定）

`versionName` 全平台同源；`versionCode` 仅安卓。桌面预览：`1.4x.0-desktop.N`。
产物命名：`release/desktop/talktree-macos-arm64-<ver>.<ext>` / `talktree-linux-arm64-<ver>.<ext>`。

## 6. 移植会话的纪律（与安卓线共同约束）

- 不动 androidx 之外的安卓发版细节；改共享文件前先确认 master 是否有未合并的发版改动。
- 每阶段结束：`git commit`（消息 = 本阶段交接），必要时回写本文件。
- 简体汉语界面、MD3 矢量、无 emoji 等全项目纪律不变。

## 7. 执行记录

### M1 桌面壳（2026-10-17，已完成）

路线决策：按 §3 建议走 **路线 B**。已落地：

- `:core`（纯 JVM 模块）：`com.treechat.core.AppEnv` + `AppFile` 接口——
  §1「Context 全局」抽象的落点。安卓侧尚未接入，接入安排在 M2/M3 做横切替换。
- `:desktop`（Compose Desktop 模块，1.6.11 + Kotlin 2.0.0，JVM 21）：
  空 Compose Window 跑通；`DesktopAppEnv` / `DesktopAppFile`（java.io + AWT 剪贴板/浏览器）实现已过冒烟：
  文件读写、UUID、剪贴板均通过（窗口截图存 `screenshots/desktop-m1-shell-window-20261017.png`）。
- 工程接线：`settings.gradle.kts` 增加 `include(":core", ":desktop")`；
  根 `build.gradle.kts` 增加 `kotlin("jvm") 2.0.0` / `org.jetbrains.compose 1.6.11` 的 `apply false` 声明
  （子模块不带版本引用，否则报 "already on the classpath with an unknown version"）。
- 验证：安卓 `:app:compileFullReleaseKotlin` + `:app:compileLiteReleaseKotlin --offline` 全绿；
  桌面 `:desktop:run` 实机出窗。依赖全部来自既有镜像（aliyun）与 Maven Central 首次在线拉取，
  已进 Gradle 缓存，此后可离线。

M2 预备知识（给下个会话）：
- 桌面侧执行命令：`JAVA_HOME=tools/jdk21 ./gradlew :desktop:run --console=plain`
  （系统 JDK 26 仍会让 Gradle 报一行 `26`，同安卓线的坑）。
- 冒烟入口：`:desktop:run --args="--appenv-smoke"`（无窗口，验证 AppEnv 全链路）。
