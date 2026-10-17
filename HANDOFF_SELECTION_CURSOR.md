# 条畅 TalkTree — 选词 / 光标 / 小窗 交接文档

> **本文档取代 `HANDOFF_FOR_ANTIGRAVITY.md` 中关于构建命令与"已真机验证"的部分。**
> 那份文档写于更早的会话，其中的单 APK 路径、`compileReleaseKotlin` 命令、
> 以及"第 5、6、17 项已真机验证"的说法**与当前事实不符**，不要照抄。
> 项目已知的六条纪律（禁改 Room 架构、禁写「中文」二字、禁彩色 Emoji、
> 禁重引本地大模型、保留离线 OCR、选词器统一复用）仍然有效，见那份文档第二节。

- **仓库**：`/Users/deepseekworkspace/TreeChatWorkbench`
- **包名**：`com.treechat.workbench`
- **没有版本控制**（不是 git 仓库，无 `.git`）。改动无法回滚，动刀前先自己备份。
- **文档撰写时刻的产物**：`versionCode = 39`，`versionName = "1.38.1"`

---

## 一、先读这一节：当前的真实状态

用户（备考研究生，情绪已经非常急躁）在 **1.38.1** 上实测后报告：

| # | 现象 | 状态 |
|---|------|------|
| 1 | 鼠标悬停在正文上，I-Beam 无故变回箭头，随后**选不中词** | **未解决** |
| 2 | 拖选到某个字符（如「其」）就停住，**后面选不进去**，"所有单行全是这样" | **未解决** |
| 3 | **小窗界面**（不论是否全屏）尤为严重，"貌似是小窗就会选不中" | **未解决** |
| 4 | **表格内文本无法选中**，会"漂移" | **未解决** |
| 5 | 小窗放大后被顶栏遮挡、返回不了 | 已修，**未经真机验证** |

**请务必理解第 3 条的严重性**：用户自己观察到的"小窗才选不中"是**目前最有价值的线索**，
它把嫌疑从"标点/折行"整体转移到了**小窗的坐标链路**上。本文档第五节列出了据此应查的方向。

### 一个必须纠正的错觉

不要相信本仓库代码注释里"已修复 / 根因"之类的断言。这些注释是**历代代理（包括我）
在未获真机验证的情况下写下的推理**，其中一部分已被用户实测证伪。典型例子见第三节。

---

## 二、构建与打包（已实测可用的唯一一套命令）

**`./gradlew` 与 `:app:compileReleaseKotlin` 在本机不可用**——项目有 `full` / `lite`
两个 flavor，不带 flavor 的任务名会报 "ambiguous"。请用下面这套：

```bash
export JAVA_HOME=/Users/deepseekworkspace/tools/jdk21/Contents/Home
export ANDROID_HOME=/Users/deepseekworkspace/android-sdk
GRADLE_BIN=/Users/app/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle
cd /Users/deepseekworkspace/TreeChatWorkbench

# 编译（快，3 秒级；加 --rerun-tasks 强制重编，约 16 秒）
"$GRADLE_BIN" --offline :app:compileFullReleaseKotlin

# 打两个包（约 2~3 分钟）
"$GRADLE_BIN" --offline :app:assembleRelease

# 验签
/Users/deepseekworkspace/android-sdk/build-tools/34.0.0/apksigner verify \
  app/build/outputs/apk/full/release/app-full-release.apk   # 期望输出 SIGNATURE OK
```

产物（两个 flavor **共用同一个 applicationId**，所以装哪个就是哪个）：

```
app/build/outputs/apk/full/release/app-full-release.apk    # 完整版（含词典）
app/build/outputs/apk/lite/release/app-lite-release.apk    # 精简版
```

⚠️ **每次改动务必同时改 `app/build.gradle.kts` 的 `versionCode` / `versionName`**
（当前在第 16–17 行）。两个 flavor 共用 applicationId，`adb install -r` 有时会因
版本号未变而静默跳过实际替换，导致用户"装了新包但测的是旧代码"。用户上一轮能确认
自己装的是 1.38.1，靠的就是版本号。**这是本项目已经踩过的坑，务必保持。**

**签名**：已配好，`assembleRelease` 自动完成签名与对齐，无需手工处理。

---

## 三、已经做过的修改（本节是事实清单，含"未验证"标注）

### 3.1 `SelectableMarkdown.kt`（约 1700 行，改动最多）

