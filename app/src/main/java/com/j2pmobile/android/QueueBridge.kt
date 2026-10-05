/*
 * Copyright (C) 2026 WisadelZ
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.j2pmobile.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Python → Kotlin 的下载队列变更通知（第 4 步切片 2）。
 *
 * Python 侧 `bridge._queue_changed` 在任务状态 / 进度变化时通过
 * `jclass("com.j2pmobile.android.QueueBridge").notifyChanged()` 调进来，UI 订阅
 * [revision] 的变化去拉一次队列快照。
 *
 * **合并刷新**：队列的进度钩子来自多个下载线程，且 `notify(force=True)` 会绕过
 * Python 侧的节流（每章一次）；若每次回调都直接自增，Compose 会跟着高频重组。
 * 因此这里沿用 [LogBridge] 的做法：只做 O(1) 标记 + 300ms 合并刷新，
 * 保证「最后一次状态一定会被发出」，同时把刷新频率压到每秒几次。
 */
object QueueBridge {

    /** 合并刷新间隔（毫秒）。 */
    private const val INTERVAL_MS = 300L

    private val lock = Any()
    private var pending = false

    private val _revision = MutableStateFlow(0L)

    /** 队列版本号：每合并刷新一次 +1，供 UI 作为刷新触发。 */
    val revision: StateFlow<Long> = _revision

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "jm2pdf-queue-notify").apply { isDaemon = true }
        }
    }

    /** 队列有变化（Python 回调，可能来自任意下载线程）。 */
    @JvmStatic
    fun notifyChanged() {
        val schedule = synchronized(lock) {
            if (pending) {
                false
            } else {
                pending = true
                true
            }
        }
        if (schedule) {
            executor.schedule(::flush, INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() {
        synchronized(lock) { pending = false }
        _revision.value = _revision.value + 1L
    }
}