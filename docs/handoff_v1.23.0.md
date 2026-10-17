# TreeChat 交接文档（v1.23.0 → 下一会话）

> 写于 2026-10-11 21:35，紧接 v1.23.0 之后。
> 配套（**建议按顺序读**）：
> 1. 本文件（当前状态 + 本轮根因 + 待修问题）
> 2. `TreeChat_交接_v1.14.0_给下一会话.md`（环境、反代事实、项目铁律）
> 3. `TreeChat_v1.16.0_布局闪退根因与修复.md`（R8 泛型签名取证）
> 4. `TreeChat_v1.17.0_RAG图片字号与推理.md`（RAG/图片/字号/推理）
> 5. `TreeChat_交接文档.md`（架构与历史坑，最长）
>
> **本文件的首要目的是"别让下一个会话重走我的弯路，也别相信我上一轮的错误结论"。**
> 所以第 1 节先写"哪些是坏的/没验证的"，第 5 节专门记录**我犯过的错**。

---

## 0. 一句话现状

**选字（长按选词提问）已在 release 包上实测修好**（这是本项目拖了多个会话的老问题，根因是结构性的，见第 3 节）。
另有 3 个用户新报的问题**完全没查**（小米模型无输出、联网检索全部失效、对话界面最小化不成侧边标签），1 个我改了但**没能证明生效**，1 个实现了但**没验证**。

当前版本：`versionCode = 23` / `versionName = "1.23.0"`，数据库 `version = 7`。

---

## 1. 证据分级（**不要跳过这一节**）

### ✅ 已用证据证实（在模拟器上实测过）

| 项 | 证据 |
|---|---|
| **选字可用**（release 包） | 长按日志：`长按成功 → 块1 偏移35（块=8 布局表=8 位置表=8）`、`已选中=true 文本长度=31`；界面 dump 出现 `已选 31 字 / 针对此段提问 / 复制 / 高亮` |
| **卡片可拖动、可改大小、重启保持** | 尺寸写入日志逐帧累加（`497x452→458→468→477→487→497`）；落盘 `hs:[199.56,497.81]`；重启后 `恢复尺寸 0946... <- 497x497` |
| 编译/出包 | `compileDebugKotlin`、`assembleRelease` 均 BUILD SUCCESSFUL |
| 迁移 v4→v7 | `test_migration_v4_to_v7.py` 30 项全通过 |
| RAG 切块检索算法 | `test_rag_chunking.py` 14/14 命中 |
| 图片两协议请求体 | `TestImageRequest.java` 6 场景通过 |

### ⚠️ 改了但**没能证明生效**

| 项 | 说明 |
|---|---|
| **小窗与画布抢事件** | 我加了"按下点落在小窗上时画布不接管"的逻辑（`TreeTopologyView.kt` 的手势入口）。但实测：触摸小窗正文后画面仍位移 341px。**我无法判定是修复无效还是我的测试前置状态不对**，所以不算修好。下一轮务必做**对照实验**（同坐标、同前置状态，v1.22.0 与 v1.23.0 各测一次比位移）。 |

### ❌ 实现了但**完全没验证**

| 项 | 说明 |
|---|---|
| **卡片之间互相推挤让位** | 代码已写进 `computeGeometry`（新增 `activeNodeId` 参数 + 卡片×卡片最小分离轴推挤）。**画布上只有 1 张卡片，无法测互推**。要验证必须先造出 ≥2 个节点。 |
| 同文本超链接小窗 | 全部链路已实现并编译通过（见第 4 节），但**未在设备上点过一次**。 |
| 全局字体滑块 / 知识库 RAG / 图片发送 / 思考过程面板 | 均已实现并编译通过，**均未在设备上验证**。 |
| 生成结束状态 | `awaitClose` 死锁已修（见第 4 节），但**未能跑通端到端验证**（模拟器上用 adb 给中文输入法输入文字始终失败）。 |

### 🆕 用户新报、**我完全没查**（下一会话的主线）

1. **小米（MIMO）模型"能拉到模型列表，但输出不出东西"**，换任何模型（反代 / 云端 API）都一样。
2. **联网检索在 App 里完全搜不到东西**，但"在 agent 里是可以搜东西的"。
3. **对话界面里最小化小窗，不能收成旁边的标签（停靠坞）**。
4. **点"返回原文"每次都新建一个小窗**（代码里本有复用逻辑，属矛盾现象，需现场证据）。

第 6 节有我核实到的**代码事实**与逐条验证假设，可直接作为排查起点。

---

## 2. 环境事实（**本轮新增了模拟器，这是最大的变化**）

