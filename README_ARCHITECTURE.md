# TreeChat: 纯原生 Android 树状嵌套 AI 对话工作台

> 对标 **FlClash** 交互与动效质感的 100% 纯 Kotlin + Jetpack Compose 原生应用。  
> 核心哲学：**“随时摘取、就地开窗、递归裂变、互不干扰”**。

---

## 一、 系统架构总览

```
TreeChatWorkbench/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/treechat/workbench/
│   │       ├── MainActivity.kt                # 主界面入口与视图模式切换 (AnimatedContent)
│   │       ├── data/
│   │       │   ├── model/
│   │       │   │   ├── ConversationNode.kt    # 树节点实体 (parentId 自引用, summary)
│   │       │   │   └── ChatMessage.kt         # 对话消息实体 (所属节点, 流式标记)
│   │       │   └── db/
│   │       │       ├── ConversationDao.kt     # 树状递归 CTE 查询 (无污染回溯至 Root)
│   │       │       └── AppDatabase.kt         # Room 单例数据库
│   │       ├── network/
│   │       │   ├── protocol/
│   │       │   │   ├── AiModelConfig.kt       # DeepSeek / MIMO / Anthropic / 自定义模型
│   │       │   │   └── PromptMessage.kt       # 规范化消息 DTO
│   │       │   └── client/
│   │       │       └── AiStreamClient.kt      # OkHttp + SSE 双协议流式接收引擎
│   │       └── ui/
│   │           ├── theme/
│   │           │   └── Theme.kt               # 极简暗黑 (#161618), 霓虹绿 (#28C840), 冰蓝
│   │           ├── window/
│   │           │   ├── FloatingWindowManager.kt # 单例拖拽控制器, 碰谁谁置顶, 停靠坞治理
│   │           │   ├── FloatingWindowCard.kt    # 物理弹簧动效, macOS三点控件, 溯源卡片, Resize
│   │           │   └── WindowDockBar.kt         # 屏幕左右停靠坞 (大头钉胶囊收纳)
│   │           ├── chat/
│   │           │   ├── ChatNodeViewModel.kt   # 上下文链拼接, Prompt Caching, 节点分叉
│   │           │   └── ChatMessageView.kt     # 长按选中 -> 「针对此段提问」胶囊 -> 逐字流式脉冲
│   │           └── tree/
│   │               └── TreeTopologyView.kt    # Canvas 贝塞尔曲线绘制多维思维树拓扑
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 二、 三大金科玉律在代码中的完整落实

### 1. 单例拖拽控制器（Single Drag Controller）
- **实现位置**：`FloatingWindowManager.kt`
- **机制**：
  - 维护唯一全局变量 `activeDraggingWindowId: String?`。
  - 用户按下窗口标题栏时，调用 `startDrag(windowId)` 独占当前手势锁。
  - 拖拽事件 `detectDragGestures` 仅派发给被锁定的窗口位移，其余窗口完全不响应。
  - 抬手时 `endDrag()` 清空锁。绝不分散全局监听，彻底杜绝串动。

### 2. 彻底阻断事件冒泡（Stop Propagation）
- **实现位置**：`FloatingWindowCard.kt`
- **机制**：
  - macOS 三点控件（红/黄/绿）显式采用 `pointerInput` 进行 `event.changes.forEach { it.consume() }`。
  - 内容区域的文本选择与滚动组件独立消费事件，绝不穿透至标题栏拖拽逻辑。

### 3. 数据层父子继承指针（Parent ID & Tree Structure）
- **实现位置**：`ConversationNode.kt` & `ConversationDao.kt`
- **机制**：
  - `ConversationNode` 包含 `parentId: String?` 指向母节点，以及 `originQuote` 记录引用的上级长按文字。
  - `ConversationDao.getAncestorChain` 使用 SQLite **`WITH RECURSIVE AncestorTree AS (...)`** CTE 查询，一次性提取从当前节点到 Root 节点的精准上溯链路。
  - 发起 AI 请求时，严格按 `Root -> Child` 前缀排列（天然契合 DeepSeek / OpenAI 的 Prompt Caching），且绝不带入兄弟分叉节点的无关数据。

---

## 三、 空间管理与动效规范（对标 FlClash）

1. **碰谁谁置顶**：
   - 窗口外层使用 `pointerInput(PointerEventType.Press)` 监听，在被摸到的瞬间立即将该窗口 `zIndex` 提升为全局最新最大值。
2. **全屏就地形变（向上走）**：
   - 点击绿色圆点，窗口利用 Compose 物理弹簧动效 `spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)` 就地铺满整个屏幕；再次点击无缝弹性缩回。
3. **停靠坞收纳（向侧走）**：
   - 点击黄色圆点，窗口平滑隐去并在屏幕侧边收纳成精致大头钉胶囊。
   - 右侧优先容纳 10 个，排满自动流向左侧（最多 10 个），点击胶囊原貌满血恢复。
4. **边缘与右下角拉伸**：
   - 右下角配备触控 Handle，支持自由调节窗口宽度（240dp ~ 600dp）与高度（280dp ~ 800dp）。
5. **拓扑思维树**：
   - 提供独立 Canvas 树状图模式，通过贝塞尔曲线动态绘制节点连线，支持点击任意节点直达其对应小窗。

---

## 四、 AI 模型双协议与流式输出支持

- **OpenAI 兼容协议**：`/v1/chat/completions`，支持 DeepSeek-V3 (`deepseek-chat`)、DeepSeek-R1 (`deepseek-reasoner`)、MIMO (`mimo-v2.5-pro`) 等。
- **Anthropic 协议**：`/v1/messages`，支持 `claude-3-5-sonnet` 等。
- **OkHttp SSE 原生推送**：底层建立高效 EventSource 连接，通过 Kotlin Flow 逐字推送至界面与 Room 本地数据库，配合呼吸灯脉冲显示生成状态。
