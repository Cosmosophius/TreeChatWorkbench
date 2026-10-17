# ============================================================================
# R8 / ProGuard 规则
# ============================================================================

# ---------------------------------------------------------------------------
# Gson 反射保留（关键）
# ---------------------------------------------------------------------------
# Gson 通过反射按字段名读写这些类。R8 在 full mode 下会重命名甚至删除它们，
# 结果是：配置写入 SharedPreferences 成功，读回来时字段全是 null / 默认值，
# 而且不抛任何异常 —— 典型的静默故障，极难排查。必须显式保留。
-keep class com.treechat.workbench.network.protocol.** { *; }

# 同理：SearchResult 会被序列化进 chat_messages.search_sources 列，
# 再反序列化回来渲染"已检索的网页"。不保留的话来源列表会静默变空。
-keep class com.treechat.workbench.network.search.** { *; }


# ---------------------------------------------------------------------------
# .talktree 跨平台档案（export 包）—— 这一条是修一个**真实崩溃**加的
# ---------------------------------------------------------------------------
# TalkTreeBundle 由 Gson 按字段名序列化/反序列化（version/appName/exportedAt/
# conversation/nodes/messages）。该包此前**不在**任何 keep 列表里，于是 R8 把字段
# 重命名成 a/b/c…，造成两个后果：
#   1. 导出的 .talktree 里键名是混淆后的名字 —— 所谓"跨平台档案"其实只有
#      同一个构建自己读得懂，换版本/换平台就废了（违背该格式的设计目的）；
#   2. 导入一份**按文档格式**生成的档案时，字段映射不上 → bundle.conversation
#      为 null → 导入直接崩溃（实测：NullPointerException on Conversation.getTitle）。
# 实测复现路径：把符合格式的 .talktree 放进设备 → 文件夹页 → ＋ → 导入 .talktree。
-keep class com.treechat.workbench.export.** { *; }

# ---------------------------------------------------------------------------
# 其余被 Gson 写进 SharedPreferences 的界面态类型（同类隐患，一并封住）
# ---------------------------------------------------------------------------
# 同一构建内自洽，所以不会当场出错；但**应用升级后**字段名一变，
# 用户已保存的图标摆放/单词本文件夹就会静默读空。宁可多留几个类名。
-keep class com.treechat.workbench.ui.explorer.FreePosition { *; }
-keep class com.treechat.workbench.ui.dictionary.WordFolder { *; }

# Room 实体由生成的 Dao_Impl 直接读写字段，Room 自身带 consumer rules，
# 但这里再显式保留一层，避免字段名被混淆后与 SQL 列名不一致。
-keep class com.treechat.workbench.data.model.** { *; }

# data.prefs 下是**会被 Gson 序列化进 SharedPreferences** 的类
# （GraphLayoutSnapshot / GraphNodeLayout、以及各 Store 用到的配置模型）。
#
# ⚠️ 这一段是修一个**真实线上闪退**加的，删掉就会复发：
#   GraphLayoutSnapshot 的 xs/ys/ws/hs 声明为 List<Float>?，但此前该包
#   **不在任何 keep 名单里**，R8 把字段的泛型信息抹成了裸 List
#   （已从 APK 的 DEX 取证：字段全为 Ljava/util/List;，
#     且整个 DEX 的 dalvik.annotation.Signature 注解数为 0）。
#   Gson 反射拿不到 Float 类型参数 -> JSON 数字被读成 java.lang.Double ->
#   Kotlin 侧按 Float 取值时抛
#   ClassCastException: java.lang.Double cannot be cast to java.lang.Float，
#   触发场景是"动完卡片位置后切回对话"。
#
# 注意：上面那条 `-keepattributes Signature` **单独写是不够的** ——
#   它只作用于「被 keep 的类」。类本身没进 keep 名单时，签名照样被剥掉。
#   这一点很容易误判为"已经防住了"，务必连同这条 -keep 一起看。
-keep class com.treechat.workbench.data.prefs.** { *; }

# Gson 解析 List<AiModelInfo> / List<ProviderConfig> / List<SearchResult>
# 这类泛型需要签名信息。必须与上面的 -keep 配合才生效（见上一条说明）。
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ---------------------------------------------------------------------------
# 网络层
# ---------------------------------------------------------------------------
# OkHttp / Okio 的可选依赖在本项目中并未引入，忽略它们的引用警告
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Kotlin
# ---------------------------------------------------------------------------
# 协程内部实现依赖这些信息
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------
# JNI 契约：本地大模型（llama.cpp / LiteRT）方案已整体移除
# ---------------------------------------------------------------------------
# 原先这里有一整组 -keep，用来保住 libllama_driver.so 在运行期用 FindClass /
# GetStaticMethodID 按**字符串名字**查找的类与方法（LlamaLogManager /
# LlamaMountProgress / ReaderModelBus / LlamaCpuEngine$NativeTokenCallbackBridge /
# NativeLlamaDriver$NativeTokenCallback）。对应的 .kt 与 .so 都已移除，
# 这些规则已无对象可保，故一并删除 —— 留着只会让 R8 报"未匹配到任何类"的噪声。

# ---------------------------------------------------------------------------
# Gson 写到 SharedPreferences / 数据库里的「阅读器」模型（关键）
# ---------------------------------------------------------------------------
# ReaderDocument / DocumentHighlight 会被 Gson 序列化进 reader_document_pool
# 这个 SharedPreferences。此前它们**不在任何 keep 名单里**，于是写进去的键是
# 混淆后的 a/b/c…；换一次构建、混淆映射一变，旧 JSON 就读不回来 ——
# 字段全为 null，书架读 format 时直接 NPE 崩在组合期。
# 这正是"重新打个包，东西就坏了"的一类根因，必须固定字段名。
-keep class com.treechat.workbench.ui.reader.ReaderDocument { *; }
-keep class com.treechat.workbench.ui.reader.DocumentHighlight { *; }
-keep class com.treechat.workbench.ui.reader.ReaderFormat { *; }

# 同理：检索证据会被写进 chat_messages.knowledge_sources 列，读回来渲染引用卡片。
-keep class com.treechat.workbench.rag.KnowledgeRetriever$Evidence { *; }
-keep class com.treechat.workbench.rag.KnowledgeRetriever { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 注：原先这里有一条
#   -keep class * implements com.treechat.workbench.engine.local.NativeLlamaDriver$NativeTokenCallback { *; }
# 用于保住 native 回调的方法名。本地大模型方案已移除，该接口不存在，规则已删。

# ---------------------------------------------------------------------------
# 注：LiteRT-LM（com.google.ai.edge.litertlm.*）的 keep 与 classes.jar 依赖
# 已随本地大模型方案一起移除；`-dontwarn java.lang.ClassValue` 保留（PDFBox 仍会用到）。
# ---------------------------------------------------------------------------
-dontwarn java.lang.ClassValue

# ---------------------------------------------------------------------------
# PDFBox-Android 的可选依赖（关键，缺了 R8 直接构建失败）
# ---------------------------------------------------------------------------
# pdfbox-android 的 JPXFilter 会引用可选的 JPEG2000 解码器 com.gemalto.jp2.JP2Decoder。
# 我们没有引入它（也不打算为极少见的 JPEG2000 图像加这个库），但 R8 在 full mode 下
# 会因为"引用了不存在的类"直接报 Missing classes detected 并终止构建。
# 这里只是告诉 R8"这个可选类确实可能不在" —— 真遇到 JPEG2000 图像时 PDFBox 会自己抛
# 可读异常，不会因为这条规则变成静默错误。
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.annotation.**