### 2.1 模拟器已装好并可用（v1.14.0 文档里的"没有设备"已过时）

| 项 | 值 |
|---|---|
| AVD 名 | `treechat_test`（Android 14 / API 34 / google_apis / x86_64） |
| emulator | `C:\Users\26258\AppData\Local\Android\Sdk\emulator\emulator.exe` |
| 已装镜像 | `system-images;android-34;google_apis;x86_64`（`system.img` 约 4GB） |
| 启动命令 | `emulator.exe -avd treechat_test -no-snapshot-load -no-boot-anim -gpu swiftshader_indirect -no-audio`（放后台跑，约 60~90 秒 boot 完成） |
| 等待启动 | `adb wait-for-device` + 轮询 `adb shell getprop sys.boot_completed` 直到为 `1` |
| 主机 | AMD Ryzen 7 7840H / 31GB / 虚拟化已开，跑得动 |

**这意味着：以前"必须让用户当测试员"的做法可以放弃。改完自己装上去测。**

### 2.2 构建

```powershell
$env:ANDROID_HOME = "C:\Users\26258\AppData\Local\Android\Sdk"
$gradle = "C:\Users\26258\.gradle\wrapper\dists\gradle-8.8-bin\4u0rgm4geyrm56fyhoco0c9in\gradle-8.8\bin\gradle.bat"

# 编译（约 35~50 秒）
& $gradle -p "D:\DEEPSEEK\TreeChatWorkbench" :app:compileDebugKotlin --no-daemon --console=plain
# debug 包（约 1.5 分钟，迭代用这个，快）
& $gradle -p "D:\DEEPSEEK\TreeChatWorkbench" :app:assembleDebug --no-daemon --console=plain
# release 包（约 2.5~4.5 分钟）
& $gradle -p "D:\DEEPSEEK\TreeChatWorkbench" :app:assembleRelease --no-daemon --console=plain
```

产物：`app\build\outputs\apk\{debug,release}\app-{debug,release}.apk`

### 2.3 在模拟器上装包与观察

```powershell
$adb = "C:\Users\26258\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb uninstall com.treechat.workbench          # 换包类型（debug<->release）必须先卸载
& $adb install -r <apk>
& $adb shell am start -W -n "com.treechat.workbench/.MainActivity"
& $adb logcat -c                                  # 清日志
& $adb logcat -d -s TreeChatSelection:D           # 只读选字诊断
& $adb logcat -d -s TreeChatCard:D                # 只读卡片诊断
```

**取界面真实坐标（唯一可靠办法，别猜）**：

```powershell
& $adb shell uiautomator dump /sdcard/u.xml
& $adb pull /sdcard/u.xml D:\DEEPSEEK\apk_treechat\u.xml
# 然后从 XML 里正则抽 text= 与 bounds=，用 bounds 的中心去点/拖
& $adb shell input swipe x1 y1 x2 y2 900      # 同点同点=长按
```

**重要**：界面坐标经常和你的直觉差几百像素（缩放、位移动画、画布 pan 都会影响）。
**先 dump、后点击**，否则会得出"这个功能没用"的错误结论（我为此浪费过好几轮）。

### 2.4 往 App 里写配置（无需手动点界面）

release 包**不能用 `run-as`**（非 debuggable），所以：

- **debug 包**：可用 `run-as` 直接写 SharedPreferences
  ```powershell
  # 注意：PowerShell 重定向会写成 UTF-16 且 BOM 为 0xff，必须用 Python 喂字节
  & $PY "D:\DEEPSEEK\apk_treechat\push_prefs.py" "D:\DEEPSEEK\apk_treechat\prefs_new.xml"
  ```
- **release 包**：只能靠 logcat 观察（诊断日志已内建）

已落盘的辅助脚本见第 7 节。

---

## 3. 本轮定位到的根因（**这是本文件最重要的技术内容**）

### 3.1 选字为什么"改了这么多年都不好"——**控制器被所有消息共用**

**现象**（release 包实测日志）：

```
13:12:53  onTextLayout 触发 块0..块7            ← 布局回调确实跑了
13:13:05  长按成功但命中为空（块=0 布局表=0 位置表=8 解析块=8）
```

**根因**（`ChatMessageView.kt` 原第 187 行）：

```kotlin
val selectionController = remember { MessageSelectionController() }   // 一个控制器
...
controller = selectionController,   // 传给每一条 MessageRow
```

而 `SelectableMarkdownText` 里每条消息在 compose 时都会：

```kotlin
LaunchedEffect(markdown) {
    controller.clearBlocks()   // ← 清空的是同一个控制器
    ...
}
```

并且 `upsertBlock(blockIndex = index)` 的 `index` 是**消息内**的块序号。

