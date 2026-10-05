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
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Python → Kotlin 的日志回流入口（第 1 步桥接层）。
 *
 * Python 侧 `core.logging_bridge.install_kotlin_sink` 通过
 * `jclass("com.j2pmobile.android.LogBridge").append(level, msg)` 调进来。
 *
 * **性能约定（重要）**：下载时 jmcomic 每张图会打 6+ 行、几十个线程并发，日志量很大。
 * 因此这里刻意做成**写入 O(1) + 限频合并刷新**：
 *
 * - [append] 只把消息压进有界队列（超上限从头部丢），不做任何字符串拼接、不发状态；
 * - 由专用线程每 [FLUSH_INTERVAL_MS] 合并一次（最多 300ms 一次），把整段文本写入 [text]；
 * - UI 直接显示 [text]，**不要**在组合函数里再 `joinToString`（那会把开销重新放大到每帧）。
 *
 * 早期实现是「每行都复制整个列表并立刻发状态」，大本子下会同时拖慢日志面板与下载线程
 * （下载线程要同步等 JNI 返回，叠加高频分配造成的 GC 抖动）。
 *
 * 注意：[append] 由 Python 工作线程调用，故用 StateFlow（原子更新）而非 Compose 可变状态。
 */
object LogBridge {

    /** 保留的最大行数（超出从头部丢弃，避免无限增长）。 */
    private const val MAX_LINES = 400

    /** 合并刷新间隔（毫秒）。 */
    private const val FLUSH_INTERVAL_MS = 300L

    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_LINES + 1)
    private var flushPending = false

    private val _text = MutableStateFlow("")

    /** 合并后的日志全文（最多 [MAX_LINES] 行），供 UI 直接显示。 */
    val text: StateFlow<String> = _text

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "jm2pdf-log-flush").apply { isDaemon = true }
        }
    }

    /** 追加一行日志（Python 回调，可能来自任意下载线程）。 */
    @JvmStatic
    fun append(level: String, message: String) {
        val needSchedule = synchronized(lock) {
            if (buffer.size >= MAX_LINES) {
                buffer.removeFirst()
            }
            buffer.addLast(message)
            if (flushPending) {
                false
            } else {
                flushPending = true
                true
            }
        }
        if (needSchedule) {
            executor.schedule(::flush, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() {
        val snapshot = synchronized(lock) {
            flushPending = false
            buffer.joinToString("\n")
        }
        _text.value = snapshot
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
        _text.value = ""
    }
}