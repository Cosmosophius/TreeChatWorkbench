package com.treechat.workbench.ui.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 视觉系带（Visual Tether）的全局状态。
 *
 * ## 它解决什么
 * 多小窗层层递归后，用户在小窗里读到一半，往往已经"忘了这扇窗是从哪一句话长出来的"，
 * 底层的母视口也不再提示那个来源。这里记录"当前被激活的小窗对应哪个节点 / 哪条来源消息"，
 * 并给出一个自增的 [pulseToken]，让底层视口能据此**短暂高亮闪烁**那个来源
 * （树画布上高亮对应卡片边框；消息列表里高亮对应那条消息）。
 *
 * ## 为什么是顶层 object，而不是塞进 FloatingWindowManager
 * 与 [BranchCollapseState] 同理：系带是"跨视图、跨组合"的瞬时 UI 信号，
 * 且需要被**树画布**与**对话列表**两个彼此无关的渲染者同时订阅。
 * 放进某个 Compose 树里就订阅不到了。
 *
 * ## 为什么用 pulseToken 而不是布尔开关
 * "高亮闪烁"是一个**一次性事件**，而布尔量只能表达"现在是否高亮"的持续状态。
 * 用单调自增的令牌，订阅方以 token 变化为 LaunchedEffect 的 key，
 * 每次激活都能重新触发一次闪烁预算，且天然不会因重组而重复触发。
 */
object WindowTetherState {

    /** 当前被激活的小窗对应的节点 id（null = 尚无激活窗）。 */
    var activeNodeId by mutableStateOf<String?>(null)
        private set

    /**
     * 该小窗的来源消息 id（ConversationNode.sourceMessageId，可能为空）。
     *
     * 供**对话列表**侧定位：在父节点的消息里找到这一条并短暂高亮。
     * 为 null 时列表侧退化为"不闪烁"（旧数据 / 整段分叉没有来源消息）。
     */
    var sourceMessageId by mutableStateOf<String?>(null)
        private set

    /** 一次性脉冲令牌：每次"激活的目标发生变化"时 +1。 */
    var pulseToken by mutableIntStateOf(0)
        private set

    /**
     * 激活某扇小窗（由 [FloatingWindowManager.bringToFront] 统一调用）。
     *
     * 只在**目标节点发生变化**时递增令牌：同一扇窗被反复置顶（例如拖动时
     * 初始阶段每次按下都会 bringToFront）不该每次都闪，否则会连续闪烁成噪声。
     * 但来源消息总是同步刷新 —— 它可能在同一节点上被更新。
     */
    fun activate(nodeId: String, sourceMessageId: String?) {
        this.sourceMessageId = sourceMessageId
        if (nodeId == activeNodeId) return
        activeNodeId = nodeId
        pulseToken++
    }
}
