// 桌面 DAO 公共样板：统一的行映射/写入包装与变更脉冲管理。
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.treechat.workbench.data.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 行映射工具（调用方必须在 synchronized(connection) 块里使用）。 */
object Rows {
    fun <T> list(st: PreparedStatement, mapper: (ResultSet) -> T): List<T> =
        st.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out.add(mapper(rs))
            out
        }

    fun <T> first(st: PreparedStatement, mapper: (ResultSet) -> T): T? =
        st.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }

    fun scalarLong(st: PreparedStatement): Long =
        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
}

/** 在 IO 上执行的事务级写操作（调用方给 SQL 动作者本体）。 */
@Suppress("NOTHING_TO_INLINE")
suspend fun <T> io_apply(operation: () -> T): T = withContext(Dispatchers.IO) { operation() }

/** 简单包装：synchronized + prepared + executeUpdate。 */
fun Connection.exec(sql: String, binder: (PreparedStatement) -> Unit): Int =
    prepareStatement(sql).use { st -> binder(st); st.executeUpdate() }

/** 观察：DbChanges 发射后重读一次 [fallback]（fallback 返回最近值）。 */
fun <T> observe(tablesMask: Int, recompute: suspend () -> T): Flow<T> =
    DbChanges.flowFor(tablesMask).map { recompute() }
