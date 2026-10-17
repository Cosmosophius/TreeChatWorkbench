package com.treechat.workbench.ui.chat

import com.treechat.workbench.network.protocol.PromptMessage

/**
 * 上下文预算裁剪：**按完整轮次**从尾部保留。
 *
 * ## 为什么单独放在这里（而不是留在 ViewModel 里）
 * 这是纯逻辑：输入一串消息与一个字符预算，输出保留哪一段，不碰数据库、
 * 不碰 Android、不依赖 ViewModel 的任何状态。放在顶层函数里就能用 JVM 直接
 * 跑真实用例（见 `tools/PromptBudgetTrimmerTest.kt`），不必为了验证一段
 * 预算算术去起模拟器、配 API 密钥。
 *
 * ## 旧实现的问题
 * 旧版从尾部逐条累加、一旦超预算就在**任意一条消息处**断开：
 * - 可能把一轮问答切在 user 与 assistant 之间，留下"问了没答"的残轮；
 * - 保留序列的起点随内容长度浮动，截断位置不稳定。
 *
 * ## 现在的规则
 * 1. 一条 `user` 消息开启一轮，紧随其后的 `assistant` / 系统提示都归入该轮；
 *    第一条 `user` 之前的内容（system 提示、焦点原文）归入「序言」。
 * 2. 从尾部**整轮整轮**地收，装不下就停在轮次边界上。
 * 3. 最后一轮永远保留（哪怕它单独超预算）—— 否则会退化成"什么都没发"。
 * 4. 序言只要还装得下就带上：它是稳定头部，对前缀缓存有利，丢了模型
 *    也就失去了分支上下文。
 *
 * ## 诚实声明
 * 滑动窗口的**起点**不可能每轮都保持字节稳定（窗口整体后移是它的定义）。
 * 真正让前缀缓存命中的是固定头部，也就是 system prompt —— 那部分由
 * "把每轮变化的 RAG 知识注入移到消息末尾"来保证。本函数保证的是：
 * **不会切出半轮，且边界确定地落在轮次之间。**
 */
internal fun trimPromptListByTurns(
    list: List<PromptMessage>,
    budgetChars: Int
): List<PromptMessage> {
    if (list.isEmpty()) return list

    /** 一条消息的估算开销：内容长度 + 少量模板开销（够精确即可，不必真分词）。 */
    fun costOf(m: PromptMessage) = m.content.length + 16

    // 切轮次：user 开新一轮；首个 user 之前的非 user 消息进序言
    val turns = mutableListOf<MutableList<PromptMessage>>()
    val preamble = mutableListOf<PromptMessage>()
    for (m in list) {
        if (m.role == "user") {
            turns.add(mutableListOf(m))
        } else if (turns.isEmpty()) {
            preamble.add(m)
        } else {
            turns.last().add(m)
        }
    }

    val keptTurns = ArrayDeque<List<PromptMessage>>()
    var used = 0
    for (i in turns.indices.reversed()) {
        val turn = turns[i]
        val cost = turn.sumOf(::costOf)
        // 至少保住最后一轮（本次提问本身）
        if (keptTurns.isNotEmpty() && used + cost > budgetChars) break
        keptTurns.addFirst(turn)
        used += cost
    }

    val keptPreamble = mutableListOf<PromptMessage>()
    for (m in preamble) {
        val cost = costOf(m)
        if (used + cost > budgetChars) break
        keptPreamble.add(m)
        used += cost
    }

    return keptPreamble + keptTurns.flatten()
}

/**
 * 前缀缓存优化：把 RAG 知识注入**追加到最后一条 user 消息**里。
 *
 * ## 为什么必须是"最后一条 user"而不是别处
 * `knowledgeSection` 是每轮实时检索的结果，内容逐轮变化。
 * - 放进 system prompt（旧实现）→ 它位于序列最前端，一变就让其后
 *   整条祖先链的前缀缓存全部穿透失效；
 * - 放进**最后一条** user 消息 → 它位于序列最末端，前端（system + 祖先链）
 *   保持字节级稳定，缓存得以命中。
 *
 * 抽成纯函数是为了能用 JVM 直接跑真实用例（见 `tools/PromptBudgetTrimmerTest.kt`）：
 * "知识确实落在最后一条 user、且前面的消息逐字节未变"这件事是可以断言出来的，
 * 不必靠配好 API 密钥去问一次模型。
 *
 * @param knowledgeText 已经拼好的知识段；为空白时**原样返回原列表**（逐字节不变）。
 */
internal fun injectKnowledgeIntoLastUser(
    list: List<PromptMessage>,
    knowledgeText: String
): List<PromptMessage> {
    if (knowledgeText.isBlank()) return list
    val lastUserIdx = list.indexOfLast { it.role == "user" }
    if (lastUserIdx < 0) {
        // 异常路径（没有 user 消息）：退化为追加一条 system 消息，
        // 保证知识不丢，也不会让整轮对话失败。
        return list + PromptMessage(role = "system", content = knowledgeText)
    }
    val original = list[lastUserIdx]
    return list.toMutableList().also {
        it[lastUserIdx] = original.copy(content = original.content + "\n\n" + knowledgeText)
    }
}
