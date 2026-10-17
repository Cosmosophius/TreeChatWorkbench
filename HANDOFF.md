# 条畅 TalkTree 交接文档

写给接手的人（或另一个 AI 代理）。读完这份再动代码，可以省掉我踩过的所有坑。

- 项目根目录：`/Users/deepseekworkspace/TreeChatWorkbench`
- 当前版本：`versionCode 59` / `versionName "1.41.7"`
- 最新构建产物：`app/build/outputs/apk/full/release/app-full-release.apk`、`app/build/outputs/apk/lite/release/app-lite-release.apk`
- 1.41.7（含 1.41.6 的 OCR 链接修复）尚未复制到交付目录 `/Users/deepseekworkspace/release/`（用户要求自行取包）
- **不是 git 仓库**。没有任何版本回退能力，改动前请自己留档（项目既有做法见 `docs/_removed/`）。

---

## 一、环境与构建（先读这一节，否则你连编译都过不去）

### 1. 必须显式指定 JDK 21

本机系统 Java 是 **JDK 26**，而 Gradle 8.7 解析不了它的版本号，报错只有一行数字：

```
* What went wrong:
26
```

看不出任何线索。解决办法是显式用随项目放的 JDK 21：

```bash
cd /Users/deepseekworkspace/TreeChatWorkbench
JAVA_HOME=/Users/deepseekworkspace/tools/jdk21/Contents/Home ./gradlew <任务> --offline
```

（那是 Temurin 21.0.5。iOS/macOS 侧不要用 `timeout` 命令——macOS 没有。）

### 2. 双 flavor，任务名必须写全

`full` 与 `lite` 共用一个 `applicationId`（`com.treechat.workbench`），所以：

- 编译：`:app:compileFullReleaseKotlin` 或 `:app:compileLiteReleaseKotlin`。
  写 `:app:compileReleaseKotlin` 会因 flavor 歧义报错。
- 打包：`:app:assembleFullRelease :app:assembleLiteRelease`
- 两个包**不能并存安装**（同一个 applicationId），且两者体积一致**不代表内容相同**。

### 3. 签名（绝对不能换证书）

签名配置在 `app/build.gradle.kts` 的 `signingConfigs["compat"]`：

- keystore：`/Users/deepseekworkspace/apk_inspect/release.keystore`
- 类型 PKCS12，alias `talktree`，store/key 口令都是 `123456`
- 证书 SHA-256：`b3fec59c89cff043c12ec8f4228f49790aa49ae3e5ccd6205e2424948c1386a5`

**必须沿用同一张证书**：换成 debug 证书后新包无法覆盖安装，用户只能卸载重装，而卸载会带走他的书库、笔记与本地模型配置。

### 4. 交付口径（这一条是本项目反复发生的事故）

用户拿到的**只能是交付目录里那个签名 APK**。只跑编译、不打包、不复制，等于用户那边什么都没变——本项目已经因此让用户白测了好几轮。

**交付目录 = `/Users/deepseekworkspace/release/`。2026-10-17 起禁止再交付到 `~/Downloads`。**

标准收尾流程：

```bash
cd /Users/deepseekworkspace/TreeChatWorkbench
JAVA_HOME=/Users/deepseekworkspace/tools/jdk21/Contents/Home \
  ./gradlew :app:assembleFullRelease :app:assembleLiteRelease --offline
BT=/Users/deepseekworkspace/android-sdk/build-tools/34.0.0
OUT=/Users/deepseekworkspace/release
cp app/build/outputs/apk/full/release/app-full-release.apk $OUT/talktree-full-<版本>.apk
cp app/build/outputs/apk/lite/release/app-lite-release.apk $OUT/talktree-lite-<版本>.apk
"$BT/apksigner" verify --print-certs $OUT/talktree-full-<版本>.apk | grep "SHA-256 digest"
"$BT/aapt2" dump badging $OUT/talktree-full-<版本>.apk | grep "^package:"
```

