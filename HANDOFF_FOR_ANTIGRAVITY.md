# 条畅 TalkTree 项目完整接交手档案 (Handoff for Antigravity)

> **致接手代理 (Antigravity)**：本文件是当前会话耗尽前的最高优先级无损交接档案。请在接手后第一时间完整阅读本文件。

---

## 一、项目核心背景与开发环境

- **应用名**：条畅 TalkTree（多维思维树式对话与离线双语阅读器）
- **包名**：`com.treechat.workbench`
- **代码仓库绝对路径**：`/Users/deepseekworkspace/TreeChatWorkbench`
- **开发栈**：纯 Kotlin + Jetpack Compose + Material 3（禁止 XML 布局，禁止混用其它框架）
- **构建环境与命令**：
  ```bash
  export JAVA_HOME=/Users/deepseekworkspace/tools/jdk21/Contents/Home
  cd /Users/deepseekworkspace/TreeChatWorkbench
  
  # 增量编译检查：
  ./gradlew :app:compileReleaseKotlin -q
  
  # 正式打 Release 包：
  ./gradlew :app:assembleRelease
  
  # 安装至在线 headed 模拟器：
  /Users/deepseekworkspace/android-sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
  ```
- **签名配置**：
  - 密钥库路径：`apk_inspect/release.keystore`
  - 别名：`talktree`
  - 密码：`123456`
  - 已在 `app/build.gradle.kts` 中配置好，直接 `assembleRelease` 即自动完成 v2 签名并对齐。
- **Android SDK 与环境工具**：
  - SDK 路径：`/Users/deepseekworkspace/android-sdk`
  - ADB：`/Users/deepseekworkspace/android-sdk/platform-tools/adb`
  - 当前在线模拟器：`emulator-5554` (ARM64 有界面 headed 模拟器，用户可实时查看)

---

## 二、不可逾越的六大核心纪律（违反即事故）

1. **绝对禁止新增 Room 数据库的表、列或执行 migration**：
   - 用户明确严禁改动 Room 架构。所有新增状态（如文件夹自由摆放坐标、单词本分类文件夹、手写笔迹）**必须全部存入 SharedPreferences 内的 JSON**（使用项目已有的 `Gson`，通过 `AppSettings.PREFS` 存取）。
2. **语言文字纪律**：
   - 严禁出现“中文”字样！必须严格写为**「简体汉语」**（Simplified Chinese）或**「繁体汉语」**（Traditional Chinese）。用户明确指正：“中文不是一种语言”。
   - 界面严禁使用任何彩色 Emoji 符号（如 📌、🗑️ 等），必须统一使用 Material 3 矢量图标（`Icons.Outlined.*` 或 `Icons.Filled.*`）。
3. **架构纪律（非底层全转 Kotlin）**：
   - 本地大模型（llama.cpp、LiteRT-LM、Vulkan）已整体彻底移除，严禁重新引入；
   - 离线 OCR（Google ML Kit）**绝对保留**，不得删除；
   - 必须保持 100% 纯 Kotlin + Jetpack Compose，严禁引入未经批准的 NDK C++ 库。
4. **自主推进，不要反复发问**：
   - 用户的既定工作约定是：“什么都不要问我，一路做完”。
5. **严禁凭空编造事实或数据**：
   - 音标、词源、汉字古音拟音等若未收录，如实展示“未收录”，严禁算法启发式编造。
6. **选词器复用**：
   - 任何涉及文本选词的界面（对话、纯文本阅读器、EPUB、Markdown、表格），**必须 100% 统一复用条畅核心选词器 `SelectableMarkdownText` + `MessageSelectionController`**，仅保留各界面专用的外部工具条选项卡。

---

## 三、当前已全部完成并真机验证的功能清单

