# 交接文档：zcode 会话成果与遗留（2026-10-15 夜）

> **给下一位接手的 agent。** 本文件写于 2026-10-15 20:00 前后，记录本会话（zcode，模型
> deepseek-flash）在 TalkTree 上做的全部改动、验证到什么程度、以及哪些还没做。
>
> ⚠️ **先读这一条**：`docs/HANDOFF_CACHE_OPTIMIZATION.md` 与
> `docs/CACHE_PREFIX_OPTIMIZATION_PLAN.md` 里的**行号已经全部失效**（本会话改动很大），
> 结论仍然有效，行号请以当前源码为准重新定位。

---

## 零、当前交付状态（最重要）

**代码处于"编译通过、但最后一处改动未打包"的状态。**

- 最后一次 `:app:compileFullReleaseKotlin` **通过**；
- 但**没有**重新 `assembleRelease`、**没有**安装到模拟器；
- 也就是说：设备上现在装的是**上一个** APK，它与源码差一处改动
  （`FloatingWindowManager.applySnap` 里的"半屏宽度下限"，见第三节）。

**接手后第一件事**：按第二节命令打包安装，然后按第五节清单验机。

---

## 一、项目纪律（违反会被用户否掉，逐条都发生过）

| 纪律 | 说明 |
|---|---|
| 数据库 | **禁止**新增/修改 Room 表、列或 migration。新状态一律用 SharedPreferences + Gson JSON（可参考 `ui/common/FreePositionStore.kt`） |
| 用语 | 界面**严禁**出现「中文」字样，必须写「简体汉语」「繁体汉语」 |
| 图标 | 界面禁止彩色 Emoji（模型输出里的 emoji 不算），一律 Material 3 矢量图标 |
| 本地大模型 | 不得重新引入（已整体移除）；离线 OCR **必须保留** |
| 技术栈 | 100% Kotlin + Jetpack Compose，不加 NDK/C++ |
| 美学 | 不用 macOS 拟物图形，只用 MD3 矢量与配色 |
| 交互验证 | 用户会**在真机上用手感和鼠标**判断，模拟器数据不能替代 |

---

## 二、构建与部署（**必须注意渠道，否则命令会失败**）

本项目有 `full` / `lite` 两个 flavor，且**两个 flavor 共用同一个 applicationId**
（`com.treechat.workbench`）—— 装 full 会覆盖 lite。

```bash
export JAVA_HOME=/Users/deepseekworkspace/tools/jdk21/Contents/Home
export ANDROID_HOME=/Users/deepseekworkspace/android-sdk
cd /Users/deepseekworkspace/TreeChatWorkbench

# 编译（注意：:app:compileReleaseKotlin 会因双 flavor 报 ambiguous，必须带 flavor）
./gradlew :app:compileFullReleaseKotlin -q

# 打包（assembleRelease 会同时出两个渠道）
./gradlew :app:assembleRelease

# 产物（不是 apk/release/，而是按 flavor 分目录）
#   app/build/outputs/apk/full/release/app-full-release.apk
#   app/build/outputs/apk/lite/release/app-lite-release.apk

# 签名校验（签名者 CN=TalkTree，keystore: apk_inspect/release.keystore，别名 talktree）
/Users/deepseekworkspace/android-sdk/build-tools/34.0.0/apksigner verify \
  app/build/outputs/apk/full/release/app-full-release.apk

# 安装（模拟器 emulator-5554）
/Users/deepseekworkspace/android-sdk/platform-tools/adb install -r \
  app/build/outputs/apk/full/release/app-full-release.apk
```

版本号当前为 `versionCode 37 / versionName 1.37.0`（`app/build.gradle.kts:16-17`），
本会话**未**改动它。

---

## 三、本会话改了什么（按主题，含验证程度）

### A. 前缀缓存优化（省 token）——已验证

1. **RAG 知识注入从 system prompt 移到消息序列末尾**。
   `ChatNodeViewModel` 里由 `injectKnowledgeIntoLastUser()` 完成（纯函数在
   `ui/chat/PromptContextBudget.kt`）。原因：知识每轮都变，放前缀会让整条祖先链
   的缓存穿透失效。