记得把上一版的 APK 从交付目录删掉，否则用户可能装到旧的。

### 5. 没有设备可验证（最重要的一条）

本环境下 `adb devices` 为空、没有模拟器可用。**所有改动都只做到"编译通过 + 代码路径核对"，从未在设备上运行过一次。** 交付时必须如实告诉用户哪些项需要他实测，不要把"编译通过"说成"已修好"。

拿到设备后验证方式：`adb install -r <apk>`。

### 6. Android SDK

`/Users/deepseekworkspace/android-sdk`，其中 `build-tools/34.0.0` 有 `aapt2`、`apksigner`、`zipalign`。

---

## 二、铁律：用户明令的约束（违反即返工）

1. **严禁新增或修改 Room 的表、列与 migration。** 需要持久化就用 SharedPreferences 存界面层状态（项目已有大量先例，见下）。
2. **界面文案一律写「简体汉语」「繁体汉语」，绝不写「中文」。**
3. **不用 macOS 拟物图形，只用 Material 3 矢量图形。** 文件夹页是"复用访达的结构，视觉全换 M3"（配色走 `MaterialTheme.colorScheme`）。
4. **OCR 不能移除。**
5. **什么都不要问，一路做完。**
6. 用户提供过的 API 凭据属测试用机密，不要外传、不要写进提交物。

---

## 三、代码地图（关键文件与职责）

### 应用骨架

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | `TreeChatMainScreen`。Tab 用 `AnimatedContent(targetState = viewMode)`，**外层套 `rememberSaveableStateHolder()` + `SaveableStateProvider(mode.name)`**——缺这一层时各 Tab 的 `rememberSaveable` 不会生效，切 Tab 就丢状态。浏览器是"常驻覆盖层"（非浏览器模式下 `Modifier.size(0.dp)`），因为它一被移出组合树 WebView 就会拆装、丢页面状态。Ctrl+K / Ctrl+F 打开统一命令面板。 |
| `ui/reader/ReaderScreen.kt` | `BookshelfScreen`（书架 + 阅读器同一个 composable）。`activeDocumentId` 用 `rememberSaveable`，`activeDocument` 由 `pool.documents` 派生；文档本体由 `ReaderDocumentPool` 落盘在 SharedPreferences `reader_document_pool`。 |
| `ui/reader/ReaderDocumentPool.kt` | 书架数据 + 阅读进度，落盘 JSON；带一条"旧构建字段名被 R8 混淆过"的抢救路径。 |
| `ui/reader/AdaptiveDocumentView.kt` | 阅读器宿主：按渲染方式分「文本路径」（`SingleDocumentPageCard`，用对话界面那套 `SelectableMarkdownText`）与「实况路径」（`LiveTextPageCard`，扫描/图片页）。翻页、点按分区、工具条、翻译/句法都在这。 |
| `ui/reader/LiveTextPageView.kt` | 单页实况卡：位图 + 选词浮层。**缩放/平移/滚轮缩放/滑动翻页/标签**都在这个文件的手势里。 |
| `ui/reader/LiveTextComposeOverlay.kt` | OCR 选词浮层本体（矩形盒模型）：观察期 → 点按 / 手柄微调 / 拖动扩选三条腿。 |
| `ui/reader/PageTagStore.kt` | 页面标签（位置锚点）落盘，SharedPreferences `reader_page_tags`，存归一化坐标。 |
| `ui/selection/SelectionModel.kt` | `MessageSelectionController`——**对话界面选词的核心**。内部直接存 `mutableStateMapOf<Int, TextLayoutResult>`。 |
| `ui/selection/SelectableMarkdown.kt` | `SelectableMarkdownText`——对话界面的选词组件（长按/桌面拖选/手柄/链接点击判定）。 |
| `ocr/OcrEngine.kt` | ML Kit 封装，产出 `OcrWordBox(id, bounds, text, confidence)`，来源是 `block.lines[].elements[]`。 |
| `ui/explorer/FolderExplorer.kt` | 访达式文件夹页；`currentFolderId` 与"按目录记忆的列表/网格滚动"都在这。 |
| `data/prefs/AppSettings.kt` | `selectionScheme`（5 档）与 `inputMode`（AUTO / 强制键鼠 / 强制触控 / 强制触控板 / 强制触控板气泡）。 |
| `ui/window/*` | 全局浮层小窗（吸附停留 1 秒分屏、边缘可挂出屏幕只留 56dp）。 |
| `docs/_removed/` | **被刻意移出 APK 的"扫描王"功能源码**（`scan/`、`ui/`、`assets/`）与若干 `.disabled`/`.bak`。用户要求"代码可保留但不占 APK 体积"，所以不要随手删，也不要随手接回来。 |

