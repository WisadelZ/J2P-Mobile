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
package com.j2pmobile.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.QueueStats
import com.j2pmobile.android.R
import com.j2pmobile.android.TaskItem
import com.j2pmobile.android.TaskStatus

/**
 * 下载队列在界面侧的状态（由 [AppShell] 持有）。
 *
 * 与 [ExploreState] 同理：状态挂在 AppShell 而不是页面内部，这样从下载页切到任务页
 * 再切回来，输入框、筛选与排序都还在（等价于桌面版把页面实例缓存起来）。
 *
 * 过滤与排序**在 Python 侧完成**（复用 `core.task_queue` 的纯函数），这里只存参数。
 */
class QueueState {

    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
    var stats by mutableStateOf(QueueStats(0, 0, 0, 0, 0, 0, 0))
    var paused by mutableStateOf(false)
    var shown by mutableStateOf(0)
    var total by mutableStateOf(0)

    /** 是否已经成功取到过一次快照（用于区分「队列为空」与「还没加载」）。 */
    var loaded by mutableStateOf(false)

    // ---- 任务页的搜索 / 筛选 / 排序（与桌面版同名语义）
    var keywordInput by mutableStateOf("")
    var keyword by mutableStateOf("")
    var statusFilter by mutableStateOf(TaskStatus.FILTER_ALL)
    var sortKey by mutableStateOf("added")
    var sortDesc by mutableStateOf(false)

    // ---- 两个页面共用的队列级提示
    var statusRes by mutableStateOf(R.string.status_ready)
    var statusArg by mutableStateOf<String?>(null)
    var statusKind by mutableStateOf(StatusKind.IDLE)

    fun setStatus(res: Int, kind: StatusKind, arg: String? = null) {
        statusRes = res
        statusArg = arg
        statusKind = kind
    }

    /** 拉一次快照并写回界面状态；失败时保留上一次结果，只记日志。 */
    suspend fun refresh() {
        when (val result = ApiBridge.queueSnapshot(keyword, statusFilter, sortKey, sortDesc)) {
            is ApiResult.Ok -> {
                tasks = result.value.tasks
                stats = result.value.stats
                paused = result.value.paused
                shown = result.value.shown
                total = result.value.total
                loaded = true
            }

            is ApiResult.Err -> Unit
        }
    }

    fun isDefaultSort(): Boolean =
        sortKey == "added" && sortDesc == TaskStatus.sortDescDefault["added"]

    fun hasActive(): Boolean = stats.waiting > 0 || stats.running > 0
}

// ---------------------------------------------------------------- 数值格式化

/** 把字节数格式化成 "2.4 MB"（与 `task_queue.format_size` 一致）。 */
fun formatSize(value: Double): String {
    var size = if (value.isFinite() && value > 0) value else 0.0
    if (size < 1024) return "%.0f B".format(size)
    for (unit in listOf("KB", "MB")) {
        size /= 1024.0
        if (size < 1024) return "%.1f %s".format(size, unit)
    }
    return "%.1f GB".format(size / 1024.0)
}

/** 把秒数格式化成 mm:ss / hh:mm:ss（与 `task_queue.format_duration` 一致）。 */
fun formatDuration(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds < 0) return "--:--"
    val total = seconds.toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

// ---------------------------------------------------------------- 提示行

/** 与探索页一致的状态条配色。 */
@Composable
fun statusColor(kind: StatusKind): Color = when (kind) {
    StatusKind.OK -> Color(0xFF27AE60)
    StatusKind.ERR -> Color(0xFFC0392B)
    StatusKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 通用的状态提示行：文案用「资源 id + 可选参数」表达，切语言后自动跟随。
 *
 * 下载页与任务页共用；探索页自有一套同样的实现，不改动它以免影响切片 1。
 */
@Composable
fun StatusLine(res: Int, arg: String?, kind: StatusKind, modifier: Modifier = Modifier) {
    Text(
        text = if (arg != null) stringResource(res, arg) else stringResource(res),
        color = statusColor(kind),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}