- **表格登记死代码修复**（`:225` `registerBlockIfReady`）
  表格分支原先排在 `val origin = origins[index] ?: return` **之后**，而表格从来不写
  `origins`/`layouts`（`SelectableBlock` 的 TABLE 分支只传 `onCellOrigin`/`onCellLayout`），
  于是整段表格登记逻辑**一次都没执行过**。现在表格分流提到最前并 `return`。
  → 我在模拟器上长按表格确实选中了整表。**但用户 1.38.1 实测"表格依旧无法选中"。**
  说明这不是全部原因，或另有区域性差异。

- **`syncLiveGeometry()`**（`:450`）
  在每次 `awaitFirstDown()` 之后调用，用活句柄 `LayoutCoordinates` 经
  `root.localPositionOf(child, Offset.Zero)` 重取每个块的 origin，再触发重新注册。
  设计意图：不再依赖 `onGloballyPositioned` 是否派发过。
  → **用户实测无效。** 注意它**只覆盖非表格块**（依赖 `blockCoords`，
  而表格格子的坐标从不写入 `blockCoords`，见第五节 P1）。

- **`anchorInGrid` 豁免**（`:690`）
  落点在表格/代码块时，豁免"纵向占主导就交还列表滚动"的两道防线，
  避免在表格里一拖就变成滚列表（用户描述的"漂移"）。
  → 未验证。

- **`inputMode` 加入 `pointerInput` 的 key**
  原先只有 `(markdown, selectionScheme)`，切输入设备档位时手势循环不重启，
  闭包攥着旧档位——这是"切到强制触屏却仍按桌面级选词"的根因。已修。

- **`useFastPath` 优先级**：改为「输入设备档位 > 选词方案」，强制档位不再被方案覆盖。

- **轻点判链接的顺序**：先在两条分支里判"同文本超链接"，**再**判"取消选区"。
  旧顺序下只要当前有选区，这次单击就被吃掉，链接永远点不开（用户报的"极其频繁点不出来"）。

- **`hoverIconModifier`**：`Modifier.pointerHoverIcon(PointerIcon.Text, overrideDescendants = true)`
  → **用户实测无效**，见第五节 P2 的正确做法。

### 3.2 `SelectionModel.kt`（约 1050 行）

- **`offsetNearestInLine()`**（`:58`）：逐字符比较 `layout.getBoundingBox(i)` 的真实矩形，
  取欧氏距离最小者，再按"落在该字右半边则插到它后面"定 caret。
  **四处命中点全部改用它**（`:227` `:267` `:722` `:781`），
  `getOffsetForPosition` 在 `selection/` 下已无任何实际调用（仅存于注释）。
  设计意图：绕开 CJK **悬挂标点**——`，` `）` `"` 会被禁则处理挂到 `lineRight` **之外**，
  而旧写法把 x 夹进 `[lineLeft, lineRight]`，恰好把鼠标夹到悬挂字的左边，那字永远点不中。
  → **这被当成"到『抛弃』就选不中"的根因修掉了，但用户实测依旧"到『其』就选不了"。**

- **行号 `coerceIn(0, lineCount - 1)`** 在四处命中点均已加上；块级边界检查保留为
  唯一剩下的 `continue`。

### 3.3 光标与小窗（本轮新增，均未验证）

- `FloatingWindowManager.kt`：新增 `topInsetPx`（顶栏下沿在窗口层坐标系的 y），
  `rectFor()` 的所有贴靠区从 `topInsetPx` 起算，`clampIntoViewport()` 的 y 下界
  改为 `topInsetPx`（原先允许 0，即标题栏藏进顶栏底下）。
- `FloatingWindowCard.kt`：全屏时 `animatedOffsetY` 目标改为 `topInsetPx`
  （原为 0），`animatedHeight` 改为 `screenHeight - topInsetDp`。
  原因：窗口层挂在根 Box 上，`y=0` 正是 Scaffold 顶栏位置，全屏后会把顶栏
  （含标签行）一起盖住，用户"返回不了"。
- `MainActivity.kt`：用 `SideEffect` 把 `innerPadding.calculateTopPadding()` 写进
  `windowManager.topInsetPx`。
  ⚠️ `calculateTopPadding()` 是 `PaddingValues` 的**成员函数**，
  **不要**为它 `import androidx.compose.foundation.layout.calculateTopPadding`，会编译失败。
- `LiveTextComposeOverlay.kt` / `AdaptiveDocumentView.kt` / `ChatMessageView.kt`：
  光标挂点 `overrideDescendants = true`；`LiveTextComposeOverlay` 的 `pointerInput`
  key 补上 `inputMode`。

### 3.4 其它（与选词无关）