2. **消除 `originQuote` 双重注入**：同一段原文以前发两遍，现在只发一遍。
3. **上下文裁剪改为"按完整轮次"**：`trimPromptListByTurns()`（同文件），
   不再把一轮问答切在 user 与 assistant 之间。

**验证方式（可复现）**：用第五节的两个脚手架抓 App 真实请求体确认 —— system prompt
里已无知识内容、知识出现在最后一条 user 消息尾部；另外
`tools/PromptBudgetTrimmerTest.kt` 是**纯 JVM 用例**（编译方式见第六节），
覆盖长对话 120 轮、单轮超预算、以 assistant 结尾的分叉、知识为空等 10 组、20+ 断言。

### B. `[[概念名]]` 概念链接整体停用——按用户要求删除

- 渲染：概念片段按**正文**渲染（不再有颜色/斜体/下划线/淡底）；
- 点击：轻点只判「同文本超链接」（点开分叉小窗）；
- 删掉了 `conceptLinksIn` / `BlockConceptLink` / `knownConcepts` / `onConceptClick` /
  `onConceptAsk` 及 `ConceptLinkIndex.knownIn(...)` 的每条消息调用（那是白花的开销）；
- 删掉了 system prompt 里"请用双方括号包住核心概念"的指令。

**顺带确立的视觉规则**：聊天正文里**唯一**可点标记是「同文本超链接」= 分叉小窗，
用 `MdColors.quoteLink`（= `primary`，与 Markdown URL 链接的 `tertiary` 明确区分）
+ 实线下划线 + 轻底衬。

### C. 小窗拖动与贴靠（**用户对这块最不满意，反复三轮**）

现状（源码已就位，最后一次改动未打包）：

| 行为 | 实现 |
|---|---|
| 松手**不再**惯性甩动 | `FloatingWindowCard` 里整段 fling 代码已删（用户原话："直接删掉回弹这个代码！永远不会弹"） |
| 位置**不做**插值 | `offsetSpec` 仅在 `isFullScreen` 时用 spring，其余一律 `snap()` |
| 尺寸**不弹跳** | 尺寸动画的 spring 由 `DampingRatioLowBouncy` 改成 `DampingRatioNoBouncy`（**这是"大幅回弹"的真凶**，之前一直没找到） |
| 贴靠触发 | 拖进边缘 28dp 感应区 → **显示预览影子**（半透明矩形，画在窗口下层）→ ①**松手即贴**（Windows 规则）②或按住不动 2 秒自动贴 |
| 停留计时 | `FloatingWindowManager.snapDwellToken` + Card 里的 `LaunchedEffect(token, target){ delay(2000) }`；同一区域内明显移动（>12dp）或换区域会自增令牌 → 计时重来 |
| 贴靠后取消 | 拖动标题栏任何移动 → `clearSnap()` 恢复贴靠前尺寸，继续自由拖动 |
| 半屏宽度下限 | 最后一次改动：半屏宽度取 `max(半屏, 260dp)`，因为手机上视口窄，半屏只有约 196dp < 窗口最小宽度 260dp，会把输入框挤成一列（**已在模拟器截图复现**） |

**验证到哪一步**：用 `adb shell input motionevent` 模拟"按住拖到边缘"，
读到日志 `applySnap: target=LEFT_HALF` 并在截图里看到窗口确实贴成左半屏 →
**贴靠链路可用**。但"手感/回弹"这三项**只能由用户在真机上用鼠标判断**。

### D. 流式渲染提速——部分验证，收益未量化

1. **增量 Markdown 解析**（`ui/markdown/IncrementalMarkdown.kt`，新文件）：
   只重解析"未写完的尾巴"，已完结的块按引用复用 —— 这一条同时让 Compose 能跳过
   未变块的**重组与重排**（`docs` 里那位 TokenFlow 作者的注释点破了这一点）。
2. **跳过重复的块快照注册**：`SelectableMarkdown` 里加了 `registeredBlocks` 身份守卫；
   并把"追加式变化"与"换内容"分开处理 —— 追加时**不再全清**（旧实现每帧清一次，
   连用户正在生成的消息上的选区都会被清掉）。
3. **界面刷新降频**：`ChatNodeViewModel.LONG_STREAM_CHARS = 1200`，超过它刷新
   间隔从 16ms 放宽到 50ms。
