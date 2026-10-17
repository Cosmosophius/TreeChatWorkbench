// 充面层：android.content.{ClipboardManager, ClipData} —— LiveTextPageView 原生选区路径用。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package android.content

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class ClipData private constructor(text: String) {
    private val items: MutableList<Item> = mutableListOf(Item(text))

    val itemCount: Int get() = items.size

    @JvmOverloads
    fun getItemAt(index: Int = 0): Item = items[index]

    class Item(internal val text: String) {
        fun coerceToText(context: Context?): CharSequence = text
    }

    companion object {
        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData =
            ClipData(text.toString())
    }
}

class ClipboardManager {
    fun interface OnPrimaryClipChangedListener {
        fun onPrimaryClipChanged()
    }

    private val listeners = ArrayList<OnPrimaryClipChangedListener>()

    fun setPrimaryClip(clip: ClipData?) {
        if (clip == null) return
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(clip.getItemAt(0).text), null)
        }
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it.onPrimaryClipChanged() } }
    }

    val primaryClip: ClipData?
        get() = try {
            val transferable = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            val text = transferable?.getTransferData(DataFlavor.stringFlavor) as? String
            text?.let { ClipData.newPlainText("text", it) }
        } catch (_: Exception) { null }

    fun getPrimaryClipDescription(): Any? = null

    fun hasPrimaryClip(): Boolean = try { primaryClip != null } catch (_: Exception) { false }

    fun addPrimaryClipChangedListener(listener: OnPrimaryClipChangedListener?) {
        if (listener != null) synchronized(listeners) { listeners.add(listener) }
    }

    fun removePrimaryClipChangedListener(listener: OnPrimaryClipChangedListener?) {
        if (listener != null) synchronized(listeners) { listeners.remove(listener) }
    }
}

interface OnPrimaryClipChangedListener {
    fun onPrimaryClipChanged()
}

