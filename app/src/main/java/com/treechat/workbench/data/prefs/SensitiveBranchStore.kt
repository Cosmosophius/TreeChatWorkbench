package com.treechat.workbench.data.prefs

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 敏感枝条标记持久化存储。
 *
 * 严格遵守第一条纪律：**绝对禁止新增 Room 数据库的表、列或执行 migration**。
 * 所有敏感枝条的节点 ID 集合序列化为 JSON 存入 SharedPreferences（AppSettings.PREFS）。
 *
 * 作用：记录被标记为含敏感内容的分支节点，避免用户在切换至 DeepSeek 等含强风控审查
 * 的国内模型时因上下文违规而触发接口拒答或封号。
 */
class SensitiveBranchStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 内存态缓存，驱动 Compose 界面实时响应 */
    val sensitiveNodeMap = mutableStateMapOf<String, Boolean>()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val json = prefs.getString(KEY_SENSITIVE_NODES, null)
        if (!json.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<Set<String>>() {}.type
                val set: Set<String> = gson.fromJson(json, type)
                sensitiveNodeMap.clear()
                set.forEach { sensitiveNodeMap[it] = true }
            }
        }
    }

    private fun saveToPrefs() {
        val set = sensitiveNodeMap.keys.toSet()
        val json = gson.toJson(set)
        prefs.edit().putString(KEY_SENSITIVE_NODES, json).apply()
    }

    fun isNodeSensitive(nodeId: String): Boolean {
        return sensitiveNodeMap[nodeId] == true
    }

    fun setNodeSensitive(nodeId: String, sensitive: Boolean) {
        if (sensitive) {
            sensitiveNodeMap[nodeId] = true
        } else {
            sensitiveNodeMap.remove(nodeId)
        }
        saveToPrefs()
    }

    fun toggleNodeSensitive(nodeId: String): Boolean {
        val current = isNodeSensitive(nodeId)
        val target = !current
        setNodeSensitive(nodeId, target)
        return target
    }

    fun getSensitiveNodeIds(): Set<String> {
        return sensitiveNodeMap.keys.toSet()
    }

    companion object {
        private const val KEY_SENSITIVE_NODES = "sensitive_branch_nodes_json"

        @Volatile
        private var instance: SensitiveBranchStore? = null

        fun get(context: Context): SensitiveBranchStore {
            return instance ?: synchronized(this) {
                instance ?: SensitiveBranchStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
