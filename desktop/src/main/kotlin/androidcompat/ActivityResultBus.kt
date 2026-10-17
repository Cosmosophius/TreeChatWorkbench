// 充面层：androidx.activity 内容里的 ManagedActivityResultLauncher 请求总线。
// 桌面宿主 swing FileDialog 挑选完文件后调 complete(requestCode, uris)。
@file:Suppress("unused", "unused_parameter", "UNCHECKED_CAST", "MemberVisibilityCanBePrivate")
package androidx.activity.compose

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** 一次未决请求：桌面宿主完成后把 Uri 列表灌进回调。 */
object ActivityResultBus {
    private val pending = HashMap<Long, (Any?) -> Unit>()
    private var requestSeq = 1L

    internal fun <I, O> register(
        contract: ActivityResultContract<I, O>,
        input: I,
        onResult: (O) -> Unit,
    ) {
        val id = requestSeq++
        synchronized(pending) { pending[id] = { any -> onResult(any as O) } }
        pendingHook?.invoke(id, contract, input)
    }

    /** 桌面宿主装一次弹窗钩子（DesktopWorkbench 启动时装好）。 */
    @Volatile
    var pendingHook: ((id: Long, contract: ActivityResultContract<*, *>, input: Any?) -> Unit)? = null

    /** 桌面宿主回填结果。 */
    fun complete(id: Long, result: Any?) {
        val consumer = synchronized(pending) { pending.remove(id) }
        consumer?.invoke(result)
    }
}