**后果**：最后 compose 的那条消息会覆盖/清空其它消息的块。用户长按另一条消息时，
命中测试用的却是**别的消息的矩形**或空表 → 必然失败。

**这解释了全部现象**：为什么改手势没用（问题不在手势）、为什么 debug 能过 release 不过
（取决于 compose/重组顺序与时机）。

**本轮修法（三处，缺一不可）**：

1. **控制器改为每条消息一个**（`ChatMessageView.kt`）：
   ```kotlin
   val selectionControllers = remember { mutableMapOf<String, MessageSelectionController>() }
   var activeSelectionController by remember { mutableStateOf<MessageSelectionController?>(null) }
   // 在 itemsIndexed 里：
   val messageController = remember(message.id) {
       selectionControllers.getOrPut(message.id) { MessageSelectionController() }
   }
   ```
   工具条改用 `activeSelectionController`（`onAsk/onCopy/onHighlight/onSelectAll/onDismiss` 全部已改）。

2. **把 `origins` / `layouts` 提升进控制器**（`SelectionModel.kt` 新增两个 `mutableStateMapOf`）。
   原因：原先这两张表存在渲染组件的 `remember` 里，组件实例一旦被重建，
   写入就会落到**已失效的旧实例**上（这正是"位置表=8 而布局表=0"）。
   放进控制器后，控制器由上层按消息缓存持有，生命周期长于渲染组件。

3. **修掉"首次组合就清空"**（`SelectableMarkdown.kt`）：
   ```kotlin
   LaunchedEffect(markdown) {
       val previous = controller.renderedMarkdown
       if (previous != null && previous != markdown) {   // ← 必须判断"真的变了"
           controller.clearBlocks(); controller.clear()
           origins.clear(); layouts.clear()
           currentOnSelectionChanged(false)
       }
       controller.renderedMarkdown = markdown
   }
   ```
   `LaunchedEffect` 在组件**首次**组合时同样会执行一次，不判断就会把已有布局清空。

> **教训**：加 R8 之后，凡是"跨重组/跨实例要保持"的渲染期状态，都不要放在组件内部的
> `remember` 里，而要提升到生命周期更长的持有者上。

### 3.2 卡片改大小为什么"只能动一点点、然后缩回"

`applyCardDrag` 原先读 `cardSizes[nodeId]` —— 那是 `remember(nodeIds, geometry)` 的
**组合期快照**。拖动循环在**同一帧内连续消费指针事件**，组合来不及重算，于是每一帧都从
同一个旧基准叠加**增量**，尺寸永远不累积。

**修法**：基准改读权威状态 `graphState.nodeSizes`（位置拖动同理改读 `graphState.positions`）。
这两个 bug 是同一类：**用组合期快照当增量基准**。

### 3.3 生成状态为什么"答完仍显示生成中、按钮不变箭头"

`AiStreamClient.kt` 的 `channelFlow` 里同时有 `job.join()` 和 `awaitClose { ... }`。
`awaitClose` 的语义是**挂起直到 channel 被关闭**，而 channel 只在 block 返回后才关 ——
互相等待，Flow 永不完成，ViewModel 里那句 `isStreaming = false` 的 `finally` 永远执行不到。

**修法**：删掉 `awaitClose`，清理逻辑（取消 OkHttp Call）移到内部协程的 `finally`。

---

## 4. 本轮改动清单（v1.16.0 → v1.23.0）

| 版本 | 内容 |
|---|---|
| v1.16.0 | 布局闪退修复（R8 抹泛型签名 → Gson 产出 Double；改显式解析）+ `-keep data.prefs.**` |
| v1.17.0 | 字体滑块、知识库 RAG（检索服务+管理界面）、图片发送（两协议+输入栏UI）、`reasoning_content` 解析与思考面板、迁移 v6→v7 |
| v1.18.0 / v1.19.0 | 选字诊断（可回报的判定日志 + 界面诊断条）、卡片拖动诊断 |
| v1.20.0 | 卡片缩放累积修复 |
| v1.21.0 | 同文本超链接小窗（全链路） |
| **v1.22.0** | **选字结构性修复（控制器按消息隔离 + 两表提升 + 清空判断）** |
| **v1.23.0** | 小窗事件让路（未证实）+ 卡片互相让位（未验证） |

### 同文本超链接小窗（v1.21.0，未验证）

用户原话：**"选中一段文字，进行 ai 对话后自动把相同的文本都变成可以超链接打开的文本，点击调出之前的小窗"**。

