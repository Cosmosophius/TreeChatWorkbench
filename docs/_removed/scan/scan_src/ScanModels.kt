package com.treechat.workbench.scan

/**
 * 扫描会话：一次"把一堆纸变成一本书"的工作单元。
 *
 * 字段刻意保持扁平且可 JSON 序列化（见 ScanSessionStore），
 * 因为续拍要求每拍一页就落盘，不能依赖进程内存。
 */
data class ScanSession(
    val id: String,
    val title: String,
    val pages: List<ScanPage> = emptyList(),
    val colorMode: ScanColorMode = ScanColorMode.Color,
    val autoCropEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 一页扫描件。 */
data class ScanPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 从 1 开始的页码，删页/重排后会被重新编号。 */
    val index: Int,
    val width: Int = 0,
    val height: Int = 0
)

/** 扫描输出模式：彩色或黑白。 */
enum class ScanColorMode(val displayName: String) {
    Color("彩色"),
    Gray("黑白")
}