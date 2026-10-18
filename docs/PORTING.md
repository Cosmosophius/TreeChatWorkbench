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
- 【2026-10-17 用户指令】**桌面版浮层小窗不沿用安卓的常驻覆盖层实现**，
  要做成真正的独立顶层窗口（Compose Desktop 多 Window）。
  `FloatingWindowManager` 的 API 形状保留对齐，渲染壳换成桌面 Window。

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
- CLI 直开一本书：`:desktop:run --args="--open=/path/to/book.epub"`。

### M2 阅读（2026-10-17，已完成——窗口版）

路线 B 完整落地的第一个功能里程碑。要点：

- **共享源直编**：`:desktop` 的源集把 `app/src/main/java/com/treechat/workbench` 挂为 srcDir，
  白名单收 `ui/selection`·`ui/markdown`·`ui/theme`·`ui/reader`·`etymology/data`·`data/{model,prefs}`
  ·`network`·`rag`·`util`·`task`·`safety` 等包；`SelectionModel.kt` + `SelectableMarkdown`·`IncrementalMarkdown`
  （选词/分词/高亮核心）、`AdaptiveDocumentView`（自适应分页+翻页动画+点击分区）等
  共享源**一行未改**直接编译。
- **充面层**（`desktop/src/main/kotlin/androidcompat/`）：`android.content.Context`
  （SharedPreferences→Properties 落盘、assets→classpath、getSystemService）、`android.util.Log`、
  `android.os.{SystemClock,Build}`、`android.graphics.{Bitmap,Typeface}`（+`FontFamily(typeface)` 桥、
  `Bitmap.asImageBitmap()` 桥）、`android.content.{ClipboardManager,ClipData}`（AWT 剪贴板）、
  `android.icu.text.BreakIterator`（委托 icu4j）、`androidx.compose.ui.platform.{LocalContext,LocalConfiguration}`
  （桌面 CMP 1.6.11 无这些符号，实测 jar 零命中后纯增量声明）、`androidx.compose.material3.dynamic{Dark,Light}ColorScheme`
  （Build 充面恒 30 走不到）、`androidx.compose.ui.res.painterResource(Int)`（占位 Painter，仅两处图标 5 冲）。
- **桌面替身**（同包同名同 API 面）：`ocr/{PdfPageSource,OcrEngine,PdfOcr}`（PDFBox 2.0.27 渲染页 +
  文字坐标词框登记；`writeString(String, List<TextPosition>)` 钩子逐词取盒，`PDFTextStripperByWord` 在
  2.0.27 里不存在——实测）、`ui.reader.DocumentFileReader`（File 版导入，纯函数与安卓逐字同源）、
  `rag.DocumentParser`（EPUB/DOCX/PPTX/MOBI/TXT 全保留，URI→字节入口）、`rag.PdfTextExtractor`、
  `ui.reader.CoverExtractor`（含 `ReaderCoverPrefs`）、`rag.ImagePicker`（仅 splitDataUri）、`util.AppEdition`。
- **排除未编**：`ui/reader/{ReaderScreen,ShelfComponents,SentenceTranslateSheet}`、`ui/browser/**`、
  `ui/{explorer,tree,knowledge,dictionary,settings,notes,search,desktop,window}/**`、
  `data/db/*Dao.kt|AppDatabase.kt|ConversationSearchRepository.kt`、`rag/KnowledgeRetriever.kt`、
  `etymology/engine/IpaAudioSynthesizer.kt`——分别为 M3+ 或桌面 v2 独立重做。
- **词库桥**：11 个 TSV 用 Copy 任务从 `app/src/main/assets` 桥进 desktop resources（不复制不进仓库）；
  `VocabIndex.ensureLoaded(context)` 走充面 assets 直接可用。
- **验证**：`:desktop:compileKotlin` BUILD SUCCESSFUL；`--appenv-smoke` 全链路通过；
  实机窗口打开样例书（`--open=` CLI 入口），`AdaptiveDocumentView` 自适应分页/底栏页码/Aa 排版对话框/
  生词侧栏标签（生词-词条-句法-AI）/翻页进度跨启动记忆 截图证据两份（screenshots/desktop-m2-*.png）。
  安卓 `:app:compile{Full,Lite}ReleaseKotlin --offline` 全绿。
