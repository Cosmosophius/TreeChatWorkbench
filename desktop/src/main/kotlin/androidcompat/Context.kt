// 「充面层」：JVM 上提供共享源码用到的少量 Android 平台符号。
// 语义最小化：只陪伴桌面用到的路径，实现按需补。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package android.content

import com.treechat.desktop.DesktopAppEnvHolder
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 充面层 Context：桌面单例（[DesktopAppEnv] 撑底）。
 * 覆盖共享代码实际调到的 surface：
 * - getSharedPreferences（treechat_settings / treechat_prefs / treechat_ink_notes / reader_* 等）
 * - filesDir / applicationContext
 * - assets.open（etymology 词库 TSV → classpath 资源）
 */
open class Context internal constructor() {
    private val prefsCache = HashMap<String, SharedPreferences>()

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        synchronized(prefsCache) {
            return prefsCache.getOrPut(name) {
                PropertiesSharedPreferences(name, File(DesktopAppEnvHolder.env.filesDir.path, "prefs"))
            }
        }
    }

    val filesDir: File get() = File(DesktopAppEnvHolder.env.filesDir.path)
    val cacheDir: File get() = File(filesDir, "cache")
    val packageName: String get() = "com.treechat.workbench"
    val contentResolver: ContentResolver get() = ContentResolver()
    val applicationContext: Context get() = this
    val assets: AssetManager get() = DesktopAssetManager

    fun getSystemService(name: String): Any? = when (name) {
        CLIPBOARD_SERVICE -> DesktopServices.clipboard
        else -> null
    }

    /** 桌面无外部 Activity；共享代码的 startActivity 退化为日志。 */
    open fun startActivity(intent: android.content.Intent?) {
        System.err.println("[facade] startActivity ignored: ${intent?.action}")
    }

    fun openFileInput(name: String): InputStream =
        FileInputStream(File(filesDir, name))

    fun openFileOutput(name: String, mode: Int): java.io.FileOutputStream {
        val f = File(filesDir, name)
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        return java.io.FileOutputStream(f, mode == MODE_APPEND)
    }

    companion object {
        // 安卓常量，共享代码按 Context.MODE_PRIVATE 等 FQ 引用
        const val MODE_PRIVATE = 0
        const val MODE_APPEND = 1
        const val MODE_WORLD_READABLE = 1
        const val CLIPBOARD_SERVICE = "clipboard"
        const val INPUT_METHOD_SERVICE = "input_method"
        const val DOWNLOAD_SERVICE = "download"
        const val NOTIFICATION_SERVICE = "notification"
    }

    private object DesktopServices {
        val clipboard = ClipboardManager()
    }
}

/** 桌面唯一 Context 实例（Main 启动时经 [DesktopAppEnvHolder] 挂接）。 */
class DesktopAppContext : Context()

/** assets 充面：classpath 资源（desktop resources 下 assets/<名> 与 app assets 同布局）。 */
object DesktopAssetManager : AssetManager() {
    override fun open(fileName: String): InputStream {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("assets/$fileName")
            ?: DesktopAssetManager::class.java.classLoader?.getResourceAsStream("assets/$fileName")
            ?: throw java.io.FileNotFoundException("桌面端缺少资产 assets/$fileName（请核对 resources 布线）")
        return BufferedInputStream(stream)
    }
}

open class AssetManager {
    open fun open(fileName: String): InputStream =
        throw IllegalStateException("该 AssetManager 为充面默认，未实现 open")
}

/** SharedPreferences 的 JVM 实现：java.util.Properties 落盘（shared 区不依赖安卓运行时）。 */
class PropertiesSharedPreferences(
    private val prefName: String,
    private val dir: File,
) : SharedPreferences {
    private val lock = Any()
    private val backing = LinkedHashMap<String, Any>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val f = File(dir, "$prefName.xml")
        if (!f.exists()) return
        runCatching {
            f.inputStream().use { ins ->
                java.util.Properties().apply { load(ins) }
                    .forEach { (k, v) -> backing[k.toString()] = v.toString() }
            }
        }
    }

    private fun persist() {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "$prefName.xml")
        val props = java.util.Properties()
        backing.forEach { (k, v) -> props.setProperty(k, v.toString()) }
        f.outputStream().use { props.store(it, "TreeChatWorkbench desktop prefs") }
    }

    override fun getString(key: String?, defValue: String?): String? {
        synchronized(lock) { load(); return (backing[key] as? String) ?: defValue }
    }

    override fun getInt(key: String?, defValue: Int): Int {
        synchronized(lock) {
            load(); val v = backing[key] as? String ?: return defValue
            return v.toFloatOrNull()?.toInt() ?: defValue
        }
    }

    override fun getLong(key: String?, defValue: Long): Long {
        synchronized(lock) {
            load(); val v = backing[key] as? String ?: return defValue
            return v.toFloatOrNull()?.toLong() ?: defValue
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        synchronized(lock) {
            load(); val v = backing[key] as? String ?: return defValue
            return v.toFloatOrNull() ?: defValue
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        synchronized(lock) {
            load(); val v = backing[key] as? String ?: return defValue
            return v.toBooleanStrictOrNull() ?: defValue
        }
    }

    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String> {
        synchronized(lock) {
            load(); val v = backing[key] as? String ?: return defValues.orEmpty().toSet()
            return v.split("\u0001").filter { it.isNotEmpty() }.toSet()
        }
    }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun contains(key: String?): Boolean {
        synchronized(lock) { load(); return key != null && backing.containsKey(key) }
    }

    inner class EditorImpl : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String?, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            staged[key] = value; return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            staged[key] = value; return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            staged[key] = value; return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            staged[key] = value; return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            staged[key] = value; return this
        }
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            staged[key] = values?.joinToString("\u0001"); return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            staged[key] = null; return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true; return this
        }
        override fun apply() {
            synchronized(lock) {
                load()
                if (clearAll) backing.clear()
                staged.forEach { (k, v) ->
                    if (k == null) return@forEach
                    if (v == null) backing.remove(k) else backing[k] = v
                }
                persist()
            }
        }
        override fun commit(): Boolean { apply(); return true }
    }
}

interface SharedPreferences {
    interface Editor {
        fun putString(key: String?, value: String?): SharedPreferences.Editor
        fun putInt(key: String?, value: Int): SharedPreferences.Editor
        fun putLong(key: String?, value: Long): SharedPreferences.Editor
        fun putFloat(key: String?, value: Float): SharedPreferences.Editor
        fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor
        fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor
        fun remove(key: String?): SharedPreferences.Editor
        fun clear(): SharedPreferences.Editor
        fun apply()
        fun commit(): Boolean
    }
    fun getString(key: String?, defValue: String?): String?
    fun getInt(key: String?, defValue: Int): Int
    fun getLong(key: String?, defValue: Long): Long
    fun getFloat(key: String?, defValue: Float): Float
    fun getBoolean(key: String?, defValue: Boolean): Boolean
    fun getStringSet(key: String?, defValues: Set<String>?): Set<String>
    fun edit(): SharedPreferences.Editor
    fun contains(key: String?): Boolean
}