实现链路：
- `ui/selection/QuoteLinkModel.kt`（**新文件**）：`QuoteLinkTarget` / `BlockQuoteLink` / `QuoteLinkMatcher`
  - `extractTargets(nodes, currentNodeId)`：收集会话内所有 `originQuote`，按长度降序（长词优先，防短词切碎长词），同一引用取最新节点
  - `findLinks(text, targets)`：找出**不重叠**的命中区间（已占用字符不再被短词切入）
  - `findHit(offset, links)`：轻点命中判定
  - 离线测试：`apk_treechat/test_quote_links.py`（通过）
- `MarkdownOffsets.kt` 的 `toAnnotated(...)` 新增 `quoteLinkRanges` / `quoteLinkColor`，命中区间渲染为**下划线 + 链接色 + 12% 透明底衬**
- `SelectableMarkdown.kt`：轻点（Tap，未长按、无选区、未拖手柄）时做 `hitTest` → `findHit` → 回调 `onQuoteClick(node)`
- `ChatMessageView.kt`：`NodeChatContent` 用 `viewModel.nodes` 算 `quoteTargets`，透传给 `MessageRow` → `SelectableMarkdownText`
- 三个场景都接了 `onOpenQuoteWindow`：主会话（`MainActivity` CHAT_WORKSPACE）、屏幕浮窗（`MainActivity` 浮层）、画布小窗（`TreeTopologyView.NodeChatContentCompact`）
  - 逻辑统一：**已有该节点的小窗 → 解除最小化 + `bringToFront`；没有 → 就地 `openSubWindow`**

**未验证**：轻点是否真的命中、是否误触（比如轻点普通文本时不该开窗）、视觉是否好看。

---

## 5. ⚠️ 我犯过的错（**请勿复现，也不要相信我上一轮的结论**）

1. **错误的"release 不触发 onTextLayout"结论**。
   我在 v1.19.0 曾断言"release 下 `onTextLayout` 一次都不触发，是 R8 优化掉了"。
   **本次接日志实测：release 下正常触发 8 次。** 那个结论是错的。
   真正原因是"共享控制器 + 首次组合清空 + 写入失效实例"。
   → **教训：结论必须来自日志/实测，不能来自"推理出的一种合理机制"。**

2. **静态类型测试掩盖了真实形态**。
   `TestGraphLayout.java` 用 Java `List<Float>` 静态类型测了 5 个场景全绿，
   而 release 包里那个泛型信息**根本不存在**（被 R8 抹掉成了裸 `List`）。
   → **教训：涉及 R8/反射的测试，必须用"混淆后的运行时类型"复现。**

3. **用 PowerShell 改源码，把 `build.gradle.kts` 弄坏了**。
   `Get-Content -Raw | -replace | Set-Content` 导致中文注释变乱码、**并且吃掉换行**，
   使 `val debugKeystore = ...` 被并进注释行 → 编译报 `Unresolved reference: debugKeystore`。
   → **教训：改代码一律用 `read` / `edit` / `write` 工具。PowerShell 只用来看和跑命令。**

4. **把 PowerShell 的乱码当成文件损坏**。
   `Get-Content` 读 UTF-8 中文源码会在 GBK 控制台显示成 `鍗曚釜鑺傜偣`，
   但文件本身是好的。我用 Python 按字节校验后才确认。
   → **判断编码问题请用 `apk_treechat/check_source_encoding.py`，不要凭控制台显示下结论。**

5. **说"编译通过"当成"修好了"**（项目老毛病，v1.14.0 文档已列）。
   本轮我强制自己：**每个结论都配一条实测日志或 dump**。请继续这么做。

6. **测试坐标靠估算，导致"这个功能没用"的假结论**。
   画布有 pan/scale 与动画位移，我按截图估算的坐标偏了 500+ 像素，
   连续几轮都点在空白处，得出"卡片拖动无效"的错误结论。
   → **教训：先用 `uiautomator dump` 取真实 bounds。**

7. **`adb shell input text` 输不进中文/经常丢失**。
   尝试在模拟器上发消息做端到端验证，多轮都没成功（输入框始终为空）。
   若要验证"发送→生成→结束"，**改用本机 mock 服务端 + 英文输入**，
   或干脆用 `input keyevent` 逐键输入。

---

## 6. 用户新报的问题（含我核实的代码事实与逐条验证假设）

### 6.1 联网检索"就是不能搜东西"

**用户原话**："不管配置什么模型，反代也好云端api也好，就是不能搜东西，但是明明在 agent 里是可以搜东西的"。

**我核实到的代码事实（可直接用）**：

