package com.treechat.workbench.data.prefs

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * 工作台画布布局的持久化（卡片位置 + 卡片尺寸），**按会话分别保存**。
 *
 * ## 为什么必须有它
 *
 * 旧实现把 `GraphViewState.positions` / `nodeSizes` 只放在内存里的 `remember`
 * 里，进程一被杀就回到自动布局 —— 用户辛苦摆好的思维导图位置全没了。
 * 这直接使"思维导图"这个功能不成立：重启就散架的东西不算图。
 *
 * ## 为什么按会话分键
 *
 * 每个会话有自己的一棵分支树。若共用一个键，切换会话时 A 会话的坐标会被写进
 * 同一个位置，B 会话读到的就是 A 的布局，节点会跑到屏幕外或重叠。
 * 因此键名是 `graph_layout_<conversationId>`。
 *
 * ## 数据结构
 *
 * 存成两个扁平数组而不是嵌套对象：
 * ```
 * { "ids": ["n1","n2"], "xs": [...], "ys": [...], "ws": [...], "hs": [...] }
 * ```
 * 这样一眼能看出下标是一一对应的，比 `{"n1": {"x":..}}` 少一层嵌套。
 *
 * ## ⚠️ 为什么这里**不用** `gson.fromJson(json, GraphLayoutSnapshot::class.java)`
 *
 * 这是本项目一个真实闪退的根因，改回反射写法会立刻复发，务必先读完这段。
 *
 * `GraphLayoutSnapshot` 在 `data.prefs.**`，而 `proguard-rules.pro` 只 `-keep` 了
 * `network.protocol.** / network.search.** / data.model.**` —— 本包**不在名单里**。
 * release 包（`isMinifyEnabled = true`）里该类被 R8 混淆为 `z2.a`，且其字段
 * `xs/ys/ws/hs` 的泛型信息被抹成**裸 `List`**。已从 APK 的 DEX 直接取证：
 *   - `Lz2/a;` 的 5 个字段类型全是 `Ljava/util/List;`；
 *   - 整个 DEX 的 `dalvik.annotation.Signature` 注解数为 **0**。
 *
 * Gson 反射时拿不到 `Float` 类型参数，就会走 Object 分支把 JSON 数字读成
 * **`java.lang.Double`**；Kotlin 侧按 `Float` 取值时抛
 * `ClassCastException: java.lang.Double cannot be cast to java.lang.Float`。
 * 触发路径正是"动完卡片位置后切回对话"（恢复布局的 LaunchedEffect）。
 *
 * 所以这里改成**显式按字段名解析 + 逐个数值容错转换**：
 *   - 不依赖任何泛型签名，R8 怎么混淆都不影响；
 *   - 已写坏的存量数据（元素是 Double）也能正常读回来；
 *   - 落盘 JSON 结构与字段名**完全不变**，新旧版本可互读。
 *
 * 注：`runCatching` 只护得住"解析失败"，护不住"取值时强转失败" ——
 * 后者发生在别的文件里，这也是当初漏掉它的原因。
 */
class GraphLayoutStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(MainActivityPrefs.NAME, Context.MODE_PRIVATE)

    /** 读某个会话的布局；没有记录或解析失败时返回 null（调用方走自动布局）。 */
    fun load(conversationId: String?): GraphLayoutSnapshot? {
        if (conversationId.isNullOrBlank()) return null
        // 整段包在 runCatching 里：布局是**次要数据**，
        // 一份损坏的 JSON 绝不该让应用在切换视图时闪退 —— 回落到自动布局即可。
        return runCatching {
            val json = prefs.getString(keyOf(conversationId), null)
                ?.takeIf { it.isNotBlank() } ?: return null
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return null
            parseSnapshot(root.asJsonObject)?.takeIf { it.isUsable() }
        }.getOrNull()
    }

    /**
     * 保存某个会话的布局。
     *
     * 空布局**不写入**：宁可保留上一次的好数据，也不要用一份空表把用户的
     * 摆放覆盖掉（例如切会话那一瞬间节点还没加载出来）。
     * 写入本身也不抛异常（见 runCatching）—— 存不下一个坐标不该崩掉应用。
     */
    fun save(conversationId: String?, snapshot: GraphLayoutSnapshot) {
        if (conversationId.isNullOrBlank()) return
        if (!snapshot.isUsable()) return
        runCatching {
            prefs.edit()
                .putString(keyOf(conversationId), writeSnapshot(snapshot))
                .apply()
        }
    }

    /** 清掉某个会话的布局（用于"重新布局"这种明确要求丢弃手工摆放的动作）。 */
    fun clear(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        runCatching { prefs.edit().remove(keyOf(conversationId)).apply() }
    }

    // ------------------------------------------------------------ 显式编解码

    /**
     * 写出与旧版本**逐字段一致**的 JSON：
     * `{"ids":[...],"xs":[...],"ys":[...],"ws":[...],"hs":[...]}`
     *
     * 保持字段名与数组语义不变，是为了让已经装过旧版本的用户
     * 升级后仍能读回自己的布局，不会因为换了实现就把用户的摆放弄丢。
     */
    private fun writeSnapshot(snapshot: GraphLayoutSnapshot): String {
        val obj = JsonObject()
        obj.add("ids", snapshot.ids?.toStringArray() ?: JsonArray())
        obj.add("xs", snapshot.xs?.toNumberArray() ?: JsonArray())
        obj.add("ys", snapshot.ys?.toNumberArray() ?: JsonArray())
        obj.add("ws", snapshot.ws?.toNumberArray() ?: JsonArray())
        obj.add("hs", snapshot.hs?.toNumberArray() ?: JsonArray())
        return obj.toString()
    }

    private fun List<String>.toStringArray(): JsonArray {
        val arr = JsonArray(size)
        forEach { arr.add(it) }
        return arr
    }

    private fun List<Float>.toNumberArray(): JsonArray {
        val arr = JsonArray(size)
        forEach { arr.add(it) }
        return arr
    }

    /**
     * 解析快照。数值一律经 [GraphLayoutSnapshot.numberToFloat] 转换，
     * 所以元素是 Float、Double 还是 LazilyParsedNumber 都能正确读回。
     */
    private fun parseSnapshot(obj: JsonObject): GraphLayoutSnapshot? {
        val ids = obj.getAsJsonArray("ids")?.mapNotNull { el ->
            // 非字符串元素（数据被写坏）当作缺失，交给 isUsable 的长度校验兜底
            el.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        } ?: return null

        return GraphLayoutSnapshot(
            ids = ids,
            xs = readFloatArray(obj, "xs"),
            ys = readFloatArray(obj, "ys"),
            ws = readFloatArray(obj, "ws"),
            hs = readFloatArray(obj, "hs")
        )
    }

    /**
     * 读一个数值数组，逐元素容错转换。
     *
     * 元素无法转成有限 Float 时填 **NaN 占位**，而不是跳过 ——
     * 因为 [GraphLayoutSnapshot.isUsable] 依赖下标与 ids 一一对应，
     * 跳过会让后面的坐标整体错位（卡片飞到屏幕外）；
     * 而 NaN 会被 `isUsable` 的 `isFinite()` 检查挡下，整份优雅地回落自动布局。
     */
    private fun readFloatArray(obj: JsonObject, name: String): List<Float>? {
        val arr = obj.getAsJsonArray(name) ?: return null
        return arr.map { el ->
            if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) {
                GraphLayoutSnapshot.numberToFloat(el.asJsonPrimitive.asNumber) ?: Float.NaN
            } else {
                Float.NaN
            }
        }
    }

    private fun keyOf(conversationId: String) = "$KEY_PREFIX$conversationId"

    private companion object {
        const val KEY_PREFIX = "graph_layout_"
    }
}