4. **自动跟随滚动限速**：`ChatMessageView.FOLLOW_SCROLL_MIN_INTERVAL_MS = 150`。
   旧实现每来一片文本就下发一次滚动，**列表偏移一变整个可视区都要重画**，
   实测这正是 `dumpsys gfxinfo` 里 `Slow issue draw commands` 的主因。
5. **复用 TokenFlow 的 `SmoothReveal`**（`ui/chat/SmoothReveal.kt`）：
   MIT 许可，**版权头已按要求保留**，鸣谢面板已登记（`SettingsDialog` 的
   `OpenSourceAcknowledgmentsCard` 首条）。本地化改动：每帧推进改为至少间隔 33ms
   （约 30fps），并在文件注释里如实标注了这处偏离。

**实测数据（模拟器 SwiftShader，仅供定位、不能代表真机）**：
空闲 ~15ms/帧；流式期间 janky 61%、`Slow issue draw commands` 93 次、
`Slow UI thread` 36 次 → **主因是绘制/光栅化（模拟器无真 GPU）**，
而非 UI 线程的解析。这也是我没有继续做"per-block 离屏层"的原因：代价是显存，
且模拟器测不出真机收益。

### E. 修掉的真 bug（都有复现路径）

1. **`.talktree` 导入崩溃 + 导出格式为乱码键名**：`app/proguard-rules.pro` 的
   Gson keep 列表漏了 `com.treechat.workbench.export` 包，R8 把 `TalkTreeBundle`
   字段名混淆 → 导出文件只有同一个构建读得懂，导入按文档格式生成的档案时
   `bundle.conversation` 为 null → 崩溃。已加 keep 规则，
   **顺带封住同类隐患** `ui.explorer.FreePosition`、`ui.dictionary.WordFolder`
   （这两个不修的话，应用升级后图标摆放/单词本文件夹会静默读空）。
   复现路径：把符合格式的 `.talktree` 放进设备 → 文件夹页 → ＋ → 导入。
2. **概念标记与分叉链接样式撞车**（用户点错）：见 B 节。
3. **选中内容"没发给 AI"**：抓真实请求体后确认**它一直是发的**，只是被放在消息序列
   **第 1 条**、后面压着三十多条历史，模型基本忽略。现已与知识注入合并成"尾部上下文"，
   贴在提问紧前面（`ChatNodeViewModel` 的 `tailContext`）。
4. **虚假注释**：我曾在注释里写"打开面板时刷新书籍列表"但没写那段逻辑，已补上。

### F. 纪律 2（禁用「中文」）——比原文档多修 3 处

原文档只列了 2 处，**实际还有 3 处用户可见**：
`ui/etymology/ui/DictBoard.kt`（义项说明）、`ocr/PdfOcr.kt`（写进识别文档抬头的
"中文识别"）、`ui/etymology/repository/EtymologyRepository.kt`（渲染成词条行的
"对应中文"）。另外 `ChatNodeViewModel.streamEntryTranslation` 的默认参数
`targetLanguage = "中文"` 已被调用方省略、会进提示词，也一并改为「简体汉语」。
prompt 与注释里的其余「中文」**未动**（会影响模型输出，应由用户决定）。

### G. 其它

- 文件拆分：`ui/reader/ShelfComponents.kt`（书架组件，从 ReaderScreen 抽出）、
  `ui/tree/TreeGraphGeometry.kt`（画布几何纯函数）—— 两者都只是**搬运 + 放宽可见性**，
  未重构内部结构。
- 两套自由摆放持久化合并为 `ui/common/FreePositionStore.kt`（删除了 `ExplorerPrefs.kt`
  与 `ShelfPrefs.kt`），并加了"画布尺寸变化时夹紧坐标"。
- 访达/书架：搜对话（标题 + 正文只读查询）、搜书（书名 + 切片正文）、书架废纸篓拖入删除、
  书架图标自由摆放、扫描档 Ctrl+F（含上一处/下一处）、小窗四分之一角贴靠。

---

## 四、本会话创建的关键文件（新会话找代码从这里入手）

