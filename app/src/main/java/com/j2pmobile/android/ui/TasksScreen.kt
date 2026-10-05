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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.LogBridge
import com.j2pmobile.android.R
import com.j2pmobile.android.TaskItem
import com.j2pmobile.android.TaskStatus
import kotlinx.coroutines.launch

/**
 * 任务中心：队列里每个任务的状态、页数进度、速度与剩余时间，以及逐条 / 整队的操作。
 *
 * 与桌面版一致：搜索（本子 ID / 名称）、排序（加入顺序 / 本子 ID / 名称 / 进度 + 升降序 +
 * 取消排序）、状态筛选、计数、以及按状态给出的操作按钮。
 *
 * 安卓端把桌面版顶栏上的三个队列级操作（暂停全部 / 继续全部 / 清空已完成）放到了
 * 搜索栏上方的一行图标里 —— 顶栏在外壳层，这里放能省一层耦合。
 */
@Composable
fun TasksScreen(
    queue: QueueState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var cancelTarget by remember { mutableStateOf<TaskItem?>(null) }

    /** 执行一个队列操作，刷新快照并给出提示。 */
    fun control(action: String, taskId: String, statusRes: Int, arg: String?, kind: StatusKind) {
        scope.launch {
            when (val result = ApiBridge.queueControl(action, taskId)) {
                is ApiResult.Ok -> {
                    queue.setStatus(statusRes, kind, arg)
                }

                is ApiResult.Err -> {
                    queue.setStatus(R.string.status_error_hint, StatusKind.ERR, result.message)
                    LogBridge.append("ERR", result.message)
                }
            }
            queue.refresh()
        }
    }

    /** 清空已完成：提示里带上真正移除的条数。 */
    fun clearFinished() {
        scope.launch {
            when (val result = ApiBridge.queueControl("clear_finished")) {
                is ApiResult.Ok -> {
                    if (result.value.removed > 0) {
                        queue.setStatus(
                            R.string.status_task_removed_count,
                            StatusKind.IDLE,
                            result.value.removed.toString(),
                        )
                    }
                }

                is ApiResult.Err -> {
                    queue.setStatus(R.string.status_error_hint, StatusKind.ERR, result.message)
                    LogBridge.append("ERR", result.message)
                }
            }
            queue.refresh()
        }
    }

    /** 打开任务产物目录。 */
    fun openFolder(task: TaskItem) {
        scope.launch {
            when (val result = ApiBridge.openPath(task.outputDir)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Err -> queue.setStatus(
                    R.string.status_open_failed, StatusKind.ERR, result.message
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        // ---------------- 队列级操作 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val stats = queue.stats
            IconButton(
                onClick = {
                    control(
                        "pause_all", "", R.string.status_queue_paused, null, StatusKind.IDLE
                    )
                },
                enabled = stats.waiting > 0 || stats.running > 0,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_pause),
                    contentDescription = stringResource(R.string.btn_pause_all),
                )
            }
            IconButton(
                onClick = {
                    control(
                        "resume_all", "", R.string.status_queue_resumed, null, StatusKind.OK
                    )
                },
                // 队列处于暂停态时也要能点：否则新加入的任务会永远停在「等待中」
                enabled = stats.paused > 0 || queue.paused,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = stringResource(R.string.btn_resume_all),
                )
            }
            IconButton(
                onClick = { clearFinished() },
                enabled = stats.done > 0 || stats.canceled > 0,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_clear_all),
                    contentDescription = stringResource(R.string.btn_clear_done),
                )
            }
        }

        // ---------------- 搜索栏 ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = queue.keywordInput,
                onValueChange = { queue.keywordInput = it },
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.task_search_hint),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    queue.keyword = queue.keywordInput.trim()
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(onClick = {
                queue.keywordInput = ""
                queue.keyword = ""
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.btn_clear),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { queue.keyword = queue.keywordInput.trim() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = stringResource(R.string.btn_search),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ---------------- 排序 / 筛选 / 计数 ----------------
        TaskToolbar(queue)

        HorizontalDivider()

        // ---------------- 任务列表 ----------------
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (queue.tasks.isEmpty()) {
                Text(
                    // 区分「队列本来就是空的」与「只是没匹配上筛选条件」
                    text = if (queue.keyword.isNotEmpty() ||
                        queue.statusFilter != TaskStatus.FILTER_ALL
                    ) {
                        stringResource(R.string.tasks_no_match)
                    } else {
                        stringResource(R.string.tasks_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(queue.tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onPause = {
                                control(
                                    "pause", task.id, R.string.status_task_paused,
                                    task.albumId, StatusKind.IDLE,
                                )
                            },
                            onResume = {
                                control(
                                    "resume", task.id, R.string.status_task_resumed,
                                    task.albumId, StatusKind.OK,
                                )
                            },
                            onRetry = {
                                control(
                                    "retry", task.id, R.string.status_task_retried,
                                    task.albumId, StatusKind.OK,
                                )
                            },
                            onRemove = {
                                control(
                                    "remove", task.id, R.string.status_task_removed,
                                    task.albumId, StatusKind.IDLE,
                                )
                            },
                            onCancel = {
                                // 还没开始下就没有东西可清，不必确认
                                if (task.status == TaskStatus.WAITING) {
                                    control(
                                        "cancel", task.id, R.string.status_task_canceled,
                                        task.albumId, StatusKind.IDLE,
                                    )
                                } else {
                                    cancelTarget = task
                                }
                            },
                            onOpenFolder = { openFolder(task) },
                        )
                    }
                }
            }
        }

        StatusLine(queue.statusRes, queue.statusArg, queue.statusKind)
    }

    // 取消确认：会清掉本轮已下载的图片与 PDF
    cancelTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text(stringResource(R.string.task_confirm_cancel_title)) },
            text = { Text(stringResource(R.string.task_confirm_cancel_body, task.albumId)) },
            confirmButton = {
                TextButton(onClick = {
                    cancelTarget = null
                    control(
                        "cancel", task.id, R.string.status_task_canceled,
                        task.albumId, StatusKind.IDLE,
                    )
                }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelTarget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/** 排序菜单 + 升降序 + 取消排序 + 状态筛选 + 计数。 */
@Composable
private fun TaskToolbar(queue: QueueState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var sortExpanded by remember { mutableStateOf(false) }
        Box {
            ToolBox(onClick = { sortExpanded = true }) {
                Text(
                    text = stringResource(sortLabelRes(queue.sortKey)),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                TaskStatus.sortKeys.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabelRes(key))) },
                        trailingIcon = {
                            if (key == queue.sortKey) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = {
                            sortExpanded = false
                            if (key != queue.sortKey) {
                                queue.sortKey = key
                                queue.sortDesc = TaskStatus.sortDescDefault[key] ?: false
                            }
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = { queue.sortDesc = !queue.sortDesc },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (queue.sortDesc) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward
                ),
                contentDescription = stringResource(
                    if (queue.sortDesc) R.string.btn_order_to_asc else R.string.btn_order_to_desc
                ),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = {
                queue.sortKey = "added"
                queue.sortDesc = false
            },
            enabled = !queue.isDefaultSort(),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_restart),
                contentDescription = stringResource(R.string.btn_sort_reset),
                modifier = Modifier.size(18.dp),
            )
        }

        var filterExpanded by remember { mutableStateOf(false) }
        Box {
            ToolBox(onClick = { filterExpanded = true }) {
                Text(
                    text = filterLabelText(queue.statusFilter),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = filterExpanded, onDismissRequest = { filterExpanded = false }) {
                (listOf(TaskStatus.FILTER_ALL) + TaskStatus.filterable).forEach { key ->
                    DropdownMenuItem(
                        text = { Text(filterLabelText(key)) },
                        trailingIcon = {
                            if (key == queue.statusFilter) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = {
                            filterExpanded = false
                            queue.statusFilter = key
                        },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = if (queue.keyword.isNotEmpty() || queue.statusFilter != TaskStatus.FILTER_ALL) {
                stringResource(R.string.tasks_count_filtered, queue.shown, queue.total)
            } else {
                stringResource(R.string.tasks_count, queue.total)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 工具控件统一的小方框（描边 + 圆角，与探索页一致）。 */
@Composable
private fun ToolBox(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** 一张任务卡片：序号 + 名称 + 状态、进度条、详情行与操作按钮。 */
@Composable
private fun TaskCard(
    task: TaskItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#%04d".format(task.seq),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = task.name.ifEmpty { task.albumId },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(statusLabelRes(task.status)),
                style = MaterialTheme.typography.labelSmall,
                color = statusLabelColor(task.status),
                maxLines = 1,
            )
        }

        TaskProgressBar(task)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = detailText(task),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    TaskStatus.WAITING -> {}
                    TaskStatus.RUNNING -> IconAction(
                        R.drawable.ic_pause, R.string.btn_pause, onPause
                    )

                    TaskStatus.PAUSED -> IconAction(
                        R.drawable.ic_play, R.string.btn_resume, onResume
                    )

                    TaskStatus.FAILED, TaskStatus.CANCELED -> {
                        IconAction(R.drawable.ic_refresh, R.string.btn_retry, onRetry)
                        IconAction(R.drawable.ic_delete, R.string.btn_remove, onRemove)
                    }

                    else -> {
                        IconAction(R.drawable.ic_folder, R.string.btn_open_folder, onOpenFolder)
                        IconAction(R.drawable.ic_delete, R.string.btn_remove, onRemove)
                    }
                }
                when (task.status) {
                    TaskStatus.WAITING, TaskStatus.RUNNING, TaskStatus.PAUSED ->
                        IconAction(R.drawable.ic_close, R.string.btn_cancel_task, onCancel)

                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun IconAction(iconRes: Int, labelRes: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(labelRes),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 进度条（自绘，避免不同 Material3 版本里 `LinearProgressIndicator` 参数签名不一致）。
 *
 * 取值规则与桌面版一致：只有「下载中且总页数未知」用半透明的整体填充表示在跑，
 * 其余静止状态一律 0；`progress` 为 null 且非下载中时视为 0。
 */
@Composable
private fun TaskProgressBar(task: TaskItem) {
    val unknown = task.status == TaskStatus.RUNNING && task.pagesTotal == 0
    val fraction = when {
        unknown -> 1f
        task.progress != null -> task.progress.coerceIn(0f, 1f)
        task.status == TaskStatus.DONE -> 1f
        else -> 0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(
                    if (unknown) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
        )
    }
}

/** 详情行：进度 / 速度 / 剩余时间，或失败原因（与桌面版 `_detail_text` 一致）。 */
@Composable
private fun detailText(task: TaskItem): String {
    if (task.status == TaskStatus.FAILED) {
        return task.error.ifEmpty { stringResource(R.string.status_error_hint, "") }
    }
    val parts = mutableListOf<String>()
    when (task.status) {
        TaskStatus.WAITING -> {
            parts.add(stringResource(R.string.task_status_waiting))
            if (task.pagesTotal > 0) {
                parts.add(stringResource(R.string.task_pages_total, task.pagesTotal))
            }
        }

        TaskStatus.RUNNING -> {
            parts.add(pagesText(task))
            if (task.byteRate > 0) {
                parts.add(stringResource(R.string.task_speed, formatSize(task.byteRate)))
            }
            parts.add(
                if (task.eta != null) {
                    stringResource(R.string.task_eta, formatDuration(task.eta))
                } else {
                    stringResource(R.string.task_eta_unknown)
                }
            )
        }

        else -> {
            parts.add(pagesText(task))
            if (task.status == TaskStatus.DONE && task.pdfCount > 0) {
                parts.add(stringResource(R.string.task_pdf_count, task.pdfCount))
            }
        }
    }
    // 刚暂停 / 取消时上一轮可能还在收尾（已发出的请求没法瞬间掐断），如实说明
    if (task.inflight &&
        (task.status == TaskStatus.PAUSED || task.status == TaskStatus.WAITING)
    ) {
        parts.add(stringResource(R.string.task_wrap_up))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun pagesText(task: TaskItem): String {
    if (task.pagesTotal <= 0) return stringResource(R.string.task_pages_unknown)
    return stringResource(
        R.string.task_pages_progress,
        minOf(task.pagesDone, task.pagesTotal),
        task.pagesTotal,
    )
}

@Composable
private fun statusLabelColor(status: String): Color = when (status) {
    TaskStatus.RUNNING, TaskStatus.DONE -> Color(0xFF27AE60)
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusLabelRes(status: String): Int = when (status) {
    TaskStatus.RUNNING -> R.string.task_status_running
    TaskStatus.PAUSED -> R.string.task_status_paused
    TaskStatus.DONE -> R.string.task_status_done
    TaskStatus.FAILED -> R.string.task_status_failed
    TaskStatus.CANCELED -> R.string.task_status_canceled
    else -> R.string.task_status_waiting
}

private fun sortLabelRes(key: String): Int = when (key) {
    "id" -> R.string.task_sort_id
    "name" -> R.string.task_sort_name
    "progress" -> R.string.task_sort_progress
    else -> R.string.task_sort_added
}

@Composable
private fun filterLabelText(key: String): String =
    if (key == TaskStatus.FILTER_ALL) {
        stringResource(R.string.task_filter_all)
    } else {
        stringResource(statusLabelRes(key))
    }