1. **App 的检索走的是"客户端函数调用"，与 agent 的服务端检索是两码事。**
   agent（DSH）里能搜，是因为那是 harness 自带的服务端工具，与本 App 实现无关。
   本 App 的检索有两条路：
   - `SearchBackend.NATIVE`：文档说走 Anthropic 的 `web_search_20250305` 服务端工具
   - `BING / DUCKDUCKGO / SEARXNG / TAVILY`：由 `WebSearchClient.kt` 本地发 HTTP

2. **`AiStreamClient.kt` 第 694 行是一处硬约束**：
   ```kotlin
   val effectiveTools = withTools && !isAnthropic
   ```
   → **Anthropic 协议下永远不注入 tools**（注释说明：两家工具协议不同，混用会直接 400）。
   而 `NATIVE` 后端期望的 `web_search_20250305` 恰恰是 Anthropic 的服务端工具。
   **即：选 NATIVE + Anthropic 协议 = 一个工具都不会发出。** 这是一处**逻辑矛盾**，值得优先查。

3. **OpenAI 兼容协议下**注入的是 `searchToolJson()` 这个**函数工具**（`web_search`），
   并设 `tool_choice = "auto"`。能否生效**完全取决于服务商是否实现 function calling**。
   代码里已有"退化"逻辑：若带 tools 发过去返回 `IOException`，会关掉 tools 重发一次
   （`AiStreamClient.kt` 第 126~140 行附近），并给用户一条"当前模型或网关不支持工具调用"的提示。
   **所以"搜不到"的第一步应确认：是否出现过这条提示。**

4. **v1.14.0 文档已记录的实测结论**：本地 Gemini 反代**不支持** `web_search_20250305`
   （会返回 `tool_use` 要求客户端自己搜，而不是 `web_search_tool_result`）
   → **原生联网搜索在那条链路上不可用**。

**下一会话第一步建议**：让用户按"设置 → 联网搜索"选一个**不依赖模型 function calling** 的后端
（`SEARXNG` / `TAVILY` / `BING` / `DUCKDUCKGO`，走本地 HTTP），先确认这条路径能搜到，
从而把问题**分离**为"本地搜索客户端坏了"还是"模型工具调用不支持"。

### 6.2 小米（MIMO）模型"能拉到模型列表，但输出不出东西"

**已知历史**（v1.14.0 文档）：MIMO 的 SSE 结构与假设不同，用户当时说不再修。
本轮我加了 `reasoning_content` 解析与 `ChatEvent.Reasoning`（思考内容此前被**静默丢弃**），
**但这不解决"完全没输出"**。

**App 里已有现成的取证入口**（这是关键，请务必用）：
`AiStreamClient.kt` 在"整轮什么都没解析出来"时，会把**原始响应片段**拼进错误信息显示在回答气泡里：

```
只解析到工具调用、没有正文。原始响应片段：
<sawUnparsedContent ? unparsedSample : ...>
```

相关变量：`sawUnparsedContent` / `unparsedSample`（第 293~294、397~404、443~461 行）。

**下一会话第一步**：让用户**换 MIMO 发一句话，把界面上那段"原始响应片段"原样贴回来**。
拿到它就能确定是 SSE 字段结构、流终止标记（缺 `[DONE]` / `finish_reason`）、
还是 `choices/delta` 形态不同。**在拿到原始响应之前，任何修改都是猜。**

### 6.3 对话界面最小化小窗，收不成侧边标签

**我核实到的代码事实**：

- 停靠坞在 `MainActivity.kt` 第 680~689 行**无条件渲染**（左右各一条 `WindowDockBar`），
  位于所有浮层之后，**在对话界面（CHAT_WORKSPACE）里也存在**。
- 最小化逻辑 `FloatingWindowManager.toggleMinimize(windowId)`（约第 416 行）：
  置 `isMinimized = true`，计算 `dockSide`（右侧超过 10 个则转左侧），并 `avoidDockCollision(window)`。
- `FloatingWindowCard` 用 `AnimatedVisibility(visible = !isItem.isMinimized)`，
  所以最小化后窗口会消失。
- `WindowDockBar.kt` 里我**没有找到明确的 `space`/`isMinimized` 过滤**（grep 未命中），
  其条目来源需要新会话读全文确认。

**可能的成因（均为假设，需验证）**：
1. `WindowDockBar` 的条目集合或 `dockFraction` 在"对话界面"下的坐标系与浮窗不一致，标签被画到屏幕外；
2. 停靠坞被对话内容或小窗图层盖住（z 序）；
3. `dockSide` / `dockFraction` 只在画布空间算对，SCREEN 空间下算错。

**下一会话第一步**：在对话界面点小窗红点左侧的"最小化"圆点，
然后 `uiautomator dump` 看**停靠坞是否出现在 dump 里**（有节点但看不见 = 位置/z序问题；没有节点 = 逻辑没执行）。

