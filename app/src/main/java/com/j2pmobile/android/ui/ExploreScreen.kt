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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.j2pmobile.android.AlbumItem
import com.j2pmobile.android.ApiBridge
import com.j2pmobile.android.ApiResult
import com.j2pmobile.android.R
import com.j2pmobile.android.ui.components.AlbumCard
import kotlinx.coroutines.launch

/** 界面每页条数（库每页 80 条，4 个界面页共用一个库页），与桌面版 PAGE_SIZE 一致。 */
const val EXPLORE_PAGE_SIZE = 20

private const val MODE_ALL = "all"
private const val SORT_LATEST = "latest"
private const val TIME_ALL = "all"

// 与 core.explore 的三张表保持一致（顺序即菜单顺序）
private val SEARCH_MODES = listOf("all", "work", "author", "tag", "actor")
private val SORT_MODES = listOf("latest", "views", "pictures", "likes")
private val TIME_MODES = listOf("today", "week", "month", "all")

/** 状态条语气，对应桌面版 COLOR_OK / COLOR_ERR / COLOR_IDLE。 */
enum class StatusKind { OK, ERR, IDLE }

/**
 * 探索页的可变状态。
 *
 * 由 [AppShell] 持有而不是页面内部 remember：进本子详情页再返回时，搜索结果仍然保留
 * （等价于桌面版把 ExplorePage 实例缓存起来的行为）。
 */
class ExploreState {
    /** 输入框里的文字（未点搜索前也在）。 */
    var keywordInput by mutableStateOf("")

    /** 上次搜索用的关键词；翻页 / 换排序时沿用它。 */
    var keyword by mutableStateOf("")

    var mode by mutableStateOf(MODE_ALL)
    var sort by mutableStateOf(SORT_LATEST)
    var time by mutableStateOf(TIME_ALL)
    var uiPage by mutableStateOf(1)
    var total by mutableStateOf(0)
    var items by mutableStateOf<List<AlbumItem>>(emptyList())
    var searching by mutableStateOf(false)

    /** 是否已发起过首次搜索：决定搜索区「居中态」还是「置顶态」，此后不再变化。 */
    var searchedOnce by mutableStateOf(false)

    /** 提示语用「资源 id + 可选参数」存，语言切换后自动跟着变。 */
    var hintRes by mutableStateOf(R.string.explore_hint)
    var hintArg by mutableStateOf<String?>(null)
    var hintVisible by mutableStateOf(true)

    var statusRes by mutableStateOf(R.string.status_ready)
    var statusKind by mutableStateOf(StatusKind.IDLE)

    /** 加载序号：点「返回」后作废还在跑的搜索，避免旧结果写回界面。 */
    var loadEpoch by mutableStateOf(0)

    val totalPages: Int get() = maxOf(1, (total + EXPLORE_PAGE_SIZE - 1) / EXPLORE_PAGE_SIZE)

    /**
     * 清空搜索结果、回到开屏态（设置页「清除缓存」用；等价于搜索区的返回按钮）。
     */
    fun reset() {
        loadEpoch += 1
        searching = false
        keywordInput = ""
        keyword = ""
        items = emptyList()
        total = 0
        uiPage = 1
        hintRes = R.string.explore_hint
        hintArg = null
        hintVisible = true
        searchedOnce = false
        statusRes = R.string.status_ready
        statusKind = StatusKind.IDLE
    }
}

/**
 * 探索页（首页）：按关键词搜索本子，网格展示封面与名称。
 *
 * 与桌面版一致的交互：
 * - 搜索方式（全部 / 作品 / 作者 / 标签 / 角色）、排序（最新 / 观看数 / 图片数 / 点赞数）、
 *   日期筛选（今天 / 本周 / 本月 / 全部），且**排序与日期筛选互斥**；
 * - 每页 20 条，上下各一个翻页工具；
 * - 首次搜索后由「居中态」切到「置顶态」，返回按钮清空搜索回到居中态。
 *
 * 与桌面版不同的两处（第 4 步既定调整）：每行固定 3 本；点击封面不再放大，
 * 点击封面或名称都直接进详情页。
 */