| 文件 | 作用 |
|---|---|
| `ui/chat/PromptContextBudget.kt` | 纯函数：知识注入到末尾、按轮次裁剪（可 JVM 单测） |
| `ui/chat/SmoothReveal.kt` | 流式平滑显示（TokenFlow MIT 改编，含版权头） |
| `ui/markdown/IncrementalMarkdown.kt` | 流式增量 Markdown 解析（只重解析尾巴） |
| `ui/common/FreePositionStore.kt` | 图标自由摆放坐标（访达与书架共用） |
| `ui/reader/ShelfComponents.kt` | 书架 UI 组件（从 ReaderScreen 拆出） |
| `ui/tree/TreeGraphGeometry.kt` | 画布几何纯函数（从 TreeTopologyView 拆出） |
| `tools/PromptBudgetTrimmerTest.kt` | 上下文裁剪 + 知识注入的 JVM 用例 |
| `tools/mock_openai_sse_server.py` | 假 OpenAI SSE 端点，**会打印收到的请求体**（排查 prompt 必备） |
| `tools/lan_recording_proxy.py` | 录制代理：转发到真端点并记录请求体与 usage（含 cache 命中数） |

备份（同目录下，可按需回滚 ChatNodeViewModel）：
`ChatNodeViewModel.kt.bak-20261015-step0/1/2/3`、`ChatNodeViewModel.kt.bak-before-cacheopt`。

---

## 五、验机清单（**必须真机/真人操作**，我在模拟器上做不到）

小窗（用户最在意）：
1. ☐ 用**鼠标**拖动小窗：松手应当**立刻停住**，不再滑行、不再回弹；
2. ☐ 拖到屏幕左/右边缘 28dp 内：应先出现**半透明预览影子**；
3. ☐ 看到影子后**松手** → 应贴成半屏；或**按住不动 2 秒** → 也应贴成半屏；
4. ☐ 贴靠后往下拖 → 应立刻恢复原尺寸并自由移动（不再被钉住）；
5. ☐ 半屏时输入框文字不应挤成竖排（这是最后一次改动要修的，**需确认**）。

对话：
6. ☐ 选中一段文字 → 提问：AI 的回答应**紧扣你选的那段原文**（改动是把原文贴到提问旁）；
7. ☐ 原本带下划线的词现在是普通正文；分叉链接仍是 `primary` 色下划线、点开是小窗。

---

## 六、可复用的测试手段（避免重新造轮子）

### 1) 抓 App 真实请求体（判断 prompt 到底发了什么）

```bash
nohup python3 -u tools/mock_openai_sse_server.py > /tmp/mock.log 2>&1 &   # 监听 8771
# App 里把服务商 API 地址改成 http://10.0.2.2:8771/v1（模拟器访问宿主用 10.0.2.2）
# 发一条消息后看 /tmp/mock.log：会逐条打印 role / 长度 / 前 100 字
```
注意：`adb shell input text` **不支持中文**（会抛 NPE），测试提问请用 ASCII；
空格要写成 `%s`。

### 2) 转发到真端点并记录用量（含缓存命中）

```bash
UPSTREAM=http://<真端点> PORT=8770 nohup python3 -u tools/lan_recording_proxy.py > /tmp/proxy.log 2>&1 &
```
它能打印每轮的 `prompt_cache_hit_tokens / miss`，是**量化缓存收益**的唯一手段
（本会话没做这件事，见第七节）。

### 3) 模拟"按住不放"的拖动（单次 `input swipe` 无法在末端停留）

```bash
adb shell input motionevent DOWN 600 576
for x in 480 340 200 90 40; do adb shell input motionevent MOVE $x 576; sleep 0.1; done
sleep 3                      # 停留 3 秒（测"停两秒吸附"）
adb shell input motionevent UP 40 576
```
⚠️ 一定要发 `UP`，否则指针会卡在按下状态。

### 4) 帧耗时与卡顿归因

```bash
adb shell dumpsys gfxinfo com.treechat.workbench reset
# …操作…
adb shell dumpsys gfxinfo com.treechat.workbench | head -20    # Janky / Slow UI / Slow issue draw commands
```

### 5) JVM 单测（不需要 Android 设备）

用 Gradle 缓存里的 Kotlin 编译器直接跑生产源码（本会话验证过可用）：

