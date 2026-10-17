接手 TalkTree（/Users/deepseekworkspace/TreeChatWorkbench，当前 v1.37.0 / versionCode 37）。

先完整读 docs/HANDOFF_CACHE_OPTIMIZATION.md 与 docs/CACHE_PREFIX_OPTIMIZATION_PLAN.md。

背景：上一轮会话诊断出 token 消耗偏大的根因是**缓存前缀被动态内容破坏**，不是缺缓存优化器。
`PromptCacheOptimizer.kt` 虽从未被调用，但**不要接它**——`getAncestorChain` 的递归 CTE
（`ORDER BY depth DESC`）返回「根→父节点」顺序，现有拼接结构已等价，接它反而要重写数据流。

要做三件事，按顺序做，每步做完编译+回归测试再进下一步：

**1（核心）。** `ChatNodeViewModel.kt`：把 RAG 知识注入从 system prompt 移到消息序列末尾。
删 2054-2057 行；把 `knowledgeText` 追加进最后一条 user 消息；约 2092 行
`messages = promptListTrimmed` 改为 `messages = finalPromptList`。
原因：`knowledgeSection` 每轮都变，却拼在最前面的 system prompt 里，导致后面整条祖先链缓存全部失效。
回归：开启教材检索后 AI 仍能引用教材并给出 `[编号]`。

**2（1 验证通过后再做）。** 删 2039-2041 行，消除 `originQuote` 双重注入
（现在同一段原文在 `promptList` 和 system prompt 各发一遍，白白翻倍）。
回归：阅读器选句→提问，AI 必须仍能正确引用原文（这是已验证的第 11 项功能，不可退化）。

**3（可选，风险最高）。** `trimPromptListToBudget`（2240 行起）改为按完整轮次截断，
使截断点稳定。需充分测试长对话。

**另外顺手修掉纪律 2 违规**（`HANDOFF_FOR_ANTIGRAVITY.md` 第二节第 2 条，界面严禁"中文"字样）：
- `ui/reader/SentenceTranslateSheet.kt:66` → `ZH("简体汉语")`（只改 displayName，枚举名 ZH 保留）
- `ui/dictionary/DictionaryScreen.kt:470` → 文案改「…译成简体汉语…」

**注意**：`HANDOFF_FOR_ANTIGRAVITY.md` 第四节那 4 项（单条消息删除 / 多语言界面 / ZIP 导出 /
.talktree 导入导出）**源码里已全部实现**，但现有 APK 时间戳 10-14 23:48 早于实现文件
10-15 00:33，**大概率没打进包**。所以改完这次的活儿统一 assembleRelease 一次，
然后按 HANDOFF 文档第六节的清单逐项验机（.talktree 导入要确认分支树完整还原）。

约束（不可违反）：
- 禁止新增/修改 Room 表、列或 migration，新状态一律存 SharedPreferences JSON
- 界面严禁彩色 Emoji，统一 Material 3 矢量图标
- 不得重新引入本地大模型；离线 OCR 必须保留
- 保持 100% 纯 Kotlin + Jetpack Compose

验证：
```bash
export JAVA_HOME=/Users/deepseekworkspace/tools/jdk21/Contents/Home
cd /Users/deepseekworkspace/TreeChatWorkbench
./gradlew :app:compileReleaseKotlin -q
./gradlew :app:assembleRelease
/Users/deepseekworkspace/android-sdk/build-tools/34.0.0/apksigner verify app/build/outputs/apk/release/app-release.apk
/Users/deepseekworkspace/android-sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

**本项目不是 git 仓库**，每步改动前先手动备份。
已有备份：`ChatNodeViewModel.kt.bak-before-cacheopt`（已验证与当前源码一致）。

文档里所有行号都已核对有效，但若你先改了别处会位移，以当前内容为准复核。