@Composable
fun ExploreScreen(
    state: ExploreState,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    /** 回到开屏态：清空关键词与结果，作废还在跑的搜索。 */
    fun resetSearch() {
        state.loadEpoch += 1
        state.searching = false
        state.keywordInput = ""
        state.keyword = ""
        state.items = emptyList()
        state.total = 0
        state.uiPage = 1
        state.hintRes = R.string.explore_hint
        state.hintArg = null
        state.hintVisible = true
        state.searchedOnce = false
        state.statusRes = R.string.status_ready
        state.statusKind = StatusKind.IDLE
    }

    /** 加载指定界面页（沿用当前关键词 / 方式 / 排序 / 时间）。 */
    fun startLoad(page: Int) {
        if (state.searching) return
        val target = page.coerceIn(1, state.totalPages)
        state.searching = true
        state.loadEpoch += 1
        val epoch = state.loadEpoch
        state.items = emptyList()
        state.uiPage = target
        state.hintRes = R.string.status_searching
        state.hintArg = null
        state.hintVisible = true
        state.statusRes = R.string.status_searching
        state.statusKind = StatusKind.IDLE
        // 首次点击搜索即切到置顶布局，与搜索结果无关；此后不再变化
        if (!state.searchedOnce) state.searchedOnce = true

        scope.launch {
            val result = ApiBridge.exploreSearch(state.mode, state.keyword, target, state.sort, state.time)
            if (epoch != state.loadEpoch) return@launch   // 期间点了「返回」：本次作废
            state.searching = false
            when (result) {
                is ApiResult.Err -> {
                    state.hintRes = R.string.explore_search_failed
                    state.hintArg = result.message
                    state.hintVisible = true
                    state.statusRes = R.string.status_search_failed
                    state.statusKind = StatusKind.ERR
                }

                is ApiResult.Ok -> {
                    state.total = result.value.total
                    state.items = result.value.items
                    state.statusRes = R.string.status_search_done
                    if (result.value.items.isEmpty()) {
                        state.hintRes = R.string.explore_no_result
                        state.hintArg = null
                        state.hintVisible = true
                        state.statusKind = StatusKind.IDLE
                    } else {
                        state.hintVisible = false
                        state.statusKind = StatusKind.OK
                    }
                }
            }
        }
    }

    /** 按输入框内容重新搜索（回到第 1 页）。 */
    fun doSearch() {
        val keyword = state.keywordInput.trim()
        if (keyword.isEmpty()) {
            state.hintRes = R.string.status_search_need_keyword
            state.hintArg = null
            state.hintVisible = true
            state.statusRes = R.string.status_search_need_keyword
            state.statusKind = StatusKind.ERR
            return
        }
        state.keyword = keyword
        state.total = 0
        state.uiPage = 1
        startLoad(1)
    }

    /** 选排序：与日期筛选互斥（换排序即取消日期筛选）。 */
    fun selectSort(key: String) {
        state.sort = key
        state.time = TIME_ALL
        if (state.keyword.isNotEmpty()) {
            state.total = 0
            startLoad(1)
        }
    }

    /** 确认日期筛选：固定基于「最新」排序。 */
    fun applyTime(value: String) {
        state.time = value
        if (value != TIME_ALL) state.sort = SORT_LATEST
        if (state.keyword.isNotEmpty()) {
            state.total = 0
            startLoad(1)
        }
    }

    var showDateDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (!state.searchedOnce) {
            // 居中态：欢迎语 + 搜索框 + 指引整体居中
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.home_welcome),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            SearchBar(state, onSearch = { doSearch() })
            HintLine(state)
            StatusLine(state)
            Spacer(Modifier.weight(1f))
        } else {
            // 置顶态：返回（清空搜索）+ 搜索框 + 翻页/排序 + 网格
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { resetSearch() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.btn_back),
                    )
                }
            }
            SearchBar(state, onSearch = { doSearch() })
            PagerRow(
                state = state,
                showSort = true,
                onPrev = { startLoad(state.uiPage - 1) },
                onNext = { startLoad(state.uiPage + 1) },
                onSort = { selectSort(it) },
                onOpenDate = { showDateDialog = true },
            )
            HintLine(state)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    AlbumCard(item = item, onClick = { onOpenAlbum(item.id) })
                }
            }
            PagerRow(
                state = state,
                showSort = false,
                onPrev = { startLoad(state.uiPage - 1) },
                onNext = { startLoad(state.uiPage + 1) },
                onSort = {},
                onOpenDate = {},
            )
            StatusLine(state)
        }
    }

    if (showDateDialog) {
        DateFilterDialog(
            current = state.time,
            onDismiss = { showDateDialog = false },
            onConfirm = { value ->
                showDateDialog = false
                applyTime(value)
            },
        )
    }
}

@Composable
private fun SearchBar(state: ExploreState, onSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSelector(state)
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
private fun ModeSelector(state: ExploreState) {
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
private fun SortMenu(state: ExploreState, onSort: (String) -> Unit, onOpenDate: () -> Unit) {
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

@Composable
private fun PagerRow(
    state: ExploreState,
    showSort: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSort: (String) -> Unit,
    onOpenDate: () -> Unit,
) {
    // 有结果时才出现，搜索过程中不提前露出
    if (state.keyword.isEmpty() || state.total <= 0) return
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
        if (showSort) SortMenu(state, onSort, onOpenDate)
    }
}

@Composable
private fun HintLine(state: ExploreState) {
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
private fun StatusLine(state: ExploreState) {
    val color = when (state.statusKind) {
        StatusKind.OK -> Color(0xFF27AE60)
        StatusKind.ERR -> Color(0xFFC0392B)
        StatusKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = stringResource(state.statusRes),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

@Composable
private fun DateFilterDialog(
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

private fun modeLabelRes(key: String): Int = when (key) {
    "work" -> R.string.explore_mode_work
    "author" -> R.string.explore_mode_author
    "tag" -> R.string.explore_mode_tag
    "actor" -> R.string.explore_mode_actor
    else -> R.string.explore_mode_all
}

private fun sortLabelRes(key: String): Int = when (key) {
    "views" -> R.string.explore_sort_views
    "pictures" -> R.string.explore_sort_pictures
    "likes" -> R.string.explore_sort_likes
    else -> R.string.explore_sort_latest
}

private fun timeLabelRes(key: String): Int = when (key) {
    "today" -> R.string.explore_time_today
    "week" -> R.string.explore_time_week
    "month" -> R.string.explore_time_month
    else -> R.string.explore_time_all
}