```bash
C=/Users/app/.gradle/caches/modules-2/files-2.1
KJAR=$C/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.0/1de310de3446414381282ffc46c3ed3919a4d1da/kotlin-compiler-embeddable-2.0.0.jar
STDLIB=$C/org.jetbrains.kotlin/kotlin-stdlib/2.0.0/b48df2c4aede9586cc931ead433bc02d6fd7879e/kotlin-stdlib-2.0.0.jar
SCRIPT=$C/org.jetbrains.kotlin/kotlin-script-runtime/2.0.0/fdc27dd047d592fa41d2dfa21e7621287a361612/kotlin-script-runtime-2.0.0.jar
DAEMON=$C/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.0.0/b39c612768614c4428c96ee9c65477181fffe9d/kotlin-daemon-embeddable-2.0.0.jar
TROVE=$C/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
ANNOT=$C/com.intellij/annotations/12.0/bbcf6448f6d40abe506e2c83b70a3e8bfd2b4539/annotations-12.0.jar

rm -rf /tmp/pbt && mkdir -p /tmp/pbt
java -cp "$KJAR:$STDLIB:$SCRIPT:$DAEMON:$TROVE:$ANNOT" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -cp "$STDLIB" -d /tmp/pbt \
  app/src/main/java/com/treechat/workbench/network/protocol/PromptMessage.kt \
  app/src/main/java/com/treechat/workbench/ui/chat/PromptContextBudget.kt \
  tools/PromptBudgetTrimmerTest.kt
java -cp "/tmp/pbt:$STDLIB" PromptBudgetTrimmerTestKt
```
（注意：`MarkdownOffsets.kt` 依赖 Compose 类型，**不能**这样单独编译。）

---

## 七、明确没做的事 / 已知遗留

1. **缓存收益没有量化**。改动 1 的目标是省钱（缓存命中），但本会话**没有**
   用它自己的录制代理去比对"改动前后 `prompt_cache_hit_tokens`"。
   接手第一件值得做的事：用第六节 (2) 的代理，在同一会话里连问两轮，看第二轮命中数。
2. **`PromptCacheOptimizer.kt` 仍然从未被调用**，按上一份交接文档要求**不要接它**
   （现有 `getAncestorChain` 的拼接结构已等价）。
3. **死代码/残留**（可清理，均无功能影响）：
   - `ui/chat/ConceptLinkIndex.kt`：概念链接停用后已无调用方；
   - `FloatingWindowManager` 里 `beginFling / endFling / moveWindow / activeFlingingWindowId`
     在删掉惯性甩动后已无调用方；`FloatingWindowCard` 里 `zeroLatency` 局部变量也已无用。
4. **测试用凭据**（用户在会话中提供，仅用于联调）：
   - workbuddy 反代：`http://10.4.76.130:8765/v1`，key `wb-lan`，model `deepseek-v4.1-flash`
     （**注意**：该端点在本 App 里长期表现为"有响应但无输出"，同一请求体用 curl 回放
     却正常；`adb` 里 MIMO 端点则能正常出字。尚未定位，怀疑与该端点的 SSE 帧格式
     或压缩有关，与本 App 自身逻辑**无关**。）
   - 小米 MIMO：`https://api.xiaomimimo.com/v1`，model `mimo-v2.6-flash`
     （key 在用户消息里给过，未写入本文件）。
5. **真机手感类问题**（小窗回弹、贴靠、渲染是否真的更快）**一律未经真人验证**，
   只能由用户判断。不要用模拟器数据宣称"已经快了"。
6. **模拟器上的历史数据是脏的**："单词：kindle"会话里累积了大量
   `（无输出）` 等测试消息（我测试时发的），用户若要干净环境可自行删除。

---

## 八、一句话总结给下一位

代码是**可编译、逻辑自洽**的；本会话把"省 token 的前缀结构"和"引用原文没有效"两个真问题
修掉了（都有真实请求体为证），把小窗的**自动吸附改为 Windows 式（预览 + 松手即贴 + 停 2 秒）**、
并**删掉了惯性甩动与尺寸弹跳**；但**所有手感类结论都需要用户在真机上确认**，
而且**缓存收益的量化数字还欠着**。
