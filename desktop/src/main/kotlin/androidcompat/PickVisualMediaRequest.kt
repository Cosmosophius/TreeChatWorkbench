// 充面层：androidx.activity.result.PickVisualMediaRequest（正确包名）。
@file:Suppress("unused")
package androidx.activity.result

class PickVisualMediaRequest(val mediaType: Int) {
    companion object {
        @JvmStatic
        fun builder(mediaType: Int): PickVisualMediaRequest = PickVisualMediaRequest(mediaType)
    }
}