1. **彻底移除本地大模型**：APK 从 187MB 瘦身至 39MB，彻底消除设备卡顿，保留离线 OCR；
2. **小窗层级与尺寸上限**：小窗浮层移至全屏最外层，允许自由拖拽覆盖主界面顶栏；小窗宽度上限改为视口动态自适应（可达 1600dp）；最小化标签大头针 emoji 替换为纯正 M3 矢量图钉 `Icons.Outlined.PushPin`；
3. **小窗拖拽快速防回弹**：拖拽与滑行中采用 `snap()` 零延迟直通跟随，剔除同步假 fling，改用协程逐帧平滑衰减，彻底杜绝回弹；
4. **切片索引改手动触发**：关闭导入书籍和 OCR 完成后的自动建立切片索引，书架菜单按状态提供“参与检索（建立切片索引）”，完全由用户手动选择方案；
5. **Markdown 表格文本选词**：通过 `CellLayoutSnapshot` 按单元格登记几何，表格文字完全支持长按选取、高亮与悬浮工具条；
6. **EPUB / 纯文本选词重构**：彻底解决“长按只能选段首 1 个词”缺陷，100% 复用条畅 `SelectableMarkdownText`，支持水滴手柄自由拖选多词、整句、多行，且阅读器原生的 7 个专业选项卡（提问/复制/词源/划线/笔记/翻译/句法）原汁原味保留；
7. **阅读器翻页与沉浸**：双击正文空白切换沉浸防误触、正文左右 1/3 点击翻页、键鼠方向键（`Left/Right/PageUp/PageDown`）换页、平板视口自适应分页、纯 Compose 仿真翻页动画（纯文本与 OCR 扫描文档均适配）；
8. **阅读器手写笔记图层 (`ReaderInkOverlay.kt`)**：顶栏手写笔矢量按钮，开启后在正文上层提供压感钢笔、荧光笔、橡皮擦手写批注，笔迹按书籍与页码自动保存，退出后笔迹常驻显示且完全不影响翻页与选词；
9. **Finder 式文件夹界面 (`FolderExplorer.kt`)**：MD3 矢量图标、左侧位置栏、顶部路径导航、图标与列表双视图切换、自由摆放（坐标存 SharedPreferences JSON）；列表长按直接多选、图标空白处拖拽矩形框选（Marquee Selection）；右下角配备常驻废纸篓矢量组件，支持单个及多选条目直接拖入废纸篓删除；
10. **AI 吐字速度优化与中途断流根治**：OkHttp `readTimeout` 提到 120s 并增加 408/429/5xx 自动续传；ViewModel 采用内存态 `streamingText` 以 16ms 直推界面，Room 落库降频至 500ms，彻底消除每秒 22 次全列表重组卡顿；
11. **选句针对提问 AI 上下文彻底修复**：当前节点与祖先节点的 `originQuote` 统一解析并注入系统提示词与首轮对话背景，AI 100% 具备引用句子的完整上下文，绝不再出现“没有上下文”；
12. **TTS 炸麦修复与古汉语听感优化**：噪声钳位 + 仅浊音归一化 + 爆破余弦斜坡，入声韵尾 26ms 喉塞收束促化 + 声调五点二次贝塞尔平滑曲线；
13. **词典单词本系统**：新增分类文件夹管理体系（支持新建文件夹归类不同单词，存 SharedPreferences JSON），单词本支持【单词卡(3D飞卡栈)】与【MD3列表】双视图切换，支持长按多选批量移出单词本；
14. **同义词下钻与单词提问**：同义词 Chip 点击直接下钻反查，长按弹出条畅提问，所有单词提问统一自动在「单词」文件夹下新建独立 Root 会话树；
15. **工作台卡片关联升级为【画布就地勾选模式】**：彻底摒弃中间弹窗，直接在工作台导图上点选卡片建立下级关联，顶部悬浮胶囊操作条；
16. **设置栏精简与 Embedding 厂商**：彻底移除独立的“检索与知识库”Tab，联网搜索移入“外观与偏好”，在“服务商与模型”新增独立的【Embedding 向量嵌入厂商】配置卡片；
17. **桌面级 I-Beam 文本光标常驻**：对话文本、阅读器正文、浏览器文本在鼠标触控板滑过时自动呈现 Mac/Windows 原生工字竖条光标；
18. **开源鸣谢与致敬面板**：在设置页底部规范收录 IconPark、nk2028 廣韻、rephon、WordNet 3.0、EtymWN、PDFBox-Android、Koodo Reader、WriteBuddy、DrawBox 等开源项目与许可，素扉宣纸出处朴实真诚。

