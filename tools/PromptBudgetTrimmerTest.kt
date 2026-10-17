// 上下文预算裁剪的真实用例（在 JVM 上直接跑生产代码，不依赖 Android）
//
// 用法（见 docs/ 或交接说明）：
//   java -cp kotlin-compiler-embeddable.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
//        -include-runtime -d /tmp/t.jar <PromptMessage.kt> <PromptContextBudget.kt> <本文件>
//   java -jar /tmp/t.jar
import com.treechat.workbench.network.protocol.PromptMessage
import com.treechat.workbench.ui.chat.trimPromptListByTurns
import com.treechat.workbench.ui.chat.injectKnowledgeIntoLastUser

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("  ✓ $name")
    } else {
        failures++
        println("  ✗ $name  $detail")
    }
}

private fun u(text: String) = PromptMessage(role = "user", content = text)
private fun a(text: String) = PromptMessage(role = "assistant", content = text)
private fun s(text: String) = PromptMessage(role = "system", content = text)

/**
 * 造一段对话。默认结尾再补一条**用户提问**——这是生产的真实形态：
 * 当前这一轮的问题已入库，而它的回答还没生成。
 * [endWithAssistant] 为真时以 assistant 结尾（树状分叉里存在这种历史）。
 */
private fun buildConversation(
    preamble: Int,
    turns: Int,
    chars: Int,
    endWithAssistant: Boolean = false
): List<PromptMessage> {
    val out = mutableListOf<PromptMessage>()
    repeat(preamble) { out.add(s("系统提示${it}:".padEnd(chars, 'x'))) }
    repeat(turns) { i ->
        out.add(u("第${i}轮提问:".padEnd(chars, 'q')))
        out.add(a("第${i}轮回答:".padEnd(chars, 'a')))
    }
    if (!endWithAssistant) out.add(u("当前提问:".padEnd(chars, 'Q')))
    return out
}

/** 结果必须是输入的**子序列**且顺序一致（不能凭空造消息或颠倒顺序）。 */
private fun isOrderedSubsequence(result: List<PromptMessage>, input: List<PromptMessage>): Boolean {
    var i = 0
    for (m in input) {
        if (i < result.size && result[i] === m) i++
    }
    return i == result.size
}

private fun cost(list: List<PromptMessage>) = list.sumOf { it.content.length + 16 }

/** 结果是否"不含半轮"：除首条外，每个非 user 消息前面必须能追溯到同轮的 user。 */
private fun noPartialTurns(result: List<PromptMessage>): Boolean {
    var seenUserInCurrentTurn = false
    for (m in result) {
        if (m.role == "user") {
            seenUserInCurrentTurn = true
        } else if (m.role == "assistant") {
            // 不允许出现"没有 user 的 assistant"
            if (!seenUserInCurrentTurn) return false
        }
        // system（序言）不计入轮次判定
    }
    return true
}

