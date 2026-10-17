package com.treechat.workbench.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * 后台任务登记处（OCR / 建索引 / 其它长任务）。
 *
 * 为什么需要它：扫描件整本 OCR、几 MB 教材切片建索引都是**几十秒到几分钟**的活。
 * 以前只有一个飘在屏幕上的小胶囊，用户切走了就再也看不到进度，也没法回来看。
 * 这里把长任务统一登记成一条条可观察的记录，界面就可以随时"调出来看"。
 *
 * 设计取舍：
 *  - 单例 + `StateFlow`：任何地方（阅读器、知识库面板）都能发布进度，任何 Composable 都能订阅。
 *  - **进程内存**，不落库：任务本身是瞬时的；进程被杀就没意义了，不该持久化。
 *  - 只保留最近 [MAX_KEEP] 条，避免长期运行越积越多。
 */
object BackgroundTaskRegistry {

    private const val MAX_KEEP = 20

    enum class State { RUNNING, DONE, FAILED, CANCELLED }

    data class Task(
        val id: String,
        /** 面向用户的短标题，如「《扫描件》离线 OCR」「《教材》切片建索引」。 */
        val title: String,
        /** 细分阶段说明，可为空。 */
        val detail: String = "",
        /** 0f~1f；未知进度用 null（界面显示不确定态进度条）。 */
        val progress: Float? = null,
        val state: State = State.RUNNING,
        val startedAtMs: Long = System.currentTimeMillis(),
        val finishedAtMs: Long? = null
    ) {
        val isRunning: Boolean get() = state == State.RUNNING
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    /** 开始一个任务，返回 id；后续用 [update]/[finish] 推进。 */
    fun start(title: String, detail: String = "", progress: Float? = null): String {
        val id = UUID.randomUUID().toString()
        _tasks.update { list ->
            (list + Task(id = id, title = title, detail = detail, progress = progress))
                .takeLast(MAX_KEEP)
        }
        return id
    }

    /** 更新进度；任务已被清理则静默忽略。 */
    fun update(id: String, detail: String? = null, progress: Float? = null) {
        _tasks.update { list ->
            list.map { t ->
                if (t.id != id || !t.isRunning) t
                else t.copy(
                    detail = detail ?: t.detail,
                    progress = progress ?: t.progress
                )
            }
        }
    }

    /** 收尾。失败时 [error] 写进明细，界面会照实显示。 */
    fun finish(id: String, state: State = State.DONE, detail: String? = null) {
        _tasks.update { list ->
            list.map { t ->
                if (t.id != id) t
                else t.copy(
                    state = if (state == State.RUNNING) State.DONE else state,
                    detail = detail ?: t.detail,
                    progress = if (state == State.DONE) 1f else t.progress,
                    finishedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    fun fail(id: String, error: String?) = finish(id, State.FAILED, error)
    fun cancel(id: String) = finish(id, State.CANCELLED, "已取消")

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.isRunning } }
    }

    fun clearAll() {
        _tasks.update { emptyList() }
    }
}
