// 充面层：android.content.Intent（桌面侧 only 当「爱好性意图」占位，携字符串说明）。
@file:Suppress("unused", "unused_parameter")
package android.content

import android.net.Uri

open class Intent {
    var action: String? = null
    val data: Uri? = null
    var `type`: String? = null
    var component: Any? = null
    val extras = HashMap<String, Any?>()

    constructor()
    constructor(action: String?) { this.action = action }

    fun putExtra(name: String, value: String?): Intent = this
    fun putExtra(name: String, value: Boolean?): Intent = this
    fun putExtra(name: String, value: Int?): Intent = this
    fun putExtra(name: String, value: Long?): Intent = this
    fun putExtra(name: String, value: Any?): Intent = this
    fun getStringExtra(name: String): String? = extras[name] as? String
    fun setPackage(name: String?): Intent = this
    fun addFlags(flags: Int): Intent = this
    fun setIntentType(t: String?): Intent = this

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"
        const val ACTION_CHOOSER = "android.intent.action.CHOOSER"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val FLAG_GRANT_READ_URI_PERMISSION = 1 shl 12
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 1 shl 13

        @JvmStatic
        fun createChooser(target: Intent?, title: String?): Intent = Intent(ACTION_CHOOSER)
    }
}
