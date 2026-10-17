package com.treechat.workbench.ui.reader

/**
 * 工作台支持的四大核心认知面板类型
 */
enum class WorkspacePanelType(val title: String, val iconDesc: String) {
    DOCUMENT("原注文档", "PDF / EPUB 原文与实况高亮"),
    CHAT_TREE("研读对话树", "TalkTree 深度分叉思考小窗"),
    ETYMOLOGY("跨语词源", "三语历时音韵与全同义词全息"),
    NOTES("批注笔记", "条畅笔记白板与划线摘录")
}

/**
 * 工作台的宏观交互布局形态（双基础形态）
 */
enum class WorkspaceLayoutMode(val label: String) {
    /** 极简聚焦模式：单屏沉浸阅读，通过就地悬浮小窗与底部抽屉交互（经典手机手感） */
    FLOATING_MODAL("极简浮窗"),

    /** 多栏自由平铺工作台：多面板并排平铺，支持大屏/横屏/桌面级高维生产力 */
    MULTI_PANE_TILED("分栏平铺")
}

/**
 * 多方案自由自适应拼装配置实体 (WorkspacePanelsConfig)
 *
 * 用户可按需独立开启或收起任何面板：
 * - 不需要笔记时：showNotes = false，笔记栏彻底隐藏，其余面板自适应弹性铺满；
 * - 纯净阅读时：只开启 showDocument；
 * - 边读边问时：开启 showDocument + showChatTree；
 * - 探究音韵时：一键点开 showEtymology。
 */
data class WorkspacePanelsConfig(
    val layoutMode: WorkspaceLayoutMode = WorkspaceLayoutMode.FLOATING_MODAL,
    val showDocument: Boolean = true,
    val showChatTree: Boolean = true,
    val showEtymology: Boolean = false,
    val showNotes: Boolean = false
) {
    /**
     * 计算当前激活可见的面板数量
     */
    val activePanelsCount: Int
        get() = (if (showDocument) 1 else 0) +
                (if (showChatTree) 1 else 0) +
                (if (showEtymology) 1 else 0) +
                (if (showNotes) 1 else 0)

    /**
     * 切换指定面板的展开/收起状态
     */
    fun togglePanel(panel: WorkspacePanelType): WorkspacePanelsConfig {
        return when (panel) {
            WorkspacePanelType.DOCUMENT -> copy(showDocument = !showDocument)
            WorkspacePanelType.CHAT_TREE -> copy(showChatTree = !showChatTree)
            WorkspacePanelType.ETYMOLOGY -> copy(showEtymology = !showEtymology)
            WorkspacePanelType.NOTES -> copy(showNotes = !showNotes)
        }
    }

    /**
     * 切换布局模式（浮窗 ⇄ 平铺）
     */
    fun toggleLayoutMode(): WorkspacePanelsConfig {
        val nextMode = if (layoutMode == WorkspaceLayoutMode.FLOATING_MODAL) {
            WorkspaceLayoutMode.MULTI_PANE_TILED
        } else {
            WorkspaceLayoutMode.FLOATING_MODAL
        }
        return copy(layoutMode = nextMode)
    }
}
