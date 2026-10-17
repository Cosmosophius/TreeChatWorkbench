// 充面层：androidx.activity.result.* 合约族（桌面对齐共享代码真实引用面）。
@file:Suppress("unused", "unused_parameter")
package androidx.activity.result.contract

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest

/** 活动结果契约的桌面最小抽象（调用方只 new 与 launch，不碰内部）。 */
open class ActivityResultContract<I : Any?, O : Any?>

object ActivityResultContracts {
    /** 多选图片（共享代码唯一用点：ChatMessageView 附件选取）。 */
    class PickMultipleVisualMedia(val maxItems: Int = 4) : ActivityResultContract<PickVisualMediaRequest, List<Uri>>()

    /** 打开单个文档（导入链路）。 */
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>()

    /** 打开多个文档（KnowledgeManagerDialog 批量导入）。 */
    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>()

    /** 建新文档（后续里程碑）。 */
    class CreateDocument(val mimeTypes: Array<String> = emptyArray()) : ActivityResultContract<String, Uri?>()

    /** PickVisualMedia 的媒体类型常量（对齐安卓命名）。 */
    object PickVisualMedia {
        const val ImageOnly: Int = 1
        const val VideoOnly: Int = 2
        const val ImagesAndVideo: Int = 3
    }
}

/** PickVisualMediaRequest 见 androidx.activity.result.PickVisualMediaRequest（安卓同构）。 */