### 持久化用到的 SharedPreferences（本项目不允许动 Room，界面状态一律走这里）

实际在用的只有四个：`treechat_settings`（设置项）、`treechat_prefs`（界面层偏好，如卡片自由摆放坐标、封面偏好、选词/输入档等）、`reader_document_pool`（书架与阅读进度）、`reader_page_tags`（页面标签）。

加新的界面状态时请沿用这个做法：新建一个小的 Store 类包一层 SharedPreferences，不要试图加 Room 表。

---

## 四、当前正在解决的问题：OCR 选词（接手重点）

### 用户的诉求

> OCR 选词根本不好用，尤其是鼠标：选完不持久化，每个字都不能锁定。要"跟对话界面一样"，而且**随意缩放都不能错选**。

### 已查清的根因（都已在 1.41.5 修掉）

**1. 放大之后必错选** —— `LiveTextPageView` 原来用 `detectTransformGestures`，而它对**单指/左键拖动也会报 pan**。于是"按住一个字往旁边拖"时页面同时在平移，光标对应的内容坐标随之变化：看着拖的是甲词、实际选到乙词。未放大时可平移量为 0（被钳死），所以这个病**只在放大后发作**。已改为：手势只由**两根及以上手指**触发，单指/左键一律归取词；另加"起点落在空白处则本层负责平移"的分流规则。

**2. "每个字都不能锁定"** —— 鼠标点一下（无拖动）会走"点按"分支，而那条分支以前只有两条腿：命中链接就开窗，否则**已有选区就清掉**。也就是说用鼠标点字只会把选区清掉，从来没有"选中这个字"这条腿。已补第三条腿：**点按落在词上 = 锁定该词并弹工具条**。

**3. 鼠标横拖顺手翻页** —— 滑动翻页判据是"横向位移 > 屏宽 18%"，而鼠标选一个短语很容易划过 18%，于是选词变成翻页、选区随页面一起没了。已改为**桌面指针完全不参与滑动翻页**。

**4. 抬手事件没被消费** —— 同一个手势还会被外层再判一次（翻页/双击放大），刚选好的字被冲掉。已在观察期、取词期、手柄拖动期三处抬手显式 `consume()`。

另外顺手定义了：在空白处**拖动** = 平移页面（且不会留下标签）；空白处**按住不动** = 放一个位置标签（长按判定 `TAG_HOLD_MS`）。

### 还没修的部分（差异仍在，用户可能还会提）

- 手柄是**四角**，对话界面是**两个水滴**。
- 词边界是在 ML Kit 的框内**按横向比例推算**的（`refineToWord`：比例 → 字符下标 → ICU 分词 → 裁子盒）。汉字近等宽基本准；**西文比例字体在行尾会有偏差**。
- 这两个差异不是 bug，是模型差异：OCR 只有"几何 + 一整块的文本"，没有 Compose 的 `TextLayoutResult`。

### 深层方案（只有在用户仍不满意时才做，且必须先有设备可验证）