fun main() {
    val budget = 24_000  // 与 CLOUD_CONTEXT_CHAR_BUDGET 一致

    println("== 用例 1：短对话（未超预算）应原样保留 ==")
    run {
        val list = buildConversation(preamble = 1, turns = 5, chars = 100)
        val out = trimPromptListByTurns(list, budget)
        check("条数不变", out.size == list.size, "in=${list.size} out=${out.size}")
        check("顺序不变", out == list)
    }

    println("== 用例 2：长对话（大幅超预算）应按整轮截断 ==")
    run {
        val list = buildConversation(preamble = 2, turns = 120, chars = 400)
        val out = trimPromptListByTurns(list, budget)
        check("确实被裁剪了", out.size < list.size, "in=${list.size} out=${out.size}")
        check("不超过预算", cost(out) <= budget, "cost=${cost(out)}")
        check("无半轮", noPartialTurns(out))
        check("末尾与输入末尾一致（最新消息永不丢）", out.last() === list.last(), out.last().role)
        check("结果是输入的有序子序列", isOrderedSubsequence(out, list))
        val keptTurns = out.count { it.role == "user" }
        println("    保留 $keptTurns 轮 / 共 120 轮，字符 ${cost(out)} / 预算 $budget")
    }

    println("== 用例 3：单轮超大（超过整个预算）仍必须保留该轮 ==")
    run {
        val list = mutableListOf<PromptMessage>()
        repeat(3) { list.add(s("序言$it".padEnd(200, 'x'))) }
        repeat(5) { list.add(u("小问题$it".padEnd(200, 'q'))); list.add(a("小回答$it".padEnd(200, 'a'))) }
        list.add(u("超大提问:".padEnd(30_000, 'Q')))   // 单轮就超预算
        val out = trimPromptListByTurns(list, budget)
        check("超大轮被保留", out.last().role == "user" && out.last().content.length > 20_000)
        check("只保留这一轮（序言与小轮都被挤出）", out.size == 1, "out=${out.size}")
        check("无半轮", noPartialTurns(out))
    }

    println("== 用例 4：边界稳定性——逐轮增长时，保留窗口只按整轮滑动 ==")
    run {
        // 同一段对话，从 60 轮加到 62 轮：每次保留结果都必须是"整轮对齐"的
        var prevKeptTurnCount = -1
        var monotone = true
        for (turns in 60..62) {
            val list = buildConversation(preamble = 2, turns = turns, chars = 400)
            val out = trimPromptListByTurns(list, budget)
            check("[$turns 轮] 无半轮", noPartialTurns(out))
            check("[$turns 轮] 末尾与输入末尾一致", out.last() === list.last())
            val kept = out.count { it.role == "user" }
            if (prevKeptTurnCount >= 0 && kept < prevKeptTurnCount) monotone = false
            println("    $turns 轮 → 保留 $kept 轮")
            prevKeptTurnCount = kept
        }
        check("保留轮数随对话增长不减少（单调）", monotone)
    }

    println("== 用例 5：轮次被 content 长度影响时也不切出半轮 ==")
    run {
        // 故意让各轮长度差异极大：旧实现会切在 user/assistant 之间
        val list = mutableListOf<PromptMessage>()
        list.add(s("system".padEnd(300, 's')))
        repeat(20) { i ->
            val len = if (i % 3 == 0) 5_000 else 120
            list.add(u("轮$i 问:".padEnd(len, 'q')))
            list.add(a("轮$i 答:".padEnd(len, 'a')))
        }
        val out = trimPromptListByTurns(list, budget)
        check("无半轮", noPartialTurns(out))
        check("末尾与输入末尾一致", out.last() === list.last())
        check("不超过预算", cost(out) <= budget, "cost=${cost(out)}")
        check("结果是输入的有序子序列", isOrderedSubsequence(out, list))
        println("    保留 ${out.count { it.role == "user" }} 轮，字符 ${cost(out)}")
    }

    println("== 用例 7：以 assistant 结尾的历史（树状分叉）也不切出半轮 ==")
    run {
        val list = buildConversation(preamble = 2, turns = 80, chars = 400, endWithAssistant = true)
        val out = trimPromptListByTurns(list, budget)
        check("无半轮", noPartialTurns(out))
        check("末尾与输入末尾一致", out.last() === list.last(), out.last().role)
        check("结果是输入的有序子序列", isOrderedSubsequence(out, list))
        check("不超过预算", cost(out) <= budget, "cost=${cost(out)}")
    }

    println("== 用例 6：空输入与全 system 输入不崩 ==")
    run {
        check("空列表返回空", trimPromptListByTurns(emptyList(), budget).isEmpty())
        val onlySystem = listOf(s("a"), s("b"))
        val out = trimPromptListByTurns(onlySystem, budget)
        check("全 system 保留为序言", out.size == 2, "out=${out.size}")
        val tiny = trimPromptListByTurns(onlySystem, 1)
        check("预算为 1 时不抛异常（可能清空）", tiny.size <= 2, "out=${tiny.size}")
    }

    println("== 用例 8：RAG 知识注入落在【最后一条 user】且前面逐字节不变（改动 1） ==")
    run {
        val preambleCount = 2
        val turns = 4
        val list = buildConversation(preamble = preambleCount, turns = turns, chars = 100)
        val knowledge = "【教材检索结果】\n[1] 细胞膜由磷脂双分子层构成。"

        val out = injectKnowledgeIntoLastUser(list, knowledge)
        check("条数不变（不新增消息）", out.size == list.size, "in=${list.size} out=${out.size}")
        check("知识落在最后一条消息里", out.last().content.contains("磷脂双分子层"))
        check("最后一条仍是 user", out.last().role == "user", out.last().role)
        // 关键：它前面的每一条都**逐字节未变**（这正是前缀缓存能命中的前提）
        val prefixUnchanged = out.dropLast(1).zip(list.dropLast(1)).all { (o, i) ->
            o.role == i.role && o.content == i.content
        }
        check("其余消息逐字节未变", prefixUnchanged)
        // 只有末尾那条变了，且是"追加"（原文完整保留）
        check("末尾是追加而非替换", out.last().content.startsWith(list.last().content))
        // 知识不在 system prompt 里（system 侧的稳定性由 ViewModel 的 effectivePrompt 保证）
        check("前两条 system 未被塞入知识", out.take(preambleCount).none { it.content.contains("磷脂") })
    }

    println("== 用例 9：知识为空时列表必须逐字节不变（改动 1 的零副作用路径） ==")
    run {
        val list = buildConversation(preamble = 2, turns = 6, chars = 80)
        val out = injectKnowledgeIntoLastUser(list, "")
        check("空白知识 → 原样返回同一对象", out === list)
        val out2 = injectKnowledgeIntoLastUser(list, "   \n  ")
        check("纯空白也原样返回", out2 === list)
    }

    println("== 用例 10：异常路径（没有 user 消息）不丢知识 ==")
    run {
        val onlySystem = listOf(s("sys1"), s("sys2"))
        val out = injectKnowledgeIntoLastUser(onlySystem, "知识X")
        check("知识被追加为 system", out.last().role == "system" && out.last().content == "知识X")
        check("原有消息保留", out.size == 3)
    }

    println()
    if (failures == 0) {
        println("全部用例通过")
    } else {
        println("有 $failures 项失败")
        kotlin.system.exitProcess(1)
    }
}
