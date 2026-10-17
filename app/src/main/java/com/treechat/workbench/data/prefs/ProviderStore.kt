package com.treechat.workbench.data.prefs

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.treechat.workbench.network.protocol.ProviderConfig

/**
 * 服务商配置的持久化仓库：**多服务商并存**。
 *
 * ## 为什么需要它（修复的真实缺陷）
 *
 * 旧实现把整份 `ProviderConfig` 存在一个 SharedPreferences 键下
 * （`provider_config`），保存时**整体覆盖**。于是"配好 DeepSeek 再配 Gemini，
 * 回头发现 DeepSeek 没了" —— 用户要同时留几条链路时只能反复重填，属于致命缺陷。
 *
 * ## 数据结构
 *
 * 与 DeepSeek Harness 的做法对齐（在 app.asar 中实测确认）：
 * DSH 用的是 `providers: z.dict(profile)` —— **以 provider id 为键的字典**，
 * 新增走 `set(["providers", id])`，同名即整体覆盖、天然幂等；删除走 `unset`。
 * 这里同样用 `id -> ProviderConfig` 的 Map，因此：
 *
 * - **新增**：换一个 id 就是并存；重复 id 就是"更新这一条"（幂等，不会产生两条）。
 * - **删除**：只摘掉这一条，其余原样保留。
 * - **切换**：单独记 `activeProviderId`，不碰其余配置。
 *
 * 与 DSH 的一处**有意偏离**：DSH 把 API Key 拆到独立的
 * `.credentials.yaml`，配置里只留引用名。本应用是单机单用户的 Android
 * 客户端，把所有 ProviderConfig 存在应用私有 SharedPreferences 里即可；
 * 引入引用层只会增加复杂度而不带来实际隔离收益。
 *
 * ## 兼容旧数据（重要）
 *
 * 若存在旧的单份 `provider_config` 键，会被读出来放进列表并标记为当前选中，
 * 然后写入新键。**旧键不删除** —— 万一新格式出问题，用户的原配置还在，
 * 可以手工回退。迁移是幂等的：重复运行不会产生重复条目（按 id 覆盖）。
 */
class ProviderStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(MainActivityPrefs.NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 读出全部已保存的服务商。
     *
     * 读取顺序刻意是"新键优先、旧键兜底"，这样升级用户第一次启动时也能拿到
     * 原来的那一条，不需要重新填写。
     */
    fun loadProviders(): List<ProviderConfig> {
        val raw = loadFromNewKey()?.takeIf { it.isNotEmpty() }
            ?: loadLegacy()?.let { listOf(it) }
            ?: emptyList()
        return stripRemovedLocalProvider(raw)
    }

    /**
     * 摘掉已废弃的「本地 NPU 离线模型」（id = `local-npu-qwen` / `local_npu_qwen`）。
     *
     * 本地大模型方案已按用户要求整体移除；若用户升级前的配置里留着这一条，
     * 它会出现在顶栏模型菜单里但一发消息就报错。这里在读入时直接剔掉，
     * 免得上层还要到处判空。
     *
     * 只做过滤、不改写磁盘：磁盘上的旧数据保留，万一将来要恢复还能取回。
     * 调用方下一次保存时自然会把过滤后的列表写回去。
     */
    private fun stripRemovedLocalProvider(list: List<ProviderConfig>): List<ProviderConfig> =
        list.filterNot { it.id == "local-npu-qwen" || it.id == "local_npu_qwen" }

    /** 读出上次选中的服务商 id；没有记录时返回 null（调用方回落到列表第一项）。 */
    fun loadActiveId(): String? =
        prefs.getString(KEY_ACTIVE_PROVIDER_ID, null)?.takeIf { it.isNotBlank() }

    /**
     * 保存整份列表与当前选中项。
     *
     * 列表顺序即用户在设置里看到的顺序（新加的在后面）。
     * 传空列表时不写入空数组，避免把用户配置抹成空 —— 调用方应先确认。
     */
    fun saveProviders(providers: List<ProviderConfig>, activeId: String?) {
        if (providers.isEmpty()) return
        prefs.edit()
            .putString(KEY_PROVIDER_LIST, gson.toJson(providers))
            .apply {
                if (activeId.isNullOrBlank()) remove(KEY_ACTIVE_PROVIDER_ID)
                else putString(KEY_ACTIVE_PROVIDER_ID, activeId)
            }
            .apply()
    }

    // ------------------------------------------------------------------ 内部

    private fun loadFromNewKey(): List<ProviderConfig>? {
        val json = prefs.getString(KEY_PROVIDER_LIST, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val type = object : TypeToken<List<ProviderConfig>>() {}.type
            gson.fromJson<List<ProviderConfig>>(json, type)
                ?.filter { it.id.isNotBlank() && it.baseUrl.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 读取旧版单份配置。
     *
     * 用 `ProviderConfig::class.java` 而不是 TypeToken —— 旧格式当初就是按
     * 这个类写的，用同一个类读回来最不容易出错。
     */
    private fun loadLegacy(): ProviderConfig? {
        val json = prefs.getString(KEY_PROVIDER_CONFIG_LEGACY, null)
            ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { gson.fromJson(json, ProviderConfig::class.java) }
            .getOrNull()
            ?.takeIf { it.id.isNotBlank() && it.baseUrl.isNotBlank() }
    }

    companion object {
        /** 旧版单份配置的键。**保留不删**，供回退。 */
        const val KEY_PROVIDER_CONFIG_LEGACY = "provider_config"
        /** 新版：全部服务商的 JSON 数组。 */
        const val KEY_PROVIDER_LIST = "provider_list"
        /** 新版：当前选中的服务商 id。 */
        const val KEY_ACTIVE_PROVIDER_ID = "active_provider_id"
    }
}

/** SharedPreferences 文件名。与 MainActivity 保持同一个文件，便于迁移。 */
object MainActivityPrefs {
    const val NAME = "treechat_prefs"
}