`MessageSelectionController` 现在**直接依赖 `TextLayoutResult`**（`SelectionModel.kt` 里 `mutableStateMapOf<Int, TextLayoutResult>`，命中/手柄/逐字高亮全靠它）。要让它同时服务扫描页，做法是：

1. 把几何来源抽成接口（大意：给我一个点 → 它在哪块、第几个字；给我一个字符下标 → 它的矩形）。
2. 提供两个实现：现有的 Compose 排版（对话界面走这条，行为必须不变），以及新的 **OCR 行图**（把 ML Kit 的行盒按行摆好，用行盒宽度与字符数推算字位）。
3. 把实况卡 `TALKTREE` 档的路由从矩形浮层切到这条新链路，`BOX_DRAG` 原样保留。

注意取舍：**不要**试图用"把 OCR 文字交给 Compose 重排、再拿那层文字去选"的办法——那样高亮会落在正确的行上但**不会贴着印刷笔画**，看起来恰恰像"错选"，与用户的诉求相反。扫描页的价值就是看着原页读，因此几何必须以 ML Kit 的框为准。

---

## 五、必须先真机验证的清单（当前全部未验证）

1. 鼠标点字能否**锁住**（工具条出现、点别处才消失）。
2. 鼠标在文字上**拖动**能否扩选，且松手保留、四角手柄可继续微调。
3. **放大之后**拖动选词还错不错。
4. 鼠标横拖还会不会翻页；滚轮是否以指针为锚点缩放。
5. 切 Tab 回来看：**打开的书还在且停在原页**、**文件夹还在原目录**、列表/网格滚动位置是否恢复。
6. 阅读器里鼠标拖动能否划选（这一条曾被写死成"强制触控"而失效，现改为跟随设置）。
7. OCR 两个模式（条畅选词 / 电脑框选）手感是否**确实不同**（以前 `selectionMethod` 不参与判定，两档完全一样）。

---

## 六、已知缺口与技术债

1. ~~**OCR 超链接在中文扫描件上基本不可用。**~~ **1.41.6 已修（未真机验证）。**
   原状：`LiveTextComposeOverlay` 里 `linkBoxes` 过滤了 `t.length < 2`，而 ML Kit 对汉字常常逐字切盒——单字永远过不了这条下限，于是中文扫描件上链接整条都标不出、也点不了。
   现改法：匹配逻辑抽成 `findOcrLinks()`，两条腿——① 原判据（≥2 字词盒命中引用原文），西文与成块识别的中文走这条；② **同一行内相邻词盒拼串**命中引用原文，让连续单字能拼出来。拼接**不补盒间空格**（汉字之间本无空格，补了反而拼不出引用原文）；只有整段拼接落在引用原文内才命中，所以孤立单字不会被误标成链接。
   同行判定抽成 `groupOcrLines()` 作唯一来源，`orderedReadingOrder()` 改为复用它，避免两处判据日后分叉。
   **待验证**：中文扫描件上的链接能否标出并点开（本环境无设备）。
2. **`NativeSelectionTextLayer` 是死代码。** 它是"基于 OCR 文字 + 原生 SelectionContainer"的那一层，全项目**没有任何调用点**；`LiveSelectionMethod` 也只有 `TALKTREE` / `BOX_DRAG` 两档，`SYSTEM_NATIVE` 从未接线。要么接上，要么删掉——留着会误导后来人以为"已经有文字层方案了"。
3. **无 git**，改动不可回退。
4. `docs/_removed/` 里的扫描功能是用户要求"移出 APK 但不删代码"的，不要当成垃圾清理掉。

---

## 七、历史事故（不要再踩）

