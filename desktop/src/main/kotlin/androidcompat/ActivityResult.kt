// 充面层：androidx.activity.compose 记得启动器 —— launch() 经 ActivityResultBus
// 交给桌面宿主（Swing/AWT FileDialog），结果回填回调。
@file:Suppress("unused", "unused_parameter", "UNCHECKED_CAST")
package androidx.activity.compose

import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class ManagedActivityResultLauncher<I, O>(
    val contract: ActivityResultContract<I, O>,
    private val onResult: (O) -> Unit,
) {
    fun launch(input: I, options: Any? = null) {
        ActivityResultBus.register(contract, input) { raw -> onResult(raw as O) }
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> = remember(contract, onResult) {
    ManagedActivityResultLauncher(contract, onResult)
}

/** 充面层 BackHandler：桌面无系统返回键，静默。 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit = {}) {
}
