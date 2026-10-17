package com.treechat.workbench.data.prefs

/**
 * 单个节点在画布内容坐标系里的位置与尺寸（单位 px）。
 *
 * 尺寸为 0 表示"没有自定义尺寸，用默认值" —— 不虚构一个具体数值，
 * 免得把默认尺寸写死进库、之后改默认值时旧数据不跟随。
 */
data class GraphNodeLayout(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

/**
 * 布局快照：位置与尺寸**成对**存放，用下标一一对应。
 *
 * 为什么用扁平数组而不是嵌套对象：
 * 下标对应关系一眼可见，JSON 层数更少，Gson 在 R8 之后的反射路径也更短
 * （本项目坑 1 就是 Gson 反射失效）。
 *
 * 为什么校验这么严：这份 JSON 可能来自旧版本或被截断。长度对不上时整份丢弃
 * （回落到自动布局），而不是把错位的数据当作坐标使用 —— 错位的后果是卡片
 * 飞出屏幕外，比"回到自动布局"糟糕得多。NaN/Inf 会让布局阶段直接崩，必须挡掉。
 */
data class GraphLayoutSnapshot(
    val ids: List<String>? = null,
    val xs: List<Float>? = null,
    val ys: List<Float>? = null,
    /** 与 ids 同长；某节点没记录过尺寸时填 0 表示"用默认值"。 */
    val ws: List<Float>? = null,
    val hs: List<Float>? = null
) {
    fun isUsable(): Boolean {
        val idList = ids ?: return false
        val xList = xs ?: return false
        val yList = ys ?: return false
        if (idList.isEmpty()) return false
        if (xList.size != idList.size || yList.size != idList.size) return false
        val wList = ws
        val hList = hs
        if (wList != null && wList.size != idList.size) return false
        if (hList != null && hList.size != idList.size) return false
        // 位与尺寸**四个数组全部**要求有限值，不只是 x/y。
        // 尺寸同样会参与命中测试与绘制（right = left + width），
        // 一个 NaN 会让卡片永远命不中；w/h 缺省用 0 表示"用默认尺寸"
        // （见 GraphNodeLayout 文档），所以这里只需挡 NaN/Inf。
        if (!xList.indices.all { i -> xList[i].isFinite() && yList[i].isFinite() }) return false
        if (wList != null && !wList.indices.all { i -> wList[i].isFinite() }) return false
        if (hList != null && !hList.indices.all { i -> hList[i].isFinite() }) return false
        return true
    }

    /** 转成 节点id -> 布局 的映射，便于调用方直接使用。 */
    fun toLayouts(): Map<String, GraphNodeLayout> {
        val idList = ids ?: return emptyMap()
        val xList = xs ?: return emptyMap()
        val yList = ys ?: return emptyMap()
        val wList = ws
        val hList = hs
        val out = mutableMapOf<String, GraphNodeLayout>()
        for (i in idList.indices) {
            val id = idList[i].takeIf { it.isNotBlank() } ?: continue
            out[id] = GraphNodeLayout(
                x = xList[i],
                y = yList[i],
                width = wList?.getOrNull(i) ?: 0f,
                height = hList?.getOrNull(i) ?: 0f
            )
        }
        return out
    }

    companion object {
        /** 从"节点 id -> 布局"构造快照，用于保存。 */
        fun from(layouts: Map<String, GraphNodeLayout>): GraphLayoutSnapshot {
            val ids = layouts.keys.toList()
            return GraphLayoutSnapshot(
                ids = ids,
                xs = ids.map { layouts.getValue(it).x },
                ys = ids.map { layouts.getValue(it).y },
                ws = ids.map { layouts.getValue(it).width },
                hs = ids.map { layouts.getValue(it).height }
            )
        }

        /**
         * 把读盘得到的任意数值安全转成 Float。
         *
         * ## 为什么必须要这一层：R8 会抹掉字段的泛型签名
         *
         * 本类的 `xs/ys/ws/hs` 声明为 `List<Float>?`。debug 包一切正常，
         * 但 release 包（`isMinifyEnabled = true`）里这些字段会退化为**裸 `List`**。
         * 这一点已从 APK 的 DEX 直接取证确认：
         *   - 混淆后类 `Lz2/a;` 的 5 个字段类型全是 `Ljava/util/List;`；
         *   - 整个 DEX 的 `dalvik.annotation.Signature` 注解数量为 **0**；
         *   - proguard-rules.pro 里只 `-keep` 了
         *     `network.protocol.** / network.search.** / data.model.**`，
         *     而本类在 `data.prefs.**` —— **不在任何 keep 名单内**。
         *
         * Gson 拿不到 `Float` 类型参数时只能走 Object 分支，JSON 里的坐标数字
         * 被解析成 **`java.lang.Double`**，赋给 `Float` 字段即抛：
         *     java.lang.ClassCastException: java.lang.Double cannot be cast to java.lang.Float
         * 这不是推测，是**已发生的线上闪退**（"动完卡片位置后切回对话"触发
         * 恢复布局的 LaunchedEffect，见 `TreeTopologyView.kt` 里 `layoutStore.load` 那段）。
         *
         * 离线对照实测（Gson 2.11.0，脚本 `TestGsonObjectAdapter.java`）：
         *   裸 List      -> java.lang.Double    ← 与崩溃堆栈逐字一致
         *   List<Number> -> LazilyParsedNumber  ← 异常文本会是它，不符，故可排除
         *   List<Double> -> java.lang.Double
         *   List<Float>  -> java.lang.Float     ← 仅在签名保留时
         *
         * 所以这里**不能靠强转**，必须按数值语义转换。
         * `LazilyParsedNumber` 也继承自 `Number`，同样被这条分支接住。
         */
        fun numberToFloat(value: Any?): Float? = when (value) {
            null -> null
            is Float -> value.takeIf { it.isFinite() }
            is Number -> value.toFloat().takeIf { it.isFinite() }
            // 字符串形态（例如旧版本或被手工改过的 JSON 写成 "123.5"）也尽量救回来
            is String -> value.trim().toFloatOrNull()?.takeIf { it.isFinite() }
            else -> null
        }

        /**
         * 把 JSON 数组里的元素逐个转成 Float，挡掉不可解析与 NaN/Inf。
         *
         * 单项坏数据只丢它自己（返回 null 表示这一项不可用），
         * 由调用方决定怎么对齐下标 —— 不让一份坏数据把整张图的布局作废。
         * NaN/Inf 必须挡掉：它们会让布局阶段直接崩（见类文档说明）。
         */
        fun coerceFloats(raw: List<*>?): List<Float?>? =
            raw?.map { numberToFloat(it) }
    }
}