- `SettingsDialog.kt`：外观区顺序改为 主题色 → 壁纸取色（莫奈）→ 界面语言 → 外观模式。
- `AppSettings.kt`：`InputMode` 含 `FORCE_TRACKPAD`；`GraphWheelMode` 等。

---

## 四、项目结构与关键坐标约定（接手前必读）

```
app/src/main/java/com/treechat/workbench/
├── MainActivity.kt                      # 根 Box(466) → Scaffold(610) → 窗口层(1048)
├── ui/selection/
│   ├── SelectableMarkdown.kt            # 渲染 + 手势循环 + 块注册（核心，~1700 行）
│   ├── SelectionModel.kt                # MessageSelectionController + 命中检测 + 几何
│   └── QuoteLinkModel.kt                # 同文本超链接匹配
├── ui/markdown/MarkdownOffsets.kt       # 块解析 + toAnnotated（恒等映射）
├── ui/chat/ChatMessageView.kt           # NodeChatContent（主界面与小窗**共用**）
├── ui/window/
│   ├── FloatingWindowCard.kt            # 小窗渲染 + 生长动画(graphicsLayer scale)
│   └── FloatingWindowManager.kt         # 窗口状态/贴靠/夹取
└── ui/tree/TreeTopologyView.kt          # 画布 + 图空间小窗
```

### 坐标约定（**这是理解全部选词 bug 的钥匙**）

`SelectableMarkdownText` 的外层 `Box` 挂 `onGloballyPositioned` 存下 `rootCoordinates`，
**所有块坐标都以它为原点**：

```kotlin
// SelectableMarkdown.kt:1239（SelectableBlock 内）
val origin = rootCoordinates?.takeIf { it.isAttached }
    ?.localPositionOf(coords, Offset.Zero)
    ?: coords.positionInParent()      // ← 没有 root 时的退化路径，可能有坑
onOrigin(origin)
```

手势坐标来自 `.pointerInput`，挂在**同一个 Box** 上，所以两者同源。
`hitTest(positionInRoot)` 做的就是 `local = positionInRoot - snapshot.originInRoot`。

**关键事实（已用字节码核实）**：`localPositionOf` 的实现走
`findCommonAncestor` → `toParentPosition`（其中 `OwnedLayer.mapOffset` **会**把
`graphicsLayer` 的 scale 算进去）→ `ancestorToLocal`。也就是说
**坐标换算本身把缩放算进去了**，缩放在理论上不会破坏同源性。

### 渲染路径

- **主界面**的消息列表：`ChatMessageView.kt` 的 `NodeChatContent`（`:153`）。
- **屏空间小窗**：`MainActivity.kt:1048` → `FloatingWindowCard` → `content()` → `NodeChatContent`。
- **图空间小窗**：`TreeTopologyView.kt:1256` → `NodeChatContentCompact` → `NodeChatContent`。
  → **三种界面共用同一套选词代码，不存在"小窗专用"的第二套实现。**
  所以"小窗才选不中"不是组件不同，而是**坐标系/时序在小窗里更容易踩到某个坑**。

---

## 五、下一步应该查什么（按怀疑度排序）

以下是我**尚未验证**的方向，按价值排序。请优先看 P1 和 P2。

### P1（最高）表格格子的坐标从不进入 `blockCoords`，`syncLiveGeometry` 对表格完全无效

`SelectableMarkdown.kt:450` 的 `syncLiveGeometry()` 只遍历 `blockCoords`，
而 `blockCoords[index]` 只在非表格块的 `onCoordinates` 回调里写入
（`SelectableMarkdown.kt:1020` 附近的 `onCoordinates = { coords -> blockCoords[index] = coords; ... }`）。

**`SelectableBlock` 的 TABLE 分支只往下传 `onCellOrigin` / `onCellLayout`，
从不传 `onCoordinates` / `onOrigin` / `onLayout`**（见 `:1264` 附近的 `TableBlockView(...)` 调用）。
后果：表格的坐标刷新**只有** `TableGridCell` 自己的 `onGloballyPositioned` 这一条路，
一旦小窗移动/缩放/重组而该回调没重发，格子坐标就停在旧值上。

**建议**：给 `TableBlockView` / `TableGridCell` 增加 `onCellCoordinates(cellIndex, LayoutCoordinates)`
回调，把活句柄也收集起来，让 `syncLiveGeometry()` 一并刷新格子坐标。
这是我认为**最可能真正修好表格**的一步。

### P2（高）光标：把"边沿触发"补成"持续断言"

