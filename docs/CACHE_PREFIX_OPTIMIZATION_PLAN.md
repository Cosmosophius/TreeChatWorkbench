# TalkTree 前缀缓存优化改造方案

> **给 zcode 的执行文档。** 本方案基于对 `ChatNodeViewModel.kt` 的完整阅读，
> 所有行号均为文档撰写时的实际行号，改动前请以当前文件内容复核。
>
> 撰写者：DSH 会话（无 zcode 的项目记忆），故本方案**只给方案与行号，不直接改文件**。
> 执行前建议 `cp ChatNodeViewModel.kt ChatNodeViewModel.kt.bak-<日期>`。
> （撰写者已放一份备份：`ChatNodeViewModel.kt.bak-before-cacheopt`）

---

## 一、背景：为什么要做这个改造

### 问题现象

用户在使用 TalkTree 时观察到 API token 消耗偏大。经代码审查，**根因不是缺少缓存优化器，
而是缓存前缀被动态内容破坏**。

### 关键事实：现有代码结构本身是对的

`ConversationDao.getAncestorChain`（`ConversationDao.kt:150-172`）是递归 CTE：

```sql
WITH RECURSIVE AncestorTree AS ( ... )
SELECT ... FROM AncestorTree
ORDER BY depth DESC
```

`ORDER BY depth DESC` 使返回顺序为**从根节点到父节点**（depth 最大是根，0 是当前节点）。

而 `ChatNodeViewModel.runCompletion` 中：

```kotlin
for (ancestor in ancestors) { ... }        // ChatNodeViewModel.kt:1955
```

按此顺序拼接。**这正是前缀缓存最理想的结构：从根到父节点整条链路不可变，只有末尾动态。**

### 结论

`PromptCacheOptimizer.kt` 虽然写好但**从未被调用**（全仓库仅定义处出现该符号），
但**不需要接上它**——现有拼接逻辑在结构上已等价。

真正破坏缓存的是**动态内容被放在了前缀上游**（见第二节）。

---

## 二、三处改动（按优先级排序）

### 改动 1：把 RAG 知识注入从 system prompt 移到消息末尾【最高优先级】

**问题**：`effectivePrompt` 的拼接顺序（`ChatNodeViewModel.kt:2037-2057`）是：

```
basePrompt                  ← 稳定
+ originQuote               ← 稳定
+ 概念链接提示               ← 稳定
+ 联网检索提示               ← 随 searchConfig.enabled 变化
+ RAG 知识注入               ← ⚠️ 每轮检索结果都不同
```

**`knowledgeSection` 是每轮实时检索的结果**（`ChatNodeViewModel.kt:2016-2035`），
内容每轮都变。而它被 `append` 到了 **system prompt 里**——
system prompt 位于消息序列最前面，**它一变，后面整条祖先链的缓存全部穿透失效。**

**当前代码（2037-2057 行）**：

```kotlin
val effectivePrompt = buildString {
    append(basePrompt)
    if (!activeOriginQuote.isNullOrBlank()) {
        append("\n\n【用户重点关注与引用的原文/上下文段落】：\n「$activeOriginQuote」\n用户当前的所有提问都是针对上述引用的原文展开的，请务必结合该段原文的具体语境、词句含义与逻辑进行精准解答。")
    }
    // 概念链接提示：...
    append("\n\n输出时，请用双方括号把值得下钻的核心概念包起来（如 [[递归]]），")
    append("便于读者点击查看释义或词源；只包裹真正的术语/概念名词，")
    append("不要包裹普通词或整句，也不要嵌套标记。")
    if (searchConfig.enabled) {
        append("\n\n你具备联网检索能力。当问题涉及实时信息、当前事件、")
        append("或你不确定的事实时，主动调用 web_search 工具，不要凭记忆猜测。")
        append("引用检索到的资料时用 [编号] 标注来源。")
    }
    val knowledgeText = KnowledgeRetriever.buildSystemPromptSection(knowledgeSection)
    if (knowledgeText.isNotBlank()) {
        append("\n\n").append(knowledgeText)          // ← 这一段要移走
    }
}
```

**改法**：

1. **删除** 2054-2057 行（`val knowledgeText = ...` 与 `if (knowledgeText.isNotBlank()) { append(...) }`）
2. 在 `promptListTrimmed` 构造完成之后、调用 `aiClient.runChat` 之前，
   **把 `knowledgeText` 追加为最后一条 user 消息的一部分**。

**参考实现**（放在 2058 行之后、`val knowledgeSourcesJson = ...` 之前）：

