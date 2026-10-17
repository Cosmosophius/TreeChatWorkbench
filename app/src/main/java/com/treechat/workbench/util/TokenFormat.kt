package com.treechat.workbench.util

import java.util.Locale

/**
 * Token 数量格式化与解析。
 *
 * 设计原则（为什么这样写）：
 * 1. 显示层只做「无损美化」：仅当一个数能被 1000 / 1000000 整除时才缩写为 K / M，
 *    否则回退为带千分位的精确整数。这样 format -> parse 是可逆的，不会出现
 *    「显示 131.07K，实际存 131070」这种静默丢精度的问题。
 * 2. 解析层宽容：接受 128k / 128K / 1m / 1.5M / 131,072 / 131072 等写法。
 */
object TokenFormat {

    const val K_BASE = 1_000L
    const val M_BASE = 1_000_000L

    /**
     * 将精确整数格式化为人类可读形式。
     *
     * 1000       -> "1K"
     * 64000      -> "64K"
     * 128000     -> "128K"
     * 1000000    -> "1M"
     * 1500000    -> "1.5M"
     * 131072     -> "131,072"   （不可整除，保持精确，不缩写）
     * 0 / 负数    -> "0"
     */
    fun format(count: Long): String {
        if (count <= 0L) return "0"
        if (count % M_BASE == 0L) return "${count / M_BASE}M"
        if (count % K_BASE == 0L) {
            // 大于 1M 且是 100K 整数倍时，用一位小数的 M 更易读（如 1500000 -> 1.5M）
            if (count >= M_BASE && count % (K_BASE * 100L) == 0L) {
                val whole = count / M_BASE
                val frac = (count % M_BASE) / (K_BASE * 100L)
                return "$whole.${frac}M"
            }
            return "${count / K_BASE}K"
        }
        return String.format(Locale.US, "%,d", count)
    }

    /**
     * 解析用户输入。无法解析时返回 null（调用方据此保留原值，不要写成 0）。
     */
    fun parse(text: String): Long? {
        val cleaned = text.trim()
            .replace(",", "")
            .replace("_", "")
            .replace(" ", "")
            .replace("，", "")
        if (cleaned.isEmpty()) return null
        val lower = cleaned.lowercase(Locale.US)
        val value = when {
            lower.endsWith("m") -> lower.dropLast(1).toDoubleOrNull()?.let { it * M_BASE }
            lower.endsWith("k") -> lower.dropLast(1).toDoubleOrNull()?.let { it * K_BASE }
            else -> cleaned.toDoubleOrNull()
        } ?: return null
        if (value.isNaN() || value.isInfinite() || value < 0) return null
        return value.toLong()
    }
}