我已用字节码核实 `PointerHoverIconModifierNode.onPointerEvent`（Compose 1.6.8）的真实逻辑：

| 事件 | 行为 |
|---|---|
| `Enter` | `cursorInBoundsOfNode = true` → `displayIconIfDescendantsDoNotHavePriority()` |
| `Exit` | `cursorInBoundsOfNode = false` → `displayIconFromAncestorNodeWithCursorInBoundsOrDefaultIcon()` |
| **其它（Move/Press/Scroll…）** | **直接 `return`，什么都不做** |

而 Windows 的 `WM_SETCURSOR` 是**每次鼠标移动都重发**、macOS 的 `cursorUpdate(with:)`
是在 tracking area 内**反复回调**——桌面系统是"每个鼠标事件重新断言一次光标"，
Compose 是"**边沿触发 + 一个布尔状态**"。节点被列表复用/流式重组换成新实例后，
指针没动 → 系统不补发 `Enter` → `cursorInBoundsOfNode` 恒为 `false` → 再移动也 `return`，
光标就停在祖先值/默认箭头上了。**这与"选不中词"是同一个状态失效的两个投影。**

**这一段结论是我用 `javap` 反汇编本项目实际依赖的 Compose AAR 得到的**（不是凭记忆）。
如果你要复核，AAR 解出的 `classes.jar` 在 `/tmp/picon2/`（临时目录，可能已不存在，
可用 `find ~/.gradle -name "ui-*.aar"` 重新定位后 `unzip classes.jar`）：

```bash
# 验证"只处理 Enter/Exit"（输出应为 2，且不见 getMove）
javap -p -c -cp classes.jar androidx.compose.ui.input.pointer.PointerHoverIconModifierNode \
  | sed -n '/onPointerEvent/,/^$/p' | grep -cE "getEnter|getExit"
```

**官方留了命令式的另一半**（已在你这份依赖里核实）：

```
PointerIconService 是 public interface:
    PointerIcon getIcon();
    void setIcon(PointerIcon);
CompositionLocalsKt.getLocalPointerIconService()      // 从 composition 取
AndroidComposeView$pointerIconService$1.setIcon
  → AndroidComposeViewVerificationHelperMethodsN.setPointerIcon(View, PointerIcon)
  → View.setPointerIcon(...)                          // 与 pointerHoverIcon 同一个原语
```

⚠️ **1.6.8 的 `setIcon` 只有一个参数，没有 `isHovering`。**
可用常量：`PointerIcon.Default` / `.Crosshair` / `.Text` / `.Hand`。

**做法**：写一个内部修饰符 `Modifier.desktopHoverCursor(icon)`，在 `pointerInput` 里对
`PointerEventType.Move` / `Enter` 调 `setIcon(icon)`，`Exit` 时 `setIcon(PointerIcon.Default)` 交还。
**同时必须把文本子树上原有的 `pointerHoverIcon` 摘掉**（正文 `:395`、`ChatMessageView:976`、
`AdaptiveDocumentView:1249`、`LiveTextComposeOverlay:205`、`LiveTextPageView:576`），
否则命令式与框架节点会在同一事件流里互相覆盖，结果取决于 pass 顺序（文档未写明优先级），
可能出现闪烁或反过来盖掉手型。

**保留不动**的：`FloatingWindowCard:498/533/566`（Crosshair）、`:662`（Hand）、
`TreeTopologyView:763`（Hand/Default）——这些与文本无关，语义不同层。

`inputMode == FORCE_TOUCH` 时应整体不挂（触屏没有悬停光标）。

### P3 小窗的 `graphicsLayer` 缩放时序

`FloatingWindowCard.kt:406`：窗口内容根挂着

```kotlin
.graphicsLayer {
    scaleX = growScale; scaleY = growScale
    alpha = growAlpha
    transformOrigin = TransformOrigin(0.08f, 0f)
}
```

`growScale`（`:376`）由 `appeared` 驱动，从 `0.80f` 弹到 `1f`。
`appeared` 在 `:271` 的 `LaunchedEffect(windowItem.id) { appeared = true }` 置真。

坐标换算理论上会把 scale 算进去（见第四节），稳态 `scale = 1f` 时更不该有问题。
**但这只是理论**，而用户的实测否定了理论。值得确认的是：
`growScale` 是否真的收敛到 `1f`（`animateFloatAsState` 的 spring 参数
`dampingRatio = 0.48f` 是**欠阻尼**的，理论上会过冲——虽然终值仍是 1f）；
以及小窗**被拖动/缩放**时，`onGloballyPositioned` 是否确实为每个 Text 重发。
建议直接在小窗里加一次性日志（块 origin + `growScale` + 指针坐标），跑一次就能定论。