```kotlin
// ---- 前缀缓存优化：RAG 知识注入移到消息序列末尾 ----
//
// 为什么不能再留在 system prompt 里：knowledgeSection 是每轮实时检索的结果，
// 内容逐轮变化。而 system prompt 位于消息序列最前端，它一变，
// 其后整条祖先链的前缀缓存全部穿透失效（DeepSeek/Claude 的缓存均按前缀匹配）。
// 移到末尾后，前端（system + 祖先链）保持字节级稳定，缓存得以命中。
val knowledgeText = KnowledgeRetriever.buildSystemPromptSection(knowledgeSection)
val finalPromptList = if (knowledgeText.isNotBlank()) {
    val lastUserIdx = promptListTrimmed.indexOfLast { it.role == "user" }
    if (lastUserIdx >= 0) {
        val original = promptListTrimmed[lastUserIdx]
        promptListTrimmed.toMutableList().also { list ->
            list[lastUserIdx] = original.copy(
                content = original.content + "\n\n" + knowledgeText
            )
        }
    } else {
        // 没有 user 消息时（异常路径），退化为追加一条 system 消息，
        // 保证知识内容不丢失，也不会让整轮对话失败。
        promptListTrimmed + PromptMessage(role = "system", content = knowledgeText)
    }
} else {
    promptListTrimmed
}
```

3. **把 `messages = promptListTrimmed`（约 2092 行）改为 `messages = finalPromptList`**

**⚠️ 回归测试必做**：
- 开启「教材检索」后提问，**AI 必须仍能引用教材内容并给出 `[编号]` 来源**
- `_lastKnowledgeCitations` 的 UI 显示不应受影响（该逻辑在 2058 行之前，未改动）

---

### 改动 2：消除 originQuote 的双重注入

**问题**：同一段原文被注入了**两次**：

```kotlin
// 第一次：作为 promptList 里的 system 消息（1948-1953 行）
if (!activeOriginQuote.isNullOrBlank()) {
    promptList.add(
        PromptMessage(
            role = "system",
            content = "【当前讨论分支引用的原文段落】：\n「$activeOriginQuote」\n用户在此分支的提问均针对此段内容展开。"
        )
    )
}

// 第二次：又拼进 system prompt（2039-2041 行）
if (!activeOriginQuote.isNullOrBlank()) {
    append("\n\n【用户重点关注与引用的原文/上下文段落】：\n「$activeOriginQuote」\n用户当前的所有提问都是针对上述引用的原文展开的，请务必结合该段原文的具体语境、词句含义与逻辑进行精准解答。")
}
```

**同一段文字发送两遍，白白翻倍 token。**

**改法**：**保留 `promptList` 里那条**（`1948-1953` 行；位置在 system prompt 之后、祖先链之前，更稳定），
**删除 `2039-2041` 行**（即 `effectivePrompt` 里那个 `if (!activeOriginQuote.isNullOrBlank()) { append(...) }`）。

**⚠️ 这条必须单独验证**：项目第 11 项已验证功能是
「选句针对提问 AI 上下文彻底修复」。**必须测试：阅读器选句 → 提问 → AI 能正确引用原文。**

> **建议：先做改动 1，验证通过、观察消耗下降后，再做改动 2。**
> 一次改两处若出问题，难以定位。

---

### 改动 3：让上下文裁剪的截断点稳定

**问题**：`trimPromptListToBudget`（`ChatNodeViewModel.kt:2240-2260`）：

```kotlin
private fun trimPromptListToBudget(list: List<PromptMessage>, budgetChars: Int): List<PromptMessage> {
    if (list.isEmpty()) return list
    val kept = ArrayDeque<PromptMessage>()
    var used = 0
    for (i in list.indices.reversed()) {        // 从尾部往前保留
        val m = list[i]
        val cost = m.content.length + 16
        if (kept.isNotEmpty() && used + cost > budgetChars) break
        kept.addFirst(m)
        used += cost
    }
    ...
}
```

按**字符预算**从头部截断。预算固定为 `CLOUD_CONTEXT_CHAR_BUDGET = 24_000`（2297 行），
但**截断位置随内容长度浮动**——上下文一长，截掉的条数就变，**前缀直接不匹配**。

**改法（二选一）**：

- **方案 A（保守，推荐）**：改为**按完整轮次截断**——保留从末尾往前数的 N 个
  `user+assistant` 配对，N 由预算换算，但**保证截断边界落在轮次之间**，
  使保留下来的部分序列稳定。
- **方案 B（激进）**：改为**固定条数上限**（如最多保留最近 40 条），
  彻底消除"按内容长度浮动"的问题。

**⚠️ 这条改动范围较大，建议放到最后做**，且必须验证：
- 长对话（超过 24000 字符预算）仍能正常回复
- 早期上下文丢失是否影响用户体验（可能需要配合"历史脉络摘要"功能，见 1961-1966 行）

---

## 三、不要做的事

| 事项 | 原因 |
|---|---|
| ❌ **不要删除 `PromptCacheOptimizer.kt`** | 留着无害；其文档注释记录了缓存陷阱，有参考价值 |
| ❌ **不要"接上" `PromptCacheOptimizer`** | 现有 `for (ancestor in ancestors)` 已实现等价效果。接它需重写数据流，风险大于收益 |
| ❌ **不要新增/修改 Room 表或列** | 项目纪律第 1 条（见 `HANDOFF_FOR_ANTIGRAVITY.md`） |
| ❌ **不要在界面上写"中文"字样** | 项目纪律第 2 条，必须写「简体汉语」/「繁体汉语」 |