1. **把"编译通过"当"已交付"**：用户看到的是 APK，不是构建日志。
2. **JDK 26 让 Gradle 全线失败，报错只有一行 `26`**：必须显式 `JAVA_HOME=tools/jdk21`。
3. **长按选中"一松手就没了"** 的经典机制：宿主在外层挂 `detectTapGestures`，它要求"整个手势里没有任何 change 被消费"才认作点击；内层长按路径若不消费抬手，外层就把这次抬手当成一次轻点 → 回调清选区。**修法一律是抬手处显式 `consume()`**。这个坑在对话正文、阅读器、OCR 浮层各犯过一次。
4. **`pointerHoverIcon` 的 onDetach 会把光标重置成 null**，且 `overrideDescendants` 会让祖先的光标赢过子节点——改光标前先看 `ui/selection/SelectableMarkdown.kt` 里那几段注释。
5. **双 flavor 同 applicationId**，不能用 `:app:compileReleaseKotlin`，也不能假定两个包内容相同。
6. 改完别只看 `-q` 无输出就以为成功，要看 `BUILD SUCCESSFUL`。

---

## 八、建议的接手顺序

1. 先按第一节把包重新打一次，确认你的环境能复现出一条完整交付链。
2. 拿设备跑第五节的清单，把真实表现记下来（这一步之前，任何"修好了"都是猜测）。
3. ~~按用户反馈修；优先处理第六节的第 1 条（OCR 链接），那是明确的功能缺口。~~
   **1.41.6 已改**（`findOcrLinks()` 两腿匹配，见第六节第 1 条），但**未真机验证**。
   接手后请先拿中文扫描件实测这一条，再决定是否继续。
4. 只有在用户仍不满意"手柄形状/词边界精度"时，才动第四节的深层方案——并且先有设备可验证。

---

## 九、1.41.6 / 1.41.7 本轮实况（写给下一个接手的人）

- 环境已复现：`JAVA_HOME=tools/jdk21` 下 `:app:assembleFullRelease :app:assembleLiteRelease --offline` → `BUILD SUCCESSFUL`。
- 版本号已由 `57 / 1.41.5` 递增到 `58 / 1.41.6`（OCR 链接）、再到 `59 / 1.41.7`（高亮污染），
  不改版本号用户无法区分新旧包。
- 两个包签名均为 `b3fec59c…c1386a5`，与既有证书一致，可覆盖安装。
- **交付未完成**：本轮用户明确选择"先不交付，我自己取包"，因此 `/Users/deepseekworkspace/release/` **未做任何改动**。
  下一个接手的人若要交付，请自行执行第一节第 4 条的收尾流程
  （复制 + 删除旧包 + 校验签名）。**在把新包放进交付目录之前，用户那边什么都没变。**
- 本轮改动**从未在设备上运行过**（`adb devices` 为空），只做到"编译通过 + 代码路径核对"。

### 1.41.7 修掉：对话高亮"污染多余文本"（第六节第 5 条）

用户反馈：对话界面高亮选中部分会**连带点亮很多无关文字**。

根因在**坐标维度**，不在匹配算法：`HighlightRange` 里存的是
`MdRichBlock.toDisplay()` 产出的**块内局部下标**，但这个类型**不带块号**，
而 `SelectableMarkdownText` 把整份区间列表**原样发给每一个块**
（见 `SelectableBlock` 的 `ranges`）。于是"第 5 段的第 3~7 个字"这一条高亮，
会在**其余每一段**的第 3~7 个字上各点亮一次 —— 段落越多、越靠后的块错得越明显。

改法（三处，缺一不可）：
1. `HighlightRange` 增加 `blockIndex` 字段，并写清"它是必需信息、不是可选的"；
2. `projectHighlights` / `projectKeywordHits` 改用 `forEachIndexed`，投影时带上块号；
3. `SelectableBlock` 的 `ranges` 改为 `highlights.filter { it.blockIndex == index }`。

⚠️ 接手注意：`HighlightRange` 的 `start/endExclusive` **永远是块内局部下标**，
不是整条消息的全局下标。任何新的渲染/命中路径都必须先按 `blockIndex` 过滤，
否则立刻复现本条 bug。检索关键词高亮（`projectKeywordHits`）走同一条链路，已一并修好。

