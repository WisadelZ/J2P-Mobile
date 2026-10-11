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

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.j2pmobile.android.R

/**
 * 搜索相关界面的可复用控件（探索页 / 独立搜索页 / 排行榜 / 浏览历史共用）。
 *
 * 各个页面各持一份实现 [SearchUiState] 的状态对象，控件只依赖这一层接口，
 * 因此新增页面不必复制搜索框、翻页工具与提示条。
 */

/** 状态条语气，对应桌面版 COLOR_OK / COLOR_ERR / COLOR_IDLE。 */
enum class StatusKind { OK, ERR, IDLE }

/**
 * 搜索类界面共用的状态。
 *
 * 只声明控件真正读写的字段；各页面自己的私有状态（结果列表、加载序号等）留在各自实现里。
 */
interface SearchUiState {
    var keywordInput: String
    var keyword: String
    var mode: String
    var sort: String
    var time: String
    var uiPage: Int
    var total: Int
    var searching: Boolean
    var hintRes: Int
    var hintArg: String?
    var hintVisible: Boolean
    var statusRes: Int
    var statusKind: StatusKind
    val totalPages: Int
}

// 与 core.explore 的三张表保持一致（顺序即菜单顺序）
internal const val MODE_ALL = "all"
internal const val SORT_LATEST = "latest"
internal const val TIME_ALL = "all"

internal val SEARCH_MODES = listOf("all", "work", "author", "tag", "actor")
internal val SORT_MODES = listOf("latest", "views", "pictures", "likes")
internal val TIME_MODES = listOf("today", "week", "month", "all")

/** 搜索框：[showMode] 为假时不显示左侧的搜索方式选择（浏览历史用）。 */
@Composable
internal fun SearchBar(state: SearchUiState, onSearch: () -> Unit, showMode: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showMode) SearchModeSelector(state)
        TextField(
            value = state.keywordInput,
            onValueChange = { state.keywordInput = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = {
                Text(
                    text = stringResource(R.string.explore_search_hint),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        IconButton(onClick = { state.keywordInput = "" }, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.btn_clear),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onSearch, enabled = !state.searching, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = stringResource(R.string.btn_search),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun SearchModeSelector(state: SearchUiState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(modeLabelRes(state.mode)),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SEARCH_MODES.forEach { key ->
                DropdownMenuItem(
                    text = { Text(stringResource(modeLabelRes(key))) },
                    onClick = {
                        state.mode = key
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SearchSortMenu(state: SearchUiState, onSort: (String) -> Unit, onOpenDate: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // 设了日期筛选就显示筛选方式，否则显示排序方式（与桌面版 sort_label_text 一致）
                text = stringResource(
                    if (state.time != TIME_ALL) timeLabelRes(state.time) else sortLabelRes(state.sort)
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SORT_MODES.forEach { key ->
                DropdownMenuItem(
                    text = { Text(stringResource(sortLabelRes(key))) },
                    trailingIcon = {
                        // 勾选标在当前排序方式上：按钮显示日期筛选时，也能看出基于哪种排序
                        if (key == state.sort) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSort(key)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.explore_sort_date)) },
                onClick = {
                    expanded = false
                    onOpenDate()
                },
            )
        }
    }
}

/** 翻页工具：[visible] 由调用方决定是否露出（有结果时才出现）。 */
@Composable
internal fun SearchPagerRow(
    state: SearchUiState,
    visible: Boolean,
    showSort: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSort: (String) -> Unit,
    onOpenDate: () -> Unit,
) {
    if (!visible) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = !state.searching && state.uiPage > 1) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_left),
                contentDescription = stringResource(R.string.btn_prev_page),
            )
        }
        Text(
            text = stringResource(
                R.string.explore_page_info,
                state.uiPage,
                state.totalPages,
                state.total,
            ),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, enabled = !state.searching && state.uiPage < state.totalPages) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.btn_next_page),
            )
        }
        if (showSort) SearchSortMenu(state, onSort, onOpenDate)
    }
}

@Composable
internal fun SearchHintLine(state: SearchUiState) {
    if (!state.hintVisible) return
    val arg = state.hintArg
    Text(
        text = if (arg != null) stringResource(state.hintRes, arg) else stringResource(state.hintRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
internal fun SearchDateFilterDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explore_sort_date)) },
        text = {
            Column {
                TIME_MODES.forEach { key ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = key }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == key, onClick = { selected = key })
                        Text(
                            text = stringResource(timeLabelRes(key)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

internal fun modeLabelRes(key: String): Int = when (key) {
    "work" -> R.string.explore_mode_work
    "author" -> R.string.explore_mode_author
    "tag" -> R.string.explore_mode_tag
    "actor" -> R.string.explore_mode_actor
    else -> R.string.explore_mode_all
}

internal fun sortLabelRes(key: String): Int = when (key) {
    "views" -> R.string.explore_sort_views
    "pictures" -> R.string.explore_sort_pictures
    "likes" -> R.string.explore_sort_likes
    else -> R.string.explore_sort_latest
}

internal fun timeLabelRes(key: String): Int = when (key) {
    "today" -> R.string.explore_time_today
    "week" -> R.string.explore_time_week
    "month" -> R.string.explore_time_month
    else -> R.string.explore_time_all
}