---

## 四、验证清单

改完后**必须**逐项执行：

```bash
export JAVA_HOME=/Users/deepseekworkspace/tools/jdk21/Contents/Home
cd /Users/deepseekworkspace/TreeChatWorkbench

# 1. 编译
./gradlew :app:compileReleaseKotlin -q

# 2. 打包
./gradlew :app:assembleRelease

# 3. 签名校验
/Users/deepseekworkspace/android-sdk/build-tools/34.0.0/apksigner verify \
  app/build/outputs/apk/release/app-release.apk

# 4. 安装到模拟器（emulator-5554）
/Users/deepseekworkspace/android-sdk/platform-tools/adb install -r \
  app/build/outputs/apk/release/app-release.apk
```

**功能回归（逐条手工验证）**：

1. ☐ **教材检索**：开启后提问，AI 仍能引用教材内容并给出 `[编号]` 来源（改动 1 回归）
2. ☐ **选句提问**：阅读器选句 → 提问，AI 正确引用原文（改动 2 回归，第 11 项功能）
3. ☐ **联网搜索**：开启后工具调用正常，能引用检索结果
4. ☐ **图片对话**：发送图片后 AI 能识别
5. ☐ **长对话**：连续聊 30+ 轮不报错、不卡死（改动 3 回归）
6. ☐ **树状分叉**：从中间节点分叉后，新分支上下文正确

**效果观测**：

观察 API 账单或日志中的 `credit` / token 消耗是否下降。
参考方法：单轮对话的 `prompt_cache_hit_tokens` 应显著上升。

---

## 五、预期收益与不确定性

**预期**：改动 1 是本方案的核心。若 RAG 检索开启，缓存命中率应有明显提升；
若未开启 RAG，收益主要来自联网搜索提示的稳定性（较小）。

**不确定性（撰写者无法验证的部分）**：

- 撰写者**未运行**该项目，未做真机/模拟器测试。上述行号与逻辑基于源码静态阅读。
- `knowledgeSection` 的具体变化幅度未知——**若检索结果在多轮间高度重复，收益会小于预期**。
- 三处改动的**实际 token 节省比例无法预估**，需实测。

---

## 六、附：为什么"只做改动 1"是更稳的路径

用户指示"只要不会影响使用就都按你说的做"。但三处改动**存在相互影响**：

- 改动 1 和 2 都动 `effectivePrompt` 的构造
- 改动 3 影响 `promptListTrimmed`，而改动 1 依赖它的结果

**建议执行顺序**：

```
改动 1  →  编译  →  回归测试  →  观察消耗  →  若正常，再做改动 2  →  最后考虑改动 3
```

**若任一步回归测试失败，立即回滚该步**：

```bash
cp app/src/main/java/com/treechat/workbench/ui/chat/ChatNodeViewModel.kt.bak-before-cacheopt \
   app/src/main/java/com/treechat/workbench/ui/chat/ChatNodeViewModel.kt
```

---

## 七、附：Antigravity 交接的 4 项任务（已实现，待验收）

`HANDOFF_FOR_ANTIGRAVITY.md` 第四节所列 4 项任务**已全部落入源码**，
但**很可能未打进现有 APK**（APK 时间戳 `10-14 23:48`，实现文件 `10-15 00:33`）。
**详见 `docs/HANDOFF_CACHE_OPTIMIZATION.md` 第六节**，其中含逐项验机清单。

| 任务 | 实现位置 |
|---|---|
| 1. 单条消息删除 | `ChatMessageView.kt:628` → `ChatNodeViewModel.kt:1869` → `ConversationDao.kt:103` |
| 2. 多语言界面 | `AppStrings.kt:21-26`、`SettingsDialog.kt:1097`、`AppSettings.kt` |
| 3. ZIP 打包导出 | `ZipExporter.kt:39`/`:205`、`FolderExplorer.kt:363`/`:743` |
| 4. `.talktree` 导入导出 | `TalkTreeBundle.kt:33`/`:60`、`ChatNodeViewModel.kt:1002`、`FolderExplorer.kt:345`/`379`/`395` |

**建议**：缓存优化改动 + 纪律 2 文案修正**一并完成后统一 `assembleRelease`**，
一次打包、一次验机，避免分批出包。

### ⚠️ 纪律 2 用户可见残留

以下位置**直接显示给用户**且用了「中文」字样，**违反项目纪律第 2 条**：

- `ui/reader/SentenceTranslateSheet.kt:66` — `TranslateTarget.ZH("中文")` 的 `displayName`
- `ui/dictionary/DictionaryScreen.kt:470` — 说明文案「…译成**中文**…」

建议改为「简体汉语」；枚举名 `ZH` 本身是标识符，可保留。