---

## 四、接手后需要继续完成的 4 项新任务

用户在本轮最后提出的 4 个明确需求，方案已制定，接手后请立即落入代码并打包：

### 1. 单个对话内容（单条消息）删除功能
- **文件**：`app/src/main/java/com/treechat/workbench/ui/chat/ChatMessageView.kt`
- **实现**：
  在 `MessageRow` 底部的操作栏（复制、重新生成、整段分叉旁边），增加【删除】按钮（`Icons.Outlined.Delete`）。
  点击后弹出小二次确认弹窗，确认后调用已在 ViewModel 写好的 `viewModel.deleteSingleMessage(message.id)`（底层对应 `dao.deleteMessage(messageId)`）。

### 2. 多语言界面体系（严格禁止写“中文”）
- **要求**：
  增加四种界面语言切换：**「简体汉语」**、**「繁体汉语」**、**「English」**、**「Русский」**。
  必须严格写为「简体汉语」和「繁体汉语」，绝对不能出现“中文”字样。
- **实现**：
  - 在 `data/prefs/AppSettings.kt` 中增加 `var appLanguage by mutableStateOf("zh-CN")`，存入 SharedPreferences；
  - 在「设置 → 外观与偏好」中增加语言切换单选行（四个选项：简体汉语、繁体汉语、English、Русский）；
  - 提供轻量字符串映射表 `AppStrings.get(key, lang)`，覆盖顶栏（文件夹/对话/工作台/笔记/书架/词典）、核心按钮与设置项。

### 3. 文件夹界面长按对话和文件夹可以压缩打包导出 (ZIP)
- **文件**：`app/src/main/java/com/treechat/workbench/ui/explorer/FolderExplorer.kt`
- **实现**：
  在条目的更多菜单 `EntryOverflowMenuContent` 和多选操作栏中增加【压缩打包导出 (ZIP)】。
  点击后递归遍历该文件夹或会话，将所有分支树与消息打包成标准 `.zip` 文件，并调用 Android 系统分享 `Intent.ACTION_SEND` 调起分享发送。

### 4. 对话导出为私有扩展名（`.talktree`）可跨平台导入导出
- **文件**：新建 `app/src/main/java/com/treechat/workbench/export/TalkTreeBundle.kt`
- **格式规范**：
  私有扩展名为 `.talktree`，内部为标准 JSON：
  ```json
  {
    "version": 1,
    "appName": "TalkTree",
    "conversation": { ... },
    "nodes": [ ... ],
    "messages": [ ... ]
  }
  ```
- **导出**：在对话菜单中增加【导出为 .talktree 跨平台档案】，生成文件并分享；
- **导入**：在 `FolderExplorer.kt` 的新建按钮菜单里增加【导入 .talktree 对话】，通过文件选择器选取 `.talktree` 文件后反序列化写入 Room（生成新的会话与节点），无缝还原完整分支树！未来移植 macOS 时直接共用此格式！

---

## 五、完成后的最终验证命令

```bash
cd /Users/deepseekworkspace/TreeChatWorkbench
export JAVA_HOME=/Users/deepseekworkspace/tools/jdk21/Contents/Home
./gradlew :app:compileReleaseKotlin -q
./gradlew :app:assembleRelease
/Users/deepseekworkspace/android-sdk/build-tools/34.0.0/apksigner verify app/build/outputs/apk/release/app-release.apk
```
生成的 APK 交付路径：`/Users/deepseekworkspace/TreeChatWorkbench/app/build/outputs/apk/release/app-release.apk`
