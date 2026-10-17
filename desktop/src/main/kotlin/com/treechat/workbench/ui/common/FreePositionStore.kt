// 桌面替身：ui.common.FreePositionStore（书卡/图标自由摆放坐标，Properties 持久化）。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.ui.common

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.ui.explorer.FreePosition

class FreePositionStore(context: Context, private val key: String) {

    private val prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): Map<String, FreePosition> {
        val json = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, FreePosition>>() {}.type
            gson.fromJson<Map<String, FreePosition>>(json, type).filterValues { value ->
                value.x.isFinite() && value.y.isFinite()
            }
        }.getOrDefault(emptyMap())
    }

    fun save(model: Map<String, FreePosition>) {
        val cleaned = model.entries.associate { it.key to it.value }
        prefs.edit().putString(key, gson.toJson(cleaned)).apply()
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val KEY_EXPLORER = "explorer_free_positions"
        const val KEY_SHELF = "shelf_free_positions"
    }
}
