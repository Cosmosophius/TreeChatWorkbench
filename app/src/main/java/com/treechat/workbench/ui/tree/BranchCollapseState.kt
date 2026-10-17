package com.treechat.workbench.ui.tree

import androidx.compose.runtime.mutableStateMapOf

/**
 * 分支折叠状态的**进程内**持有者（退出即忘）。
 *
 * 与旧实现 `CardActionController.collapsedNodeIds` 语义对齐：
 * 旧实现是一个 `static final Set<String>`（`Collections.synchronizedSet`），
 * 只活在内存里、不落库、进程退出即清空。这里保持**完全相同的语义** ——
 * 不加 Room 列、不加迁移（见数据层约束）。
 *
 * 为什么写成顶层 object，而不是 `remember` 在画布内部：
 * 旧实现是 JVM 静态字段，跨"切到别的视图再切回来"依然有效；object 能复刻这一点。
 * 内部用 Compose 的 snapshot state map，折叠状态变化会驱动画布重组。
 *
 * `ids` 在组合期被读取时会建立订阅，因此折叠/展开后画布会自动重算可见集。
 */
object BranchCollapseState {

    private val collapsed = mutableStateMapOf<String, Unit>()

    /** 已折叠节点 id 的快照（组合期读取它即可订阅折叠变化）。 */
    val ids: Set<String> get() = collapsed.keys.toSet()

    fun isCollapsed(nodeId: String): Boolean = collapsed.containsKey(nodeId)

    /**
     * 切换折叠状态。
     *
     * @return 切换**之后**是否处于折叠态。
     *   与旧实现 `toggleCollapse` 的文案分支一一对应：
     *   收起 → "已收起该卡片的所有下级分支"；展开 → "已展开下级分支卡片"。
     */
    fun toggle(nodeId: String): Boolean {
        return if (collapsed.remove(nodeId) != null) {
            false
        } else {
            collapsed[nodeId] = Unit
            true
        }
    }

    fun setCollapsed(nodeId: String, value: Boolean) {
        if (value) collapsed[nodeId] = Unit else collapsed.remove(nodeId)
    }
}
