package com.treechat.workbench.util

import android.content.Context
import com.treechat.workbench.BuildConfig

/**
 * 版本特性控制器：管理全功能版 (Full) 与纯净精简版 (Lite)。
 * 
 * 精简版 (Lite):
 * 彻底移除词典、词源音标映射与查询入口，保留最纯粹极致的树状对话、书架阅读与笔记工作台。
 * 全功能版 (Full):
 * 包含完整三语词典、离线字词库、词源思维导图与音标溯源功能。
 */
object AppEdition {

    /**
     * 当前编译形态是否包含词典特性代码。
     */
    val isDictionaryCompiledIn: Boolean
        get() = try {
            BuildConfig.FEATURE_DICTIONARY
        } catch (_: Throwable) {
            true
        }

    /**
     * 当前版本代号 (Full 或 Lite)。
     */
    val editionName: String
        get() = try {
            BuildConfig.EDITION_NAME
        } catch (_: Throwable) {
            "Full"
        }

    /**
     * 运行时是否启用了词典功能。
     * 若当前编译版本为 Lite 版，则恒为 false；
     * 若为 Full 版，则兼顾用户的动态开关偏好。
     */
    fun isDictionaryEnabled(context: Context? = null): Boolean {
        if (!isDictionaryCompiledIn) return false
        if (context == null) return true
        val sp = context.getSharedPreferences("treechat_prefs", Context.MODE_PRIVATE)
        return sp.getBoolean("feature_dictionary_enabled", true)
    }

    /**
     * 在设置中动态启用或禁用词典模块（仅在 Full 版下有效）。
     */
    fun setDictionaryEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences("treechat_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("feature_dictionary_enabled", enabled).apply()
    }
}
