package com.treechat.core

/**
 * 平台环境抽象（docs/PORTING.md §1「Context 全局」一行的落点）。
 *
 * 安卓上由 Context 实现，桌面上由 java.io / java.awt 实现。
 * 侧向约定：接口只暴露「能力」而非平台对象；调用方不得假设与哪个 UI 线程绑定。
 */
interface AppEnv {
    /** 平台名，仅用于日志与调试界面，如 "android" / "linux-arm64" / "macos-arm64"。 */
    val platform: String

    /** 当前用户/应用专属的可写目录（安卓 filesDir 的等位目录）。 */
    val filesDir: AppFile

    /** 生成安全的随机 UUID 字符串。 */
    fun newUuid(): String

    /** 把任意文本写入系统剪贴板。 */
    fun setClipboardText(text: String)

    /** 用系统默认方式打开外部链接（安卓 Intent ACTION_VIEW / 桌面 Desktop 浏览器）。 */
    fun openLink(url: String): Boolean
}

/**
 * 路径抽象：不把 java.io.File / android.Uri 泄漏进接口，
 * 平台实现负责把语义映射到各自的文件 API。
 */
interface AppFile {
    /** 人类可读的绝对路径，仅用于调试与错误提示。 */
    val path: String

    /** 拼子路径，子路径以「/」分隔；返回的 AppFile 指向其下同名条目。 */
    fun resolve(child: String): AppFile

    /** 该路径所指文件/目录是否存在。 */
    fun exists(): Boolean

    /** 读出全部字节（文件不存在时抛出异常，由调用方决定如何兜底）。 */
    fun readBytes(): ByteArray

    /** 覆盖写入全部字节（必要时自动创建父目录）。 */
    fun writeBytes(data: ByteArray)
}
