package com.treechat.workbench.ui.common

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.treechat.workbench.data.prefs.AppSettings
import com.treechat.workbench.ui.explorer.FreePosition

/**
 * 「图标自由摆放」坐标的统一持久化（SharedPreferences + Gson JSON）。
 *
 * ## 为什么合并成一个类
 * 访达（文件夹页）与书架各写过一份一模一样的存取实现，只有 key 不同。
 * 两份代码意味着以后任何一处修 bug 都要记得改另一处 —— 这类"复制出来的双胞胎"
 * 正是本仓库反复出回归的来源之一。现在只有这一个类，key 由调用方传入。
 *
 * ## 为什么不进 Room
 * 这里存的是纯界面态（图标摆在画布上的 x/y），与数据模型没有外键或查询关系；
 * 加表加列都要写迁移，而项目明确禁止变动数据库结构。
 *
 * ## 单位约定
 * x / y 一律是 **dp**（密度无关）：换分辨率、旋转屏幕、横竖屏切换后仍落在合理位置。
 * 读取时丢弃非法值（NaN / Infinity），写入前由调用方保证已夹紧到可视区。
 */
class FreePositionStore(context: Context, private val key: String) {

    private val prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 读取坐标表；JSON 缺失、损坏或类型不符一律回退为空表。 */
    fun load(): Map<String, FreePosition> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, FreePosition>>() {}.type
            val parsed = gson.fromJson<Map<String, FreePosition>>(raw, type)
            parsed.orEmpty().filterValues { it.x.isFinite() && it.y.isFinite() }
        }.getOrDefault(emptyMap())
    }

    /** 覆盖写入整张坐标表（apply 异步落盘，不阻塞主线程）。 */
    fun save(positions: Map<String, FreePosition>) {
        prefs.edit().putString(key, gson.toJson(positions)).apply()
    }

    /** 清空坐标表 —— 对应界面上的「恢复默认排列」。 */
    fun clear() {
        prefs.edit().remove(key).apply()
    }

    companion object {
        /** 访达（文件夹页）的自由摆放坐标。 */
        const val KEY_EXPLORER = "explorer_free_positions"

        /** 书架的自由摆放坐标。与访达分开存：混用会让两处的摆放互相错位。 */
        const val KEY_SHELF = "shelf_free_positions"
    }
}