---

### 6.4 【新增】点"返回原文"每次都新建一个小窗

**用户原话**："目前每次点击返回原文都会新建一个卡片，修复"。

**我核实到的代码事实（重要：代码里本来就有复用逻辑，所以这里有矛盾）**：

引用卡片的点击链路：
- `FloatingWindowCard.kt:106` 声明 `onQuoteClick: ((String?) -> Unit)?`（**可空 lambda，参数可空**）
- `FloatingWindowCard.kt:240-243` 里 `QuoteOriginCard(onClick = onQuoteClick?.let { ... })`
- `FloatingWindowCard.kt:605-608` 文案：`"引用上级讨论（L${depth - 1} 来源）· 点击跳回原文"`

两个接线点（都已**包含复用逻辑**）：

| 位置 | 处理器 | 复用逻辑 |
|---|---|---|
| `MainActivity.kt:598` | 屏幕浮窗里的引用卡片 | 有：`windows.firstOrNull { it.nodeId == sourceNodeId }` → 若已有则解除最小化 + `bringToFront`；否则 `openSubWindow` |
| `TreeTopologyView.kt:780` | 画布小窗里的引用卡片 | 转发给 `onJumpToSourceMessage`；`MainActivity.kt:535` 那段同为"先查 existing，再决定是否 open"，且会先切会话 |

还有一个**前置守卫**：`MainActivity.kt:602` 是
```kotlin
if (sourceNodeId != null && sourceNodeId != item.nodeId) { ... }
```
即"来源就是我自己"时不动作。

**所以从代码看，重复点击同一张引用卡片不应该产生第二个小窗。** 观察到"每次新建"说明另有原因，
**必须先拿到现场证据再改**。下面是待验证的假设（按可能性排序）：

1. **`sourceNodeId` 每次解析结果不同**：`viewModel.nodeIdOfMessage(sourceMessageId)` 依赖
   `ConversationDao` 的查询。若那条消息所在的节点在会话切换/重组中变化，就会命中不同的 nodeId，
   于是 `firstOrNull` 找不到"同名"窗口，每次开新的。
   → **验证**：在 `openSubWindow` 调用前打一条日志，打印 `sourceMessageId / sourceNodeId / windows.map{it.nodeId}`，
   连点两次对比 `sourceNodeId` 是否一致。
2. **窗口在两次点击之间被移除**：链路里有 `viewModel.openConversation(conversationId)` 切会话，
   以及渲染层按 `space` / 当前会话节点列表过滤（`TreeTopologyView` 的 `graphWindows` 会按
   `nodes.any { node -> node.id == it.nodeId }` 过滤）。若窗口被过滤掉或被回收，
   下次点击自然又新建。
   → **验证**：连点两次，第二次点击前 dump 一次，看 `windows` 里是否还留着那个 nodeId 的窗口。
3. **用户说的"卡片"其实是"工作台上的节点卡片"而非小窗**：若真是在建节点，
   那说明点击链路里误调了 `forkChildNode` 之类的写库操作（当前代码里没有看到这种调用，
   属于待排除项）。
   → **验证**：点几次之后看工作台节点数是否增加（`N 个节点` 文案）。

**⚠️ 顺带发现一处命名冲突（请下一会话处理）**：

同名 `onQuoteClick` 现在有两个**含义与类型都不同**的参数，散落在不同组件里：

| 组件 | 签名 | 含义 |
|---|---|---|
| `FloatingWindowCard` | `((String?) -> Unit)?` | **跳回原文**（传来源消息 id） |
| `SelectableMarkdownText`（我新加） | `(ConversationNode) -> Unit` | **同文本超链接**（传目标分支节点） |

两者不在同一组件所以不会编译错误，但**极易读错、也容易接错线**。
建议把新加的那个重命名为 `onQuoteLinkClick`。**这很可能正是此类"点一下冒出个新窗"问题的温床**——
下一会话排查 6.4 时请一并核对：点击引用卡片时，**是不是也有路径走到了同文本超链接的开窗回调**。

---

## 7. 本轮落盘的脚本（**都可重跑，不需要设备**）