- **须知**：鼠标划选/键盘翻页的手动验证仍在等用户（截图环境无辅助功能授权，无法注入输入事件）；
  书架交互与视觉为桌面 v2 简化版（安卓 ShelfComponents 的卡片视觉 M5 对齐）；
  OCR 扫描盒（无文字层 PDF）在桌面 v1 显示图页但无选词（按 PORTING §1 规划 v2 评估）。

### M3 数据层 + 工作台骨架（2026-10-17 晚，阶段一完成）

- **数据层**：sqlite-jdbc 手写实现五个 DAO 全部契约（含 `getAncestorChain` 递归 CTE、
  双协议投影 `NodeTitleHit/MessageContentHit/ConversationSummary`、Knowledge 批量/事务组合）。
  表结构与安卓 v10 一致；桌面库从 v10 起步不跑迁移。
  失效追踪用 `DbChanges.bump(mask)` 位掩码脉冲 + `observe()` 重读实现（对位 Room invalidation）。
- **共享业务层全量编译**：ChatNodeViewModel / ChatMessageView / 探索页 / 导图 / 词典 / 笔记 /
  设置 / 命令面板 / export(TalkTreeBundle, ZipExporter) ——共享源一行未改。
  ReaderScreen(2115 行) + ShelfComponents(55KB 书架) + 五页签 ReaderSidePanel 也已收进来，
  安卓 v1.41.24 的共享层增量（SourceMark 通道、词条 tags/levels、句对翻译 P5）合并后同步编入。
- **充面层新增**：android.net.Uri／android.app.{Application,Activity}／androidx.lifecycle.AndroidViewModel、
  android.database.Cursor、androidx.core.view.{WindowCompat,WindowInsets*}（沉浸退化 no-op）、
  android.view.{View,Window}＋LocalView/LocalConfiguration 桌面版、android.graphics.BitmapFactory、
  FileProvider/Intent 桥、ActivityResultContracts[]>(OpenDocument/OpenMultipleDocuments/PickMultipleVisualMedia)、
  androidx.activity.compose.BackHandler。
- **独立顶层小窗**（用户指令）：FloatingWindowManager API 不变，
  SCREEN 空间小窗在 application 作用域逐个渲染为 Compose Desktop 独立 Window
  （关闭/置顶直接映射 manager）。
- **桌面 Speaker**（handoff 增量点 1）：`data.reader.Speech.kt` 共享侧排除，
  `desktop` 侧同名 `Speaker`/`DesktopSpeaker`/`AndroidSpeaker`（say → espeak-ng → espeak，探测失败静默）。
- **验证**：`:desktop:compileKotlin` 全绿；工作台实机截图（七模式药丸 Tab/新建会话 FAB/
  provider 快击/设置入口）`screenshots/desktop-m3-workbench-shell-20261017.png`；
  安卓双 flavor `--offline` 编译全绿。冒烟 `--appenv-smoke` 与 CLI `--open=` 阅读入口均可用。
- **待办（M3 阶段二起）**：会话创建后的 ChatNodeViewModel 流接线实测（需真点击）；
  对话气泡 P6；命令面板接 CommandPaletteDialog；句对翻译窗（SentenceTranslateSheet 已编译，入口接侧栏）；
  linux-arm64 打包。

### M3 阶段一点五：云盘桌面对面（v1.41.26 合并，2026-10-17 深夜）

按 handoff「增量二」执行：

- **`CloudFolderStore` 桌面替代**（共享 SAF 版排除）：普通文件夹 + java.io.File，同签名
  （folderUri/hasFolder/setFolder/clear/listChildren/findByName/putFile/readFile/delete + CloudItem）。
  语义映射：树 URI = `file://<目录>`；documentId = 文件绝对路径；
  `takePersistableUriPermission` 充面为 no-op（文件夹即路径，无权限模型）。
  书架云盘 UI（拖书上传/面板/导入书架/存文件/换文件夹）原样复用。
- **非 OAuth**：GoogleDriveClient.kt 是纯 SAF 改造后的 zero-referer 死代码，
  排出共享 include，不移植不复活（handoff 明令）。
- **android.os.Environment 充面**：「存到 Download/条畅」走 `user.home/Download`。
- **ActivityResultContracts.OpenDocumentTree** 合约 + 工作台总线分派：
  目录选择走 javax.swing.JFileChooser（目录唯一模式），其它合约照旧 AWT FileDialog 多选模式。
