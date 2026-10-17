// 充面层：android.icu.text.BreakIterator（SelectionModel 汉字分词），委托 icu4j。
@file:Suppress("unused")
package android.icu.text

import com.ibm.icu.text.BreakIterator as IcuBreakIterator

class BreakIterator private constructor(private val delegate: IcuBreakIterator) {
    fun setText(text: String) { delegate.setText(text) }

    fun current(): Int = delegate.current()
    fun next(): Int = delegate.next()
    fun next(codePointLimit: Int): Int = delegate.next(codePointLimit)
    fun preceding(offset: Int): Int = delegate.preceding(offset)
    fun following(offset: Int): Int = delegate.following(offset)
    fun first(): Int = delegate.first()
    fun last(): Int = delegate.last()

    companion object {
        @JvmStatic
        fun getWordInstance(locale: java.util.Locale?): BreakIterator =
            BreakIterator(IcuBreakIterator.getWordInstance(locale ?: java.util.Locale.getDefault()))

        @JvmStatic
        fun getCharacterInstance(locale: java.util.Locale?): BreakIterator =
            BreakIterator(IcuBreakIterator.getCharacterInstance())
    }
}