**待验证**：真机上划一段高亮后，是否只有那一段被点亮（本环境无设备）。


## 多线协作（2026-10-17 起）

- 仓库已 git 化：master = 安卓发版主线（本会话/发版会话用）；`desktop-port` = Linux ARM64 + macOS 移植分支（新会话用，开工即 `git checkout desktop-port`，避免与发版改动互踩）。
- 版本口径：`versionName` 全平台同源共进，`versionCode` 仅安卓。桌面里程碑用 `1.4x.0-desktop.N` 预览尾缀，达到门槛并入正式号。
- 交付目录：安卓仍进 workspace/release/（talktree-full/-lite-<ver>.apk）；桌面进 workspace/release/desktop/（talktree-<os>-arm64-<ver>.<ext>）。
- 合并节奏：desktop-port 每完成一个稳定阶段回合并 master，合并必验：安卓双 flavor 编译 + 桌面目标编译。

## 安卓线增量（2026-10-17 晚，桌面移植会话合并前必读）

安卓发版线已推进到 **v1.41.24（versionCode 76）**，v1.41.19 以来共 6 个提交
（`git log --oneline` 可见，全部只动安卓侧与共享 UI 层）。对移植方要点：

### 1. 共享 UI 层的新架构件（common 友好，桌面上来就有）
- **多色源标记通道**：`HighlightProjection.SourceMark(start, endExclusive, color)` +
  `projectSourceMarks()`；`SelectableMarkdownText(sourceMarks=...)`、`SelectableBlock(markGroups=...)`、
  `toAnnotated(markGroups=...)`。生词/页内查找/当前命中共用一条管线 ——
  桌面端任何"给正文叠色"的需求**不要再加新参数**，加 SourceMark 即可。
- **侧边栏**（`ui/reader/ReaderSidePanel.kt`）：生词/词条/**句法**/AI/注释 五页签，
  可拖宽、可完全收起（`ReaderPanelState`，SharedPreferences）。
  句法标注 GUI（`SyntaxSentenceCard`：点树行↔原句片段双向聚焦）**只活在侧栏**，
  正文页与 OCR 页都不再画卡 —— 别把它搬回原文。
- **词条结构化**：`VocabIndex.EnEntry` 新增 `tags`/`levels`（vocab2 成对列）；
  义项分组用 `GlossParser`；发音走 `data/reader/Speech.kt` 的 **Speaker 接口**
  （Android 实现已给；桌面端请补 `say`/espeak 实现，签名一致即可，UI 不用动）。
- **翻译对齐**：`ChatNodeViewModel.requestSentenceTranslationAligned()` +
  `TRANSLATE_ALIGNED_CONTRACT`（严格 JSON 句对）；失败自动回退整段流式。
  -- P5 UI 在 `SentenceTranslateSheet.kt`。
- **链接悬停预览**：`SelectableBlock` else 分支（Compose UI 1.6.8 无
  `PointerEventType.Hover`，用 Enter/Move+Exit 等价——升级 1.7 后可换 Hover）。

### 2. 已移除的（别"补回来"）
正文/OCR 工具条的「划线」「笔记」按钮、侧栏「打开白板」按钮 —— 用户明确移除；
读书笔记采集只剩手写批注层，旧划线数据仍在侧栏「注释」页可看。

### 3. 修过的接线错误（桌面端容易踩同坑）
`AdaptiveDocumentView` 里 self-document 的调用点参数（activeExamTokens/onPageText/
toolState/...）曾整批错插进 **LiveTextPageCard** 的调用——两处调用点形似，
带默认值的新参数漏插不报错。给双卡片加参数时：**先 grep 两处调用点逐一核对**。

### 4. 待办归属
`docs/REDESIGN.md` 的 P6（对话气泡桌面快捷动作）留给下一批 ——
对话层正被桌面 M2 触碰，本轮刻意避开。