`D:\DEEPSEEK\apk_treechat\` 下：

| 脚本 | 用途 |
|---|---|
| `test_quote_links.py` | 同文本超链接匹配算法（长短词竞争、防重叠、命中） |
| `test_migration_v4_to_v7.py` | 迁移 v4→v7（30 项：列名一致、外键 CASCADE、不丢数据） |
| `test_migration.py` | 旧迁移 v1→v4（回归用） |
| `test_rag_chunking.py` | RAG 切块与检索（14/14 命中） |
| `TestImageRequest.java` | 图片两协议请求体（OpenAI `image_url` vs Anthropic `source.base64`） |
| `check_braces3.py` | **Kotlin 花括号平衡检查**（按顶层函数分段报深度）。写 Kotlin 后先跑它，比等编译器报 `Expecting '}'` 快得多 |
| `check_source_encoding.py` | 判定源码真实编码（区分"真损坏"与"控制台乱码"） |
| `deobfuscate_crash.py` | 用 R8 `mapping.txt` 反混淆崩溃堆栈 |
| `compare_dex_signatures.py` / `dump_dex_signatures.py` | DEX 泛型签名取证 |
| `mock_openai_server.py` | 本机模拟 OpenAI 兼容服务端（含 `reasoning_content`、`finish_reason`，可配置是否发 `[DONE]`/关连接） |
| `push_prefs.py` / `build_prefs.py` / `set_mock_provider.py` | 直接写 App 的 SharedPreferences（**必须用 Python 喂字节**） |

> `check_braces.py`（逐字符状态机版）**会误报**（对能正常编译的文件也报不平衡），已被 `check_braces3.py` 取代，别再用。

---

## 8. 下一会话建议的优先顺序

1. **先要两份证据**（都要用户配合，但一次说清）：
   - MIMO 出错时界面上的**"原始响应片段"**原文；
   - 联网搜索失败时**是否出现过**"当前模型或网关不支持工具调用"这条提示。
   拿到这两样，6.1 与 6.2 都能立刻定位方向。
2. **查 6.1 的逻辑矛盾**：`SearchBackend.NATIVE` 声称用 Anthropic 服务端工具，
   而 `effectiveTools = withTools && !isAnthropic` 永不向 Anthropic 注入 tools。这是明确的死路。
   建议先让用户用一个**本地后端**（SearXNG/TAVILY 等）验证"搜索客户端本身是好的"。
3. **查 6.3**：对话界面最小化后 dump 一次，看停靠坞有没有节点。
4. **补上本轮的验证欠账**（都不需要用户）：
   - 小窗/画布对照实验（v1.22.0 vs v1.23.0）；
   - 造 ≥2 个节点验证卡片互推；
   - 验证同文本超链接（轻点命中、是否误触）；
   - 端到端验证"生成结束状态"（用 `mock_openai_server.py`，别再用 `input text` 输中文）。

---

## 9. 项目铁律（累计，**新增第 12~16 条**）

1. 不要说"已修复"除非有证据（本项目所有只靠"编译通过"宣称修好的改动，事后都被证明没修好）。
2. 改 Kotlin 必须编译：`compileDebugKotlin` 约 35~50 秒。
3. 分清"离线可验证"与"需真机"：检索、迁移、端点拼接、索引可离线验证；手势、渲染、闪退必须真机（**现在有模拟器了，自己测**）。
4. Gson 反射：新增要序列化的类必须在已 `-keep` 的包里，否则 R8 后读出全空。
5. 一个可交互区域只放一个手势检测器（多个会让双方都失效）。
6. 窗口分 SCREEN / GRAPH 两个坐标空间，新增任何涉及窗口偏移的代码都要先判 `space`。
7. 端点拼接只走三个顶层函数（`AiStreamClient.kt` 末尾），不要手写 `"$base/xxx"`。
8. 窗口渲染必须判 `space`：SCREEN 由 `MainActivity` 渲染，GRAPH 由 `TreeTopologyCanvas` 渲染。
9. 不要用 PowerShell 测中文接口（PowerShell 5.1 不按 UTF-8 编码请求体，且读含中文的 .ps1 用 ANSI 代码页）。
10. `-keepattributes Signature` 单独写不生效，必须与 `-keep class` 配合；**且即便配合也不要假设泛型签名一定保留**。
11. 测试的数据类型必须与线上运行时类型一致（R8 会抹掉静态类型信息）。
12. **不要把"跨重组要保持"的状态放进组件内部的 `remember`** —— 组件实例会被重建，写入会落到失效实例上。提升到生命周期更长的持有者（如控制器）。
13. **一个可交互组件不要被多条数据共用**（选择控制器曾被所有消息共用，是选字反复失效的结构性根因）。共用的东西要问一句："它的状态是按哪条数据区分？"
14. **拖动/缩放这类"同帧连续消费事件"的逻辑，增量基准必须读权威状态，不能读组合期快照**（卡片尺寸与位置两个 bug 都是这个）。
15. **改代码一律用 read/edit/write 工具**；PowerShell 只用于查看与运行命令（它会把编码与换行改坏）。
16. **拿不到原始数据就不要改代码**：MIMO 要原始 SSE、崩溃要堆栈、手势要诊断日志。本项目所有"靠猜"的修复轮次全部失败。

---

## 10. 一句话提醒给下一会话

**这个项目的失败模式从来不是"技术做不到"，而是"没有证据就动手"。**
本轮选字之所以能一次修对，唯一原因是先拿到了
`块=0 布局表=0 位置表=8` 这条日志，并顺着它找到了"共享控制器"这个结构性根因。

所以：**先要日志、先要原始响应、先 dump 界面，再改代码。**

---

## 11. 需求台账（用户历来提过的全部需求 + 真实状态）

> **状态口径**：✅ = 已在设备/模拟器上实测通过；⚠️ = 代码已实现/已改但**未验证**；❌ = 未做或未查。
> 请以本表为准，不要相信任何"已修复"的口头说法。

### ✅ 已实测通过

| # | 需求 | 证据 |
|---|---|---|
| 1 | **长按选字 / 选词提问**（不吊起系统复制菜单，用自绘工具条） | release 实测：`块=8 布局表=8 位置表=8` → `已选中=true 文本长度=31`，工具条出现"针对此段提问" |
| 2 | **卡片可拖动** | 实测：拖动后位置变化并落盘 |
| 3 | **卡片可改大小**（像小窗一样） | 实测：尺寸逐帧累积（`497x452→…→497x497`）、写盘、**重启后保持 497x497** |

### ⚠️ 已实现但**未验证**（下一会话可自行验证，不需要用户）

| # | 需求 | 备注 |
|---|---|---|
| 4 | **答完不再显示"生成中"、按钮变回箭头** | 已删 `channelClose` 死锁（`awaitClose`）。端到端未验证（模拟器中文输入受阻） |
| 5 | **全局字体滑块** | 设置 → 外观；只缩放 Typography，不影响图标/间距 |
| 6 | **知识库 RAG**（导入教材 + 检索 + 接入对话 + 管理界面） | 算法离线 14/14；设备端导入流程未验证 |
| 7 | **图片上传**（输入栏入口 + 缩略图 + 历史渲染 + 两协议） | 协议请求体离线 6 场景通过；服务端是否接受未验证 |
| 8 | **推理强度 + 思考过程显示** | 推理强度早已有（**默认"关闭"，需手动开**）；`reasoning_content` 解析与面板本轮新加，未验证 |
| 9 | **同文本超链接小窗** | 全链路已实现，未点过一次（详见第 4 节） |
| 10 | **卡片互相挤开**（拖动/缩放时邻居让开，松手回位） | 已写进 `computeGeometry`，但画布上只有 1 张卡，无法测互推 |
| 11 | **小窗与画布抢事件**（点小窗却拖了画布） | 已加"命中窗口则画布不接管"，但实测画面仍位移 341px，**未证实生效** |

### ❌ 未修 / 未查（下一会话主线）

| # | 问题 | 线索 |
|---|---|---|
| 12 | **对话界面最小化小窗，收不成侧边标签** | 停靠坞在 `MainActivity.kt:680-689` 无条件渲染；先 dump 看标签节点是否存在（详见 6.3） |
| 13 | **点"返回原文"每次都新建一个小窗** | 代码里**本就有复用逻辑**，属矛盾现象，需现场日志（详见 6.4） |
| 14 | **小米（MIMO）模型能拉到列表但无输出** | 需用户提供界面上"原始响应片段"（详见 6.2） |
| 15 | **联网检索全部失效**（换任何模型都一样） | `NATIVE` + Anthropic 协议的工具注入是死路；建议先用本地后端分离问题（详见 6.1） |

### ❌ 更早的 C 类待办（来自 v1.14.0 文档，仍未动）

| # | 项 |
|---|---|
| 16 | 自动记忆摘要 |
| 17 | 小窗位置持久化 |
| 18 | 消息编辑重发 |
| 19 | 分屏对比 |
| 20 | 会话导出 |
| 21 | Anthropic 协议下的客户端工具调用 |
| 22 | 三个文件的硬编码字阶迁移（配合需求 5 的全局字号） |

### 建议的推进顺序

1. 先做**不需要用户**的验证（表 ⚠️ 的 4~11 项），把"已实现但没验证"清零——
   这些都能在模拟器上完成，且**其中任何一项失败都会暴露真实 bug**。
2. 再向用户索取两份原始数据（MIMO 原始响应片段、搜索失败时的提示文案），解 14/15。
3. 然后按 6.4 的假设逐一排除 13，按 6.3 的方法查 12。
4. 最后收 C 类待办。