- **验证**：共享云盘 UI + 拖拽 + 批量条全量编译过；冒烟与安卓双 flavor 离线编译全绿。
  拖拽跟手幽灵/真实上传需用户真点击（工作台有待接 UI 时与 shelf 一并测）。

### M4 收口 + M5 打包（2026-10-18，阶段完成）

- **词典/墨迹接线**：工作台 Tab 里 `DictionaryScreen`（onAskWord→`createQuestionInSourceTree`+
  openSubWindow，照抄安卓接线语义）与 `InkWorkbenchScreen`（InkNoteStore 直连管理 boards；
  「画布快照」「导出 PDF/图片」两个入口留 M5 F 处收口，地址不变）。
- **ZipExporter 桌面替身**（同 Markdown/talktree 打包口径）：`shareFile` 从 Intent 链
  换保存对话框（javax.swing JFileChooser，改名自动补 .zip）。
- **命令面板**：工作台 Ctrl/Cmd+K 动作面板、Ctrl/Cmd+F 检索初始态输入——共享
  `CommandPaletteDialog`（v1.41.24+），不改一行。
- **M5 交付**（`release/desktop/`）：
  - `talktree-macos-arm64-1.41.0-desktop.3.dmg`（内嵌 jlink runtime；实测进程稳定启动，
    UI 手势循环日志在产）
  - `talktree-linux-arm64-1.41.0-desktop.3.zip`（jar 集合 + `talktree.sh` 启动脚本 +
    词库 TSV assets + linux-arm64 skiko 原生库；jpackage 不能在 macOS 交叉打 Linux，故用 zip 方案）
- **两个实机修复（对移植方极总要）**：
  1. sqlite schema：SQLite 外键语法是 `ON DELETE SET NULL`（带空格）——写成 `SET_NULL`
     会让 conversations 表建不出来（首次运行即崩在 FolderDao 上）。
  2. **Dispatchers.Main 缺失**：kotlinx-coroutines JVM 在本机 FastServiceLoader 枚举不到
     `META-INF/services/...MainDispatcherFactory`（探针证据：普通 ServiceLoader 能找到、
     内部 loader 返回空）。修法：main() 最先作反射调
     `kotlinx.coroutines.swing.SwingDispatcherFactory.createDispatcher(List)` + 公开 API
     `kotlinx.coroutines.test.Dispatchers.setMain(...)` 注入（先 setMain 再首个 Main 访问）。
     smoketime 校验 `Dispatchers.Main` 从 [missing] 变正常。
- **下一批待办**：墨迹快照/导出 batching；BROWSER 独立顶层窗方案落点；用户真机点验清单
  （会话创建→发消息流式回复→书架拖拽云盘→划选词条→命令面板）。

### M5 补齐：缺口 + 图标（2026-10-18 晚，用户点名）

- **图标**：App 品牌 icns（深底绿环「条」字，`desktop/resources/条畅.icns`，挂 mac bundle
  `macOS { iconFile }` + bundleID）；代码里两处 `painterResource` 占位换真绘制
  （`VectorPlaceholderPainter`：注音角标=圆环+声调波纹；手写=笔杆+笔头），共享代码未改。
- **模型快击菜单完整化**：`DesktopProviderAndModelMenu`（复刻安卓两段结构：
  服务商切换段＋当前服务商模型段——含 ctx 窗口显示、当前项打勾、设置入口；
  模型切换=改 selectedModelId 后 `replaceProviders` 回写）。
- **浏览器 Tab 桌面方案**：`DesktopBrowserPane`（URL 输入 → `AppEnv.openLink` 系统默认浏览器；
  桌面不内嵌 WebView 覆盖层——告知用户与安卓差异及配合方式：网页复制回条畅划词提问）。
- **墨迹**：画布快照真实现（`viewModel.nodes`/`loadNodeSummaries`/`computeGraphLayout`/
  `graphState.positions` → `InkNoteBoard`，照安卓字段）；`InkNoteExporter` 桌面版
  （java2D 渲 PNG + pdfbox 包 PDF；导出走 ZipExporter 保存对话框）。
- **验证**：全量编译绿；smoke Main 正常；安卓双 flavor 离线绿；dmg/zip 重出（含图标）。
- **剩余已知缺口（如实）**：扫描件 OCR 选词（规划为桌面 v2/onnx Paddle 评估）；
  工作台视觉与安卓原版仍有细节差距（药丸 Tab/顶栏等）；AI 对话需用户自行配置服务商密钥后实测。