### P4 `positionInParent()` 退化路径

`SelectableMarkdown.kt:1239`：`rootCoordinates` 为 null 或未 attach 时退回
`coords.positionInParent()`。**这两者的坐标系原点不同**（一个是 `SelectableMarkdownText`
的外层 Box，一个是直接父节点）。如果小窗里出现"首帧 root 还没设置好就把 origin 算完"
的窗口期，就会出现**整体偏移且不再自愈**（因为去重判据是"origin 是否相等"，一旦后续
回调也走了同一条退化路径，就永远相等、永远不重建）。
建议：**删掉这个退化路径**，宁可本次不登记、等真实的 `rootCoordinates` 到位后再登记。

### P5 表格 `hitCellExact` 的遮挡顺序

`SelectionModel.kt:211` 按 `cells` 的阅读顺序返回**第一个**命中格。表格若横向滚动
（`TableBlockView` 的每行都有 `horizontalScroll`），被滚出可视区、被裁剪的格子其
`localPositionOf` 仍会给出容器内的逻辑坐标。请确认裁剪区外的格子不会"截胡"命中。

### P6 务必保留的排查工具

`SelectableMarkdown.kt:212` 的 `diagnose()` 会把判定结果同时写进控制器与上层 UI，
用户在设置里打开 **「排查用：显示文本选择诊断」** 后，长按会显示形如：

```
长按成功 → 块3 偏移42（块=8 布局表=8 位置表=8 解析块=8）
长按成功但命中为空（块=0 布局表=0 位置表=8 解析块=8）
```

**这几个数字能直接区分故障类别**——
`块=0 且布局表=0` → `onTextLayout` 没回调；
`块=0 且位置表=0` → `onGloballyPositioned` 没回调；
`块>0 却命中为空` → 坐标空间不一致（**小窗最可能是这一种**）。

⚠️ 用户已经对"再让我复现/再让我看诊断"表现出明显不耐烦，
但这个数字是**唯一能把猜测变成结论**的手段。如果要用，请一次问清、别反复打扰。

---

## 六、给接手的你：几条血的教训

1. **用户明确要求过"不要测，包给我"，后来也说过"别在模拟器看，想办法用代码解决"。**
   但本项目的教训恰恰是：**我在模拟器上验证过的两个修复（表格长按选中、中文句子按位置命中）
   在用户设备上依然失败**。模拟器（1080×2400 / density 420 / Android 13，手机竖屏）
   与用户的**横屏平板（2434×1722）**差异极大——最直接的一点：
   用户平板够宽，那段文字**只占一行**，所有"折行相关的修复"在他那里根本不会被触发。
   **不要用模拟器的通过来推断用户设备会通过。**

2. **不要相信注释里的"根因"。** 这个仓库里有多处注释斩钉截铁地宣称找到了根因，
   其中一部分已被实测证伪。注释是用来说明**当时的推理**的，不是事实。自己也别再写这种断言。

3. **不要一次改太多。** 前几轮我每轮塞进 6~8 个"修复"，用户无法归因，
   我也无法定位——因为测出来"还是坏的"时，不知道是 8 个里哪个无效、
   还是互相干扰。**一次一个机制，改完给包、等反馈。**

4. **加 `versionCode`。** 理由见第二节。

5. **小窗相关的改动要格外小心。** 窗口层在 `MainActivity` 根 Box 上、
   被 `graphicsLayer` 缩放过、还有生长动画，是坐标最容易出问题的地方；
   而且用户报告"小窗才选不中"，这里就是主战场。

---

## 七、交接时的产物状态

- 源码：`app/src/` 下所有改动已落盘，**最新源文件 22:20:23**。
- APK（`versionCode=39` / `1.38.1`，41,168,184 字节，签名已验证）：
  - `app/build/outputs/apk/full/release/app-full-release.apk`（22:22:01）
  - `app/build/outputs/apk/lite/release/app-lite-release.apk`（22:22:58）
  - **两个包都晚于所有源文件**，即包含本文档第三节列出的全部改动。
- 模拟器 `emulator-5554` 上已装 1.38.1；数据库中我注入过一条测试消息
  （node `3990fa09-9c63-425a-8f43-f85849a76bf7`，含「基本内核」段落 + 2 列 markdown 表格），
  如需清理请自行删除。**该测试数据只影响模拟器，不影响用户设备。**
