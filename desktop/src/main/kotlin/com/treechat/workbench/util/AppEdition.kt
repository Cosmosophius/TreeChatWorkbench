// 桌面替身：util.AppEdition（安卓版吃 BuildConfig；桌面版恒为完整版词典可用）。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.util

import android.content.Context

object AppEdition {
    val isDictionaryCompiledIn: Boolean = true
    val editionName: String = "Full"

    fun isDictionaryEnabled(context: Context? = null): Boolean = isDictionaryCompiledIn

    fun setDictionaryEnabled(context: Context, enabled: Boolean) {
        // 桌面 v1 恒可用，落盘仅为界面状态一致性。
        context.getSharedPreferences("treechat_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("feature_dictionary_enabled", enabled).apply()
    }
}
